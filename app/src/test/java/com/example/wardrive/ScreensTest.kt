package com.example.wardrive

import android.view.View
import android.widget.FrameLayout
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import org.junit.Rule
import org.junit.Test

/** Renders each screen with sample data so the look can be checked without a phone. */
class ScreensTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        theme = "android:Theme.Material.NoActionBar",
        renderingMode = SessionParams.RenderingMode.V_SCROLL,
        maxPercentDifference = 100.0,
    )

    private val actions = object : Actions {
        override fun toggleScan() {}
        override fun openDetail(s: Sighting) {}
        override fun showOnMap(s: Sighting) {}
        override fun applyTheme() {}
        override fun share(file: java.io.File) {}
    }

    private fun wrap(v: View): View {
        val f = FrameLayout(paparazzi.context)
        f.setBackgroundColor(Themes.current.bg)
        f.addView(v)
        return f
    }

    private fun shot(theme: String, make: (Ui) -> Screen) {
        Prefs.theme = theme
        FakeData.load()
        val ui = Ui(paparazzi.context)
        val s = make(ui)
        s.render()
        paparazzi.snapshot(wrap(s.view))
    }

    @Test fun hud_shok3r() = shot("shok3r") { HudScreen(it, actions) }
    @Test fun hud_toxic() = shot("toxic") { HudScreen(it, actions) }
    @Test fun hud_memphis() = shot("memphis") { HudScreen(it, actions) }
    @Test fun hud_grid() = shot("grid") { HudScreen(it, actions) }
    @Test fun alerts() = shot("shok3r") { AlertsScreen(it, actions) }
    @Test fun devices() = shot("shok3r") { DevicesScreen(it, actions) }
    @Test fun settings() = shot("shok3r") { SettingsScreen(it, actions) }

    @Test fun detail() {
        Prefs.theme = "shok3r"
        FakeData.load()
        val ui = Ui(paparazzi.context)
        val s = alertsSorted().first()
        paparazzi.snapshot(wrap(detailView(ui, s, {}, {})))
    }
}

object FakeData {
    fun load() {
        Store.sightings.clear()
        Store.scanning = true
        Store.startTime = System.currentTimeMillis() - 1_234_000
        Store.hasFix = true; Store.acc = 4f; Store.lat = 35.1495; Store.lon = -90.0490
        Store.rowsWritten = 3812
        val now = System.currentTimeMillis()
        var n = 0
        fun add(key: String, kind: Kind, name: String, rssi: Int, ch: Int = 0, freq: Int = 0, type: String = kind.name, alert: Alert? = null, following: Boolean = false) {
            val s = Sighting(key, kind, name, rssi, ch, freq, "", type, now - 600_000)
            s.lastSeen = now - (n++ % 7) * 9_000L
            s.hits = 3 + n * 2
            s.lat = 35.14 + n * 0.0003; s.lon = -90.05 + n * 0.0002
            s.alert = alert; s.following = following
            Store.sightings[key] = s
        }
        add("B4:1E:52:10:22:9A", Kind.WIFI, "", -61, 6, 2437, alert = Alert(Cat.FLOCK, "Flock Safety camera", 3, "Flock-registered MAC prefix"))
        add("D4:9A:11:03:7C:21", Kind.BLE, "", -70, alert = Alert(Cat.GUNSHOT, "Raven gunshot sensor", 3, "Raven GATT service 0x31xx-0x35xx"))
        add("00:25:DF:44:10:0B", Kind.BLE, "", -77, alert = Alert(Cat.POLICE, "Axon body cam / Taser", 3, "Axon (Taser Intl) MAC prefix"))
        add("5E:12:9C:EE:01:7A", Kind.BLE, "", -58, alert = Alert(Cat.TRACKER, "Apple Find My tracker — FOLLOWING YOU", 3, "Find My 'separated' beacon"), following = true)
        add("C0:FF:EE:12:34:56", Kind.BLE, "Flipper Shok3r", -66, alert = Alert(Cat.FLIPPER, "Flipper Zero", 2, "Name \"Flipper Shok3r\""))
        add("A8:42:E3:77:01:02", Kind.WIFI, "V380_9F21", -80, 11, 2462, alert = Alert(Cat.CAMERA, "Possible hidden / IoT camera", 2, "SSID \"V380_9F21\""))
        val ssids = listOf("Memphis Grizz WiFi", "xfinitywifi", "NETGEAR42", "Beale St Guest", "ATT8c2T", "Pyramid_5G", "Spectrum-Setup-41", "DIRECT-roku-112", "HP-Print-7A")
        val chans = listOf(1, 1, 6, 6, 6, 11, 11, 3, 9, 36, 44, 149, 157, 48, 161)
        for (i in 0 until 40) {
            val ch = chans[i % chans.size]
            val f = if (ch <= 14) 2407 + ch * 5 else 5000 + ch * 5
            add("3C:%02X:11:22:33:%02X".format(i, i), Kind.WIFI, ssids[i % ssids.size] + if (i > 8) "-$i" else "", -50 - (i * 7) % 45, ch, f)
        }
        for (i in 0 until 25) add("7A:%02X:00:BE:EF:%02X".format(i, i), Kind.BLE, if (i % 3 == 0) "JBL Flip $i" else "", -60 - (i * 5) % 35)
        for (i in 0 until 6) add("310260_1234_${5600 + i}", Kind.CELL, "T-Mobile", -95 + i, type = "LTE")
    }
}
