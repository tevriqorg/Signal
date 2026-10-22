package com.wb.signalpro.photos

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wb.signalpro.data.model.MediaId
import com.wb.signalpro.data.model.MediaItem
import com.wb.signalpro.data.model.MediaType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryRecoveryInstrumentedTest {
    @Test fun fullLocalIndexHasNoTwoThousandItemTruncation(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("signal-media.db")
        val items = (0 until 2501).map { number ->
            MediaItem(MediaId("large-fixture:$number"), number.toLong(), "content://synthetic/$number", MediaType.PHOTO,
                number.toLong(), number.toLong(), 1, "image/jpeg", 10, 10, null, "Synthetic/", "fixture-$number.jpg")
        }
        MediaIndexStore(context).use { it.upsert(items) }
        MediaIndexCoordinator(context).use { coordinator ->
            val loaded = coordinator.indexedMedia()
            assertEquals(2501, loaded.size)
            assertEquals(2501, loaded.map { it.id }.toSet().size)
        }
        context.deleteDatabase("signal-media.db")
        Unit
    }

    @Test fun concurrentForegroundAndCatchupShareOneVerifiedTransfer(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.READ_MEDIA_IMAGES)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.READ_MEDIA_VIDEO)
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "concurrent-synthetic.jpg")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SignalSynthetic/")
        }
        val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        try {
            context.contentResolver.openOutputStream(uri)!!.use { output ->
                val bitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.MAGENTA)
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output)
                bitmap.recycle()
            }
            val coordinator = MediaIndexCoordinator(context)
            val item = try {
                coordinator.reconcile(true)
                coordinator.indexedMedia().first { it.mediaStoreId == android.content.ContentUris.parseId(uri) }
            } finally { coordinator.close() }
            val client = SignalMediaClient(context)
            if (!client.paired) client.enroll("http://127.0.0.1:8765", "synthetic-test-enrollment-secret", "Android Emulator")
            val first = GallerySyncEngine(context).apply { foreground = true }
            val second = GallerySyncEngine(context).apply { allowBackgroundImages = true }
            try {
                listOf(async { first.synchronize(listOf(item)) { } }, async { second.synchronize(listOf(item)) { } }).awaitAll()
                assertTrue(first.snapshot(true, "").local[item.id.value]!!.verified)
                assertTrue(second.snapshot(true, "").local[item.id.value]!!.verified)
                assertEquals(1, client.catalog(0).items.map { it.id }.distinct().count { id ->
                    client.catalog(0).items.any { it.id == id && it.fileName == "concurrent-synthetic.jpg" }
                })
                assertEquals(0, first.snapshot(true, "").failures)
            } finally { first.close(); second.close() }
        } finally { context.contentResolver.delete(uri, null, null) }
        Unit
    }

    @Test fun videoNotificationIsPermissionAwareAndThrottled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        context.getSharedPreferences("gallery-video-notice", 0).edit().clear().commit()
        assertTrue(GalleryVideoNotification.offer(context))
        assertFalse(GalleryVideoNotification.offer(context))
        val notices = context.getSystemService(android.app.NotificationManager::class.java).activeNotifications
        assertTrue(notices.any { it.id == 14 && it.notification.fullScreenIntent == null })
        context.getSystemService(android.app.NotificationManager::class.java).cancel(14)
    }
}
