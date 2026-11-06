package com.rtspmonitor.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Bounded thumbnail extraction; cache contains only immutable completed recordings. */
class RecordingThumbnails(context:Context) {
 private val app=context.applicationContext
 private val directory=File(app.cacheDir,"recording-thumbnails").apply { mkdirs() }
 private val permits=Semaphore(2)
 private val removed=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
 private val memory=object:LruCache<String,Bitmap>(12*1024*1024) { override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount }
 private fun file(uri:String)=File(directory,MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }+".jpg")
 suspend fun load(uri:String):Bitmap?=withContext(Dispatchers.IO) { permits.withPermit {
  if(removed.contains(uri))return@withPermit null
  memory.get(uri)?.let { return@withPermit it }
  val target=file(uri)
  val bitmap=if(target.exists()) BitmapFactory.decodeFile(target.path) else {
   val retriever=MediaMetadataRetriever()
   try {
    retriever.setDataSource(app,Uri.parse(uri))
    retriever.getScaledFrameAtTime(1000000,MediaMetadataRetriever.OPTION_CLOSEST,320,180)?.also { image ->
     val atomic=android.util.AtomicFile(target);val output=atomic.startWrite()
     try { check(image.compress(Bitmap.CompressFormat.JPEG,75,output));atomic.finishWrite(output) }
     catch(e:Exception) { atomic.failWrite(output);throw e }
    }
   } finally { retriever.release() }
  }
  synchronized(memory) {
   if(removed.contains(uri)) { target.delete();null } else bitmap?.also { memory.put(uri,it) }
  }
 } }
 suspend fun duration(uri:String):Long?=withContext(Dispatchers.IO) { permits.withPermit {
  val retriever=MediaMetadataRetriever()
  try { retriever.setDataSource(app,Uri.parse(uri));retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() }
  finally { retriever.release() }
 } }
 suspend fun remove(uri:String)=withContext(Dispatchers.IO) { synchronized(memory) { removed.add(uri);memory.remove(uri) };file(uri).delete();Unit }
}
