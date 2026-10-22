package com.wb.signalpro.photos

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class GallerySystemHealthInstrumentedTest {
    @Test fun authenticationFaultSurvivesCachedPreviewCacheClearAndProcessReopen(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val endpoint = "http://127.0.0.1:8765"
        suspend fun repair() {
            GallerySyncEngine(context).use { engine ->
                engine.pair(endpoint, "synthetic-test-enrollment-secret", "Android Emulator")
                engine.foreground = true
                engine.synchronize(emptyList()) { }
            }
        }
        repair()
        val id = java.util.UUID.randomUUID().toString()
        val preview = File(context.filesDir, "gallery-previews/$id.jpg")
        preview.parentFile!!.mkdirs()
        val bitmap = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
        preview.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
        bitmap.recycle()
        File(preview.parentFile, "$id.revision").writeText("1")
        File(preview.parentFile, "$id.sha256").writeText(MessageDigest.getInstance("SHA-256").digest(preview.readBytes()).joinToString("") { "%02x".format(it) })
        val media = RemotePhoto.parse(JSONObject().put("media_id", id).put("origin_device_id", "synthetic-peer")
            .put("revision", 1).put("thumbnail_ready", true)).copy(centerUrl = endpoint)
        try {
            SignalPairingStore(context).save(endpoint, "synthetic-invalid-token")
            GallerySyncEngine(context).use { engine ->
                engine.foreground = true
                engine.synchronize(emptyList()) { }
                assertFalse(engine.snapshot(true, "").healthy)
                assertEquals(preview, engine.loadPreview(media))
                assertFalse("cached preview cannot clear an authentication fault", engine.snapshot(true, "").healthy)
                engine.clearOriginalCache()
                assertFalse("cache cleanup cannot clear an authentication fault", engine.snapshot(true, "").healthy)
            }
            GallerySyncEngine(context).use { assertFalse("fault persists after engine reopen", it.snapshot(true, "").healthy) }
            repair()
            GallerySyncEngine(context).use { assertTrue("verified authenticated reconciliation clears the fault", it.snapshot(true, "").healthy) }
        } finally { repair(); preview.delete(); File(preview.parentFile, "$id.revision").delete(); File(preview.parentFile, "$id.sha256").delete() }
        Unit
    }

    @Test fun globalAnalysisDegradationIsVisibleEvenWithoutPerAssetErrors() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        GallerySyncEngine(context).use { engine ->
            engine.applyServerStatus(JSONObject().put("analysis_degraded", true)
                .put("analysis", JSONObject().put("enabled", true).put("failed", 0)))
            assertFalse(engine.snapshot(true, "").healthy)
            assertTrue(engine.snapshot(true, "").failures > 0)
            assertTrue(engine.snapshot(true, "").analysisStatus.contains("异常"))
        }
    }
}
