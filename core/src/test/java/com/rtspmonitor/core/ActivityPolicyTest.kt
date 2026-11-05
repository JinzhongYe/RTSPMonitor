package com.rtspmonitor.core
import org.junit.Assert.*
import org.junit.Test

class ActivityPolicyTest {
 @Test fun debouncesExtendsAndStopsAfterQuietPeriod() {
  val p = ActivityPolicy()
  assertFalse(p.sample(true,false,0))
  assertTrue(p.sample(true,false,500))
  for(t in 1000L..30000L step 500) assertTrue(p.sample(false,false,t))
  assertFalse(p.sample(false,false,30500))
 }
 @Test fun missingFramesDoNotConfirmQuietAndMotionAlsoTriggers() {
  val p = ActivityPolicy()
  p.sample(false,true,0)
  assertTrue(p.sample(false,true,500))
  assertTrue(p.sample(false,false,60000))
  p.reset()
  assertFalse(p.sample(false,false,60500))
 }
}
