package com.rocketgod.warble.usb

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Wardrive Lite (SHOK3R fork): DIY Biscuit firmware (CodeHedge), for XIAO ESP32-C5 and
 * other C5 boards. Fetched at flash time from CodeHedge's own public web-flasher files
 * (the same images their browser flasher writes) — not rebundled into this app.
 *
 * NOTE: this flashes real Biscuit firmware onto the board. The board then works inside the
 * Biscuit ecosystem (its DIY wardriver / its node + a Biscuit manager). Biscuit's own
 * link protocol is not published, so this SHOK3R app does not read a Biscuit board live —
 * use the Biscuit app/manager for that, or flash streaming firmware (Marauder) instead.
 */
object BiscuitFirmware {
    private const val BASE = "https://raw.githubusercontent.com/CodeHedge/Adafruit_WebSerial_ESPTool/main/"
    private const val MANIFEST_URL = BASE + "resources/BISCUIT/CURRENT/manifest.json"

    data class Dev(
        val id: String, val name: String, val group: String, val version: String,
        val offsets: List<Int>, val bootloader: String, val partitions: String, val firmware: String,
    )

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 60_000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "WardriveLite-Flasher")
        try {
            if (c.responseCode !in 200..299) throw EspFlasher.FlashError("Download failed: HTTP ${c.responseCode} for ${url.substringAfterLast('/')}")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    /** C5 devices from the Biscuit manifest. Blocking. */
    fun devices(): List<Dev> {
        val m = JSONObject(String(get(MANIFEST_URL)))
        val arr = m.getJSONArray("devices")
        val out = ArrayList<Dev>()
        for (i in 0 until arr.length()) {
            val d = arr.getJSONObject(i)
            if (!d.optString("chip").contains("C5", true)) continue
            val offs = d.getJSONArray("offsets")
            val files = d.getJSONObject("files")
            out.add(Dev(
                d.getString("id"), d.getString("name"), d.optString("group"), d.optString("version"),
                (0 until offs.length()).map { offs.getInt(it) },
                files.getString("bootloader"), files.getString("partitions"), files.getString("firmware"),
            ))
        }
        return out
    }

    /** Download the device's three images and map them to their flash offsets. Blocking. */
    fun fetch(dev: Dev, log: (String) -> Unit): List<EspFlasher.Segment> {
        if (dev.offsets.size < 3) throw EspFlasher.FlashError("Biscuit manifest has unexpected offsets for ${dev.name}")
        val parts = listOf(
            Triple("bootloader", dev.offsets[0], dev.bootloader),
            Triple("partition table", dev.offsets[1], dev.partitions),
            Triple("application", dev.offsets[2], dev.firmware),
        )
        return parts.map { (role, off, path) ->
            log("Downloading $role …")
            val b = get(BASE + path)
            EspFlasher.Segment(off, b, "$role (${path.substringAfterLast('/')})")
        }
    }
}
