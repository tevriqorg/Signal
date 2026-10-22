package com.wb.signalpro.photos

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.wb.signalpro.data.model.MediaItem
import com.wb.signalpro.data.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class GallerySyncSnapshot(
    val paired: Boolean = false,
    val healthy: Boolean = false,
    val description: String = "尚未连接 Signal Server",
    val remote: List<RemotePhoto> = emptyList(),
    val local: Map<String, LocalTransfer> = emptyMap(),
    val transferredBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val pendingUploads: Int = 0,
    val failures: Int = 0,
    val activeTransfer: String = "",
    val remoteFailures: Set<String> = emptySet(),
)

/** Foreground opportunity sync. The service is the only server; no AIBOX adapter exists. */
internal class GallerySyncEngine(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    val client = SignalMediaClient(app)
    private val store = GallerySyncStore(app)
    private val failurePrefs = app.getSharedPreferences("gallery-remote-failures", Context.MODE_PRIVATE)
    private var knownItems: List<MediaItem> = emptyList()
    private fun remoteFailures(): Set<String> = failurePrefs.getStringSet("ids", emptySet())!!.toSet()
    @Synchronized private fun remoteFailure(id: String, failed: Boolean, kind: String = "original") {
        val ids = remoteFailures().toMutableSet()
        val key = "$kind:$id"
        if (failed) ids.add(key) else ids.remove(key)
        failurePrefs.edit().putStringSet("ids", ids).commit()
    }
    @Volatile var foreground = false
    @Volatile var allowBackgroundImages = false
    @Volatile var cancelled = false

    internal fun fastWifi(): Boolean {
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun snapshot(healthy: Boolean, message: String, bytes: Long = 0, total: Long = 0, active: String = ""): GallerySyncSnapshot {
        val locals = store.localSnapshot()
        return GallerySyncSnapshot(
            paired = client.paired, healthy = healthy && remoteFailures().isEmpty(), description = message,
            local = locals, remote = store.remote(client.deviceId),
            transferredBytes = bytes, totalBytes = total,
            pendingUploads = knownItems.count { locals[it.id.value]?.verified != true || store.sourceVersion(it.id.value) != it.toString() },
            failures = locals.values.count { it.failed } + remoteFailures().size,
            remoteFailures = remoteFailures().map { it.substringAfter(':') }.toSet(),
            activeTransfer = active,
        )
    }

    suspend fun synchronize(items: List<MediaItem>, onUpdate: (GallerySyncSnapshot) -> Unit) =
        withContext(Dispatchers.IO) {
            knownItems = items
            if (!client.paired) {
                onUpdate(snapshot(false, "未配对 Signal Server"))
                return@withContext
            }
            onUpdate(snapshot(false, "正在检查云端目录"))
            var failed = false
            try {
                // Fetch deltas in pages. The cursor and remote entries persist over process death.
                while (true) {
                    if (cancelled) return@withContext
                    val start = store.cursor()
                    val result = client.catalog(start)
                    store.ingest(result.items, result.cursor)
                    if (result.items.size < 200 || result.cursor == start) break
                }
                onUpdate(snapshot(true, "目录已更新"))
            } catch (error: Exception) {
                onUpdate(snapshot(isTransient(error), "连接暂不可用，将自动重试：" + (error.message ?: "未知错误")))
                return@withContext
            }

            var touched = 0
            for (media in items) {
                if (cancelled || (!foreground && !allowBackgroundImages)) break
                val old = store.local(media.id.value)
                val version = media.toString()
                if (old?.verified == true && store.sourceVersion(media.id.value) == version && store.thumbnailReady(old.mediaId)) continue
                if (old?.failed == true) {
                    // Failed items remain visible in red until retry/reconnect; attempt again here.
                }
                var mediaId = old?.mediaId
                try {
                    if (mediaId == null || store.sourceVersion(media.id.value) != version) {
                        mediaId = client.register(media)
                        store.saveLocal(media.id.value, mediaId, verified = false)
                        store.saveSourceVersion(media.id.value, version)
                        touched++
                        onUpdate(snapshot(true, "基础信息已同步，等待原件"))

                    }
                    val confirmedId = mediaId ?: continue
                    if (!store.thumbnailReady(confirmedId)) client.uploadThumbnail(media, confirmedId)
                    val allowed = media.mediaType != MediaType.VIDEO || (foreground && fastWifi())
                    if (!allowed) continue
                    val uploaded = client.uploadOriginal(media, confirmedId,
                        shouldContinue = { !cancelled && (foreground || (allowBackgroundImages && media.mediaType != MediaType.VIDEO)) },
                        progress = { done, total ->
                            onUpdate(snapshot(true, "正在传输原件", done, total, media.displayName ?: "照片"))
                        })
                    if (uploaded && !cancelled && (foreground || (allowBackgroundImages && media.mediaType != MediaType.VIDEO))) {
                        store.saveLocal(media.id.value, confirmedId, verified = true)
                        onUpdate(snapshot(true, "原件已通过服务端校验"))
                    }
                    touched++
                } catch (error: Exception) {
                    val terminal = !isTransient(error)
                    failed = failed || terminal
                    if (mediaId != null) store.saveLocal(media.id.value, mediaId, verified = false, failed = terminal)
                    onUpdate(snapshot(!terminal, if (terminal) "同步异常：" + (error.message ?: "未知原因") else "网络暂不可用，等待重试"))
                }
            }
            if (!cancelled) {
                runCatching {
                    while (true) {
                        val start = store.cursor()
                        val result = client.catalog(start)
                        store.ingest(result.items, result.cursor)
                        if (result.items.size < 200 || result.cursor == start) break
                    }
                }.onFailure { failed = true }
                onUpdate(snapshot(!failed && store.localSnapshot().values.none { it.failed },
                    if (failed) "有项目需要重试" else "同步系统正常"))
            }
        }

    private fun isTransient(error: Exception): Boolean = error is java.io.IOException ||
        (error is SignalHttpError && (error.status == 408 || error.status == 429 || error.status >= 500 && error.status != 507))

    suspend fun pair(url: String, key: String, name: String): GallerySyncSnapshot = withContext(Dispatchers.IO) {
        client.enroll(url, key, name)
        snapshot(true, "设备配对成功")
    }

    suspend fun search(query: String): List<RemotePhoto> = withContext(Dispatchers.IO) {
        val items = linkedMapOf<String, RemotePhoto>()
        var cursor = 0L
        while (true) {
            val page = client.catalog(cursor, query)
            page.items.filter { !it.deleted }.forEach { items[it.id] = it }
            if (page.items.size < 200 || page.cursor == cursor) break
            cursor = page.cursor
        }
        items.values.toList()
    }

    suspend fun loadOriginal(media: RemotePhoto, progress: (Long, Long) -> Unit) =
        withContext(Dispatchers.IO) {
            try { client.fetchOriginal(media, progress).also { remoteFailure(media.id, false) } }
            catch (error: Exception) { remoteFailure(media.id, true); throw error }
        }

    fun cachedPreview(media: RemotePhoto) = client.preview(media.id)
    fun cachedOriginal(media: RemotePhoto) = client.original(media.id)
    suspend fun loadPreview(media: RemotePhoto) = withContext(Dispatchers.IO) {
        try { client.fetchPreview(media).also { remoteFailure(media.id, false, "preview") } }
        catch (error: Exception) { if (media.thumbReady) remoteFailure(media.id, true, "preview"); throw error }
    }

    override fun close() { cancelled = true; store.close() }
}
