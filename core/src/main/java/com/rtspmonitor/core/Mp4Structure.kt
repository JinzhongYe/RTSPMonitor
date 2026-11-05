package com.rtspmonitor.core

import java.io.File
import java.io.RandomAccessFile

/** Top-level ISO BMFF boundaries; does not claim to verify compressed video contents. */
data class Mp4Structure(val complete: Boolean, val recoverableLength: Long)

fun inspectMp4Structure(file: File): Mp4Structure = RandomAccessFile(file, "r").use { input ->
    val length = input.length()
    var position = 0L
    var hasMovie = false
    var hasType = false
    var pendingFragment = false
    var recoverable = 0L
    while(length - position >= 8) {
        input.seek(position)
        var size = input.readInt().toLong() and 0xffffffffL
        val type = input.readInt()
        var header = 8L
        if(size == 1L) {
            if(length - position < 16) break
            size = input.readLong(); header = 16L
        } else if(size == 0L) size = length - position
        if(size < header || size > length - position) break
        when(type) {
            0x66747970 -> hasType = true // ftyp
            0x6d6f6f76 -> hasMovie = true // moov
            0x6d6f6f66 -> pendingFragment = true // moof
            0x6d646174 -> if(pendingFragment && hasMovie && hasType) { // mdat
                recoverable = position + size
                pendingFragment = false
            }
        }
        position += size
    }
    Mp4Structure(hasType && hasMovie && position == length && !pendingFragment, recoverable)
}
