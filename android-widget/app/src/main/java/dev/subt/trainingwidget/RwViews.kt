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

/**
 * The day strip: one weather-coloured cell per hour (night darkened with a moon, hours without data hatched), one icon
 * per hour, a zone band (daylight / dark OK / night), a white box round the window, a "now" line and a 3-hourly axis.
 */
object RwViews {
    const val NOW_BLUE = 0xFF4EA1FF.toInt()
    private const val BASE = 0xFF232834.toInt()
    private const val DIM = 0xFF9AA3B2.toInt()
    private const val DAYLIGHT = 0xFFFFC53D.toInt()
    private const val DARK_OK = 0xFF8FA9D6.toInt()
    private const val RB_BLUE = 0xFF8FD3FF.toInt()

    private const val BAND_DP = 14f

    /** Height of the strip bitmap in dp at scale 1 (widgets scale it down to fit). */
    fun heightDp(mini: Boolean): Float = if (mini) 3f + 14f + 3f else 22f + BAND_DP + 34f + 20f

    private fun darken(argb: Int, k: Float): Int {
        val r = ((argb shr 16 and 0xFF) * k).roundToInt(); val g = ((argb shr 8 and 0xFF) * k).roundToInt(); val b = ((argb and 0xFF) * k).roundToInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** The single most important icon for an hour: danger, rain, UV, heat, cold, cloud. Second = true for heavy rain (drawn bigger). */
    fun primaryIcon(h: Weather.Combined, cls: Weather.HourClass, type: Weather.RunType, s: RwSettings): Pair<String, Boolean>? = when {
        h.frozen || h.icy -> "🧊" to false
        (h.aqi ?: 0.0) > 100.0 -> "😷" to false
        h.rainMm > 0.25 -> "💧" to (h.rainMm > 0.5)
        type == Weather.RunType.TT && (h.gust ?: 0.0) > 30.0 -> "💨" to false
        !Weather.uvOk(h.uv, h.cloud, s) -> "☀" to false
        cls.band == Weather.Band.HOT -> "🔥" to false
        cls.band == Weather.Band.COLD -> (if (cls.eff < 0.0) "❄" else "🧤") to false
        cls.cloudy -> "☁" to false
        else -> null
    }

    /**
     * [mini] = cells and the box only (Week list). [hours] / [win] default to the plan's overall pick; pass the Rainbow
     * hours and window for the Rainbow strip, and [other] to outline a second window dashed. [minCellDp] widens the
     * bitmap beyond [widthPx] so cells stay readable (the caller then scrolls it).
     */
    fun strip(
        ctx: Context, plan: Weather.DayPlan, s: RwSettings, widthPx: Int, mini: Boolean, nowSec: Long?,
        hours: List<Weather.Combined> = plan.hours, win: Weather.Window? = plan.window, other: Weather.Window? = null,
        zone: ZoneId = ZoneId.systemDefault(), dScale: Float = 1f, minCellDp: Float = 0f,
    ): Bitmap {
        val d = ctx.resources.displayMetrics.density * dScale
        val margin = if (mini) 2f * d else 12f * d
        val t0 = plan.lo; val t1 = plan.hi
        val nCells = Math.ceil((t1 - t0) / 3600.0).toInt().coerceAtLeast(1)
        val w = maxOf(widthPx.coerceAtLeast(160), if (minCellDp > 0f) (nCells * minCellDp * d + 2 * margin).roundToInt() else 0)
        val topH = if (mini) 3f * d else 22f * d
        val bandH = if (mini) 0f else BAND_DP * d
        val cellH = (if (mini) 14f else 34f) * d
        val axisH = if (mini) 3f * d else 20f * d
        val h = (topH + bandH + cellH + axisH).roundToInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val span = (t1 - t0).toFloat().coerceAtLeast(1f)
        fun x(t: Long): Float = margin + ((t - t0).toFloat() / span).coerceIn(0f, 1f) * (w - 2 * margin)

        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val cellTop = topH + bandH
        val cellBottom = cellTop + cellH
        // empty track
        p.color = BASE
        c.drawRoundRect(RectF(x(t0), cellTop, x(t1), cellBottom), 3f * d, 3f * d, p)
        // zone band: daylight (solid), dark but OK (dashed), night fallback (dotted)
        if (!mini) drawBand(c, plan, s, zone, { t -> x(t) }, cellTop, d, t0, t1)
        // hour cells
        val month = plan.date.monthValue
        val cellW = (x(t0 + 3600) - x(t0))
        val byT = HashMap<Long, Weather.Combined>()
        for (hr in hours) byT[Weather.hourFloor(hr.t)] = hr
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        val moonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 7f * d; textAlign = Paint.Align.LEFT }
        val hatch = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d; color = 0xFF465065.toInt() }
        val tiny = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 7f * d; textAlign = Paint.Align.CENTER; color = DIM }
        var slot = Weather.hourFloor(t0)
        // contiguous blocks of hours with no data: [start, end) in seconds, labelled once each
        val gaps = ArrayList<LongArray>()
        while (slot < t1) {
            val l = x(slot) + 0.5f; val r = x(slot + 3600) - 0.5f
            val hr = byT[slot]
            if (hr == null) {
                // no data: hatched, never a plain box
                p.color = BASE
                c.drawRect(l, cellTop, r, cellBottom, p)
                c.save(); c.clipRect(l, cellTop, r, cellBottom)
                var k = -cellH
                val step = 5f * d
                while (k < r - l) { c.drawLine(l + k, cellBottom, l + k + cellH, cellTop, hatch); k += step }
                c.restore()
                val last = gaps.lastOrNull()
                if (last != null && last[1] == slot) last[1] = slot + 3600 else gaps.add(longArrayOf(slot, slot + 3600))
            } else {
                val night = Weather.isNight(hr.t, plan.sun, zone)
                val cls = Weather.classify(hr, s, month, false)
                // a night cell too narrow for the moon loses it and is darkened more instead
                val showMoon = !mini && night && cellW >= 14f * d
                p.color = if (night) darken(cls.tone.argb, if (mini || showMoon) 0.55f else 0.4f) else cls.tone.argb
                c.drawRect(l, cellTop, r, cellBottom, p)
                if (!mini) {
                    if (showMoon) c.drawText("🌙", l + 1.5f * d, cellTop + 8f * d, moonPaint)
                    if (cellW >= 12f * d) {
                        val ic = primaryIcon(hr, cls, plan.run.type, s)
                        if (ic != null) {
                            iconPaint.textSize = (if (ic.second) 16f else 12f) * d
                            // with a moon in the corner the icon sits lower so the two never overlap
                            val base = if (showMoon) cellBottom - (if (ic.second) 3f else 5f) * d
                            else cellBottom - (cellH - 12f * d) / 2f - 2f * d + (if (ic.second) 2f * d else 0f)
                            c.drawText(ic.first, (l + r) / 2f, base, iconPaint)
                        }
                    }
                }
            }
            slot += 3600
        }
        // past hours (viewing today) are dimmed, so past and future read at a glance
        if (nowSec != null && nowSec > t0) {
            p.color = 0x59000000
            c.drawRect(x(t0), cellTop, x(minOf(nowSec, t1)), cellBottom, p)
        }
        // one label per no-data block, centred: "past" only when the block is entirely before now
        if (!mini) {
            for (g in gaps) {
                val gl = x(g[0]); val gr = x(g[1])
                val past = nowSec != null && g[1] <= nowSec
                val full = if (past) "past" else if (hours !== plan.hours) "no Rainbow data" else "no data"
                val label = listOf(full, "no data").firstOrNull { tiny.measureText(it) <= gr - gl - 4f * d } ?: continue
                c.drawText(label, (gl + gr) / 2f, cellTop + cellH / 2f + 3f * d, tiny)
            }
        }
        // every window of the day (v3), or the one given (Rainbow strip): tight ones amber
        val boxes: List<Weather.Window> = if (win != null && win === plan.window && plan.windows.isNotEmpty()) plan.windows else listOfNotNull(win)
        // the windows' cells get a subtle white wash so they pop
        for (bw in boxes) {
            p.color = 0x1FFFFFFF
            c.drawRect(x(bw.start), cellTop, x(bw.end), cellBottom, p)
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
        // each window: a box (white, amber when tight) with bold start / end above it
        for (bw in boxes) {
            val boxColor = if (bw.narrow) 0xFFFFC53D.toInt() else 0xFFFFFFFF.toInt()
            val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = (if (mini) 1.6f else 2.4f) * d; color = boxColor }
            c.drawRoundRect(RectF(x(bw.start), cellTop - 1f * d, x(bw.end), cellBottom + 1f * d), 4f * d, 4f * d, box)
            if (!mini) {
                val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * d; typeface = Typeface.DEFAULT_BOLD; color = boxColor }
                val a = Weather.hhmm(bw.start, zone); val b = Weather.hhmm(bw.end, zone)
                val base = topH - 6f * d
                if (boxes.size == 1 && x(bw.end) - x(bw.start) >= 96f * d) {
                    tp.textAlign = Paint.Align.LEFT; c.drawText(a, x(bw.start), base, tp)
                    tp.textAlign = Paint.Align.RIGHT; c.drawText(b, x(bw.end), base, tp)
                } else {
                    tp.textAlign = Paint.Align.CENTER
                    val cx = ((x(bw.start) + x(bw.end)) / 2f).coerceIn(46f * d, w - 46f * d)
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
                if (hr % 3 == 0 && sunX.none { Math.abs(it - x(t)) < 14f * d }) {
                    val label = hr.toString(); val half = ax.measureText(label) / 2f
                    c.drawText(label, x(t).coerceIn(half + 2f * d, w - half - 2f * d), cellBottom + 14f * d, ax)
                }
                t += 3600
            }
            // sunrise / sunset ticks
            val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.5f * d; color = 0xFFFFC53D.toInt() }
            val em = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * d; textAlign = Paint.Align.CENTER }
            plan.sun?.let { sun ->
                if (sun.rise in t0..t1) { c.drawLine(x(sun.rise), cellTop, x(sun.rise), cellBottom + 3f * d, tick); c.drawText("🌅", x(sun.rise).coerceIn(8f * d, w - 8f * d), cellBottom + 16f * d, em) }
                if (sun.set in t0..t1) { c.drawLine(x(sun.set), cellTop, x(sun.set), cellBottom + 3f * d, tick); c.drawText("🌇", x(sun.set).coerceIn(8f * d, w - 8f * d), cellBottom + 16f * d, em) }
            }
        }
        return bmp
    }

    /** Zones just above the cells: daylight ±30 min solid, "dark OK" dashed, the night fallback dotted. */
    private fun drawBand(
        c: Canvas, plan: Weather.DayPlan, s: RwSettings, zone: ZoneId, x: (Long) -> Float, cellTop: Float, d: Float, t0: Long, t1: Long,
    ) {
        val sun = plan.sun ?: return
        val lenSec = (plan.run.minutes + s.bufferMin) * 60L
        val dayLo = sun.rise - 1800; val dayHi = sun.set + 1800
        val (aLo, aHi) = Weather.allowedRange(plan.date, sun, s, zone, lenSec)
        val okLo = minOf(aLo, dayLo); val okHi = maxOf(aHi, dayHi)
        val nfLo = Weather.atMinutes(plan.date, 5 * 60, zone); val nfHi = Weather.nightEnd(plan.date, zone)
        val y = cellTop - 3f * d
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d; strokeCap = Paint.Cap.ROUND }
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f * d; textAlign = Paint.Align.CENTER }
        fun seg(a: Long, b: Long, color: Int, dash: FloatArray?, label: String?) {
            val lo = maxOf(a, t0); val hi = minOf(b, t1)
            if (hi <= lo) return
            line.color = color; line.pathEffect = dash?.let { DashPathEffect(it, 0f) }
            c.drawLine(x(lo), y, x(hi), y, line)
            if (label != null && x(hi) - x(lo) >= 44f * d) { txt.color = color; c.drawText(label, (x(lo) + x(hi)) / 2f, y - 3f * d, txt) }
        }
        seg(nfLo, okLo, DIM, floatArrayOf(1f * d, 4f * d), null)
        seg(okHi, nfHi, DIM, floatArrayOf(1f * d, 4f * d), null)
        seg(okLo, dayLo, DARK_OK, floatArrayOf(6f * d, 3f * d), "dark OK")
        seg(dayHi, okHi, DARK_OK, floatArrayOf(6f * d, 3f * d), "dark OK")
        seg(dayLo, dayHi, DAYLIGHT, null, "daylight")
    }
}
