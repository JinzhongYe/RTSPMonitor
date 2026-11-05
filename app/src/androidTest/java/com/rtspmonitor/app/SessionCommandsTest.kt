package com.rtspmonitor.app
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rtspmonitor.core.*
import com.rtspmonitor.data.Camera
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class) class SessionCommandsTest {
 @Test fun stopAndRestartAreSerialized() = runBlocking {
  val base=InstrumentationRegistry.getArguments().getString("rtspBase")
  assumeTrue(base!=null)
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MonitorApp
  val camera=Camera(UUID.randomUUID().toString(),"Commands",app.vault.encrypt(Connection("$base/h264","","",Transport.TCP)))
  app.database.dao().save(camera)
  try {
   ActivityScenario.launch(MainActivity::class.java).use { scenario ->
    scenario.onActivity { it.command("START",camera.id) }
    repeat(3) {
     withTimeout(30_000) { while(app.sessions.states.value[camera.id]?.recordingState!=RecordingState.RECORDING) delay(100) }
     delay(300)
     withContext(Dispatchers.Main) {
      val stopping=async { app.sessions.stop(camera.id) }
      val restarting=async { app.sessions.start(camera) }
      stopping.await(); restarting.await()
     }
    }
    withTimeout(30_000) { while(app.sessions.states.value[camera.id]?.recordingState!=RecordingState.RECORDING) delay(100) }
    assertTrue(app.sessions.session(camera.id).job?.isActive==true)
   }
  } finally {
   withContext(Dispatchers.Main) { app.sessions.remove(camera) }
   app.database.dao().recordings().first().filter { it.cameraId==camera.id }.forEach { app.store.delete(it) }
  }
 }
 @Test fun decodeQuotaAlsoAppliesToSoutProbe()=runBlocking {
  val base=InstrumentationRegistry.getArguments().getString("rtspBase")
  assumeTrue(base!=null)
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MonitorApp
  val cameras=listOf("first","second").map { Camera(UUID.randomUUID().toString(),it,app.vault.encrypt(Connection("$base/h264","","",Transport.TCP))) }
  cameras.forEach { app.database.dao().save(it) }
  try {
   withContext(Dispatchers.Main) {
    app.sessions.connect(cameras[0]); app.sessions.connect(cameras[1]); app.sessions.probe(cameras[1])
    assertEquals(ConnectionState.DISCONNECTED,app.sessions.session(cameras[1].id).player.status.value.state)
    assertTrue(app.sessions.message.value.contains("一路"))
   }
  } finally {
   withContext(Dispatchers.Main) { cameras.forEach { app.sessions.remove(it) } }
  }
 }

}
