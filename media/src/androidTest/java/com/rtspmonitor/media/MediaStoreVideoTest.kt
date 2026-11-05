package com.rtspmonitor.media

import android.provider.MediaStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rtspmonitor.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MediaStoreVideoTest {
    @Test fun h264PublishAndDelete() = publish("h264.mp4")
    @Test fun h265PublishAndDelete() = publish("h265.mp4")
    @Test fun longFragmentedMp4Publishes() = publish("long-h264.mp4")

    @Test fun invalidContainerIsNotPublished() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, MonitorDatabase::class.java).build()
        val store = VideoStore(context, db.dao())
        val file = File(store.temporary, "${UUID.randomUUID()}.mp4").apply { writeText("not a video") }
        try {
            assertTrue(runCatching { store.publish(file, Camera("invalid", "Invalid", "not-used")) }.isFailure)
            assertTrue(db.dao().recordings().first().isEmpty())
            assertTrue("Failed validation must retain the source", file.exists())
        } finally { file.delete(); db.close() }
    }

    private fun publish(name: String) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("useFixtures") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.getExternalFilesDir("fixtures"), name)
        assertTrue("Generate synthetic fixtures first", fixture.exists())
        val db = Room.inMemoryDatabaseBuilder(context, MonitorDatabase::class.java).build()
        val store = VideoStore(context, db.dao())
        val file = File(store.temporary, "${UUID.randomUUID()}.mp4")
        fixture.copyTo(file)
        val start=java.time.Instant.parse("2026-11-05T02:30:00Z").toEpochMilli()
        val metadata=org.json.JSONObject().put("startedAt",start).put("endedAt",start+6000).put("offsetMinutes",480).put("sources",3).toString()
        File(file.parentFile,file.nameWithoutExtension+".meta").writeText(metadata)
        val camera = Camera("fixture-camera", "Camera_01-门口", "not-used")
        var published: Recording? = null
        try {
            store.publish(file, camera)
            val item = db.dao().recordings().first().single()
            published = item
            assertFalse("Published temporary file must be removed", file.exists())
            assertEquals("20261105_103000-20261105_103006_混合.mp4",item.name)
            assertEquals(3,item.sourceMask);assertEquals(start,item.startedAt)
            val duplicate=File(store.temporary,"${UUID.randomUUID()}.mp4")
            fixture.copyTo(duplicate)
            File(duplicate.parentFile,duplicate.nameWithoutExtension+".meta").writeText(metadata)
            store.publish(duplicate,camera)
            val other=db.dao().recordings().first().single { it.uri!=item.uri }
            assertEquals("20261105_103000-20261105_103006_混合_1.mp4",other.name)
            store.delete(other)
            val uri = android.net.Uri.parse(item.uri)
            assertEquals("file",uri.scheme)
            val exported=store.export(item)
            assertEquals(exported,store.export(item))
            context.contentResolver.query(android.net.Uri.parse(exported), arrayOf(MediaStore.Video.Media.RELATIVE_PATH, MediaStore.Video.Media.IS_PENDING,MediaStore.Video.Media.DISPLAY_NAME), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals("DCIM/RTSPMonitor/", it.getString(0))
                assertEquals(0, it.getInt(1))
                assertEquals(item.name,it.getString(2))
            }
            val expected = MessageDigest.getInstance("SHA-256").digest(fixture.readBytes())
            val actual = context.contentResolver.openInputStream(uri)!!.use { MessageDigest.getInstance("SHA-256").digest(it.readBytes()) }
            assertArrayEquals("MediaStore copy must preserve every byte", expected, actual)
            store.delete(item)
            context.contentResolver.openInputStream(android.net.Uri.parse(exported))!!.use { assertTrue(it.read()!=-1) }
            context.contentResolver.delete(android.net.Uri.parse(exported),null,null)
            published = null
            assertTrue(db.dao().recordings().first().isEmpty())
        } finally {
            published?.let { store.delete(it) }
            file.delete(); db.close()
        }
    }
}
