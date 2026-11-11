package com.example.wardrive

import android.util.Base64
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Uploads a WiGLE CSV log to:
 *  - WiGLE     POST https://api.wigle.net/api/v2/file/upload   (multipart "file", Basic auth)
 *  - WDGWars   POST https://wdgwars.pl/api/v2/upload-csv         (multipart "file", X-API-Key)
 *  - Wardrift  POST https://wardrift.net/v1/ingest/signals       (raw text/csv body, X-API-Key, X-File-Name)
 * Endpoints match the ones used by the ESP32 Marauder and Piglet firmwares.
 */
object Uploader {
    data class Result(val ok: Boolean, val msg: String)

    fun upload(service: String, file: File, done: (Result) -> Unit) {
        Store.flush()
        thread(name = "upload-$service") {
            val r = try {
                when (service) {
                    "wigle" -> wigle(file)
                    "wdg" -> wdg(file)
                    "drift" -> drift(file)
                    else -> Result(false, "Unknown service")
                }
            } catch (e: Exception) {
                Result(false, e.message ?: e.javaClass.simpleName)
            }
            done(r)
        }
    }

    private fun wigle(file: File): Result {
        val name = Prefs.wigleName
        val token = Prefs.wigleToken
        if (token.isEmpty()) return Result(false, "Add your WiGLE API token in Settings")
        val auth = if (name.isEmpty()) token
        else Base64.encodeToString("$name:$token".toByteArray(), Base64.NO_WRAP)
        return multipart(
            "https://api.wigle.net/api/v2/file/upload", file,
            mapOf("Authorization" to "Basic $auth", "Accept" to "application/json"),
            mapOf("donate" to "false"),
        )
    }

    private fun wdg(file: File): Result {
        val key = Prefs.wdgKey
        if (key.isEmpty()) return Result(false, "Add your WDGWars API key in Settings")
        return multipart("https://wdgwars.pl/api/v2/upload-csv", file, mapOf("X-API-Key" to key, "Accept" to "application/json"), emptyMap())
    }

    private fun drift(file: File): Result {
        val key = Prefs.driftKey
        if (key.isEmpty()) return Result(false, "Add your Wardrift API key in Settings")
        val c = URL("https://wardrift.net/v1/ingest/signals").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.setRequestProperty("X-API-Key", key)
        c.setRequestProperty("Content-Type", "text/csv")
        c.setRequestProperty("X-File-Name", file.name)
        c.setFixedLengthStreamingMode(file.length())
        c.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
        return finish(c)
    }

    private fun multipart(url: String, file: File, headers: Map<String, String>, fields: Map<String, String>): Result {
        val boundary = "----wardrive" + System.currentTimeMillis()
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 15_000
        c.readTimeout = 120_000
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.setChunkedStreamingMode(16 * 1024)
        DataOutputStream(c.outputStream).use { out ->
            for ((k, v) in fields) {
                out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
            }
            out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n")
            out.writeBytes("Content-Type: text/csv\r\n\r\n")
            file.inputStream().use { it.copyTo(out) }
            out.writeBytes("\r\n--$boundary--\r\n")
        }
        return finish(c)
    }

    private fun finish(c: HttpURLConnection): Result {
        val code = c.responseCode
        val body = try {
            (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (_: Exception) { "" }
        c.disconnect()
        val short = body.replace(Regex("\\s+"), " ").take(140)
        return when {
            code in 200..299 && body.contains("\"success\":false") -> Result(false, short)
            code in 200..299 -> Result(true, "Uploaded (HTTP $code) $short".trim())
            code == 401 || code == 403 -> Result(false, "Key rejected (HTTP $code)")
            else -> Result(false, "HTTP $code $short".trim())
        }
    }
}
