package dev.subt.trainingwidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The graphic day chart (Today card and day plan), Rainbow-style: a sky that goes from deep-navy night through an orange dawn and
 * dusk glow to day blue, soft puffy clouds (more and thicker with cloud cover, greyer at night), stars on clear nights, rain
 * streaks, a white temperature line, the window band(s) with a pill row under it, UV dots, and (SubT / TT days) dew point and
 * wind rows. Drawn in dp units on a scaled canvas into a cached bitmap; [RwChartView] adds touch-and-drag scrubbing on top
 * without redrawing the sky.
 */
object RwChart {
    private const val GOOD = 0xFF3DD68C.toInt()
    private const val AMBER = 0xFFFFC53D.toInt()
    private const val ORANGE = 0xFFF7933F.toInt()
    private const val RED = 0xFFFF6B6B.toInt()
    private const val BLUE = 0xFF8FD3FF.toInt()
    private const val DIM = 0xFF9AA3B2.toInt()
    private const val FAINT = 0xFF6B7386.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    class Seg(val a: Float, val b: Float, val tight: Boolean)
    class Run(val a: Float, val b: Float, val label: String, val color: Int)

    /** Everything the chart draws, in hours after local midnight. [hours] is one entry per hour from [start] (null = no data). */
    class Spec(
        val plan: Weather.DayPlan,
        val zone: ZoneId,
        val day0: Long,
        val start: Int,
        val end: Int,
        val hours: List<Weather.Combined?>,
        val rise: Float,
        val set: Float,
        val wins: List<Seg>,
        val best: Seg?,
        val runs: List<Run>,
        val rainbow: Seg?,
        val extra: Boolean,
        val uvLim: Double,
        val nowSec: Long?,
        val steps: List<Weather.NowStep>,
        val typeLabel: String,
        /** 15-minute slots by slot start (today and tomorrow, when that forecast is there). */
        val fine: Map<Long, Weather.Combined> = emptyMap(),
    ) {
        fun fh(t: Long): Float = (t - day0) / 3600f
    }

    fun spec(p: Weather.DayPlan, s: RwSettings, zone: ZoneId, nowSec: Long? = null, steps: List<Weather.NowStep> = emptyList()): Spec {
        val day0 = Weather.atMinutes(p.date, 0, zone)
        val start = floor((p.lo - day0) / 3600.0).toInt().coerceIn(0, 18)
        val end = ceil((p.hi - day0) / 3600.0).toInt().coerceIn(start + 6, 24)
        val byT = p.hours.associateBy { Weather.hourFloor(it.t) }
        val hours = (start until end).map { byT[day0 + it * 3600L] }
        fun fh(t: Long) = (t - day0) / 3600f
        val rise = p.sun?.let { fh(it.rise) } ?: 6f
        val set = p.sun?.let { fh(it.set) } ?: 20f
        val wins = p.windows.map { Seg(fh(it.start), fh(it.end), it.narrow) }
        val best = p.best?.let { Seg(fh(it.start), fh(it.end), false) }
        val runs = ArrayList<Run>()
        val main = p.window
        if (p.second != null && main != null && p.windows.isNotEmpty()) {
            val a = main.start + 1800
            val b = min(main.end, a + p.run.minutes * 60L)
            runs += Run(fh(a), fh(b), "①", WHITE)
            p.secondWindow?.let { sw -> runs += Run(fh(sw.start), fh(min(sw.end, sw.start + p.second.minutes * 60L)), "②", BLUE) }
        }
        val rb = if (!p.same) p.rainbow?.let { Seg(fh(it.start), fh(it.end), false) } else null
        val lim = if (p.run.type == Weather.RunType.LONG) s.uvLimit - 1.0 else s.uvLimit
        return Spec(
            p, zone, day0, start, end, hours, rise, set, wins, best, runs, rb,
            extra = p.run.type == Weather.RunType.SUBT || p.run.type == Weather.RunType.TT, uvLim = lim, nowSec = nowSec, steps = steps,
            typeLabel = p.run.type.label, fine = p.fine.associateBy { it.t },
        )
    }

    private const val X0 = 8f
    private const val ICONS_Y = 18f
    private const val TOP = 34f
    private const val BASE = 118f

    /** Where things sit, in dp (shared by drawing and scrubbing). */
    class Geo(val w: Float, val h: Float, val start: Float, val end: Float, val pillY: Float, val lastY: Float) {
        val x0 = X0
        val x1 = w - 8f
        fun x(hr: Float): Float = x0 + (hr - start) / (end - start) * (x1 - x0)
        fun hourAt(x: Float): Float = (start + (x - x0) / (x1 - x0) * (end - start)).coerceIn(start, end - 0.001f)
        fun ty(t: Double, tmin: Double, tmax: Double): Float = (BASE - 8f - (t - tmin) / (tmax - tmin) * (BASE - TOP - 22f)).toFloat()
    }

    private fun tempRange(spec: Spec): Pair<Double, Double>? {
        val t = spec.hours.mapNotNull { it?.temp }
        if (t.isEmpty()) return null
        return (t.min() - 2) to (t.max() + 2)
    }

    fun geo(spec: Spec, widthPx: Int, d: Float): Geo {
        val w = widthPx / d
        val start = spec.start.toFloat(); val end = spec.end.toFloat()
        fun x(hr: Float) = X0 + (hr - start) / (end - start) * (w - 8f - X0)
        val anyNarrowLabel = spec.wins.any { x(it.b) - x(it.a) <= 80f }
        val pillY = BASE + 50f + if (anyNarrowLabel) 8f else 0f
        var y = pillY + if (spec.best != null) 32f else 24f
        if (spec.extra) y += 24f + 22f
        return Geo(w, y + 16f, start, end, pillY, y)
    }

    class Rendered(val bmp: Bitmap, val geo: Geo)

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private fun stroke(color: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = w }
    private fun textPaint(color: Int, size: Float, bold: Boolean = false, align: Paint.Align = Paint.Align.CENTER) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = size; textAlign = align; if (bold) typeface = Typeface.DEFAULT_BOLD }

    fun uvColor(u: Double, lim: Double): Int = when {
        u <= lim -> GOOD
        u <= lim + 1 -> AMBER
        u <= 6 -> ORANGE
        else -> RED
    }

    private fun uvText(u: Double): String = if (u < 1) String.format(java.util.Locale.US, "%.1f", u).removePrefix("0") else u.roundToInt().toString()

    private fun hhmm(h: Float): String {
        val m = (h * 60).roundToInt()
        return "%02d:%02d".format((m / 60) % 24, m % 60)
    }

    /** Draws a label centred on [cx], kept inside [2, w-2]. */
    private fun Canvas.label(t: String, cx: Float, y: Float, p: Paint, w: Float) {
        val half = p.measureText(t) / 2f
        drawText(t, cx.coerceIn(half + 2f, max(half + 2f, w - half - 2f)), y, p)
    }

    fun render(spec: Spec, widthPx: Int, d: Float): Rendered {
        val g = geo(spec, widthPx, d)
        val bmp = Bitmap.createBitmap(widthPx.coerceAtLeast(1), ceil(g.h * d).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(d, d)
        draw(c, spec, g)
        return Rendered(bmp, g)
    }

    // ================================================================ the sky

    /** The mockup's little generator (the same numbers every time for a seed). */
    class Rnd(private var s: Long) {
        fun next(): Double { s = (s * 9301L + 49297L) % 233280L; return s / 233280.0 }
    }

    /** A generator for one hour of the day and one purpose, so an hour always looks the same however the chart's range moves. */
    fun rndFor(kind: Int, hour: Int): Rnd {
        val r = Rnd(((hour + 1L) * 7919L + kind * 104729L) % 233280L)
        repeat(3) { r.next() }
        return r
    }

    /** One soft cloud puff inside an hour: position across the hour, height and size, each 0..1. */
    class Puff(val fx: Float, val fy: Float, val fr: Float)

    const val MAX_PUFFS = 9

    /** The [MAX_PUFFS] puffs of an hour; the first [puffCount] of them are drawn, so more cloud only adds puffs. */
    fun puffs(hour: Int): List<Puff> {
        val r = rndFor(1, hour)
        return List(MAX_PUFFS) { Puff(r.next().toFloat(), r.next().toFloat(), r.next().toFloat()) }
    }

    fun puffCount(cloud: Double): Int = (cloud.coerceIn(0.0, 100.0) / 100.0 * MAX_PUFFS).roundToInt()

    /** A star: position across the hour and down the upper sky, and its brightness. */
    class Star(val fx: Float, val fy: Float, val alpha: Float)

    fun stars(hour: Int): List<Star> {
        val r = rndFor(2, hour)
        return List(6) { Star(r.next().toFloat(), (r.next() * 0.7).toFloat(), (0.4 + r.next() * 0.5).toFloat()) }
    }

    /** Sky colour [r, g, b] at hour [h]: deep navy night, an orange glow within 45 minutes of sunrise and sunset, day blue. */
    fun skyColor(h: Float, rise: Float, set: Float): IntArray {
        val d = min(h - rise, set - h)
        if (d < -0.75f) return intArrayOf(14, 20, 44)
        if (d >= 0.75f) return intArrayOf(70, 110, 170)
        val t = (d + 0.75f) / 1.5f
        val glow = 1f - abs(t - 0.5f) * 2f
        return intArrayOf(
            (14 + t * (70 - 14) + glow * 120).roundToInt().coerceIn(0, 255),
            (20 + t * (110 - 20) + glow * 40).roundToInt().coerceIn(0, 255),
            (44 + t * (170 - 44)).roundToInt().coerceIn(0, 255),
        )
    }

    private fun argb(a: Float, r: Int, g: Int, b: Int) = ((a.coerceIn(0f, 1f) * 255).roundToInt() shl 24) or (r shl 16) or (g shl 8) or b

    /** Sky, stars, soft clouds and rain streaks inside the chart box (the caller has clipped to it). */
    private fun drawSky(c: Canvas, spec: Spec, g: Geo) {
        val x0 = g.x0; val x1 = g.x1; val top = TOP; val base = BASE
        val start = g.start.toInt()
        val k = (base - top) / 106f              // the mockup's chart was 106 high
        val span = g.end - g.start
        fun X(h: Float) = g.x(h)
        val hourW = X(1f) - X(0f)
        // colour per x: top lighter, bottom darker
        val col = Paint()
        var x = x0
        while (x < x1) {
            val h = g.start + (x - x0) / (x1 - x0) * span
            val cl = skyColor(h, spec.rise, spec.set)
            col.shader = LinearGradient(0f, top, 0f, base, argb(1f, cl[0], cl[1], cl[2]),
                argb(1f, (cl[0] * 0.55f).roundToInt(), (cl[1] * 0.55f).roundToInt(), (cl[2] * 0.55f).roundToInt()), Shader.TileMode.CLAMP)
            c.drawRect(x, top, min(x + 1.3f, x1), base, col)
            x += 1f
        }
        // stars on clear night hours
        val starP = Paint(Paint.ANTI_ALIAS_FLAG)
        spec.hours.forEachIndexed { i, hr ->
            val hh = start + i
            val cloud = hr?.cloud ?: 0.0
            if (cloud >= 40.0) return@forEachIndexed
            for (st in stars(hh)) {
                val h = hh + st.fx
                if (h < spec.rise - 0.5f || h > spec.set + 0.5f) {
                    starP.color = argb(st.alpha, 255, 255, 255)
                    c.drawRect(X(h), top + st.fy * (base - top), X(h) + 1f, top + st.fy * (base - top) + 1f, starP)
                }
            }
        }
        // clouds: soft puffs, more and thicker with cloud cover, greyer at night
        spec.hours.forEachIndexed { i, hr ->
            val cl = hr?.cloud ?: return@forEachIndexed
            val hh = start + i
            val night = hh < spec.rise - 0.3f || hh > spec.set + 0.3f
            val a = ((0.25 + cl / 100.0 * 0.45) * (if (night) 0.55 else 1.0)).toFloat()
            val (r, gg, b) = if (night) Triple(170, 178, 200) else Triple(245, 247, 252)
            val pf = Paint(Paint.ANTI_ALIAS_FLAG)
            for (p in puffs(hh).take(puffCount(cl))) {
                val cx = X(hh.toFloat()) + p.fx * hourW
                val cy = top + (8f + p.fy * 38f * (0.4f + cl.toFloat() / 100f)) * k
                val rad = (10f + p.fr * 16f * (0.5f + cl.toFloat() / 100f)) * k
                pf.shader = RadialGradient(cx, cy, rad, argb(a, r, gg, b), argb(0f, r, gg, b), Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, rad, pf)
            }
        }
        // rain streaks: denser with mm/h
        val rainP = stroke(0x999AC3FF.toInt(), 1.1f)
        spec.hours.forEachIndexed { i, hr ->
            val m = hr?.rainMm ?: 0.0
            if (m <= 0.25) return@forEachIndexed
            val hh = start + i
            val r = rndFor(3, hh)
            repeat((m * 5).roundToInt().coerceIn(1, 18)) {
                val sx = X(hh.toFloat()) + r.next().toFloat() * hourW
                val sy = top + (40f + r.next().toFloat() * 40f) * k
                c.drawLine(sx, sy, sx - 3f, sy + 12f * k, rainP)
            }
        }
    }

    private fun draw(c: Canvas, spec: Spec, g: Geo) {
        val start = g.start.toInt()
        val x0 = g.x0; val x1 = g.x1; val w = g.w
        val top = TOP; val base = BASE; val ch = base - top
        val lim = spec.uvLim
        fun X(h: Float) = g.x(h)
        val tr = tempRange(spec)

        // ---- sky, clouds, rain, windows, temperature (clipped to the rounded chart box)
        c.save()
        val clip = Path().apply { addRoundRect(RectF(x0, top, x1, base), 10f, 10f, Path.Direction.CW) }
        c.clipPath(clip)
        drawSky(c, spec, g)

        // window band(s): green, amber when tight; edge lines
        for (s in spec.wins) {
            val col = if (s.tight) AMBER else GOOD
            c.drawRect(X(s.a), top, X(s.b), base, fill(col).apply { alpha = (255 * 0.16f).toInt() })
            val edge = stroke(col, 2f)
            c.drawLine(X(s.a), top, X(s.a), base, edge)
            c.drawLine(X(s.b), top, X(s.b), base, edge)
        }
        spec.best?.let { b -> c.drawRect(X(b.a), top, X(b.b), base, fill(GOOD).apply { alpha = (255 * 0.22f).toInt() }) }

        // Rainbow's different pick: a small dashed outline
        spec.rainbow?.let { r ->
            val dash = stroke(BLUE, 1.5f).apply { pathEffect = DashPathEffect(floatArrayOf(5f, 3f), 0f) }
            c.drawRoundRect(RectF(X(r.a), top + 2f, X(r.b), base - 2f), 4f, 4f, dash)
        }

        // the two runs
        for (r in spec.runs) {
            c.drawRoundRect(RectF(X(r.a), top, X(r.b), base), 4f, 4f, stroke(r.color, 2f))
            val lp = textPaint(r.color, 12f, bold = true)
            c.drawText(r.label, (X(r.a) + X(r.b)) / 2f, top + 14f, lp)
        }

        // temperature line
        if (tr != null) {
            val path = Path()
            var first = true
            spec.hours.forEachIndexed { i, h ->
                val t = h?.temp ?: return@forEachIndexed
                val px = X(start + i + 0.5f); val py = g.ty(t, tr.first, tr.second)
                if (first) { path.moveTo(px, py); first = false } else path.lineTo(px, py)
            }
            c.drawPath(path, stroke(0xE6FFFFFF.toInt(), 2f).apply { strokeJoin = Paint.Join.ROUND; setShadowLayer(3f, 0f, 1f, 0x99000000.toInt()) })
            val dot = fill(WHITE)
            val tp = textPaint(WHITE, 10.5f, bold = true).apply { setShadowLayer(3f, 0f, 0.5f, 0xCC000000.toInt()) }
            spec.hours.forEachIndexed { i, h ->
                val t = h?.temp ?: return@forEachIndexed
                if ((start + i) % 3 == 0) {
                    val px = X(start + i + 0.5f); val py = g.ty(t, tr.first, tr.second)
                    c.drawCircle(px, py, 2.5f, dot)
                    c.label("${t.roundToInt()}°", px, py - 6f, tp, w)
                }
            }
        }
        // scale hint, top-left
        c.drawText("☁ thicker = cloudier", x0 + 6f, top + 12f, textPaint(0x99FFFFFF.toInt(), 9f, align = Paint.Align.LEFT).apply { setShadowLayer(2f, 0f, 0.5f, 0x99000000.toInt()) })
        c.restore()

        // ---- sky icons every 2 h
        val iconP = textPaint(WHITE, 16f)
        spec.hours.forEachIndexed { i, h ->
            val hh = start + i
            if (h != null && hh % 2 == 1) {
                val night = hh + 0.5f < spec.rise || hh + 0.5f > spec.set
                c.drawText(RwText.skyEmoji(h.cloud, h.rainMm, night), X(hh + 0.5f), ICONS_Y + 6f, iconP)
            }
        }

        // ---- axis: labels every 3 h, small hourly ticks, sunrise and sunset
        val axisP = textPaint(DIM, 10f)
        val tick = stroke(0xFF3A4150.toInt(), 1f)
        for (hh in start..g.end.toInt()) {
            c.drawLine(X(hh.toFloat()), base, X(hh.toFloat()), base + 3f, tick)
            if (hh % 3 == 0) c.label("${hh % 24}:00", X(hh.toFloat()).coerceIn(14f, w - 14f), base + 14f, axisP, w)
        }
        if (spec.plan.sun != null) {
            if (spec.rise in g.start..g.end) c.label("🌅 ${hhmm(spec.rise)}", X(spec.rise), base + 28f, textPaint(AMBER, 10f), w)
            if (spec.set in g.start..g.end) c.label("🌇 ${hhmm(spec.set)}", X(spec.set), base + 28f, textPaint(ORANGE, 10f), w)
        }

        // ---- window pills (narrow ones carry their label above)
        val py = g.pillY
        for (s in spec.wins) {
            val col = if (s.tight) AMBER else GOOD
            val a = X(s.a); val b = X(s.b)
            c.drawRoundRect(RectF(a, py - 10f, b, py + 6f), 8f, 8f, fill(col))
            val b0 = spec.best
            if (b0 != null && !s.tight && b0.a >= s.a - 0.01f && b0.b <= s.b + 0.01f) {
                c.drawRoundRect(RectF(X(b0.a), py - 10f, X(b0.b), py + 6f), 8f, 8f, fill(0xFF1E9E63.toInt()))
                c.label("best", (X(b0.a) + X(b0.b)) / 2f, py + 18f, textPaint(GOOD, 9.5f), w)
            }
            val t = "${hhmm(s.a)}–${hhmm(s.b)}"
            if (b - a > 80f) c.label(t, (a + b) / 2f, py + 2f, textPaint(0xFF0B0E13.toInt(), 10.5f, bold = true), w)
            else c.label(t, (a + b) / 2f, py - 14f, textPaint(col, 10.5f, bold = true), w)
        }

        // ---- UV dots (every 2 h), dew and wind rows on SubT / TT days
        var y = py + if (spec.best != null) 32f else 24f
        val rowLabel = textPaint(FAINT, 9.5f, align = Paint.Align.LEFT)
        c.drawText("UV", x0, y + 4f, rowLabel)
        spec.hours.forEachIndexed { i, h ->
            val hh = start + i
            if (h != null && hh % 2 == 0 && X(hh + 0.5f) > x0 + 22f) {
                val u = h.uv
                val dotP = fill(uvColor(u, lim)).apply { alpha = if (u < 0.5) (255 * 0.35f).toInt() else 255 }
                c.drawCircle(X(hh + 0.5f), y, 9f, dotP)
                c.drawText(uvText(u), X(hh + 0.5f), y + 3.5f, textPaint(0xFF10141B.toInt(), 9.5f, bold = true))
            }
        }
        if (spec.extra) {
            y += 24f
            c.drawText("Dew", x0, y + 4f, rowLabel)
            spec.hours.forEachIndexed { i, h ->
                val hh = start + i
                val dw = h?.dew
                if (dw != null && hh % 2 == 0 && X(hh + 0.5f) > x0 + 22f) {
                    val col = if (dw >= 16) RED else if (dw >= 12) AMBER else BLUE
                    c.label("💧${dw.roundToInt()}°", X(hh + 0.5f), y + 4f, textPaint(col, 10.5f, bold = true), w)
                }
            }
            y += 22f
            c.drawText("Wind", x0, y + 4f, rowLabel)
            spec.hours.forEachIndexed { i, h ->
                val hh = start + i
                val wd = h?.wind
                if (wd != null && hh % 2 == 0 && X(hh + 0.5f) > x0 + 22f) {
                    c.label("➤${wd.roundToInt()}", X(hh + 0.5f), y + 4f, textPaint(if (wd > 25) AMBER else 0xFFC9D0DC.toInt(), 10.5f), w)
                }
            }
        }
    }

    // ================================================================ scrubbing

    /** What a touch at some x shows: the snapped time, that hour's weather, rain (nowcast within 4 h), and whether it is inside a window. */
    class Probe(
        val timeSec: Long,
        /** Chart x (dp) the line snaps to. */
        val x: Float,
        val hour: Weather.Combined?,
        val rain: Double,
        val night: Boolean,
        val inWindow: Boolean,
        val inBest: Boolean,
        val why: String?,
        val fineStep: Boolean,
    ) {
        fun time(zone: ZoneId) = Weather.hhmm(timeSec, zone)
    }

    /** 10-minute steps inside the 4 h of nowcast after [Spec.nowSec], then 15-minute steps (today and tomorrow), then hour steps. */
    fun probe(spec: Spec, g: Geo, xDp: Float): Probe? {
        val tau = g.hourAt(xDp)
        val tSec = spec.day0 + (tau * 3600.0).toLong()
        val nowSec = spec.nowSec
        val nowcast = nowSec != null && spec.steps.isNotEmpty() && tSec >= nowSec - 600 && tSec <= nowSec + 4 * 3600
        val hs = Weather.hourFloor(tSec)
        val hourly = spec.hours.getOrNull(((hs - spec.day0) / 3600).toInt() - spec.start)
        val slot = Math.floorDiv(tSec, 900L) * 900L
        val fineHr = spec.fine[slot]
        val snapped = when {
            nowcast -> Math.floorDiv(tSec, 600L) * 600L
            fineHr != null -> slot
            else -> hs
        }
        val stepped = nowcast || fineHr != null
        val hr = if (nowcast) spec.fine[Math.floorDiv(snapped, 900L) * 900L] ?: hourly else fineHr ?: hourly
        val px = if (stepped) g.x(spec.fh(snapped)) else g.x(spec.fh(hs) + 0.5f)
        val probeT = if (stepped) snapped else hs + 1800
        var rain = hr?.rainMm ?: 0.0
        if (nowcast) {
            val m = spec.steps.filter { it.begin < snapped + 600 && it.end > snapped }.maxOfOrNull { it.rate }
            if (m != null) rain = m
        }
        val a = spec.fh(probeT)
        val inWin = spec.wins.any { a >= it.a && a < it.b }
        val inBest = spec.best?.let { a >= it.a && a < it.b } == true
        val night = a < spec.rise || a > spec.set
        var why: String? = null
        if (!inWin) {
            why = spec.plan.why[Math.floorDiv(snapped, 900L) * 900L].takeIf { stepped } ?: spec.plan.why[hs]
            if (why == null && rain > 0.5) why = "rain ${RwText.num1(rain)} mm/h"
            if (why == null) {
                val ps = spec.plan
                why = when {
                    ps.allowedHi > 0 && (probeT < ps.allowedLo || probeT >= ps.allowedHi) -> "outside your running hours"
                    ps.windows.isEmpty() -> "no window fits today"
                    else -> "too short a gap for your run"
                }
            }
        }
        return Probe(snapped, px, hr, rain, night, inWin, inBest, why, stepped)
    }

    private val CARD_W = 214f

    /** Draws the scrub line, dot and floating card over a chart already drawn at [d] px per dp. */
    fun drawScrub(c: Canvas, spec: Spec, g: Geo, p: Probe, d: Float) {
        c.save()
        c.scale(d, d)
        val line = stroke(WHITE, 1.5f)
        c.drawLine(p.x, TOP - 4f, p.x, g.lastY + 10f, line)
        val tr = tempRange(spec)
        val temp = p.hour?.temp
        if (tr != null && temp != null) {
            c.drawCircle(p.x, g.ty(temp, tr.first, tr.second), 5f, fill(WHITE))
            c.drawCircle(p.x, g.ty(temp, tr.first, tr.second), 5f, stroke(0xFF4EA1FF.toInt(), 2f))
        }
        // floating card
        val h = p.hour
        val cardW = min(CARD_W, g.w - 8f)
        val left = (p.x - 105f).coerceIn(4f, max(4f, g.w - cardW - 4f))
        val lineH = 16f
        val rows = 5
        val cardH = 12f + rows * lineH
        val topY = TOP - 6f
        val r = RectF(left, topY, left + cardW, topY + cardH)
        c.drawRoundRect(r, 12f, 12f, fill(0xF20F131B.toInt()))
        c.drawRoundRect(r, 12f, 12f, stroke(0xFF3A4150.toInt(), 1f))
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; color = 0xFFF2F4F8.toInt() }
        val bold = TextPaint(tp).apply { typeface = Typeface.DEFAULT_BOLD; textSize = 13f }
        val inner = cardW - 20f
        var y = topY + 20f
        val x = left + 10f
        val sky = RwText.skyEmoji(h?.cloud, p.rain, p.night)
        c.drawText(TextUtils.ellipsize("${p.time(spec.zone)} · $sky ${RwText.skyLabel(h?.cloud, p.rain, p.night)}", bold, inner, TextUtils.TruncateAt.END).toString(), x, y, bold)
        y += lineH
        // cloud, UV (coloured), temperature
        var cx = x
        fun part(t: String, color: Int) { tp.color = color; c.drawText(t, cx, y, tp); cx += tp.measureText(t) }
        part("☁️ ${h?.cloud?.roundToInt() ?: "–"}% · ", 0xFFF2F4F8.toInt())
        part("UV ${h?.let { RwText.num1(it.uv) } ?: "–"}", if (h != null) uvColor(h.uv, spec.uvLim) else DIM)
        part(" · 🌡 ${h?.temp?.roundToInt() ?: "–"}° (feels ${(h?.feels ?: h?.temp)?.roundToInt() ?: "–"}°)", 0xFFF2F4F8.toInt())
        tp.color = 0xFFF2F4F8.toInt()
        y += lineH
        val rainTxt = if (p.rain > 0.25) "🌧 ${RwText.num1(p.rain)} mm/h" else "dry"
        val pct = h?.rainPct?.roundToInt()
        c.drawText(TextUtils.ellipsize("💧 dew ${h?.dew?.roundToInt() ?: "–"}° · $rainTxt" + (if (pct != null) " (${pct}%)" else ""), tp, inner, TextUtils.TruncateAt.END).toString(), x, y, tp)
        y += lineH
        val gust = h?.gust?.roundToInt()
        c.drawText(TextUtils.ellipsize("💨 ${h?.wind?.roundToInt() ?: "–"} km/h" + (if (gust != null) " (gusts $gust)" else ""), tp, inner, TextUtils.TruncateAt.END).toString(), x, y, tp)
        y += lineH
        val verdict = if (p.inWindow) "✓ inside your window" + (if (p.inBest) " · best for ${spec.typeLabel}" else "") else "✗ outside window: ${p.why ?: "no run window"}"
        tp.color = if (p.inWindow) GOOD else 0xFFFF9A9A.toInt()
        c.drawText(TextUtils.ellipsize(verdict, tp, inner, TextUtils.TruncateAt.END).toString(), x, y, tp)
        c.restore()
    }
}

/** The chart as a view: draws [RwChart] at the view's width and shows the scrub line and card while a finger is down. */
class RwChartView(ctx: Context) : View(ctx) {
    private var spec: RwChart.Spec? = null
    private var rendered: RwChart.Rendered? = null
    private var renderedW = 0
    private var probe: RwChart.Probe? = null
    private val dens = resources.displayMetrics.density

    fun set(spec: RwChart.Spec) {
        this.spec = spec
        rendered = null
        probe = null
        requestLayout()
        invalidate()
    }

    private fun ensure(w: Int): RwChart.Rendered? {
        val sp = spec ?: return null
        val cur = rendered
        if (cur != null && renderedW == w) return cur
        // a drawing problem (or no memory for the bitmap) leaves the chart blank instead of taking the screen down
        val r = try { RwChart.render(sp, w, dens) } catch (e: Throwable) { android.util.Log.w("RwChart", "chart not drawn", e); return null }
        rendered = r; renderedW = w
        return r
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var w = MeasureSpec.getSize(widthMeasureSpec)
        if (w <= 0) w = resources.displayMetrics.widthPixels
        val r = ensure(w)
        setMeasuredDimension(w, r?.bmp?.height ?: 0)
    }

    override fun onDraw(canvas: Canvas) {
        val sp = spec ?: return
        val r = ensure(width.coerceAtLeast(1)) ?: return
        canvas.drawBitmap(r.bmp, 0f, 0f, null)
        probe?.let { pr -> runCatching { RwChart.drawScrub(canvas, sp, r.geo, pr, dens) } }
    }

    /** Puts the scrub line at [xPx] (view pixels); null lifts it. Also used by tests. */
    fun scrubTo(xPx: Float?) {
        val sp = spec ?: return
        val r = ensure(width.coerceAtLeast(1)) ?: return
        probe = if (xPx == null) null else RwChart.probe(sp, r.geo, xPx / dens)
        invalidate()
    }

    /** What the scrub shows right now (for tests). */
    fun currentProbe(): RwChart.Probe? = probe

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                scrubTo(e.x)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                scrubTo(null)
            }
        }
        return true
    }
}
