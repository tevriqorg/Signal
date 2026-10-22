package com.wb.signalpro.photos

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Background is opportunistic: WorkManager is OS-scheduled, never a permanent service.
 * It may transfer normal photos; video originals are FOREGROUND ONLY.
 */
internal class GalleryBackgroundWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val engine = GallerySyncEngine(applicationContext)
        val coordinator = MediaIndexCoordinator(applicationContext)
        try {
            if (!engine.client.paired) return Result.success()
            val access = MediaPermissionPolicy.current(applicationContext)
            if (access == MediaAccessLevel.DENIED) return Result.success()
            coordinator.reconcile()
            val local = coordinator.indexedMedia(limit = 2_000)
            engine.allowBackgroundImages = true
            engine.synchronize(local) { /* No UI updates in background. */ }
            return Result.success()
        } catch (_: Exception) {
            return Result.retry()
        } finally {
            engine.close()
            coordinator.close()
        }
    }
}

internal object GalleryBackgroundScheduler {
    fun schedule(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<GalleryBackgroundWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "signal-gallery-sync-v1", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
