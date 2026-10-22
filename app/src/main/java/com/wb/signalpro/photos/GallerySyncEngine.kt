package com.wb.signalpro.photos

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.wb.signalpro.data.model.MediaItem
import com.wb.signalpro.data.model.MediaType
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class DeviceMediaProgress(val name: String, val ready: Int, val total: Int, val bytes: Long)

internal data class GallerySyncSnapshot(
    val analysisStatus: String = "",
    val devices: List<DeviceMediaProgress> = emptyList(),
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
    companion object { private val transferMutex = Mutex(); private val downloadMutex = Mutex() }
    private val lifecycleLock = Any()
    private var activeTransfers = 0
    private fun beginTransfer(): Boolean = synchronized(lifecycleLock) {
        if (cancelled) false else { activeTransfers++; true }
    }
    private fun endTransfer() = synchronized(lifecycleLock) {
        activeTransfers--
        if (cancelled && activeTransfers == 0) store.close()
    }
    private val app = context.applicationContext
    val client = SignalMediaClient(app)
    private val store = GallerySyncStore(app)
    private val failurePrefs = app.getSharedPreferences("gallery-remote-failures", Context.MODE_PRIVATE)
    private var analysisStatus = ""
    private var analysisFailures = 0
    private fun refreshAnalysisStatus() {
        val analysis = client.serverStatus().getJSONObject("analysis")
        analysisFailures = analysis.optInt("failed")
        analysisStatus = if (!analysis.optBoolean("enabled")) "AI 搜索尚未启用"
            else "AI 索引：已处理 " + analysis.optInt("indexed") + " · 等待 " + analysis.optInt("pending") + " · 异常 " + analysisFailures
    }
    private var knownItems: List<MediaItem> = emptyList()
    private var knownVersions: Map<String, String> = emptyMap()
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
        val storedVersions = store.sourceVersions()
        val locals = store.localSnapshot().mapValues { (id, transfer) ->
            if (knownVersions[id] != null && knownVersions[id] != storedVersions[id]) transfer.copy(verified = false, failed = false)
            else transfer
        }
        return GallerySyncSnapshot(
            analysisStatus = analysisStatus,
            devices = store.allCatalog().groupBy { it.deviceId }.values.map { entries ->
                DeviceMediaProgress(entries.first().deviceName, entries.count { it.originalReady }, entries.size,
                    entries.sumOf { it.size ?: 0L })
            },
            paired = client.paired, healthy = healthy && locals.values.none { it.failed } && remoteFailures().isEmpty() && analysisFailures == 0, description = message,
            local = locals, remote = store.remote(client.deviceId),
            transferredBytes = bytes, totalBytes = total,
            pendingUploads = knownItems.count { locals[it.id.value]?.verified != true || storedVersions[it.id.value] != knownVersions[it.id.value] },
            failures = locals.values.count { it.failed } + remoteFailures().size + analysisFailures,
            remoteFailures = remoteFailures().map { it.substringAfter(':') }.toSet(),
            activeTransfer = active,
        )
    }

    suspend fun synchronize(items: List<MediaItem>, onUpdate: (GallerySyncSnapshot) -> Unit) =
        withContext(Dispatchers.IO) { transferMutex.withLock {
            if (!beginTransfer()) return@withContext
            try {
            val job = currentCoroutineContext()[Job]
            knownItems = items
            knownVersions = items.associate { it.id.value to it.toString() }
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
                refreshAnalysisStatus()
                onUpdate(snapshot(true, "目录已更新"))
            } catch (error: Exception) {
                onUpdate(snapshot(isTransient(error), "连接暂不可用，将自动重试：" + (error.message ?: "未知错误")))
                return@withContext
            }

            var touched = 0
            for (media in items) {
                currentCoroutineContext().ensureActive()
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
                        shouldContinue = { !cancelled && job?.isActive != false &&
                            (foreground || (allowBackgroundImages && media.mediaType != MediaType.VIDEO)) &&
                            (media.mediaType != MediaType.VIDEO || fastWifi()) },
                        progress = { done, total ->
                            onUpdate(snapshot(true, "正在传输原件", done, total, media.displayName ?: "照片"))
                        })
                    if (uploaded && !cancelled && (foreground || (allowBackgroundImages && media.mediaType != MediaType.VIDEO))) {
                        store.saveLocal(media.id.value, confirmedId, verified = true)
                        onUpdate(snapshot(true, "原件已通过服务端校验"))
                    }
                    touched++
                } catch (error: kotlinx.coroutines.CancellationException) { throw error
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
                runCatching { refreshAnalysisStatus() }
                val unresolved = failed || store.localSnapshot().values.any { it.failed } || remoteFailures().isNotEmpty() || analysisFailures > 0
                onUpdate(snapshot(!unresolved, if (unresolved) "有项目需要重试" else "同步系统正常"))
            }
            } finally { endTransfer() }
        } }

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
        withContext(Dispatchers.IO) { downloadMutex.withLock {
            try { client.fetchOriginal(media, progress).also { remoteFailure(media.id, false) } }
            catch (error: Exception) { remoteFailure(media.id, true); throw error }
        } }

    suspend fun retryFailures() = withContext(Dispatchers.IO) {
        if (!client.paired) return@withContext
        val catalog = store.allCatalog().associateBy { it.id }
        for (failure in remoteFailures()) {
            val id = failure.substringAfter(':')
            val media = catalog[id] ?: continue
            if (failure.startsWith("preview:")) {
                if (media.thumbReady) loadPreview(media) else remoteFailure(id, false, "preview")
            } else {
                if (media.originalReady) loadOriginal(media) { _, _ -> } else remoteFailure(id, false)
            }
        }
        val enabled = client.serverStatus().getJSONObject("analysis").optBoolean("enabled")
        if (enabled) client.retryAnalysis()
        refreshAnalysisStatus()
    }

    fun cachedPreview(media: RemotePhoto) = client.preview(media.id)
    fun cachedOriginal(media: RemotePhoto) = client.original(media.id)
    suspend fun loadPreview(media: RemotePhoto) = withContext(Dispatchers.IO) {
        try { client.fetchPreview(media).also { remoteFailure(media.id, false, "preview") } }
        catch (error: Exception) { if (media.thumbReady) remoteFailure(media.id, true, "preview"); throw error }
    }

    override fun close() = synchronized(lifecycleLock) {
        cancelled = true
        if (activeTransfers == 0) store.close()
    }
}
