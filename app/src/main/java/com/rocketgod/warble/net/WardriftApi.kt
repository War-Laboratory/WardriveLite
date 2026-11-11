package com.rocketgod.warble.net

import java.net.HttpURLConnection
import java.net.URL

/**
 * Wardrive Lite (SHOK3R fork): Wardrift (wardrift.net) upload.
 * POST /v1/ingest/signals with the WiGLE CSV as the raw body, X-API-Key = character key (wdk_…),
 * the same request the Piglet wardriver firmware makes.
 */
object WardriftApi {
    private const val URL_INGEST = "https://wardrift.net/v1/ingest/signals"

    fun uploadFile(apiKey: String, file: java.io.File, filename: String): WdgwApi.Result {
        if (apiKey.isBlank()) return WdgwApi.Result(false, "Missing Wardrift API key")
        if (file.length() > 10L * 1024 * 1024) return WdgwApi.Result(false, "Wardrift accepts files up to 10 MB")
        var c: HttpURLConnection? = null
        return try {
            c = (URL(URL_INGEST).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = 30_000; readTimeout = 300_000
                setFixedLengthStreamingMode(file.length())
                setRequestProperty("X-API-Key", apiKey)
                setRequestProperty("Content-Type", "text/csv")
                setRequestProperty("X-File-Name", filename)
                setRequestProperty("Accept", "application/json")
            }
            c.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            com.rocketgod.warble.usb.BeastDiag.log("Wardrift upload HTTP $code: ${body.take(300)}")
            when {
                code == 401 || code == 403 -> WdgwApi.Result(false, "Wardrift rejected the API key (HTTP $code)")
                code !in 200..299 -> WdgwApi.Result(false, "Wardrift upload failed (HTTP $code). ${body.take(160)}")
                else -> WdgwApi.Result(true, "Sent to Wardrift")
            }
        } catch (e: Exception) {
            WdgwApi.Result(false, "Wardrift upload error: ${e.message}")
        } finally { c?.disconnect() }
    }
}
