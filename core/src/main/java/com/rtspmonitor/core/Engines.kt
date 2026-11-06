package com.rtspmonitor.core

import kotlinx.coroutines.flow.StateFlow
import java.io.File

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, ERROR }
enum class RecordingState { IDLE, PREPARING, RECORDING, FINALIZING, INTERRUPTED, FAILED }
enum class Transport { TCP, UDP }
// Connection details deliberately have no data-class toString containing secrets.
class Connection(val url: String, val username: String, val password: String, val transport: Transport) {
    init {
        val uri = java.net.URI(url)
        require(uri.scheme == "rtsp" && !uri.host.isNullOrBlank() && uri.fragment == null) { "需要有效的 rtsp:// URL" }
    }
    override fun toString() = "Connection([redacted])"
}
data class PlayerStatus(val state: ConnectionState = ConnectionState.DISCONNECTED, val info: String = "", val error: String? = null)
interface RtspPlayerEngine {
    val status: StateFlow<PlayerStatus>
    fun connect(connection: Connection)
    fun disconnect()
    fun release()
}
interface RecordingEngine {
    fun setSegmentSeconds(seconds:Int)
    suspend fun record(connection: Connection, directory: File, segmentSeconds: Int = 300, onEvent: (String, String) -> Unit): Int
    fun stop()
}
data class SessionStatus(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val recordingState: RecordingState = RecordingState.IDLE,
    val reconnectCount: Int = 0,
    val lastError: String? = null
)
fun safeCameraName(name: String): String = name.replace(Regex("[^\\p{L}\\p{N}_-]"), "_").take(60).ifBlank { "Camera" }

data class PlaybackStatus(val positionMs:Long=0,val durationMs:Long=0,val speed:Float=1f,val playing:Boolean=false,val seekable:Boolean=false,val error:String?=null)
