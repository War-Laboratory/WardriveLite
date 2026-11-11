package com.example.wardrive

import android.content.Context
import android.content.SharedPreferences

/** App settings. Falls back to in-memory defaults when SharedPreferences is unavailable (previews). */
object Prefs {
    private var sp: SharedPreferences? = null
    private val mem = HashMap<String, Any>()

    fun init(ctx: Context) {
        if (sp != null) return
        sp = try { ctx.applicationContext.getSharedPreferences("wardrive", Context.MODE_PRIVATE) } catch (_: Throwable) { null }
    }

    fun str(k: String, def: String = ""): String = sp?.getString(k, def) ?: (mem[k] as? String ?: def)
    fun bool(k: String, def: Boolean): Boolean = sp?.getBoolean(k, def) ?: (mem[k] as? Boolean ?: def)
    fun putStr(k: String, v: String) { sp?.edit()?.putString(k, v)?.apply() ?: mem.put(k, v) }
    fun putBool(k: String, v: Boolean) { sp?.edit()?.putBoolean(k, v)?.apply() ?: mem.put(k, v) }

    var theme: String
        get() = str("theme", Themes.ALL.first().id)
        set(v) = putStr("theme", v)

    fun catEnabled(c: Cat) = bool("cat_${c.name}", true)
    fun setCat(c: Cat, on: Boolean) = putBool("cat_${c.name}", on)

    var notify: Boolean
        get() = bool("notify", true)
        set(v) = putBool("notify", v)
    var vibrate: Boolean
        get() = bool("vibrate", true)
        set(v) = putBool("vibrate", v)
    var minSeverity: String
        get() = str("min_sev", "1")
        set(v) = putStr("min_sev", v)

    var wigleName: String
        get() = str("wigle_name")
        set(v) = putStr("wigle_name", v.trim())
    var wigleToken: String
        get() = str("wigle_token")
        set(v) = putStr("wigle_token", v.trim())
    var wdgKey: String
        get() = str("wdg_key")
        set(v) = putStr("wdg_key", v.trim())
    var driftKey: String
        get() = str("drift_key")
        set(v) = putStr("drift_key", v.trim())

    fun uploadStatus(service: String, file: String) = str("up_${service}_$file")
    fun setUploadStatus(service: String, file: String, s: String) = putStr("up_${service}_$file", s)
}
