package com.rtspmonitor.media
import com.rtspmonitor.core.*
import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AvRecorder: RecordingEngine {
 companion object { init { System.loadLibrary("rtsp_recorder") } }
 @Volatile var sourceProvider: () -> Int = { 0 }
 private var handle = create().also { check(it != 0L) }
 private external fun create(): Long
 private external fun run(handle: Long, url: String, tcp: Boolean, directory: String, seconds: Int, callback: Callback): Int
 private external fun cancel(handle: Long)
 private external fun destroy(handle: Long)
 interface Callback { fun event(kind: String, value: String) }
 override suspend fun record(connection: Connection, directory: File, segmentSeconds: Int, onEvent: (String, String) -> Unit): Int = withContext(Dispatchers.IO) {
  val u = URI(connection.url)
  val auth = if(connection.username.isEmpty()) u.rawUserInfo else encode(connection.username) + ":" + encode(connection.password)
  val url = buildString {
   append(u.scheme); append("://"); if(auth != null) { append(auth); append('@') }
   append(u.rawAuthority.substringAfterLast('@')); append(u.rawPath ?: ""); if(u.rawQuery != null) { append('?'); append(u.rawQuery) }
  }
  var callbackError: Throwable? = null
  var metadataFile: File? = null
  var metadata: org.json.JSONObject? = null
  var lastPersisted = 0L
  fun persist() {
   val file=metadataFile ?: return
   val value=metadata ?: return
   val atomic=android.util.AtomicFile(file)
   val stream=atomic.startWrite()
   try { stream.write(value.toString().toByteArray()); atomic.finishWrite(stream) }
   catch(e:Throwable) { atomic.failWrite(stream); throw e }
  }
  try {
   val result = run(handle, url, connection.transport == Transport.TCP, directory.absolutePath, segmentSeconds, object: Callback {
    override fun event(kind: String, value: String) { try {
     when(kind) {
      "recording" -> { metadataFile=File(File(value).parentFile,File(value).nameWithoutExtension+".meta"); metadata=null; lastPersisted=0 }
      "frame-time" -> {
       val times=value.split(',').map { it.toLong() }
       val old=metadata
       val sources=(old?.optInt("sources") ?: 0) or sourceProvider()
       metadata=org.json.JSONObject().put("startedAt",times[0]).put("endedAt",times[1]).put("offsetMinutes",old?.optInt("offsetMinutes") ?: (java.util.TimeZone.getDefault().getOffset(times[0])/60000)).put("sources",sources)
       if(times[1]-lastPersisted>=1000 || sources!=old?.optInt("sources")) { persist(); lastPersisted=times[1] }
      }
      "segment" -> persist()
     }
     if(kind!="frame-time") onEvent(kind, value)
    } catch(e: Throwable) { callbackError = e; stop() } }
   })
   callbackError?.let { throw it }
   result
  } finally { synchronized(this@AvRecorder) { destroy(handle); handle = 0 } }
 }
 private fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
 fun close() { synchronized(this) { if(handle != 0L) { destroy(handle); handle = 0 } } }
 override fun stop() { synchronized(this) { if(handle != 0L) cancel(handle) } }
}
