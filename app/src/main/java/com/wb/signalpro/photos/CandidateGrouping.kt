package com.wb.signalpro.photos

import com.wb.signalpro.data.model.MediaItem
import com.wb.signalpro.data.model.MediaType
import kotlin.math.abs

enum class CandidateBoundaryReason {
    START,
    TIME_GAP,
    SCREENSHOT_CAMERA_TRANSITION,
    BUCKET_TRANSITION,
}

data class CandidateGroup(
    val id: String,
    val items: List<MediaItem>,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val boundaryReason: CandidateBoundaryReason,
) {
    init {
        require(items.isNotEmpty()) { "CandidateGroup cannot be empty" }
    }
}

/**
 * Low-cost, deterministic first-stage segmentation.
 *
 * This intentionally does not perform AI interpretation. It preserves the input order and only
 * introduces candidate boundaries which later gates may reinterpret without changing MediaStore truth.
 */
object CandidateGroupSegmenter {
    private const val HARD_TIME_GAP_MS = 90L * 60L * 1_000L
    private const val SCREENSHOT_TRANSITION_GAP_MS = 10L * 60L * 1_000L
    private const val BUCKET_TRANSITION_GAP_MS = 30L * 60L * 1_000L

    fun segment(orderedItems: List<MediaItem>): List<CandidateGroup> {
        if (orderedItems.isEmpty()) return emptyList()

        val groups = mutableListOf<CandidateGroup>()
        var current = mutableListOf(orderedItems.first())
        var currentReason = CandidateBoundaryReason.START

        orderedItems.drop(1).forEach { item ->
            val previous = current.last()
            val reason = boundaryBetween(previous, item)
            if (reason == null) {
                current += item
            } else {
                groups += buildGroup(current, currentReason)
                current = mutableListOf(item)
                currentReason = reason
            }
        }
        groups += buildGroup(current, currentReason)
        return groups
    }

    private fun boundaryBetween(previous: MediaItem, next: MediaItem): CandidateBoundaryReason? {
        val gap = abs(timestamp(previous) - timestamp(next))
        if (gap >= HARD_TIME_GAP_MS) return CandidateBoundaryReason.TIME_GAP

        if (gap >= SCREENSHOT_TRANSITION_GAP_MS && screenshotCameraTransition(previous, next)) {
            return CandidateBoundaryReason.SCREENSHOT_CAMERA_TRANSITION
        }

        if (gap >= BUCKET_TRANSITION_GAP_MS) {
            val previousBucket = bucket(previous)
            val nextBucket = bucket(next)
            if (previousBucket != null && nextBucket != null && previousBucket != nextBucket) {
                return CandidateBoundaryReason.BUCKET_TRANSITION
            }
        }
        return null
    }

    private fun screenshotCameraTransition(first: MediaItem, second: MediaItem): Boolean {
        fun classOf(item: MediaItem): Int = when (item.mediaType) {
            MediaType.SCREENSHOT -> 1
            MediaType.PHOTO -> 2
            else -> 0
        }
        val a = classOf(first)
        val b = classOf(second)
        return a != 0 && b != 0 && a != b
    }

    private fun bucket(item: MediaItem): String? = item.relativePath
        ?.trim()
        ?.trimEnd('/')
        ?.takeIf { it.isNotEmpty() }
        ?.lowercase()

    private fun timestamp(item: MediaItem): Long = item.capturedAtEpochMs ?: item.addedAtEpochMs

    private fun buildGroup(
        items: List<MediaItem>,
        boundaryReason: CandidateBoundaryReason,
    ): CandidateGroup {
        val times = items.map(::timestamp)
        val firstId = items.first().id.value
        val lastId = items.last().id.value
        return CandidateGroup(
            id = "candidate:$firstId:$lastId:${items.size}",
            items = items.toList(),
            startedAtEpochMs = times.minOrNull() ?: 0L,
            endedAtEpochMs = times.maxOrNull() ?: 0L,
            boundaryReason = boundaryReason,
        )
    }
}
