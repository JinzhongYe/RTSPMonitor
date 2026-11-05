package com.rtspmonitor.media

import android.content.Context
import android.net.Uri
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import com.rtspmonitor.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.videolan.libvlc.*
import org.videolan.libvlc.interfaces.IVLCVout
import java.io.File

data class RecordProbeStatus(val active: Boolean = false, val path: String? = null)

class VlcPlayer(context: Context): RtspPlayerEngine {
 private val vlc = LibVLC(context, arrayListOf("--quiet", "--no-stats"))
 private val player = MediaPlayer(vlc)
 private val output = VideoOutput()
 var frameListener: ((android.graphics.Bitmap, Long) -> Unit)?
  get() = output.onFrame
  set(value) { output.onFrame = value }
 private val resolver = context.applicationContext.contentResolver
 private var localFile: android.os.ParcelFileDescriptor? = null
 private var localResumeAt = -1L
 private val mutable = MutableStateFlow(PlayerStatus())
 override val status = mutable.asStateFlow()
 private val mutablePlayback = kotlinx.coroutines.flow.MutableStateFlow(com.rtspmonitor.core.PlaybackStatus())
 val playback = mutablePlayback.asStateFlow()
 fun refreshPlayback() { mutablePlayback.value=com.rtspmonitor.core.PlaybackStatus(player.time.coerceAtLeast(0),player.length.coerceAtLeast(0),player.rate,player.isPlaying,player.isSeekable,mutable.value.error) }
 fun pausePlayback() { player.pause();refreshPlayback() }
 fun resumePlayback() { if(mutable.value.state==ConnectionState.DISCONNECTED) player.setTime(0,false);player.play();refreshPlayback() }
 fun seekPlayback(ms:Long) { if(player.isSeekable) player.setTime(ms.coerceIn(0,player.length.coerceAtLeast(0)),false);refreshPlayback() }
 fun speedPlayback(rate:Float) { require(rate in listOf(0.5f,1f,1.5f,2f));player.setRate(rate);refreshPlayback() }
 private val recordProbe = MutableStateFlow(RecordProbeStatus())
 val recordProbeStatus = recordProbe.asStateFlow()
 private var width = 0
 private var height = 0
 private var attachedOwner: View? = null
 private var onVideoSize: ((Int,Int,Int,Int)->Unit)?=null
 private var surfacesReady = false
 private val surfaceCallback = object: IVLCVout.Callback {
  override fun onSurfacesCreated(vout: IVLCVout) { surfacesReady = true; if(attachedOwner != null) player.setVideoTrackEnabled(true) }
  override fun onSurfacesDestroyed(vout: IVLCVout) { surfacesReady = false }
 }
 init { player.vlcVout.setVideoSurface(output.surface, null); player.vlcVout.setWindowSize(640,360); player.vlcVout.attachViews { _, cw, ch, vw, vh, _, _ -> width=if(vw>0)vw else cw; height=if(vh>0)vh else ch; output.resize(width,height); if(width>0 && height>0)player.vlcVout.setWindowSize(width,height); android.os.Handler(android.os.Looper.getMainLooper()).post { onVideoSize?.invoke(width,height,1,1) }; updateStatus() }; player.vlcVout.addCallback(surfaceCallback); player.setEventListener { event ->
  when(event.type) {
   MediaPlayer.Event.RecordChanged -> recordProbe.value = RecordProbeStatus(event.recording, event.recordPath)
   MediaPlayer.Event.Opening -> mutable.value = PlayerStatus(ConnectionState.CONNECTING)
   MediaPlayer.Event.Vout -> {
    if(event.voutCount == 0 && surfacesReady && attachedOwner != null) player.setVideoTrackEnabled(true)
    updateStatus()
   }
   MediaPlayer.Event.ESSelected -> {
    if(localResumeAt >= 0 && surfacesReady && event.esChangedType == org.videolan.libvlc.interfaces.IMedia.Track.Type.Video && player.videoTrack >= 0) {
     val position = localResumeAt; localResumeAt = -1L
     // Local demuxers can reach EOF while video is disabled; refill after reselection.
     player.setTime(position, false)
    }
    updateStatus()
   }
   MediaPlayer.Event.Playing -> updateStatus()
   MediaPlayer.Event.EncounteredError -> mutable.value = PlayerStatus(ConnectionState.ERROR, error = "播放连接失败，请检查地址、认证及编码")
   MediaPlayer.Event.EndReached -> mutable.value = PlayerStatus(ConnectionState.DISCONNECTED)
  }
 } }
 private fun updateStatus() {
  val track = player.currentVideoTrack
  val dimensions = if(width > 0 && height > 0) "$width × $height" else "等待视频布局"
  mutable.value = PlayerStatus(if(player.isPlaying) ConnectionState.CONNECTED else mutable.value.state, "$dimensions ${track?.codec ?: ""}；硬解已请求，实际后端待实机确认")
 }
 internal fun attach(surface: SurfaceView, subtitles: SurfaceView, owner: View, onSize: (Int, Int, Int, Int) -> Unit) {
  attachedOwner=owner;onVideoSize=onSize
  val callback=object: android.view.SurfaceHolder.Callback {
   override fun surfaceCreated(holder:android.view.SurfaceHolder) { if(attachedOwner===owner) output.show(holder.surface) }
   override fun surfaceChanged(holder:android.view.SurfaceHolder, format:Int, w:Int, h:Int) { if(attachedOwner===owner) output.show(holder.surface) }
   override fun surfaceDestroyed(holder:android.view.SurfaceHolder) { if(attachedOwner===owner) output.show(null) }
  }
  surface.holder.addCallback(callback)
  if(surface.holder.surface.isValid) output.show(surface.holder.surface)
  onSize(width.coerceAtLeast(16),height.coerceAtLeast(9),1,1)
 }
 internal fun windowSize(owner:View,width:Int,height:Int) { }
 fun detach(owner:View?=null) {
  if(owner!=null && owner!==attachedOwner)return
  attachedOwner=null;onVideoSize=null;output.show(null)
 }
 override fun connect(connection: Connection) = open(connection, null)
 private fun open(c: Connection, sout: String?) {
  stopInput(); width = 0; height = 0
  mutable.value = PlayerStatus(ConnectionState.CONNECTING)
  val media = Media(vlc, Uri.parse(c.url)).apply {
   setHWDecoderEnabled(true, false)
   addOption(":network-caching=300")
   if(c.transport == Transport.TCP) addOption(":rtsp-tcp")
   if(c.username.isNotEmpty()) addOption(":rtsp-user=${c.username}")
   if(c.password.isNotEmpty()) addOption(":rtsp-pwd=${c.password}")
   if(sout != null) addOption(":sout=$sout")
  }
  player.media = media; media.release(); player.play()
 }
 // This explicit probe reconnects; dynamic same-session recording is tested separately.
 fun probeSout(c: Connection, file: File) {
  require(!file.path.contains("'"))
  open(c, "#duplicate{dst=display,dst=std{access=file,mux=mp4,dst='${file.absolutePath}'}}")
 }
 fun probeRecord(directory: File): Boolean { recordProbe.value = RecordProbeStatus(); return player.record(directory.absolutePath) }
 fun stopProbeRecord(): Boolean = player.record(null)
 private fun stopInput() {
  player.stop(); player.media = null
  runCatching { localFile?.close() }; localFile = null; localResumeAt = -1L
 }
 fun playLocal(uri: Uri) {
  player.setRate(1f)
  stopInput(); width = 0; height = 0
  mutable.value = PlayerStatus(ConnectionState.CONNECTING)
  try {
   val descriptor = resolver.openFileDescriptor(uri, "r") ?: error("无法打开录像")
   localFile = descriptor
   val media = Media(vlc, descriptor.fileDescriptor).apply { setHWDecoderEnabled(true, false) }
   player.media = media; media.release(); player.play()
  } catch(error: Exception) {
   stopInput(); mutable.value = PlayerStatus(ConnectionState.ERROR, error = "录像文件无法打开或播放")
  }
 }
 override fun disconnect() { stopInput(); width = 0; height = 0; mutable.value = PlayerStatus() }
 override fun release() { player.setEventListener(null); player.vlcVout.removeCallback(surfaceCallback); detach(); stopInput(); player.release(); output.release(); vlc.release() }

}

fun createVideoView(context: Context, player: VlcPlayer): View {
 val root = FrameLayout(context).apply { setBackgroundColor(android.graphics.Color.BLACK) }
 val surface = SurfaceView(context)
 // LibVLC's opaque hardware output needs a transparent companion surface.
 val subtitles = SurfaceView(context).apply {
  setZOrderMediaOverlay(true)
  holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
 }
 var aspect = 0.0
 fun fit() {
  if(root.width <= 0 || root.height <= 0 || aspect <= 0.0) return
  val wide = root.width.toDouble() / root.height > aspect
  val width = if(wide) (root.height * aspect).toInt().coerceAtLeast(1) else root.width
  val height = if(wide) root.height else (root.width / aspect).toInt().coerceAtLeast(1)
  if(surface.layoutParams.width != width || surface.layoutParams.height != height) {
   surface.layoutParams = FrameLayout.LayoutParams(width, height, android.view.Gravity.CENTER)
   subtitles.layoutParams = FrameLayout.LayoutParams(width, height, android.view.Gravity.CENTER)
  }
 }
 root.addView(surface, FrameLayout.LayoutParams(-1, -1, android.view.Gravity.CENTER))
 root.addView(subtitles, FrameLayout.LayoutParams(-1, -1, android.view.Gravity.CENTER))
 root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit() }
 surface.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> player.windowSize(root, surface.width, surface.height) }
 root.addOnAttachStateChangeListener(object: View.OnAttachStateChangeListener {
  override fun onViewAttachedToWindow(view: View) {
   player.attach(surface, subtitles, root) { width, height, sarNum, sarDen ->
    if(height > 0) { aspect = width.toDouble() / height * if(sarNum > 0 && sarDen > 0) sarNum.toDouble() / sarDen else 1.0; fit() }
   }
  }
  override fun onViewDetachedFromWindow(view: View) { player.detach(root) }
 })
 return root
}
