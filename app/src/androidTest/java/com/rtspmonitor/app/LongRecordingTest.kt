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

@RunWith(AndroidJUnit4::class)
class LongRecordingTest {
    @Test fun defaultFiveMinuteSegmentsInOneForegroundSession() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("longRecording") == "true" && args.getString("rtspBase") != null)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MonitorApp
        val cameras = listOf("h264").map { codec -> Camera(UUID.randomUUID().toString(), "Long-$codec", app.vault.encrypt(Connection("${args.getString("rtspBase")}/$codec", "", "", Transport.TCP))) }
        cameras.forEach { app.database.dao().save(it) }
        val evidence = org.json.JSONArray()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                cameras.forEach { camera -> scenario.onActivity { it.command("START", camera.id) } }
                withTimeout(30_000) { while(cameras.any { app.sessions.states.value[it.id]?.recordingState != RecordingState.RECORDING }) delay(100) }
                shell("input keyevent KEYCODE_HOME")
                shell("input keyevent KEYCODE_SLEEP")
                // Real wall-clock duration: no accelerated segmentation override.
                repeat(61) {
                    delay(10_000)
                    cameras.forEach { camera -> assertEquals(RecordingState.RECORDING, app.sessions.states.value[camera.id]?.recordingState) }
                }
                withContext(Dispatchers.Main) { cameras.forEach { app.sessions.stop(it.id) } }
                cameras.forEach { camera ->
                    val videos = app.database.dao().recordings().first().filter { it.cameraId == camera.id }.sortedBy { it.createdAt }
                    assertEquals("Two full 300-second segments plus final tail", 3, videos.size)
                    videos.forEachIndexed { index, video ->
                        val extractor = MediaExtractor()
                        var last = -1L
                        try {
                            extractor.setDataSource(app, Uri.parse(video.uri), null)
                            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                            extractor.selectTrack(track)
                            assertTrue(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                            while(extractor.sampleTime >= 0) { last=extractor.sampleTime; if(!extractor.advance()) break }
                            if(index < 2) assertTrue("Full segment duration", last in 299_000_000L..302_000_000L)
                            else assertTrue(last > 0)
                        } finally { extractor.release() }
                        val output=File(app.getExternalFilesDir("long-results"), "${camera.name}-$index.mp4")
                        app.contentResolver.openInputStream(Uri.parse(video.uri))!!.use { input -> output.outputStream().use { input.copyTo(it) } }
                        evidence.put(org.json.JSONObject().put("name",output.name).put("lastVideoPtsUs",last))
                    }
                }
                File(app.getExternalFilesDir("long-results"),"result.json").writeText(evidence.toString(2))
            }
        } finally {
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
            withContext(Dispatchers.Main) { cameras.forEach { app.sessions.remove(it) } }
            app.database.dao().recordings().first().filter { video -> cameras.any { it.id == video.cameraId } }.forEach { app.store.delete(it) }
        }
    }
    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
}
