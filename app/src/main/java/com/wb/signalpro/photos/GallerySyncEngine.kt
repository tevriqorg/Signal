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
)

/** Foreground opportunity sync. The service is the only server; no AIBOX adapter exists. */
internal class GallerySyncEngine(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    val client = SignalMediaClient(app)
    private val store = GallerySyncStore(app)
    @Volatile var foreground = false
    @Volatile var cancelled = false

    private fun fastWifi(): Boolean {
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
            paired = client.paired, healthy = healthy, description = message,
            local = locals, remote = store.remote(client.deviceId),
            transferredBytes = bytes, totalBytes = total,
            pendingUploads = locals.values.count { !it.verified },
            failures = locals.values.count { it.failed },
            activeTransfer = active,
        )
    }

    suspend fun synchronize(items: List<MediaItem>, onUpdate: (GallerySyncSnapshot) -> Unit) =
        withContext(Dispatchers.IO) {
            if (!client.paired) {
                onUpdate(snapshot(false, "未配对 Signal Server"))
                return@withContext
            }
            onUpdate(snapshot(false, "正在检查云端目录"))
            var failed = false
            try {
                // Fetch deltas in pages. The cursor and remote entries persist over process death.
                repeat(20) {
                    if (cancelled) return@withContext
                    val start = store.cursor()
                    val result = client.catalog(start)
                    store.ingest(result.items, result.cursor)
                    if (result.items.size < 200 || result.cursor == start) return@repeat
                }
                onUpdate(snapshot(true, "目录已更新"))
            } catch (error: Exception) {
                onUpdate(snapshot(false, "连接失败：" + (error.message ?: "未知错误")))
                return@withContext
            }

            var touched = 0
            for (media in items) {
                if (cancelled || !foreground || touched >= 100) break
                val old = store.local(media.id.value)
                if (old?.verified == true) continue
                if (old?.failed == true) {
                    // Failed items remain visible in red until retry/reconnect; attempt again here.
                }
                var mediaId = old?.mediaId
                try {
                    if (mediaId == null) {
                        mediaId = client.register(media)
                        store.saveLocal(media.id.value, mediaId, verified = false)
                        touched++
                        onUpdate(snapshot(true, "基础信息已同步，等待原件"))
                        runCatching { client.uploadThumbnail(media, mediaId) }
                    }
                    val allowed = media.mediaType != MediaType.VIDEO || fastWifi()
                    if (!allowed) continue
                    val confirmedId = mediaId ?: continue
                    client.uploadOriginal(media, confirmedId,
                        shouldContinue = { foreground && !cancelled },
                        progress = { done, total ->
                            onUpdate(snapshot(true, "正在传输原件", done, total, media.displayName ?: "照片"))
                        })
                    if (!cancelled && foreground) {
                        store.saveLocal(media.id.value, confirmedId, verified = true)
                        onUpdate(snapshot(true, "原件已通过服务端校验"))
                    }
                    touched++
                } catch (error: Exception) {
                    failed = true
                    if (mediaId != null) store.saveLocal(media.id.value, mediaId, verified = false, failed = true)
                    onUpdate(snapshot(false, "同步异常：" + (error.message ?: "未知原因")))
                }
            }
            if (!cancelled) {
                runCatching {
                    repeat(20) {
                        val start = store.cursor()
                        val result = client.catalog(start)
                        store.ingest(result.items, result.cursor)
                        if (result.items.size < 200 || result.cursor == start) return@repeat
                    }
                }.onFailure { failed = true }
                onUpdate(snapshot(!failed && store.localSnapshot().values.none { it.failed },
                    if (failed) "有项目需要重试" else "同步系统正常"))
            }
        }

    suspend fun pair(url: String, key: String, name: String): GallerySyncSnapshot = withContext(Dispatchers.IO) {
        client.enroll(url, key, name)
        snapshot(true, "设备配对成功")
    }

    suspend fun search(query: String): List<RemotePhoto> = withContext(Dispatchers.IO) {
        client.catalog(0, query).items
    }

    suspend fun loadOriginal(media: RemotePhoto, progress: (Long, Long) -> Unit) =
        withContext(Dispatchers.IO) { client.fetchOriginal(media, progress) }

    fun cachedPreview(media: RemotePhoto) = client.preview(media.id)
    fun cachedOriginal(media: RemotePhoto) = client.original(media.id)
    suspend fun loadPreview(media: RemotePhoto) = withContext(Dispatchers.IO) { client.fetchPreview(media) }

    override fun close() { cancelled = true; store.close() }
}
