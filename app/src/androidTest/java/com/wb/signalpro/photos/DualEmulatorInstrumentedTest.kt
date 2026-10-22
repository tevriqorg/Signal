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
    private suspend fun actualMedia(uri: String): MediaItem = MediaIndexCoordinator(context).use { coordinator ->
        coordinator.reconcile(forceFull = true)
        coordinator.indexedMedia().first {
            it.mediaStoreId == android.content.ContentUris.parseId(android.net.Uri.parse(uri))
        }
    }
    @Test fun phase(): Unit = runBlocking {
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
                val item = actualMedia(uri.toString())
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
                // Same-size cache corruption must be detected and repaired from the authenticated server.
                java.io.RandomAccessFile(file, "rw").use { it.seek(0); it.write(0) }
                assertEquals(media.sha256, sha(client.fetchOriginal(media) { _, _ -> }.readBytes()))
                GallerySyncEngine(context).use { engine ->
                    val wrong = media.copy(sha256 = "0".repeat(64))
                    assertTrue(runCatching { engine.loadOriginal(wrong) { _, _ -> } }.isFailure)
                }
                GallerySyncEngine(context).use { engine ->
                    assertTrue(engine.snapshot(true, "").remoteFailures.contains(media.id))
                    engine.loadPreview(media)
                    assertFalse("preview success cannot hide original failure", engine.snapshot(true, "").healthy)
                    engine.loadOriginal(media) { _, _ -> }
                    assertFalse(engine.snapshot(true, "").remoteFailures.contains(media.id))
                }
                GallerySyncStore(context).use { it.ingest(all(client), client.catalog(0).cursor) }
                val activity = instrumentation.startActivitySync(Intent(context, GalleryActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                try {
                    val device = UiDevice.getInstance(instrumentation)
                    assertTrue(device.wait(Until.hasObject(By.text("照片")), 15000))
                    assertTrue(device.wait(Until.hasObject(By.textContains("Emulator-$peer")), 15000))
                    // Query the actual Signal Server OCR/vision index from the installed Android APK.
                    val deadline = System.currentTimeMillis() + 60000
                    var visual: List<RemotePhoto> = emptyList()
                    while (System.currentTimeMillis() < deadline) {
                        visual = client.catalog(0, "红色的方块").items
                        if (visual.any { it.fileName == "dual-A.jpg" }) break
                        Thread.sleep(1000)
                    }
                    val red = visual.indexOfFirst { it.fileName == "dual-A.jpg" }
                    val blue = visual.indexOfFirst { it.fileName == "dual-B.jpg" }
                    assertTrue("real multilingual visual index must return the generated red image", red >= 0)
                    assertTrue(blue < 0 || red < blue)
                    assertTrue(visual[red].matchReasons.isNotBlank())
                    fun searchUi(text: String) {
                        device.findObject(By.text("···")).click()
                        assertTrue(device.wait(Until.hasObject(By.text("搜索相册")), 8000))
                        device.findObject(By.text("搜索相册")).click()
                        assertTrue(device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 8000))
                        device.findObject(By.clazz("android.widget.EditText")).text = text
                        device.findObject(By.text("搜索")).click()
                    }
                    searchUi("no-such-synthetic-123.jpg")
                    assertTrue(device.wait(Until.hasObject(By.text("当前还没有这一类照片")), 15000))
                    assertFalse(device.hasObject(By.desc("dual-$role.jpg")))
                    searchUi("dual-$peer.jpg")
                    assertTrue(device.wait(Until.hasObject(By.desc("dual-$peer.jpg")), 15000))
                    assertFalse("search must not retain unrelated local photos", device.hasObject(By.desc("dual-$role.jpg")))
                    device.findObject(By.text("···")).click()
                    assertTrue(device.wait(Until.hasObject(By.text("搜索相册")), 8000))
                    device.findObject(By.text("搜索相册")).click()
                    assertTrue(device.wait(Until.hasObject(By.text("清除筛选")), 8000))
                    device.findObject(By.text("清除筛选")).click()
                    assertTrue(device.wait(Until.hasObject(By.desc("dual-$peer.jpg")), 15000))
                    if (all(client).any { it.fileName == "dual-$role.jpg" })
                        assertTrue(device.wait(Until.hasObject(By.desc("dual-$role.jpg")), 15000))
                    assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), "dual-$role.png")))
                } finally { activity.finish() }
            }
            "offline" -> {
                val client = client()
                assertTrue(client.paired)
                assertTrue(GallerySyncStore(context).use { it.remote(client.deviceId).isNotEmpty() })
                assertTrue(runCatching { client.catalog(0) }.isFailure)
            }
            "publish-video" -> {
                val input = File("/sdcard/Download/signal-large.mp4")
                assertTrue("fixture must exceed 128 MiB", input.length() > 128L * 1024 * 1024)
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "dual-large-$role.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SignalDual/")
                }
                val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
                context.contentResolver.openOutputStream(uri)!!.use { output -> input.inputStream().use { it.copyTo(output) } }
                val item = actualMedia(uri.toString())
                val client = client()
                val id = client.register(item)
                client.uploadThumbnail(item, id)
                var stopAt = 0L
                client.uploadOriginal(item, id, { stopAt < 1024 * 1024 }) { done, _ -> stopAt = done }
                assertFalse(all(client).first { it.id == id }.originalReady)
                context.getSharedPreferences("dual-video", 0).edit().putString("uri", uri.toString()).commit()
            }
            "resume-video", "interrupt-upload" -> {
                val uri = context.getSharedPreferences("dual-video", 0).getString("uri", null)!!
                val item = actualMedia(uri)
                val client = client()
                val id = all(client).first { it.fileName == "dual-large-$role.mp4" }.id
                var initial = -1L
                client.uploadOriginal(item, id, { true }) { done, _ ->
                    if (initial < 0) initial = done
                    if (phase == "interrupt-upload") {
                        File(context.filesDir, "dual-interrupt-upload.progress").writeText(done.toString())
                        Thread.sleep(25)
                    }
                }
                assertTrue("must resume past first MiB", initial > 1024 * 1024)
                assertTrue(all(client).first { it.id == id }.originalReady)
            }
            "interrupt-download" -> {
                val client = client()
                val media = all(client).first { it.fileName.startsWith("dual-large-") }
                client.fetchOriginal(media) { done, _ ->
                    File(context.filesDir, "dual-interrupt-download.progress").writeText(done.toString())
                    Thread.sleep(25)
                }
            }
            "receive-video" -> {
                val client = client()
                val media = all(client).first { it.fileName.startsWith("dual-large-") }
                var first = -1L
                val file = client.fetchOriginal(media) { done, _ -> if (first < 0) first = done }
                assertTrue("download must resume beyond previous partial", first > 1024 * 1024)
                assertEquals(media.size, file.length())
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                }
                assertEquals(media.sha256, digest.digest().joinToString("") { "%02x".format(it) })
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    assertTrue(retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() > 30000)
                    assertNotNull(retriever.getFrameAtTime(1000000))
                } finally { retriever.release() }
                GallerySyncStore(context).use { it.ingest(all(client), client.catalog(0).cursor) }
                val activity = instrumentation.startActivitySync(Intent(context, GalleryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                try {
                    val device = UiDevice.getInstance(instrumentation)
                    assertTrue(device.wait(Until.hasObject(By.text("视频")), 15000))
                    device.findObject(By.text("视频")).click()
                    assertTrue(device.wait(Until.hasObject(By.desc(media.fileName)), 15000))
                    device.findObject(By.desc(media.fileName)).click()
                    assertTrue(device.wait(Until.hasObject(By.text("使用系统播放器打开")), 15000))
                    device.findObject(By.text("使用系统播放器打开")).click()
                    assertTrue(device.wait(Until.hasObject(By.desc("视频正在播放")), 15000))
                    device.takeScreenshot(File(context.getExternalFilesDir(null), "dual-video-playing.png"))
                } finally { activity.finish() }
            }
            "background-seed" -> {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "background-$role.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SignalDual/")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                context.contentResolver.openOutputStream(uri)!!.use { output ->
                    val image = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
                    image.eraseColor(Color.GREEN)
                    image.compress(Bitmap.CompressFormat.JPEG, 80, output)
                    image.recycle()
                }
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                GalleryBackgroundScheduler.catchUp(context)
            }
            "background-verify" -> {
                val client = client()
                val deadline = System.currentTimeMillis() + 60000
                var ready = false
                while (System.currentTimeMillis() < deadline) {
                    ready = all(client).any { it.fileName == "background-$role.jpg" && it.originalReady && it.thumbReady }
                    if (ready) break
                    Thread.sleep(1000)
                }
                assertTrue("OS worker must upload indexed media after idle and restart", ready)
            }
            "permission-full", "permission-partial", "permission-denied" -> {
                val expected = when (phase) {
                    "permission-full" -> MediaAccessLevel.FULL
                    "permission-partial" -> MediaAccessLevel.PARTIAL
                    else -> MediaAccessLevel.DENIED
                }
                assertEquals(expected, MediaPermissionPolicy.current(context))
                val activity = instrumentation.startActivitySync(Intent(context, GalleryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                try {
                    val device = UiDevice.getInstance(instrumentation)
                    assertTrue(device.wait(Until.hasObject(By.text("照片")), 15000))
                    if (expected != MediaAccessLevel.FULL) {
                        val label = if (expected == MediaAccessLevel.PARTIAL) "仅部分相册可访问" else "授权后才能浏览本机相册"
                        assertTrue(device.wait(Until.hasObject(By.text(label)), 15000))
                    }
                    assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), "dual-permission-$role.png")))
                } finally { activity.finish() }
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
        Unit
    }
}
