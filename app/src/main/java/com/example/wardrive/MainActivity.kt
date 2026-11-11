package com.example.wardrive

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var startBtn: Button
    private lateinit var alertList: ListView
    private lateinit var allList: ListView
    private lateinit var map: MapView
    private lateinit var alertAdapter: ArrayAdapter<String>
    private lateinit var allAdapter: ArrayAdapter<String>
    private val tabs = mutableListOf<Button>()
    private var tab = 0
    private var mapCentered = false
    private var lastMapCount = -1

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocation()) startScan()
        else Toast.makeText(this, "Location permission is required to scan", Toast.LENGTH_LONG).show()
    }

    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName

        val pad = dp(8)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E1116"))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left + pad, bars.top + pad, bars.right + pad, bars.bottom + pad)
            insets
        }

        val title = TextView(this).apply {
            text = "WARDRIVE LITE"
            setTextColor(Color.parseColor("#39FF88"))
            textSize = 20f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
        }
        status = TextView(this).apply {
            setTextColor(Color.parseColor("#B8C2CC"))
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(0, pad / 2, 0, pad)
        }

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startBtn = Button(this).apply { setOnClickListener { toggleScan() } }
        val exportBtn = Button(this).apply { text = "Export CSV"; setOnClickListener { exportCsv() } }
        val clearBtn = Button(this).apply {
            text = "Clear"
            setOnClickListener { Store.sightings.clear(); lastMapCount = -1; render() }
        }
        listOf(startBtn, exportBtn, clearBtn).forEach {
            actions.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Alerts", "All", "Map").forEachIndexed { i, label ->
            val b = Button(this).apply {
                text = label
                setOnClickListener { selectTab(i) }
            }
            tabs += b
            tabRow.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        alertAdapter = monoAdapter()
        allAdapter = monoAdapter()
        alertList = ListView(this).apply { adapter = alertAdapter; emptyView = null }
        allList = ListView(this).apply { adapter = allAdapter }
        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)
        }

        val content = FrameLayout(this)
        val mp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        content.addView(alertList, mp)
        content.addView(allList, mp)
        content.addView(map, mp)

        root.addView(title)
        root.addView(status)
        root.addView(actions)
        root.addView(tabRow)
        root.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        selectTab(0)
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        handler.removeCallbacks(refresh)
    }

    // ---------------- Scanning control ----------------

    private fun hasLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun toggleScan() {
        if (Store.scanning) {
            startService(Intent(this, ScanService::class.java).setAction(ScanService.ACTION_STOP))
            handler.postDelayed({ render() }, 300)
            return
        }
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= 31) {
            needed += Manifest.permission.BLUETOOTH_SCAN
            needed += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startScan() else permLauncher.launch(missing.toTypedArray())
    }

    private fun startScan() {
        ContextCompat.startForegroundService(this, Intent(this, ScanService::class.java))
        handler.postDelayed({ render() }, 500)
    }

    private fun exportCsv() {
        Store.flush()
        val file = Store.currentFile?.takeIf { it.exists() }
            ?: Store.logDir(this).listFiles { f -> f.name.endsWith(".csv") }?.maxByOrNull { it.lastModified() }
        if (file == null) {
            Toast.makeText(this, "No log yet: start a scan first", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share WiGLE CSV"))
    }

    // ---------------- UI ----------------

    private fun selectTab(i: Int) {
        tab = i
        alertList.visibility = if (i == 0) View.VISIBLE else View.GONE
        allList.visibility = if (i == 1) View.VISIBLE else View.GONE
        map.visibility = if (i == 2) View.VISIBLE else View.GONE
        tabs.forEachIndexed { idx, b -> b.alpha = if (idx == i) 1f else 0.5f }
        lastMapCount = -1
        render()
    }

    private fun render() {
        startBtn.text = if (Store.scanning) "■ Stop" else "▶ Start"
        val all = Store.sightings.values.toList()
        val w = all.count { it.kind == Kind.WIFI }
        val b = all.count { it.kind == Kind.BLE }
        val c = all.count { it.kind == Kind.CELL }
        val alerts = all.filter { it.alert != null }
        val gps = if (Store.hasFix) "%.5f, %.5f ±%dm".format(Store.lat, Store.lon, Store.acc.toInt()) else "waiting for GPS…"
        status.text = "WiFi $w   BLE $b   Cell $c   Alerts ${alerts.size}\n$gps"

        val now = System.currentTimeMillis()
        when (tab) {
            0 -> {
                val rows = alerts
                    .sortedWith(compareByDescending<Sighting> { it.alert!!.severity }.thenByDescending { it.lastSeen })
                    .map { s ->
                        val a = s.alert!!
                        val mark = when (a.severity) { 3 -> "‼"; 2 -> "!"; else -> "·" }
                        "$mark ${a.label}\n   ${s.name.ifEmpty { "(no name)" }}  ${s.key}\n   ${s.rssi} dBm · seen ${ago(now - s.lastSeen)} ago"
                    }
                alertAdapter.clear()
                alertAdapter.addAll(if (rows.isEmpty()) listOf("No detections yet.") else rows)
            }
            1 -> {
                val rows = all.sortedByDescending { it.rssi }.take(400).map { s ->
                    val tag = when (s.kind) { Kind.WIFI -> "WIFI"; Kind.BLE -> "BLE "; Kind.CELL -> s.type.padEnd(4) }
                    val ch = if (s.channel != 0) " ch${s.channel}" else ""
                    "[$tag] ${s.name.ifEmpty { "(hidden)" }}\n       ${s.key}  ${s.rssi}dBm$ch${if (s.alert != null) "  ⚠" else ""}"
                }
                allAdapter.clear()
                allAdapter.addAll(rows)
            }
            2 -> renderMap(all)
        }
    }

    private fun renderMap(all: List<Sighting>) {
        if (Store.hasFix && !mapCentered) {
            map.controller.setCenter(GeoPoint(Store.lat, Store.lon))
            mapCentered = true
        }
        val located = all.filter { it.lat != 0.0 }
        if (located.size == lastMapCount) return
        lastMapCount = located.size
        map.overlays.clear()
        // Alerts always; plus the strongest 300 other devices to keep the map fast.
        val shown = located.filter { it.alert != null } +
            located.filter { it.alert == null }.sortedByDescending { it.bestRssi }.take(300)
        for (s in shown) {
            val m = Marker(map)
            m.position = GeoPoint(s.lat, s.lon)
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            m.title = (s.alert?.label?.let { "⚠ $it\n" } ?: "") + "${s.type}: ${s.name.ifEmpty { s.key }}"
            m.snippet = "${s.key}  best ${s.bestRssi} dBm"
            map.overlays.add(m)
        }
        if (Store.hasFix) {
            val me = Marker(map)
            me.position = GeoPoint(Store.lat, Store.lon)
            me.title = "You"
            me.icon = ContextCompat.getDrawable(this, android.R.drawable.ic_menu_mylocation)
            map.overlays.add(me)
        }
        map.invalidate()
    }

    private fun monoAdapter() = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf()) {
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val v = super.getView(position, convertView, parent) as TextView
            v.typeface = Typeface.MONOSPACE
            v.textSize = 12f
            v.gravity = Gravity.START
            val item = getItem(position) ?: ""
            v.setTextColor(
                when {
                    item.startsWith("‼") -> Color.parseColor("#FF5A5A")
                    item.startsWith("!") -> Color.parseColor("#FFB347")
                    item.contains("⚠") -> Color.parseColor("#FFB347")
                    else -> Color.parseColor("#D7DEE5")
                }
            )
            return v
        }
    }

    private fun ago(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m"
            else -> "${s / 3600}h"
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
