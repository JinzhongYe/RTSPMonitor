package com.rtspmonitor.app
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import com.rtspmonitor.core.*
import com.rtspmonitor.media.ActivityDetector
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class RecordingForegroundService: Service() {
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
 private val app get()=application as MonitorApp
 private val prefs get()=app.getSharedPreferences("monitor",MODE_PRIVATE)
 private var detector:ActivityDetector?=null
 private val detecting=AtomicBoolean(false)
 private val policy=ActivityPolicy()
 private var wakeLock:PowerManager.WakeLock?=null
 private var loop:Job?=null
 private var retryAt=0L
 private var retry=0
 private var attempted=false
 private var eventId=100
 private var lastRecordState=RecordingState.IDLE
 private fun flag(key:String)=prefs.getBoolean(key,false)
 private fun set(key:String,value:Boolean) { prefs.edit().putBoolean(key,value).apply() }
 override fun onCreate() {
  super.onCreate()
  val manager=getSystemService(NotificationManager::class.java)
  manager.createNotificationChannel(NotificationChannel("recording","后台监控状态",NotificationManager.IMPORTANCE_LOW))
  manager.createNotificationChannel(NotificationChannel("activity","活动提醒",NotificationManager.IMPORTANCE_HIGH))
 }
 private fun notification(text:String,alarm:Boolean=false):Notification {
  val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  val builder=Notification.Builder(this,if(alarm) "activity" else "recording").setSmallIcon(android.R.drawable.presence_video_online)
   .setContentTitle(if(alarm) "摄像头活动与录像" else "RTSP Monitor").setContentText(text).setContentIntent(open).setOnlyAlertOnce(true)
  if(!alarm) {
   val stop=PendingIntent.getService(this,1,Intent(this,javaClass).setAction("STOP_ALL"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
   builder.setOngoing(true).addAction(Notification.Action.Builder(null,"停止所有任务",stop).build())
  } else builder.setAutoCancel(true)
  return builder.build()
 }
 private fun notifyEvent(text:String) { getSystemService(NotificationManager::class.java).notify(eventId,notification(text,true)) }
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  startForeground(1,notification("恢复监控任务"),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
  intent?.getStringExtra("id")?.let { prefs.edit().putString("active_camera",it).apply() }
  when(intent?.action) {
   "CONNECT" -> { set("preview",true);retryAt=0 }
   "DISCONNECT" -> set("preview",false)
   "START" -> { set("manual",true);attempted=false }
   "STOP" -> set("manual",false)
   "AUTO_ON" -> { set("auto",true);retryAt=0 }
   "AUTO_OFF" -> { set("auto",false);policy.reset() }
   "STOP_ALL" -> { set("auto",false);set("manual",false);set("preview",false);policy.reset() }
  }
  if(loop?.isActive!=true) loop=scope.launch { runMonitor() }
  return START_NOT_STICKY
 }
 private suspend fun runMonitor() {
  var lastLock=0L
  try {
   while(currentCoroutineContext().isActive) {
    val id=prefs.getString("active_camera",null) ?: break
    val camera=app.database.dao().camera(id) ?: break
    val session=app.sessions.session(id)
    if(!app.hasLocalNetworkAccess()) { app.sessions.message.value="请返回页面授予局域网权限";break }
    if(flag("auto") && detector==null) {
     try { detector=withContext(Dispatchers.Default) { ActivityDetector(this@RecordingForegroundService) } }
     catch(e:Exception) { set("auto",false);policy.reset();app.sessions.message.value="检测模型初始化失败，请重新开启自动监控" }
    }
    if(flag("auto") && detector!=null) {
     session.player.frameListener={ bitmap,now ->
      if(!detecting.compareAndSet(false,true)) bitmap.recycle()
      else scope.launch {
       try {
        val result=withContext(Dispatchers.Default) { detector?.detect(bitmap,now) }
        if(flag("auto") && result!=null) {
         app.sessions.detectionStatus.value=if(result.first) "检测到人形" else if(result.second) "检测到画面变化" else "未检测到活动"
         val before=policy.active
         policy.sample(result.first,result.second,now)
         if(!before && policy.active) { eventId++;attempted=false;notifyEvent("正在准备自动录像") }
        }
       } catch(e:Exception) { if(e is CancellationException)throw e;set("auto",false);policy.reset();app.sessions.message.value="检测失败，自动监控已关闭" }
       finally { bitmap.recycle();detecting.set(false) }
      }
     }
    } else {
     session.player.frameListener=null
     detector?.let { value -> withContext(Dispatchers.Default) { value.close() } };detector=null;policy.reset()
    }
    val desiredLive=flag("preview") || flag("auto")
    val now=SystemClock.elapsedRealtime()
    val connection=session.player.status.value.state
    if(desiredLive && connection in listOf(ConnectionState.ERROR,ConnectionState.DISCONNECTED) && now>=retryAt) {
     app.sessions.connect(camera);retryAt=now+minOf(30000L,1000L shl minOf(retry++,5))
    } else if(connection==ConnectionState.CONNECTED) { retry=0;retryAt=0 }
    if(!desiredLive && connection!=ConnectionState.DISCONNECTED) app.sessions.disconnect(id)
    val desiredRecord=flag("manual") || policy.active
    if(desiredRecord && !attempted) { attempted=true;app.sessions.start(camera) }
    if(!desiredRecord) {
     if(session.job?.isCompleted==false) { app.sessions.stop(id);if(lastRecordState!=RecordingState.FAILED)notifyEvent("录像已保存到应用内，可在录像列表导出到相册") }
     attempted=false
    }
    val record=session.state.value.recordingState
    if(record!=lastRecordState) {
     if(record==RecordingState.RECORDING && policy.active)notifyEvent("正在自动录像")
     if(record==RecordingState.FAILED)notifyEvent("录像失败，请打开应用检查错误和存储空间")
     lastRecordState=record
    }
    app.sessions.monitoring.value=flag("auto")
    app.sessions.manualRecording.value=flag("manual")
    app.sessions.automaticRecording.value=policy.active
    if(!desiredLive && !desiredRecord)break
    if(wakeLock==null || now-lastLock>=300000) {
     wakeLock?.let { if(it.isHeld)it.release() }
     wakeLock=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"RTSPMonitor:monitor").apply { acquire(600000) };lastLock=now
    }
    getSystemService(NotificationManager::class.java).notify(1,notification("${if(flag("auto")) "自动监控中" else "监控运行中"} · ${if(desiredRecord) "录像中" else "等待活动"}"))
    delay(500)
   }
  } finally {
   app.sessions.monitoring.value=false;app.sessions.manualRecording.value=false;app.sessions.automaticRecording.value=false
   stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
  }
 }
 override fun onDestroy() {
  scope.launch { try {
   app.sessions.states.value.keys.toList().forEach { app.sessions.session(it).player.frameListener=null;app.sessions.disconnect(it) }
   app.sessions.stopAll();detector?.let { value -> withContext(Dispatchers.Default) { value.close() } };detector=null
  } finally { wakeLock?.let { if(it.isHeld)it.release() };scope.cancel() } }
  super.onDestroy()
 }
 override fun onBind(intent:Intent?)=null
}
