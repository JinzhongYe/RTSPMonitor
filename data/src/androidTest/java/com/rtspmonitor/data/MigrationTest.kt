package com.rtspmonitor.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MigrationTest {
 @Test fun existingGalleryRecordingsSurviveUpgrade()=upgrade(1)
 @Test fun exportedLocalRecordingsSurviveVersionTwoUpgrade()=upgrade(2)
 private fun upgrade(version:Int)=runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val name="migration-${UUID.randomUUID()}.db"
  context.openOrCreateDatabase(name,0,null).use { db ->
   db.execSQL("CREATE TABLE Camera (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, encryptedConnection TEXT NOT NULL)")
   db.execSQL("CREATE TABLE Recording (uri TEXT NOT NULL PRIMARY KEY, cameraId TEXT NOT NULL, name TEXT NOT NULL, createdAt INTEGER NOT NULL)")
   db.execSQL("INSERT INTO Recording VALUES ('content://media/external/video/media/123','camera','old.mp4',123)")
   if(version==2) {
    db.execSQL("ALTER TABLE Recording ADD COLUMN exportedUri TEXT")
    db.execSQL("UPDATE Recording SET exportedUri='content://media/external/video/media/456'")
   }
   db.version=version
  }
  val db=Room.databaseBuilder(context,MonitorDatabase::class.java,name).addMigrations(MONITOR_MIGRATION_1_2,MONITOR_MIGRATION_2_3).build()
  try {
   val row=db.dao().recordings().first().single()
   assertEquals("old.mp4",row.name)
   assertEquals("content://media/external/video/media/123",row.uri)
   if(version==1) assertNull(row.exportedUri) else assertEquals("content://media/external/video/media/456",row.exportedUri);assertNull(row.startedAt);assertNull(row.endedAt);assertEquals(0,row.sourceMask)
  } finally { db.close();context.deleteDatabase(name) }
 }
}
