package com.rtspmonitor.media
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rtspmonitor.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class) class PlaybackTest {
 @Test fun h264Tcp()=play("h264",Transport.TCP)
 @Test fun h265Tcp()=play("h265",Transport.TCP)
 @Test fun h264Udp()=play("h264",Transport.UDP)
 @Test fun h265Udp()=play("h265",Transport.UDP)
 @Test fun publishedH264Plays()=play("h264",Transport.TCP,true)
 @Test fun publishedH265Plays()=play("h265",Transport.TCP,true)
 private fun play(path: String, transport: Transport, local: Boolean = false)=runBlocking {
  val base=InstrumentationRegistry.getArguments().getString("rtspBase")
  assumeTrue(if(local) InstrumentationRegistry.getArguments().getString("useFixtures")=="true" else base!=null)
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val db=if(local) androidx.room.Room.inMemoryDatabaseBuilder(context,com.rtspmonitor.data.MonitorDatabase::class.java).build() else null
  val store=db?.let { com.rtspmonitor.data.VideoStore(context,it.dao()) }
  var item: com.rtspmonitor.data.Recording?=null
  val input=store?.let { java.io.File(it.temporary,"${java.util.UUID.randomUUID()}.mp4") }
  try {
  if(local) {
   java.io.File(context.getExternalFilesDir("fixtures"),"$path.mp4").copyTo(input!!)
   store!!.publish(input,com.rtspmonitor.data.Camera("local-test","Local","not-used"))
   item=db!!.dao().recordings().first().single()
  }
  lateinit var player: VlcPlayer
  ActivityScenario.launch<ProbeActivity>(Intent(context,ProbeActivity::class.java)).use { scenario ->
   scenario.onActivity { player=it.player; if(item != null) player.playLocal(android.net.Uri.parse(item!!.uri)) else player.connect(Connection("$base/$path","","",transport)) }
   try { withTimeout(20_000) { while(player.status.value.state!=ConnectionState.CONNECTED || !player.status.value.info.contains("320 × 240")) delay(100) } }
   catch(error: TimeoutCancellationException) { throw AssertionError("Playback state: ${player.status.value}",error) }
   assertNull(player.status.value.error)
   delay(1_000)
   assertEquals(ConnectionState.CONNECTED,player.status.value.state)
   var previous: Int?=null
   repeat(3) { index ->
    if(index==2) {
     scenario.onActivity { activity -> activity.setContentView(createVideoView(activity,player)) }
     delay(1_500)
    }
    val copied=CompletableDeferred<Int>()
    val bitmap=android.graphics.Bitmap.createBitmap(32,24,android.graphics.Bitmap.Config.ARGB_8888)
    scenario.onActivity { activity ->
     val content=activity.findViewById<android.view.ViewGroup>(android.R.id.content)
     val root=content.getChildAt(0) as android.widget.FrameLayout
     val surface=root.getChildAt(0) as android.view.SurfaceView
     android.view.PixelCopy.request(surface,bitmap,{ result -> copied.complete(result) },android.os.Handler(android.os.Looper.getMainLooper()))
    }
    assertEquals("Video surface must have a decoded frame at sample $index",android.view.PixelCopy.SUCCESS,withTimeout(5_000) { copied.await() })
    val pixel=bitmap.getPixel(16,12)
    assertTrue("Synthetic gray video must be visible",android.graphics.Color.red(pixel)>0 || android.graphics.Color.green(pixel)>0 || android.graphics.Color.blue(pixel)>0)
    previous?.let { assertNotEquals("Synthetic video must advance, including after view replacement",it,pixel) }
    previous=pixel; bitmap.recycle(); delay(700)
   }
   scenario.onActivity { player.disconnect() }
   assertEquals(ConnectionState.DISCONNECTED,player.status.value.state)
  }
  } finally { item?.let { store!!.delete(it) }; input?.delete(); db?.close() }
 }
}
