package com.rtspmonitor.core

data class MonitorSettings(val personConfidence:Int=60,val motionAreaPercent:Int=3,val brightnessDifference:Int=25,val manualSegmentMinutes:Int=5) {
 init { require(personConfidence in 10..95 && personConfidence%5==0);require(motionAreaPercent in 1..30);require(brightnessDifference in 5..100);require(manualSegmentMinutes in 1..60) }
 fun acceptsPerson(score:Float)=score>=personConfidence/100f
 fun changed(previous:IntArray,current:IntArray):Boolean {
  require(previous.size==current.size && current.isNotEmpty())
  return current.indices.count { kotlin.math.abs(current[it]-previous[it])>brightnessDifference }*100L>=current.size.toLong()*motionAreaPercent
 }
 fun sameDetection(other:MonitorSettings)=personConfidence==other.personConfidence && motionAreaPercent==other.motionAreaPercent && brightnessDifference==other.brightnessDifference
 fun segmentSeconds(manual:Boolean)=if(manual)manualSegmentMinutes*60 else 300
}
