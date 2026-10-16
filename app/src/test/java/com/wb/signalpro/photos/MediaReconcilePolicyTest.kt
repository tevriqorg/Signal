package com.wb.signalpro.photos

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaReconcilePolicyTest {
    private val full = MediaAccessLevel.FULL
    private val checkpoint = MediaCheckpoint("v1", 10L, full, 1L)

    @Test fun firstRunIsFull() {
        assertEquals(ReconcileMode.FULL, MediaReconcilePolicy.choose(35, null, MediaStoreSnapshot("v1", 10L), full))
    }

    @Test fun unchangedGenerationSkips() {
        assertEquals(ReconcileMode.SKIP, MediaReconcilePolicy.choose(35, checkpoint, MediaStoreSnapshot("v1", 10L), full))
    }

    @Test fun higherGenerationIsIncremental() {
        assertEquals(ReconcileMode.INCREMENTAL, MediaReconcilePolicy.choose(35, checkpoint, MediaStoreSnapshot("v1", 11L), full))
    }

    @Test fun versionChangeForcesFull() {
        assertEquals(ReconcileMode.FULL, MediaReconcilePolicy.choose(35, checkpoint, MediaStoreSnapshot("v2", 11L), full))
    }

    @Test fun permissionModeChangeForcesFull() {
        assertEquals(ReconcileMode.FULL, MediaReconcilePolicy.choose(35, checkpoint, MediaStoreSnapshot("v1", 11L), MediaAccessLevel.PARTIAL))
    }

    @Test fun generationRollbackForcesFull() {
        assertEquals(ReconcileMode.FULL, MediaReconcilePolicy.choose(35, checkpoint, MediaStoreSnapshot("v1", 9L), full))
    }

    @Test fun deniedAccessSkips() {
        assertEquals(ReconcileMode.SKIP, MediaReconcilePolicy.choose(35, checkpoint, MediaStoreSnapshot("v1", 11L), MediaAccessLevel.DENIED))
    }

    @Test fun android14SelectedOnlyIsPartial() {
        assertEquals(
            MediaAccessLevel.PARTIAL,
            MediaPermissionPolicy.resolve(34, images = false, videos = false, selected = true, legacy = false),
        )
    }
}
