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
        if (!it.paired) it.enroll(args.getString("endpoint") ?: "http://127.0.0.1:8765", args.getString("enrollment") ?: "synthetic-test-enrollment-secret", "Emulator-$role")
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun uploadSessionOffset(item: MediaItem, mediaId: String): Long {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        context.contentResolver.openInputStream(android.net.Uri.parse(item.contentUri))!!.use { input ->
            val block = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(block)
                if (count < 0) break
                digest.update(block, 0, count); size += count
            }
        }
        val credential = SignalPairingStore(context).read()!!
        val body = org.json.JSONObject().put("media_id", mediaId).put("size", size)
            .put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
            .put("source_version", sha(item.toString().toByteArray())).toString().toByteArray()
        val request = java.net.URL(credential.serverUrl + "/v1/uploads").openConnection() as java.net.HttpURLConnection
        try {
            request.requestMethod = "POST"; request.doOutput = true
            request.connectTimeout = 12000; request.readTimeout = 60000
            request.instanceFollowRedirects = false
            request.setRequestProperty("Authorization", "Bearer " + credential.deviceToken)
            request.setRequestProperty("Content-Type", "application/json")
            request.setFixedLengthStreamingMode(body.size)
            request.outputStream.use { it.write(body) }
            assertTrue("authenticated session probe", request.responseCode in 200..299)
            return org.json.JSONObject(request.inputStream.bufferedReader().use { it.readText() }).getLong("offset")
        } finally { request.disconnect() }
    }
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
    private suspend fun actualMedia(uri: String, settle: Boolean = false): MediaItem {
        if (settle) {
            val parsed = android.net.Uri.parse(uri)
            val path = context.contentResolver.query(parsed, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)!!.use {
                check(it.moveToFirst()); it.getString(0)
            }
            val latch = java.util.concurrent.CountDownLatch(1)
            android.media.MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, _ -> latch.countDown() }
            assertTrue("synthetic MediaStore publication must finish", latch.await(15, java.util.concurrent.TimeUnit.SECONDS))
        }
        return MediaIndexCoordinator(context).use { coordinator ->
            var previous: MediaItem? = null
            var stable = 0
            val deadline = System.currentTimeMillis() + 15000
            while (true) {
                coordinator.reconcile(forceFull = true)
                val item = coordinator.indexedMedia().first { it.mediaStoreId == android.content.ContentUris.parseId(android.net.Uri.parse(uri)) }
                if (!settle) return@use item
                stable = if (item == previous && (item.width ?: 0) > 0 && (item.height ?: 0) > 0) stable + 1 else 0
                if (stable >= 3) return@use item
                assertTrue("MediaStore metadata did not settle before fault injection", System.currentTimeMillis() < deadline)
                previous = item
                Thread.sleep(250)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    }
    @Test fun phase(): Unit = runBlocking {
        // Attach accessibility before opening a new window (API 35 registration race).
        instrumentation.uiAutomation.windows
        UiDevice.getInstance(instrumentation).waitForIdle()
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
                val item = actualMedia(uri.toString(), settle = true)
                val client = client()
                val id = client.register(item)
                client.uploadThumbnail(item, id)
                client.uploadOriginal(item, id, { true }) { _, _ -> }
                val published = all(client).first { it.id == id }
                assertTrue(published.originalReady)
                context.getSharedPreferences("dual-observed", 0).edit().putString("own-sha", published.sha256).commit()
            }
            "receive" -> {
                val client = client()
                val peer = if (role == "A") "B" else "A"
                val media = all(client).first { it.fileName == "dual-$peer.jpg" }
                assertEquals("Emulator-$peer", media.deviceName)
                context.getSharedPreferences("dual-observed", 0).edit().putString("peer-sha", media.sha256).commit()
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
                    val preview = engine.loadPreview(media)
                    val previewSha = sha(preview.readBytes())
                    java.io.RandomAccessFile(preview, "rw").use { it.seek(0); it.write(0) }
                    assertEquals("damaged cached preview must be replaced", previewSha, sha(engine.loadPreview(media).readBytes()))
                    assertFalse("preview success cannot hide original failure", engine.snapshot(true, "").healthy)
                    engine.loadOriginal(media) { _, _ -> }
                    assertFalse(engine.snapshot(true, "").remoteFailures.contains(media.id))
                    val generation = engine.snapshot(true, "").cacheGeneration
                    assertTrue(engine.clearOriginalCache() > 0)
                    assertNull(verifiedGalleryOriginal(context, media))
                    assertTrue(engine.snapshot(true, "").cacheGeneration > generation)
                    assertEquals(media.sha256, sha(engine.loadOriginal(media) { _, _ -> }.readBytes()))
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
                val media = GallerySyncStore(context).use { it.remote(client.deviceId).first { photo -> photo.fileName.startsWith("dual-") && !photo.isVideo } }
                GallerySyncEngine(context).use { engine ->
                    engine.clearOriginalCache()
                    client.preview(media.id)?.delete()
                    assertTrue(runCatching { engine.loadOriginal(media) { _, _ -> } }.isFailure)
                    assertTrue(runCatching { engine.loadPreview(media) }.isFailure)
                    var state = engine.snapshot(true, "")
                    assertFalse("temporary offline download must remain waiting, never red", state.remoteFailures.contains(media.id))
                    engine.foreground = true
                    engine.synchronize(emptyList()) { state = it }
                    assertTrue("temporary catalog disconnect is not a system fault", state.healthy)
                }
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
                val item = actualMedia(uri.toString(), settle = true)
                val client = client()
                val id = client.register(item)
                client.uploadThumbnail(item, id)
                var stopAt = 0L
                client.uploadOriginal(item, id, { stopAt < 1024 * 1024 }) { done, _ -> stopAt = done }
                assertFalse(all(client).first { it.id == id }.originalReady)
                GallerySyncEngine(context).use { engine ->
                    engine.allowBackgroundImages = true
                    var state = engine.snapshot(true, "")
                    engine.synchronize(listOf(item)) { state = it }
                    assertTrue("policy-deferred video is healthy", state.healthy)
                    assertEquals(1, state.pendingUploads)
                    assertFalse("background worker must not upload a video original", all(client).first { it.id == id }.originalReady)
                    assertEquals("background metadata reconciliation cannot advance original bytes", stopAt, uploadSessionOffset(item, id))
                }
                context.getSharedPreferences("dual-video", 0).edit().putString("uri", uri.toString()).putString("source-version", item.toString()).commit()
            }
            "resume-video", "interrupt-upload" -> {
                val uri = context.getSharedPreferences("dual-video", 0).getString("uri", null)!!
                val item = actualMedia(uri)
                assertEquals("network-fault fixture source must remain unchanged", context.getSharedPreferences("dual-video", 0).getString("source-version", null), item.toString())
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
                GalleryBackgroundScheduler.catchUp(context).result.get(15, java.util.concurrent.TimeUnit.SECONDS)
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
            "edit-offline" -> {
                val coordinator = MediaIndexCoordinator(context)
                val old = try { coordinator.reconcile(true); coordinator.indexedMedia().first { it.displayName == "dual-$role.jpg" } } finally { coordinator.close() }
                GallerySyncStore(context).use { assertTrue(it.local(old.id.value)!!.verified) }
                context.contentResolver.openOutputStream(android.net.Uri.parse(old.contentUri), "wt")!!.use { output ->
                    val edited = Bitmap.createBitmap(576, 512, Bitmap.Config.ARGB_8888)
                    edited.eraseColor(Color.GREEN)
                    edited.compress(Bitmap.CompressFormat.JPEG, 90, output)
                    edited.recycle()
                }
                val edited = actualMedia(old.contentUri, settle = true)
                assertNotEquals(old.modifiedGeneration, edited.modifiedGeneration)
                GallerySyncEngine(context).use { engine ->
                    engine.foreground = true
                    var state = engine.snapshot(true, "")
                    engine.synchronize(listOf(edited)) { state = it }
                    assertFalse("offline edit must never retain old verified state", state.local[edited.id.value]!!.verified)
                    assertTrue(state.pendingUploads > 0)
                }
            }
            "recover-edited" -> {
                val coordinator = MediaIndexCoordinator(context)
                val item = try { coordinator.reconcile(true); coordinator.indexedMedia().first { it.displayName == "dual-$role.jpg" } } finally { coordinator.close() }
                GallerySyncEngine(context).use { engine ->
                    engine.foreground = true
                    engine.synchronize(listOf(item)) { }
                    val transfer = engine.snapshot(true, "").local[item.id.value]!!
                    assertTrue(transfer.verified)
                    assertEquals(item.toString(), transfer.sourceVersion)
                }
                val media = all(client()).first { it.fileName == "dual-$role.jpg" }
                assertTrue(media.originalReady && media.thumbReady)
                assertNotEquals(context.getSharedPreferences("dual-observed", 0).getString("own-sha", null), media.sha256)
            }
            "receive-edited" -> {
                val client = client()
                val media = all(client).first { it.fileName == "dual-A.jpg" }
                assertNotEquals(context.getSharedPreferences("dual-observed", 0).getString("peer-sha", null), media.sha256)
                val file = client.fetchOriginal(media) { _, _ -> }
                assertEquals(media.sha256, sha(file.readBytes()))
                val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                assertEquals(576, bitmap.width)
                val color = bitmap.getPixel(200, 200)
                assertTrue(Color.green(color) > Color.red(color))
                bitmap.recycle()
                val cover = android.graphics.BitmapFactory.decodeFile(client.fetchPreview(media).absolutePath)
                val coverColor = cover.getPixel(100, 100)
                assertTrue(Color.green(coverColor) > Color.red(coverColor))
                cover.recycle()
                assertEquals(1, all(client).count { it.fileName == "dual-A.jpg" })
                val activity = instrumentation.startActivitySync(Intent(context, GalleryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                try {
                    val device = UiDevice.getInstance(instrumentation)
                    assertTrue(device.wait(Until.hasObject(By.desc("dual-A.jpg")), 15000))
                    assertTrue(device.hasObject(By.textContains("Emulator-A")))
                    assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), "dual-edited-B.png")))
                } finally { activity.finish() }
            }
            "archive-local" -> {
                val client = client()
                val media = all(client).first { it.fileName == "dual-$role.jpg" }
                val coordinator = MediaIndexCoordinator(context)
                try {
                    coordinator.reconcile(true)
                    val item = coordinator.indexedMedia().first { it.displayName == "dual-$role.jpg" }
                    assertEquals(1, context.contentResolver.delete(android.net.Uri.parse(item.contentUri), null, null))
                    coordinator.reconcile(false)
                    val remaining = coordinator.indexedMedia()
                    assertFalse(remaining.any { it.id == item.id })
                    GallerySyncEngine(context).use { engine ->
                        engine.foreground = true
                        engine.synchronize(remaining) { }
                        assertTrue(engine.snapshot(true, "").remote.any { it.id == media.id && it.deviceId == client.deviceId })
                    }
                    assertTrue(all(client).first { it.id == media.id }.originalReady)
                    val original = client.fetchOriginal(media) { _, _ -> }
                    assertEquals(media.sha256, sha(original.readBytes()))
                    val activity = instrumentation.startActivitySync(Intent(context, GalleryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    try {
                        val device = UiDevice.getInstance(instrumentation)
                        assertTrue(device.wait(Until.hasObject(By.desc(media.fileName)), 15000))
                        assertTrue(device.wait(Until.hasObject(By.textContains("Emulator-$role")), 15000))
                        assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), "dual-archive-$role.png")))
                    } finally { activity.finish() }
                } finally { coordinator.close() }
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
