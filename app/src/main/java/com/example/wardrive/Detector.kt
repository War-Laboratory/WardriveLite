package com.example.wardrive

import java.util.Locale

/**
 * Signature rules for "interesting" devices. All matching is passive, based on what a
 * device broadcasts (names, MAC prefixes, BLE advertisement data).
 *
 * These are heuristics: expect some misses and false positives. Edit the lists
 * below to add signatures you trust (e.g. from community projects like DeFlock).
 */
object Detector {

    // --- Flock Safety ALPR cameras ----------------------------------------------------
    private val FLOCK_NAME = Regex("(?i)^(flock|penguin|pigvision|fs ext battery)")
    /** MAC prefixes (first 3 bytes, "AA:BB:CC"). Left empty on purpose: add verified OUIs. */
    private val FLOCK_OUI = setOf<String>()

    // --- Police gear -----------------------------------------------------------------
    /** 00:25:DF is registered to Taser International (now Axon): body cams, Tasers, Signal units. */
    private val AXON_OUI = setOf("00:25:DF")
    /** In-car routers / equipment commonly seen in patrol vehicles. */
    private val POLICE_NAME = Regex(
        "(?i)^(ibr\\d{3,4}|cradlepoint|axon|watchguard|getac|whelen|l3harris|motorola solutions)"
    )

    // --- Hidden / cheap IoT cameras broadcasting their own hotspot --------------------
    private val CAM_SSID = Regex(
        "(?i)^(hdwificam|ipcam|ip-cam|v380|yi_|mini.?cam|wificam|lookcam|hidvcam|camhi|jxlcam|accq|spycam|cam-|ezviz_|xiaoyi)"
    )

    // --- Trackers ---------------------------------------------------------------------
    private const val APPLE = 0x004C
    private const val FINDMY_OFFLINE = 0x12.toByte() // "separated from owner" Find My beacon
    private val SMARTTAG = uuid16("fd5a")            // Samsung SmartTag
    private val TILE = uuid16("feed")                // Tile

    // --- Other gadgets ----------------------------------------------------------------
    private val FLIPPER_NAME = Regex("(?i)^flipper")
    private val GLASSES_NAME = Regex("(?i)(ray-?ban|rb meta|meta glasses|oakley meta|spectacles)")
    /** Bluetooth SIG company IDs registered to Meta / Facebook. Also matches Quest headsets. */
    private val META_IDS = setOf(0x01AB, 0x058E)

    private fun uuid16(short: String) = "0000$short-0000-1000-8000-00805f9b34fb"
    private fun oui(mac: String) = mac.uppercase(Locale.US).take(8)

    fun check(o: Obs): Alert? {
        if (o.kind == Kind.CELL) return null
        val prefix = oui(o.key)
        val name = o.name

        if (FLOCK_NAME.containsMatchIn(name) || prefix in FLOCK_OUI)
            return Alert("flock", "Flock Safety camera (ALPR)", 3)
        if (prefix in AXON_OUI)
            return Alert("police", "Axon device (body cam / Taser)", 3)
        if (POLICE_NAME.containsMatchIn(name))
            return Alert("police", "Possible police vehicle equipment", 2)

        if (o.kind == Kind.BLE) {
            val apple = o.mfgData[APPLE]
            if (apple != null && apple.isNotEmpty() && apple[0] == FINDMY_OFFLINE)
                return Alert("tracker", "Apple Find My tracker (AirTag?)", 1)
            if (SMARTTAG in o.services) return Alert("tracker", "Samsung SmartTag", 1)
            if (TILE in o.services) return Alert("tracker", "Tile tracker", 1)
            if (FLIPPER_NAME.containsMatchIn(name)) return Alert("flipper", "Flipper Zero", 2)
            if (GLASSES_NAME.containsMatchIn(name))
                return Alert("glasses", "Smart glasses (camera)", 2)
            if (o.mfgData.keys.any { it in META_IDS })
                return Alert("glasses", "Meta device (smart glasses?)", 1)
        }

        if (o.kind == Kind.WIFI && CAM_SSID.containsMatchIn(name))
            return Alert("camera", "Possible hidden / IoT camera", 2)

        return null
    }
}
