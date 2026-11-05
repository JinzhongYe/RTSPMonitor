package com.rtspmonitor.core
import org.junit.Assert.*
import org.junit.Test
class RecordingMetadataTest {
 @Test fun rangeUsesCapturedOffsetAcrossMidnight() {
  val start=java.time.Instant.parse("2026-11-05T15:59:59Z").toEpochMilli()
  assertEquals("20261105_235959-20261106_000001_混合",recordingRangeName(SegmentMetadata(start,start+2000,480,SOURCE_MANUAL or SOURCE_ACTIVITY)))
 }
 @Test fun legacySourceIsUnknown() { assertEquals("来源未知",recordingSourceLabel(0)) }
 @Test(expected=IllegalArgumentException::class) fun invalidRangeRejected() { recordingRangeName(SegmentMetadata(100,99,0,1)) }
}
