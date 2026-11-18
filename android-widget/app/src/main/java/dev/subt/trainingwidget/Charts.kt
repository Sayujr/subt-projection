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
    const val SUBT = 0xFFFF9F5A.toInt()
    const val FITNESS_COLOR = 0xFF4EA1FF.toInt()

    /** Maps data to a rectangle on the canvas. */
    private class Frame(val r: RectF, val n: Int, val pad: Float, lo0: Double, hi0: Double, minSpan: Double = 4.0) {
        val lo: Double
        val hi: Double

        init {
            var lo1 = lo0
            var hi1 = hi0
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

    /** Grid spacing in days for the chart length: weekly up to ~2 months, fortnightly, then monthly. */
    private fun gridStep(n: Int) = when {
        n <= 60 -> 7
        n <= 130 -> 14
        else -> 30
    }

    private fun labelPaint(density: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LABEL
        textSize = 9f * density
    }

    /** Vertical grid lines counting back from today; dates along the bottom when [labels]. */
    private fun drawGrid(c: Canvas, f: Frame, days: List<Day>, chartBottom: Float, h: Int, w: Int, density: Float, labels: Boolean) {
        val grid = Paint().apply { color = GRID; strokeWidth = 1f }
        val text = labelPaint(density)
        val step = gridStep(days.size)
        var lastLeft = Float.MAX_VALUE
        var i = days.lastIndex - step
        while (i > 0) {
            val x = f.x(i)
            c.drawLine(x, 0f, x, chartBottom, grid)
            if (labels) {
                val label = shortDate(days[i].date)
                val tw = text.measureText(label)
                val left = x - tw / 2
                // walking right-to-left: skip labels that would collide with the previous one or the edges
                if (left > 0 && x + tw / 2 < w && x + tw / 2 + 4f * density < lastLeft) {
                    c.drawText(label, left, h - 2f * density, text)
                    lastLeft = left
                }
            }
            i -= step
        }
    }

    /** Fitness (filled) + Fatigue (line) on a shared scale inside [r]. */
    private fun drawFitFat(c: Canvas, r: RectF, days: List<Day>, density: Float, bg: Int, labels: Boolean): Frame {
        val ctl = days.map { it.ctl }
        val atl = days.map { it.atl }
        val f = Frame(r, days.size, 4f * density, min(ctl.min(), atl.min()), max(ctl.max(), atl.max()))
        drawFilledLine(c, f, ctl, FormColors.FITNESS, density, bg)
        c.drawPath(pathOf(f, atl), stroke(FormColors.FATIGUE, density, 1.5f))
        dot(c, f.x(atl.lastIndex), f.y(atl.last()), FormColors.FATIGUE, density, bg)
        if (labels) {
            val text = labelPaint(density)
            c.drawText("${max(ctl.max(), atl.max()).roundToInt()}", r.left + 2f * density, r.top + 9f * density, text)
            c.drawText("${min(ctl.min(), atl.min()).roundToInt()}", r.left + 2f * density, r.bottom - 2f * density, text)
        }
        return f
    }

    /** Fitness and Fatigue together in one chart (used when Form is shown separately). */
    fun fitFat(days: List<Day>, w: Int, h: Int, density: Float, bg: Int, labels: Boolean = false): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (days.size < 2) return bmp
        val c = Canvas(bmp)
        val axisH = if (labels) 13f * density else 0f
        val r = RectF(0f, 0f, w.toFloat(), h - axisH)
        val ctl = days.map { it.ctl }
        val atl = days.map { it.atl }
        val gridFrame = Frame(r, days.size, 4f * density, min(ctl.min(), atl.min()), max(ctl.max(), atl.max()))
        drawGrid(c, gridFrame, days, r.bottom, h, w, density, labels)
        drawFitFat(c, r, days, density, bg, labels)
        return bmp
    }

    /**
     * All three in one: Fitness (filled blue) and Fatigue (purple) share the top panel,
     * Form with its zone colouring sits underneath on its own scale.
     * With [labels], date ticks run along the bottom and the panels get value labels.
     */
    fun combined(days: List<Day>, w: Int, h: Int, density: Float, bg: Int, labels: Boolean = false): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (days.size < 2) return bmp
        val c = Canvas(bmp)
        val form = days.map { it.form }

        val axisH = if (labels) 13f * density else 0f
        val chartH = h - axisH
        val gap = 6f * density
        val topH = (chartH - gap) * 0.6f
        val top = RectF(0f, 0f, w.toFloat(), topH)
        val bottom = RectF(0f, topH + gap, w.toFloat(), chartH)

        val ctl = days.map { it.ctl }
        val atl = days.map { it.atl }
        val gridFrame = Frame(top, days.size, 4f * density, min(ctl.min(), atl.min()), max(ctl.max(), atl.max()))
        drawGrid(c, gridFrame, days, chartH, h, w, density, labels)
        drawFitFat(c, top, days, density, bg, labels)
        drawForm(c, bottom, form, density, bg)

        if (labels) {
            val text = labelPaint(density)
            c.drawText(signed(form.max().roundToInt()), 2f * density, bottom.top + 9f * density, text)
            c.drawText(signed(form.min().roundToInt()), 2f * density, bottom.bottom - 2f * density, text)
        }
        return bmp
    }

    // ---------------------------------------------------------------- week bars

    /**
     * Mon–Sun columns: completed load (green), still-planned load (amber, translucent),
     * missed planned load on past days (grey outline). Today's column is highlighted.
     */
    fun weekBars(week: List<Workout>, monday: LocalDate, today: LocalDate, w: Int, h: Int, density: Float): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val dates = (0 until 7).map { monday.plusDays(it.toLong()) }
        val done = dates.map { d -> week.filter { it.date == d.toString() && it.status == WorkoutStatus.DONE }.sumOf { it.load } }
        val planned = dates.map { d -> week.filter { it.date == d.toString() && it.status != WorkoutStatus.DONE }.sumOf { it.load } }
        val max = (0 until 7).maxOf { done[it] + planned[it] }.coerceAtLeast(50)

        val label = labelPaint(density).apply { textSize = 10f * density }
        val number = labelPaint(density).apply { textSize = 10f * density; color = 0xFF9AA3B2.toInt() }
        val topPad = 14f * density
        val bottomPad = 15f * density
        val colW = w / 7f
        val barW = colW * 0.56f
        val chartH = h - topPad - bottomPad
        val doneP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3DD68C.toInt() }
        val planP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFC53D.toInt() }
        val missP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.2f * density; color = 0x889AA3B2.toInt()
            pathEffect = DashPathEffect(floatArrayOf(3f * density, 2f * density), 0f)
        }
        val todayBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1AFFFFFF }
        val base = Paint().apply { color = GRID; strokeWidth = 1f }
        c.drawLine(0f, topPad + chartH, w.toFloat(), topPad + chartH, base)

        val r = 3f * density
        for (i in 0 until 7) {
            val d = dates[i]
            val cx = colW * i + colW / 2
            val left = cx - barW / 2
            val right = cx + barW / 2
            if (d == today) c.drawRoundRect(colW * i + 2f, 0f, colW * (i + 1) - 2f, h.toFloat(), 8f * density, 8f * density, todayBg)
            var y = topPad + chartH
            if (done[i] > 0) {
                val bh = chartH * done[i] / max
                c.drawRoundRect(left, y - bh, right, y, r, r, doneP)
                y -= bh
            }
            if (planned[i] > 0) {
                val bh = chartH * planned[i] / max
                if (d.isBefore(today)) c.drawRoundRect(left, y - bh, right, y, r, r, missP)
                else c.drawRoundRect(left, y - bh, right, y, r, r, planP)
                y -= bh
            }
            val total = done[i] + planned[i]
            if (total > 0) {
                val t = total.toString()
                c.drawText(t, cx - number.measureText(t) / 2, y - 3f * density, number)
            }
            val dl = d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()).take(3)
            label.color = if (d == today) 0xFFF2F4F8.toInt() else LABEL
            label.isFakeBoldText = d == today
            c.drawText(dl, cx - label.measureText(dl) / 2, h - 3f * density, label)
        }
        return bmp
    }

    // ---------------------------------------------------------------- SubT pace

    fun paceText(minPerKm: Double): String {
        val total = (minPerKm * 60).roundToInt()
        return "${total / 60}:${"%02d".format(total % 60)}"
    }

    /**
     * Monthly median SubT pace, faster = higher. Outlier months are hollow and left out of the line
     * and trend. A dashed least-squares trend runs through the last six regular months.
     */
    fun subtPace(months: List<SubtMonth>, w: Int, h: Int, density: Float, bg: Int, labels: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (months.size < 2) return bmp
        val c = Canvas(bmp)
        val color = SUBT
        val axisH = if (labels) 13f * density else 0f
        val leftPad = if (labels) 26f * density else 0f
        val r = RectF(leftPad, 0f, w.toFloat(), h - axisH)
        // Negate pace so faster (smaller) plots higher
        val vals = months.map { -it.pace }
        // Scale to regular months only; outliers are pinned to the chart edge so they can't squash it
        val scale = months.indices.filter { !months[it].outlier }.map { vals[it] }.ifEmpty { vals }
        val f = Frame(r, months.size, 5f * density, scale.min(), scale.max(), minSpan = 0.15)
        fun yy(i: Int) = f.y(vals[i]).coerceIn(r.top + 3f * density, r.bottom - 3f * density)

        if (labels) {
            val text = labelPaint(density)
            c.drawText(paceText(-f.hi), 0f, r.top + 9f * density, text)
            c.drawText(paceText(-f.lo), 0f, r.bottom - 2f * density, text)
            var last = -1f
            months.forEachIndexed { i, m ->
                val lbl = runCatching {
                    LocalDate.parse(m.month + "-01").month.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault())
                }.getOrDefault("")
                val x = f.x(i) - text.measureText(lbl) / 2
                if (x > last + 2f * density) {
                    c.drawText(lbl, x, h - 2f * density, text)
                    last = x + text.measureText(lbl)
                }
            }
        }

        val regular = months.indices.filter { !months[it].outlier }
        val path = Path()
        regular.forEachIndexed { k, i -> if (k == 0) path.moveTo(f.x(i), yy(i)) else path.lineTo(f.x(i), yy(i)) }
        c.drawPath(path, stroke(color, density))

        // Trend over the last six regular months
        val tail = regular.takeLast(6)
        if (tail.size >= 3) {
            val xs = tail.map { it.toDouble() }
            val ys = tail.map { vals[it] }
            val mx = xs.average(); val my = ys.average()
            val slope = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / xs.indices.sumOf { (xs[it] - mx) * (xs[it] - mx) }
            val x0 = tail.first(); val x1 = months.lastIndex
            val trend = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 1.2f * density; this.color = withAlpha(color, 0x99)
                pathEffect = DashPathEffect(floatArrayOf(4f * density, 3f * density), 0f)
            }
            c.drawLine(f.x(x0), f.y(my + slope * (x0 - mx)), f.x(x1), f.y(my + slope * (x1 - mx)), trend)
        }

        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * density; this.color = LABEL }
        months.forEachIndexed { i, m ->
            if (m.outlier) {
                c.drawCircle(f.x(i), yy(i), 2.6f * density, ring)
            } else {
                fill.color = bg; c.drawCircle(f.x(i), yy(i), 3.4f * density, fill)
                fill.color = color; c.drawCircle(f.x(i), yy(i), (if (i == months.lastIndex) 3f else 2.2f) * density, fill)
            }
        }
        return bmp
    }

    /** Least-squares slope (min/km per month) over the last six regular months, or null. */
    fun subtTrendPerMonth(months: List<SubtMonth>): Double? {
        val reg = months.withIndex().filter { !it.value.outlier }.takeLast(6)
        if (reg.size < 3) return null
        val xs = reg.map { it.index.toDouble() }
        val ys = reg.map { it.value.pace }
        val mx = xs.average(); val my = ys.average()
        val den = xs.sumOf { (it - mx) * (it - mx) }
        if (den == 0.0) return null
        return xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / den
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
