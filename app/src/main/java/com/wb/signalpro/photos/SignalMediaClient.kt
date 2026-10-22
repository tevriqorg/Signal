package com.wb.signalpro.photos

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Size
import com.wb.signalpro.data.model.MediaItem
import com.wb.signalpro.data.model.MediaType
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

internal data class SignalPairing(val serverUrl: String, val deviceId: String, val deviceToken: String)
internal class SignalHttpError(val status: Int, val response: JSONObject) :
    Exception("HTTP " + status + ": " + response.optString("error"))

/** Persist the device token encrypted using the Android Keystore, not as plaintext settings. */
internal class SignalPairingStore(private val context: Context) {
    companion object { private val identityLock = Any() }
    private val prefs = context.getSharedPreferences("signal-gallery-pairing", Context.MODE_PRIVATE)
    private val alias = "signal-gallery-token-v1"

    fun deviceId(): String = synchronized(identityLock) {
        prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            check(prefs.edit().putString("device_id", it).commit()) { "无法保存设备身份" }
        }
    }

    private fun key(): javax.crypto.SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? javax.crypto.SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    fun read(): SignalPairing? {
        val url = prefs.getString("server_url", null) ?: return null
        val encrypted = prefs.getString("device_token", null) ?: return null
        val bytes = runCatching { Base64.decode(encrypted, Base64.NO_WRAP) }.getOrNull() ?: return null
        if (bytes.size < 16) return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            SignalPairing(url, deviceId(), String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        }.getOrNull()
    }

    fun lastServerUrl(): String? = prefs.getString("last_server_url", null) ?: prefs.getString("server_url", null)

    fun save(url: String, token: String) {
        fun encryptionCipher() = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val cipher = try { encryptionCipher() } catch (_: java.security.GeneralSecurityException) {
            // Explicit credential recovery may follow a permanently invalidated/removed key.
            // The server already revoked the old token; generate a fresh wrapping key.
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            encryptionCipher()
        }
        val encrypted = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("server_url", url.trimEnd('/')).putString("last_server_url", url.trimEnd('/'))
            .putString("device_token", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) { "无法保存配对凭据" }
    }

    fun clear() { check(prefs.edit().remove("server_url").remove("device_token").commit()) }
}

internal class SignalMediaClient(private val context: Context) {
    private val credentials = SignalPairingStore(context)
    val deviceId: String get() = credentials.deviceId()
    val paired: Boolean get() = credentials.read() != null
    val address: String? get() = credentials.read()?.serverUrl

    private fun checkUrl(raw: String): String {
        val parsed = Uri.parse(raw.trim().trimEnd('/'))
        val host = parsed.host ?: throw IllegalArgumentException("请填写有效服务器地址")
        val localDebug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            host in listOf("127.0.0.1", "localhost", "10.0.2.2")
        require(parsed.scheme == "https" || (parsed.scheme == "http" && localDebug)) {
            "正式环境必须使用 HTTPS；HTTP 仅限模拟器本地调试"
        }
        require(parsed.query.isNullOrEmpty() && parsed.fragment.isNullOrEmpty())
        require(!raw.contains("@")) { "服务器地址不得包含账号或口令" }
        return raw.trim().trimEnd('/')
    }

    fun enroll(rawUrl: String, enrollmentKey: String, displayName: String) {
        val url = checkUrl(rawUrl)
        require(enrollmentKey.length >= 16 && displayName.isNotBlank())
        val payload = JSONObject().put("device_id", deviceId)
            .put("device_name", displayName.trim().take(80)).put("platform", "android").put("recover_existing", true)
        val result = JSONObject(String(request(url, "POST", "/v1/devices",
            payload.toString().toByteArray(), null, mapOf("X-Enrollment-Key" to enrollmentKey))))
        val previous = credentials.lastServerUrl()
        if (previous != url) {
            // Callers serialize center changes with all sync/download actors. Clear derived
            // state before publishing credentials, so process death cannot retain old proofs.
            GallerySyncStore(context).use { it.resetCenter() }
            for (name in listOf("gallery-originals", "gallery-previews")) {
                val folder = File(context.filesDir, name)
                check(!folder.exists() || folder.deleteRecursively()) { "无法清理旧中心缓存" }
            }
            check(context.getSharedPreferences("gallery-remote-failures", Context.MODE_PRIVATE).edit().clear().commit())
        }
        credentials.save(url, result.getString("device_token"))
    }

    internal fun checkMediaCenter(media: RemotePhoto) {
        if (media.centerUrl.isNotBlank() && media.centerUrl != address)
            throw kotlinx.coroutines.CancellationException("媒体所属中心已更改")
    }

    fun unpair() = credentials.clear()
    fun serverStatus(): JSONObject {
        val credential = pair()
        return JSONObject(String(request(credential.serverUrl, "GET", "/v1/status", null, credential.deviceToken)))
    }
    fun retryAnalysis() {
        val credential = pair()
        request(credential.serverUrl, "POST", "/v1/analysis/retry", null, credential.deviceToken)
    }

    fun catalog(since: Long, query: String = ""): SignalCatalog {
        val credential = pair()
        val path = if (query.isBlank()) "/v1/media?since=$since&limit=200"
            else "/v1/search?since=$since&limit=200&q=" + java.net.URLEncoder.encode(query, "UTF-8")
        val result = JSONObject(String(request(credential.serverUrl, "GET", path, null, credential.deviceToken)))
        val items = result.getJSONArray("items")
        return SignalCatalog((0 until items.length()).map { RemotePhoto.parse(items.getJSONObject(it)).copy(centerUrl = credential.serverUrl) },
            result.getLong("next_cursor"))
    }

    fun register(item: MediaItem): String {
        val credential = pair()
        val kind = when (item.mediaType) {
            MediaType.VIDEO -> "video"
            MediaType.SCREENSHOT -> "screenshot"
            else -> "photo"
        }
        val data = JSONObject().put("local_id", item.id.value).put("source_version", sha(item.toString().toByteArray()))
            .put("media_type", kind)
            .put("mime_type", item.mimeType ?: if (kind == "video") "video/mp4" else "image/jpeg")
            .put("captured_at", item.capturedAtEpochMs ?: item.addedAtEpochMs)
            .put("width", item.width).put("height", item.height)
            .put("duration_ms", item.durationMs).put("filename", item.displayName ?: "")
        runCatching { context.contentResolver.openAssetFileDescriptor(Uri.parse(item.contentUri), "r")?.use { it.length } }
            .getOrNull()?.takeIf { it >= 0 }?.let { data.put("source_size", it) }
        val result = JSONObject(String(request(credential.serverUrl, "POST", "/v1/media",
            data.toString().toByteArray(), credential.deviceToken)))
        return result.getString("media_id")
    }

    fun uploadThumbnail(item: MediaItem, mediaId: String) {
        val credential = pair()
        val cancellation = android.os.CancellationSignal()
        val timer = java.util.Timer("gallery-thumbnail-timeout", true)
        timer.schedule(object : java.util.TimerTask() { override fun run() { cancellation.cancel() } }, 30_000)
        val preview = try {
            context.contentResolver.loadThumbnail(Uri.parse(item.contentUri), Size(320, 320), cancellation)
        } finally { timer.cancel() }
        val output = ByteArrayOutputStream()
        check(preview.compress(Bitmap.CompressFormat.JPEG, 75, output))
        val bytes = output.toByteArray()
        request(credential.serverUrl, "PUT", "/v1/media/$mediaId/thumbnail", bytes, credential.deviceToken,
            mapOf("X-SHA256" to sha(bytes), "X-Source-Version" to sha(item.toString().toByteArray())))
    }

    fun uploadOriginal(item: MediaItem, mediaId: String, shouldContinue: () -> Boolean, progress: (Long, Long) -> Unit): Boolean {
        val credential = pair()
        val uri = Uri.parse(item.contentUri)
        val digest = MessageDigest.getInstance("SHA-256")
        var length = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            val block = ByteArray(128 * 1024)
            while (true) {
                if (!shouldContinue()) return false
                val count = input.read(block)
                if (count == -1) break
                digest.update(block, 0, count)
                length += count
            }
        } ?: throw IllegalStateException("原件不可读取")
        require(length > 0L) { "原件为空" }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val payload = JSONObject().put("media_id", mediaId).put("size", length).put("sha256", hash)
            .put("source_version", sha(item.toString().toByteArray()))
        val session = JSONObject(String(request(credential.serverUrl, "POST", "/v1/uploads",
            payload.toString().toByteArray(), credential.deviceToken)))
        if (session.optBoolean("complete")) { progress(length, length); return true }
        val uploadId = session.getString("upload_id")
        var offset = session.getLong("offset")
        context.contentResolver.openInputStream(uri)?.use { input ->
            skipExactly(input, offset)
            val block = ByteArray(256 * 1024)
            while (offset < length) {
                if (!shouldContinue()) return false
                val read = input.read(block, 0, minOf(block.size.toLong(), length - offset).toInt())
                if (read < 1) throw IllegalStateException("原件在上传期间发生变化")
                val response = JSONObject(String(request(credential.serverUrl, "PUT", "/v1/uploads/$uploadId",
                    block.copyOf(read), credential.deviceToken, mapOf("X-Offset" to offset.toString()))))
                val next = response.getLong("offset")
                check(next == offset + read && next <= length) { "服务器上传进度无效" }
                offset = next
                if (offset == length) check(response.optBoolean("complete")) { "服务器尚未确认原件" }
                progress(offset, length)
            }
        } ?: throw IllegalStateException("原件不可读取")
        return true
    }

    fun preview(mediaId: String): File? =
        File(File(context.filesDir, "gallery-previews"), "$mediaId.jpg").takeIf { it.isFile && it.length() > 0 }

    fun original(mediaId: String): File? =
        File(File(context.filesDir, "gallery-originals"), mediaId).takeIf { it.isFile && it.length() > 0 }

    fun fetchPreview(media: RemotePhoto): File {
        checkMediaCenter(media)
        preview(media.id)?.let {
            val revision = File(it.parentFile, media.id + ".revision")
            val digest = File(it.parentFile, media.id + ".sha256")
            if (revision.isFile && revision.readText() == media.revision.toString() &&
                digest.isFile && digest.readText() == fileSha(it)) return it
            it.delete()
            revision.delete()
            digest.delete()
        }
        val credential = pair()
        val bytes = request(credential.serverUrl, "GET", "/v1/media/" + media.id + "/thumbnail",
            null, credential.deviceToken)
        require(bytes.size in 1..(3 * 1024 * 1024)) { "缩略图大小无效" }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096) { "缩略图图像无效" }
        val folder = File(context.filesDir, "gallery-previews").apply { mkdirs() }
        reserveCache(folder, bytes.size.toLong(), 128L * 1024 * 1024, media.id)
        val temporary = File.createTempFile("pending", ".jpg", folder)
        try {
            temporary.writeBytes(bytes)
            val final = File(folder, media.id + ".jpg")
            check(temporary.renameTo(final)) { "缓存缩略图失败" }
            File(folder, media.id + ".sha256").writeText(fileSha(final))
            File(folder, media.id + ".revision").writeText(media.revision.toString())
            return final
        } finally { temporary.delete() }
    }

    fun fetchOriginal(media: RemotePhoto, progress: (Long, Long) -> Unit): File {
        checkMediaCenter(media)
        require(media.originalReady && !media.sha256.isNullOrBlank()) { "云端原件尚未就绪" }
        original(media.id)?.let { cached ->
            if (fileSha(cached) == media.sha256) return cached
            cached.delete()
        }
        val credential = pair()
        val folder = File(context.filesDir, "gallery-originals").apply { mkdirs() }
        // Stable partial path survives process death. SHA binds it to the exact revision.
        val tmp = File(folder, media.id + "." + media.sha256 + ".part")
        folder.listFiles()?.filter { it.name.startsWith(media.id + ".") && it.name.endsWith(".part") && it.name != tmp.name }
            ?.forEach { check(it.delete()) { "无法清理旧版本缓存" } }
        reserveCache(folder, media.size ?: 0, 2L * 1024 * 1024 * 1024, media.id, reusableFileName = tmp.name)
        if (media.size != null && tmp.length() > media.size) tmp.delete()
        var offset = tmp.length()
        if (media.size != null && offset == media.size && fileSha(tmp) == media.sha256) {
            val target = File(folder, media.id)
            check(tmp.renameTo(target)) { "无法缓存原件" }
            File(folder, media.id + ".sha256").writeText(media.sha256!!)
            return target
        }
        val connection = open(URL(credential.serverUrl + "/v1/media/" + media.id + "/original"),
            "GET", credential.deviceToken)
        try {
            if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
            val status = connection.responseCode
            if (status !in listOf(200, 206)) throw httpError(connection)
            if (status == 206) {
                check(connection.getHeaderField("Content-Range")?.startsWith("bytes $offset-") == true) {
                    "服务器续传范围不匹配"
                }
            } else offset = 0L
            var total = offset
            connection.inputStream.use { input ->
                java.io.FileOutputStream(tmp, offset > 0).use { output ->
                    val block = ByteArray(128 * 1024)
                    while (true) {
                        val read = input.read(block)
                        if (read == -1) break
                        output.write(block, 0, read)
                        total += read
                        progress(total, media.size ?: 0L)
                    }
                    output.fd.sync()
                }
            }
            if (fileSha(tmp) != media.sha256 || (media.size != null && media.size != total)) {
                tmp.delete()
                error("下载文件完整性校验失败")
            }
            val target = File(folder, media.id)
            check(tmp.renameTo(target)) { "无法缓存原件" }
            File(folder, media.id + ".sha256").writeText(media.sha256!!)
            return target
        } finally { connection.disconnect() }
    }

    fun clearOriginalCache(): Long {
        val folder = File(context.filesDir, "gallery-originals")
        var freed = 0L
        folder.listFiles()?.forEach { file ->
            val size = file.length()
            check(file.delete()) { "缓存正在使用或无法清理，请稍后重试" }
            freed += size
        }
        return freed
    }

    internal fun reserveCache(folder: File, required: Long, limit: Long, protectedId: String,
        availableBytes: Long? = null, reusableFileName: String? = null) {
        val entries = folder.listFiles()?.filter { it.isFile } ?: emptyList()
        var used = entries.sumOf { it.length() }
        val protectedBytes = entries.filter { it.name.startsWith(protectedId) }.sumOf { it.length() }
        val existing = entries.filter { it.name == reusableFileName }.sumOf { it.length() }.coerceAtMost(required)
        val additional = maxOf(0L, required - existing)
        // Retention budget is soft for an explicitly opened large original. Never discard
        // resumable partials; actual filesystem free space remains the hard safety gate.
        val otherPartials = entries.filter { it.name.endsWith(".part") && !it.name.startsWith(protectedId) }.sumOf { it.length() }
        val budget = maxOf(limit, required + otherPartials + maxOf(0L, protectedBytes - existing))
        for (entry in entries.sortedBy { it.lastModified() }) {
            if (used + additional <= budget) break
            if (entry.name.startsWith(protectedId) || entry.name.endsWith(".part")) continue
            val size = entry.length()
            if (entry.delete()) used -= size
        }
        check(used + additional <= budget) { "缓存空间不足，请稍后重试" }
        check((availableBytes ?: folder.usableSpace) >= additional + 16L * 1024 * 1024) { "设备存储空间不足" }
    }

    private fun request(url: String, method: String, path: String, data: ByteArray?, token: String?,
        headers: Map<String, String> = emptyMap()): ByteArray {
        val connection = open(URL(url + path), method, token)
        try {
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (data != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(data.size)
                connection.outputStream.use { it.write(data) }
            }
            if (connection.responseCode !in 200..299) throw httpError(connection)
            return connection.inputStream.use { it.readBytes() }
        } finally { connection.disconnect() }
    }

    private fun open(url: URL, method: String, token: String?): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 60_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }

    private fun httpError(connection: HttpURLConnection): SignalHttpError {
        val data = runCatching { JSONObject(String(connection.errorStream?.readBytes() ?: ByteArray(0))) }
            .getOrElse { JSONObject().put("error", "服务器请求失败") }
        return SignalHttpError(connection.responseCode, data)
    }

    private fun pair(): SignalPairing = credentials.read() ?: error("Signal Server 尚未配对")
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun fileSha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(bytes)
                if (count == -1) break
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun skipExactly(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped
            else if (input.read() != -1) remaining--
            else error("本机原件已发生变化")
        }
    }
}

internal data class SignalCatalog(val items: List<RemotePhoto>, val cursor: Long)

/** Only a verified cache for the current server revision earns the no-dot state. */
internal fun verifiedGalleryOriginal(context: Context, media: RemotePhoto): File? {
    if (!media.originalReady || media.sha256 == null) return null
    val folder = File(context.filesDir, "gallery-originals")
    val file = File(folder, media.id)
    val marker = File(folder, media.id + ".sha256")
    return file.takeIf { it.isFile && (media.size == null || it.length() == media.size) &&
        marker.isFile && runCatching { marker.readText() == media.sha256 }.getOrDefault(false) }
}
