package com.example.wardrive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Radar scope: rings, a sweeping beam while scanning, and a blip for each alert. */
class RadarView(ctx: Context) : View(ctx) {
    var scanning = false
    var blips: List<Pair<Sighting, Alert>> = emptyList()
    private var angle = 0f
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val beam = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.62f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val p = Themes.current
        val d = resources.displayMetrics.density
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 6 * d
        ring.strokeWidth = 1.5f * d
        for (i in 1..4) {
            ring.color = Ui.withAlpha(p.frame, if (i == 4) 255 else 110)
            canvas.drawCircle(cx, cy, r * i / 4f, ring)
        }
        ring.color = Ui.withAlpha(p.frame, 80)
        canvas.drawLine(cx - r, cy, cx + r, cy, ring)
        canvas.drawLine(cx, cy - r, cx, cy + r, ring)

        if (scanning) {
            oval.set(cx - r, cy - r, cx + r, cy + r)
            for (k in 0 until 6) {
                beam.color = Ui.withAlpha(p.accent2, 70 - k * 11)
                canvas.drawArc(oval, angle - 8f * (k + 1), 8f, true, beam)
            }
            angle = (angle + 3f) % 360f
            postInvalidateOnAnimation()
        }

        for ((s, a) in blips) {
            val h = (s.key.hashCode() and 0x7fffffff) % 360
            val strength = ((s.rssi + 100).coerceIn(0, 70)) / 70f
            val dist = r * (1f - strength * 0.85f)
            val x = cx + dist * cos(Math.toRadians(h.toDouble())).toFloat()
            val y = cy + dist * sin(Math.toRadians(h.toDouble())).toFloat()
            val col = when (a.severity) { 3 -> p.danger; 2 -> p.accent; else -> p.accent2 }
            dot.color = Ui.withAlpha(col, 80)
            canvas.drawCircle(x, y, 9 * d, dot)
            dot.color = col
            canvas.drawCircle(x, y, 4.5f * d, dot)
        }
        dot.color = p.accent
        canvas.drawCircle(cx, cy, 4 * d, dot)
    }
}

/** Equalizer-style bars: Wi-Fi networks per channel, 2.4 GHz on the left, 5 GHz on the right. */
class SpectrumView(ctx: Context) : View(ctx) {
    private var g24 = IntArray(14)
    private var g5 = IntArray(12)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setData(sightings: Collection<Sighting>) {
        val a = IntArray(14)
        val b = IntArray(12)
        for (s in sightings) {
            if (s.kind != Kind.WIFI) continue
            when {
                s.channel in 1..14 && s.freq < 3000 -> a[s.channel - 1]++
                s.freq in 5000..5999 -> b[((s.freq - 5150) / 75).coerceIn(0, 11)]++
            }
        }
        g24 = a; g5 = b
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (110 * resources.displayMetrics.density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val p = Themes.current
        val d = resources.displayMetrics.density
        label.typeface = Fonts.mono
        label.textSize = 11 * d
        label.color = p.dim
        val labelH = 16 * d
        val top = 4 * d
        val bottom = height - labelH
        val blockH = 5 * d
        val gapY = 2 * d
        val maxBlocks = ((bottom - top) / (blockH + gapY)).toInt()
        val all = g24.size + g5.size + 2
        val slot = width / all.toFloat()
        val barW = slot * 0.72f
        val maxVal = maxOf(1, (g24 + g5).maxOrNull() ?: 1)

        fun drawGroup(vals: IntArray, startSlot: Int) {
            for (i in vals.indices) {
                val x = (startSlot + i) * slot + (slot - barW) / 2
                val n = if (vals[i] == 0) 0 else maxOf(1, vals[i] * maxBlocks / maxVal)
                for (k in 0 until maxBlocks) {
                    val y = bottom - (k + 1) * (blockH + gapY)
                    paint.color = when {
                        k >= n -> Ui.withAlpha(p.frame, 45)
                        k > maxBlocks * 0.75 -> p.danger
                        k > maxBlocks * 0.45 -> p.accent
                        else -> p.accent2
                    }
                    canvas.drawRect(x, y, x + barW, y + blockH, paint)
                }
            }
        }
        drawGroup(g24, 0)
        drawGroup(g5, g24.size + 2)
        canvas.drawText("2.4 GHz", 0f, height - 3 * d, label)
        canvas.drawText("5 GHz", (g24.size + 2) * slot, height - 3 * d, label)
        paint.color = p.frame
        canvas.drawRect((g24.size + 0.9f) * slot, top, (g24.size + 1.1f) * slot, bottom, paint)
    }
}
