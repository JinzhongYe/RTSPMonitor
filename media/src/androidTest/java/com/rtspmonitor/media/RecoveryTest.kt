package com.rtspmonitor.media
import android.media.MediaExtractor
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rtspmonitor.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

@RunWith(AndroidJUnit4::class) class RecoveryTest {
 @Test fun incompleteFinalFragmentIsTrimmedBeforePublication() = runBlocking {
  assumeTrue(InstrumentationRegistry.getArguments().getString("useFixtures")=="true")
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val source=File(context.getExternalFilesDir("fixtures"),"recovery-fmp4.mp4")
  assertTrue(source.exists())
  val db=Room.inMemoryDatabaseBuilder(context,MonitorDatabase::class.java).build()
  val store=VideoStore(context,db.dao())
  val input=File(store.temporary,"${UUID.randomUUID()}.part")
  source.copyTo(input)
  val start=java.time.Instant.parse("2026-11-05T02:30:00Z").toEpochMilli()
  val sidecar=File(input.parentFile,input.nameWithoutExtension+".meta")
  sidecar.writeText(org.json.JSONObject().put("startedAt",start).put("endedAt",start+60000).put("offsetMinutes",480).put("sources",2).toString())
  input.appendBytes(ByteBuffer.allocate(32).putInt(8).put("moof".toByteArray()).putInt(100000).put("mdat".toByteArray()).array())
  var published: Recording?=null
  try {
   assertTrue("Truncated container must be rejected",runCatching { store.validate(input) }.isFailure)
   store.recoverTemporary(input)
   assertTrue(input.length()<source.length())
   val extractor=MediaExtractor()
   try {
    extractor.setDataSource(input.path); extractor.selectTrack(0)
    var count=0; while(extractor.sampleTime>=0) { count++; if(!extractor.advance()) break }
    assertEquals("All complete source frames survive",60,count)
   } finally { extractor.release() }
   decodeTrack(input,"video/avc")
   store.publish(input,Camera("recovery","Recovery","not-used"))
   published=db.dao().recordings().first().single()
   assertFalse(input.exists())
   assertEquals(2,published!!.sourceMask)
   assertTrue(published!!.endedAt!! in start+5900..start+6100)
   assertTrue(published!!.name.endsWith("_活动.mp4"))
  } finally { published?.let { store.delete(it) }; input.delete(); db.close() }
 }
 @Test fun unrecoverableHeaderIsRetained() {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val db=Room.inMemoryDatabaseBuilder(context,MonitorDatabase::class.java).build()
  val store=VideoStore(context,db.dao())
  val input=File(store.temporary,"${UUID.randomUUID()}.part").apply { writeBytes(byteArrayOf(0,0,0,8,0x66,0x74,0x79,0x70)) }
  try { assertTrue(runCatching { store.recoverTemporary(input) }.isFailure); assertTrue(input.exists()); assertEquals(8,input.length()) }
  finally { input.delete(); db.close() }
 }
}
