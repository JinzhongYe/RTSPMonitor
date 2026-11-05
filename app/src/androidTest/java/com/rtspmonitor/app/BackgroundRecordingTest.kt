package com.rtspmonitor.app

import android.app.ActivityManager
import android.app.NotificationManager
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
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackgroundRecordingTest {
    @Test fun recordsAcrossHomeAndScreenOffThenNotificationStopFinalizes() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("notificationPermission") != "denied")
        recordAndStop(false)
    }
    @Test fun recordsWithNotificationPermissionDenied() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("notificationPermission") == "denied")
        recordAndStop(true)
    }
    private fun recordAndStop(notificationsDenied: Boolean) = runBlocking {
        val base = InstrumentationRegistry.getArguments().getString("rtspBase")
        assumeTrue("Provide local synthetic RTSP source", base != null)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MonitorApp
        val camera = Camera(UUID.randomUUID().toString(), "Background-Spike", app.vault.encrypt(Connection("$base/h264", "", "", Transport.TCP)))
        assertEquals(!notificationsDenied, app.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
        app.database.dao().save(camera)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.command("START", camera.id) }
                await { app.sessions.states.value[camera.id]?.recordingState == RecordingState.RECORDING }
                shell("input keyevent KEYCODE_HOME")
                shell("input keyevent KEYCODE_SLEEP")
                delay(8_000)
                assertEquals(RecordingState.RECORDING, app.sessions.states.value[camera.id]?.recordingState)
                if(notificationsDenied) {
                    shell("input keyevent KEYCODE_WAKEUP")
                    shell("wm dismiss-keyguard")
                    shell("am start -W -n com.rtspmonitor.app/.MainActivity")
                    scenario.onActivity { it.command("STOP", camera.id) }
                } else {
                    val notification = app.getSystemService(NotificationManager::class.java).activeNotifications.first { it.id == 1 }.notification
                    val stop = notification.actions.first { it.title.toString() == "停止全部录像" }
                    stop.actionIntent.send()
                }
                await { app.sessions.states.value[camera.id]?.recordingState == RecordingState.IDLE }
                val videos = app.database.dao().recordings().first().filter { it.cameraId == camera.id }
                assertTrue("Stop must publish a recording", videos.isNotEmpty())
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(app, Uri.parse(videos.single().uri), null)
                    val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "video/avc" }
                    extractor.selectTrack(track)
                    var last = -1L
                    while(extractor.sampleTime >= 0) { last = extractor.sampleTime; if(!extractor.advance()) break }
                    assertTrue("Video content must cover screen-off interval", last >= 7_000_000L)
                } finally { extractor.release() }
                await { app.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE).none { it.service.className == RecordingForegroundService::class.java.name } }
            }
        } finally {
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            withContext(Dispatchers.Main) { app.sessions.remove(camera) }
            app.database.dao().recordings().first().filter { it.cameraId == camera.id }.forEach { app.store.delete(it) }
        }
    }
    private suspend fun await(predicate: () -> Boolean) = withTimeout(30_000) { while(!predicate()) delay(100) }
    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ).use { it.readBytes() }
    }
}
