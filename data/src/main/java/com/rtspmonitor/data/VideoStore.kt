package com.rtspmonitor.data
import android.content.*
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.rtspmonitor.core.*
import com.rtspmonitor.core.inspectMp4Structure
import com.rtspmonitor.core.requireRecordingSpace
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class VideoStore(private val context: Context, private val dao: MonitorDao) {
 private val exportLock=Mutex()
 val temporary = File(context.noBackupFilesDir, "recordings").apply { mkdirs() }
 fun checkSpace() { requireRecordingSpace(temporary.usableSpace) }
 fun recoverTemporary(file: File) {
  check(file.extension == "part" || file.extension == "mp4")
  val structure = inspectMp4Structure(file)
  if(!structure.complete) {
   check(file.extension == "part" && structure.recoverableLength > 0) { "录像没有可恢复的完整分片" }
   java.io.RandomAccessFile(file,"rw").use { it.setLength(structure.recoverableLength); it.fd.sync() }
  }
  validate(file)
  if(!structure.complete) {
   val sidecar=File(file.parentFile,file.nameWithoutExtension+".meta")
   if(sidecar.exists() || File(sidecar.path+".bak").exists()) {
    val atomic=android.util.AtomicFile(sidecar)
    val data=org.json.JSONObject(atomic.readFully().toString(Charsets.UTF_8))
    val extractor=MediaExtractor()
    var end=0L;var previous=-1L;var interval=0L
    try {
     extractor.setDataSource(file.path)
     val track=(0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true }
     extractor.selectTrack(track)
     do { val pts=extractor.sampleTime; if(pts>previous && previous>=0)interval=pts-previous; previous=pts;end=maxOf(end,pts) } while(extractor.advance())
    } finally { extractor.release() }
    data.put("endedAt",minOf(data.getLong("endedAt"),data.getLong("startedAt")+(end+interval)/1000))
    val output=atomic.startWrite()
    try { output.write(data.toString().toByteArray());atomic.finishWrite(output) } catch(e:Throwable) { atomic.failWrite(output);throw e }
   }
  }
 }
 fun validate(file: File) {
  check(file.length() > 0) { "空录像文件" }
  check(inspectMp4Structure(file).complete) { "MP4容器未完整收尾" }
  val extractor = MediaExtractor()
  try {
   extractor.setDataSource(file.absolutePath)
   val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) in listOf("video/avc", "video/hevc") } ?: error("缺少 H.264/H.265 视频轨道")
   extractor.selectTrack(track)
   check(extractor.sampleTime >= 0 && extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) { "录像缺少有效首关键帧" }
   var buffer = java.nio.ByteBuffer.allocate(64 * 1024)
   do {
    val size = extractor.sampleSize
    // shortcut: cap samples at 32 MiB; raise only for validated higher-bitrate camera streams.
    check(size in 1..(32L * 1024 * 1024)) { "录像样本大小无效或超过32 MiB" }
    if(size > buffer.capacity()) buffer = java.nio.ByteBuffer.allocate(size.toInt())
    buffer.clear()
    check(extractor.readSampleData(buffer, 0).toLong() == size) { "录像样本不完整" }
   } while(extractor.advance())
  } finally { extractor.release() }
 }
 suspend fun publish(file: File, camera: Camera) {
  // Finish legacy exports in place so upgrades do not orphan pending videos.
  if(File(file.path + ".uri").exists()) { publishLegacy(file,camera);return }
  validate(file);checkSpace()
  val directory=File(context.noBackupFilesDir,"videos").apply { mkdirs() }
  val metaFile=File(file.parentFile,file.nameWithoutExtension+".meta")
  val meta=if(metaFile.exists() || File(metaFile.path+".bak").exists()) org.json.JSONObject(android.util.AtomicFile(metaFile).readFully().toString(Charsets.UTF_8)) else null
  val start=meta?.getLong("startedAt")
  val end=meta?.getLong("endedAt")
  val offset=meta?.getInt("offsetMinutes")
  val sources=meta?.getInt("sources") ?: 0
  val base=if(start!=null && end!=null && offset!=null) recordingRangeName(SegmentMetadata(start,end,offset,sources)) else "时间未知_${UUID.randomUUID()}"
  var name="$base.mp4"
  var sequence=1
  while(File(directory,name).exists() || File(directory,name+".local-index").exists()) { name="${base}_${sequence++}.mp4" }
  val target=File(directory,name)
  val index=File(target.path+".local-index")
  val created=System.currentTimeMillis()
  index.writeText(org.json.JSONObject().put("cameraId",camera.id).put("name",name).put("createdAt",created).put("startedAt",start).put("endedAt",end).put("offsetMinutes",offset).put("sources",sources).toString())
  check(file.renameTo(target)) { "无法保存录像，临时文件已保留" }
  try { dao.save(Recording(Uri.fromFile(target).toString(),camera.id,name,created,startedAt=start,endedAt=end,offsetMinutes=offset,sourceMask=sources));index.delete();metaFile.delete() }
  catch(e:Throwable) { if(target.renameTo(file))index.delete();throw e }
 }
 suspend fun export(item:Recording): String = exportLock.withLock { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
  val recording=dao.recordings().first().firstOrNull { it.uri==item.uri } ?: error("录像不存在")
  if(Uri.parse(recording.uri).scheme!="file") return@withContext recording.uri
  val file=File(checkNotNull(Uri.parse(recording.uri).path))
  require(file.canonicalFile.parentFile==File(context.noBackupFilesDir,"videos").canonicalFile)
  validate(file);requireRecordingSpace(temporary.usableSpace,file.length())
  val resolver=context.contentResolver
  val journal=File(file.path+".export-uri")
  var uri:Uri?=recording.exportedUri?.let { Uri.parse(it) }
  fun exists(value:Uri):Boolean=runCatching { resolver.query(value,arrayOf(MediaStore.MediaColumns._ID),null,null,null)?.use { it.moveToFirst() }==true }.getOrDefault(false)
  if(uri!=null && exists(uri)) return@withContext uri.toString()
  uri=if(journal.exists()) Uri.parse(journal.readText()).takeIf { exists(it) } else null
  if(uri==null) {
   uri=checkNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
    put(MediaStore.MediaColumns.DISPLAY_NAME,recording.name);put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4")
    put(MediaStore.MediaColumns.RELATIVE_PATH,"DCIM/RTSPMonitor/");put(MediaStore.MediaColumns.IS_PENDING,1)
   }))
   journal.writeText(uri.toString())
  }
  resolver.openOutputStream(uri,"rwt")!!.use { out -> file.inputStream().use { it.copyTo(out) } }
  check(resolver.update(uri,ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null)==1)
  dao.save(recording.copy(exportedUri=uri.toString()));journal.delete();uri.toString()
 } }
 private suspend fun publishLegacy(file: File, camera: Camera) {

  validate(file)
  checkSpace()
  requireRecordingSpace(temporary.usableSpace, file.length())
  val resolver = context.contentResolver
  val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(file.lastModified()))
  val sequence = synchronized(this) {
   val prefs = context.getSharedPreferences("video-sequence", Context.MODE_PRIVATE)
   val next = prefs.getLong("next", 0L) + 1L
   check(prefs.edit().putLong("next", next).commit())
   next
  }
  val name = "${safeCameraName(camera.name)}_${stamp}_${sequence}.mp4"
  // Persist the URI before copying so recovery can find pending rows after process death.
  val journal = File(file.path + ".uri")
  val uri = if (journal.exists()) Uri.parse(journal.readText()) else {
   resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
    put(MediaStore.Video.Media.DISPLAY_NAME, name); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/RTSPMonitor/"); put(MediaStore.Video.Media.IS_PENDING, 1)
   }) ?: error("无法创建 MediaStore 文件")
  }.also { journal.writeText(it.toString()) }
  check(resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 1) }, null, null) == 1)
  resolver.openOutputStream(uri, "rwt")!!.use { output -> file.inputStream().use { it.copyTo(output) } }
  check(resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) == 1)
  val actualName = resolver.query(uri, arrayOf(MediaStore.Video.Media.DISPLAY_NAME), null, null, null)?.use { cursor -> if(cursor.moveToFirst()) cursor.getString(0) else name } ?: name
  dao.save(Recording(uri.toString(), camera.id, actualName, file.lastModified()))
  file.delete(); journal.delete()
 }
 @Suppress("DEPRECATION")
 suspend fun reconcile() {
  File(context.noBackupFilesDir,"videos").listFiles()?.filter { it.name.endsWith(".local-index") }?.forEach { index ->
   val file=File(index.path.removeSuffix(".local-index"))
   if(file.exists()) {
    val p=org.json.JSONObject(index.readText());validate(file)
    dao.save(Recording(Uri.fromFile(file).toString(),p.getString("cameraId"),p.getString("name"),p.getLong("createdAt"),startedAt=if(p.isNull("startedAt")) null else p.getLong("startedAt"),endedAt=if(p.isNull("endedAt")) null else p.getLong("endedAt"),offsetMinutes=if(p.isNull("offsetMinutes")) null else p.getInt("offsetMinutes"),sourceMask=p.optInt("sources")))
   }
   index.delete()
  }
  val resolver = context.contentResolver
  val references = temporary.walkTopDown().filter { it.isFile && it.extension == "uri" && File(it.path.removeSuffix(".uri")).exists() }.map { it.readText() }.toSet()
  val collection = MediaStore.setIncludePending(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
  resolver.query(collection, arrayOf(MediaStore.Video.Media._ID), "${MediaStore.Video.Media.IS_PENDING}=1 AND ${MediaStore.Video.Media.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?", arrayOf("Movies/RTSPMonitor/", context.packageName), null)?.use { cursor ->
   while(cursor.moveToNext()) {
    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
    if(uri.toString() !in references) resolver.delete(uri, null, null)
   }
  }
  dao.recordings().first().forEach { item ->
   val exists = if(Uri.parse(item.uri).scheme=="file") File(checkNotNull(Uri.parse(item.uri).path)).exists() else resolver.query(Uri.parse(item.uri), arrayOf(MediaStore.Video.Media._ID), null, null, null)?.use { it.moveToFirst() } ?: false
   if(!exists) dao.delete(item)
   else if(Uri.parse(item.uri).scheme=="file" && File(Uri.parse(item.uri).path+".export-uri").exists()) runCatching { export(item) }
  }
 }
 suspend fun delete(recording: Recording) = exportLock.withLock {
  if(Uri.parse(recording.uri).scheme=="file") {
   val file=File(checkNotNull(Uri.parse(recording.uri).path))
   require(file.canonicalFile.parentFile==File(context.noBackupFilesDir,"videos").canonicalFile)
   val journal=File(file.path+".export-uri")
   if(journal.exists()) {
    context.contentResolver.delete(Uri.parse(journal.readText()),"${MediaStore.MediaColumns.IS_PENDING}=1",null)
    check(journal.delete())
   }
   check(!file.exists() || file.delete());dao.delete(recording);return@withLock
  }
  val removed = context.contentResolver.delete(Uri.parse(recording.uri), "${MediaStore.Video.Media.IS_PENDING}=0", null)
  check(removed > 0) { "录像尚未完成或已不存在，不能删除" }
  dao.delete(recording)
 }
}
