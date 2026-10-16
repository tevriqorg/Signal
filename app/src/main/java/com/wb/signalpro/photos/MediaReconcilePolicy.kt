package com.wb.signalpro.photos

enum class MediaAccessLevel {
    FULL,
    PARTIAL,
    DENIED,
}

data class MediaCheckpoint(
    val version: String?,
    val generation: Long?,
    val accessLevel: MediaAccessLevel,
    val lastReconcileAtEpochMs: Long,
)

data class MediaStoreSnapshot(
    val version: String?,
    val generation: Long?,
)

enum class ReconcileMode {
    FULL,
    INCREMENTAL,
    SKIP,
}

/** Pure decision policy so generation/version behavior is unit-testable. */
object MediaReconcilePolicy {
    fun choose(
        sdkInt: Int,
        checkpoint: MediaCheckpoint?,
        current: MediaStoreSnapshot,
        accessLevel: MediaAccessLevel,
        forceFull: Boolean = false,
    ): ReconcileMode {
        if (accessLevel == MediaAccessLevel.DENIED) return ReconcileMode.SKIP
        if (forceFull || checkpoint == null) return ReconcileMode.FULL
        if (checkpoint.accessLevel != accessLevel) return ReconcileMode.FULL
        if (sdkInt < 30) return ReconcileMode.FULL
        if (checkpoint.version != current.version) return ReconcileMode.FULL

        val previousGeneration = checkpoint.generation ?: return ReconcileMode.FULL
        val currentGeneration = current.generation ?: return ReconcileMode.FULL
        if (currentGeneration < previousGeneration) return ReconcileMode.FULL
        return if (currentGeneration == previousGeneration) {
            ReconcileMode.SKIP
        } else {
            ReconcileMode.INCREMENTAL
        }
    }
}
