package com.rtspmonitor.core
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.nio.ByteBuffer

class Mp4StructureTest {
 private fun box(name: String, payload: ByteArray = byteArrayOf()) = ByteBuffer.allocate(8+payload.size).putInt(8+payload.size).put(name.toByteArray()).put(payload).array()
 private fun inspect(bytes: ByteArray): Mp4Structure {
  val file=File.createTempFile("rtsp-mp4", ".part")
  return try { file.writeBytes(bytes); inspectMp4Structure(file) } finally { file.delete() }
 }
 private fun prefix()=box("ftyp")+box("moov")+box("moof")+box("mdat",byteArrayOf(1,2,3))
 @Test fun completeFragmentedFile() { val bytes=prefix(); assertEquals(Mp4Structure(true,bytes.size.toLong()),inspect(bytes)) }
 @Test fun incompleteMediaBoxKeepsPreviousFragment() {
  val bytes=prefix(); val tail=box("moof")+ByteBuffer.allocate(10).putInt(100).put("mdat".toByteArray()).putShort(0).array()
  assertEquals(Mp4Structure(false,bytes.size.toLong()),inspect(bytes+tail))
 }
 @Test fun partialHeaderCannotBePublished() { val bytes=prefix(); assertEquals(Mp4Structure(false,bytes.size.toLong()),inspect(bytes+byteArrayOf(0,1,2))) }
 @Test fun noCompletedFragmentCannotBeRecovered() { assertEquals(0,inspect(box("ftyp")+box("moov")+box("moof")).recoverableLength) }
 @Test fun oversizedExtendedBoxCannotOverflow() { val tail=ByteBuffer.allocate(16).putInt(1).put("mdat".toByteArray()).putLong(Long.MAX_VALUE).array(); assertFalse(inspect(prefix()+tail).complete) }
 @Test fun completeOrdinaryMp4HasNoRecoverableFragmentPrefix() { assertEquals(Mp4Structure(true,0),inspect(box("ftyp")+box("mdat",byteArrayOf(1))+box("moov"))) }
}
