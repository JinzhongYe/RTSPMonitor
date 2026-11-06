package com.rtspmonitor.app

import com.rtspmonitor.data.Recording
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

internal fun Recording.recordedDate():LocalDate? = startedAt?.let { start -> offsetMinutes?.let { Instant.ofEpochMilli(start).atOffset(ZoneOffset.ofTotalSeconds(it*60)).toLocalDate() } }
internal fun Recording.recordedTime():String = startedAt?.let { start -> offsetMinutes?.let { Instant.ofEpochMilli(start).atOffset(ZoneOffset.ofTotalSeconds(it*60)).toLocalTime().toString().take(5) } } ?: "时间未知"
internal fun browseRecordings(rows:List<Recording>,date:LocalDate?,mask:Int):List<Recording> = rows.filter { it.recordedDate()==date && (mask==0 || it.sourceMask and mask!=0) }.sortedWith(compareByDescending<Recording> { it.startedAt ?: it.createdAt }.thenBy { it.uri })
