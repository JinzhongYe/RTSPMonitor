package com.rtspmonitor.app

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rtspmonitor.core.*
import com.rtspmonitor.data.Camera
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalNetworkPermissionTest {
 @Test fun deniedPermissionBlocksPreviewAndFailsRecordingWithoutRetry() = runBlocking {
  assumeTrue(Build.VERSION.SDK_INT >= 37 && InstrumentationRegistry.getArguments().getString("localNetworkDenied") == "true")
  val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MonitorApp
  assertFalse("Host must revoke ACCESS_LOCAL_NETWORK before instrumentation starts", app.hasLocalNetworkAccess())
  val camera = Camera(UUID.randomUUID().toString(), "Permission-test", app.vault.encrypt(Connection("rtsp://192.0.2.1/test", "", "", Transport.TCP)))
  try {
   withContext(Dispatchers.Main) {
    app.sessions.connect(camera); app.sessions.probe(camera)
    assertEquals(ConnectionState.DISCONNECTED, app.sessions.session(camera.id).player.status.value.state)
    app.sessions.start(camera)
   }
   withTimeout(5_000) { while(app.sessions.states.value[camera.id]?.recordingState != RecordingState.FAILED) delay(50) }
   val state = app.sessions.states.value.getValue(camera.id)
   assertEquals(0, state.reconnectCount)
   assertTrue(state.lastError!!.contains("局域网"))
   assertNull(app.sessions.session(camera.id).recordingEngine)
  } finally { withContext(Dispatchers.Main) { app.sessions.remove(camera) } }
 }
}
