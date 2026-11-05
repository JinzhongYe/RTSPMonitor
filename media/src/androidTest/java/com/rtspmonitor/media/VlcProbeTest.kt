package com.rtspmonitor.media

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rtspmonitor.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

class ProbeActivity: Activity() {
    lateinit var player: VlcPlayer
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = VlcPlayer(this)
        setContentView(createVideoView(this, player))
    }
    override fun onDestroy() { player.release(); super.onDestroy() }
}

/** Opt-in capability probes. A failed probe is evidence against this recording route. */
@RunWith(AndroidJUnit4::class)
class VlcProbeTest {
    @Test fun h264Sout() = sout("h264")
    @Test fun h265Sout() = sout("h265")
    @Test fun h264LiveRecord() = liveRecord("h264")
    @Test fun h265LiveRecord() = liveRecord("h265")

    private fun source(path: String): Connection {
        val base = InstrumentationRegistry.getArguments().getString("rtspBase")
        assumeTrue("Provide a synthetic RTSP source", base != null)
        return Connection("$base/$path", "", "", Transport.TCP)
    }
    private fun directory(name: String): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.getExternalFilesDir("spike-results"), "$name-${System.currentTimeMillis()}").apply { mkdirs() }
    }
    private fun scenario(): ActivityScenario<ProbeActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return ActivityScenario.launch(Intent(context, ProbeActivity::class.java))
    }
    private fun sout(path: String) = runBlocking {
        val connection = source(path)
        val output = File(directory("sout-$path"), "probe.mp4")
        scenario().use { scenario ->
            scenario.onActivity { it.player.probeSout(connection, output) }
            delay(5_000)
            scenario.onActivity { it.player.disconnect() }
            assertTrue("Android sout must produce a nonempty MP4", output.exists() && output.length() > 0)
            checkVideo(output, if(path == "h264") "video/avc" else "video/hevc"); decodeTrack(output, if(path == "h264") "video/avc" else "video/hevc")
        }
    }
    private fun liveRecord(path: String) = runBlocking {
        val connection = source(path)
        val directory = directory("record-$path")
        lateinit var player: VlcPlayer
        scenario().use { scenario ->
            scenario.onActivity { player = it.player; player.connect(connection) }
            withTimeout(15_000) { while(player.status.value.state != ConnectionState.CONNECTED) delay(100) }
            val initialSessions = readerIds(path)
            if(initialSessions != null) assertEquals(1, initialSessions.size)
            scenario.onActivity { assertTrue("record() must accept start", player.probeRecord(directory)) }
            delay(4_000)
            scenario.onActivity { assertTrue("record(null) must accept stop", player.stopProbeRecord()) }
            withTimeout(10_000) { while(player.recordProbeStatus.value.path == null) delay(100) }
            assertEquals("Stopping recording must keep preview connected", ConnectionState.CONNECTED, player.status.value.state)
            if(initialSessions != null) assertEquals("Recording toggle must retain the RTSP session", initialSessions, readerIds(path))
            val output = File(player.recordProbeStatus.value.path!!)
            assertTrue("Same-session record must write MP4", output.extension.equals("mp4", true))
            checkVideo(output, if(path == "h264") "video/avc" else "video/hevc"); decodeTrack(output, if(path == "h264") "video/avc" else "video/hevc")
        }
    }
    private fun readerIds(path: String): Set<String>? {
        val api = InstrumentationRegistry.getArguments().getString("rtspApi") ?: return null
        val connection = java.net.URL("$api/v3/rtspsessions/list").openConnection().apply { connectTimeout=5_000; readTimeout=5_000 }
        val items = org.json.JSONObject(connection.getInputStream().bufferedReader().use { it.readText() }).getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it) }.filter { it.optString("state") == "read" && it.optString("path") == path }.map { it.getString("id") }.toSet()
    }

}
