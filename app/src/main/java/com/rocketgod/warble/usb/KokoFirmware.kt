package com.rocketgod.warble.usb

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Firmware for justcallmekoko's ESP32 Dual-Band Wardriver (ESP32-C5, MIT licence).
 * Latest: read the release's firmware-manifest.json (offsets + SHA-256 for every file).
 * Offline: a v2.2.0 build bundled in the APK (from the repo's C5_Py_Flasher folder).
 */
object KokoFirmware {
    const val REPO = "justcallmekoko/ESP32DualBandWardriver"
    const val BUNDLED_VERSION = "v2.2.0"

    data class Seg(val role: String, val offset: Int, val size: Int, val sha256: String, val fileName: String)
    data class Target(val id: String, val name: String, val flashSize: Int, val factory: List<Seg>, val update: List<Seg>)
    data class Release(val version: String, val targets: List<Target>, val urls: Map<String, String>)

    private fun get(url: String, accept: String = "application/octet-stream"): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 60_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "WardriveLite-Flasher")
        c.setRequestProperty("Accept", accept)
        try {
            if (c.responseCode !in 200..299) throw EspFlasher.FlashError("Download failed: HTTP ${c.responseCode} for ${url.substringAfterLast('/')}")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    /** Latest release that carries an installer manifest. Blocking. */
    fun latest(): Release {
        val rel = JSONObject(String(get("https://api.github.com/repos/$REPO/releases/latest", "application/vnd.github+json")))
        val urls = HashMap<String, String>()
        val assets = rel.optJSONArray("assets")
        if (assets != null) for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            urls[a.getString("name")] = a.getString("browser_download_url")
        }
        val mUrl = urls["firmware-manifest.json"]
            ?: throw EspFlasher.FlashError("Latest release (${rel.optString("tag_name")}) has no installer manifest — use the bundled build")
        val m = JSONObject(String(get(mUrl)))
        val targets = ArrayList<Target>()
        val ta = m.getJSONArray("targets")
        for (i in 0 until ta.length()) {
            val t = ta.getJSONObject(i)
            val fl = t.getJSONObject("flash")
            fun segs(plan: String): List<Seg> {
                val arr = fl.getJSONObject(plan).getJSONArray("segments")
                return (0 until arr.length()).map {
                    val s = arr.getJSONObject(it)
                    Seg(s.getString("role"), s.getInt("offset"), s.getInt("size"), s.getString("sha256").lowercase(), s.getString("fileName"))
                }
            }
            targets += Target(t.getString("id"), t.getString("displayName"), fl.getInt("sizeBytes"), segs("factory"), segs("update"))
        }
        return Release(m.optString("version", rel.optString("tag_name")), targets, urls)
    }

    fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Downloads every segment of [plan], checking size and SHA-256 against the manifest. */
    fun fetch(rel: Release, plan: List<Seg>, log: (String) -> Unit): List<EspFlasher.Segment> = plan.map { s ->
        val url = rel.urls[s.fileName] ?: throw EspFlasher.FlashError("Release is missing ${s.fileName}")
        log("Downloading ${s.fileName} (${s.size / 1024} KB)…")
        val b = get(url)
        if (b.size != s.size || sha256(b) != s.sha256) throw EspFlasher.FlashError("Checksum mismatch for ${s.fileName} — not flashing")
        EspFlasher.Segment(s.offset, b, "${s.role} (${s.fileName})")
    }

    /** The bundled v2.2.0 DevKitC-1 build. */
    fun bundled(ctx: Context): List<EspFlasher.Segment> {
        fun a(n: String) = ctx.assets.open("koko/$n").use { it.readBytes() }
        return listOf(
            EspFlasher.Segment(0x2000, a("bootloader.bin"), "bootloader"),
            EspFlasher.Segment(0x8000, a("partitions.bin"), "partition table"),
            EspFlasher.Segment(0x10000, a("app.bin"), "application $BUNDLED_VERSION"),
        )
    }

    /**
     * A full install must also clear the OTA-data partition, or the bootloader may keep
     * booting an older app from the other OTA slot. Returns a 0xFF segment for it, if any.
     */
    fun otadataWipe(segments: List<EspFlasher.Segment>): EspFlasher.Segment? {
        val pt = segments.firstOrNull { it.offset == 0x8000 }?.data ?: return null
        var o = 0
        while (o + 32 <= pt.size) {
            if ((pt[o].toInt() and 0xFF) != 0xAA || (pt[o + 1].toInt() and 0xFF) != 0x50) break
            val type = pt[o + 2].toInt() and 0xFF
            val sub = pt[o + 3].toInt() and 0xFF
            fun u32(i: Int) = (pt[i].toInt() and 0xFF) or ((pt[i + 1].toInt() and 0xFF) shl 8) or
                ((pt[i + 2].toInt() and 0xFF) shl 16) or ((pt[i + 3].toInt() and 0xFF) shl 24)
            if (type == 1 && sub == 0) {
                val off = u32(o + 4); val size = u32(o + 8)
                if (segments.any { it.offset == off }) return null
                if (size in 1..0x10000) return EspFlasher.Segment(off, ByteArray(size) { 0xFF.toByte() }, "OTA data reset")
            }
            o += 32
        }
        return null
    }
}
