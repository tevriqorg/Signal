package com.wb.signalpro.photos

import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.wb.signalpro.data.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

internal data class MediaReconcileReport(
    val accessLevel: MediaAccessLevel,
    val indexedRows: Int,
    val modes: Map<String, ReconcileMode>,
    val completedAtEpochMs: Long,
)

internal class MediaIndexCoordinator(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val source = MediaStoreSource(appContext)
    private val store = MediaIndexStore(appContext)

    suspend fun reconcile(forceFull: Boolean = false): MediaReconcileReport = withContext(Dispatchers.IO) {
        val access = MediaPermissionPolicy.current(appContext)
        if (access == MediaAccessLevel.DENIED) {
            return@withContext MediaReconcileReport(access, 0, emptyMap(), System.currentTimeMillis())
        }

        var indexed = 0
        val modes = linkedMapOf<String, ReconcileMode>()
        source.externalVolumes().sorted().forEach { volume ->
            val snapshot = source.snapshot(volume)
            val checkpoint = store.checkpoint(volume)
            val mode = MediaReconcilePolicy.choose(
                sdkInt = Build.VERSION.SDK_INT,
                checkpoint = checkpoint,
                current = snapshot,
                accessLevel = access,
                forceFull = forceFull,
            )
            modes[volume] = mode
            when (mode) {
                ReconcileMode.FULL -> {
                    val rows = source.query(volume, null)
                    store.replaceVolumeSnapshot(volume, rows, UUID.randomUUID().toString())
                    indexed += rows.size
                }
                ReconcileMode.INCREMENTAL -> {
                    val rows = source.query(volume, checkpoint?.generation)
                    store.upsertScanned(rows)
                    indexed += rows.size
                }
                ReconcileMode.SKIP -> Unit
            }
            if (mode != ReconcileMode.FULL) store.pruneUnavailable(volume, source.visibleIds(volume))
            if (mode != ReconcileMode.SKIP || checkpoint == null) {
                store.saveCheckpoint(
                    volume,
                    MediaCheckpoint(
                        version = snapshot.version,
                        generation = snapshot.generation,
                        accessLevel = access,
                        lastReconcileAtEpochMs = System.currentTimeMillis(),
                    ),
                )
            }
        }
        MediaReconcileReport(access, indexed, modes, System.currentTimeMillis())
    }

    suspend fun indexedMedia(limit: Int = Int.MAX_VALUE): List<MediaItem> = withContext(Dispatchers.IO) {
        val result = ArrayList<MediaItem>()
        var offset = 0
        while (offset < limit) {
            val page = store.list(limit = minOf(1000, limit - offset), offset = offset)
            result.addAll(page)
            offset += page.size
            if (page.size < 1000) break
        }
        result
    }

    fun newObserver(onChange: () -> Unit): MediaChangeObserver = MediaChangeObserver(appContext, onChange)

    override fun close() {
        store.close()
    }
}

internal class MediaChangeObserver(
    private val context: Context,
    private val onChange: () -> Unit,
) : ContentObserver(Handler(Looper.getMainLooper())) {
    private var registered = false

    fun register() {
        if (registered) return
        context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, this)
        context.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, this)
        registered = true
    }

    fun unregister() {
        if (!registered) return
        runCatching { context.contentResolver.unregisterContentObserver(this) }
        registered = false
    }

    override fun onChange(selfChange: Boolean) {
        super.onChange(selfChange)
        onChange()
    }
}
