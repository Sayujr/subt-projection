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

    /** Which day a touch at [x] (in bitmap pixels) falls on, matching [combined]'s layout. */
    fun combinedIndexAt(x: Float, n: Int, w: Int, density: Float, labels: Boolean): Int {
        val left = if (labels) LEFT_LABELS * density else 0f
        val pad = 4f * density
        val span = (w - left - 2 * pad) / max(1, n - 1)
        return ((x - left - pad) / span).roundToInt().coerceIn(0, n - 1)
    }

    // ---------------------------------------------------------------- week bars

    /** Mon–Sun columns for the week widget. */
    fun weekBars(week: List<Workout>, monday: LocalDate, today: LocalDate, w: Int, h: Int, density: Float): Bitmap =
        rangeBars(week, monday, monday.plusDays(6), today, w, h, density)

    /**
     * Load bars over [start]..[end]: one per day up to ~5 weeks, otherwise one per week (Mon–Sun).
     * Completed load is green, missed planned load (before today) a dashed outline, and still-to-do
     * planned load amber. The bar containing today is highlighted.
     */
    fun rangeBars(items: List<Workout>, start: LocalDate, end: LocalDate, today: LocalDate, w: Int, h: Int, density: Float): Bitmap {
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
        val max = (0 until n).maxOfOrNull { done[it] + missed[it] + todo[it] }?.coerceAtLeast(50) ?: 50

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

        /** ✓ = on plan (±10 %), chevron up = more than planned, chevron down = less. No arrow stems. */
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
            fun seg(v: Int, p: Paint): Pair<Float, Float>? {
                if (v <= 0) return null
                val bh = chartH * v / max
                c.drawRoundRect(left, y - bh, right, y, r, r, p)
                val span = y - bh to y
                y -= bh
                return span
            }
            seg(done[i], doneP)
            val missSpan = seg(missed[i], missP)
            val todoSpan = seg(todo[i], planP)
            if (showNumbers) {
                // Numbers inside the dashed (missed) and amber (to do) boxes when they fit
                fun inside(span: Pair<Float, Float>?, v: Int, p: Paint): Boolean {
                    if (span == null) return false
                    if (span.second - span.first < 12f * density) return false
                    val s = v.toString()
                    c.drawText(s, cx - p.measureText(s) / 2, (span.first + span.second) / 2 + 3.5f * density, p)
                    return true
                }
                val missIn = inside(missSpan, missed[i], missT)
                val todoIn = inside(todoSpan, todo[i], todoInT)
                // Above the bar: done in green; if nothing done yet, what's still planned in amber
                var labelY = y - 3f * density
                if (done[i] > 0) {
                    val s = done[i].toString()
                    c.drawText(s, cx - doneT.measureText(s) / 2, labelY, doneT)
                    labelY -= 11f * density
                } else if (todo[i] > 0 && !todoIn) {
                    val s = todo[i].toString()
                    c.drawText(s, cx - todoT.measureText(s) / 2, labelY, todoT)
                    labelY -= 11f * density
                }
                if (!missIn && missed[i] > 0 && done[i] == 0) {
                    val s = missed[i].toString()
                    c.drawText(s, cx - missT.measureText(s) / 2, labelY, missT)
                    labelY -= 11f * density
                }
                // Plan comparison mark
                if (finished && (planned[i] > 0 || done[i] > 0)) {
                    val kind = when {
                        planned[i] == 0 -> 1
                        done[i] > planned[i] * 1.1 -> 1
                        done[i] < planned[i] * 0.9 -> 2
                        else -> 0
                    }
                    mark(cx, labelY - markH / 2 + 2f * density, kind)
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
