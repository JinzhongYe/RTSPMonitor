package com.rtspmonitor.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.rtspmonitor.core.*
import com.rtspmonitor.data.Camera
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class PlaybackIsolationTest {
 @Test fun activityNotificationSurvivesRecordingStatusAndMixedSourceIsSaved()=runBlocking {
  assumeTrue("Explicit device test: MediaPipe RDSVL is unsupported on this ARM64 emulator",InstrumentationRegistry.getArguments().getString("runActivityNotifications")=="true")
  val base=InstrumentationRegistry.getArguments().getString("rtspBase");assumeTrue(base!=null)
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MonitorApp
  val id=UUID.randomUUID().toString()
  val camera=Camera(id,"Activity-Test",app.vault.encrypt(Connection("$base/h264","","",Transport.TCP)))
  app.database.dao().save(camera)
  app.getSharedPreferences("monitor",0).edit().putString("active_camera",id).putBoolean("manual",false).putBoolean("auto",false).putBoolean("preview",false).commit()
  val manager=app.getSystemService(android.app.NotificationManager::class.java)
  try {
   ActivityScenario.launch(MainActivity::class.java).use { scenario ->
    scenario.onActivity { it.command("AUTO_ON",id) }
    withTimeout(30000) { while(app.sessions.session(id).player.frameListener==null)delay(100) }
    // Inject synthetic camera frames through the same callback used by decoder output.
    for(color in listOf(android.graphics.Color.BLACK,android.graphics.Color.WHITE,android.graphics.Color.BLACK)) {
     val bitmap=android.graphics.Bitmap.createBitmap(320,320,android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
     app.sessions.session(id).player.frameListener!!(bitmap,android.os.SystemClock.elapsedRealtime());delay(1000)
    }
    withTimeout(30000) { while(!app.sessions.automaticRecording.value || app.sessions.states.value[id]?.recordingState!=RecordingState.RECORDING)delay(100) }
    val notice=manager.activeNotifications.first { it.id>100 && it.notification.extras.getString(android.app.Notification.EXTRA_TEXT)?.contains("Activity-Test")==true }
    val text=notice.notification.extras.getString(android.app.Notification.EXTRA_TEXT)
    scenario.onActivity { it.command("START",id) }
    delay(2500)
    assertEquals(text,manager.activeNotifications.single { it.id==notice.id }.notification.extras.getString(android.app.Notification.EXTRA_TEXT))
    scenario.onActivity { it.command("AUTO_OFF",id);it.command("STOP",id) }
    withTimeout(30000) { while(app.sessions.session(id).job?.isCompleted==false)delay(100) }
    val recording=app.database.dao().recordings().first().single { it.cameraId==id }
    assertEquals(3,recording.sourceMask);assertTrue(recording.name.endsWith("_混合.mp4"))
   }
  } finally {
   app.startService(Intent(app,RecordingForegroundService::class.java).setAction("STOP_ALL"));delay(1500)
   withContext(Dispatchers.Main) { app.sessions.remove(camera) }
   app.database.dao().recordings().first().filter { it.cameraId==id }.forEach { app.store.delete(it) }
   manager.activeNotifications.filter { it.notification.extras.getString(android.app.Notification.EXTRA_TEXT)?.contains("Activity-Test")==true }.forEach { manager.cancel(it.id) }
  }
 }
 @Test fun controlsAndRecreationDoNotRestartForegroundRecording()=runBlocking {
  val base=InstrumentationRegistry.getArguments().getString("rtspBase");assumeTrue(base!=null)
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  val app=instrumentation.targetContext.applicationContext as MonitorApp
  val id=UUID.randomUUID().toString()
  val camera=Camera(id,"Isolation",app.vault.encrypt(Connection("$base/h264","","",Transport.TCP)))
  app.database.dao().save(camera)
  app.getSharedPreferences("monitor",0).edit().putString("active_camera",id).putBoolean("manual",false).putBoolean("auto",false).putBoolean("preview",false).commit()
  val fixture=File(app.store.temporary,"$id.mp4")
  instrumentation.context.assets.open("h264.mp4").use { input -> fixture.outputStream().use { input.copyTo(it) } }
  app.store.publish(fixture,camera)
  val item=app.database.dao().recordings().first().single { it.cameraId==id }
  try {
   ActivityScenario.launch(MainActivity::class.java).use { main ->
    main.onActivity { it.command("START",id) }
    withTimeout(30000) { while(app.sessions.states.value[id]?.recordingState!=RecordingState.RECORDING)delay(100) }
    val job=app.sessions.session(id).job
    ActivityScenario.launch<PlaybackActivity>(Intent(app,PlaybackActivity::class.java).putExtra("uri",item.uri)).use { playback ->
     lateinit var vm:PlaybackViewModel
     playback.onActivity { vm=androidx.lifecycle.ViewModelProvider(it)[PlaybackViewModel::class.java] }
     withTimeout(20000) { while(!vm.player.playback.value.seekable)delay(100) }
     for(speed in listOf(0.5f,1f,1.5f,2f)) {
      playback.onActivity { vm.player.speedPlayback(speed);vm.player.seekPlayback(1000);vm.player.pausePlayback();vm.player.resumePlayback() }
      delay(500)
      assertSame(job,app.sessions.session(id).job);assertTrue(job!!.isActive)
     }
     playback.recreate()
     playback.onActivity { assertSame(vm,androidx.lifecycle.ViewModelProvider(it)[PlaybackViewModel::class.java]) }
    }
    assertTrue(job!!.isActive)
    withContext(Dispatchers.Main) { app.sessions.stop(id) }
    val recorded=app.database.dao().recordings().first().single { it.cameraId==id && it.uri!=item.uri }
    assertEquals(SOURCE_MANUAL,recorded.sourceMask)
    assertTrue(recorded.endedAt!!-recorded.startedAt!!>=2000)
    assertTrue(recorded.name.endsWith("_手动.mp4"))
   }
  } finally {
   app.startService(Intent(app,RecordingForegroundService::class.java).setAction("STOP_ALL"))
   delay(1500)
   withContext(Dispatchers.Main) { app.sessions.remove(camera) }
   app.database.dao().recordings().first().filter { it.cameraId==id }.forEach { app.store.delete(it) }
  }
 }
}
