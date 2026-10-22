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
    val deviceId: String = "",
    val centerUrl: String = "",
    val cacheGeneration: Long = 0,
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
    @Volatile private var cacheGeneration = 0L
    private var analysisStatus = ""
    private var analysisFailures = 0
    private var analysisDegraded = false
    private var storageFailures = 0
    private fun refreshAnalysisStatus() = applyServerStatus(client.serverStatus())
    internal fun applyServerStatus(status: org.json.JSONObject) {
        storageFailures = status.optInt("storage_failures")
        analysisDegraded = status.optBoolean("analysis_degraded")
        val analysis = status.getJSONObject("analysis")
        analysisFailures = analysis.optInt("failed")
        analysisStatus = if (analysisDegraded) "AI 分析服务异常，请重试"
            else if (!analysis.optBoolean("enabled")) "AI 搜索尚未启用"
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
    @Volatile var interruptForRescan = false

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
        val availableServerIds = knownItems.mapNotNull { locals[it.id.value]?.mediaId }.toSet()
        return GallerySyncSnapshot(
            deviceId = client.deviceId,
            centerUrl = client.address ?: "",
            cacheGeneration = cacheGeneration,
            analysisStatus = analysisStatus,
            devices = store.allCatalog().groupBy { it.deviceId }.values.map { entries ->
                DeviceMediaProgress(entries.first().deviceName, entries.count { it.originalReady }, entries.size,
                    entries.sumOf { it.size ?: 0L })
            },
            paired = client.paired, healthy = healthy && !failurePrefs.getBoolean("system_fault", false) && locals.values.none { it.failed } && remoteFailures().isEmpty() && analysisFailures == 0 && storageFailures == 0 && !analysisDegraded, description = message,
            local = locals, remote = store.allCatalog().filter { media ->
                media.id !in availableServerIds
            },
            transferredBytes = bytes, totalBytes = total,
            pendingUploads = knownItems.count { locals[it.id.value]?.verified != true || storedVersions[it.id.value] != knownVersions[it.id.value] },
            failures = locals.values.count { it.failed } + remoteFailures().size + analysisFailures + storageFailures + (if (failurePrefs.getBoolean("system_fault", false)) 1 else 0) + (if (analysisDegraded && analysisFailures == 0) 1 else 0),
            remoteFailures = remoteFailures().map { it.substringAfter(':') }.toSet(),
            activeTransfer = active,
        )
    }

    suspend fun synchronize(items: List<MediaItem>, onUpdate: (GallerySyncSnapshot) -> Unit) =
        withContext(Dispatchers.IO) { transferMutex.withLock {
            if (!beginTransfer()) return@withContext
            try {
            var lastPublish = 0L
            fun update(healthy: Boolean, message: String, bytes: Long = 0L, total: Long = 0L,
                active: String = "", force: Boolean = false) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (force || now - lastPublish >= 250) {
                    lastPublish = now
                    onUpdate(snapshot(healthy, message, bytes, total, active))
                }
            }
            val job = currentCoroutineContext()[Job]
            knownItems = items
            knownVersions = items.associate { it.id.value to it.toString() }
            if (!client.paired) {
                update(false, "未配对 Signal Server", force = true)
                return@withContext
            }
            update(true, "正在检查云端目录", force = true)
            var failed = false
            var completedRound = true
            fun recordFault(error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                completedRound = false
                if (!isTransient(error)) failurePrefs.edit().putBoolean("system_fault", true).commit()
            }
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
                update(true, "目录已更新", force = true)
            } catch (error: Exception) {
                recordFault(error)
                update(isTransient(error), "连接暂不可用，将自动重试：" + (error.message ?: "未知错误"), force = true)
                return@withContext
            }

            var touched = 0
            for (media in items) {
                currentCoroutineContext().ensureActive()
                if (cancelled || interruptForRescan || (!foreground && !allowBackgroundImages)) { completedRound = false; break }
                val old = store.local(media.id.value)
                val version = media.toString()
                if (old?.verified == true && store.sourceVersion(media.id.value) == version && store.thumbnailReady(old.mediaId)) continue
                if (old?.failed == true) {
                    // Failed items remain visible in red until retry/reconnect; attempt again here.
                }
                val sourceChanged = old == null || store.sourceVersion(media.id.value) != version
                var mediaId = old?.mediaId
                try {
                    if (mediaId == null || sourceChanged) {
                        mediaId = client.register(media)
                        store.saveLocal(media.id.value, mediaId, verified = false)
                        store.saveSourceVersion(media.id.value, version)
                        touched++
                        update(true, "基础信息已同步，等待原件")

                    }
                    val confirmedId = mediaId ?: continue
                    if (sourceChanged || !store.thumbnailReady(confirmedId)) client.uploadThumbnail(media, confirmedId)
                    val allowed = media.mediaType != MediaType.VIDEO || (foreground && fastWifi())
                    if (!allowed) continue
                    val uploaded = client.uploadOriginal(media, confirmedId,
                        shouldContinue = { !cancelled && !interruptForRescan && job?.isActive != false &&
                            (foreground || (allowBackgroundImages && media.mediaType != MediaType.VIDEO)) &&
                            (media.mediaType != MediaType.VIDEO || fastWifi()) },
                        progress = { done, total ->
                            update(true, "正在传输原件", done, total, media.displayName ?: "照片")
                        })
                    if (uploaded && !cancelled && !interruptForRescan && (foreground || (allowBackgroundImages && media.mediaType != MediaType.VIDEO))) {
                        store.saveLocal(media.id.value, confirmedId, verified = true)
                        update(true, "原件已通过服务端校验")
                    }
                    if (!uploaded) completedRound = false
                    touched++
                } catch (error: kotlinx.coroutines.CancellationException) { throw error
                } catch (error: Exception) {
                    recordFault(error)
                    val terminal = !isTransient(error)
                    failed = failed || terminal
                    if (mediaId != null) store.saveLocal(media.id.value, mediaId, verified = false, failed = terminal)
                    update(!terminal, if (terminal) "同步异常：" + (error.message ?: "未知原因") else "网络暂不可用，等待重试", force = true)
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
                }.onFailure { recordFault(it); failed = failed || !isTransient(it) }
                runCatching { refreshAnalysisStatus() }.onFailure { recordFault(it); failed = failed || !isTransient(it) }
                val unresolved = failed || store.localSnapshot().values.any { it.failed } || remoteFailures().isNotEmpty() || analysisFailures > 0 || storageFailures > 0 || analysisDegraded
                if (completedRound && !unresolved && !interruptForRescan && job?.isActive != false)
                    failurePrefs.edit().putBoolean("system_fault", false).commit()
                update(!unresolved, if (unresolved) "有项目需要重试" else "同步系统正常", force = true)
            }
            } finally { endTransfer() }
        } }

    fun isTransient(error: Throwable): Boolean =
        error is java.net.SocketException || error is java.net.UnknownHostException ||
        error is java.io.InterruptedIOException || error is java.io.EOFException ||
        (error is SignalHttpError && (error.status == 408 || error.status == 429 || error.status >= 500 && error.status != 507))

    suspend fun pair(url: String, key: String, name: String): GallerySyncSnapshot = withContext(Dispatchers.IO) {
        // One lock order for foreground, worker and lazy downloads. Old-center requests
        // finish before credentials or protocol proofs are changed.
        transferMutex.withLock { downloadMutex.withLock {
            check(beginTransfer()) { "相册已关闭" }
            try {
                val previous = client.address
                client.enroll(url, key, name)
                if (previous != client.address) {
                    knownItems = emptyList()
                    knownVersions = emptyMap()
                    analysisStatus = ""
                    analysisFailures = 0
                    storageFailures = 0
                    analysisDegraded = false
                    cacheGeneration++
                }
                snapshot(true, "设备配对成功")
            } finally { endTransfer() }
        } }
    }

    suspend fun search(query: String): List<RemotePhoto> = withContext(Dispatchers.IO) { downloadMutex.withLock {
        val items = linkedMapOf<String, RemotePhoto>()
        var cursor = 0L
        while (true) {
            val page = client.catalog(cursor, query)
            page.items.filter { !it.deleted }.forEach { items[it.id] = it }
            if (page.items.size < 200 || page.cursor == cursor) break
            cursor = page.cursor
        }
        items.values.toList()
    } }

    suspend fun loadOriginal(media: RemotePhoto, progress: (Long, Long) -> Unit) =
        withContext(Dispatchers.IO) { downloadMutex.withLock {
            try { client.fetchOriginal(media, progress).also { remoteFailure(media.id, false); cacheGeneration++ } }
            catch (error: Exception) { if (!isTransient(error) && error !is kotlinx.coroutines.CancellationException) remoteFailure(media.id, true); throw error }
        } }

    suspend fun clearOriginalCache() = withContext(Dispatchers.IO) { downloadMutex.withLock {
        client.clearOriginalCache().also { cacheGeneration++ }
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
    suspend fun loadPreview(media: RemotePhoto) = withContext(Dispatchers.IO) { downloadMutex.withLock {
        try { client.fetchPreview(media).also { remoteFailure(media.id, false, "preview") } }
        catch (error: Exception) { if (media.thumbReady && !isTransient(error) && error !is kotlinx.coroutines.CancellationException) remoteFailure(media.id, true, "preview"); throw error }
    } }

    override fun close() = synchronized(lifecycleLock) {
        cancelled = true
        if (activeTransfers == 0) store.close()
    }
}
