package com.rtspmonitor.app
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class RecordingForegroundService: Service() {
 private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
 private val app get() = application as MonitorApp
 private var wakeLock: PowerManager.WakeLock? = null
 private var commands = 0
 private var monitor: Job? = null
 override fun onCreate() {
  super.onCreate()
  getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("recording", "录像状态", NotificationManager.IMPORTANCE_LOW))
  scope.launch { app.sessions.states.collectLatest { states ->
   val active = states.values.count { it.recordingState in listOf(com.rtspmonitor.core.RecordingState.PREPARING, com.rtspmonitor.core.RecordingState.RECORDING, com.rtspmonitor.core.RecordingState.FINALIZING) }
   if(active > 0 && wakeLock == null) wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RTSPMonitor:recording").apply { acquire(10 * 60 * 1000L) }
   if(active == 0) releaseLock()
   getSystemService(NotificationManager::class.java).notify(1, notification("$active 路活跃；${states.values.count { it.recordingState == com.rtspmonitor.core.RecordingState.INTERRUPTED }} 路重连中"))
  } }
  scope.launch { while(isActive) { delay(5 * 60 * 1000L); wakeLock?.let { if(it.isHeld) it.release(); it.acquire(10 * 60 * 1000L) } } }
 }
 private fun notification(text: String): Notification {
  val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction("STOP_ALL"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
  return Notification.Builder(this,"recording").setSmallIcon(android.R.drawable.presence_video_online).setContentTitle("RTSP Monitor 录像").setContentText(text).setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"停止全部录像",stop).build()).build()
 }
 override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
  startForeground(1, notification("准备录像"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
  commands++
  scope.launch {
   try {
    when(intent?.action) {
     "START" -> { app.sessions.recover(); intent.getStringExtra("id")?.let { app.database.dao().camera(it) }?.let { app.sessions.start(it) } }
     "STOP" -> intent.getStringExtra("id")?.let { app.sessions.stop(it) }
     "STOP_ALL" -> app.sessions.stopAll()
    }
   } finally { commands--; }
  }
  if(monitor?.isActive != true) monitor = scope.launch {
   delay(1000)
   while(commands > 0 || app.sessions.states.value.values.any { it.recordingState in listOf(com.rtspmonitor.core.RecordingState.PREPARING, com.rtspmonitor.core.RecordingState.RECORDING, com.rtspmonitor.core.RecordingState.INTERRUPTED, com.rtspmonitor.core.RecordingState.FINALIZING) }) delay(1000)
   releaseLock(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
  }
  return START_NOT_STICKY
 }
 private fun releaseLock() { wakeLock?.let { if(it.isHeld) it.release() }; wakeLock = null }
 override fun onDestroy() {
  scope.launch { try { app.sessions.stopAll() } finally { releaseLock(); scope.cancel() } }; super.onDestroy()
 }
 override fun onBind(intent: Intent?) = null
}
