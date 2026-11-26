package dev.subt.trainingwidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/** The day strip: one coloured cell per hour, a white box round the window, a "now" line, sunrise / sunset and a 3-hourly axis. */
object RwViews {
    const val NOW_BLUE = 0xFF4EA1FF.toInt()
    private const val BASE = 0xFF232834.toInt()
    private const val DIM = 0xFF9AA3B2.toInt()
    private const val RB_BLUE = 0xFF8FD3FF.toInt()

    /**
     * [mini] = cells and the box only (Week list). [hours] / [win] default to the plan's overall pick; pass the Rainbow
     * hours and window for the Rainbow strip, and [other] to outline a second window dashed.
     */
    fun strip(
        ctx: Context, plan: Weather.DayPlan, s: RwSettings, widthPx: Int, mini: Boolean, nowSec: Long?,
        hours: List<Weather.Combined> = plan.hours, win: Weather.Window? = plan.window, other: Weather.Window? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val w = widthPx.coerceAtLeast(160)
        val margin = if (mini) 2f * d else 8f * d
        val topH = if (mini) 3f * d else 22f * d
        val cellH = (if (mini) 14f else 34f) * d
        val axisH = if (mini) 3f * d else 20f * d
        val h = (topH + cellH + axisH).roundToInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val t0 = plan.lo; val t1 = plan.hi
        val span = (t1 - t0).toFloat().coerceAtLeast(1f)
        fun x(t: Long): Float = margin + ((t - t0).toFloat() / span).coerceIn(0f, 1f) * (w - 2 * margin)

        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val cellTop = topH
        val cellBottom = topH + cellH
        // empty track
        p.color = BASE
        c.drawRoundRect(RectF(x(t0), cellTop, x(t1), cellBottom), 3f * d, 3f * d, p)
        // hour cells
        val month = plan.date.monthValue
        val cellW = (x(t0 + 3600) - x(t0))
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * d; textAlign = Paint.Align.CENTER; color = 0xFF1B1F27.toInt() }
        for (hr in hours) {
            if (hr.t + 3600 <= t0 || hr.t >= t1) continue
            val night = Weather.isNight(hr.t, plan.sun, zone)
            val cls = Weather.classify(hr, s, month, night)
            p.color = cls.tone.argb
            c.drawRect(x(hr.t) + 0.5f, cellTop, x(hr.t + 3600) - 0.5f, cellBottom, p)
            if (!mini && !night && cellW >= 14f * d) {
                val icon = Weather.icons(hr, cls, plan.run.type, s).firstOrNull()
                if (icon != null) c.drawText(icon, (x(hr.t) + x(hr.t + 3600)) / 2f, cellBottom - (cellH - 12f * d) / 2f - 2f * d, iconPaint)
            }
        }
        // the other (Rainbow) window, dashed
        if (other != null) {
            val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = (if (mini) 1.2f else 2f) * d; color = RB_BLUE
                pathEffect = DashPathEffect(floatArrayOf(5f * d, 3f * d), 0f)
            }
            val inset = if (mini) 0f else 3f * d
            c.drawRoundRect(RectF(x(other.start), cellTop - inset, x(other.end), cellBottom + inset), 4f * d, 4f * d, dash)
        }
        // the window: white box with bold start / end above it
        if (win != null) {
            val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = (if (mini) 1.6f else 2.4f) * d; color = 0xFFFFFFFF.toInt() }
            c.drawRoundRect(RectF(x(win.start), cellTop - 1f * d, x(win.end), cellBottom + 1f * d), 4f * d, 4f * d, box)
            if (!mini) {
                val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * d; typeface = Typeface.DEFAULT_BOLD; color = 0xFFFFFFFF.toInt() }
                val a = Weather.hhmm(win.start, zone); val b = Weather.hhmm(win.end, zone)
                val base = topH - 6f * d
                if (x(win.end) - x(win.start) >= 96f * d) {
                    tp.textAlign = Paint.Align.LEFT; c.drawText(a, x(win.start), base, tp)
                    tp.textAlign = Paint.Align.RIGHT; c.drawText(b, x(win.end), base, tp)
                } else {
                    tp.textAlign = Paint.Align.CENTER
                    val cx = ((x(win.start) + x(win.end)) / 2f).coerceIn(46f * d, w - 46f * d)
                    c.drawText("$a–$b", cx, base, tp)
                }
            }
        }
        // now line (today only)
        if (nowSec != null && nowSec in t0..t1) {
            val nl = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = (if (mini) 1.5f else 2.2f) * d; color = NOW_BLUE }
            c.drawLine(x(nowSec), cellTop - 2f * d, x(nowSec), cellBottom + 2f * d, nl)
            if (!mini) c.drawCircle(x(nowSec), cellTop - 3f * d, 3f * d, nl)
        }
        if (!mini) {
            // 3-hourly axis
            val ax = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.5f * d; color = DIM; textAlign = Paint.Align.CENTER }
            val sunX = listOfNotNull(plan.sun?.rise, plan.sun?.set).filter { it in t0..t1 }.map { x(it) }
            var t = Weather.hourFloor(t0)
            if (t < t0) t += 3600
            while (t <= t1) {
                val hr = Instant.ofEpochSecond(t).atZone(zone).hour
                if (hr % 3 == 0 && sunX.none { Math.abs(it - x(t)) < 14f * d }) c.drawText(hr.toString(), x(t), cellBottom + 14f * d, ax)
                t += 3600
            }
            // sunrise / sunset ticks
            val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.5f * d; color = 0xFFFFC53D.toInt() }
            val em = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * d; textAlign = Paint.Align.CENTER }
            plan.sun?.let { sun ->
                if (sun.rise in t0..t1) { c.drawLine(x(sun.rise), cellTop, x(sun.rise), cellBottom + 3f * d, tick); c.drawText("🌅", x(sun.rise), cellBottom + 16f * d, em) }
                if (sun.set in t0..t1) { c.drawLine(x(sun.set), cellTop, x(sun.set), cellBottom + 3f * d, tick); c.drawText("🌇", x(sun.set), cellBottom + 16f * d, em) }
            }
        }
        return bmp
    }
}
