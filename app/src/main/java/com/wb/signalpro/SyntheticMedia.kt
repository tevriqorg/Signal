package com.wb.signalpro

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object SyntheticMedia {
    data class Sample(
        val file: File,
        val sha256: String,
        val displayName: String,
        val mediaType: String
    )

    private val random = SecureRandom()
    private const val MIB = 1024 * 1024

    fun createRandom(cacheDir: File, onProgress: (Long, Long) -> Unit): Sample {
        val kind = listOf("wav", "pdf", "zip")[random.nextInt(3)]
        val (lowMiB, highMiB) = when (kind) {
            "pdf" -> 2 to 18
            "zip" -> 10 to 60
            else -> 15 to 70
        }
        val target = lowMiB.toLong() * MIB + random.nextInt((highMiB - lowMiB) * MIB).toLong()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = when (kind) {
            "pdf" -> "Report_${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}_${100 + random.nextInt(900)}.pdf"
            "zip" -> "Documents_${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}_${100 + random.nextInt(900)}.zip"
            else -> "REC_${stamp}.wav"
        }
        val file = File.createTempFile("signal-", ".$kind", cacheDir)
        when (kind) {
            "pdf" -> createPdf(file, target, onProgress)
            "zip" -> createZip(file, target, onProgress)
            else -> createWav(file, target, onProgress)
        }
        return Sample(file, sha256(file), displayName, kind)
    }

    private fun createWav(file: File, target: Long, onProgress: (Long, Long) -> Unit) {
        val dataBytes = target - 44
        val header = wavHeader(dataBytes)
        FileOutputStream(file).buffered(1024 * 1024).use { output ->
            output.write(header)
            var written = 44L
            onProgress(written, target)
            val buffer = ByteArray(1024 * 1024)
            while (written < target) {
                val count = minOf(buffer.size.toLong(), target - written).toInt()
                random.nextBytes(buffer)
                output.write(buffer, 0, count)
                written += count
                onProgress(written, target)
            }
        }
    }

    private fun createZip(file: File, target: Long, onProgress: (Long, Long) -> Unit) {
        val payload = (target - 512).coerceAtLeast(1024)
        ZipOutputStream(FileOutputStream(file).buffered(1024 * 1024)).use { zip ->
            zip.setMethod(ZipOutputStream.STORED)
            // STORED needs CRC/size up front, so use a temporary payload and then package it.
            val payloadFile = File.createTempFile("signal-payload-", ".bin", file.parentFile)
            try {
                FileOutputStream(payloadFile).buffered(1024 * 1024).use { out ->
                    var written = 0L
                    val buffer = ByteArray(1024 * 1024)
                    while (written < payload) {
                        val count = minOf(buffer.size.toLong(), payload - written).toInt()
                        random.nextBytes(buffer); out.write(buffer, 0, count); written += count
                        onProgress(written, payload)
                    }
                }
                val crc = java.util.zip.CRC32()
                payloadFile.inputStream().buffered(1024 * 1024).use { input ->
                    val buf = ByteArray(1024 * 1024)
                    while (true) { val n = input.read(buf); if (n < 0) break; crc.update(buf, 0, n) }
                }
                val entry = ZipEntry("Archive/data.bin").apply {
                    method = ZipEntry.STORED
                    size = payloadFile.length()
                    compressedSize = payloadFile.length()
                    this.crc = crc.value
                }
                zip.putNextEntry(entry)
                payloadFile.inputStream().buffered(1024 * 1024).use { it.copyTo(zip, 1024 * 1024) }
                zip.closeEntry()
            } finally { payloadFile.delete() }
        }
        onProgress(file.length(), file.length())
    }

    private fun createPdf(file: File, target: Long, onProgress: (Long, Long) -> Unit) {
        val payload = (target - 2048).coerceAtLeast(1024)
        FileOutputStream(file).buffered(1024 * 1024).use { out ->
            val offsets = mutableListOf<Long>()
            var pos = 0L
            fun put(bytes: ByteArray) { out.write(bytes); pos += bytes.size }
            fun put(text: String) = put(text.toByteArray(Charsets.US_ASCII))
            put("%PDF-1.7\n% Synthetic Signal document\n")
            val objects = listOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R >>",
                "<< /Length 42 >>\nstream\nBT /F1 12 Tf 72 770 Td (Signal report) Tj ET\nendstream"
            )
            objects.forEachIndexed { index, obj ->
                offsets += pos; put("${index + 1} 0 obj\n$obj\nendobj\n")
            }
            offsets += pos
            put("5 0 obj\n<< /Type /EmbeddedFile /Length $payload >>\nstream\n")
            val buffer = ByteArray(1024 * 1024)
            var written = 0L
            while (written < payload) {
                val count = minOf(buffer.size.toLong(), payload - written).toInt()
                random.nextBytes(buffer); out.write(buffer, 0, count); pos += count; written += count
                onProgress(written, payload)
            }
            put("\nendstream\nendobj\n")
            val xref = pos
            put("xref\n0 6\n0000000000 65535 f \n")
            offsets.forEach { put(String.format(Locale.US, "%010d 00000 n \n", it)) }
            put("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        }
        onProgress(file.length(), file.length())
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1024 * 1024).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun wavHeader(dataSize: Long): ByteArray {
        val sampleRate = 48_000; val channels = 1; val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8; val blockAlign = channels * bitsPerSample / 8
        val out = ByteArray(44)
        fun ascii(offset: Int, value: String) = value.toByteArray(Charsets.US_ASCII).copyInto(out, offset)
        fun le16(offset: Int, value: Int) { out[offset] = (value and 0xff).toByte(); out[offset + 1] = ((value ushr 8) and 0xff).toByte() }
        fun le32(offset: Int, value: Long) { for (i in 0..3) out[offset + i] = ((value ushr (8 * i)) and 0xff).toByte() }
        ascii(0, "RIFF"); le32(4, 36L + dataSize); ascii(8, "WAVE"); ascii(12, "fmt "); le32(16, 16)
        le16(20, 1); le16(22, channels); le32(24, sampleRate.toLong()); le32(28, byteRate.toLong())
        le16(32, blockAlign); le16(34, bitsPerSample); ascii(36, "data"); le32(40, dataSize)
        return out
    }
}
