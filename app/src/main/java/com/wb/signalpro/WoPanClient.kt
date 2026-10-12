package com.wb.signalpro

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlin.math.max

/** WoPan direct transfer client. Upload capabilities are intentionally scoped to upload(). */
class WoPanClient(private val baseUrl: String) {
    data class TransferProgress(val done: Long, val total: Long, val currentMbps: Double, val averageMbps: Double, val elapsedMs: Long)
    data class TransferResult(val bytes: Long, val elapsedMs: Long, val averageMbps: Double, val host: String, val sha256Match: Boolean? = null, val fid: String? = null)
    data class RemoteJob(val jobId: String, val state: String, val phase: String, val progressBytes: Long, val totalBytes: Long, val currentMbps: Double, val averageMbps: Double, val result: JSONObject?, val error: String?)
    data class Prepared(val fid: String, val size: Long, val sha256: String, val name: String, val cached: Boolean, val host: String)
    private fun api(path: String) = baseUrl.trimEnd('/') + path

    fun health() = JsonHttp.requestJson("GET", api("/api/signal/health"))
    fun nodeInfo(target: String): JSONObject = JsonHttp.requestJson("GET", api("/api/signal/node-info?target=${URLEncoder.encode(target, Charsets.UTF_8.name())}"), timeoutMs = 5_000)
    fun startPrepareDownloadJob() = parseJob(JsonHttp.requestJson("POST", api("/api/signal/jobs/prepare-download"), JSONObject()))
    fun getRemoteJob(id: String) = parseJob(JsonHttp.requestJson("GET", api("/api/signal/jobs/$id")))
    fun cancelRemoteJob(id: String) = parseJob(JsonHttp.requestJson("POST", api("/api/signal/jobs/$id/cancel"), JSONObject()))
    fun startVerifyDownload(fid: String, sha256: String, trash: Boolean = true) = parseJob(JsonHttp.requestJson("POST", api("/api/signal/jobs/verify-download/${encodePath(fid)}"), JSONObject().put("expected_sha256", sha256).put("trash", trash)))
    fun delete(fid: String) { JsonHttp.requestJson("POST", api("/api/signal/delete/${encodePath(fid)}"), JSONObject()) }
    fun reportResult(payload: JSONObject) { JsonHttp.requestJson("POST", api("/api/signal/results"), payload, timeoutMs = 10_000) }
    fun prepared(job: RemoteJob): Prepared {
        val r = job.result ?: error("Tokyo job returned no result")
        return Prepared(r.optString("fid", r.optString("file_id", r.optString("unique_id"))), r.getLong("size"), r.getString("sha256"), r.optString("name", r.optString("file_name")), r.optBoolean("cached"), r.optString("host"))
    }

    fun download(prepared: Prepared, shouldCancel: () -> Boolean = { false }, onCapability: (String) -> Unit = {}, onProgress: (TransferProgress) -> Unit): TransferResult {
        val cap = JsonHttp.requestJson("POST", api("/api/signal/downloads/${encodePath(prepared.fid)}"), JSONObject(), timeoutMs = 150_000)
        val directUrl = cap.getString("url")
        val host = cap.optString("host").ifBlank { URL(directUrl).host }
        onCapability(host)
        val conn = (URL(directUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 30_000; readTimeout = 180_000; useCaches = false
            setRequestProperty("Accept-Encoding", "identity")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val started = System.nanoTime(); var done = 0L; var lastBytes = 0L; var lastAt = started
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("WoPan GET HTTP $code")
            val total = conn.getHeaderFieldLong("Content-Length", prepared.size).takeIf { it > 0 } ?: prepared.size
            BufferedInputStream(conn.inputStream, 256 * 1024).use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    if (shouldCancel()) throw CancellationException("transfer cancelled")
                    val n = input.read(buffer); if (n < 0) break
                    digest.update(buffer, 0, n); done += n
                    val now = System.nanoTime()
                    if (now - lastAt >= 200_000_000L || done == total) {
                        onProgress(progress(done, total, started, lastBytes, lastAt, now)); lastBytes = done; lastAt = now
                    }
                }
            }
        } finally { conn.disconnect() }
        val elapsed = elapsed(started)
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return TransferResult(done, elapsed, mbps(done, elapsed), host, actual.equals(prepared.sha256, true), prepared.fid)
    }

    fun upload(sample: SyntheticMedia.Sample, shouldCancel: () -> Boolean = { false }, onCapability: (String) -> Unit = {}, onProgress: (TransferProgress) -> Unit): TransferResult {
        val size = sample.file.length()
        val meta = JsonHttp.requestJson("POST", api("/api/signal/uploads"), JSONObject().put("size", size).put("sha256", sample.sha256).put("name", sample.displayName))
        // Account access credential is delivered only for this transfer and remains in this stack frame.
        val accessToken = meta.getString("access_token")
        val uploadUrl = meta.getString("upload_url")
        val host = meta.optString("host").ifBlank { URL(uploadUrl).host }
        onCapability(host)
        val fileInfo = meta.getString("file_info")
        val uniqueId = meta.getString("unique_id")
        val fileName = meta.optString("file_name", sample.displayName)
        val directoryId = meta.optString("directory_id", "0")
        val channel = meta.optString("channel", "wocloud")
        val partSize = meta.optLong("part_size", 8L * 1024 * 1024).takeIf { it > 0 } ?: 8L * 1024 * 1024
        val partCount = meta.optInt("part_count", maxOf(1, (size / partSize).toInt()))
        require(partSize == 8L * 1024 * 1024) { "WoPan returned unsupported multipart part size" }
        require(partCount > 0) { "WoPan returned invalid multipart part count" }
        val started = System.nanoTime(); var lastBytes = 0L; var lastAt = started; var providerFid = ""
        RandomAccessFile(sample.file, "r").use { file ->
            for (part in 0 until partCount) {
                if (shouldCancel()) throw CancellationException("transfer cancelled")
                val offset = part.toLong() * partSize
                val length = if (part == partCount - 1) size - offset else minOf(partSize, size - offset)
                file.seek(offset)
                val fields = linkedMapOf(
                    "uniqueId" to uniqueId, "accessToken" to accessToken, "fileName" to fileName,
                    "psToken" to "undefined", "fileSize" to size.toString(), "totalPart" to partCount.toString(),
                    "channel" to channel, "directoryId" to directoryId, "fileInfo" to fileInfo,
                    "partSize" to length.toString(), "partIndex" to (part + 1).toString()
                )
                var response: JSONObject? = null
                var lastError = ""
                for (attempt in 1..4) {
                    if (shouldCancel()) throw CancellationException("transfer cancelled")
                    file.seek(offset)
                    try {
                        val r = multipartPart(uploadUrl, fields, file, length, fileName, shouldCancel) { partBytes ->
                            val done = offset + partBytes; val now = System.nanoTime()
                            if (now - lastAt >= 200_000_000L || done == size) {
                                onProgress(progress(done, size, started, lastBytes, lastAt, now)); lastBytes = done; lastAt = now
                            }
                        }
                        if (r.optString("code") == "0000") { response = r; break }
                        lastError = "code=${r.optString("code")} msg=${r.optString("msg", r.optString("message"))}".take(180)
                    } catch (t: Throwable) {
                        lastError = (t.message ?: t.javaClass.simpleName).replace('\n', ' ').take(180)
                    }
                    if (attempt < 4) Thread.sleep(800L shl (attempt - 1))
                }
                val ok = response ?: error("WoPan part ${part + 1}/$partCount failed after 4 attempts: $lastError")
                ok.optJSONObject("data")?.optString("fid")?.takeIf { it.isNotBlank() }?.let { providerFid = it }
            }
        }
        if (providerFid.isBlank()) error("WoPan did not return fid")
        val elapsed = elapsed(started)
        return TransferResult(size, elapsed, mbps(size, elapsed), host, fid = providerFid)
    }

    private fun multipartPart(target: String, fields: Map<String, String>, file: RandomAccessFile, length: Long, fileName: String, shouldCancel: () -> Boolean, onBytes: (Long) -> Unit): JSONObject {
        val boundary = "----Signal${UUID.randomUUID().toString().replace("-", "")}"; val crlf = "\r\n"
        val preamble = buildString {
            fields.forEach { (name, value) ->
                append("--$boundary$crlf")
                append("Content-Disposition: form-data; name=\"$name\"$crlf$crlf$value$crlf")
            }
            append("--$boundary$crlf")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"${fileName.replace("\"", "")}\"$crlf")
            append("Content-Type: application/octet-stream$crlf$crlf")
        }.toByteArray()
        val tail = "$crlf--$boundary--$crlf".toByteArray()
        val conn = (URL(target).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 30_000; readTimeout = 180_000; useCaches = false
            setRequestProperty("Origin", "https://pan.wo.cn"); setRequestProperty("Referer", "https://pan.wo.cn/")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setFixedLengthStreamingMode(preamble.size.toLong() + length + tail.size)
        }
        try {
            var sent = 0L
            BufferedOutputStream(conn.outputStream, 256 * 1024).use { out ->
                out.write(preamble)
                val buffer = ByteArray(256 * 1024)
                while (sent < length) {
                    if (shouldCancel()) throw CancellationException("transfer cancelled")
                    val n = minOf(buffer.size.toLong(), length - sent).toInt(); file.readFully(buffer, 0, n)
                    out.write(buffer, 0, n); sent += n; onBytes(sent)
                }
                out.write(tail)
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("WoPan upload2C HTTP $code")
            return JSONObject(text)
        } finally { conn.disconnect() }
    }

    private fun parseJob(j: JSONObject) = RemoteJob(j.getString("job_id"), j.optString("state", "not_reported"), j.optString("phase", "not_reported"), j.optLong("progress_bytes"), j.optLong("total_bytes"), j.optDouble("current_mbps"), j.optDouble("average_mbps"), j.optJSONObject("result"), j.optString("error").takeIf { it.isNotBlank() })
    private fun encodePath(s: String) = URLEncoder.encode(s, Charsets.UTF_8.name()).replace("+", "%20")
    private fun elapsed(start: Long) = max(1L, (System.nanoTime() - start) / 1_000_000L)
    private fun mbps(bytes: Long, ms: Long) = bytes * 8.0 / ms / 1000.0
    private fun progress(done: Long, total: Long, started: Long, lastBytes: Long, lastAt: Long, now: Long): TransferProgress {
        val elapsedMs = max(1L, (now - started) / 1_000_000L); val interval = max(1L, (now - lastAt) / 1_000_000L)
        return TransferProgress(done, total, mbps(done - lastBytes, interval), mbps(done, elapsedMs), elapsedMs)
    }
}
