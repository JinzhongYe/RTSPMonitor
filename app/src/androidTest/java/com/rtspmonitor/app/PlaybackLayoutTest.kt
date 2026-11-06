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
 @Test fun controlsStayOutsidePortraitAndHideOnlyDuringFullscreenPlayback()=runBlocking {
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  val app=instrumentation.targetContext.applicationContext as MonitorApp
  val id=UUID.randomUUID().toString()
  val camera=Camera(id,"Layout fixture","not-used")
  val input=File(app.store.temporary,"$id.mp4")
  instrumentation.context.assets.open("h264.mp4").use { src -> input.outputStream().use { src.copyTo(it) } }
  app.database.dao().save(camera);app.store.publish(input,camera)
  val row=app.database.dao().recordings().first().single { it.cameraId==id }
  fun find(test:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo? {
   fun visit(node:AccessibilityNodeInfo):AccessibilityNodeInfo? {
    if(test(node))return node
    for(i in 0 until node.childCount)node.getChild(i)?.let { visit(it)?.let { found -> return found } }
    return null
   }
   return instrumentation.uiAutomation.rootInActiveWindow?.let { visit(it) }
  }
  fun capture(name:String) {
   android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/playback-$name.png")).use { it.readBytes() }
  }
  fun panel()=find { it.contentDescription?.toString()=="回放控制条" }
  fun video()=find { it.contentDescription?.toString()=="回放画面" }
  fun click(text:String) {
   var node=checkNotNull(find { it.text?.toString()==text }) { "Missing $text" }
   while(!node.isClickable)node=checkNotNull(node.parent)
   check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
  }
  try {
   ActivityScenario.launch<PlaybackActivity>(Intent(app,PlaybackActivity::class.java).putExtra("uri",row.uri)).use { scenario ->
    lateinit var vm:PlaybackViewModel
    scenario.onActivity { vm=androidx.lifecycle.ViewModelProvider(it)[PlaybackViewModel::class.java] }
    withTimeout(15000) { while(!vm.player.playback.value.seekable || panel()==null)delay(100) }
    val picture=android.graphics.Rect();val controls=android.graphics.Rect()
    checkNotNull(video()).getBoundsInScreen(picture);checkNotNull(panel()).getBoundsInScreen(controls)
    assertTrue("Portrait controls must not overlap the video",controls.top>=picture.bottom)
    capture("portrait")
    scenario.onActivity { vm.player.pausePlayback();vm.player.speedPlayback(0.5f) }
    withTimeout(3000) { while(vm.player.playback.value.playing)delay(100) }
    click("全屏")
    withTimeout(10000) { while(app.resources.configuration.orientation!=android.content.res.Configuration.ORIENTATION_LANDSCAPE)delay(100) }
    delay(3700);assertNotNull("Paused controls stay visible",panel())
    scenario.onActivity { vm.player.seekPlayback(0);vm.player.resumePlayback() }
    withTimeout(5000) { while(!vm.player.playback.value.playing)delay(100) }
    delay(3800);assertNull("Playing fullscreen controls auto-hide",panel());capture("fullscreen-hidden")
    assertTrue(checkNotNull(video()).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    withTimeout(3000) { while(panel()==null)delay(100) }
    click("暂停");delay(3700);assertNotNull(panel());capture("fullscreen-paused")
    scenario.onActivity { vm.player.seekPlayback(0);vm.player.resumePlayback() }
    click("0.5×");delay(3700);assertNotNull("Open menu holds controls",panel())
    click("1.5×")
    withTimeout(3000) { while(vm.player.playback.value.speed!=1.5f)delay(100) }
    val position=vm.player.playback.value.positionMs
    click("退出全屏")
    withTimeout(10000) { while(app.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE)delay(100) }
    assertTrue("Rotation preserves playback position",vm.player.playback.value.positionMs>=position-1000)
   }
  } finally { app.store.delete(row);app.database.dao().delete(camera) }
 }
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
