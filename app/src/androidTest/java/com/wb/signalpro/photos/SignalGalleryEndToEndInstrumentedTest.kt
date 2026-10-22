package com.wb.signalpro.photos

import android.Manifest
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
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/** Android 35 real APK + MediaStore + remote server through adb reverse port 8765.
 * Uses synthetic image and enrollment key ONLY, never personal data.
 */
@RunWith(AndroidJUnit4::class)
class SignalGalleryEndToEndInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val base = "http://127.0.0.1:8765"
    private val enrollKey = "synthetic-test-enrollment-secret"

    @Test
    fun androidAndSecondSyntheticDeviceCanExchangeVerifiedOriginal() {
        val app = context
        InstrumentationRegistry.getInstrumentation().uiAutomation.apply {
            grantRuntimePermission(app.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        }
        val source = createSyntheticMedia()
        try {
            val client = SignalMediaClient(app)
            // Fresh emulator starts unpaired. Device identity persists across client recreation.
            if (!client.paired) client.enroll(base, enrollKey, "Android Emulator")
            val mediaId = client.register(source.first)
            client.uploadThumbnail(source.first, mediaId)
            var maxProgress = 0L
            client.uploadOriginal(source.first, mediaId, { true }) { now, _ -> maxProgress = now }
            assertTrue(maxProgress > 0)

            val second = registerOtherDevice()
            val catalog = queryCatalog(second)
            val fromAndroid = catalog.getJSONArray("items")
            val found = (0 until fromAndroid.length()).map { fromAndroid.getJSONObject(it) }
                .first { it.getString("media_id") == mediaId }
            assertEquals("Android Emulator", found.getString("device_name"))
            assertTrue(found.getBoolean("original_ready"))
            assertTrue(found.getBoolean("thumbnail_ready"))
            val fetched = request("GET", "/v1/media/" + mediaId + "/original", null, second)
            assertArrayEquals(source.second, fetched)

            val foreignId = registerRemoteSynthetic(second)
            val otherItem = client.catalog(0).items.first { it.id == foreignId }
            assertEquals("Second device", otherItem.deviceName)
            assertTrue(client.fetchPreview(otherItem).isFile)
            assertArrayEquals(source.second, client.fetchOriginal(otherItem) { _, _ -> }.readBytes())

            val stored = GallerySyncStore(app)
            stored.ingest(client.catalog(0).items, 1)
            val reopened = GallerySyncStore(app)
            assertTrue(reopened.remote(client.deviceId).any { it.id == foreignId })
            stored.close()
            reopened.close()
        } finally {
            app.contentResolver.delete(source.first.contentUri.let { android.net.Uri.parse(it) }, null, null)
        }
    }

    @Test
    fun nativePhotoWallAndCloudDetailsAreReachableThroughRealUi() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val app = context
        val activity = instrumentation.startActivitySync(Intent(app, GalleryActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            assertTrue(device.wait(Until.hasObject(By.text("照片")), 15_000))
            assertNotNull(device.findObject(By.text("视频")))
            assertNotNull(device.findObject(By.text("截图")))
            val cloud = device.findObject(By.text("☁"))
            assertNotNull("cloud status control", cloud)
            cloud.click()
            assertTrue(device.wait(Until.hasObject(By.text("同步与存储")), 8_000))
            assertTrue(device.hasObject(By.textContains("本机索引")))
            device.findObject(By.text("关闭")).click()
            // Screenshot is evidence only; no assertion is based on color-pixel matching.
            device.takeScreenshot(java.io.File("/sdcard/Pictures/signal-gallery-emulator.png"))
        } finally { activity.finish() }
    }

    private fun createSyntheticMedia(): Pair<MediaItem, ByteArray> {
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(175, 68, 46))
        val out = ByteArrayOutputStream()
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out))
        bitmap.recycle()
        val bytes = out.toByteArray()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "signal-synthetic-test.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SignalSynthetic/")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed")
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: error("MediaStore output failed")
        return MediaItem(
            id = MediaId("synthetic:" + UUID.randomUUID()),
            mediaStoreId = 10,
            contentUri = uri.toString(),
            mediaType = MediaType.PHOTO,
            capturedAtEpochMs = System.currentTimeMillis(),
            addedAtEpochMs = System.currentTimeMillis(),
            modifiedGeneration = 1,
            mimeType = "image/jpeg",
            width = 48,
            height = 48,
            durationMs = null,
            relativePath = "Pictures/SignalSynthetic/",
            displayName = "signal-synthetic-test.jpg",
        ) to bytes
    }

    private fun registerOtherDevice(): String {
        val result = JSONObject(String(request("POST", "/v1/devices",
            JSONObject().put("device_id", UUID.randomUUID().toString())
                .put("device_name", "Second device").put("platform", "test").toString().toByteArray(),
            null, mapOf("X-Enrollment-Key" to enrollKey))))
        return result.getString("device_token")
    }

    private fun registerRemoteSynthetic(token: String): String {
        val id = JSONObject(String(request("POST", "/v1/media",
            JSONObject().put("local_id", "other-" + UUID.randomUUID())
                .put("media_type", "photo").put("mime_type", "image/jpeg")
                .put("filename", "other-photo.jpg").toString().toByteArray(), token)))
            .getString("media_id")
        val sample = createSyntheticMedia()
        try {
            val hash = MessageDigest.getInstance("SHA-256").digest(sample.second)
                .joinToString("") { "%02x".format(it) }
            request("PUT", "/v1/media/" + id + "/thumbnail", sample.second, token,
                mapOf("X-SHA256" to hash))
            val start = JSONObject(String(request("POST", "/v1/uploads",
                JSONObject().put("media_id", id).put("size", sample.second.size)
                    .put("sha256", hash).toString().toByteArray(), token)))
            if (!start.optBoolean("complete")) {
                request("PUT", "/v1/uploads/" + start.getString("upload_id"),
                    sample.second, token, mapOf("X-Offset" to "0"))
            }
            assertArrayEquals(sample.second, request("GET", "/v1/media/$id/original", null, token))
            return id
        } finally {
            context.contentResolver.delete(android.net.Uri.parse(sample.first.contentUri), null, null)
        }
    }

    private fun queryCatalog(token: String) = JSONObject(String(request("GET", "/v1/media", null, token)))

    private fun request(method: String, path: String, body: ByteArray?, token: String?,
        more: Map<String, String> = emptyMap()): ByteArray {
        val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 10_000
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            more.forEach { (key, value) -> setRequestProperty(key, value) }
            if (body != null) {
                doOutput = true
                setFixedLengthStreamingMode(body.size)
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body) }
            assertTrue("server response " + conn.responseCode, conn.responseCode in 200..299)
            return conn.inputStream.use { it.readBytes() }
        } finally { conn.disconnect() }
    }
}
