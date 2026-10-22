package com.wb.signalpro.photos

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wb.signalpro.data.model.MediaId
import com.wb.signalpro.data.model.MediaItem
import com.wb.signalpro.data.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryRecoveryInstrumentedTest {
    @Test fun fullLocalIndexHasNoTwoThousandItemTruncation() = runBlocking {
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
