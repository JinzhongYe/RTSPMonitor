package com.rtspmonitor.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SegmentPublisherTest {
    @Test fun slowPublicationDoesNotBlockReaderAndBacklogIsBounded() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val published = mutableListOf<String>()
        val publisher = SegmentPublisher(this, { file ->
            entered.complete(Unit)
            release.await()
            published.add(file.name)
        }, { fail("Unexpected publishing failure") })
        publisher.enqueue(File("first.mp4"))
        withTimeout(5_000) { entered.await() }
        publisher.enqueue(File("second.mp4"))
        publisher.enqueue(File("third.mp4"))
        assertTrue(runCatching { publisher.enqueue(File("fourth.mp4")) }.isFailure)
        release.complete(Unit)
        publisher.finish()
        assertEquals(listOf("first.mp4", "second.mp4", "third.mp4"), published)
    }

    @Test fun failedPublicationStopsReaderAndIsReportedToOwner() = runBlocking {
        val stopped = CompletableDeferred<Unit>()
        val publisher = SegmentPublisher(this, { error("storage full") }, { stopped.complete(Unit) })
        publisher.enqueue(File("retained.mp4"))
        withTimeout(5_000) { stopped.await() }
        assertTrue(runCatching { publisher.finish() }.isFailure)
        assertTrue(runCatching { publisher.enqueue(File("later.mp4")) }.isFailure)
    }

    @Test fun cancellingRecorderStillDrainsCompletedSegments() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val published = CompletableDeferred<Unit>()
        val job = launch {
            val publisher = SegmentPublisher(this, { entered.complete(Unit); release.await(); published.complete(Unit) }, {})
            publisher.enqueue(File("final.mp4"))
            try { awaitCancellation() } finally { publisher.finish() }
        }
        withTimeout(5_000) { entered.await() }
        job.cancel()
        release.complete(Unit)
        withTimeout(5_000) { job.join() }
        assertTrue(published.isCompleted)
    }
}
