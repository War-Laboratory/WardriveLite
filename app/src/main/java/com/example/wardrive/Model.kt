package com.example.wardrive

enum class Kind { WIFI, BLE, CELL }

/** Detection categories. Each can be switched off in Settings. */
enum class Cat(val label: String, val tag: String) {
    FLOCK("Flock ALPR camera", "FLOCK"),
    GUNSHOT("Gunshot sensor", "SHOT"),
    POLICE("Police gear", "COPS"),
    LIGHTBAR("Police light bar", "LIGHT"),
    RADAR("Radar / lidar", "RADAR"),
    TRACKER("Tracker", "TRACK"),
    CAMERA("Hidden camera", "CAM"),
    FLIPPER("Flipper Zero", "FLIP"),
    GLASSES("Smart glasses", "GLASS"),
}

/** What a detection rule decided this device is. severity: 1 = info, 2 = notable, 3 = high. */
data class Alert(val cat: Cat, val label: String, val severity: Int, val why: String = "")

/** One raw observation coming from a scanner. */
data class Obs(
    val key: String,              // MAC / BSSID, or cell id like 310260_1234_5678
    val kind: Kind,
    val name: String,             // SSID, BLE name, or operator
    val rssi: Int,
    val channel: Int = 0,
    val freq: Int = 0,
    val auth: String = "",        // WiGLE AuthMode column
    val type: String,             // WiGLE Type column: WIFI, BLE, LTE, GSM, WCDMA, NR
    val mfgData: Map<Int, ByteArray> = emptyMap(),
    val services: Set<String> = emptySet(), // lowercase full 128-bit UUID strings
)

/** Everything we know about one device, merged across observations. */
class Sighting(
    val key: String,
    val kind: Kind,
    var name: String,
    var rssi: Int,
    var channel: Int,
    var freq: Int,
    var auth: String,
    val type: String,
    val firstSeen: Long,
) {
    var lastSeen: Long = firstSeen
    var bestRssi: Int = rssi
    var lat = 0.0          // position of strongest signal
    var lon = 0.0
    var firstLat = 0.0     // where we first saw it (for "following you" check)
    var firstLon = 0.0
    var firstFixTime = 0L
    var alert: Alert? = null
    var following = false
    var mfgrId: String = ""
    var hits = 0
}
