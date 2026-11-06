package com.rtspmonitor.app

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.rtspmonitor.data.Camera
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class PlaybackLayoutTest {
 @Test fun browseCompletedRecordingsAndCaptureLayout()=runBlocking {
  val args=InstrumentationRegistry.getArguments()
  assumeTrue(args.getString("captureLayout")=="true")
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  val app=instrumentation.targetContext.applicationContext as MonitorApp
  val id=UUID.randomUUID().toString()
  val camera=Camera(id,"办公室摄像头 · 测试","not-used")
  app.database.dao().save(camera)
  try {
   repeat(9) { index ->
    val input=File(app.store.temporary,"${UUID.randomUUID()}.mp4")
    instrumentation.context.assets.open("h264.mp4").use { src -> input.outputStream().use { src.copyTo(it) } }
    val start=java.time.Instant.parse("2026-11-05T13:00:00Z").toEpochMilli()-index*600000L
    File(input.parentFile,input.nameWithoutExtension+".meta").writeText(org.json.JSONObject().put("startedAt",start).put("endedAt",start+6000).put("offsetMinutes",480).put("sources",index%3+1).toString())
    app.store.publish(input,camera)
   }
   val rows=app.database.dao().recordings().first().filter { it.cameraId==id }
   val selected=rows.maxBy { it.startedAt!! }
   ActivityScenario.launch<PlaybackActivity>(Intent(app,PlaybackActivity::class.java).putExtra("uri",selected.uri)).use { scenario ->
    delay(2000)
    lateinit var vm:PlaybackViewModel
    scenario.onActivity { vm=androidx.lifecycle.ViewModelProvider(it)[PlaybackViewModel::class.java] }
    withTimeout(15000) { while(!vm.player.playback.value.seekable)delay(100) }
    assertNotNull(vm.thumbnails.load(selected.uri))
    assertEquals(selected.uri,vm.uri)
    if(args.getString("landscape")=="true") {
     // The host taps the visible full-screen control during this manual screenshot interval.
     delay(60000)
     assertEquals(android.content.res.Configuration.ORIENTATION_LANDSCAPE,app.resources.configuration.orientation)
    }
    val image=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
    val folder=File(app.getExternalFilesDir("layout-results"),"playback").apply { mkdirs() }
    File(folder,args.getString("screenshotName") ?: "layout.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
    image.recycle()
    assertTrue(File(folder,args.getString("screenshotName") ?: "layout.png").length()>0)
   }
  } finally {
   app.database.dao().recordings().first().filter { it.cameraId==id }.forEach { app.store.delete(it) }
   app.database.dao().delete(camera)
  }
 }
}
