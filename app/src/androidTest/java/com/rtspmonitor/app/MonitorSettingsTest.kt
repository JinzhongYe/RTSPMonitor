package com.rtspmonitor.app
import androidx.test.platform.app.InstrumentationRegistry
import com.rtspmonitor.core.MonitorSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
class MonitorSettingsTest {
 @Test fun persistsReloadsAndRecoversInvalidPreferences()=runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val prefs=context.getSharedPreferences("detection-recording-settings",0)
  val original=MonitorSettingsStore(context).state.value
  try {
   prefs.edit().clear().commit()
   assertEquals(MonitorSettings(),MonitorSettingsStore(context).state.value)
   val changed=MonitorSettings(35,2,15,2)
   val store=MonitorSettingsStore(context);store.save(changed)
   assertEquals(changed,store.state.value);assertEquals(changed,MonitorSettingsStore(context).state.value)
   prefs.edit().putString("person","invalid").commit()
   assertEquals(MonitorSettings(),MonitorSettingsStore(context).state.value)
  } finally { MonitorSettingsStore(context).save(original) }
 }
}
