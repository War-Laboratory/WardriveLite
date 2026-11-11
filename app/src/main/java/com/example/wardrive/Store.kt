package com.example.wardrive

import android.content.Context
import android.location.Location
import android.os.Build
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Shared state between the scanning service and the UI, plus the WiGLE CSV log. */
object Store {
    val sightings = ConcurrentHashMap<String, Sighting>()

    @Volatile var lat = 0.0
    @Volatile var lon = 0.0
    @Volatile var alt = 0.0
    @Volatile var acc = 0f
    @Volatile var hasFix = false
    @Volatile var scanning = false
    @Volatile var onNewAlert: ((Sighting) -> Unit)? = null
    @Volatile var startTime = 0L
    @Volatile var rowsWritten = 0

    /** A tracker is "following" if seen > FOLLOW_METERS from first sighting after FOLLOW_MS. */
    private const val FOLLOW_METERS = 500f
    private const val FOLLOW_MS = 10 * 60 * 1000L
    private const val ROW_INTERVAL_MS = 30_000L

    private var writer: BufferedWriter? = null
    var currentFile: File? = null
        private set
    private val lastWritten = HashMap<String, Long>()
    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun logDir(ctx: Context): File = ctx.getExternalFilesDir(null) ?: ctx.filesDir

    @Synchronized
    fun openLog(ctx: Context) {
        closeLog()
        val name = "wardrive_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
        val f = File(logDir(ctx), name)
        val w = f.bufferedWriter()
        fun c(s: String) = s.replace(",", " ")
        w.write(
            "WigleWifi-1.6,appRelease=1.0,model=${c(Build.MODEL)},release=${c(Build.VERSION.RELEASE)}," +
                "device=${c(Build.DEVICE)},display=${c(Build.DISPLAY)},board=${c(Build.BOARD)}," +
                "brand=${c(Build.BRAND)},star=Sol,body=3,subBody=0\n"
        )
        w.write("MAC,SSID,AuthMode,FirstSeen,Channel,Frequency,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,RCOIs,MfgrId,Type\n")
        writer = w
        currentFile = f
        lastWritten.clear()
        rowsWritten = 0
        startTime = System.currentTimeMillis()
    }

    @Synchronized
    fun flush() {
        try { writer?.flush() } catch (_: Exception) {}
    }

    @Synchronized
    fun closeLog() {
        try { writer?.flush(); writer?.close() } catch (_: Exception) {}
        writer = null
    }

    fun observe(o: Obs) {
        val now = System.currentTimeMillis()
        val s = sightings.getOrPut(o.key) {
            Sighting(o.key, o.kind, o.name, o.rssi, o.channel, o.freq, o.auth, o.type, now).also {
                it.mfgrId = o.mfgData.keys.firstOrNull()?.toString() ?: ""
            }
        }
        var fire = false
        synchronized(s) {
            if (o.name.isNotEmpty()) s.name = o.name
            s.rssi = o.rssi
            s.lastSeen = now
            s.hits++
            if (o.channel != 0) s.channel = o.channel
            if (hasFix) {
                if (s.lat == 0.0 || o.rssi >= s.bestRssi) { s.bestRssi = o.rssi; s.lat = lat; s.lon = lon }
                if (s.firstFixTime == 0L) { s.firstLat = lat; s.firstLon = lon; s.firstFixTime = now }
            }
            if (s.alert == null) {
                val a = Detector.check(o)
                if (a != null && Prefs.catEnabled(a.cat)) { s.alert = a; fire = true }
            }
            if (s.alert?.cat == Cat.TRACKER && !s.following && hasFix && s.firstFixTime != 0L &&
                now - s.firstFixTime > FOLLOW_MS && distance(s.firstLat, s.firstLon, lat, lon) > FOLLOW_METERS
            ) {
                s.following = true
                s.alert = s.alert!!.copy(label = s.alert!!.label + " — FOLLOWING YOU", severity = 3)
                fire = true
            }
        }
        if (fire) onNewAlert?.invoke(s)
        writeRow(s, o, now)
    }

    @Synchronized
    private fun writeRow(s: Sighting, o: Obs, now: Long) {
        val w = writer ?: return
        if (!hasFix) return
        val last = lastWritten[o.key] ?: 0L
        if (now - last < ROW_INTERVAL_MS) return
        lastWritten[o.key] = now
        val row = listOf(
            o.key, csv(o.name), csv(o.auth), tsFmt.format(Date(s.firstSeen)),
            o.channel.toString(), o.freq.toString(), o.rssi.toString(),
            "%.7f".format(Locale.US, lat), "%.7f".format(Locale.US, lon),
            "%.1f".format(Locale.US, alt), "%.1f".format(Locale.US, acc),
            "", s.mfgrId, o.type
        ).joinToString(",")
        try { w.write(row); w.write("\n"); rowsWritten++ } catch (_: Exception) {}
    }

    /** All CSV logs, newest first. */
    fun sessions(ctx: Context): List<File> =
        logDir(ctx).listFiles { f -> f.name.endsWith(".csv") }?.sortedByDescending { it.lastModified() } ?: emptyList()

    private fun csv(v: String): String =
        if (v.contains(',') || v.contains('"') || v.contains('\n'))
            "\"" + v.replace("\"", "\"\"").replace("\n", " ") + "\"" else v

    private fun distance(a: Double, b: Double, c: Double, d: Double): Float {
        val out = FloatArray(1)
        Location.distanceBetween(a, b, c, d, out)
        return out[0]
    }
}
