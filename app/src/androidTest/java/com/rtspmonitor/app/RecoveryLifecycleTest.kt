package com.rtspmonitor.app
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
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
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class) class RecoveryLifecycleTest {
 private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MonitorApp
 private suspend fun await(condition: ()->Boolean)=withTimeout(40_000) { while(!condition()) delay(100) }
 private fun camera()=Camera(UUID.randomUUID().toString(),"Resilience",app.vault.encrypt(Connection("${InstrumentationRegistry.getArguments().getString("rtspBase")}/h264","","",Transport.TCP)))
 private fun marker(name: String)=File(app.getExternalFilesDir("control"),name)
 private fun samples(uri: String): Int {
  val extractor=MediaExtractor()
  try {
   extractor.setDataSource(app,Uri.parse(uri),null)
   val track=(0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)=="video/avc" }
   extractor.selectTrack(track)
   assertTrue(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC!=0)
   var count=0; while(extractor.sampleTime>=0) { count++; if(!extractor.advance()) break }; return count
  } finally { extractor.release() }
 }
 @Test fun interruptedSessionReconnectsIntoNewFile() = runBlocking {
  assumeTrue(InstrumentationRegistry.getArguments().getString("rtspBase")!=null)
  val camera=camera(); app.database.dao().save(camera)
  marker("reconnect-ready").delete()
  try {
   ActivityScenario.launch(MainActivity::class.java).use { scenario ->
    scenario.onActivity { it.command("START",camera.id) }
    await { app.sessions.states.value[camera.id]?.recordingState==RecordingState.RECORDING }
    delay(2_000)
    marker("reconnect-ready").writeText(camera.id)
    await { (app.sessions.states.value[camera.id]?.reconnectCount ?: 0)>0 && app.sessions.states.value[camera.id]?.recordingState==RecordingState.RECORDING }
    assertNull(app.sessions.states.value[camera.id]?.lastError)
    delay(2_000)
    withContext(Dispatchers.Main) { app.sessions.stop(camera.id) }
    val videos=app.database.dao().recordings().first().filter { it.cameraId==camera.id }
    assertTrue(videos.size>=2)
    assertEquals(videos.size,videos.map { it.uri }.distinct().size)
    assertTrue(videos.all { samples(it.uri)>0 })
   }
  } finally {
   marker("reconnect-ready").delete()
   withContext(Dispatchers.Main) { app.sessions.remove(camera) }
   app.database.dao().recordings().first().filter { it.cameraId==camera.id }.forEach { app.store.delete(it) }
  }
 }
 @Test fun prepareProcessDeath() = runBlocking {
  assumeTrue(InstrumentationRegistry.getArguments().getString("rtspBase")!=null)
  val camera=camera(); app.database.dao().save(camera)
  marker("crash-ready").delete()
  ActivityScenario.launch(MainActivity::class.java).use { scenario ->
   scenario.onActivity { it.command("START",camera.id) }
   await { app.sessions.states.value[camera.id]?.recordingState==RecordingState.RECORDING }
   delay(3_000)
   assertTrue(app.database.dao().recordings().first().none { it.cameraId==camera.id })
   marker("crash-ready").writeText(camera.id)
   delay(60_000)
   fail("Host controller must terminate this process after the ready marker")
  }
 }
 @Test fun recoverAfterProcessDeath() = runBlocking {
  val id=InstrumentationRegistry.getArguments().getString("recoveryCameraId")
  assumeTrue(id!=null)
  val camera=app.database.dao().camera(id!!)!!
  try {
   withContext(Dispatchers.Main) { app.sessions.recover() }
   val videos=app.database.dao().recordings().first().filter { it.cameraId==id }
   assertTrue("Recovered index must exist",videos.isNotEmpty())
   assertTrue("Recovered file must contain complete frames",videos.all { samples(it.uri)>=10 })
   File(app.getExternalFilesDir("control"),"recovered.json").writeText(org.json.JSONObject().put("files",videos.size).put("samples",videos.sumOf { samples(it.uri) }).toString())
  } finally {
   withContext(Dispatchers.Main) { app.sessions.remove(camera) }
   app.database.dao().recordings().first().filter { it.cameraId==id }.forEach { app.store.delete(it) }
   marker("crash-ready").delete()
  }
 }
}
