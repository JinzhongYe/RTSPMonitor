package com.rtspmonitor.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rtspmonitor.core.*
import com.rtspmonitor.data.Camera
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SingleCameraConfigTest {
 @Test fun savingUpdatesSameCameraAndRejectsEmbeddedCredentials() = runBlocking {
  val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MonitorApp
  assertTrue("Run on a clean test installation", app.database.dao().cameras().first().isEmpty())
  val vm = withContext(Dispatchers.Main) { MonitorViewModel(app) }
  try {
   withContext(Dispatchers.Main) { vm.save("Test", "rtsp://192.0.2.1/stream1", "admin", "test-only", true) }.join()
   val original = app.database.dao().cameras().first().single()
   assertEquals(original.id, vm.activeId)
   withContext(Dispatchers.Main) { vm.save("Updated", "rtsp://192.0.2.2/stream1", "admin", "test-only", true) }.join()
   val updated = app.database.dao().cameras().first().single()
   assertEquals(original.id, updated.id)
   assertEquals("Updated", updated.name)
   assertEquals("test-only", app.vault.decrypt(updated.encryptedConnection).password)
   assertFalse(updated.encryptedConnection.contains("test-only"))
   withContext(Dispatchers.Main) { vm.save("Invalid", "rtsp://admin:fake@192.0.2.3/stream1", "", "", true) }.join()
   assertEquals(updated, app.database.dao().cameras().first().single())
   val legacy = Camera("legacy-camera", "Legacy", updated.encryptedConnection)
   app.database.dao().save(legacy)
   withContext(Dispatchers.Main) {
    vm.selectCamera(updated)
    app.sessions.connect(legacy)
    app.sessions.start(legacy)
    assertEquals(ConnectionState.DISCONNECTED, app.sessions.session(legacy.id).player.status.value.state)
    assertNull(app.sessions.session(legacy.id).job)
   }
   assertEquals(2, app.database.dao().cameras().first().size)
   val recreated = withContext(Dispatchers.Main) { MonitorViewModel(app) }
   assertEquals(updated.id, recreated.activeId)
   withContext(Dispatchers.Main) { recreated.localPlayer.release() }
   assertEquals(1, app.sessions.decodeLimit)
   assertEquals(1, app.sessions.recordingLimit)
  } finally {
   withContext(Dispatchers.Main) { app.sessions.stopAll(); vm.localPlayer.release() }
   app.database.dao().cameras().first().forEach { app.database.dao().delete(it) }
   vm.preferences.edit().remove("active_camera").commit()
  }
 }
}
