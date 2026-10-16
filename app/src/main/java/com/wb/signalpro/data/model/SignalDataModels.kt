package com.wb.signalpro.data.model

@JvmInline
value class MediaId(val value: String)

@JvmInline
value class EventId(val value: String)

@JvmInline
value class EpisodeId(val value: String)

@JvmInline
value class ContextId(val value: String)

enum class SensitivityClass {
    NORMAL,
    PRIVATE,
    SENSITIVE,
}

enum class RetentionClass {
    RAW_EPHEMERAL,
    LOCAL_INDEX,
    SEMANTIC_CONTEXT,
    SENSITIVE_METADATA_ONLY,
    EXPLICIT_UPLOAD,
}

enum class SyncPolicy {
    LOCAL_ONLY,
    POLICY_ELIGIBLE,
    EXPLICIT_ONLY,
}

enum class MediaType {
    PHOTO,
    VIDEO,
    SCREENSHOT,
    UNKNOWN,
}

/**
 * Stable local media identity/metadata boundary.
 * MediaStore indexing and semantic analysis are intentionally deferred to G1+.
 */
data class MediaItem(
    val id: MediaId,
    val mediaStoreId: Long,
    val contentUri: String,
    val mediaType: MediaType,
    val capturedAtEpochMs: Long?,
    val addedAtEpochMs: Long,
    val modifiedGeneration: Long?,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val relativePath: String?,
)

/**
 * Generic event envelope. Sensitive/raw payloads remain behind payloadRef instead
 * of being embedded in the cross-feature envelope.
 */
data class SignalEvent(
    val id: EventId,
    val type: String,
    val occurredAtEpochMs: Long,
    val source: String,
    val payloadRef: String?,
    val sensitivity: SensitivityClass,
    val retention: RetentionClass,
    val syncPolicy: SyncPolicy,
)

/** Semantic layer over raw events/media. Construction and grouping arrive later. */
data class Episode(
    val id: EpisodeId,
    val title: String?,
    val summary: String?,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val mediaIds: List<MediaId> = emptyList(),
    val eventIds: List<EventId> = emptyList(),
    val evidenceRefs: List<String> = emptyList(),
    val confidence: Double? = null,
)

/** Snapshot of derived context; concrete context builders are deferred to later gates. */
data class ContextState(
    val id: ContextId,
    val type: String,
    val validFromEpochMs: Long,
    val validUntilEpochMs: Long?,
    val evidenceRefs: List<String> = emptyList(),
    val sensitivity: SensitivityClass = SensitivityClass.PRIVATE,
    val syncPolicy: SyncPolicy = SyncPolicy.LOCAL_ONLY,
)
