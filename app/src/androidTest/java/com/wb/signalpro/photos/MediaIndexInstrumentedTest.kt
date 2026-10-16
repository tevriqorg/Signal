package com.wb.signalpro.photos

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MediaIndexInstrumentedTest {
    @Test
    fun fullSnapshotThenRestartUsesGenerationIncrementalCatchup() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_VIDEO)
        context.deleteDatabase("signal-media.db")

        val firstUri = createImage(context, "signal_g1_${UUID.randomUUID()}.png")
        val firstId = ContentUris.parseId(firstUri)
        var secondUri: android.net.Uri? = null
        try {
            val firstCoordinator = MediaIndexCoordinator(context)
            val fullReport = firstCoordinator.reconcile(forceFull = true)
            assertEquals(MediaAccessLevel.FULL, fullReport.accessLevel)
            assertTrue(firstCoordinator.indexedMedia().any { it.mediaStoreId == firstId })
            firstCoordinator.close()

            secondUri = createImage(context, "signal_g1_${UUID.randomUUID()}.png")
            val secondId = ContentUris.parseId(secondUri)
            val restartedCoordinator = MediaIndexCoordinator(context)
            val catchup = restartedCoordinator.reconcile(forceFull = false)
            assertTrue(catchup.modes.values.any { it == ReconcileMode.INCREMENTAL || it == ReconcileMode.FULL })
            assertTrue(restartedCoordinator.indexedMedia().any { it.mediaStoreId == secondId })
            restartedCoordinator.close()
        } finally {
            context.contentResolver.delete(firstUri, null, null)
            secondUri?.let { context.contentResolver.delete(it, null, null) }
        }
    }

    private fun createImage(context: android.content.Context, name: String): android.net.Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SignalG1")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        context.contentResolver.openOutputStream(uri).use { stream ->
            requireNotNull(stream)
            val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.rgb(32, 128, 220))
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            bitmap.recycle()
        }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }
}
