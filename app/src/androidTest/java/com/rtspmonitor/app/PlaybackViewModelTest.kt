package com.rtspmonitor.app
import android.graphics.Bitmap
import android.view.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rtspmonitor.core.ConnectionState
import com.rtspmonitor.data.Camera
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class) class PlaybackViewModelTest {
 @Test fun publishedPlaybackSurvivesActivityRecreation()=runBlocking {
  assumeTrue(InstrumentationRegistry.getArguments().getString("runPlaybackLifecycle")=="true")
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  val app=instrumentation.targetContext.applicationContext as MonitorApp
  val id=UUID.randomUUID().toString()
  val input=File(app.store.temporary,"$id.mp4")
  instrumentation.context.assets.open("h264.mp4").use { src -> input.outputStream().use { src.copyTo(it) } }
  app.store.publish(input,Camera(id,"Playback-Rotation","not-used"))
  val item=app.database.dao().recordings().first().single { it.cameraId==id }
  try {
   ActivityScenario.launch<PlaybackActivity>(android.content.Intent(app,PlaybackActivity::class.java).putExtra("uri",item.uri)).use { scenario ->
    lateinit var vm: PlaybackViewModel
    scenario.onActivity { activity -> vm=ViewModelProvider(activity)[PlaybackViewModel::class.java] }
    withTimeout(20_000) { while(!vm.player.playback.value.seekable) delay(100) }
    scenario.onActivity { vm.player.speedPlayback(1.5f);vm.player.seekPlayback(1000) }
    scenario.recreate()
    scenario.onActivity { activity -> assertSame(vm,ViewModelProvider(activity)[PlaybackViewModel::class.java]); assertEquals(item.uri,vm.uri);vm.player.refreshPlayback();assertEquals(1.5f,vm.player.playback.value.speed,0.01f) }
    delay(1_500)
    val bitmap=Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888)
    val result=CompletableDeferred<Int>()
    scenario.onActivity { activity ->
     val video=findSurface(activity.window.decorView) ?: error("Missing video surface")
     PixelCopy.request(video,bitmap,{ result.complete(it) },android.os.Handler(android.os.Looper.getMainLooper()))
    }
    assertEquals(PixelCopy.SUCCESS,withTimeout(5_000) { result.await() })
    assertTrue(android.graphics.Color.red(bitmap.getPixel(16,12))>0)
    bitmap.recycle()
   }
  } finally { app.store.delete(item); input.delete() }
 }
 private fun findSurface(view: View): SurfaceView? {
  if(view is SurfaceView) return view
  if(view is ViewGroup) for(index in 0 until view.childCount) findSurface(view.getChildAt(index))?.let { return it }
  return null
 }
}
