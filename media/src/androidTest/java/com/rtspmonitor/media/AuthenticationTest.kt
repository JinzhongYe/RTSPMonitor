package com.rtspmonitor.media

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rtspmonitor.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Synthetic credentials only; they must match the loopback authentication fixture. */
@RunWith(AndroidJUnit4::class)
class AuthenticationTest {
 private fun connection(valid: Boolean): Connection {
  val base=InstrumentationRegistry.getArguments().getString("authBase")
  assumeTrue(base!=null)
  return Connection("$base/h264","fixture+viewer",if(valid) "fixture@p+ss#&" else "wrong-fixture",Transport.TCP)
 }
 @Test fun recorderAcceptsSpecialCharacters()=record(true)
 @Test fun recorderRejectsWrongPassword()=record(false)
 private fun record(valid: Boolean)=runBlocking {
  val connection=connection(valid)
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val directory=File(context.getExternalFilesDir("auth-results"),UUID.randomUUID().toString()).apply { mkdirs() }
  val recorder=AvRecorder()
  val segment=CompletableDeferred<File>()
  val job=async(Dispatchers.IO) { recorder.record(connection,directory,2) { kind,value -> if(kind=="segment") segment.complete(File(value)) } }
  try {
   if(valid) {
    val file=withTimeout(25_000) { segment.await() }
    recorder.stop(); assertEquals(0,job.await()); checkVideo(file,"video/avc"); decodeTrack(file,"video/avc")
   } else {
    assertEquals("Authentication failure must be distinguished from retryable network failure",-401,withTimeout(25_000) { job.await() })
    assertFalse(directory.listFiles()!!.any { it.extension=="mp4" })
   }
  } finally { recorder.stop(); withContext(NonCancellable) { job.join() }; recorder.close() }
 }
 @Test fun playerAcceptsSpecialCharacters()=play(true)
 @Test fun playerRejectsWrongPassword()=play(false)
 private fun play(valid: Boolean)=runBlocking {
  val connection=connection(valid)
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  lateinit var player: VlcPlayer
  ActivityScenario.launch<ProbeActivity>(Intent(context,ProbeActivity::class.java)).use { scenario ->
   scenario.onActivity { player=it.player; player.connect(connection) }
   if(valid) {
    withTimeout(20_000) { while(player.status.value.state!=ConnectionState.CONNECTED || !player.status.value.info.contains("320 × 240")) delay(100) }
   } else {
    withTimeout(20_000) { while(player.status.value.state!=ConnectionState.ERROR) delay(100) }
   }
  }
 }
}
