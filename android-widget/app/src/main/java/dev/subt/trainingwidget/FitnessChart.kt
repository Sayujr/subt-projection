package dev.subt.trainingwidget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The long-term Fitness chart: the last 12 weeks (solid), the plan (dashed) and the green target band, out to the race
 * (or the end of your plan, up to 16 weeks), with a tick where the plan ends. Registers a point per day with [ChartHits] so [Scrub] can read values off it.
 */
object FitnessChart {
    private const val GRID = 0x339AA3B2
    private const val LABEL = 0xFF6B7386.toInt()
    private const val BAND = 0xFF3DD68C.toInt()
    private const val AMBER = 0xFFFFC53D.toInt()

    private fun ld(s: String): LocalDate? = runCatching { LocalDate.parse(s) }.getOrNull()

    fun render(s: Snapshot, band: FitnessPlan.Band, w: Int, h: Int, density: Float): Bitmap {
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(40), h.coerceAtLeast(40), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val d = density
        val today = FitnessPlan.todayOf(s)
        val left = 28f * d; val right = bmp.width - 8f * d; val top = 10f * d; val bottom = bmp.height - 18f * d
        val span = ChronoUnit.DAYS.between(band.start, band.end).coerceAtLeast(1).toFloat()
        fun x(date: LocalDate): Float = left + ChronoUnit.DAYS.between(band.start, date) / span * (right - left)

        val hist = s.days.mapNotNull { day -> ld(day.date)?.takeIf { it >= band.start && it <= today }?.let { it to day.ctl } }
        val proj = s.proj.mapNotNull { day -> ld(day.date)?.takeIf { it > today && it <= band.end }?.let { it to day.ctl } }
        val all = hist.map { it.second } + proj.map { it.second } + band.lo + band.hi
        var lo = all.min(); var hi = all.max()
        if (hi - lo < 6) { val m = (hi + lo) / 2; lo = m - 3; hi = m + 3 }
        val pad = (hi - lo) * 0.08; lo -= pad; hi += pad
        fun y(v: Double): Float = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LABEL; textSize = 10f * d }
        val grid = Paint().apply { color = GRID; strokeWidth = 1f }
        for (k in 0..3) {
            val v = lo + (hi - lo) * (k + 0.5) / 4
            c.drawLine(left, y(v), right, y(v), grid)
            c.drawText(Math.round(v).toString(), 0f, y(v) + 3.5f * d, text)
        }
        // target band
        val poly = Path()
        for (i in band.hi.indices) { val xx = x(band.start.plusDays(i.toLong())); if (i == 0) poly.moveTo(xx, y(band.hi[i])) else poly.lineTo(xx, y(band.hi[i])) }
        for (i in band.lo.indices.reversed()) poly.lineTo(x(band.start.plusDays(i.toLong())), y(band.lo[i]))
        poly.close()
        c.drawPath(poly, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (BAND and 0x00FFFFFF) or 0x30000000 })
        // taper start and race day
        band.taperStart?.let { t ->
            if (!t.isBefore(band.start) && !t.isAfter(band.end))
                c.drawLine(x(t), top, x(t), bottom, Paint().apply { color = 0x669AA3B2; strokeWidth = 1f * d; pathEffect = DashPathEffect(floatArrayOf(3f * d, 3f * d), 0f) })
        }
        if (band.race != null) {
            c.drawLine(x(band.end), top, x(band.end), bottom, Paint().apply { color = 0xCCFFC53D.toInt(); strokeWidth = 1.2f * d })
            val lbl = Paint(text).apply { color = AMBER; isFakeBoldText = true }
            val name = band.race.name.take(14)
            c.drawText(name, (right - lbl.measureText(name)).coerceAtLeast(left), top + 9f * d, lbl)
        }
        // where the plan stops: the dashed line ends here
        val planEnd = s.planEnd?.let { ld(it) }?.takeIf { it > today && it <= band.end }
        if (planEnd != null) {
            c.drawLine(x(planEnd), top, x(planEnd), bottom, Paint().apply { color = 0x889AA3B2.toInt(); strokeWidth = 1.2f * d; pathEffect = DashPathEffect(floatArrayOf(2f * d, 3f * d), 0f) })
            val pl = "plan ends ${planEnd.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))}"
            val pw = text.measureText(pl)
            val px = if (x(planEnd) + 3f * d + pw <= right) x(planEnd) + 3f * d else (x(planEnd) - 3f * d - pw).coerceAtLeast(left)
            c.drawText(pl, px, bottom - 4f * d, Paint(text).apply { isFakeBoldText = true })
        }
        // today
        if (today >= band.start && today <= band.end)
            c.drawLine(x(today), top, x(today), bottom, Paint().apply { color = 0x55FFFFFF; strokeWidth = 1.4f * d })
        // Fitness: history solid, plan dashed
        fun stroke(col: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.2f * d; color = col; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
        val hp = Path()
        hist.forEachIndexed { i, (date, v) -> if (i == 0) hp.moveTo(x(date), y(v)) else hp.lineTo(x(date), y(v)) }
        c.drawPath(hp, stroke(FormColors.FITNESS))
        val pp = Path()
        hist.lastOrNull()?.let { (date, v) -> pp.moveTo(x(date), y(v)) }
        proj.forEachIndexed { i, (date, v) -> if (i == 0 && hist.isEmpty()) pp.moveTo(x(date), y(v)) else pp.lineTo(x(date), y(v)) }
        c.drawPath(pp, stroke(FormColors.FITNESS).apply { pathEffect = DashPathEffect(floatArrayOf(5f * d, 4f * d), 0f) })
        hist.lastOrNull()?.let { (date, v) ->
            c.drawCircle(x(date), y(v), 3.6f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF222733.toInt() })
            c.drawCircle(x(date), y(v), 2.5f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FormColors.FITNESS })
        }
        // axis labels
        val df = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
        c.drawText("${FitnessPlan.HISTORY_WEEKS} wk ago", left, bmp.height - 3f * d, text)
        val endTxt = band.end.format(df)
        c.drawText(endTxt, right - text.measureText(endTxt), bmp.height - 3f * d, text)
        if (today > band.start && today < band.end) {
            val tx = x(today) - text.measureText("today") / 2
            if (tx > left + 50f * d && tx + text.measureText("today") < right - 50f * d) c.drawText("today", tx, bmp.height - 3f * d, text)
        }
        ChartHits.put(bmp,
            hist.map { (date, v) -> ChartHits.Hit(x(date), y(v), "${date.format(df)} · Fitness ${Math.round(v)}", FormColors.FITNESS) } +
                proj.map { (date, v) -> ChartHits.Hit(x(date), y(v), "${date.format(df)} · plan ${Math.round(v)}", FormColors.FITNESS) })
        return bmp
    }
}
