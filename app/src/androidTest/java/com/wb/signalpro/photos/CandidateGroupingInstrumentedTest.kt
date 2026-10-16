package com.wb.signalpro.photos

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wb.signalpro.data.model.MediaId
import com.wb.signalpro.data.model.MediaItem
import com.wb.signalpro.data.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CandidateGroupingInstrumentedTest {
    @Test
    fun sameInputAlwaysProducesSameGroupsWithoutReordering() {
        val input = listOf(
            media("a", 300, MediaType.PHOTO, "DCIM/Camera"),
            media("b", 260, MediaType.PHOTO, "DCIM/Camera"),
            media("c", 200, MediaType.SCREENSHOT, "Pictures/Screenshots"),
        )
        val first = CandidateGroupSegmenter.segment(input)
        val second = CandidateGroupSegmenter.segment(input)
        assertEquals(first, second)
        assertEquals(input.map { it.id }, first.flatMap { it.items }.map { it.id })
        assertNotSame(input, first.flatMap { it.items })
    }

    @Test
    fun hardTimeGapSplits() {
        val input = listOf(
            media("a", 10_000, MediaType.PHOTO, "DCIM/Camera"),
            media("b", 10_000 - 91 * MINUTE, MediaType.PHOTO, "DCIM/Camera"),
        )
        val groups = CandidateGroupSegmenter.segment(input)
        assertEquals(2, groups.size)
        assertEquals(CandidateBoundaryReason.TIME_GAP, groups[1].boundaryReason)
    }

    @Test
    fun shortScreenshotRunStaysTogether() {
        val input = listOf(
            media("a", 20_000, MediaType.SCREENSHOT, "Pictures/Screenshots"),
            media("b", 20_000 - 2 * MINUTE, MediaType.SCREENSHOT, "Pictures/Screenshots"),
            media("c", 20_000 - 4 * MINUTE, MediaType.SCREENSHOT, "Pictures/Screenshots"),
        )
        assertEquals(1, CandidateGroupSegmenter.segment(input).size)
    }

    @Test
    fun meaningfulScreenshotCameraTransitionSplits() {
        val input = listOf(
            media("a", 20_000, MediaType.SCREENSHOT, "Pictures/Screenshots"),
            media("b", 20_000 - 11 * MINUTE, MediaType.PHOTO, "DCIM/Camera"),
        )
        val groups = CandidateGroupSegmenter.segment(input)
        assertEquals(2, groups.size)
        assertEquals(CandidateBoundaryReason.SCREENSHOT_CAMERA_TRANSITION, groups[1].boundaryReason)
    }

    @Test
    fun bucketChangeRequiresTemporalSupport() {
        val close = listOf(
            media("a", 40_000, MediaType.PHOTO, "DCIM/Camera"),
            media("b", 40_000 - 5 * MINUTE, MediaType.PHOTO, "Pictures/Other"),
        )
        assertEquals(1, CandidateGroupSegmenter.segment(close).size)

        val apart = listOf(
            media("a", 40_000, MediaType.PHOTO, "DCIM/Camera"),
            media("b", 40_000 - 31 * MINUTE, MediaType.PHOTO, "Pictures/Other"),
        )
        val groups = CandidateGroupSegmenter.segment(apart)
        assertEquals(2, groups.size)
        assertEquals(CandidateBoundaryReason.BUCKET_TRANSITION, groups[1].boundaryReason)
    }

    private fun media(id: String, minutes: Long, type: MediaType, path: String): MediaItem = MediaItem(
        id = MediaId(id),
        mediaStoreId = id.hashCode().toLong(),
        contentUri = "content://test/$id",
        mediaType = type,
        capturedAtEpochMs = minutes * MINUTE,
        addedAtEpochMs = minutes * MINUTE,
        modifiedGeneration = 1,
        mimeType = if (type == MediaType.VIDEO) "video/mp4" else "image/jpeg",
        width = 100,
        height = 100,
        durationMs = null,
        relativePath = path,
        displayName = "$id.jpg",
    )

    private companion object {
        const val MINUTE = 60_000L
    }
}
