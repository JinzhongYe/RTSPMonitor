package com.rtspmonitor.media

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rtspmonitor.core.Connection
import com.rtspmonitor.core.Transport
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Opt-in against synthetic sources: -e rtspBase rtsp://10.0.2.2:18554 */
@RunWith(AndroidJUnit4::class)
class RtspRecordingTest {
    @Test fun sourceChangesAreUnionedWithinOneSegment()=runBlocking {
        val base=InstrumentationRegistry.getArguments().getString("rtspBase");assumeTrue(base!=null)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=File(context.getExternalFilesDir("spike-results"),"sources-${System.currentTimeMillis()}").apply { mkdirs() }
        val frames=java.util.concurrent.atomic.AtomicInteger()
        val engine=AvRecorder().apply { sourceProvider={if(frames.incrementAndGet()<=5) 1 else 2} }
        val began=CompletableDeferred<Unit>();val closed=CompletableDeferred<File>()
        val job=async(Dispatchers.IO) { engine.record(Connection("$base/h264","","",Transport.TCP),folder,2) { kind,value ->
            if(kind=="recording")began.complete(Unit)
            if(kind=="segment")closed.complete(File(value))
        } }
        try {
            withTimeout(20000) { began.await() }
            val file=withTimeout(20000) { closed.await() }
            engine.stop();assertEquals(0,job.await())
            val metadata=org.json.JSONObject(File(folder,file.nameWithoutExtension+".meta").readText())
            assertEquals(3,metadata.getInt("sources"))
            assertTrue(metadata.getLong("endedAt")>metadata.getLong("startedAt"))
            checkVideo(file,"video/avc")
        } finally { engine.stop();withContext(NonCancellable) { job.join() };engine.close() }
    }
    @Test fun segmentLimitCanShrinkAndGrowWithoutRestart()=runBlocking {
        val base=InstrumentationRegistry.getArguments().getString("rtspBase");assumeTrue(base!=null)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=File(context.getExternalFilesDir("spike-results"),"limits-${System.currentTimeMillis()}").apply { mkdirs() }
        val engine=AvRecorder()
        val segments=CopyOnWriteArrayList<File>();var opened=0
        val job=async(Dispatchers.IO) { engine.record(Connection("$base/h264","","",Transport.TCP),folder,8) { kind,value ->
            if(kind=="recording") { opened++;engine.setSegmentSeconds(if(opened==1)2 else 4) }
            if(kind=="segment")segments.add(File(value))
        } }
        try {
            withTimeout(30000) { while(segments.size<2)delay(100) }
            assertTrue(job.isActive);engine.stop();assertEquals(0,job.await())
            segments.take(2).forEachIndexed { index,file ->
                val extractor=MediaExtractor()
                try {
                    extractor.setDataSource(file.path);extractor.selectTrack(0)
                    var last=0L;while(extractor.sampleTime>=0) { last=maxOf(last,extractor.sampleTime);if(!extractor.advance())break }
                    assertTrue("Unexpected segment duration $last",if(index==0)last in 1800000..2500000 else last in 3800000..4500000)
                } finally { extractor.release() }
                checkVideo(file,"video/avc");decodeTrack(file,"video/avc")
            }
        } finally { engine.stop();withContext(NonCancellable) { job.join() };engine.close() }
    }
    @Test fun h264Tcp() = record("h264", Transport.TCP, "video/avc")
    @Test fun h264Udp() = record("h264", Transport.UDP, "video/avc")
    @Test fun h265Tcp() = record("h265", Transport.TCP, "video/hevc")
    @Test fun h264AacCopy() = record("h264aac", Transport.TCP, "video/avc", "audio/mp4a-latm")
    @Test fun h265AacCopy() = record("h265aac", Transport.TCP, "video/hevc", "audio/mp4a-latm")
    @Test fun alawIsDroppedWithWarning() = record("h264alaw", Transport.TCP, "video/avc", warningExpected = true)
    @Test fun h265Udp() = record("h265", Transport.UDP, "video/hevc")

    @Test fun twoRecordersRemainIndependentWhenOneStops() = runBlocking {
        val base = InstrumentationRegistry.getArguments().getString("rtspBase")
        assumeTrue("Provide local synthetic RTSP sources", base != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = AvRecorder(); val second = AvRecorder()
        val firstSegments = CopyOnWriteArrayList<File>(); val secondSegments = CopyOnWriteArrayList<File>()
        fun directory(name: String) = File(context.getExternalFilesDir("spike-results"), "$name-${System.currentTimeMillis()}").apply { mkdirs() }
        val firstJob = async(Dispatchers.IO) { first.record(Connection("$base/h264", "", "", Transport.TCP), directory("parallel-h264"), 2) { kind, value -> if(kind == "segment") firstSegments.add(File(value)) } }
        val secondJob = async(Dispatchers.IO) { second.record(Connection("$base/h265", "", "", Transport.TCP), directory("parallel-h265"), 2) { kind, value -> if(kind == "segment") secondSegments.add(File(value)) } }
        try {
            withTimeout(30_000) { while(firstSegments.size < 2 || secondSegments.size < 2) delay(100) }
            first.stop()
            assertEquals(0, firstJob.await())
            val count = secondSegments.size
            withTimeout(10_000) { while(secondSegments.size <= count) delay(100) }
            assertTrue("Stopping H.264 must not stop H.265", secondJob.isActive)
            second.stop(); assertEquals(0, secondJob.await())
            firstSegments.forEach { checkVideo(it, "video/avc"); decodeTrack(it, "video/avc") }
            secondSegments.forEach { checkVideo(it, "video/hevc"); decodeTrack(it, "video/hevc") }
        } finally {
            first.stop(); second.stop()
            withContext(NonCancellable) { firstJob.join(); secondJob.join() }
            first.close(); second.close()
        }
    }

    private fun record(path: String, transport: Transport, mime: String, audioMime: String? = null, warningExpected: Boolean = false) = runBlocking {
        val base = InstrumentationRegistry.getArguments().getString("rtspBase")
        assumeTrue("Provide a local synthetic RTSP source", base != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir("spike-results"), "$path-${transport.name}-${System.currentTimeMillis()}").apply { mkdirs() }
        val engine = AvRecorder()
        val segments = CopyOnWriteArrayList<File>()
        val warnings = CopyOnWriteArrayList<String>()
        val recording = async(Dispatchers.IO) {
            engine.record(Connection("$base/$path", "", "", transport), directory, 2) { kind, value ->
                if (kind == "segment") segments.add(File(value))
                if (kind == "warning") warnings.add(value)
            }
        }
        try {
            withTimeout(30_000) {
                while (segments.size < 3 && !recording.isCompleted) delay(100)
            }
            engine.stop()
            val result = withTimeout(20_000) { recording.await() }
            assertEquals("Native recording must stop cleanly", 0, result)
            assertTrue("Need three closed keyframe segments", segments.size >= 3)
            segments.forEach {
                checkVideo(it, mime); decodeTrack(it, mime)
                val extractor=MediaExtractor()
                try {
                    extractor.setDataSource(it.path)
                    val audio=(0 until extractor.trackCount).map { index -> extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) }.filter { type -> type?.startsWith("audio/")==true }
                    assertEquals(if(audioMime==null) emptyList<String>() else listOf(audioMime),audio)
                    if(audioMime!=null) {
                        val track=(0 until extractor.trackCount).first { index -> extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)==audioMime }
                        extractor.selectTrack(track)
                        var samples=0
                        while(extractor.sampleTime>=0) { samples++; if(!extractor.advance()) break }
                        // A very short final video tail may precede the next RTP audio bundle.
                        if(it.nameWithoutExtension.toInt()<3) assertTrue("AAC access units must not be dropped: $samples",samples>=24)
                        if(samples>0) decodeTrack(it,audioMime)
                    }
                } finally { extractor.release() }
            }
            if(warningExpected) assertTrue("Incompatible audio must be reported", warnings.any { it.contains("音频") })
            assertFalse(directory.listFiles()!!.any { it.extension == "part" })
        } catch (error: TimeoutCancellationException) {
            throw AssertionError("No three closed segments within 30 seconds", error)
        } finally {
            engine.stop()
            withContext(NonCancellable) { recording.join() }
            engine.close()
        }
    }

}

internal fun checkVideo(file: File, mime: String) {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.path)
        val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == mime }
        extractor.selectTrack(track)
        assertTrue("First sample must be a sync frame", extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
        assertTrue(extractor.sampleTime >= 0)
        var count = 0
        while (extractor.sampleTime >= 0) { count++; if (!extractor.advance()) break }
        assertTrue("Segment must contain video samples", count > 0)
    } finally { extractor.release() }
}
internal fun decodeTrack(file: File, mime: String) {
    val extractor = MediaExtractor()
    val codec = android.media.MediaCodec.createDecoderByType(mime)
    var started = false
    try {
        extractor.setDataSource(file.path)
        val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == mime }
        extractor.selectTrack(track)
        codec.configure(extractor.getTrackFormat(track), null, null, 0)
        codec.start(); started = true
        var inputEnded = false
        var outputEnded = false
        var frames = 0
        val info = android.media.MediaCodec.BufferInfo()
        val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
        while (!outputEnded) {
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "Video decoding timeout" }
            if (!inputEnded) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val input = codec.getInputBuffer(index)!!
                    input.clear()
                    val size = extractor.readSampleData(input, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val index = codec.dequeueOutputBuffer(info, 10_000)
            if (index >= 0) {
                if (info.size > 0) frames++
                outputEnded = info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(index, false)
            }
        }
        assertTrue("Every segment must decode", frames > 0)
    } finally {
        if (started) codec.stop()
        codec.release(); extractor.release()
    }
}

