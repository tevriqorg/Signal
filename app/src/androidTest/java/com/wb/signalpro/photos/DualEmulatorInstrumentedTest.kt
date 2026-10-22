package com.wb.signalpro.photos

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.wb.signalpro.GalleryActivity
import com.wb.signalpro.data.model.MediaId
import com.wb.signalpro.data.model.MediaItem
import com.wb.signalpro.data.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Each phase runs in a separate instrumentation process on one of TWO real emulators.
 * The host orchestrator asserts serial identity and handles network loss/force-stop.
 */
@RunWith(AndroidJUnit4::class)
class DualEmulatorInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val role get() = args.getString("role") ?: error("role required")
    private fun client() = SignalMediaClient(context).also {
        if (!it.paired) it.enroll("http://127.0.0.1:8765", "synthetic-test-enrollment-secret", "Emulator-$role")
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun all(client: SignalMediaClient): List<RemotePhoto> {
        var cursor = 0L
        val result = linkedMapOf<String, RemotePhoto>()
        while (true) {
            val page = client.catalog(cursor)
            page.items.forEach { result[it.id] = it }
            if (page.items.isEmpty() || page.cursor == cursor) break
            cursor = page.cursor
        }
        return result.values.toList()
    }
    @Test fun phase() = runBlocking {
        val phase = args.getString("phase") ?: error("phase required")
        when (phase) {
            "publish" -> {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "dual-$role.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SignalDual/")
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                context.contentResolver.openOutputStream(uri)!!.use { out ->
                    val image = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                    image.eraseColor(if (role == "A") Color.RED else Color.BLUE)
                    assertTrue(image.compress(Bitmap.CompressFormat.JPEG, 90, out))
                    image.recycle()
                }
                val item = MediaItem(MediaId("dual-$role"), 1, uri.toString(), MediaType.PHOTO,
                    1790000000000, 1790000000000, 1, "image/jpeg", 512, 512, null, "Pictures/SignalDual/", "dual-$role.jpg")
                val client = client()
                val id = client.register(item)
                client.uploadThumbnail(item, id)
                client.uploadOriginal(item, id, { true }) { _, _ -> }
                assertTrue(all(client).first { it.id == id }.originalReady)
            }
            "receive" -> {
                val client = client()
                val peer = if (role == "A") "B" else "A"
                val media = all(client).first { it.fileName == "dual-$peer.jpg" }
                assertEquals("Emulator-$peer", media.deviceName)
                val file = client.fetchOriginal(media) { _, _ -> }
                assertEquals(media.sha256, sha(file.readBytes()))
                GallerySyncStore(context).use { it.ingest(all(client), client.catalog(0).cursor) }
                val activity = instrumentation.startActivitySync(Intent(context, GalleryActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                try {
                    val device = UiDevice.getInstance(instrumentation)
                    assertTrue(device.wait(Until.hasObject(By.text("照片")), 15000))
                    assertTrue(device.wait(Until.hasObject(By.textContains("Emulator-$peer")), 15000))
                    assertTrue(device.takeScreenshot(File("/sdcard/Pictures/dual-$role.png")))
                } finally { activity.finish() }
            }
            "offline" -> {
                val client = client()
                assertTrue(client.paired)
                assertTrue(GallerySyncStore(context).use { it.remote(client.deviceId).isNotEmpty() })
                assertTrue(runCatching { client.catalog(0) }.isFailure)
            }
            "migration" -> {
                val name = "signal-gallery-sync.db"
                context.deleteDatabase(name)
                context.openOrCreateDatabase(name, 0, null).use { db ->
                    db.execSQL("CREATE TABLE local_sync(local_id TEXT PRIMARY KEY, media_id TEXT NOT NULL,verified INTEGER NOT NULL DEFAULT 0,failed INTEGER NOT NULL DEFAULT 0)")
                    db.execSQL("CREATE TABLE remote_catalog(media_id TEXT PRIMARY KEY,json TEXT NOT NULL,revision INTEGER NOT NULL)")
                    db.execSQL("CREATE TABLE progress(key TEXT PRIMARY KEY,value INTEGER NOT NULL)")
                    db.execSQL("INSERT INTO local_sync VALUES('legacy','asset',1,0)")
                    db.version = 1
                }
                GallerySyncStore(context).use { store ->
                    assertFalse(store.local("legacy")!!.verified)
                    store.saveSourceVersion("legacy", "generation-2")
                    assertEquals("generation-2", store.sourceVersion("legacy"))
                }
            }
            else -> error("unknown phase")
        }
    }
}
