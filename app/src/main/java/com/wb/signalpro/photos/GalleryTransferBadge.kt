package com.wb.signalpro.photos

/**
 * UI semantics for #14. Transport state and media origin are independent.
 *
 * IMPORTANT: Until Signal Server acknowledges the original, never claim a local photo is
 * fully synchronized. Until the complete remote original is cached, never claim it is local.
 */
internal enum class GalleryOrigin {
    THIS_DEVICE,
    OTHER_DEVICE,
    SHARED,
}

internal enum class GalleryTransferState {
    // Local source states
    NOT_REGISTERED,
    METADATA_ONLY,
    UPLOADING_ORIGINAL,
    ORIGINAL_VERIFIED,
    UPLOAD_FAILED,

    // Remote source states
    PREVIEW_ONLY,
    DOWNLOADING_ORIGINAL,
    ORIGINAL_CACHED,
    DOWNLOAD_FAILED,
}

internal enum class GalleryDot {
    NONE,
    AMBER,
    RED,
}

internal data class GallerySource(
    val origin: GalleryOrigin,
    val deviceName: String,
    val ownerName: String? = null,
    val localLibraryEntry: Boolean = origin == GalleryOrigin.THIS_DEVICE,
) {
    init {
        require(deviceName.isNotBlank())
        require(!localLibraryEntry || origin == GalleryOrigin.THIS_DEVICE)
        if (origin == GalleryOrigin.SHARED) require(!ownerName.isNullOrBlank())
    }

    val label: String
        get() = when {
            localLibraryEntry -> "本机"
            origin == GalleryOrigin.SHARED -> "$ownerName · $deviceName"
            else -> deviceName
        }

    val showCloud: Boolean get() = !localLibraryEntry
}

internal data class GalleryTransferBadge(
    val source: GallerySource,
    val transfer: GalleryTransferState,
) {
    val dot: GalleryDot
        get() = when {
            source.localLibraryEntry -> when (transfer) {
                GalleryTransferState.ORIGINAL_VERIFIED -> GalleryDot.NONE
                GalleryTransferState.UPLOAD_FAILED -> GalleryDot.RED
                GalleryTransferState.NOT_REGISTERED,
                GalleryTransferState.METADATA_ONLY,
                GalleryTransferState.UPLOADING_ORIGINAL -> GalleryDot.AMBER
                else -> GalleryDot.RED // invalid remote-only states must not silently appear complete
            }
            else -> when (transfer) {
                GalleryTransferState.ORIGINAL_CACHED -> GalleryDot.NONE
                GalleryTransferState.DOWNLOAD_FAILED -> GalleryDot.RED
                GalleryTransferState.PREVIEW_ONLY,
                GalleryTransferState.DOWNLOADING_ORIGINAL -> GalleryDot.AMBER
                else -> GalleryDot.RED
            }
        }

    companion object {
        fun localPending() = GalleryTransferBadge(
            source = GallerySource(GalleryOrigin.THIS_DEVICE, "本机"),
            transfer = GalleryTransferState.NOT_REGISTERED,
        )
    }
}

/** Green means healthy, not that every deferred video/remote cache has completed. */
internal enum class GalleryCloudHealth {
    HEALTHY,
    NEEDS_ATTENTION,
}
