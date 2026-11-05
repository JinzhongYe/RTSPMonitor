package com.rtspmonitor.core

class InsufficientStorageException: java.io.IOException("存储空间不足，录像已停止；请清理空间后重试")

fun requireRecordingSpace(availableBytes: Long, publicationBytes: Long = 0) {
    require(publicationBytes >= 0)
    if(availableBytes < publicationBytes || availableBytes - publicationBytes <= 128L * 1024 * 1024) throw InsufficientStorageException()
}
