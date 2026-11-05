package com.rtspmonitor.media
import com.rtspmonitor.core.*
import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AvRecorder: RecordingEngine {
 companion object { init { System.loadLibrary("rtsp_recorder") } }
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
  try {
   val result = run(handle, url, connection.transport == Transport.TCP, directory.absolutePath, segmentSeconds, object: Callback {
    override fun event(kind: String, value: String) { try { onEvent(kind, value) } catch(e: Throwable) { callbackError = e; stop() } }
   })
   callbackError?.let { throw it }
   result
  } finally { synchronized(this@AvRecorder) { destroy(handle); handle = 0 } }
 }
 private fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
 fun close() { synchronized(this) { if(handle != 0L) { destroy(handle); handle = 0 } } }
 override fun stop() { synchronized(this) { if(handle != 0L) cancel(handle) } }
}
