package com.rtspmonitor.media
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rtspmonitor.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class) class NativeTest {
 @Test fun failedConnectionReturnsAndReleasesNativeHandle() = runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val dir=java.io.File(context.cacheDir,"native-test").apply { mkdirs() }
  val engine=AvRecorder()
  val result=engine.record(Connection("rtsp://127.0.0.1:1/test","","",Transport.TCP),dir) { _, _ -> }
  assertTrue(result<0); engine.stop(); engine.close(); assertTrue(dir.listFiles().isNullOrEmpty())
 }
}
