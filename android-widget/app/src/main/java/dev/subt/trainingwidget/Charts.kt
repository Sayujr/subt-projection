package dev.subt.trainingwidget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import kotlin.math.max
import kotlin.math.min

/** Small trend-line bitmaps for the metric tiles. */
object Charts {

    private class Frame(values: List<Double>, val w: Int, val h: Int, val pad: Float, lo0: Double, hi0: Double) {
        val n = values.size
        val lo: Double
        val hi: Double

        init {
            var lo1 = lo0
            var hi1 = hi0
            val minSpan = 4.0
            if (hi1 - lo1 < minSpan) {
                val mid = (hi1 + lo1) / 2
                lo1 = mid - minSpan / 2; hi1 = mid + minSpan / 2
            }
            val margin = (hi1 - lo1) * 0.08
            lo = lo1 - margin; hi = hi1 + margin
        }

        fun x(i: Int): Float = pad + i * (w - 2 * pad) / max(1, n - 1)
        fun y(v: Double): Float = (pad + (1 - (v - lo) / (hi - lo)) * (h - 2 * pad)).toFloat()
    }

    private fun stroke(color: Int, density: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        this.color = color
    }

    private fun dot(c: Canvas, x: Float, y: Float, color: Int, density: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFF222733.toInt()
        c.drawCircle(x, y, 3.6f * density, p)
        p.color = color
        c.drawCircle(x, y, 2.5f * density, p)
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    /** Smooth line with a soft gradient fill underneath (Fitness, Fatigue). */
    fun line(values: List<Double>, color: Int, w: Int, h: Int, density: Float): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (values.size < 2) return bmp
        val c = Canvas(bmp)
        val f = Frame(values, w, h, 4f * density, values.min(), values.max())

        val line = Path()
        values.forEachIndexed { i, v -> if (i == 0) line.moveTo(f.x(i), f.y(v)) else line.lineTo(f.x(i), f.y(v)) }

        val fill = Path(line).apply {
            lineTo(f.x(values.lastIndex), h.toFloat())
            lineTo(f.x(0), h.toFloat())
            close()
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                withAlpha(color, 0x70), withAlpha(color, 0x00), Shader.TileMode.CLAMP
            )
        }
        c.drawPath(fill, fillPaint)
        c.drawPath(line, stroke(color, density))
        dot(c, f.x(values.lastIndex), f.y(values.last()), color, density)
        return bmp
    }

    /** Form line coloured segment-by-segment by zone, over faint zone bands and a zero line. */
    fun form(values: List<Double>, w: Int, h: Int, density: Float): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (values.size < 2) return bmp
        val c = Canvas(bmp)
        val f = Frame(values, w, h, 4f * density, min(values.min(), 0.0), max(values.max(), 0.0))

        // Zone bands
        val band = Paint()
        for (z in FormZone.entries) {
            val top = f.y(min(z.upper, f.hi)).coerceIn(0f, h.toFloat())
            val bottom = f.y(max(z.lower, f.lo)).coerceIn(0f, h.toFloat())
            if (bottom - top < 0.5f) continue
            band.color = withAlpha(z.color, 0x24)
            c.drawRect(0f, top, w.toFloat(), bottom, band)
        }

        // Zero line
        val zero = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
            color = 0x669AA3B2
            pathEffect = DashPathEffect(floatArrayOf(3f * density, 3f * density), 0f)
        }
        val zy = f.y(0.0)
        c.drawLine(0f, zy, w.toFloat(), zy, zero)

        // Coloured segments
        for (i in 1 until values.size) {
            val p = stroke(FormZone.of(values[i]).color, density)
            c.drawLine(f.x(i - 1), f.y(values[i - 1]), f.x(i), f.y(values[i]), p)
        }
        dot(c, f.x(values.lastIndex), f.y(values.last()), FormZone.of(values.last()).color, density)
        return bmp
    }
}
