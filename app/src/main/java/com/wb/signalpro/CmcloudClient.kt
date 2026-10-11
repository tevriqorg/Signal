package com.wb.signalpro

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CancellationException
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.max

class CmcloudClient(private val baseUrl: String) {
    data class TransferProgress(
        val done: Long,
        val total: Long,
        val currentMbps: Double,
        val averageMbps: Double,
        val elapsedMs: Long
    )

    data class TransferResult(
        val bytes: Long,
        val elapsedMs: Long,
        val averageMbps: Double,
        val eosHost: String,
        val sha256Match: Boolean? = null,
        val sessionId: String? = null,
        val cached: Boolean? = null
    )

    data class PreparedDownload(
        val sessionId: String,
        val size: Long,
        val sha256: String,
        val cached: Boolean
    )

    data class RemoteJob(
        val jobId: String,
        val state: String,
        val phase: String,
        val progressBytes: Long,
        val totalBytes: Long,
        val currentMbps: Double,
        val averageMbps: Double,
        val elapsedMs: Double,
        val result: JSONObject?,
        val error: String?
    )

    private fun api(path: String) = baseUrl.trimEnd('/') + path

    fun health(): JSONObject = JsonHttp.requestJson("GET", api("/api/health"))

    fun startPrepareDownloadJob(sizeMiB: Int): RemoteJob {
        val json = JsonHttp.requestJson(
            "POST",
            api("/api/signal/jobs/prepare-download"),
            JSONObject().put("size_mib", sizeMiB)
        )
        return parseRemoteJob(json)
    }

    fun getRemoteJob(jobId: String): RemoteJob =
        parseRemoteJob(JsonHttp.requestJson("GET", api("/api/signal/jobs/$jobId")))

    fun cancelRemoteJob(jobId: String): RemoteJob =
        parseRemoteJob(JsonHttp.requestJson("POST", api("/api/signal/jobs/$jobId/cancel"), JSONObject()))

    fun preparedFromJob(job: RemoteJob): PreparedDownload {
        val result = job.result ?: error("remote job has no result")
        return PreparedDownload(
            sessionId = result.getString("session_id"),
            size = result.getLong("size"),
            sha256 = result.getString("sha256"),
            cached = result.optBoolean("cached", false)
        )
    }

    private fun parseRemoteJob(json: JSONObject): RemoteJob = RemoteJob(
        jobId = json.getString("job_id"),
        state = json.optString("state", "unknown"),
        phase = json.optString("phase", "unknown"),
        progressBytes = json.optLong("progress_bytes", 0L),
        totalBytes = json.optLong("total_bytes", 0L),
        currentMbps = json.optDouble("current_mbps", 0.0),
        averageMbps = json.optDouble("average_mbps", 0.0),
        elapsedMs = json.optDouble("elapsed_ms", 0.0),
        result = json.optJSONObject("result"),
        error = json.optString("error").takeIf { it.isNotBlank() }
    )

    fun prepareDownload(sizeMiB: Int): PreparedDownload {
        val json = JsonHttp.requestJson(
            "POST",
            api("/api/prepare-download"),
            JSONObject().put("size_mib", sizeMiB),
            timeoutMs = 240_000
        )
        return PreparedDownload(
            sessionId = json.getString("session_id"),
            size = json.getLong("size"),
            sha256 = json.getString("sha256"),
            cached = json.optBoolean("cached", false)
        )
    }

    fun download(
        prepared: PreparedDownload,
        shouldCancel: () -> Boolean = { false },
        onCapability: (String) -> Unit = {},
        onProgress: (TransferProgress) -> Unit
    ): TransferResult {
        val cap = JsonHttp.requestJson(
            "POST",
            api("/api/downloads/${prepared.sessionId}"),
            JSONObject()
        )
        val signedUrl = cap.getString("url")
        onCapability(URL(signedUrl).host)
        val total = cap.optLong("size", prepared.size).takeIf { it > 0 } ?: prepared.size
        val connection = (URL(signedUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 180_000
            useCaches = false
            setRequestProperty("Accept-Encoding", "identity")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val started = System.nanoTime()
        var done = 0L
        var lastBytes = 0L
        var lastAt = started
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("EOS GET HTTP $code")
            BufferedInputStream(connection.inputStream, 256 * 1024).use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    if (shouldCancel()) throw CancellationException("transfer cancelled")
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    done += count
                    val now = System.nanoTime()
                    if (now - lastAt >= 200_000_000L || done == total) {
                        onProgress(progress(done, total, started, lastBytes, lastAt, now))
                        lastBytes = done
                        lastAt = now
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        val elapsed = elapsedMs(started)
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return TransferResult(
            bytes = done,
            elapsedMs = elapsed,
            averageMbps = mbps(done, elapsed),
            eosHost = URL(signedUrl).host,
            sha256Match = actual.equals(prepared.sha256, ignoreCase = true),
            sessionId = prepared.sessionId,
            cached = prepared.cached
        )
    }

    fun upload(
        sample: SyntheticMedia.Sample,
        shouldCancel: () -> Boolean = { false },
        onCapability: (String) -> Unit = {},
        onProgress: (TransferProgress) -> Unit
    ): TransferResult {
        val size = sample.file.length()
        val session = JsonHttp.requestJson(
            "POST",
            api("/api/uploads"),
            JSONObject()
                .put("size", size)
                .put("sha256", sample.sha256)
                .put("encryption", "none")
                .put("name", sample.displayName)
        )
        val sessionId = session.getString("session_id")
        try {
            val partCount = session.getInt("part_count")
            val partSize = session.getLong("part_size")
            val urls = linkedMapOf<Int, String>()
            readPartUrls(session.optJSONArray("parts"), urls)
            val missing = (1..partCount).filterNot { urls.containsKey(it) }
            missing.chunked(100).forEach { numbers ->
                val array = JSONArray().apply { numbers.forEach { put(it) } }
                val issued = JsonHttp.requestJson(
                    "POST",
                    api("/api/uploads/$sessionId/parts"),
                    JSONObject().put("part_numbers", array)
                )
                readPartUrls(issued.optJSONArray("parts"), urls)
            }

            val firstUrl = urls.values.firstOrNull() ?: error("no upload capability returned")
            onCapability(URL(firstUrl).host)

            val started = System.nanoTime()
            var totalDone = 0L
            var lastBytes = 0L
            var lastAt = started
            var eosHost = "--"
            RandomAccessFile(sample.file, "r").use { file ->
                for (number in 1..partCount) {
                    if (shouldCancel()) throw CancellationException("transfer cancelled")
                    val signedUrl = urls[number] ?: error("missing part $number capability")
                    if (eosHost == "--") eosHost = URL(signedUrl).host
                    val offset = (number - 1L) * partSize
                    val length = minOf(partSize, size - offset)
                    file.seek(offset)
                    putPart(signedUrl, file, length, shouldCancel) { partDone ->
                        val done = totalDone + partDone
                        val now = System.nanoTime()
                        if (now - lastAt >= 200_000_000L || done == size) {
                            onProgress(progress(done, size, started, lastBytes, lastAt, now))
                            lastBytes = done
                            lastAt = now
                        }
                    }
                    totalDone += length
                }
            }
            JsonHttp.requestJson("POST", api("/api/uploads/$sessionId/complete"), JSONObject())
            val elapsed = elapsedMs(started)
            return TransferResult(
                bytes = totalDone,
                elapsedMs = elapsed,
                averageMbps = mbps(totalDone, elapsed),
                eosHost = eosHost,
                sessionId = sessionId
            )
        } catch (t: Throwable) {
            try { trash(sessionId) } catch (_: Throwable) {}
            throw t
        }
    }

    fun trash(sessionId: String) {
        JsonHttp.requestJson("POST", api("/api/trash/$sessionId"), JSONObject())
    }

    private fun readPartUrls(parts: JSONArray?, into: MutableMap<Int, String>) {
        if (parts == null) return
        for (i in 0 until parts.length()) {
            val item = parts.getJSONObject(i)
            into[item.getInt("part_number")] = item.getString("upload_url")
        }
    }

    private fun putPart(
        signedUrl: String,
        file: RandomAccessFile,
        length: Long,
        shouldCancel: () -> Boolean,
        onPartProgress: (Long) -> Unit
    ) {
        val connection = (URL(signedUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 180_000
            useCaches = false
            setRequestProperty("Content-Type", "application/octet-stream")
            setFixedLengthStreamingMode(length)
        }
        try {
            var sent = 0L
            BufferedOutputStream(connection.outputStream, 256 * 1024).use { output ->
                val buffer = ByteArray(256 * 1024)
                while (sent < length) {
                    if (shouldCancel()) throw CancellationException("transfer cancelled")
                    val count = minOf(buffer.size.toLong(), length - sent).toInt()
                    file.readFully(buffer, 0, count)
                    output.write(buffer, 0, count)
                    sent += count
                    onPartProgress(sent)
                }
            }
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("EOS PUT HTTP $code")
        } finally {
            connection.disconnect()
        }
    }

    private fun progress(
        done: Long,
        total: Long,
        started: Long,
        lastBytes: Long,
        lastAt: Long,
        now: Long
    ): TransferProgress {
        val elapsedMs = max(1L, (now - started) / 1_000_000L)
        val intervalMs = max(1L, (now - lastAt) / 1_000_000L)
        return TransferProgress(
            done = done,
            total = total,
            currentMbps = mbps(done - lastBytes, intervalMs),
            averageMbps = mbps(done, elapsedMs),
            elapsedMs = elapsedMs
        )
    }

    private fun elapsedMs(started: Long) = max(1L, (System.nanoTime() - started) / 1_000_000L)

    private fun mbps(bytes: Long, elapsedMs: Long): Double =
        if (elapsedMs <= 0) 0.0 else bytes * 8.0 / elapsedMs / 1000.0
}
