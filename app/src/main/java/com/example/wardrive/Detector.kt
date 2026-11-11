package com.example.wardrive

import java.util.Locale

/**
 * Signature rules for "interesting" devices. All matching is passive, based on what a
 * device broadcasts (names, MAC prefixes, BLE advertisement data).
 *
 * Flock / Penguin / Raven signatures come from the open-source Flock You project
 * (github.com/colonelpanichacks/flock-you, MIT licence) and DeFlock community data.
 * Light bar and radar rules are name guesses only: expect misses and false positives.
 * Edit the lists below to add signatures you trust.
 */
object Detector {

    // --- Flock Safety ALPR cameras ----------------------------------------------------
    /** Flock Safety's own IEEE-registered OUI. */
    private val FLOCK_OUI_STRONG = setOf("B4:1E:52")
    /** OUIs seen on Flock cameras in community drive tests (Flock You, 2026-09 list). */
    private val FLOCK_OUI = setOf(
        "70:C9:4E", "3C:91:80", "D8:F3:BC", "80:30:49", "B8:35:32",
        "14:5A:FC", "74:4C:A1", "08:3A:88", "9C:2F:9D", "C0:35:32",
        "94:08:53", "E4:AA:EA", "F4:6A:DD", "E0:0A:F6", "24:B2:B9",
        "00:F4:8D", "D0:39:57", "E8:D0:FC", "E0:4F:43", "B8:1E:A4",
        "70:08:94", "58:8E:81", "EC:1B:BD", "58:00:E3", "90:35:EA",
        "5C:93:A2", "64:6E:69", "48:27:EA", "14:B5:CD", "82:6B:F2",
        "00:03:7F",
    )
    /** Prefixes in the list that are also used by lots of ordinary gadgets: lower confidence. */
    private val FLOCK_OUI_GENERIC = setOf("3C:71:BF", "A4:CF:12")
    private val FLOCK_NAME = Regex("(?i)^(flock|penguin|pigvision|fs ext battery)")
    private val PENGUIN_SERIAL = Regex("^(Penguin-)?\\d{10}$")
    private const val XUNTONG = 0x09C8                 // Penguin battery pack mfg ID
    private const val FLOCK_GATT = "e8ccbb38-9532-46a8-9fe5-1814df172e6f"
    private const val NORDIC_DFU = "00001530-1212-efde-1523-785feabcd123"

    // --- Raven gunshot sensors (Flock / SoundThinking) --------------------------------
    private const val RAVEN_MIN = 0x3100
    private const val RAVEN_MAX = 0x3500

    // --- Police gear -----------------------------------------------------------------
    /** 00:25:DF is registered to Taser International (now Axon): body cams, Tasers, Signal units. */
    private val AXON_OUI = setOf("00:25:DF")
    private val POLICE_NAME = Regex(
        "(?i)^(ibr\\d{3,4}|cradlepoint|axon|watchguard|getac|motorola solutions|l3harris|panasonic arbitrator|coban)"
    )
    private val LIGHTBAR_NAME = Regex(
        "(?i)(whelen|cencom|wecan|code ?3|matrix-?z|pathfinder|federal signal|soundoff|bluprint|blueprint|feniex|setina|galls)"
    )
    private val RADAR_NAME = Regex(
        "(?i)(stalker|kustom|decatur|dragoneye|mph ind|laser ?ally|lti ?20|genesis-?ii|golden eagle)"
    )

    // --- Hidden / cheap IoT cameras broadcasting their own hotspot --------------------
    private val CAM_SSID = Regex(
        "(?i)^(hdwificam|ipcam|ip-cam|v380|yi_|mini.?cam|wificam|lookcam|hidvcam|camhi|jxlcam|accq|spycam|cam-|ezviz_|xiaoyi|a9_|hd-wifi)"
    )

    // --- Trackers ---------------------------------------------------------------------
    private const val APPLE = 0x004C
    private const val FINDMY_OFFLINE = 0x12.toByte()
    private val SMARTTAG = uuid16("fd5a")
    private val TILE = setOf(uuid16("feed"), uuid16("feec"))
    private val CHIPOLO = uuid16("fe33")
    private val GOOGLE_FMDN = uuid16("feaa")

    // --- Other gadgets ----------------------------------------------------------------
    private val FLIPPER_NAME = Regex("(?i)^flipper")
    private val GLASSES_NAME = Regex("(?i)(ray-?ban|rb meta|meta glasses|oakley meta|spectacles|meta hypernova)")
    /** Bluetooth SIG company IDs registered to Meta / Facebook. Also matches Quest headsets. */
    private val META_IDS = setOf(0x01AB, 0x058E)

    private fun uuid16(short: String) = "0000$short-0000-1000-8000-00805f9b34fb"
    private fun oui(mac: String) = mac.uppercase(Locale.US).take(8)

    /** Returns the 16-bit value of a Bluetooth base UUID, or -1. */
    private fun short16(uuid: String): Int {
        if (!uuid.startsWith("0000") || !uuid.endsWith("-0000-1000-8000-00805f9b34fb")) return -1
        return uuid.substring(4, 8).toIntOrNull(16) ?: -1
    }

    fun check(o: Obs): Alert? {
        if (o.kind == Kind.CELL) return null
        val prefix = oui(o.key)
        val name = o.name

        // Flock / Penguin / Raven
        if (prefix in FLOCK_OUI_STRONG) return Alert(Cat.FLOCK, "Flock Safety camera", 3, "Flock-registered MAC prefix")
        if (FLOCK_NAME.containsMatchIn(name)) return Alert(Cat.FLOCK, "Flock Safety camera", 3, "Name \"$name\"")
        if (o.kind == Kind.BLE) {
            if (o.services.any { short16(it) in RAVEN_MIN..RAVEN_MAX })
                return Alert(Cat.GUNSHOT, "Raven gunshot sensor", 3, "Raven GATT service 0x31xx-0x35xx")
            if (FLOCK_GATT in o.services) return Alert(Cat.FLOCK, "Flock camera accessory", 3, "Flock GATT service")
            if (o.mfgData.containsKey(XUNTONG)) return Alert(Cat.FLOCK, "Flock Penguin battery", 3, "Mfg ID 0x09C8")
            if (PENGUIN_SERIAL.matches(name)) return Alert(Cat.FLOCK, "Possible Flock Penguin battery", 2, "10-digit serial name")
            if (name == "DfuTarg" && NORDIC_DFU in o.services)
                return Alert(Cat.FLOCK, "Flock battery updating firmware", 2, "Nordic DFU target")
        }
        if (prefix in FLOCK_OUI) return Alert(Cat.FLOCK, "Possible Flock camera", 2, "MAC prefix $prefix from drive tests")
        if (prefix in FLOCK_OUI_GENERIC && name.isEmpty())
            return Alert(Cat.FLOCK, "Possible Flock camera", 1, "Generic radio prefix $prefix")

        // Police
        if (prefix in AXON_OUI) return Alert(Cat.POLICE, "Axon body cam / Taser", 3, "Axon (Taser Intl) MAC prefix")
        if (POLICE_NAME.containsMatchIn(name)) return Alert(Cat.POLICE, "Police vehicle equipment", 2, "Name \"$name\"")
        if (LIGHTBAR_NAME.containsMatchIn(name)) return Alert(Cat.LIGHTBAR, "Possible light bar / siren", 2, "Name \"$name\"")
        if (RADAR_NAME.containsMatchIn(name)) return Alert(Cat.RADAR, "Possible traffic radar / lidar", 2, "Name \"$name\"")

        if (o.kind == Kind.BLE) {
            val apple = o.mfgData[APPLE]
            if (apple != null && apple.isNotEmpty() && apple[0] == FINDMY_OFFLINE)
                return Alert(Cat.TRACKER, "Apple Find My tracker", 1, "Find My 'separated' beacon")
            if (SMARTTAG in o.services) return Alert(Cat.TRACKER, "Samsung SmartTag", 1, "Service 0xFD5A")
            if (o.services.any { it in TILE }) return Alert(Cat.TRACKER, "Tile tracker", 1, "Tile service")
            if (CHIPOLO in o.services) return Alert(Cat.TRACKER, "Chipolo tracker", 1, "Service 0xFE33")
            if (GOOGLE_FMDN in o.services && name.isEmpty())
                return Alert(Cat.TRACKER, "Google Find My tag", 1, "Eddystone / FMDN frame")
            if (FLIPPER_NAME.containsMatchIn(name)) return Alert(Cat.FLIPPER, "Flipper Zero", 2, "Name \"$name\"")
            if (GLASSES_NAME.containsMatchIn(name)) return Alert(Cat.GLASSES, "Smart glasses (camera)", 2, "Name \"$name\"")
            if (o.mfgData.keys.any { it in META_IDS }) return Alert(Cat.GLASSES, "Meta device (glasses?)", 1, "Meta company ID")
        }

        if (o.kind == Kind.WIFI && CAM_SSID.containsMatchIn(name))
            return Alert(Cat.CAMERA, "Possible hidden / IoT camera", 2, "SSID \"$name\"")

        return null
    }
}
