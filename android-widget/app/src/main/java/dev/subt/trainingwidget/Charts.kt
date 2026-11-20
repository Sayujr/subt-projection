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
    private const val LEFT_LABELS = 24f
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

    private fun dashedStroke(color: Int, density: Float, width: Float = 1.8f) = stroke(color, density, width).apply {
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 3.5f * density), 0f)
    }

    /** Line with gradient fill; points after [split] (planned future) are dashed and unfilled. */
    private fun drawFilledLine(c: Canvas, f: Frame, values: List<Double>, color: Int, density: Float, bg: Int, split: Int = values.lastIndex) {
        val hist = values.take(split + 1)
        val line = Path()
        hist.forEachIndexed { i, v -> if (i == 0) line.moveTo(f.x(i), f.y(v)) else line.lineTo(f.x(i), f.y(v)) }
        val fill = Path(line).apply {
            lineTo(f.x(hist.lastIndex), f.r.bottom)
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
        if (split < values.lastIndex) {
            val fut = Path().apply {
                moveTo(f.x(split), f.y(values[split]))
                for (i in split + 1..values.lastIndex) lineTo(f.x(i), f.y(values[i]))
            }
            c.drawPath(fut, dashedStroke(withAlpha(color, 0xCC), density))
        }
        dot(c, f.x(split), f.y(values[split]), color, density, bg)
    }

    private fun drawForm(c: Canvas, r: RectF, values: List<Double>, density: Float, bg: Int, labels: Boolean = false, split: Int = values.lastIndex): Frame {
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
            val col = FormZone.of(values[i]).color
            c.drawLine(
                f.x(i - 1), f.y(values[i - 1]), f.x(i), f.y(values[i]),
                if (i > split) dashedStroke(withAlpha(col, 0xCC), density) else stroke(col, density)
            )
        }
        dot(c, f.x(split), f.y(values[split]), FormZone.of(values[split]).color, density, bg)
        if (labels) {
            // Zone boundaries as dotted lines with their values, zone names at the right edge
            val text = labelPaint(density)
            val dotted = dottedPaint(density)
            for (t in listOf(-30.0, -10.0, 5.0, 25.0)) {
                if (t <= f.lo || t >= f.hi) continue
                c.drawLine(r.left, f.y(t), r.right, f.y(t), dotted)
                c.drawText(if (t > 0) "+${t.toInt()}" else "${t.toInt()}", 0f, f.y(t) + 3.5f * density, text)
            }
            c.drawText("0", 0f, f.y(0.0) + 3.5f * density, text)
            val name = labelPaint(density).apply { textSize = 8.5f * density }
            for (z in FormZone.entries) {
                val top = f.y(min(z.upper, f.hi)).coerceIn(r.top, r.bottom)
                val bottom = f.y(max(z.lower, f.lo)).coerceIn(r.top, r.bottom)
                if (bottom - top < 11f * density) continue
                name.color = withAlpha(z.color, 0xAA)
                val tw = name.measureText(z.label)
                c.drawText(z.label, r.right - tw - 3f * density, (top + bottom) / 2 + 3f * density, name)
            }
        }
        return f
    }

    private fun dottedPaint(density: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x449AA3B2
        pathEffect = DashPathEffect(floatArrayOf(1.5f * density, 3f * density), 0f)
    }

    /** A "nice" gridline step (1, 2, 5 × 10ⁿ) giving about [target] lines over [range]. */
    private fun niceStep(range: Double, target: Int = 4): Double {
        val raw = range / target
        val mag = Math.pow(10.0, Math.floor(Math.log10(raw)))
        val n = raw / mag
        return mag * when { n < 1.5 -> 1.0; n < 3 -> 2.0; n < 7 -> 5.0; else -> 10.0 }
    }

    /** Dotted horizontal gridlines with value labels in the left margin. */
    private fun drawValueGrid(c: Canvas, f: Frame, r: RectF, density: Float) {
        val text = labelPaint(density)
        val dotted = dottedPaint(density)
        val step = niceStep(f.hi - f.lo)
        var v = Math.ceil(f.lo / step) * step
        while (v <= f.hi) {
            val y = f.y(v)
            if (y > r.top + 4f * density && y < r.bottom - 2f * density) {
                c.drawLine(r.left, y, r.right, y, dotted)
                c.drawText(v.roundToInt().toString(), 0f, y + 3.5f * density, text)
            }
            v += step
        }
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
        // Short ranges (This week, 2 weeks): a line and a weekday + date label for every day
        val daily = days.size <= 16
        val step = if (daily) 1 else gridStep(days.size)
        var lastLeft = Float.MAX_VALUE
        var i = if (daily) days.lastIndex else days.lastIndex - step
        val first = if (daily) 0 else 1
        val wd = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
        while (i >= first) {
            val x = f.x(i)
            if (!daily || (i in 1 until days.lastIndex)) c.drawLine(x, 0f, x, chartBottom, grid)
            if (labels) {
                val label = if (daily) runCatching { LocalDate.parse(days[i].date).let { d -> d.format(wd).take(2) + " " + d.dayOfMonth } }.getOrDefault(days[i].date)
                    else shortDate(days[i].date)
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
    private fun drawFitFat(c: Canvas, r: RectF, days: List<Day>, density: Float, bg: Int, labels: Boolean, split: Int = days.lastIndex): Frame {
        val ctl = days.map { it.ctl }
        val atl = days.map { it.atl }
        val f = Frame(r, days.size, 4f * density, min(ctl.min(), atl.min()), max(ctl.max(), atl.max()))
        drawFilledLine(c, f, ctl, FormColors.FITNESS, density, bg, split)
        c.drawPath(pathOf(f, atl.take(split + 1)), stroke(FormColors.FATIGUE, density, 1.5f))
        if (split < atl.lastIndex) {
            val fut = Path().apply { moveTo(f.x(split), f.y(atl[split])); for (i in split + 1..atl.lastIndex) lineTo(f.x(i), f.y(atl[i])) }
            c.drawPath(fut, dashedStroke(withAlpha(FormColors.FATIGUE, 0xCC), density, 1.5f))
        }
        dot(c, f.x(split), f.y(atl[split]), FormColors.FATIGUE, density, bg)
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
        val r = RectF(if (labels) LEFT_LABELS * density else 0f, 0f, w.toFloat(), h - axisH)
        val ctl = days.map { it.ctl }
        val atl = days.map { it.atl }
        val gridFrame = Frame(r, days.size, 4f * density, min(ctl.min(), atl.min()), max(ctl.max(), atl.max()))
        if (labels) drawValueGrid(c, gridFrame, r, density)
        drawGrid(c, gridFrame, days, r.bottom, h, w, density, labels)
        drawFitFat(c, r, days, density, bg, labels = false)
        return bmp
    }

    /**
     * All three in one: Fitness (filled blue) and Fatigue (purple) share the top panel,
     * Form with its zone colouring sits underneath on its own scale.
     * With [labels], date ticks run along the bottom and the panels get value labels.
     */
    fun combined(
        history: List<Day>, w: Int, h: Int, density: Float, bg: Int, labels: Boolean = false, highlight: Int? = null,
        future: List<Day> = emptyList(),
    ): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (history.size < 2) return bmp
        // History then the planned days ahead (dashed); split = today
        val days = history + future.filter { it.date > history.last().date }
        val split = history.lastIndex
        val c = Canvas(bmp)
        val form = days.map { it.form }

        val left = if (labels) LEFT_LABELS * density else 0f
        val axisH = if (labels) 13f * density else 0f
        val chartH = h - axisH
        val gap = 6f * density
        val topH = (chartH - gap) * 0.6f
        val top = RectF(left, 0f, w.toFloat(), topH)
        val bottom = RectF(left, topH + gap, w.toFloat(), chartH)

        val ctl = days.map { it.ctl }
        val atl = days.map { it.atl }
        val ft = Frame(top, days.size, 4f * density, min(ctl.min(), atl.min()), max(ctl.max(), atl.max()))
        if (labels) drawValueGrid(c, ft, top, density)
        drawGrid(c, ft, days, chartH, h, w, density, labels)
        drawFitFat(c, top, days, density, bg, labels = false, split = split)
        val fb = drawForm(c, bottom, form, density, bg, labels, split)
        if (split < days.lastIndex) {
            val x = ft.x(split)
            val p = Paint().apply { color = 0x44FFFFFF; strokeWidth = 1f * density }
            c.drawLine(x, 0f, x, chartH, p)
            if (labels) {
                val t = labelPaint(density).apply { color = 0xFFF2F4F8.toInt() }
                c.drawText("today", (x - t.measureText("today") - 3f * density).coerceAtLeast(left), 10f * density, t)
                val pl = labelPaint(density)
                c.drawText("plan", x + 3f * density, 10f * density, pl)
            }
        }

        if (labels && split >= 9) periodStats(c, ft, top, bottom, ctl, form, split, density, bg, days, chartH, h)

        if (highlight != null && highlight in days.indices) {
            val x = ft.x(highlight)
            val line = Paint().apply { color = 0x99FFFFFF.toInt(); strokeWidth = 1.2f * density }
            c.drawLine(x, 0f, x, chartH, line)
            dot(c, x, ft.y(ctl[highlight]), FormColors.FITNESS, density, bg)
            dot(c, x, ft.y(atl[highlight]), FormColors.FATIGUE, density, bg)
            dot(c, x, fb.y(form[highlight]), FormZone.of(form[highlight]).color, density, bg)
        }
        return bmp
    }

    /** Rise over the period from a least-squares line: +1 up, −1 down, 0 flat (|change| below [flat]). */
    fun trend(v: List<Double>, flat: Double): Int {
        if (v.size < 3) return 0
        val n = v.size; val mx = (n - 1) / 2.0; val my = v.average()
        var num = 0.0; var den = 0.0
        for (i in v.indices) { num += (i - mx) * (v[i] - my); den += (i - mx) * (i - mx) }
        val change = if (den == 0.0) 0.0 else num / den * (n - 1)
        return if (change > flat) 1 else if (change < -flat) -1 else 0
    }

    /** Stemless chevron: up, down, or a short dash for flat. */
    private fun chevron(c: Canvas, cx: Float, cy: Float, dir: Int, color: Int, density: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.7f * density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; this.color = color
        }
        val s = 3.4f * density
        val path = Path()
        when (dir) {
            1 -> { path.moveTo(cx - s, cy + s / 2); path.lineTo(cx, cy - s / 2); path.lineTo(cx + s, cy + s / 2) }
            -1 -> { path.moveTo(cx - s, cy - s / 2); path.lineTo(cx, cy + s / 2); path.lineTo(cx + s, cy - s / 2) }
            else -> { path.moveTo(cx - s, cy); path.lineTo(cx + s, cy) }
        }
        c.drawPath(path, p)
    }

    /**
     * Period extras for the multi-week views: a gold dot on the period's peak Fitness, and small
     * "avg" tags at the right with a stemless trend chevron for Fitness and Form (history only).
     */
    private fun periodStats(
        c: Canvas, ft: Frame, top: RectF, @Suppress("UNUSED_PARAMETER") bottom: RectF, ctl: List<Double>, form: List<Double>, split: Int,
        density: Float, bg: Int, days: List<Day>, chartBottom: Float, h: Int,
    ) {
        val gold = 0xFFFFC53D.toInt()
        val hist = ctl.subList(0, split + 1)
        val pi = hist.indices.maxByOrNull { hist[it] } ?: return
        val px = ft.x(pi); val py = ft.y(hist[pi])
        val pt = labelPaint(density).apply { color = gold; textSize = 9.5f * density; isFakeBoldText = true }
        val ptxt = "peak ${hist[pi].roundToInt()}"
        val tx = (px - pt.measureText(ptxt) / 2).coerceIn(top.left + 2f * density, top.right - pt.measureText(ptxt) - 2f * density)
        c.drawText(ptxt, tx, (py - 7f * density).coerceAtLeast(top.top + 9f * density), pt)
        // Dotted gold drop line to the date axis, and the peak's date there in gold
        val drop = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(gold, 0xB0); strokeWidth = 1.2f * density; style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(2f * density, 3f * density), 0f)
        }
        c.drawLine(px, py + 4f * density, px, chartBottom, drop)
        val dl = labelPaint(density).apply { color = gold; isFakeBoldText = true }
        val dtxt = shortDate(days[pi].date)
        val dw = dl.measureText(dtxt)
        val dx = (px - dw / 2).coerceIn(top.left, top.right - dw)
        // cover any regular date label underneath, then write the peak date
        c.drawRect(dx - 3f * density, chartBottom + 0.5f * density, dx + dw + 3f * density, h.toFloat(), Paint().apply { color = bg })
        c.drawText(dtxt, dx, h - 2f * density, dl)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg }
        c.drawCircle(px, py, 4.6f * density, ring)
        ring.color = gold
        c.drawCircle(px, py, 3.4f * density, ring)

        // Averages + trend, as small tags at the right edge
        val fHist = form.subList(0, split + 1)
        // Just the period averages in their colours, each with its trend chevron (Fitness above, Form below)
        @Suppress("UNUSED_PARAMETER")
        fun tag(y: Float, name: String, value: String, valueColor: Int, dir: Int, dirColor: Int) {
            val vp = labelPaint(density).apply { textSize = 11f * density; color = valueColor; isFakeBoldText = true }
            val wVal = vp.measureText(value); val wChev = 9f * density
            val padH = 4f * density
            val w = padH + wVal + 3f * density + wChev + padH
            val right = top.right - 3f * density
            val rect = RectF(right - w, y - 10f * density, right, y + 4f * density)
            c.drawRoundRect(rect, 4f * density, 4f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(bg, 0xCC) })
            val x = rect.left + padH
            c.drawText(value, x, y, vp)
            chevron(c, x + wVal + 3f * density + wChev / 2, y - 3.8f * density, dir, dirColor, density)
        }
        val avgF = hist.average(); val fd = trend(hist, 1.0)
        tag(top.top + 24f * density, "avg Fitness", avgF.roundToInt().toString(), FormColors.FITNESS, fd,
            if (fd > 0) 0xFF3DD68C.toInt() else if (fd < 0) 0xFFFF7A7A.toInt() else LABEL)
        val avgForm = fHist.average(); val md = trend(fHist, 2.0)
        val fz = FormZone.of(avgForm)
        tag(top.top + 38f * density, "avg Form", (if (avgForm > 0.5) "+" else "") + avgForm.roundToInt(), fz.color, md, fz.color)
    }

    /** Which day a touch at [x] (in bitmap pixels) falls on, matching [combined]'s layout. */
    fun combinedIndexAt(x: Float, n: Int, w: Int, density: Float, labels: Boolean): Int {
        val left = if (labels) LEFT_LABELS * density else 0f
        val pad = 4f * density
        val span = (w - left - 2 * pad) / max(1, n - 1)
        return ((x - left - pad) / span).roundToInt().coerceIn(0, n - 1)
    }

    // ---------------------------------------------------------------- week bars

    /** Plan comparison for a finished day: 0 = on plan (± tolerance), 1 = more than planned, 2 = less, null = nothing planned. */
    fun planMark(done: Int, planned: Int, tolPct: Int): Int? = when {
        planned <= 0 -> null
        // whole-number maths, so exactly ±tolerance counts as on plan
        done * 100 > planned * (100 + tolPct) -> 1
        done * 100 < planned * (100 - tolPct) -> 2
        else -> 0
    }

    /** Mon–Sun columns for the week widget. */
    fun weekBars(week: List<Workout>, monday: LocalDate, today: LocalDate, w: Int, h: Int, density: Float, tolPct: Int = 10): Bitmap =
        rangeBars(week, monday, monday.plusDays(6), today, w, h, density, tolPct)

    /**
     * Load bars over [start]..[end]: one per day up to ~5 weeks, otherwise one per week (Mon–Sun).
     * Completed load is green, missed planned load (before today) a dashed outline, and still-to-do
     * planned load amber. The bar containing today is highlighted.
     */
    fun rangeBars(items: List<Workout>, start: LocalDate, end: LocalDate, today: LocalDate, w: Int, h: Int, density: Float, tolPct: Int = 10): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val nDays = java.time.temporal.ChronoUnit.DAYS.between(start, end).toInt() + 1
        val daily = nDays <= 35
        val bucketStarts: List<LocalDate> = if (daily) {
            (0 until nDays).map { start.plusDays(it.toLong()) }
        } else {
            val first = start.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            generateSequence(first) { it.plusWeeks(1) }.takeWhile { !it.isAfter(end) }.toList()
        }
        val len = if (daily) 0L else 6L
        fun inBucket(i: Int, date: String): Boolean {
            val b0 = bucketStarts[i].toString()
            val b1 = bucketStarts[i].plusDays(len).toString()
            return date in b0..b1
        }
        val t = today.toString()
        val n = bucketStarts.size
        val done = IntArray(n); val missed = IntArray(n); val todo = IntArray(n); val planned = IntArray(n)
        for (wk in items) for (i in 0 until n) if (inBucket(i, wk.date)) {
            when {
                wk.status == WorkoutStatus.DONE -> done[i] += wk.load
                wk.date < t -> missed[i] += wk.load
                else -> todo[i] += wk.load
            }
            planned[i] += if (wk.status == WorkoutStatus.DONE) wk.plannedLoad else wk.load
            break
        }
        val max = (0 until n).maxOfOrNull { maxOf(done[it] + missed[it] + todo[it], planned[it]) }?.coerceAtLeast(50) ?: 50

        val label = labelPaint(density).apply { textSize = 10f * density }
        val colW = w / n.toFloat()
        val showNumbers = colW >= 22f * density
        val markH = 9f * density
        val topPad = if (showNumbers) 15f * density + markH else 4f * density
        val bottomPad = 15f * density
        val barW = colW * (if (n <= 7) 0.56f else 0.7f)
        val chartH = h - topPad - bottomPad
        val doneP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3DD68C.toInt() }
        val planP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFC53D.toInt() }
        val missP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.2f * density; color = 0x889AA3B2.toInt()
            pathEffect = DashPathEffect(floatArrayOf(3f * density, 2f * density), 0f)
        }
        val doneT = labelPaint(density).apply { textSize = 10.5f * density; color = 0xFF3DD68C.toInt(); isFakeBoldText = true }
        val missT = labelPaint(density).apply { textSize = 9.5f * density; color = 0xFF9AA3B2.toInt() }
        val todoT = labelPaint(density).apply { textSize = 10f * density; color = 0xFFFFC53D.toInt() }
        val todoInT = labelPaint(density).apply { textSize = 9.5f * density; color = 0xFF161A22.toInt(); isFakeBoldText = true }
        val todayBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1AFFFFFF }
        val base = Paint().apply { color = GRID; strokeWidth = 1f }
        c.drawLine(0f, topPad + chartH, w.toFloat(), topPad + chartH, base)

        /** ✓ = on plan (± tolerance), chevron up = more than planned, chevron down = less. No arrow stems. */
        fun mark(cx: Float, y: Float, kind: Int) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 1.8f * density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                color = when (kind) { 0 -> 0xFF3DD68C.toInt(); 1 -> 0xFF4EA1FF.toInt(); else -> 0xFFFF7A7A.toInt() }
            }
            val s2 = 3.5f * density
            val path = Path()
            when (kind) {
                0 -> { path.moveTo(cx - s2, y); path.lineTo(cx - s2 / 3, y + s2 * 0.8f); path.lineTo(cx + s2 * 1.1f, y - s2 * 0.7f) }
                1 -> { path.moveTo(cx - s2, y + s2 / 2); path.lineTo(cx, y - s2 / 2); path.lineTo(cx + s2, y + s2 / 2) }
                else -> { path.moveTo(cx - s2, y - s2 / 2); path.lineTo(cx, y + s2 / 2); path.lineTo(cx + s2, y - s2 / 2) }
            }
            c.drawPath(path, p)
        }

        val sample = if (daily && n <= 7) "Wed" else if (daily) "30" else "30 Nov"
        val k = maxOf(1, kotlin.math.ceil((label.measureText(sample) + 6f * density) / colW).toInt())
        val r = (if (n <= 14) 3f else 1.5f) * density
        for (i in 0 until n) {
            val cx = colW * i + colW / 2
            val left = cx - barW / 2
            val right = cx + barW / 2
            val isToday = inBucket(i, t)
            val bucketEnd = bucketStarts[i].plusDays(len).toString()
            val finished = bucketEnd < t || (isToday && todo[i] == 0 && done[i] > 0)
            if (isToday) c.drawRoundRect(colW * i + 1f, 0f, colW * (i + 1) - 1f, h.toFloat(), 6f * density, 6f * density, todayBg)
            var y = topPad + chartH
            val baseY = topPad + chartH
            fun hOf(v: Int) = chartH * v / max
            fun seg(v: Int, p: Paint): Pair<Float, Float>? {
                if (v <= 0) return null
                val bh = hOf(v)
                c.drawRoundRect(left, y - bh, right, y, r, r, p)
                val span = y - bh to y
                y -= bh
                return span
            }
            var labelY: Float
            if (finished) {
                // Finished: the dashed outline is the plan, the green bar is what you did.
                // Gap above the bar = short of plan; bar above the outline = more than plan.
                val pl = planned[i]; val dn = done[i]
                seg(dn, doneP)
                val planTop = baseY - hOf(pl)
                if (pl > 0) c.drawRoundRect(left, planTop, right, baseY, r, r, missP)
                val top = minOf(y, if (pl > 0) planTop else y)
                labelY = top - 3f * density
                if (showNumbers) {
                    val short = pl - dn
                    if (pl > 0 && short > 0 && (y - planTop) >= 12f * density) {
                        val sTxt = short.toString()
                        c.drawText(sTxt, cx - missT.measureText(sTxt) / 2, (planTop + y) / 2 + 3.5f * density, missT)
                    }
                    if (dn > 0 || pl > 0) {
                        val sTxt = dn.toString()
                        c.drawText(sTxt, cx - doneT.measureText(sTxt) / 2, labelY, if (dn > 0) doneT else missT)
                        labelY -= 11f * density
                    }
                    // Mark only when something was planned
                    planMark(dn, pl, tolPct)?.let { mark(cx, labelY - markH / 2 + 2f * density, it) }
                }
            } else {
                seg(done[i], doneP)
                val missSpan = seg(missed[i], missP)
                val todoSpan = seg(todo[i], planP)
                labelY = y - 3f * density
                if (showNumbers) {
                    fun inside(span: Pair<Float, Float>?, v: Int, p: Paint): Boolean {
                        if (span == null) return false
                        if (span.second - span.first < 12f * density) return false
                        val sTxt = v.toString()
                        c.drawText(sTxt, cx - p.measureText(sTxt) / 2, (span.first + span.second) / 2 + 3.5f * density, p)
                        return true
                    }
                    inside(missSpan, missed[i], missT)
                    val todoIn = inside(todoSpan, todo[i], todoInT)
                    if (done[i] > 0) {
                        val sTxt = done[i].toString()
                        c.drawText(sTxt, cx - doneT.measureText(sTxt) / 2, labelY, doneT)
                    } else if (todo[i] > 0 && !todoIn) {
                        val sTxt = todo[i].toString()
                        c.drawText(sTxt, cx - todoT.measureText(sTxt) / 2, labelY, todoT)
                    }
                }
            }
            if (i % k == 0 || isToday) {
                val d = bucketStarts[i]
                val dl = when {
                    daily && n <= 7 -> d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()).take(3)
                    daily -> d.dayOfMonth.toString()
                    else -> d.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
                }
                label.color = if (isToday) 0xFFF2F4F8.toInt() else LABEL
                label.isFakeBoldText = isToday
                c.drawText(dl, cx - label.measureText(dl) / 2, h - 3f * density, label)
            }
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
            val grid = Paint().apply { this.color = GRID; strokeWidth = 1f }
            for (k in 1..3) {
                val v = f.lo + (f.hi - f.lo) * k / 4
                c.drawLine(r.left, f.y(v), r.right, f.y(v), grid)
                c.drawText(paceText(-v), 0f, f.y(v) + 3.5f * density, text)
            }
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

    // ---------------------------------------------------------------- labelled time charts (app screens)

    /**
     * General time chart with value gridlines + labels, date labels, an optional shaded "normal"
     * band and threshold line. [scatter] draws dots plus a 5-point rolling average (for per-run
     * metrics); otherwise a line. [invert] plots smaller values higher (pace).
     */
    fun timeChart(
        points: List<Pair<LocalDate, Double>>, start: LocalDate, end: LocalDate, lineColor: Int,
        w: Int, h: Int, density: Float, bg: Int,
        band: Pair<Double, Double>? = null, bandColor: Int = lineColor, threshold: Double? = null,
        scatter: Boolean = false, invert: Boolean = false, fmt: (Double) -> String = { it.roundToInt().toString() },
    ): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (points.isEmpty()) return bmp
        val c = Canvas(bmp)
        val text = labelPaint(density).apply { textSize = 10f * density }
        val leftPad = 34f * density
        val axisH = 15f * density
        val r = RectF(leftPad, 4f * density, w - 4f * density, h - axisH)
        val span = java.time.temporal.ChronoUnit.DAYS.between(start, end).toDouble().coerceAtLeast(1.0)
        fun x(d: LocalDate) = (r.left + java.time.temporal.ChronoUnit.DAYS.between(start, d) / span * r.width()).toFloat()
        val sign = if (invert) -1.0 else 1.0
        val vals = points.map { it.second * sign }
        var lo = vals.min(); var hi = vals.max()
        band?.let { lo = min(lo, min(it.first, it.second) * sign); hi = max(hi, max(it.first, it.second) * sign) }
        threshold?.let { lo = min(lo, it * sign); hi = max(hi, it * sign) }
        if (hi - lo < 1e-6) { lo -= 1; hi += 1 }
        val m = (hi - lo) * 0.1; lo -= m; hi += m
        fun y(v: Double) = (r.top + (1 - (v * sign - lo) / (hi - lo)) * r.height()).toFloat()

        // Horizontal gridlines with value labels
        val grid = Paint().apply { this.color = GRID; strokeWidth = 1f }
        for (k in 0..3) {
            val vv = (lo + (hi - lo) * k / 3) * sign
            val yy = y(vv)
            c.drawLine(r.left, yy, r.right, yy, grid)
            c.drawText(fmt(vv), 0f, yy + 3.5f * density, text)
        }
        // Normal band
        band?.let {
            val p = Paint().apply { this.color = withAlpha(bandColor, 0x22) }
            c.drawRect(r.left, min(y(it.first), y(it.second)), r.right, max(y(it.first), y(it.second)), p)
        }
        threshold?.let {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 1.2f * density; this.color = 0x99FFC53D.toInt()
                pathEffect = DashPathEffect(floatArrayOf(4f * density, 3f * density), 0f)
            }
            c.drawLine(r.left, y(it), r.right, y(it), p)
        }
        // Date labels
        val days = span.toInt()
        val stepDays = when { days <= 21 -> 7; days <= 70 -> 14; days <= 200 -> 30; else -> 60 }
        val fmtD = DateTimeFormatter.ofPattern(if (stepDays >= 30) "MMM" else "d MMM", Locale.getDefault())
        var d = end
        var lastLeft = Float.MAX_VALUE
        while (!d.isBefore(start)) {
            val lbl = d.format(fmtD)
            val tw = text.measureText(lbl)
            val xx = x(d)
            if (xx - tw / 2 >= r.left - 2f && xx + tw / 2 + 4f * density < lastLeft) {
                c.drawText(lbl, (xx - tw / 2).coerceAtMost(w - tw), h - 2f * density, text)
                c.drawLine(xx, r.top, xx, r.bottom, grid)
                lastLeft = xx - tw / 2
            }
            d = if (stepDays >= 30) d.minusMonths((stepDays / 30).toLong()) else d.minusDays(stepDays.toLong())
        }

        val sorted = points.sortedBy { it.first }
        if (scatter) {
            val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = withAlpha(lineColor, 0xAA) }
            sorted.forEach { (dd, v) -> c.drawCircle(x(dd), y(v), 2.6f * density, dotP) }
            if (sorted.size >= 3) {
                val path = Path()
                sorted.indices.forEach { i ->
                    val win = sorted.subList(maxOf(0, i - 4), i + 1).map { it.second }
                    val yy = y(win.average())
                    if (i == 0) path.moveTo(x(sorted[i].first), yy) else path.lineTo(x(sorted[i].first), yy)
                }
                c.drawPath(path, stroke(lineColor, density, 2f))
            }
        } else {
            val path = Path()
            sorted.forEachIndexed { i, (dd, v) -> if (i == 0) path.moveTo(x(dd), y(v)) else path.lineTo(x(dd), y(v)) }
            c.drawPath(path, stroke(lineColor, density, 1.8f))
        }
        val last = sorted.last()
        dot(c, x(last.first), y(last.second), lineColor, density, bg)
        return bmp
    }

    /**
     * Race build-up: Fitness (blue; solid = history, dashed = projected) and Fatigue (purple, faint)
     * from ~90 days before the race, the taper target band, checkpoint markers and the peak.
     * Form (zone coloured) runs underneath.
     */
    fun raceBuild(
        days: List<Day>, todayIndex: Int, marks: List<Pair<Int, String>>, peakIndex: Int,
        guardrail: Double?, fatigueTarget: Double?, w: Int, h: Int, density: Float, bg: Int,
        /** Race-morning point (end of the day before the race): marked with dots. */
        eveIndex: Int? = null,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (days.size < 2) return bmp
        val c = Canvas(bmp)
        val text = labelPaint(density).apply { textSize = 10f * density }
        val leftPad = 28f * density
        val axisH = 27f * density   // two rows of checkpoint labels
        val gap = 8f * density
        val chartH = h - axisH
        val topR = RectF(leftPad, 14f * density, w.toFloat() - 6f * density, (chartH - gap) * 0.68f)
        val botR = RectF(leftPad, topR.bottom + gap, w.toFloat() - 6f * density, chartH)
        val ctl = days.map { it.ctl }
        val atl = days.map { it.atl }
        var lo = min(ctl.min(), atl.min()); var hi = max(ctl.max(), atl.max())
        guardrail?.let { lo = min(lo, it) }
        fatigueTarget?.let { lo = min(lo, it); hi = max(hi, it) }
        val f = Frame(topR, days.size, 3f * density, lo, hi)
        val grid = Paint().apply { color = GRID; strokeWidth = 1f }
        for (k in 0..2) {
            val v = f.lo + (f.hi - f.lo) * (k + 0.5) / 3
            c.drawLine(topR.left, f.y(v), topR.right, f.y(v), grid)
            c.drawText(v.roundToInt().toString(), 0f, f.y(v) + 3.5f * density, text)
        }
        // Fitness guardrail (blue) across the chart, Fatigue target (purple) over the last ~3 weeks
        fun dashed(col: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.4f * density; this.color = col
            pathEffect = DashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)
        }
        guardrail?.let { g ->
            c.drawLine(topR.left, f.y(g), topR.right, f.y(g), dashed(withAlpha(FormColors.FITNESS, 0xAA)))
            val lbl = Paint(text).apply { this.color = FormColors.FITNESS }
            c.drawText("fitness floor ${g.roundToInt()}", topR.left + 4f * density, f.y(g) + 12f * density, lbl)
        }
        fatigueTarget?.let { t ->
            val x0 = f.x(maxOf(0, days.size - 22))
            c.drawLine(x0, f.y(t), topR.right, f.y(t), dashed(FormColors.FATIGUE))
            val lbl = Paint(text).apply { this.color = FormColors.FATIGUE }
            val s2 = "fatigue target ${t.roundToInt()}"
            c.drawText(s2, (topR.right - lbl.measureText(s2)), f.y(t) - 4f * density, lbl)
        }
        // Checkpoint markers
        val markP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1f * density; color = 0x669AA3B2
            pathEffect = DashPathEffect(floatArrayOf(3f * density, 3f * density), 0f)
        }
        var lastRight = -1f
        for ((i, label) in marks) {
            val xx = f.x(i)
            val isRace = label.startsWith("race")
            c.drawLine(xx, topR.top - 2f * density, xx, chartH, if (isRace) Paint(markP).apply { color = 0xCCFFC53D.toInt(); pathEffect = null } else markP)
            val p = if (isRace) Paint(text).apply { color = 0xFFFFC53D.toInt(); isFakeBoldText = true } else text
            val tw = p.measureText(label)
            val x0 = (xx - tw / 2).coerceIn(leftPad, w - tw)
            // Second row when it would overlap the previous label
            val row2 = x0 < lastRight + 4f * density
            c.drawText(label, x0, if (row2) h - 2f * density else h - 14f * density, p)
            if (!row2) lastRight = x0 + tw
        }
        // Today marker
        if (todayIndex in days.indices) {
            val xx = f.x(todayIndex)
            val p = Paint().apply { color = 0x55FFFFFF; strokeWidth = 1.5f * density }
            c.drawLine(xx, topR.top - 10f * density, xx, chartH, p)
            val lbl = Paint(text).apply { color = 0xFFF2F4F8.toInt() }
            c.drawText("today", (xx - lbl.measureText("today") / 2).coerceIn(leftPad, w - 30f * density), 10f * density, lbl)
        }
        // Fatigue (faint), Fitness history solid + projection dashed
        c.drawPath(pathOf(f, atl), stroke(withAlpha(FormColors.FATIGUE, 0x88), density, 1.3f))
        val hist = Path(); val proj = Path()
        ctl.forEachIndexed { i, v ->
            if (i <= todayIndex) { if (i == 0) hist.moveTo(f.x(i), f.y(v)) else hist.lineTo(f.x(i), f.y(v)) }
            if (i >= todayIndex) { if (i == todayIndex || (todayIndex < 0 && i == 0)) proj.moveTo(f.x(i), f.y(v)) else proj.lineTo(f.x(i), f.y(v)) }
        }
        c.drawPath(hist, stroke(FormColors.FITNESS, density, 2.2f))
        c.drawPath(proj, stroke(FormColors.FITNESS, density, 2.2f).apply {
            pathEffect = DashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)
        })
        // Peak
        if (peakIndex in days.indices) {
            dot(c, f.x(peakIndex), f.y(ctl[peakIndex]), FormColors.FITNESS, density, bg)
            val lbl = Paint(text).apply { color = FormColors.FITNESS }
            val s = "peak ${ctl[peakIndex].roundToInt()}"
            c.drawText(s, (f.x(peakIndex) - lbl.measureText(s) / 2).coerceIn(leftPad, w - lbl.measureText(s)), f.y(ctl[peakIndex]) - 6f * density, lbl)
        }
        val ei = eveIndex?.takeIf { it in days.indices } ?: days.lastIndex
        dot(c, f.x(ei), f.y(atl[ei]), FormColors.FATIGUE, density, bg)
        dot(c, f.x(ei), f.y(ctl[ei]), FormColors.FITNESS, density, bg)
        // Form panel
        drawForm(c, botR, days.map { it.form }, density, bg)
        c.drawText("Form", 0f, botR.centerY() + 3.5f * density, text)
        return bmp
    }

    /**
     * SubT history (solid) and projection (dashed realistic line inside a fast–slow fan) against the
     * 5K goal paces. Faster plots higher.
     */
    fun subtProjection(
        hist: List<SubtMonth>, real: List<Double>, fast: List<Double>, slow: List<Double>,
        goals: List<Pair<String, Double>>, w: Int, h: Int, density: Float, bg: Int,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (hist.isEmpty() || real.isEmpty()) return bmp
        val c = Canvas(bmp)
        val text = labelPaint(density).apply { textSize = 10f * density }
        val leftPad = 30f * density
        val rightPad = 52f * density
        val axisH = 15f * density
        val r = RectF(leftPad, 6f * density, w - rightPad, h - axisH)
        val n = hist.size + real.size - 1
        fun x(i: Int) = r.left + i * r.width() / max(1, n - 1)
        val all = hist.map { it.pace } + real + fast + slow
        var lo = all.min(); var hi = all.max()   // pace: lo = fastest
        val vis = goals.filter { it.second in (lo - 0.1)..(hi + 0.1) }
        vis.forEach { lo = min(lo, it.second); hi = max(hi, it.second) }
        val m = (hi - lo) * 0.06; lo -= m; hi += m
        fun y(p: Double) = (r.top + (p - lo) / (hi - lo) * r.height()).toFloat()  // faster = higher

        val grid = Paint().apply { color = GRID; strokeWidth = 1f }
        for (k in 0..3) {
            val p = lo + (hi - lo) * k / 3
            c.drawLine(r.left, y(p), r.right, y(p), grid)
            c.drawText(paceText(p), 0f, y(p) + 3.5f * density, text)
        }
        // Goal lines
        val goalP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1f * density; color = 0x88FFC53D.toInt()
            pathEffect = DashPathEffect(floatArrayOf(2f * density, 3f * density), 0f)
        }
        val goalT = Paint(text).apply { color = 0xFFFFC53D.toInt() }
        vis.forEach { (name, pace) ->
            c.drawLine(r.left, y(pace), r.right, y(pace), goalP)
            c.drawText(name.removeSuffix(" 5K"), r.right + 4f * density, y(pace) + 3.5f * density, goalT)
        }
        // Fan between fast and slow
        val off = hist.size - 1
        val fan = Path()
        fast.forEachIndexed { k, p -> if (k == 0) fan.moveTo(x(off + k), y(p)) else fan.lineTo(x(off + k), y(p)) }
        for (k in slow.indices.reversed()) fan.lineTo(x(off + k), y(slow[k]))
        fan.close()
        c.drawPath(fan, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(SUBT, 0x22) })
        // History + projection
        val hp = Path()
        hist.forEachIndexed { k, mo -> if (k == 0) hp.moveTo(x(k), y(mo.pace)) else hp.lineTo(x(k), y(mo.pace)) }
        c.drawPath(hp, stroke(SUBT, density, 2f))
        val pp = Path()
        real.forEachIndexed { k, p -> if (k == 0) pp.moveTo(x(off + k), y(p)) else pp.lineTo(x(off + k), y(p)) }
        c.drawPath(pp, stroke(SUBT, density, 2f).apply { pathEffect = DashPathEffect(floatArrayOf(5f * density, 4f * density), 0f) })
        dot(c, x(off), y(hist.last().pace), SUBT, density, bg)
        // Now marker and month labels
        val nowP = Paint().apply { color = 0x55FFFFFF; strokeWidth = 1.5f * density }
        c.drawLine(x(off), r.top, x(off), r.bottom, nowP)
        val start = runCatching { LocalDate.parse(hist.first().month + "-01") }.getOrNull() ?: return bmp
        val f = DateTimeFormatter.ofPattern("MMM yy", Locale.getDefault())
        var lastRight = -1f
        for (k in 0 until n step 6) {
            val lbl = start.plusMonths(k.toLong()).format(f)
            val tw = text.measureText(lbl)
            val xx = (x(k) - tw / 2).coerceIn(0f, w - tw)
            if (xx > lastRight + 4f * density) { c.drawText(lbl, xx, h - 2f * density, text); lastRight = xx + tw }
        }
        return bmp
    }

    /** Streaks on a time line: green bars for each run (labelled with its length when there's room), red ticks where one broke. */
    fun streakTimeline(
        runs: List<Streaks.Run>, start: LocalDate, end: LocalDate, unit: String, w: Int, h: Int, density: Float,
        /** Every period (start, end, passed?) so failed and not-judged ones are coloured too, not left blank. */
        periods: List<Triple<LocalDate, LocalDate, Boolean?>> = emptyList(),
    ): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val text = labelPaint(density).apply { textSize = 10f * density }
        val span = java.time.temporal.ChronoUnit.DAYS.between(start, end).toFloat().coerceAtLeast(1f)
        fun x(d: LocalDate) = (java.time.temporal.ChronoUnit.DAYS.between(start, d) / span * (w - 4f * density) + 2f * density)
        val laneTop = 16f * density; val laneBottom = h - 18f * density
        c.drawRoundRect(0f, laneTop, w.toFloat(), laneBottom, 4f * density, 4f * density, Paint().apply { color = 0xFF262B36.toInt() })
        val failP = Paint().apply { color = 0x66FF7A7A }
        val skipP = Paint().apply { color = 0x665EC8FF }
        for ((ps, pe, ok) in periods) {
            if (ok == true) continue
            c.drawRect(x(ps), laneTop, maxOf(x(pe.plusDays(1)), x(ps) + 1f), laneBottom, if (ok == null) skipP else failP)
        }
        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC3DD68C.toInt() }
        val live = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3DD68C.toInt() }
        val brk = Paint().apply { color = 0xFFFF7A7A.toInt(); strokeWidth = 2f * density }
        val lbl = labelPaint(density).apply { textSize = 10f * density; color = 0xFFF2F4F8.toInt() }
        for (r in runs) {
            val x0 = x(r.start); val x1 = maxOf(x(r.end.plusDays(1)), x0 + 2f * density)
            c.drawRoundRect(x0, laneTop, x1, laneBottom, 3f * density, 3f * density, if (r.brokenOn == null) live else bar)
            val s = "${r.length}"
            if (x1 - x0 > lbl.measureText(s) + 6f * density) c.drawText(s, (x0 + x1) / 2 - lbl.measureText(s) / 2, (laneTop + laneBottom) / 2 + 3.5f * density, lbl)
            r.brokenOn?.let { b -> val bx = x(b); c.drawLine(bx, laneTop - 4f * density, bx, laneBottom + 4f * density, brk) }
        }
        // Month labels
        var m = start.withDayOfMonth(1).plusMonths(1)
        val f = DateTimeFormatter.ofPattern("MMM", Locale.getDefault())
        var lastRight = -1f
        while (!m.isAfter(end)) {
            val xx = x(m); val l = m.format(f); val tw = text.measureText(l)
            if (xx - tw / 2 > lastRight + 4f * density) { c.drawText(l, (xx - tw / 2).coerceIn(0f, w - tw), h - 3f * density, text); lastRight = xx + tw / 2 }
            m = m.plusMonths(1)
        }
        c.drawText(unit, 0f, 11f * density, text)
        return bmp
    }

    /**
     * GitHub / Hevy-style year grid: one column per week (Mon-first), one row per weekday,
     * each square coloured by [colorOf] for that day. Month labels along the top.
     */
    fun yearGrid(year: Int, colorOf: (LocalDate) -> Int, w: Int, density: Float, labels: Boolean = true): Bitmap {
        val first = LocalDate.of(year, 1, 1)
        val start = first.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val last = LocalDate.of(year, 12, 31)
        val weeks = (java.time.temporal.ChronoUnit.DAYS.between(start, last) / 7 + 1).toInt()
        val gap = maxOf(1f, w / weeks * 0.14f)
        val cell = (w - gap * (weeks - 1)) / weeks
        val top = if (labels) 14f * density else 0f
        val h = (top + 7 * cell + 6 * gap).toInt() + 2
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = cell * 0.18f
        var d = start
        while (!d.isAfter(last)) {
            if (d.year == year) {
                val col = (java.time.temporal.ChronoUnit.DAYS.between(start, d) / 7).toInt()
                val row = d.dayOfWeek.value - 1
                val x = col * (cell + gap); val y = top + row * (cell + gap)
                p.color = colorOf(d)
                c.drawRoundRect(x, y, x + cell, y + cell, r, r, p)
            }
            d = d.plusDays(1)
        }
        if (labels) {
            val text = labelPaint(density).apply { textSize = 10f * density; color = 0xFF9AA3B2.toInt() }
            val f = DateTimeFormatter.ofPattern("MMM", Locale.getDefault())
            for (m in 1..12) {
                val md = LocalDate.of(year, m, 1)
                val col = (java.time.temporal.ChronoUnit.DAYS.between(start, md) / 7).toInt()
                c.drawText(md.format(f), col * (cell + gap), 10f * density, text)
            }
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
