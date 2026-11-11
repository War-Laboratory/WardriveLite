package com.example.wardrive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import androidx.core.content.res.ResourcesCompat

/** One colour scheme. frame = panel borders, accent = headline colour, accent2 = highlights. */
data class Palette(
    val id: String,
    val name: String,
    val bg: Int,
    val panel: Int,
    val frame: Int,
    val accent: Int,
    val accent2: Int,
    val text: Int,
    val dim: Int,
    val danger: Int,
)

object Themes {
    private fun c(s: String) = Color.parseColor(s)

    val ALL = listOf(
        Palette("shok3r", "SHOK3R", c("#07050B"), c("#130C1E"), c("#8E2DE2"), c("#FFE11A"), c("#C6FF1A"), c("#F4EEFF"), c("#9A8CB6"), c("#FF3B6B")),
        Palette("toxic", "TOXIC", c("#050805"), c("#0E140A"), c("#B7F516"), c("#C55CFF"), c("#FFE11A"), c("#F2FFE8"), c("#8FA37A"), c("#FF3B6B")),
        Palette("memphis", "MEMPHIS NIGHT", c("#08050E"), c("#160B22"), c("#B026FF"), c("#FFE600"), c("#FF4FD8"), c("#FFF6E0"), c("#A98FC0"), c("#FF5A3B")),
        Palette("grid", "NEON GRID", c("#03070C"), c("#0A1420"), c("#1EE3FF"), c("#FF3EC8"), c("#FFF04A"), c("#E8FBFF"), c("#7FA6B8"), c("#FF3B6B")),
    )

    val current: Palette get() = ALL.firstOrNull { it.id == Prefs.theme } ?: ALL.first()
}

object Fonts {
    var head: Typeface = Typeface.DEFAULT_BOLD
    var body: Typeface = Typeface.DEFAULT
    var mono: Typeface = Typeface.MONOSPACE
    private var loaded = false

    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        head = load(ctx, R.font.chakra_bold_italic) ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
        body = load(ctx, R.font.chakra_semibold) ?: Typeface.DEFAULT_BOLD
        mono = load(ctx, R.font.share_tech_mono) ?: Typeface.MONOSPACE
    }

    private fun load(ctx: Context, id: Int): Typeface? = try { ResourcesCompat.getFont(ctx, id) } catch (_: Throwable) { null }
}

/**
 * Panel background: corners cut at 45°, a glowing outline, and optional hazard stripes
 * in the top-right corner, like an engraved plate.
 */
class ChamferDrawable(
    private val fill: Int,
    private val stroke: Int,
    private val strokePx: Float,
    private val cutPx: Float,
    private val stripes: Int = 0,
    private val stripeColor: Int = stroke,
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill; style = Paint.Style.FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = stroke; alpha = 70; style = Paint.Style.STROKE; strokeWidth = strokePx * 3f
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = stroke; style = Paint.Style.STROKE; strokeWidth = strokePx }
    private val stripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = stripeColor; style = Paint.Style.FILL }
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val i = strokePx * 1.5f
        val l = b.left + i; val t = b.top + i; val r = b.right - i; val bt = b.bottom - i
        val c = cutPx.coerceAtMost(minOf(r - l, bt - t) / 3f)
        path.reset()
        path.moveTo(l + c, t); path.lineTo(r - c, t); path.lineTo(r, t + c); path.lineTo(r, bt - c)
        path.lineTo(r - c, bt); path.lineTo(l + c, bt); path.lineTo(l, bt - c); path.lineTo(l, t + c); path.close()
        canvas.drawPath(path, fillPaint)
        if (strokePx > 0) {
            canvas.drawPath(path, glowPaint)
            canvas.drawPath(path, strokePaint)
        }
        if (stripes > 0) {
            val h = c * 0.55f
            val w = h * 0.9f
            var x = r - c - stripes * (w + h * 0.35f)
            repeat(stripes) {
                val p = Path()
                p.moveTo(x + h * 0.5f, t + h * 0.35f); p.lineTo(x + w + h * 0.5f, t + h * 0.35f)
                p.lineTo(x + w, t + h * 1.15f); p.lineTo(x, t + h * 1.15f); p.close()
                canvas.drawPath(p, stripePaint)
                x += w + h * 0.35f
            }
        }
    }

    override fun setAlpha(alpha: Int) { fillPaint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { fillPaint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
