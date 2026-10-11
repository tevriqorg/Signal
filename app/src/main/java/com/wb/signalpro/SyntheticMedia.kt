package com.wb.signalpro

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random

object SyntheticMedia {
    data class Sample(val file: File, val sha256: String, val displayName: String)

    fun createWav(cacheDir: File, sizeBytes: Long, onProgress: (Long, Long) -> Unit): Sample {
        require(sizeBytes > 44) { "WAV size must be > 44 bytes" }
        require(sizeBytes <= 512L * 1024L * 1024L) { "sample too large" }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "REC_${stamp}.wav"
        val file = File.createTempFile("signal-", ".wav", cacheDir)
        val digest = MessageDigest.getInstance("SHA-256")
        val random = Random(System.nanoTime())
        val dataBytes = sizeBytes - 44
        val header = wavHeader(dataBytes)

        FileOutputStream(file).buffered(1024 * 1024).use { output ->
            output.write(header)
            digest.update(header)
            var written = 44L
            onProgress(written, sizeBytes)
            val buffer = ByteArray(1024 * 1024)
            while (written < sizeBytes) {
                val count = minOf(buffer.size.toLong(), sizeBytes - written).toInt()
                random.nextBytes(buffer)
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                written += count
                onProgress(written, sizeBytes)
            }
        }
        return Sample(file, digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }, displayName)
    }

    private fun wavHeader(dataSize: Long): ByteArray {
        val sampleRate = 48_000
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val chunkSize = 36L + dataSize
        val out = ByteArray(44)
        fun ascii(offset: Int, value: String) = value.toByteArray(Charsets.US_ASCII).copyInto(out, offset)
        fun le16(offset: Int, value: Int) {
            out[offset] = (value and 0xff).toByte()
            out[offset + 1] = ((value ushr 8) and 0xff).toByte()
        }
        fun le32(offset: Int, value: Long) {
            for (i in 0..3) out[offset + i] = ((value ushr (8 * i)) and 0xff).toByte()
        }
        ascii(0, "RIFF")
        le32(4, chunkSize)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        le32(16, 16)
        le16(20, 1)
        le16(22, channels)
        le32(24, sampleRate.toLong())
        le32(28, byteRate.toLong())
        le16(32, blockAlign)
        le16(34, bitsPerSample)
        ascii(36, "data")
        le32(40, dataSize)
        return out
    }
}
