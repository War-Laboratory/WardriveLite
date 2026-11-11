package com.rocketgod.warble.usb

/**
 * Wardrive Lite (SHOK3R fork): WPA hash capture (PMKID / 4-way handshake harvesting)
 * has been removed. This stub keeps the same API so the rest of the app compiles,
 * but it never inspects EAPOL frames and never produces captures or crackable hashes.
 */
object PmkidCapture {

    data class Hit(
        val pmkid: String, val bssid: String, val sta: String, val ssid: String?,
        val channel: Int, val rssi: Int, val time: Long,
        val kind: Int = 1, val hashline: String? = null
    )

    val total = 0
    val handshakes = 0

    fun diag(): String = "Hash capture: removed in this build"

    @Suppress("UNUSED_PARAMETER")
    fun scan(buf: ByteArray, d: Int, end: Int, pad: Int, ch: Int, rssi: Int, ssidFor: (String) -> String?) {}

    fun drainNew(): List<Hit> = emptyList()

    @Suppress("UNUSED_PARAMETER")
    fun hashcat22000(rows: List<com.rocketgod.warble.data.PmkidEntity>): String = ""
}
