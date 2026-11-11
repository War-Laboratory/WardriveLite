package com.example.wardrive

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.osmdroid.config.Configuration
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), Actions {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var ui: Ui
    private lateinit var screens: List<Screen>
    private lateinit var map: MapScreen
    private val tabViews = mutableListOf<TextView>()
    private lateinit var content: FrameLayout
    private lateinit var title: TextView
    private var tab = 0

    private val tabs = listOf("◉" to "HUD", "⚠" to "ALERTS", "≋" to "DEVICES", "⌖" to "MAP", "⚙" to "SETUP")

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocation()) startScan()
        else Toast.makeText(this, "Location permission is required to scan", Toast.LENGTH_LONG).show()
    }

    private val refresh = object : Runnable {
        override fun run() {
            if (tab != 4) screens[tab].render()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Configuration.getInstance().userAgentValue = packageName
        ui = Ui(this)
        val p = ui.p
        window.setBackgroundDrawable(ColorDrawable(p.bg))
        window.statusBarColor = p.bg
        window.navigationBarColor = p.bg

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.bg) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // Title bar
        val bar = ui.hbox(14, 10)
        title = ui.head("", 20f, p.accent)
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(ui.head("WARDRIVE LITE", 12f, p.dim))
        root.addView(bar, ui.lp(match = true))
        root.addView(ui.divider(), ui.lp(match = true, h = ui.dp(2)))

        map = MapScreen(ui, this)
        screens = listOf(HudScreen(ui, this), AlertsScreen(ui, this), DevicesScreen(ui, this), map, SettingsScreen(ui, this))
        content = FrameLayout(this)
        for (s in screens) content.addView(s.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(content, ui.lp(match = true, h = 0, weight = 1f))

        // Bottom navigation
        val nav = ui.hbox(6, 6)
        nav.background = ui.panel(cut = 14, stripes = 0)
        tabs.forEachIndexed { i, (icon, label) ->
            val t = ui.head("$icon\n$label", 11f, p.dim).apply {
                gravity = Gravity.CENTER
                setLineSpacing(ui.dpf(2), 1f)
                setPadding(0, ui.dp(7), 0, ui.dp(7))
                setOnClickListener { selectTab(i) }
            }
            tabViews += t
            nav.addView(t, ui.lp(w = 0, weight = 1f, left = 2, right = 2))
        }
        root.addView(nav, ui.lp(match = true, left = 6, right = 6, bottom = 4))
        setContentView(root)

        selectTab(intent?.getIntExtra("tab", 0) ?: 0)
        handleAutostart(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra("tab")) selectTab(intent.getIntExtra("tab", 0))
        handleAutostart(intent)
    }

    private fun handleAutostart(i: Intent?) {
        if (i?.getBooleanExtra("autostart", false) == true) {
            i.removeExtra("autostart")
            if (!Store.scanning) toggleScan()
        }
    }

    override fun onResume() {
        super.onResume()
        map.map.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        map.map.onPause()
        handler.removeCallbacks(refresh)
    }

    private fun selectTab(i: Int) {
        tab = i
        val p = ui.p
        screens.forEachIndexed { idx, s -> s.view.visibility = if (idx == i) View.VISIBLE else View.GONE }
        tabViews.forEachIndexed { idx, t ->
            val sel = idx == i
            t.setTextColor(if (sel) p.bg else p.dim)
            t.background = if (sel) ui.panel(stroke = p.accent2, fill = p.accent, cut = 8, strokeW = 1) else null
        }
        title.text = when (i) { 0 -> "// COMMAND HUD"; 1 -> "// THREATS"; 2 -> "// DEVICES"; 3 -> "// MAP"; else -> "// SETUP" }
        screens[i].onShow()
    }

    // ---------------- Actions ----------------

    private fun hasLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun toggleScan() {
        if (Store.scanning) {
            startService(Intent(this, ScanService::class.java).setAction(ScanService.ACTION_STOP))
            handler.postDelayed({ screens[tab].render() }, 300)
            return
        }
        val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            needed += Manifest.permission.BLUETOOTH_SCAN
            needed += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startScan() else permLauncher.launch(missing.toTypedArray())
    }

    private fun startScan() {
        ContextCompat.startForegroundService(this, Intent(this, ScanService::class.java))
        handler.postDelayed({ screens[tab].render() }, 500)
    }

    override fun applyTheme() = recreate()

    override fun share(file: File) {
        Store.flush()
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share WiGLE CSV"))
    }

    override fun showOnMap(s: Sighting) {
        selectTab(3)
        map.focus(s)
    }

    override fun openDetail(s: Sighting) {
        val d = Dialog(this)
        d.window?.setBackgroundDrawable(ColorDrawable(0))
        d.setContentView(detailView(ui, s, onMap = { d.dismiss(); showOnMap(s) }, onClose = { d.dismiss() }))
        d.window?.setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        d.show()
    }
}

/** Device detail card used in the popup. */
fun detailView(ui: Ui, s: Sighting, onMap: () -> Unit, onClose: () -> Unit): View {
    val p = ui.p
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    val a = s.alert
    val col = ui.vbox(18, 16)
    col.background = ui.panel(stroke = if (a != null) ui.sevColor(a.severity) else p.frame, cut = 16, stripes = 4, strokeW = 2)
    col.addView(ui.head(a?.label ?: s.name.ifEmpty { "(no name)" }, 20f, if (a != null) ui.sevColor(a.severity) else p.accent))
    if (a != null) {
        val sev = when (a.severity) { 3 -> "HIGH"; 2 -> "MEDIUM"; else -> "LOW" }
        col.addView(ui.mono("${a.cat.label.uppercase(Locale.US)} · $sev CONFIDENCE", 12f, p.dim), ui.lp(top = 4))
        if (a.why.isNotEmpty()) col.addView(ui.text("Why: ${a.why}", 13f, p.text), ui.lp(top = 6))
    }
    if (s.following) col.addView(ui.head("⚠ This tracker has stayed with you for 10+ min and 500+ m.", 14f, p.danger), ui.lp(top = 8))
    col.addView(ui.divider(), ui.lp(match = true, h = ui.dp(1), top = 12, bottom = 10))
    val rows = mutableListOf(
        "NAME" to s.name.ifEmpty { "—" },
        if (s.kind == Kind.CELL) "CELL ID" to s.key else "MAC" to s.key,
        "TYPE" to s.type,
        "SIGNAL" to "${s.rssi} dBm now · best ${s.bestRssi} dBm",
        "SEEN" to "${fmt.format(Date(s.firstSeen))} → ${fmt.format(Date(s.lastSeen))} · ${s.hits}×",
    )
    if (s.channel != 0) rows += "CHANNEL" to "${s.channel}" + if (s.freq != 0) " · ${s.freq} MHz" else ""
    if (s.auth.isNotEmpty()) rows += "SECURITY" to s.auth
    if (s.mfgrId.isNotEmpty()) rows += "MFG ID" to "0x%04X".format(s.mfgrId.toIntOrNull() ?: 0)
    rows += "LOCATION" to if (s.lat != 0.0) "%.5f, %.5f".format(Locale.US, s.lat, s.lon) else "no GPS fix yet"
    for ((k, v) in rows) {
        val r = ui.hbox(0, 3)
        r.addView(ui.head(k, 11f, p.accent2), ui.lp(w = ui.dp(84)))
        r.addView(ui.mono(v, 13f, p.text), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(r)
    }
    val btns = ui.hbox()
    btns.addView(ui.button("⌖ Show on map") { onMap() }, ui.lp(w = 0, weight = 1f, right = 6))
    btns.addView(ui.button("Close") { onClose() }, ui.lp(w = 0, weight = 1f))
    col.addView(btns, ui.lp(match = true, top = 14))
    return ScrollView(ui.ctx).apply { addView(col) }
}
