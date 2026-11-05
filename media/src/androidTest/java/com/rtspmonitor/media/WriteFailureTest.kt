package com.rtspmonitor.media
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rtspmonitor.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class) class WriteFailureTest {
 @Test fun rejectedOutputDoesNotPublishPartialFile() = runBlocking {
  val base=InstrumentationRegistry.getArguments().getString("rtspBase")
  assumeTrue(base!=null && File("/dev/full").exists())
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val directory=File(context.cacheDir,"full-${UUID.randomUUID()}").apply { mkdirs() }
  val link=File(directory,"000000.part")
  val engine=AvRecorder(); var published=0; var storageError=false
  try {
   Os.symlink("/dev/full",link.path)
   val result=engine.record(Connection("$base/h264","","",Transport.TCP),directory,2) { kind,_ -> if(kind=="segment") published++; if(kind=="storage-error") storageError=true }
   assertTrue("ENOSPC or SELinux access denial must reach the owner: $result",result == -28 || result == -13)
   assertTrue("Filesystem failures must be distinguishable from network interruptions",storageError)
   assertEquals(0,published)
   assertFalse(directory.listFiles()!!.any { it.extension=="mp4" })
  } finally { engine.stop(); engine.close(); link.delete(); directory.delete() }
 }
}
