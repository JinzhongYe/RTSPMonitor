package com.rtspmonitor.media
import android.media.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteOrder
import kotlin.math.sin

@RunWith(AndroidJUnit4::class) class AudioFixtureTest {
 @Test fun generateSyntheticAac() {
  assumeTrue(InstrumentationRegistry.getArguments().getString("generateFixtures")=="true")
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val output=File(context.getExternalFilesDir("fixtures"),"audio.m4a")
  val codec=MediaCodec.createEncoderByType("audio/mp4a-latm")
  val muxer=MediaMuxer(output.path,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
  var started=false
  try {
   codec.configure(MediaFormat.createAudioFormat("audio/mp4a-latm",16000,1).apply {
    setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC)
    setInteger(MediaFormat.KEY_BIT_RATE,32000)
   },null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
   codec.start()
   var sample=0; var inputEnded=false; var outputEnded=false; var track=-1
   val info=MediaCodec.BufferInfo(); val deadline=android.os.SystemClock.elapsedRealtime()+30_000
   while(!outputEnded) {
    check(android.os.SystemClock.elapsedRealtime()<deadline)
    if(!inputEnded) {
     val index=codec.dequeueInputBuffer(10_000)
     if(index>=0) {
      val buffer=codec.getInputBuffer(index)!!.apply { clear(); order(ByteOrder.LITTLE_ENDIAN) }
      val count=minOf(1024,96000-sample)
      if(count==0) { codec.queueInputBuffer(index,0,0,sample*1_000_000L/16000,MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded=true }
      else {
       repeat(count) { offset -> buffer.putShort((sin((sample+offset)*2.0*Math.PI*440/16000)*4000).toInt().toShort()) }
       codec.queueInputBuffer(index,0,count*2,sample*1_000_000L/16000,0); sample+=count
      }
     }
    }
    when(val index=codec.dequeueOutputBuffer(info,10_000)) {
     MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track=muxer.addTrack(codec.outputFormat); muxer.start(); started=true }
     else -> if(index>=0) {
      if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0) {
       val buffer=codec.getOutputBuffer(index)!!.apply { position(info.offset); limit(info.offset+info.size) }
       muxer.writeSampleData(track,buffer,info)
      }
      outputEnded=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
      codec.releaseOutputBuffer(index,false)
     }
    }
   }
  } finally { codec.release(); if(started) muxer.stop(); muxer.release() }
  check(output.length()>0)
 }
}
