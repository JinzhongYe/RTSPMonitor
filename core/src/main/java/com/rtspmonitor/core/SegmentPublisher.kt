package com.rtspmonitor.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Keeps filesystem publication off the native RTSP reader; full queues fail without deleting files. */
class SegmentPublisher(
    scope: CoroutineScope,
    publish: suspend (File) -> Unit,
    onFailure: () -> Unit
) {
    private val queue = Channel<File>(2)
    private val failure = AtomicReference<Throwable?>(null)
    private val worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                for (file in queue) publish(file)
            } catch (error: Throwable) {
                failure.set(error)
                queue.close(error)
                onFailure()
            }
        }
    }

    fun enqueue(file: File) {
        check(queue.trySend(file).isSuccess) { "录像发布积压或失败；临时文件已保留" }
    }

    suspend fun finish() = withContext(NonCancellable) {
        queue.close()
        worker.join()
        failure.get()?.let { throw it }
    }
}
