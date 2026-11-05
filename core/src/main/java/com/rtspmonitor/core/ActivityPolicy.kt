package com.rtspmonitor.core

/** Timestamps use a monotonic clock. Missing frames reset evidence, not the active event. */
class ActivityPolicy {
 var active = false
  private set
 private var consecutive = 0
 private var lastActivity = 0L
 private var lastFrame: Long? = null
 fun reset() { active = false; consecutive = 0; lastFrame = null }
 fun sample(person: Boolean, changed: Boolean, now: Long): Boolean {
  val previous = lastFrame
  if(previous != null && now - previous > 3000) {
   consecutive = 0
   if(active) lastActivity += now - previous
  }
  lastFrame = now
  consecutive = if(person || changed) consecutive + 1 else 0
  if(consecutive >= 2) { active = true; lastActivity = now }
  if(active && now - lastActivity >= 30_000) active = false
  return active
 }
}
