package com.rtspmonitor.app
import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rtspmonitor.core.*
import com.rtspmonitor.data.*
import com.rtspmonitor.media.VlcPlayer
import kotlinx.coroutines.launch
import java.util.UUID

class MonitorViewModel(application: android.app.Application): AndroidViewModel(application) {
 val app = application as MonitorApp
 val cameras = app.database.dao().cameras()
 val recordings = app.database.dao().recordings()
 val localPlayer = VlcPlayer(app)
 var localUri by mutableStateOf<String?>(null)
  private set
 fun playRecording(uri: String) { localUri = uri; localPlayer.playLocal(android.net.Uri.parse(uri)) }
 fun closeRecording() { localPlayer.disconnect(); localUri = null }
 override fun onCleared() { localPlayer.release(); super.onCleared() }
 val ready = mutableStateOf(false)
 init { viewModelScope.launch { app.sessions.recover(); ready.value = true } }
 fun save(name: String, url: String, user: String, password: String, tcp: Boolean) = viewModelScope.launch {
  runCatching {
   val uri = java.net.URI(url)
   require(!uri.host.isNullOrBlank() && uri.fragment == null) { "地址无效" }
   val c = Connection(url, user, password, if(tcp) Transport.TCP else Transport.UDP)
   app.database.dao().save(Camera(UUID.randomUUID().toString(), name.ifBlank { "Camera" }, app.vault.encrypt(c)))
  }.onFailure { app.sessions.message.value = "保存失败，请检查 RTSP 地址或 Keystore" }
 }
 fun cleanup() = viewModelScope.launch { app.sessions.cleanupDamaged() }
 fun remove(c: Camera) = viewModelScope.launch { app.sessions.remove(c) }
 fun delete(r: Recording) = viewModelScope.launch { runCatching { app.store.delete(r) }.onFailure { app.sessions.message.value = "删除录像失败" } }
}
class MainActivity: ComponentActivity() {
 private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
  if(!granted) (application as MonitorApp).sessions.message.value = "未允许通知；录像可继续，请返回此页面停止"
 }
 private var pendingNetworkAction: (() -> Unit)? = null
 private val localNetwork = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
  val action = pendingNetworkAction; pendingNetworkAction = null
  if(granted) action?.invoke() else (application as MonitorApp).sessions.message.value = "未允许局域网访问，无法连接网络摄像头；可在系统设置中授权"
 }
 fun withNetworkPermission(action: () -> Unit) {
  if(hasLocalNetworkAccess()) action()
  else { pendingNetworkAction = action; localNetwork.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) }
 }
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  enableEdgeToEdge()
  if(Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
  setContent { MaterialTheme { Surface { MonitorScreen() } } }
 }
 fun command(action: String, id: String) {
  if(action == "START") withNetworkPermission { sendCommand(action, id) } else sendCommand(action, id)
 }
 private fun sendCommand(action: String, id: String) {
  runCatching { startForegroundService(Intent(this, RecordingForegroundService::class.java).setAction(action).putExtra("id",id)) }
   .onFailure { (application as MonitorApp).sessions.message.value = "无法启动前台服务，请返回前台重试" }
 }
}
@Composable private fun MonitorScreen(vm: MonitorViewModel = viewModel()) {
 val activity = androidx.activity.compose.LocalActivity.current as MainActivity
 val cameras by vm.cameras.collectAsState(initial = emptyList())
 val recordings by vm.recordings.collectAsState(initial = emptyList())
 val states by vm.app.sessions.states.collectAsState()
 val message by vm.app.sessions.message.collectAsState()
 var selected by rememberSaveable { mutableStateOf<String?>(null) }
 var name by remember { mutableStateOf("") }; var url by remember { mutableStateOf("") }
 var user by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
 var tcp by remember { mutableStateOf(true) }; var full by rememberSaveable { mutableStateOf(false) }
 val local = vm.localUri
 val localPlayer = vm.localPlayer
 val player = if(local != null) localPlayer else selected?.let { vm.app.sessions.session(it).player }
 if(full && player != null) {
  Box(Modifier.fillMaxSize().safeDrawingPadding()) {
   key(player) { AndroidView(factory={ context -> playerView(context, player) }, modifier=Modifier.fillMaxSize(), onRelease={ player.detach(it) }) }
   Button(onClick={ full=false; activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }, modifier=Modifier.align(androidx.compose.ui.Alignment.TopEnd)) { Text("退出全屏") }
  }
  return
 }
 Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp).verticalScroll(rememberScrollState())) {
  Text("RTSP Monitor · 技术验证", style = MaterialTheme.typography.titleLarge)
  Text("录像为独立连接；sout 尚待实机验证。硬解请求不等于硬解实测通过。")
  if(message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)
  if(player != null) {
   key(player) { VideoView(player, if(full) 320 else 220) }
   val status by player.status.collectAsState()
   Text("${status.state} ${status.info} ${status.error ?: ""}")
   Button(onClick = { full = !full; activity.requestedOrientation = if(full) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }) { Text(if(full) "退出全屏" else "横屏全屏") }
  }
  if(!full) {
   OutlinedTextField(name, { name=it }, label={ Text("设备名称") }, modifier=Modifier.fillMaxWidth())
   OutlinedTextField(url, { url=it }, label={ Text("RTSP URL") }, modifier=Modifier.fillMaxWidth(), singleLine=true)
   OutlinedTextField(user, { user=it }, label={ Text("用户名") }, singleLine=true)
   OutlinedTextField(password, { password=it }, label={ Text("密码") }, visualTransformation=PasswordVisualTransformation(), singleLine=true)
   Row { Checkbox(tcp, {tcp=it}); Text(if(tcp) "TCP" else "UDP") }
   Button(enabled=vm.ready.value, onClick={ vm.save(name,url,user,password,tcp); user=""; password=""; url="" }) { Text("添加并加密保存") }
   Row {
    Text("并发测试配额")
    TextButton(onClick={ vm.app.sessions.decodeLimit++; vm.app.sessions.recordingLimit++; vm.app.sessions.message.value="配额：${vm.app.sessions.decodeLimit}；请监测温度和带宽" }) { Text("增加一路") }
    TextButton(onClick={ vm.app.sessions.decodeLimit=1; vm.app.sessions.recordingLimit=1 }) { Text("恢复 1 路") }
   }
   cameras.forEach { c ->
    val state = states[c.id] ?: SessionStatus()
    HorizontalDivider(); Text(c.name, style=MaterialTheme.typography.titleMedium)
    Text("${state.connectionState} / ${state.recordingState}；重连 ${state.reconnectCount}")
    state.lastError?.let { Text(it) }
    Row {
     TextButton(onClick={ vm.closeRecording(); selected=c.id; activity.withNetworkPermission { vm.app.sessions.connect(c) } }) { Text("连接") }
     TextButton(onClick={ vm.app.sessions.disconnect(c.id) }) { Text("断开预览") }
     TextButton(enabled=vm.ready.value, onClick={ activity.command("START",c.id) }) { Text("录像") }
     TextButton(onClick={ activity.command("STOP",c.id) }) { Text("停止") }
    }
    Row {
     TextButton(onClick={ vm.closeRecording(); selected=c.id; activity.withNetworkPermission { vm.app.sessions.probe(c) } }) { Text("sout 实验（重连）") }
     TextButton(onClick={ vm.remove(c); if(selected==c.id) selected=null }) { Text("删除设备") }
    }
   }
   TextButton(onClick={ vm.cleanup() }) { Text("清理无法恢复的临时录像") }
   HorizontalDivider(); Text("已发布录像")
   recordings.forEach { r ->
    Text(r.name)
    Row {
     TextButton(onClick={ vm.playRecording(r.uri) }) { Text("回放") }
     TextButton(onClick={ if(local==r.uri) vm.closeRecording(); vm.delete(r) }) { Text("删除") }
    }
   }
  }
 }
}
@Composable private fun VideoView(player: VlcPlayer, height: Int) {
 AndroidView(factory={ context -> playerView(context,player) }, modifier=Modifier.fillMaxWidth().height(height.dp), onRelease={ player.detach(it) })
}
private fun playerView(context: android.content.Context, player: VlcPlayer): android.view.View {
 // Keep the LibVLC view type behind the media module boundary.
 return com.rtspmonitor.media.createVideoView(context, player)
}
