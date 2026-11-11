package com.example.wardrive

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What screens can ask the activity to do. */
interface Actions {
    fun toggleScan()
    fun openDetail(s: Sighting)
    fun showOnMap(s: Sighting)
    fun applyTheme()
    fun share(file: java.io.File)
}

interface Screen {
    val view: View
    fun render()
    fun onShow() = render()
}

/** One row for a device or alert: severity bar, label, name / MAC, signal and age. */
fun deviceRow(ui: Ui, s: Sighting, now: Long): View {
    val p = ui.p
    val a = s.alert
    val row = ui.hbox(0, 4)
    val bar = View(ui.ctx).apply { setBackgroundColor(if (a != null) ui.sevColor(a.severity) else Ui.withAlpha(p.frame, 120)) }
    row.addView(bar, LinearLayout.LayoutParams(ui.dp(4), ViewGroup.LayoutParams.MATCH_PARENT))
    val col = ui.vbox(10, 8)
    val top = ui.hbox()
    val tag = when (s.kind) { Kind.WIFI -> "WIFI"; Kind.BLE -> "BLE"; Kind.CELL -> s.type }
    val title = if (a != null) a.label else s.name.ifEmpty { if (s.kind == Kind.WIFI) "(hidden network)" else "(no name)" }
    top.addView(ui.head(title, 15f, if (a != null) ui.sevColor(a.severity) else p.text).apply { maxLines = 1 },
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    if (s.following) top.addView(ui.head(" FOLLOWING ", 11f, p.bg).apply {
        background = ui.panel(stroke = p.danger, fill = p.danger, cut = 4, strokeW = 1)
    }, ui.lp(left = 6))
    top.addView(ui.mono(" $tag", 11f, p.dim))
    col.addView(top)
    val second = if (a != null) "${s.name.ifEmpty { "(no name)" }}  ${s.key}" else s.key
    col.addView(ui.mono(second, 12f, p.dim).apply { maxLines = 1 }, ui.lp(top = 3))
    val ch = if (s.channel != 0) " · ch ${s.channel}" else ""
    col.addView(ui.mono("${s.rssi} dBm$ch · ${Ui.ago(now - s.lastSeen)} ago · ${s.hits}×", 12f, p.text), ui.lp(top = 3))
    row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    row.background = ui.panel(stroke = Ui.withAlpha(p.frame, 150), fill = p.panel, cut = 8, strokeW = 1)
    return row
}

class SightingAdapter(private val ui: Ui) : BaseAdapter() {
    var items: List<Sighting> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }
    var empty = "Nothing yet."

    override fun getCount() = maxOf(1, items.size)
    override fun getItem(position: Int): Any? = items.getOrNull(position)
    override fun getItemId(position: Int) = position.toLong()
    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val s = items.getOrNull(position) ?: return ui.text(empty, 14f, ui.p.dim).apply {
            setPadding(ui.dp(12), ui.dp(24), ui.dp(12), ui.dp(24)); gravity = Gravity.CENTER
        }
        val v = deviceRow(ui, s, System.currentTimeMillis())
        val wrap = FrameLayout(ui.ctx)
        wrap.setPadding(0, ui.dp(3), 0, ui.dp(3))
        wrap.addView(v)
        return wrap
    }
}

private fun listView(ui: Ui, adapter: SightingAdapter, actions: Actions) = ListView(ui.ctx).apply {
    this.adapter = adapter
    divider = null
    dividerHeight = 0
    selector = android.graphics.drawable.ColorDrawable(0)
    setOnItemClickListener { _, _, pos, _ -> adapter.items.getOrNull(pos)?.let { actions.openDetail(it) } }
}

fun alertsSorted(): List<Sighting> = Store.sightings.values
    .filter { it.alert != null }
    .sortedWith(compareByDescending<Sighting> { it.following }.thenByDescending { it.alert!!.severity }.thenByDescending { it.lastSeen })

// ======================================================================================
// HUD
// ======================================================================================

class HudScreen(private val ui: Ui, private val actions: Actions) : Screen {
    private val p = ui.p
    private val scanBtn = ui.button("▶ START SCAN", primary = true) { actions.toggleScan() }
    private val status = ui.mono("", 12f, p.dim)
    private val nums = HashMap<String, TextView>()
    private val radar = RadarView(ui.ctx)
    private val catRow = ui.hbox()
    private val spectrum = SpectrumView(ui.ctx)
    private val latest = ui.vbox()
    override val view: View

    init {
        val col = ui.vbox(12, 10)
        col.addView(ImageView(ui.ctx).apply {
            setImageResource(R.drawable.art_banner)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, ui.lp(match = true))
        col.addView(scanBtn, ui.lp(match = true, top = 10))
        col.addView(status, ui.lp(match = true, top = 8))

        val grid = ui.vbox()
        val r1 = ui.hbox(); val r2 = ui.hbox()
        listOf("WIFI" to r1, "BLE" to r1, "CELL" to r2, "THREATS" to r2).forEachIndexed { i, (k, row) ->
            val tile = ui.vbox(12, 10)
            tile.background = ui.panel(stroke = if (k == "THREATS") p.accent else p.frame, cut = 10)
            tile.addView(ui.head(k, 12f, p.dim))
            val n = ui.mono("0", 34f, if (k == "THREATS") p.accent else p.text)
            nums[k] = n
            tile.addView(n, ui.lp(top = 2))
            row.addView(tile, ui.lp(w = 0, weight = 1f, left = if (i % 2 == 1) 5 else 0, right = if (i % 2 == 0) 5 else 0))
        }
        grid.addView(r1, ui.lp(match = true))
        grid.addView(r2, ui.lp(match = true, top = 10))
        col.addView(grid, ui.lp(match = true, top = 10))

        val (radarCard, radarBody) = ui.card("// Threat radar")
        radarBody.addView(radar, ui.lp(match = true))
        radarBody.addView(ui.chipRow(listOf(catRow)), ui.lp(match = true, top = 8))
        col.addView(radarCard, ui.lp(match = true, top = 12))

        val (specCard, specBody) = ui.card("// Spectrum", stripes = 2)
        specBody.addView(spectrum, ui.lp(match = true))
        col.addView(specCard, ui.lp(match = true, top = 12))

        val (lastCard, lastBody) = ui.card("// Latest threats", stripes = 4)
        lastBody.addView(latest, ui.lp(match = true))
        col.addView(lastCard, ui.lp(match = true, top = 12, bottom = 12))

        view = ScrollView(ui.ctx).apply { isVerticalScrollBarEnabled = false; addView(col) }
    }

    override fun render() {
        val now = System.currentTimeMillis()
        scanBtn.text = if (Store.scanning) "■ STOP SCAN" else "▶ START SCAN"
        val all = Store.sightings.values.toList()
        val alerts = alertsSorted()
        nums["WIFI"]?.text = all.count { it.kind == Kind.WIFI }.toString()
        nums["BLE"]?.text = all.count { it.kind == Kind.BLE }.toString()
        nums["CELL"]?.text = all.count { it.kind == Kind.CELL }.toString()
        nums["THREATS"]?.text = alerts.size.toString()
        val live = if (Store.scanning) "● LIVE ${Ui.duration(now - Store.startTime)}" else "○ IDLE"
        val gps = if (Store.hasFix) "GPS ±${Store.acc.toInt()}m" else "NO GPS FIX"
        status.text = "$live · $gps · ${Store.rowsWritten} rows logged"
        status.setTextColor(if (Store.scanning) p.accent2 else p.dim)

        radar.scanning = Store.scanning
        radar.blips = alerts.take(40).map { it to it.alert!! }
        radar.invalidate()

        catRow.removeAllViews()
        for (c in Cat.entries) {
            val n = alerts.count { it.alert!!.cat == c }
            val chip = ui.head("${c.tag} $n", 11f, if (n > 0) p.bg else p.dim)
            chip.setPadding(ui.dp(9), ui.dp(5), ui.dp(9), ui.dp(5))
            chip.background = if (n > 0) ui.panel(stroke = p.accent, fill = p.accent, cut = 5, strokeW = 1)
            else ui.panel(stroke = p.frame, fill = p.bg, cut = 5, strokeW = 1)
            catRow.addView(chip, ui.lp(right = 5))
        }

        spectrum.setData(all)

        latest.removeAllViews()
        if (alerts.isEmpty()) latest.addView(ui.text("No threats detected. Start a scan and go for a drive.", 13f, p.dim))
        for (s in alerts.take(4)) {
            val r = deviceRow(ui, s, now)
            r.setOnClickListener { actions.openDetail(s) }
            latest.addView(r, ui.lp(match = true, bottom = 6))
        }
    }
}

// ======================================================================================
// Alerts
// ======================================================================================

class AlertsScreen(private val ui: Ui, actions: Actions) : Screen {
    private var filter: Cat? = null
    private val adapter = SightingAdapter(ui).apply { empty = "No threats detected yet." }
    private val chips = ui.hbox()
    private val count = ui.mono("", 12f, ui.p.dim)
    override val view: View

    init {
        val col = ui.vbox(12, 8)
        col.addView(ui.chipRow(listOf(chips)), ui.lp(match = true))
        col.addView(count, ui.lp(top = 8, bottom = 4))
        col.addView(listView(ui, adapter, actions), ui.lp(match = true, h = 0, weight = 1f))
        view = col
        buildChips()
    }

    private fun buildChips() {
        chips.removeAllViews()
        chips.addView(ui.chip("All", filter == null) { filter = null; buildChips(); render() }, ui.lp(right = 6))
        for (c in Cat.entries) chips.addView(ui.chip(c.tag, filter == c) { filter = c; buildChips(); render() }, ui.lp(right = 6))
    }

    override fun render() {
        val list = alertsSorted().filter { filter == null || it.alert!!.cat == filter }
        count.text = "${list.size} DETECTION${if (list.size == 1) "" else "S"}" + (filter?.let { " · ${it.label.uppercase(Locale.US)}" } ?: "")
        adapter.items = list
    }
}

// ======================================================================================
// Devices
// ======================================================================================

class DevicesScreen(private val ui: Ui, actions: Actions) : Screen {
    private var kind: Kind? = null
    private var sort = "SIGNAL"
    private var query = ""
    private val adapter = SightingAdapter(ui).apply { empty = "No devices yet. Start a scan." }
    private val chips = ui.hbox()
    private val count = ui.mono("", 12f, ui.p.dim)
    override val view: View

    init {
        val col = ui.vbox(12, 8)
        col.addView(ui.chipRow(listOf(chips)), ui.lp(match = true))
        val search = ui.field("Search name or MAC", "")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim() ?: ""; render() }
        })
        col.addView(search, ui.lp(match = true, top = 8))
        col.addView(count, ui.lp(top = 8, bottom = 4))
        col.addView(listView(ui, adapter, actions), ui.lp(match = true, h = 0, weight = 1f))
        view = col
        buildChips()
    }

    private fun buildChips() {
        chips.removeAllViews()
        chips.addView(ui.chip("All", kind == null) { kind = null; buildChips(); render() }, ui.lp(right = 6))
        for (k in Kind.entries) chips.addView(ui.chip(k.name, kind == k) { kind = k; buildChips(); render() }, ui.lp(right = 6))
        chips.addView(View(ui.ctx), ui.lp(w = ui.dp(10)))
        for (s in listOf("SIGNAL", "RECENT", "NAME"))
            chips.addView(ui.chip("↓ $s", sort == s) { sort = s; buildChips(); render() }, ui.lp(right = 6))
    }

    override fun render() {
        val q = query.lowercase(Locale.US)
        var list = Store.sightings.values.filter {
            (kind == null || it.kind == kind) &&
                (q.isEmpty() || it.name.lowercase(Locale.US).contains(q) || it.key.lowercase(Locale.US).contains(q))
        }
        list = when (sort) {
            "RECENT" -> list.sortedByDescending { it.lastSeen }
            "NAME" -> list.sortedBy { it.name.ifEmpty { "~" }.lowercase(Locale.US) }
            else -> list.sortedByDescending { it.rssi }
        }
        count.text = "${list.size} DEVICES" + if (list.size > 500) " · SHOWING 500" else ""
        adapter.items = list.take(500)
    }
}

// ======================================================================================
// Map
// ======================================================================================

/** Round marker with a dark outline, sized in dp. */
class DotDrawable(private val color: Int, private val outline: Int, private val sizePx: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val r = sizePx / 2f
        val cx = bounds.exactCenterX(); val cy = bounds.exactCenterY()
        paint.color = outline; canvas.drawCircle(cx, cy, r, paint)
        paint.color = color; canvas.drawCircle(cx, cy, r * 0.68f, paint)
    }
    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

class MapScreen(private val ui: Ui, private val actions: Actions) : Screen {
    private val p = ui.p
    val map: MapView = MapView(ui.ctx)
    private var alertsOnly = false
    private var centered = false
    private var lastKey = ""
    private val toggle: TextView
    override val view: View

    init {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(16.0)
        map.overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
        map.overlayManager.tilesOverlay.setLoadingBackgroundColor(p.bg)
        map.overlayManager.tilesOverlay.setLoadingLineColor(p.frame)

        val frame = FrameLayout(ui.ctx)
        frame.addView(map, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val bar = ui.hbox(10, 10)
        toggle = ui.chip("Alerts only", false) {}
        toggle.setOnClickListener { alertsOnly = !alertsOnly; restyle(); lastKey = ""; render() }
        bar.addView(ui.button("◎ Center") { centered = false; render() }, ui.lp(right = 8))
        bar.addView(toggle)
        frame.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        val legend = ui.hbox(10, 6)
        legend.background = ui.panel(cut = 6, strokeW = 1)
        for ((label, c) in listOf("HIGH" to p.danger, "MED" to p.accent, "LOW" to p.accent2, "WIFI/BLE" to p.frame)) {
            legend.addView(View(ui.ctx).apply { background = DotDrawable(c, p.bg, ui.dp(12)) }, ui.lp(w = ui.dp(12), h = ui.dp(12), right = 4))
            legend.addView(ui.mono(label, 11f, p.text), ui.lp(right = 10))
        }
        frame.addView(legend, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(ui.dp(10), 0, 0, ui.dp(10))
        })
        view = frame
    }

    private fun restyle() {
        toggle.setTextColor(if (alertsOnly) p.bg else p.dim)
        toggle.background = if (alertsOnly) ui.panel(stroke = p.accent2, fill = p.accent2, cut = 6, strokeW = 1)
        else ui.panel(stroke = p.frame, fill = p.panel, cut = 6, strokeW = 1)
    }

    fun focus(s: Sighting) {
        if (s.lat == 0.0) return
        centered = true
        map.controller.animateTo(GeoPoint(s.lat, s.lon))
        map.controller.setZoom(18.0)
    }

    override fun render() {
        if (Store.hasFix && !centered) {
            map.controller.setCenter(GeoPoint(Store.lat, Store.lon))
            centered = true
        }
        val located = Store.sightings.values.filter { it.lat != 0.0 && (!alertsOnly || it.alert != null) }
        val key = "${located.size}:${located.count { it.alert != null }}:${Store.lat}:${Store.lon}"
        if (key == lastKey) return
        lastKey = key
        map.overlays.clear()
        val shown = located.filter { it.alert != null } +
            located.filter { it.alert == null }.sortedByDescending { it.bestRssi }.take(300)
        for (s in shown.reversed()) {
            val m = Marker(map)
            m.position = GeoPoint(s.lat, s.lon)
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            val a = s.alert
            m.icon = if (a != null) DotDrawable(ui.sevColor(a.severity), p.bg, ui.dp(18)) else DotDrawable(p.frame, p.bg, ui.dp(10))
            m.setOnMarkerClickListener { _, _ -> actions.openDetail(s); true }
            map.overlays.add(m)
        }
        if (Store.hasFix) {
            val me = Marker(map)
            me.position = GeoPoint(Store.lat, Store.lon)
            me.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            me.icon = DotDrawable(p.text, p.accent, ui.dp(20))
            me.setOnMarkerClickListener { _, _ -> true }
            map.overlays.add(me)
        }
        map.invalidate()
    }
}

// ======================================================================================
// Settings & uploads
// ======================================================================================

class SettingsScreen(private val ui: Ui, private val actions: Actions) : Screen {
    private val p = ui.p
    private val sessions = ui.vbox()
    private val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.US)
    override val view: View

    init {
        val col = ui.vbox(12, 10)

        // Theme
        val (themeCard, themeBody) = ui.card("// Theme")
        val grid = ui.vbox()
        Themes.ALL.chunked(2).forEach { pair ->
            val row = ui.hbox()
            pair.forEachIndexed { i, t ->
                val sel = t.id == p.id
                val sw = ui.vbox(10, 10)
                sw.background = ChamferDrawable(t.panel, if (sel) t.accent2 else t.frame, ui.dpf(if (sel) 2.5f else 1.4f), ui.dpf(8))
                sw.addView(ui.head(t.name, 13f, t.accent))
                val dots = ui.hbox()
                for (c in listOf(t.frame, t.accent, t.accent2, t.danger))
                    dots.addView(View(ui.ctx).apply { background = DotDrawable(c, t.bg, ui.dp(14)) }, ui.lp(w = ui.dp(14), h = ui.dp(14), right = 4, top = 6))
                sw.addView(dots)
                sw.setOnClickListener { Prefs.theme = t.id; actions.applyTheme() }
                row.addView(sw, ui.lp(w = 0, weight = 1f, left = if (i == 1) 5 else 0, right = if (i == 0) 5 else 0))
            }
            grid.addView(row, ui.lp(match = true, bottom = 8))
        }
        themeBody.addView(grid)
        col.addView(themeCard, ui.lp(match = true))

        // Detections
        val (detCard, detBody) = ui.card("// Detections", stripes = 2)
        for (c in Cat.entries) detBody.addView(ui.toggleRow(c.label, null, Prefs.catEnabled(c)) { Prefs.setCat(c, it) })
        detBody.addView(ui.text("Changes apply to new detections.", 12f, p.dim), ui.lp(top = 4))
        col.addView(detCard, ui.lp(match = true, top = 12))

        // Alerts
        val (alCard, alBody) = ui.card("// Alerts", stripes = 2)
        alBody.addView(ui.toggleRow("Notifications", "Pop up a notification for each new detection", Prefs.notify) { Prefs.notify = it })
        alBody.addView(ui.toggleRow("Vibrate", "Buzz on high-severity detections", Prefs.vibrate) { Prefs.vibrate = it })
        alBody.addView(ui.toggleRow("Only notify medium & high", "Skip low-severity trackers and guesses", Prefs.minSeverity == "2") {
            Prefs.minSeverity = if (it) "2" else "1"
        })
        col.addView(alCard, ui.lp(match = true, top = 12))

        // Upload accounts
        val (upCard, upBody) = ui.card("// Upload accounts", stripes = 3)
        upBody.addView(ui.head("WIGLE", 13f, p.accent2))
        upBody.addView(ui.text("wigle.net/account → API Name + API Token. Or leave the name empty and paste the \"Encoded for use\" token.", 12f, p.dim), ui.lp(top = 2))
        val wName = ui.field("API Name", Prefs.wigleName)
        val wTok = ui.field("API Token", Prefs.wigleToken, secret = true)
        upBody.addView(wName, ui.lp(match = true, top = 6))
        upBody.addView(wTok, ui.lp(match = true, top = 6))
        upBody.addView(ui.head("WDGWARS", 13f, p.accent2), ui.lp(top = 12))
        upBody.addView(ui.text("API key from wdgwars.pl/profile", 12f, p.dim), ui.lp(top = 2))
        val wdg = ui.field("WDGWars API key", Prefs.wdgKey, secret = true)
        upBody.addView(wdg, ui.lp(match = true, top = 6))
        upBody.addView(ui.head("WARDRIFT", 13f, p.accent2), ui.lp(top = 12))
        upBody.addView(ui.text("Character API key (wdk_…) from wardrift.net", 12f, p.dim), ui.lp(top = 2))
        val drift = ui.field("Wardrift API key", Prefs.driftKey, secret = true)
        upBody.addView(drift, ui.lp(match = true, top = 6))
        val saved = ui.mono("", 12f, p.accent2)
        upBody.addView(ui.button("Save keys") {
            Prefs.wigleName = wName.text.toString(); Prefs.wigleToken = wTok.text.toString()
            Prefs.wdgKey = wdg.text.toString(); Prefs.driftKey = drift.text.toString()
            saved.text = "Saved."
            render()
        }, ui.lp(match = true, top = 12))
        upBody.addView(saved, ui.lp(top = 6))
        col.addView(upCard, ui.lp(match = true, top = 12))

        // Sessions
        val (sesCard, sesBody) = ui.card("// Sessions", stripes = 4)
        sesBody.addView(sessions, ui.lp(match = true))
        col.addView(sesCard, ui.lp(match = true, top = 12))

        // About
        val (abCard, abBody) = ui.card("// About")
        abBody.addView(ImageView(ui.ctx).apply {
            setImageResource(R.drawable.art_hood); adjustViewBounds = true
        }, ui.lp(match = true))
        abBody.addView(ui.text(
            "Wardrive Lite only listens to what devices broadcast. It never connects to them or interferes with them.\n\n" +
                "Flock, Penguin and Raven signatures: Flock You by colonelpanichacks (MIT) and DeFlock community data. " +
                "Light bar and radar rules are name guesses. Fonts: Chakra Petch and Share Tech Mono (SIL OFL). Art: SHOK3R.",
            12f, p.dim), ui.lp(top = 10))
        col.addView(abCard, ui.lp(match = true, top = 12, bottom = 16))

        view = ScrollView(ui.ctx).apply { isVerticalScrollBarEnabled = false; addView(col) }
    }

    override fun render() {
        sessions.removeAllViews()
        val files = try { Store.sessions(ui.ctx) } catch (_: Throwable) { emptyList() }
        if (files.isEmpty()) {
            sessions.addView(ui.text("No logs yet. Rows are only written while there is a GPS fix.", 13f, p.dim))
            return
        }
        for (f in files.take(15)) {
            val box = ui.vbox(10, 8)
            box.background = ui.panel(stroke = Ui.withAlpha(p.frame, 160), cut = 8, strokeW = 1)
            val live = Store.scanning && f == Store.currentFile
            box.addView(ui.head(fmt.format(Date(f.lastModified())) + if (live) "  ● RECORDING" else "", 14f, if (live) p.accent2 else p.text))
            box.addView(ui.mono("${f.name} · ${f.length() / 1024} KB", 11f, p.dim), ui.lp(top = 2))
            val st = ui.mono(statusLine(f.name), 11f, p.accent)
            box.addView(st, ui.lp(top = 4))
            val btns = ui.hbox()
            fun up(label: String, svc: String) = ui.button(label) {
                st.text = "Uploading to $label…"
                Uploader.upload(svc, f) { r ->
                    Prefs.setUploadStatus(svc, f.name, if (r.ok) "OK" else "FAIL")
                    st.post { st.text = (if (r.ok) "✔ " else "✖ ") + "$label: ${r.msg}" }
                }
            }
            btns.addView(ui.button("Share") { actions.share(f) }, ui.lp(right = 6))
            btns.addView(up("WiGLE", "wigle"), ui.lp(right = 6))
            btns.addView(up("WDG", "wdg"), ui.lp(right = 6))
            btns.addView(up("Drift", "drift"))
            box.addView(ui.chipRow(listOf(btns)), ui.lp(match = true, top = 8))
            sessions.addView(box, ui.lp(match = true, bottom = 8))
        }
    }

    private fun statusLine(name: String): String {
        val parts = listOf("wigle" to "WiGLE", "wdg" to "WDG", "drift" to "Drift").mapNotNull { (k, l) ->
            when (Prefs.uploadStatus(k, name)) { "OK" -> "$l ✔"; "FAIL" -> "$l ✖"; else -> null }
        }
        return if (parts.isEmpty()) "Not uploaded" else parts.joinToString("  ")
    }
}
