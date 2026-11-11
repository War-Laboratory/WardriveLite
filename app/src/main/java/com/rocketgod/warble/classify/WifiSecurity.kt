package com.rocketgod.warble.classify

/**
 * Wardrive Lite (SHOK3R fork): reads the security a Wi-Fi network already advertises in its
 * beacon, from Android's ScanResult.capabilities string (e.g. "[WPA2-PSK-CCMP][WPS][ESS]").
 *
 * This is survey data only — the publicly broadcast auth/cipher/WPS/PMF flags. It never touches
 * key material and says nothing that cracking a network would reveal.
 */
object WifiSecurity {

    enum class Grade { OPEN, WEAK, OK, STRONG, UNKNOWN }

    data class Info(
        val label: String,        // short posture, e.g. "WPA3-SAE" or "Open"
        val auth: String?,        // PSK / SAE / 802.1X (EAP) / OWE / none
        val cipher: String?,      // CCMP / TKIP / WEP
        val wps: Boolean,         // WPS advertised (a known weak point)
        val pmf: String?,         // "required" / "capable" / null
        val grade: Grade,
        val notes: List<String>,  // plain-language flags for the weak ones
    )

    fun parse(capabilities: String?): Info {
        val caps = capabilities?.uppercase() ?: return Info("Unknown", null, null, false, null, Grade.UNKNOWN, emptyList())
        val wps = caps.contains("[WPS")
        val pmf = when {
            caps.contains("[MFPR") -> "required"
            caps.contains("[MFPC") -> "capable"
            else -> null
        }

        val sae = caps.contains("SAE")
        val owe = caps.contains("OWE")
        val wpa2 = caps.contains("WPA2") || caps.contains("[RSN")
        val wpa1 = caps.contains("WPA-") || caps.contains("[WPA")
        val eap = caps.contains("-EAP") || caps.contains("-SUITE_B") || caps.contains("-FT/EAP")
        val wep = caps.contains("[WEP")
        val cipher = when {
            caps.contains("CCMP") || caps.contains("GCMP") -> "CCMP/AES"
            caps.contains("TKIP") -> "TKIP"
            wep -> "WEP"
            else -> null
        }

        val notes = ArrayList<String>()
        val label: String
        val auth: String?
        val grade: Grade

        when {
            sae && (wpa2 || !wpa1) -> {
                // WPA3 Personal, optionally in WPA3/WPA2 transition mode
                label = if (caps.contains("PSK")) "WPA3/WPA2-PSK (transition)" else "WPA3-SAE"
                auth = "SAE"
                grade = Grade.STRONG
            }
            owe -> {
                label = if (caps.contains("OWE_TRANSITION")) "OWE (open, transition)" else "OWE (enhanced open)"
                auth = "OWE"
                grade = Grade.OK
                if (caps.contains("OWE_TRANSITION")) notes += "Falls back to a fully open network"
            }
            eap && wpa2 -> { label = "WPA2-Enterprise (802.1X)"; auth = "802.1X (EAP)"; grade = Grade.STRONG }
            wpa2 -> {
                label = "WPA2-PSK"; auth = "PSK"
                grade = if (cipher == "TKIP") Grade.WEAK else Grade.OK
                if (cipher == "TKIP") notes += "Uses legacy TKIP — upgrade to AES/CCMP"
            }
            wpa1 -> { label = "WPA (original)"; auth = if (eap) "802.1X (EAP)" else "PSK"; grade = Grade.WEAK; notes += "WPA1 is deprecated and breakable" }
            wep -> { label = "WEP"; auth = "shared key"; grade = Grade.WEAK; notes += "WEP is broken — effectively open" }
            else -> { label = "Open"; auth = "none"; grade = Grade.OPEN; notes += "No encryption — anyone can join and traffic is unprotected" }
        }

        if (wps && grade != Grade.OPEN) notes += "WPS is on — a known weak point; turn it off"
        if (pmf == null && grade in setOf(Grade.OK, Grade.STRONG)) notes += "No management-frame protection (PMF)"

        return Info(label, auth, cipher, wps, pmf, grade, notes)
    }

    fun gradeArgb(g: Grade): Long = when (g) {
        Grade.OPEN -> 0xFFFF3B3B
        Grade.WEAK -> 0xFFFFB300
        Grade.OK -> 0xFFC6FF1A
        Grade.STRONG -> 0xFF37E36B
        Grade.UNKNOWN -> 0xFF9A8CB6
    }

    fun gradeWord(g: Grade): String = when (g) {
        Grade.OPEN -> "OPEN"
        Grade.WEAK -> "WEAK"
        Grade.OK -> "OK"
        Grade.STRONG -> "STRONG"
        Grade.UNKNOWN -> "—"
    }
}
