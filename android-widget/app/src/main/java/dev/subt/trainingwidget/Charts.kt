package dev.subt.trainingwidget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Trend-line bitmaps for the widgets. */
object Charts {

    private const val TILE_BG = 0xFF222733.toInt()
    private const val GRID = 0x339AA3B2
    private const val LABEL = 0xFF6B7386.toInt()

    /** Maps data to a rectangle on the canvas. */
    private class Frame(val r: RectF, val n: Int, val pad: Float, lo0: Double, hi0: Double) {
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

        fun x(i: Int): Float = r.left + pad + i * (r.width() - 2 * pad) / max(1, n - 1)
        fun y(v: Double): Float = (r.top + pad + (1 - (v - lo) / (hi - lo)) * (r.height() - 2 * pad)).toFloat()
    }

    private fun stroke(color: Int, density: Float, width: Float = 1.8f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        this.color = color
    }

    private fun dot(c: Canvas, x: Float, y: Float, color: Int, density: Float, bg: Int = TILE_BG) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = bg
        c.drawCircle(x, y, 3.6f * density, p)
        p.color = color
        c.drawCircle(x, y, 2.5f * density, p)
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    private fun pathOf(f: Frame, values: List<Double>) = Path().apply {
        values.forEachIndexed { i, v -> if (i == 0) moveTo(f.x(i), f.y(v)) else lineTo(f.x(i), f.y(v)) }
    }

    private fun drawFilledLine(c: Canvas, f: Frame, values: List<Double>, color: Int, density: Float, bg: Int) {
        val line = pathOf(f, values)
        val fill = Path(line).apply {
            lineTo(f.x(values.lastIndex), f.r.bottom)
            lineTo(f.x(0), f.r.bottom)
            close()
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, f.r.top, 0f, f.r.bottom,
                withAlpha(color, 0x70), withAlpha(color, 0x00), Shader.TileMode.CLAMP
            )
        }
        c.drawPath(fill, fillPaint)
        c.drawPath(line, stroke(color, density))
        dot(c, f.x(values.lastIndex), f.y(values.last()), color, density, bg)
    }

    private fun drawForm(c: Canvas, r: RectF, values: List<Double>, density: Float, bg: Int) {
        val f = Frame(r, values.size, 4f * density, min(values.min(), 0.0), max(values.max(), 0.0))
        val band = Paint()
        for (z in FormZone.entries) {
            val top = f.y(min(z.upper, f.hi)).coerceIn(r.top, r.bottom)
            val bottom = f.y(max(z.lower, f.lo)).coerceIn(r.top, r.bottom)
            if (bottom - top < 0.5f) continue
            band.color = withAlpha(z.color, 0x24)
            c.drawRect(r.left, top, r.right, bottom, band)
        }
        val zero = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
            color = 0x669AA3B2
            pathEffect = DashPathEffect(floatArrayOf(3f * density, 3f * density), 0f)
        }
        val zy = f.y(0.0)
        c.drawLine(r.left, zy, r.right, zy, zero)
        for (i in 1 until values.size) {
            c.drawLine(
                f.x(i - 1), f.y(values[i - 1]), f.x(i), f.y(values[i]),
                stroke(FormZone.of(values[i]).color, density)
            )
        }
        dot(c, f.x(values.lastIndex), f.y(values.last()), FormZone.of(values.last()).color, density, bg)
    }

    /** Single line with gradient fill (Fitness or Fatigue tile). */
    fun line(values: List<Double>, color: Int, w: Int, h: Int, density: Float): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (values.size < 2) return bmp
        val r = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val f = Frame(r, values.size, 4f * density, values.min(), values.max())
        drawFilledLine(Canvas(bmp), f, values, color, density, TILE_BG)
        return bmp
    }

    /** Form line coloured by zone over shaded zone bands (Form tile). */
    fun form(values: List<Double>, w: Int, h: Int, density: Float): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (values.size < 2) return bmp
        drawForm(Canvas(bmp), RectF(0f, 0f, w.toFloat(), h.toFloat()), values, density, TILE_BG)
        return bmp
    }

    /**
     * All three in one: Fitness (filled blue) and Fatigue (purple) share the top panel,
     * Form with its zone colouring sits underneath on its own scale.
     * With [labels], weekly date ticks run along the bottom and the panels get value labels.
     */
    fun combined(days: List<Day>, w: Int, h: Int, density: Float, bg: Int, labels: Boolean = false): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (days.size < 2) return bmp
        val c = Canvas(bmp)
        val ctl = days.map { it.ctl }
        val atl = days.map { it.atl }
        val form = days.map { it.form }

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LABEL
            textSize = 9f * density
        }
        val axisH = if (labels) 13f * density else 0f
        val chartH = h - axisH
        val gap = 6f * density
        val topH = (chartH - gap) * 0.6f
        val top = RectF(0f, 0f, w.toFloat(), topH)
        val bottom = RectF(0f, topH + gap, w.toFloat(), chartH)

        val ft = Frame(top, days.size, 4f * density, min(ctl.min(), atl.min()), max(ctl.max(), atl.max()))

        // Weekly grid lines counting back from today, with dates underneath
        val grid = Paint().apply { color = GRID; strokeWidth = 1f }
        var i = days.lastIndex - 7
        while (i > 0) {
            val x = ft.x(i)
            c.drawLine(x, 0f, x, chartH, grid)
            if (labels) {
                val label = shortDate(days[i].date)
                val tw = text.measureText(label)
                if (x - tw / 2 > 0 && x + tw / 2 < w) c.drawText(label, x - tw / 2, h - 2f * density, text)
            }
            i -= 7
        }

        drawFilledLine(c, ft, ctl, FormColors.FITNESS, density, bg)
        c.drawPath(pathOf(ft, atl), stroke(FormColors.FATIGUE, density, 1.5f))
        dot(c, ft.x(atl.lastIndex), ft.y(atl.last()), FormColors.FATIGUE, density, bg)

        drawForm(c, bottom, form, density, bg)

        if (labels) {
            // Range of the Fitness/Fatigue panel, and the Form panel's extremes
            val hiV = max(ctl.max(), atl.max()).roundToInt()
            val loV = min(ctl.min(), atl.min()).roundToInt()
            c.drawText("$hiV", 2f * density, top.top + 9f * density, text)
            c.drawText("$loV", 2f * density, top.bottom - 2f * density, text)
            val fHi = form.max().roundToInt()
            val fLo = form.min().roundToInt()
            c.drawText(signed(fHi), 2f * density, bottom.top + 9f * density, text)
            c.drawText(signed(fLo), 2f * density, bottom.bottom - 2f * density, text)
        }
        return bmp
    }

    private fun signed(v: Int) = if (v > 0) "+$v" else "$v"

    private fun shortDate(iso: String): String = try {
        LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
    } catch (e: Exception) {
        ""
    }
}

object FormColors {
    const val FITNESS = 0xFF4EA1FF.toInt()
    const val FATIGUE = 0xFFC17BFF.toInt()
}
