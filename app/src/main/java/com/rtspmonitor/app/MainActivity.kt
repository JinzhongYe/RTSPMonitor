package com.rtspmonitor.app
import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.flow.first
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
 val preferences = app.getSharedPreferences("monitor", android.content.Context.MODE_PRIVATE)
 var activeId by mutableStateOf(preferences.getString("active_camera", null))
  private set
 var saving by mutableStateOf(false)
  private set
 fun selectCamera(camera: Camera) {
  preferences.edit().putString("active_camera", camera.id).apply()
  activeId = camera.id
 }
 fun connection(camera: Camera): Connection? = runCatching { app.vault.decrypt(camera.encryptedConnection) }.getOrNull()
 fun save(name: String, url: String, user: String, password: String, tcp: Boolean, done: () -> Unit = {}) = viewModelScope.launch {
  if(saving) return@launch
  saving = true
  try {
   val uri = java.net.URI(url.trim())
   require(uri.rawUserInfo == null) { "请在用户名和密码栏填写凭据" }
   val c = Connection(url.trim(), user, password, if(tcp) Transport.TCP else Transport.UDP)
   val existing = app.database.dao().cameras().first()
   val old = existing.firstOrNull { it.id == activeId } ?: existing.singleOrNull()
   require(old != null || existing.isEmpty()) { "请先选择使用的摄像头" }
   val encrypted = app.vault.encrypt(c)
   if(old != null) {
    require(!preferences.getBoolean("auto",false) && !preferences.getBoolean("manual",false)) { "请先停止自动监控和手动录像" }
    preferences.edit().putBoolean("preview",false).apply()
    app.sessions.stop(old.id);app.sessions.disconnect(old.id)
   }
   val camera = Camera(old?.id ?: UUID.randomUUID().toString(), name.ifBlank { "我的摄像头" }, encrypted)
   app.database.dao().save(camera)
   selectCamera(camera)
   app.sessions.message.value = "设置已加密保存"
   done()
  } catch(e: Exception) {
   if(e is kotlinx.coroutines.CancellationException) throw e
   app.sessions.message.value = "保存失败，请检查地址、独立凭据或 Keystore"
  } finally { saving = false }
 }
 var exporting by mutableStateOf<String?>(null)
  private set
 fun export(recording:Recording)=viewModelScope.launch {
  if(exporting!=null)return@launch
  exporting=recording.uri
  try { app.store.export(recording);app.sessions.message.value="已保存到系统相册" }
  catch(e:Exception) { if(e is kotlinx.coroutines.CancellationException)throw e;app.sessions.message.value="导出失败，原录像已保留" }
  finally { exporting=null }
 }
 fun cleanup() = viewModelScope.launch { app.sessions.cleanupDamaged() }
 fun remove(c: Camera) = viewModelScope.launch { app.sessions.remove(c) }
 fun delete(r: Recording) = viewModelScope.launch { runCatching { app.store.delete(r) }.onFailure { app.sessions.message.value = "删除录像失败" } }
}
class MainActivity: ComponentActivity() {
 var resumeCount by mutableIntStateOf(0)
  private set
 override fun onResume() {
  super.onResume();resumeCount++
  val prefs=getSharedPreferences("monitor",MODE_PRIVATE)
  if(listOf("auto","preview","manual").any { prefs.getBoolean(it,false) }) prefs.getString("active_camera",null)?.let { command("RESUME",it) }
 }
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
  setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF126B62), background = Color(0xFFF3F6F8))) { Surface { MonitorScreen() } } }
 }
 fun command(action: String, id: String) {
  if(action in listOf("START","CONNECT","AUTO_ON","RESUME")) withNetworkPermission { sendCommand(action, id) } else sendCommand(action, id)
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
 val detectionStatus by vm.app.sessions.detectionStatus.collectAsState()
 val monitoring by vm.app.sessions.monitoring.collectAsState()
 val manual by vm.app.sessions.manualRecording.collectAsState()
 val automatic by vm.app.sessions.automaticRecording.collectAsState()
 LaunchedEffect(activity.resumeCount) { vm.closeRecording() }
 val camera = cameras.firstOrNull { it.id == vm.activeId } ?: cameras.singleOrNull()
 LaunchedEffect(camera?.id) { if(camera != null && vm.activeId != camera.id) vm.selectCamera(camera) }
 var settings by rememberSaveable { mutableStateOf(false) }
 var full by rememberSaveable { mutableStateOf(false) }
 var deleting by remember { mutableStateOf<Recording?>(null) }
 val local = vm.localUri
 val player = if(local != null) vm.localPlayer else camera?.let { vm.app.sessions.session(it.id).player }
 val state = camera?.let { states[it.id] } ?: SessionStatus()
 val busy = state.recordingState in listOf(RecordingState.PREPARING, RecordingState.RECORDING, RecordingState.FINALIZING, RecordingState.INTERRUPTED)
 fun exitFull() { full = false; activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
 BackHandler(!full) { activity.moveTaskToBack(true) }
 BackHandler(full) { exitFull() }
 if(full && player != null) {
  Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
   key(player) { AndroidView(factory={ playerView(it, player) }, modifier=Modifier.fillMaxSize(), onRelease={ player.detach(it) }) }
   Button(onClick={ exitFull() }, modifier=Modifier.align(Alignment.TopEnd)) { Text("退出全屏") }
  }
 } else {
  LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentPadding=PaddingValues(20.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
   item {
    Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.SpaceBetween) {
     Column { Text("RTSP Monitor", style=MaterialTheme.typography.headlineSmall); Text(camera?.name ?: "单路监控", style=MaterialTheme.typography.bodyMedium) }
     TextButton(enabled=vm.ready.value && !vm.saving && !monitoring && !manual && (camera != null || cameras.isEmpty()), onClick={ settings=true }) { Text("设置") }
    }
   }
   if(camera == null && cameras.size > 1) item {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
     Text("选择本机使用的摄像头", style=MaterialTheme.typography.titleMedium)
     Text("旧设备和历史录像将保留，选择后使用单路监控。")
     cameras.forEach { c -> TextButton(onClick={ vm.selectCamera(c) }) { Text(c.name) } }
    } }
   }
   item {
    Card(Modifier.fillMaxWidth()) {
     Box(Modifier.fillMaxWidth().aspectRatio(16f/9f).background(Color.Black), contentAlignment=Alignment.Center) {
      if(player != null) key(player) { AndroidView(factory={ playerView(it, player) }, modifier=Modifier.fillMaxSize(), onRelease={ player.detach(it) }) }
      if(local == null && state.connectionState == ConnectionState.DISCONNECTED) Text(if(camera == null) "请先配置摄像头" else "点击连接，查看实时画面", color=Color.White)
     }
     Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
      Text(if(local != null) "正在回放录像" else "预览 · ${connectionLabel(state.connectionState)}", style=MaterialTheme.typography.titleMedium)
      Text("录像 · ${recordingLabel(state.recordingState)}" + if(state.reconnectCount > 0) " · 重连 ${state.reconnectCount} 次" else "")
      state.lastError?.let { Text(it, color=MaterialTheme.colorScheme.error) }
      Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
       Button(enabled=camera != null && vm.ready.value && !vm.saving, onClick={ camera?.let { vm.closeRecording(); activity.command("CONNECT",it.id) } }) { Text("连接") }
       OutlinedButton(enabled=camera != null && !vm.saving && !monitoring, onClick={ camera?.let { activity.command("DISCONNECT",it.id) } }) { Text("断开") }
       TextButton(enabled=player != null, onClick={ full=true; activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }) { Text("全屏") }
      }
      Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
       Button(enabled=camera != null && vm.ready.value && !manual && !vm.saving, onClick={ camera?.let { activity.command("START",it.id) } }) { Text("开始录像") }
       OutlinedButton(enabled=manual && !vm.saving, onClick={ camera?.let { activity.command("STOP",it.id) } }) { Text("停止手动录像") }
      }
      Row(verticalAlignment=Alignment.CenterVertically) {
       Switch(checked=monitoring,enabled=camera!=null && vm.ready.value && !vm.saving,onCheckedChange={ enabled -> camera?.let { activity.command(if(enabled) "AUTO_ON" else "AUTO_OFF",it.id) } })
       Text("自动监控：${if(monitoring) "已开启" else "已关闭"}")
      }
      if(monitoring || manual)Text("修改连接设置前，请先停止手动录像并关闭自动监控",style=MaterialTheme.typography.bodySmall)
      if(monitoring)Text(detectionStatus)
      if(automatic)Text("活动录像中，无活动30秒后停止")
      if(manual)Text("手动录像中，自动事件结束不会停止手动录像")
      if(android.os.Build.VERSION.SDK_INT>=33 && activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)Text("通知未授权，活动提醒无法显示",color=MaterialTheme.colorScheme.error)
      if(local != null) TextButton(onClick={ vm.closeRecording() }) { Text("返回实时监控") }
      if(camera == null && cameras.isEmpty()) Button(enabled=vm.ready.value, onClick={ settings=true }) { Text("配置摄像头") }
     }
    }
   }
   if(message.isNotEmpty()) item { Text(message, style=MaterialTheme.typography.bodyMedium) }
   item { Text("录像 (${recordings.size})", style=MaterialTheme.typography.titleLarge) }
   if(recordings.isEmpty()) item { Text("暂无录像，开始录像后会自动分段保存。") }
   items(recordings, key={ it.uri }) { r ->
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
     Text(r.name, style=MaterialTheme.typography.titleSmall)
     Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(r.createdAt)), style=MaterialTheme.typography.bodySmall)
     Row {
      TextButton(onClick={ vm.playRecording(r.uri) }) { Text("回放") }
      TextButton(enabled=vm.exporting==null,onClick={ vm.export(r) }) { Text(if(vm.exporting==r.uri) "保存中…" else if(r.exportedUri!=null || android.net.Uri.parse(r.uri).scheme!="file") "已保存到相册" else "保存到相册") }
      TextButton(enabled=vm.exporting!=r.uri,onClick={ deleting=r }) { Text("删除") }
     }
    } }
   }
   item { TextButton(enabled=!busy, onClick={ vm.cleanup() }) { Text("清理损坏的临时录像") } }
  }
 }
 if(settings) CameraSettings(vm, camera) { settings=false }
 deleting?.let { r -> AlertDialog(onDismissRequest={ deleting=null }, title={ Text("删除录像？") }, text={ Text("删除应用内录像后无法恢复，已经导出的相册副本不受影响；旧版相册录像会删除原文件。") }, confirmButton={ TextButton(onClick={ if(local==r.uri) vm.closeRecording(); vm.delete(r); deleting=null }) { Text("删除") } }, dismissButton={ TextButton(onClick={ deleting=null }) { Text("取消") } }) }
}
@Composable private fun CameraSettings(vm: MonitorViewModel, camera: Camera?, dismiss: () -> Unit) {
 val connection = remember(camera) { camera?.let { vm.connection(it) } }
 var name by remember { mutableStateOf(camera?.name ?: "我的摄像头") }
 var url by remember { mutableStateOf(connection?.url ?: "rtsp://192.168.0.210:554/stream1") }
 var user by remember { mutableStateOf(connection?.username ?: "admin") }
 var password by remember { mutableStateOf("") }
 var tcp by remember { mutableStateOf(connection?.transport != Transport.UDP) }
 AlertDialog(onDismissRequest={ if(!vm.saving) dismiss() }, title={ Text("摄像头设置") }, text={
  Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(8.dp)) {
   OutlinedTextField(name, {name=it}, label={Text("设备名称")}, singleLine=true)
   OutlinedTextField(url, {url=it}, label={Text("RTSP 地址（不含密码）")}, singleLine=true)
   OutlinedTextField(user, {user=it}, label={Text("用户名")}, singleLine=true)
   OutlinedTextField(password, {password=it}, label={Text(if(connection != null) "密码（留空保留）" else "密码")}, visualTransformation=PasswordVisualTransformation(), singleLine=true)
   Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(tcp, {tcp=it}); Text(if(tcp) "TCP · 稳定传输" else "UDP") }
   Text("凭据仅在本机加密保存。保存设置会先停止预览与录像。", style=MaterialTheme.typography.bodySmall)
  }
 }, confirmButton={ TextButton(enabled=!vm.saving, onClick={ vm.save(name,url,user,password.ifEmpty { connection?.password ?: "" },tcp,dismiss) }) { Text(if(vm.saving) "保存中…" else "保存") } }, dismissButton={ TextButton(enabled=!vm.saving, onClick=dismiss) { Text("取消") } })
}
private fun connectionLabel(state: ConnectionState) = when(state) {
 ConnectionState.DISCONNECTED -> "未连接"; ConnectionState.CONNECTING -> "连接中"; ConnectionState.CONNECTED -> "已连接"; ConnectionState.RECONNECTING -> "重连中"; ConnectionState.ERROR -> "连接失败"
}
private fun recordingLabel(state: RecordingState) = when(state) {
 RecordingState.IDLE -> "未录像"; RecordingState.PREPARING -> "准备中"; RecordingState.RECORDING -> "正在录像"; RecordingState.FINALIZING -> "保存中"; RecordingState.INTERRUPTED -> "等待重连"; RecordingState.FAILED -> "录像失败"
}
private fun playerView(context: android.content.Context, player: VlcPlayer): android.view.View = com.rtspmonitor.media.createVideoView(context, player)
