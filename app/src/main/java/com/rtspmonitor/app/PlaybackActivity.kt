package com.rtspmonitor.app

import android.app.Application
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rtspmonitor.core.recordingSourceLabel
import com.rtspmonitor.data.Recording
import com.rtspmonitor.media.*
import kotlinx.coroutines.*
import java.time.LocalDate

class PlaybackViewModel(app:Application):AndroidViewModel(app) {
 private val monitor=app as MonitorApp
 val player=VlcPlayer(app)
 val recordings=monitor.database.dao().recordings()
 val cameras=monitor.database.dao().cameras()
 val thumbnails=RecordingThumbnails(app)
 var uri by mutableStateOf<String?>(null)
  private set
 var message by mutableStateOf("")
  private set
 var working by mutableStateOf(false)
  private set
 init { viewModelScope.launch { while(isActive) { player.refreshPlayback();delay(250) } } }
 fun open(value:String) { if(uri!=value) { uri=value;player.playLocal(android.net.Uri.parse(value)) } }
 fun export(row:Recording)=viewModelScope.launch {
  if(working)return@launch
  working=true
  try { withContext(Dispatchers.IO) { monitor.store.export(row) };message="已保存到系统相册" }
  catch(e:Exception) { if(e is CancellationException)throw e;message="保存失败，原录像已保留" }
  finally { working=false }
 }
 fun delete(row:Recording)=viewModelScope.launch {
  if(working)return@launch
  working=true
  if(uri==row.uri) { player.disconnect();uri=null }
  try { withContext(Dispatchers.IO) { monitor.store.delete(row) };thumbnails.remove(row.uri);message="录像已删除" }
  catch(e:Exception) { if(e is CancellationException)throw e;message="删除失败，请重试" }
  finally { working=false }
 }
 override fun onCleared() { player.release();super.onCleared() }
}
class PlaybackActivity:ComponentActivity() {
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState);enableEdgeToEdge()
  setContent { MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF126B62),secondary=Color(0xFF126B62),secondaryContainer=Color(0xFFE3F1EE),onSecondaryContainer=Color(0xFF126B62),surface=Color.White,background=Color.White)) { Surface { PlaybackScreen(intent.getStringExtra("uri"),this) } } }
 }
}
private fun time(ms:Long):String = java.lang.String.format(java.util.Locale.ROOT,"%02d:%02d:%02d",ms/3600000,(ms/60000)%60,(ms/1000)%60)
@Composable private fun PlaybackScreen(initialUri:String?,activity:PlaybackActivity,vm:PlaybackViewModel=viewModel()) {
 val rows by vm.recordings.collectAsState(initial=emptyList())
 val cameras by vm.cameras.collectAsState(initial=emptyList())
 var dateText by rememberSaveable { mutableStateOf<String?>(null) }
 var initialized by rememberSaveable { mutableStateOf(false) }
 var mask by rememberSaveable { mutableIntStateOf(0) }
 var full by rememberSaveable { mutableStateOf(false) }
 var deleting by remember { mutableStateOf<Recording?>(null) }
 var menu by remember { mutableStateOf(false) }
 val current=rows.firstOrNull { it.uri==vm.uri }
 val initial=rows.firstOrNull { it.uri==initialUri }
 LaunchedEffect(rows.isNotEmpty()) {
  if(!initialized && rows.isNotEmpty()) {
   dateText=(if(initial!=null) initial.recordedDate() else rows.mapNotNull { it.recordedDate() }.maxOrNull())?.toString()
   initialized=true
   initial?.let { vm.open(it.uri) }
  }
 }
 val date=dateText?.let { LocalDate.parse(it) }
 val filtered=remember(rows,dateText,mask) { browseRecordings(rows,date,mask) }
 fun exitFull() { full=false;activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
 BackHandler(full) { exitFull() }
 fun toggleFull() { if(full)exitFull() else { full=true;activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE } }
 val cameraName=cameras.firstOrNull { it.id==current?.cameraId }?.name ?: cameras.singleOrNull()?.name ?: "录像浏览"
 Column(Modifier.fillMaxSize().safeDrawingPadding()) {
  if(!full) Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
   TextButton(onClick={activity.finish()},modifier=Modifier.heightIn(min=48.dp)) { Text("‹ 返回") }
   Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally) { Text(cameraName,style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis);Text("本地回放",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
   Box { TextButton(onClick={menu=true},enabled=current!=null && !vm.working,modifier=Modifier.heightIn(min=48.dp)) { Text("更多") }
    DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
     DropdownMenuItem(text={Text("保存到相册")},onClick={menu=false;current?.let { vm.export(it) }})
     DropdownMenuItem(text={Text("删除此录像")},onClick={menu=false;deleting=current})
    }
   }
  }
  PlayerPanel(vm,full,Modifier.then(if(full)Modifier.weight(1f) else Modifier.fillMaxWidth().aspectRatio(16f/9f)),::toggleFull)
  if(!full) {
   current?.let { Text("正在播放 · ${it.recordedTime()} · ${if(it.sourceMask==3) "手动＋活动" else recordingSourceLabel(it.sourceMask)}",modifier=Modifier.padding(horizontal=16.dp,vertical=6.dp),style=MaterialTheme.typography.labelMedium) }
   Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
    TextButton(onClick={dateText=(date ?: LocalDate.now()).minusDays(1).toString()},modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp)) { Text("前一天") }
    TextButton(onClick={val chosen=date ?: LocalDate.now();android.app.DatePickerDialog(activity,{_,year,month,day -> dateText=LocalDate.of(year,month+1,day).toString()},chosen.year,chosen.monthValue-1,chosen.dayOfMonth).show()},modifier=Modifier.weight(1f).heightIn(min=48.dp)) { Text(date?.toString() ?: "时间未知 · 选择日期") }
    TextButton(onClick={dateText=(date ?: LocalDate.now()).plusDays(1).toString()},modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp)) { Text("后一天") }
   }
   Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
    listOf(0 to "全部",1 to "手动",2 to "活动").forEach { (value,label) -> FilterChip(selected=mask==value,onClick={mask=value},label={Text(label)},modifier=Modifier.heightIn(min=48.dp)) }
    if(rows.any { it.recordedDate()==null }) FilterChip(selected=date==null,onClick={dateText=null},label={Text("时间未知")},modifier=Modifier.heightIn(min=48.dp))
   }
   if(vm.message.isNotEmpty()) Text(vm.message,Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.bodySmall)
   BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
    val columns=if(maxWidth<360.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale>1.2f)2 else 3
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
     if(filtered.isEmpty()) item { Text("这个日期和分类下暂无录像",Modifier.padding(20.dp),color=MaterialTheme.colorScheme.onSurfaceVariant) }
     filtered.groupBy { if(it.recordedDate()==null) "时间未知" else it.recordedTime().take(2)+":00" }.forEach { (hour,group) ->
      item(key="hour-$hour") { Text(hour,Modifier.padding(start=4.dp,top=12.dp,bottom=4.dp),style=MaterialTheme.typography.titleSmall) }
      items(group.chunked(columns),key={it.first().uri}) { chunk -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
       chunk.forEach { row -> Thumbnail(row,vm,Modifier.weight(1f)) }
       repeat(columns-chunk.size) { Spacer(Modifier.weight(1f)) }
      } }
     }
    }
   }
  }
 }
 deleting?.let { row -> AlertDialog(onDismissRequest={deleting=null},title={Text("删除录像？")},text={Text("应用内录像删除后无法恢复；已导出的副本保留。旧版相册录像将删除原文件。")},confirmButton={TextButton(onClick={deleting=null;vm.delete(row)}) { Text("删除") }},dismissButton={TextButton(onClick={deleting=null}) { Text("取消") }}) }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PlayerPanel(vm:PlaybackViewModel,full:Boolean,modifier:Modifier,onFull:()->Unit) {
 val state by vm.player.playback.collectAsState()
 var dragging by remember(vm.uri) { mutableStateOf<Float?>(null) }
 var speeds by remember { mutableStateOf(false) }
 Box(modifier.background(Color.Black)) {
  AndroidView(factory={createVideoView(it,vm.player)},modifier=Modifier.fillMaxSize(),onRelease={vm.player.detach(it)})
  if(vm.uri==null) Text("选择下方录像开始回放",Modifier.align(Alignment.Center),color=Color.White)
  Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha=0.65f))) {
   Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
    TextButton(onClick={if(state.playing)vm.player.pausePlayback() else vm.player.resumePlayback()},enabled=vm.uri!=null) { Text(if(state.playing) "暂停" else "播放",color=Color.White) }
    TextButton(onClick={vm.player.seekPlayback(state.positionMs-10000)},enabled=state.seekable) { Text("−10秒",color=Color.White) }
    TextButton(onClick={vm.player.seekPlayback(state.positionMs+10000)},enabled=state.seekable) { Text("＋10秒",color=Color.White) }
    Box { TextButton(onClick={speeds=true},enabled=vm.uri!=null) { Text("${state.speed}×",color=Color.White) }
     DropdownMenu(expanded=speeds,onDismissRequest={speeds=false}) { listOf(0.5f,1f,1.5f,2f).forEach { rate -> DropdownMenuItem(text={Text("${rate}×")},onClick={vm.player.speedPlayback(rate);speeds=false}) } }
    }
    TextButton(onClick=onFull) { Text(if(full) "退出全屏" else "全屏",color=Color.White) }
   }
   Text("${time(dragging?.toLong() ?: state.positionMs)} / ${time(state.durationMs)}",Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.labelSmall,color=Color.White)
   Slider(value=(dragging ?: state.positionMs.toFloat()).coerceIn(0f,state.durationMs.coerceAtLeast(1).toFloat()),onValueChange={dragging=it},onValueChangeFinished={dragging?.let { vm.player.seekPlayback(it.toLong()) };dragging=null},valueRange=0f..state.durationMs.coerceAtLeast(1).toFloat(),enabled=state.seekable && state.durationMs>0,thumb={ Box(Modifier.size(14.dp).background(Color.White,androidx.compose.foundation.shape.CircleShape)) },track={ SliderDefaults.Track(sliderState=it,modifier=Modifier.height(3.dp)) },modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp))
  }
  state.error?.let { Text(it,Modifier.align(Alignment.TopCenter).background(Color.Black.copy(alpha=0.7f)).padding(12.dp),color=Color.White) }
 }
}
@Composable private fun Thumbnail(row:Recording,vm:PlaybackViewModel,modifier:Modifier) {
 var bitmap by remember(row.uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
 var duration by remember(row.uri) { mutableStateOf<Long?>(null) }
 LaunchedEffect(row.uri) {
  try { bitmap=vm.thumbnails.load(row.uri);duration=if(row.startedAt!=null && row.endedAt!=null) row.endedAt!!-row.startedAt!! else vm.thumbnails.duration(row.uri) }
  catch(e:Exception) { if(e is CancellationException)throw e }
 }
 val selected=vm.uri==row.uri
 Column(modifier.clickable { vm.open(row.uri) }) {
  Box(Modifier.fillMaxWidth().aspectRatio(16f/9f).background(Color(0xFF303538)).border(if(selected)2.dp else 0.dp,if(selected)MaterialTheme.colorScheme.primary else Color.Transparent)) {
   bitmap?.let { Image(it.asImageBitmap(),contentDescription="${row.recordedTime()}录像缩略图",modifier=Modifier.fillMaxSize(),contentScale=androidx.compose.ui.layout.ContentScale.Crop) }
   Text(row.recordedTime(),Modifier.align(Alignment.TopStart).background(Color.Black.copy(alpha=0.55f)).padding(3.dp),style=MaterialTheme.typography.labelSmall,color=Color.White,maxLines=1)
   duration?.let { Text("${it/1000}秒",Modifier.align(Alignment.TopEnd).background(Color.Black.copy(alpha=0.55f)).padding(3.dp),style=MaterialTheme.typography.labelSmall,color=Color.White) }
   if(selected) Text("播放中",Modifier.align(Alignment.BottomEnd).background(MaterialTheme.colorScheme.primary).padding(3.dp),style=MaterialTheme.typography.labelSmall,color=Color.White)
  }
  Text(if(row.sourceMask==3) "手动＋活动" else recordingSourceLabel(row.sourceMask),Modifier.padding(vertical=6.dp),style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
 }
}
