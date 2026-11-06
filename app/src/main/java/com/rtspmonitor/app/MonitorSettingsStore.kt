package com.rtspmonitor.app

import android.content.Context
import com.rtspmonitor.core.MonitorSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MonitorSettingsStore(context:Context) {
 private val prefs=context.getSharedPreferences("detection-recording-settings",Context.MODE_PRIVATE)
 private val mutable=MutableStateFlow(runCatching { MonitorSettings(prefs.getInt("person",60),prefs.getInt("area",3),prefs.getInt("brightness",25),prefs.getInt("manualMinutes",5)) }.getOrDefault(MonitorSettings()))
 val state=mutable.asStateFlow()
 fun save(value:MonitorSettings) {
  check(prefs.edit().putInt("person",value.personConfidence).putInt("area",value.motionAreaPercent).putInt("brightness",value.brightnessDifference).putInt("manualMinutes",value.manualSegmentMinutes).commit()) { "设置保存失败" }
  mutable.value=value
 }
}
