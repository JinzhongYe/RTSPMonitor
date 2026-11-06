package com.rtspmonitor.app
import com.rtspmonitor.data.Recording
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
class RecordingBrowserTest {
 private fun row(uri:String,start:String?,source:Int=1)=Recording(uri,"camera","name",0,startedAt=start?.let { Instant.parse(it).toEpochMilli() },offsetMinutes=480,sourceMask=source)
 @Test fun captureTimezoneAndUnknownDates() {
  assertEquals(LocalDate.of(2026,11,6),row("a","2026-11-05T16:01:00Z").recordedDate())
  assertEquals("00:01",row("a","2026-11-05T16:01:00Z").recordedTime())
  assertNull(row("old",null).recordedDate())
 }
 @Test fun mixedSourcesSortAndFilter() {
  val rows=listOf(row("a","2026-11-05T01:00:00Z",1),row("b","2026-11-05T02:00:00Z",3),row("c","2026-11-05T03:00:00Z",2),row("old",null))
  val day=LocalDate.of(2026,11,5)
  assertEquals(listOf("b","a"),browseRecordings(rows,day,1).map { it.uri })
  assertEquals(listOf("c","b"),browseRecordings(rows,day,2).map { it.uri })
  assertEquals(listOf("old"),browseRecordings(rows,null,0).map { it.uri })
 }
}
