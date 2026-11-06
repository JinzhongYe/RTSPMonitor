package com.rtspmonitor.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import com.rtspmonitor.core.MonitorSettings
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun MonitorSettingsDialog(store:MonitorSettingsStore,dismiss:()->Unit) {
 val saved by store.state.collectAsState()
 var person by remember { mutableIntStateOf(saved.personConfidence) }
 var area by remember { mutableIntStateOf(saved.motionAreaPercent) }
 var brightness by remember { mutableIntStateOf(saved.brightnessDifference) }
 var minutes by remember { mutableStateOf(saved.manualSegmentMinutes.toString()) }
 var saving by remember { mutableStateOf(false) }
 var error by remember { mutableStateOf("") }
 val scope=rememberCoroutineScope()
 val parsed=minutes.toIntOrNull()
 AlertDialog(onDismissRequest={if(!saving)dismiss()},title={Text("检测与录像设置")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)) {
  Text("人形置信分：${person/100f}");Slider(person.toFloat(),{person=(it/5).roundToInt()*5},valueRange=10f..95f,steps=16,enabled=!saving)
  Text("画面变化面积：$area%");Slider(area.toFloat(),{area=it.roundToInt()},valueRange=1f..30f,steps=28,enabled=!saving)
  Text("像素亮度差：$brightness / 255");Slider(brightness.toFloat(),{brightness=it.roundToInt()},valueRange=5f..100f,steps=94,enabled=!saving)
  Text("以上数值调低更容易触发，也可能增加误报。保存后立即生效。",style=MaterialTheme.typography.bodySmall)
  OutlinedTextField(minutes,{minutes=it},label={Text("手动录像每段时长（分钟）")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,enabled=!saving,isError=parsed !in 1..60)
  Text("可设 1～60 分钟，默认 5 分钟。仅手动录像使用此值；活动录像仍为 5 分钟，重叠时手动优先。达到时长后在下一关键帧切段，不会自动停止录制。",style=MaterialTheme.typography.bodySmall)
  TextButton(enabled=!saving,onClick={person=60;area=3;brightness=25;minutes="5"}) { Text("恢复默认") }
  if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
 }},confirmButton={TextButton(enabled=!saving && parsed in 1..60,onClick={val selected=MonitorSettings(person,area,brightness,checkNotNull(parsed));scope.launch {
  saving=true
  try { withContext(Dispatchers.IO) { store.save(selected) };dismiss() }
  catch(e:Exception) { if(e is kotlinx.coroutines.CancellationException)throw e;error="保存失败，请重试" }
  finally { saving=false }
 }}) {Text(if(saving) "保存中…" else "保存")}},dismissButton={TextButton(enabled=!saving,onClick=dismiss) {Text("取消")}})
}
