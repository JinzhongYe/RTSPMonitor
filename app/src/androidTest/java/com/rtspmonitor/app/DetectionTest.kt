package com.rtspmonitor.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rtspmonitor.media.ActivityDetector
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DetectionTest {
 @Test fun loadsBundledModelAndDetectsImageChanges() {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  ActivityDetector(context).use { detector ->
   val bitmap=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888)
   try {
    bitmap.eraseColor(Color.BLACK)
    assertEquals(false to false,detector.detect(bitmap,1000))
    bitmap.eraseColor(Color.WHITE)
    assertTrue(detector.detect(bitmap,1500).second)
    assertFalse(detector.detect(bitmap,2000).second)
    bitmap.eraseColor(Color.BLACK)
    assertFalse("Reconnect resets the difference baseline",detector.detect(bitmap,6000).second)
   } finally { bitmap.recycle() }
  }
 }
 @Test fun usesConfiguredBrightnessAndAreaThresholds() {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  ActivityDetector(context).use { detector ->
   val bitmap=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888)
   try {
    bitmap.eraseColor(Color.BLACK);detector.detect(bitmap,1000)
    repeat(30) { i -> bitmap.setPixel((i%64)*320/64,(i/64)*320/36,Color.rgb(20,20,20)) }
    assertFalse(detector.detect(bitmap,1500).second)
    bitmap.eraseColor(Color.BLACK);detector.detect(bitmap,2000)
    repeat(30) { i -> bitmap.setPixel((i%64)*320/64,(i/64)*320/36,Color.rgb(20,20,20)) }
    assertTrue(detector.detect(bitmap,2500,com.rtspmonitor.core.MonitorSettings(motionAreaPercent=1,brightnessDifference=10)).second)
   } finally { bitmap.recycle() }
  }
 }
 @Test fun recognizesPersonFixture() {
  assumeTrue(InstrumentationRegistry.getArguments().getString("validatePerson")=="true")
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val file=File(context.getExternalFilesDir("fixtures"),"person.jpg")
  val bitmap=checkNotNull(BitmapFactory.decodeFile(file.path))
  try { ActivityDetector(context).use { assertTrue(it.detect(bitmap,1000).first) } }
  finally { bitmap.recycle() }
 }
}
