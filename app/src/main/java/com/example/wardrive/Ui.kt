package com.example.wardrive

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/** Small helpers so the screens can be built in code with a consistent look. */
class Ui(val ctx: Context) {
    val p: Palette get() = Themes.current

    init { Fonts.init(ctx) }

    fun dp(v: Number): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()
    fun dpf(v: Number): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics)

    fun text(s: CharSequence, sp: Float = 14f, color: Int = p.text, font: Typeface = Fonts.body): TextView =
        TextView(ctx).apply {
            text = s
            textSize = sp
            setTextColor(color)
            typeface = font
            includeFontPadding = false
        }

    fun head(s: CharSequence, sp: Float = 18f, color: Int = p.accent) = text(s, sp, color, Fonts.head).apply { letterSpacing = 0.04f }
    fun mono(s: CharSequence, sp: Float = 13f, color: Int = p.text) = text(s, sp, color, Fonts.mono)

    fun panel(stroke: Int = p.frame, fill: Int = p.panel, cut: Number = 12, stripes: Int = 0, strokeW: Number = 1.6f) =
        ChamferDrawable(fill, stroke, dpf(strokeW), dpf(cut), stripes, p.accent2)

    fun vbox(padH: Number = 0, padV: Number = 0) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
    }

    fun hbox(padH: Number = 0, padV: Number = 0) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
    }

    /** A titled card: "// TITLE" header with stripes, content below. */
    fun card(title: String, stripes: Int = 3): Pair<LinearLayout, LinearLayout> {
        val outer = vbox(14, 12)
        outer.background = panel(stripes = stripes)
        val t = head(title.uppercase(Locale.US), 15f, p.accent)
        outer.addView(t)
        val body = vbox()
        outer.addView(body, lp(match = true, top = 8))
        return outer to body
    }

    fun button(label: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        head(label.uppercase(Locale.US), if (primary) 20f else 14f, if (primary) p.bg else p.accent).apply {
            gravity = Gravity.CENTER
            val padV = if (primary) 16 else 9
            setPadding(dp(14), dp(padV), dp(14), dp(padV))
            background = if (primary) panel(stroke = p.accent2, fill = p.accent, cut = 14, strokeW = 2)
            else panel(stroke = p.frame, fill = p.panel, cut = 8)
            isClickable = true
            setOnClickListener { onClick() }
        }

    fun chip(label: String, selected: Boolean, onClick: () -> Unit): TextView =
        head(label.uppercase(Locale.US), 12f, if (selected) p.bg else p.dim).apply {
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = if (selected) panel(stroke = p.accent2, fill = p.accent2, cut = 6, strokeW = 1)
            else panel(stroke = p.frame, fill = p.panel, cut = 6, strokeW = 1)
            setOnClickListener { onClick() }
        }

    fun chipRow(views: List<View>): HorizontalScrollView {
        val row = hbox()
        views.forEach { row.addView(it, lp(right = 6)) }
        return HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    /** "[ ON ]" style toggle row. */
    fun toggleRow(label: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit): View {
        val row = hbox(0, 6)
        val texts = vbox()
        texts.addView(text(label, 15f))
        if (sub != null) texts.addView(text(sub, 12f, p.dim), lp(top = 2))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        var on = value
        val sw = head("", 13f)
        fun paint() {
            sw.text = if (on) " ON " else " OFF "
            sw.setTextColor(if (on) p.bg else p.dim)
            sw.background = if (on) panel(stroke = p.accent2, fill = p.accent2, cut = 6, strokeW = 1)
            else panel(stroke = p.frame, fill = p.bg, cut = 6, strokeW = 1)
        }
        sw.setPadding(dp(10), dp(6), dp(10), dp(6))
        sw.minWidth = dp(64)
        sw.gravity = Gravity.CENTER
        paint()
        sw.setOnClickListener { on = !on; paint(); onChange(on) }
        row.addView(sw)
        return row
    }

    fun field(hint: String, value: String, secret: Boolean = false): EditText = EditText(ctx).apply {
        setText(value)
        this.hint = hint
        textSize = 14f
        typeface = Fonts.mono
        setTextColor(p.text)
        setHintTextColor(p.dim)
        setSingleLine()
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        else InputType.TYPE_CLASS_TEXT
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = panel(stroke = p.frame, fill = p.bg, cut = 6, strokeW = 1)
    }

    fun divider(): View = View(ctx).apply { setBackgroundColor(withAlpha(p.frame, 90)) }

    fun lp(
        match: Boolean = false,
        w: Int = if (match) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
        h: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        weight: Float = 0f,
        left: Number = 0, top: Number = 0, right: Number = 0, bottom: Number = 0,
    ) = LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(left), dp(top), dp(right), dp(bottom)) }

    fun sevColor(sev: Int) = when (sev) { 3 -> p.danger; 2 -> p.accent; else -> p.accent2 }

    companion object {
        fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

        fun ago(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return when {
                s < 60 -> "${s}s"
                s < 3600 -> "${s / 60}m"
                else -> "${s / 3600}h"
            }
        }

        fun duration(ms: Long): String {
            val s = ms / 1000
            return "%02d:%02d:%02d".format(Locale.US, s / 3600, (s / 60) % 60, s % 60)
        }
    }
}
