package com.rtspmonitor.data
import android.content.ContentValues
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class) class StoreTest {
 @Test fun deletingPrivateVideoCleansFailedPendingExport()=runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val db=Room.inMemoryDatabaseBuilder(context,MonitorDatabase::class.java).build()
  val folder=java.io.File(context.noBackupFilesDir,"videos").apply { mkdirs() }
  val file=java.io.File(folder,"delete-${java.util.UUID.randomUUID()}.mp4").apply { writeText("private test fixture") }
  val journal=java.io.File(file.path+".export-uri")
  val resolver=context.contentResolver
  val uri=resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
   put(MediaStore.MediaColumns.DISPLAY_NAME,file.name)
   put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4")
   put(MediaStore.MediaColumns.RELATIVE_PATH,"DCIM/RTSPMonitor/")
   put(MediaStore.MediaColumns.IS_PENDING,1)
  })!!
  try {
   journal.writeText(uri.toString())
   val item=Recording(android.net.Uri.fromFile(file).toString(),"test",file.name,0)
   db.dao().save(item)
   VideoStore(context,db.dao()).delete(item)
   assertFalse(file.exists());assertFalse(journal.exists())
   assertTrue(db.dao().recordings().first().isEmpty())
   val exists=resolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,arrayOf(MediaStore.MediaColumns._ID),"_id=?",arrayOf(android.content.ContentUris.parseId(uri).toString()),null)?.use { it.moveToFirst() } ?: false
   assertFalse(exists)
  } finally { file.delete();journal.delete();runCatching { resolver.delete(uri,null,null) };db.close() }
 }
 @Test fun orphanPendingRowsAndMissingPublishedIndexesAreReconciled() = runBlocking {
  val context = InstrumentationRegistry.getInstrumentation().targetContext
  val db = Room.inMemoryDatabaseBuilder(context, MonitorDatabase::class.java).build()
  val resolver = context.contentResolver
  val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
   put(MediaStore.Video.Media.DISPLAY_NAME,"recovery-test.mp4")
   put(MediaStore.Video.Media.MIME_TYPE,"video/mp4")
   put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/RTSPMonitor/")
   put(MediaStore.Video.Media.IS_PENDING,1)
  })!!
  try {
   db.dao().save(Camera("camera-test","Test","ciphertext"))
   assertEquals("Test", db.dao().camera("camera-test")!!.name)
   db.dao().save(Recording(uri.toString(),"camera-test","recovery-test.mp4",0))
   VideoStore(context,db.dao()).reconcile()
   val exists=resolver.query(uri,arrayOf(MediaStore.Video.Media._ID),null,null,null)?.use { it.moveToFirst() } ?: false
   assertFalse(exists)
   assertTrue(db.dao().recordings().first().isEmpty())
  } finally { runCatching { resolver.delete(uri,null,null) }; db.close() }
 }
 @Test fun pendingRecordingCannotBeDeleted() = runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val db=Room.inMemoryDatabaseBuilder(context,MonitorDatabase::class.java).build()
  val resolver=context.contentResolver
  val uri=resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
   put(MediaStore.Video.Media.DISPLAY_NAME,"pending-protection.mp4")
   put(MediaStore.Video.Media.MIME_TYPE,"video/mp4")
   put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/RTSPMonitor/")
   put(MediaStore.Video.Media.IS_PENDING,1)
  })!!
  val recording=Recording(uri.toString(),"camera-test","pending-protection.mp4",0)
  try {
   db.dao().save(recording)
   assertTrue(runCatching { VideoStore(context,db.dao()).delete(recording) }.isFailure)
   resolver.query(uri,arrayOf(MediaStore.Video.Media.IS_PENDING),null,null,null)!!.use { assertTrue(it.moveToFirst()); assertEquals(1,it.getInt(0)) }
   assertEquals(1,db.dao().recordings().first().size)
  } finally { resolver.delete(uri,null,null); db.close() }
 }

}
