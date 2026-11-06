package com.rtspmonitor.core
import org.junit.Assert.*
import org.junit.Test
class MonitorSettingsTest {
 @Test fun defaultsAndManualPriority() {
  val settings=MonitorSettings(manualSegmentMinutes=2)
  assertEquals(120,settings.segmentSeconds(true));assertEquals(300,settings.segmentSeconds(false))
  assertTrue(settings.acceptsPerson(.6f));assertFalse(settings.acceptsPerson(.59f))
 }
 @Test fun areaAndBrightnessBoundaries() {
  val previous=IntArray(100);val frame=IntArray(100)
  repeat(3) { frame[it]=26 }
  assertTrue(MonitorSettings().changed(previous,frame))
  assertFalse(MonitorSettings(motionAreaPercent=4).changed(previous,frame))
  assertFalse(MonitorSettings(brightnessDifference=26).changed(previous,frame))
  assertTrue(MonitorSettings(brightnessDifference=5,motionAreaPercent=1).changed(previous,frame))
 }
 @Test fun changingThresholdsClearsUnconfirmedEvidenceButKeepsActiveEvent() {
  val p=ActivityPolicy();p.sample(true,false,0);p.resetEvidence()
  assertFalse(p.sample(true,false,500));assertTrue(p.sample(true,false,1000));p.resetEvidence()
  assertTrue(p.active);assertTrue(p.sample(false,false,1500))
 }
 @Test fun invalidValuesRejected() {
  listOf<()->Unit>({MonitorSettings(personConfidence=9)},{MonitorSettings(personConfidence=61)},{MonitorSettings(motionAreaPercent=0)},{MonitorSettings(brightnessDifference=101)},{MonitorSettings(manualSegmentMinutes=0)},{MonitorSettings(manualSegmentMinutes=61)}).forEach { assertTrue(runCatching(it).isFailure) }
 }
}
