package com.rtspmonitor.app

import android.app.Application
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rtspmonitor.media.*
import kotlinx.coroutines.*

class PlaybackViewModel(app:Application):AndroidViewModel(app) {
 val player=VlcPlayer(app)
 var uri:String?=null
  private set
 init { viewModelScope.launch { while(isActive) { player.refreshPlayback();delay(250) } } }
 fun open(value:String) { if(uri!=value) { uri=value;player.playLocal(android.net.Uri.parse(value)) } }
 override fun onCleared() { player.release();super.onCleared() }
}
class PlaybackActivity:ComponentActivity() {
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  val uri=intent.getStringExtra("uri") ?: run { finish();return }
  setContent { MaterialTheme { Surface { PlaybackScreen(uri,this) } } }
 }
}
private fun time(ms:Long):String = java.lang.String.format(java.util.Locale.ROOT,"%02d:%02d:%02d",ms/3600000,(ms/60000)%60,(ms/1000)%60)
@Composable private fun PlaybackScreen(uri:String,activity:PlaybackActivity,vm:PlaybackViewModel=viewModel()) {
 LaunchedEffect(uri) { vm.open(uri) }
 val state by vm.player.playback.collectAsState()
 var dragging by remember { mutableStateOf<Float?>(null) }
 var full by rememberSaveable { mutableStateOf(false) }
 Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
  Row { TextButton(onClick={activity.finish()}) { Text("返回监控") };TextButton(onClick={full=!full;activity.requestedOrientation=if(full) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED}) { Text("横屏/竖屏") } }
  AndroidView(factory={createVideoView(it,vm.player)},modifier=Modifier.fillMaxWidth().weight(1f),onRelease={vm.player.detach(it)})
  state.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
  Text("${time(dragging?.toLong() ?: state.positionMs)} / ${time(state.durationMs)}")
  Slider(value=(dragging ?: state.positionMs.toFloat()).coerceIn(0f,state.durationMs.coerceAtLeast(1).toFloat()),onValueChange={dragging=it},onValueChangeFinished={dragging?.let { vm.player.seekPlayback(it.toLong()) };dragging=null},valueRange=0f..state.durationMs.coerceAtLeast(1).toFloat(),enabled=state.seekable && state.durationMs>0)
  Row {
   Button(onClick={if(state.playing)vm.player.pausePlayback() else vm.player.resumePlayback()}) { Text(if(state.playing) "暂停" else "播放") }
   listOf(0.5f,1f,1.5f,2f).forEach { rate -> TextButton(onClick={vm.player.speedPlayback(rate)}) { Text("${if(state.speed==rate) "✓ " else ""}${rate}×") } }
  }
 }
}
