package com.wb.signalpro.photos

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
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
            val local = coordinator.indexedMedia()
            engine.allowBackgroundImages = true
            var unhealthy = false
            engine.synchronize(local) { unhealthy = !it.healthy }
            if (engine.fastWifi() && local.any { it.mediaType == com.wb.signalpro.data.model.MediaType.VIDEO &&
                    engine.snapshot(true, "").local[it.id.value]?.verified != true }) {
                GalleryVideoNotification.offer(applicationContext)
            }
            return if (unhealthy) Result.retry() else Result.success()
        } catch (_: Exception) {
            return Result.retry()
        } finally {
            engine.close()
            coordinator.close()
        }
    }
}

internal object GalleryBackgroundScheduler {
    fun catchUp(context: Context): androidx.work.Operation {
        val request = OneTimeWorkRequestBuilder<GalleryBackgroundWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        return WorkManager.getInstance(context).enqueueUniqueWork("signal-gallery-catchup", ExistingWorkPolicy.KEEP, request)
    }
    fun schedule(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<GalleryBackgroundWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "signal-gallery-sync-v1", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

/** Low-frequency opt-in invitation; never brings the activity to foreground automatically. */
internal object GalleryVideoNotification {
    fun offer(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
                android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return false
        val prefs = context.getSharedPreferences("gallery-video-notice", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last", 0) < TimeUnit.HOURS.toMillis(24)) return false
        val channel = "signal-video-opportunity"
        manager.createNotificationChannel(android.app.NotificationChannel(channel,
            "视频同步机会", android.app.NotificationManager.IMPORTANCE_LOW))
        val intent = android.content.Intent(context, com.wb.signalpro.GalleryActivity::class.java)
        val action = android.app.PendingIntent.getActivity(context, 14, intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(14, android.app.Notification.Builder(context, channel)
            .setSmallIcon(com.wb.signalpro.R.drawable.ic_notification)
            .setContentTitle("现在适合同步视频")
            .setContentText("点此打开相册，并保持前台以同步待传视频。")
            .setContentIntent(action).setAutoCancel(true).setOnlyAlertOnce(true).build())
        prefs.edit().putLong("last", now).commit()
        return true
    }
}
