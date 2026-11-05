package com.rtspmonitor.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.SimpleTimeZone

const val SOURCE_MANUAL = 1
const val SOURCE_ACTIVITY = 2
fun recordingSourceLabel(mask: Int) = when(mask) { 1 -> "手动"; 2 -> "活动"; 3 -> "混合"; else -> "来源未知" }
data class SegmentMetadata(val startedAt: Long, val endedAt: Long, val offsetMinutes: Int, val sources: Int)
fun recordingRangeName(meta: SegmentMetadata): String {
 require(meta.startedAt > 0 && meta.endedAt >= meta.startedAt)
 val format=SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT).apply { timeZone=SimpleTimeZone(meta.offsetMinutes*60000,"recording") }
 return "${format.format(Date(meta.startedAt))}-${format.format(Date(meta.endedAt))}_${recordingSourceLabel(meta.sources)}"
}
