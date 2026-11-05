package com.rtspmonitor.media

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

class ActivityDetector(context: Context): AutoCloseable {
 init {
  val expected=context.assets.open("efficientdet_lite0.sha256").bufferedReader().use { it.readText().substringBefore(' ') }
  val digest=java.security.MessageDigest.getInstance("SHA-256").digest(context.assets.open("efficientdet_lite0.tflite").use { it.readBytes() }).joinToString("") { "%02x".format(it) }
  check(digest==expected) { "Detection model checksum mismatch" }
 }
 private val detector=ObjectDetector.createFromOptions(context,ObjectDetector.ObjectDetectorOptions.builder()
  .setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite").build())
  .setRunningMode(RunningMode.VIDEO).setScoreThreshold(0.6f).setCategoryAllowlist(listOf("person")).build())
 private var previous:IntArray?=null
 private var lastFrame=0L
 @Synchronized fun detect(bitmap:Bitmap,now:Long):Pair<Boolean,Boolean> {
  val image=BitmapImageBuilder(bitmap).build()
  val person=try { detector.detectForVideo(image,now).detections().isNotEmpty() } finally { image.close() }
  val gray=IntArray(64*36) { i ->
   val color=bitmap.getPixel((i%64)*bitmap.width/64,(i/64)*bitmap.height/36)
   (android.graphics.Color.red(color)*77+android.graphics.Color.green(color)*150+android.graphics.Color.blue(color)*29)/256
  }
  val prior=previous.takeIf { now-lastFrame<=3000 }
  val motion=prior!=null && gray.indices.count { kotlin.math.abs(gray[it]-prior[it])>25 }>=gray.size*0.03
  previous=gray;lastFrame=now
  return person to motion
 }
 @Synchronized override fun close() { detector.close();previous=null }
}
