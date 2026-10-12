package com.wb.signalpro

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object JsonHttp {
    fun requestJson(method: String, url: String, body: JSONObject? = null, timeoutMs: Int = 30_000): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            useCaches = false
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                BufferedOutputStream(connection.outputStream).use { it.write(bytes) }
            }
            val code = connection.responseCode
            val source = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = source?.let { BufferedInputStream(it).use { input -> input.readBytes().toString(StandardCharsets.UTF_8) } }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code ${text.take(300)}".trim())
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }
}
