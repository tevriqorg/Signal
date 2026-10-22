package com.wb.signalpro.photos

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryTransferBadgeInstrumentedTest {
    private val local = GallerySource(GalleryOrigin.THIS_DEVICE, "本机")
    private val pixel = GallerySource(GalleryOrigin.OTHER_DEVICE, "Pixel 8")
    private val shared = GallerySource(GalleryOrigin.SHARED, "iPhone 17", "朋友")

    @Test fun verifiedLocalHasNoDotButPendingAndInProgressUseAmber() {
        assertEquals(GalleryDot.NONE, GalleryTransferBadge(local, GalleryTransferState.ORIGINAL_VERIFIED).dot)
        assertEquals(GalleryDot.AMBER, GalleryTransferBadge.localPending().dot)
        assertEquals(GalleryDot.AMBER, GalleryTransferBadge(local, GalleryTransferState.METADATA_ONLY).dot)
        assertEquals(GalleryDot.AMBER, GalleryTransferBadge(local, GalleryTransferState.UPLOADING_ORIGINAL).dot)
        assertEquals(GalleryDot.RED, GalleryTransferBadge(local, GalleryTransferState.UPLOAD_FAILED).dot)
    }

    @Test fun remoteAndSharedKeepSourceRegardlessOfCaching() {
        assertEquals(GalleryDot.AMBER, GalleryTransferBadge(pixel, GalleryTransferState.PREVIEW_ONLY).dot)
        assertEquals(GalleryDot.AMBER, GalleryTransferBadge(pixel, GalleryTransferState.DOWNLOADING_ORIGINAL).dot)
        assertEquals(GalleryDot.NONE, GalleryTransferBadge(pixel, GalleryTransferState.ORIGINAL_CACHED).dot)
        assertEquals(GalleryDot.RED, GalleryTransferBadge(pixel, GalleryTransferState.DOWNLOAD_FAILED).dot)
        assertEquals("Pixel 8", pixel.label)
        assertTrue(pixel.showCloud)
        assertEquals("朋友 · iPhone 17", shared.label)
        assertTrue(shared.showCloud)
        assertFalse(local.showCloud)
    }

    @Test fun impossibleSourceStatusCannotBeMistakenAsComplete() {
        assertEquals(GalleryDot.RED, GalleryTransferBadge(local, GalleryTransferState.ORIGINAL_CACHED).dot)
        assertEquals(GalleryDot.RED, GalleryTransferBadge(pixel, GalleryTransferState.ORIGINAL_VERIFIED).dot)
    }
}
