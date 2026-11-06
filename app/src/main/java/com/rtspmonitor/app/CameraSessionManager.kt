package com.rtspmonitor.app
import com.rtspmonitor.core.*
import com.rtspmonitor.media.*
import com.rtspmonitor.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

class CameraSessionManager(private val app: MonitorApp) {
 class Session(val player: VlcPlayer) {
  val commands = Mutex()
  var removed = false
  val state = MutableStateFlow(SessionStatus())
  @Volatile var sources: Int = 0
  @Volatile var segmentSeconds:Int = 300
  var recordingEngine: AvRecorder? = null
  var job: Job? = null
  var observer: Job? = null
 }
 private var recovered = false
 private val recoveryLock = kotlinx.coroutines.sync.Mutex()
 private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
 private val sessions = mutableMapOf<String, Session>()
 val states = MutableStateFlow<Map<String, SessionStatus>>(emptyMap())
 val message = MutableStateFlow("")
 val detectionStatus = MutableStateFlow("等待画面")
 val monitoring = MutableStateFlow(false)
 val manualRecording = MutableStateFlow(false)
 val automaticRecording = MutableStateFlow(false)
 val decodeLimit = 1
 val recordingLimit = 1
 private fun update(id: String, state: SessionStatus) { sessions[id]?.state?.value = state; states.value = sessions.mapValues { it.value.state.value } }
 fun session(id: String): Session = sessions.getOrPut(id) {
  Session(VlcPlayer(app)).also { session -> session.observer = scope.launch {
   session.player.status.collect { p -> update(id, session.state.value.copy(connectionState = p.state, lastError = p.error ?: session.state.value.lastError)) }
  } }
 }
 private fun allowedCamera(id: String): Boolean {
  val selected = app.getSharedPreferences("monitor", android.content.Context.MODE_PRIVATE).getString("active_camera", null)
  val otherActive = sessions.any { (key, value) -> key != id && (value.job?.isCompleted == false || value.player.status.value.state in listOf(ConnectionState.CONNECTED, ConnectionState.CONNECTING, ConnectionState.RECONNECTING)) }
  if((selected != null && selected != id) || otherActive) { message.value = "当前仅支持同一摄像头的一路预览和录像"; return false }
  return true
 }
 private fun canDecode(id: String): Boolean {
  val count = sessions.filterKeys { it != id }.values.count { it.player.status.value.state in listOf(ConnectionState.CONNECTED, ConnectionState.CONNECTING, ConnectionState.RECONNECTING) }
  if(count >= decodeLimit) { message.value = "已达到并发解码配额"; return false }
  return true
 }
 private fun networkAllowed(): Boolean {
  if(app.hasLocalNetworkAccess()) return true
  message.value = "未允许局域网访问，请返回页面授权"
  return false
 }
 fun connect(camera: Camera) {
  if(!allowedCamera(camera.id) || !networkAllowed()) return
  if(!canDecode(camera.id)) return
  runCatching { session(camera.id).player.connect(app.vault.decrypt(camera.encryptedConnection)) }.onFailure { message.value = "连接配置不可用，请重新输入凭据" }
 }
 fun disconnect(id: String) { sessions[id]?.player?.disconnect() }
 fun probe(camera: Camera) {
  if(!allowedCamera(camera.id) || !networkAllowed()) return
  if(!canDecode(camera.id)) return
  runCatching {
   val folder = File(app.noBackupFilesDir, "sout-probe").apply { mkdirs() }
   session(camera.id).player.probeSout(app.vault.decrypt(camera.encryptedConnection), File(folder, "probe-${System.currentTimeMillis()}.mp4"))
   message.value = "sout 实验正在写入私有目录；断开后收尾，尚未确认同会话可靠性"
  }.onFailure { message.value = "sout 实验无法启动" }
 }
 suspend fun start(camera: Camera) {
  if(!allowedCamera(camera.id)) return
  val s = session(camera.id)
  s.commands.withLock {
  if(!allowedCamera(camera.id)) return@withLock
  if(s.removed || s.job?.isCompleted == false) return@withLock
  if(sessions.values.count { it.job?.isCompleted == false } >= recordingLimit) { message.value = "已达到并发录像配额"; return@withLock }
  s.job = scope.launch {
   update(camera.id, s.state.value.copy(recordingState = RecordingState.PREPARING, reconnectCount = 0, lastError = null))
   try {
    val connection = app.vault.decrypt(camera.encryptedConnection)
    var retry = 0
    while(isActive) {
     if(!networkAllowed()) {
      update(camera.id, s.state.value.copy(recordingState = RecordingState.FAILED, lastError = message.value)); break
     }
     withContext(Dispatchers.IO) { app.store.checkSpace() }
     val directory = File(app.store.temporary, "${camera.id}/${UUID.randomUUID()}").apply { mkdirs() }
     val recorder = AvRecorder().apply { sourceProvider = { s.sources }; setSegmentSeconds(s.segmentSeconds) }; s.recordingEngine = recorder
     var storageFailure = false
     val publisher = SegmentPublisher(this, { file -> app.store.publish(file, camera) }, { recorder.stop() })
     val result = try { recorder.record(connection, directory,s.segmentSeconds) { kind, value ->
      when(kind) {
       "segment" -> publisher.enqueue(File(value))
       "warning" -> message.value = value
       "storage-error" -> { storageFailure = true; message.value = value }
       "recording" -> scope.launch { update(camera.id, s.state.value.copy(recordingState = RecordingState.RECORDING, lastError = null)) }
      }
     }
     } finally { publisher.finish() }
     s.recordingEngine = null
     if(storageFailure || result == -28) {
      update(camera.id, s.state.value.copy(recordingState = RecordingState.FAILED, lastError = if(result == -28) InsufficientStorageException().message else "录像文件写入失败；临时文件已保留")); break
     }
     if(result == -401 || result == -415) {
      update(camera.id, s.state.value.copy(recordingState = RecordingState.FAILED, lastError = if(result == -401) "认证失败，请重新输入凭据" else "不支持的视频编码")); break
     }
     if(!isActive) break
     retry++
     update(camera.id, s.state.value.copy(recordingState = RecordingState.INTERRUPTED, reconnectCount = retry, lastError = "录像连接中断（代码 $result），等待重连"))
     delay(minOf(30_000L, 1000L shl minOf(retry - 1, 5)))
     update(camera.id, s.state.value.copy(recordingState = RecordingState.PREPARING))
    }
   } catch(e: CancellationException) { throw e }
   catch(e: Throwable) {
    update(camera.id, s.state.value.copy(recordingState = RecordingState.FAILED, lastError = if(e is InsufficientStorageException) e.message else "录像失败：请检查空间、发布积压、凭据及原生库；临时文件已保留"))
   } finally {
    s.recordingEngine?.close(); s.recordingEngine = null
    if(s.state.value.recordingState != RecordingState.FAILED) update(camera.id, s.state.value.copy(recordingState = RecordingState.IDLE))
   }
  }
 }
 }
 suspend fun stop(id: String) {
  val s = sessions[id] ?: return
  s.commands.withLock { stopSession(id, s) }
 }
 private suspend fun stopSession(id: String, s: Session) {
  update(id, s.state.value.copy(recordingState = RecordingState.FINALIZING))
  s.recordingEngine?.stop(); s.job?.cancelAndJoin(); s.job = null
  if(s.state.value.recordingState != RecordingState.FAILED) update(id, s.state.value.copy(recordingState = RecordingState.IDLE))
 }
 suspend fun stopAll() { sessions.keys.toList().forEach { stop(it) } }
 suspend fun remove(camera: Camera) {
  val s = sessions[camera.id]
  if(s != null) s.commands.withLock {
   stopSession(camera.id, s)
   s.removed = true
   sessions.remove(camera.id); s.observer?.cancel(); s.player.release()
  }
  states.value = sessions.mapValues { it.value.state.value }; app.database.dao().delete(camera)
 }
 suspend fun cleanupDamaged() = withContext(Dispatchers.IO) {
  if(states.value.values.any { it.recordingState in listOf(RecordingState.PREPARING, RecordingState.RECORDING, RecordingState.FINALIZING, RecordingState.INTERRUPTED) }) { message.value = "请先停止全部录像再清理"; return@withContext }
  app.store.temporary.walkTopDown().filter { it.isFile && it.extension == "damaged" }.forEach { file -> file.delete() }
  message.value = "已清理无法恢复的临时录像"
 }
 suspend fun recover() {
  recoveryLock.lock()
  try {
  if(recovered) return
  withContext(Dispatchers.IO) {
  runCatching { app.store.reconcile() }.onFailure { message.value = "MediaStore 对账失败，未删除已有索引" }
  app.store.temporary.listFiles()?.forEach { cameraFolder ->
   val camera = app.database.dao().camera(cameraFolder.name)
   cameraFolder.walkTopDown().filter { it.isFile && it.extension in listOf("mp4", "part") }.forEach { file ->
    if(camera != null) {
     val valid = runCatching { app.store.recoverTemporary(file) }.isSuccess
     if(valid) runCatching { app.store.publish(file, camera) }.onFailure { message.value = "录像发布失败，已保留临时文件，下次启动重试" }
     else { file.renameTo(File(file.path + ".damaged")); message.value = "发现无法恢复的临时录像，可在停止录像后清理" }
    }
   }
  }
  }
  recovered = true
  } finally { recoveryLock.unlock() }
 }
}
