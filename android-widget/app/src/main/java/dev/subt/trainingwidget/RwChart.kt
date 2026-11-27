package dev.subt.trainingwidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The graphic day chart (Today card and day plan): a sky that goes from night to day with a cloud blanket hanging from the
 * top, rain streaks, a white temperature line, the window band(s) with a pill row under it, UV dots, and (SubT / TT days)
 * dew point and wind rows. Drawn in dp units on a scaled canvas. [RwChartView] adds touch-and-drag scrubbing.
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
            typeLabel = p.run.type.label,
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
        val nightP = fill(0xFF000000.toInt()).apply { shader = LinearGradient(0f, top, 0f, base, 0xFF151B2E.toInt(), 0xFF10131C.toInt(), Shader.TileMode.CLAMP) }
        c.drawRect(x0, top, x1, base, nightP)
        val dayShader = LinearGradient(0f, top, 0f, base, 0xFF3B5A86.toInt(), 0xFF22324A.toInt(), Shader.TileMode.CLAMP)
        val dawn = fill(0xFF000000.toInt()).apply { shader = dayShader; alpha = (255 * 0.55f).toInt() }
        c.drawRect(X(spec.rise - 0.5f), top, X(spec.set + 0.5f), base, dawn)
        val day = fill(0xFF000000.toInt()).apply { shader = dayShader }
        c.drawRect(X(spec.rise), top, X(spec.set), base, day)

        // cloud blanket hanging from the top: thicker = cloudier
        val blanket = Path()
        blanket.moveTo(X(g.start), top)
        spec.hours.forEachIndexed { i, h ->
            val cl = h?.cloud ?: return@forEachIndexed
            blanket.lineTo(X(start + i + 0.5f), top + (cl / 100.0 * ch * 0.55).toFloat())
        }
        blanket.lineTo(X(g.end), top)
        blanket.close()
        val cloudP = fill(0xFF000000.toInt()).apply { shader = LinearGradient(0f, top, 0f, top + ch * 0.55f, 0x8CE8EDF5.toInt(), 0x14E8EDF5, Shader.TileMode.CLAMP) }
        c.drawPath(blanket, cloudP)

        // rain streaks: denser with mm/h
        val rainP = stroke(0xBF7FB8FF.toInt(), 1.6f)
        val hourW = X(1f) - X(0f)
        spec.hours.forEachIndexed { i, h ->
            val r = h?.rainMm ?: 0.0
            if (r > 0.25) {
                val n = min(6, ceil(r * 1.5).toInt())
                for (k in 0 until n) {
                    val xx = X((start + i).toFloat()) + 4f + k * ((hourW - 8f) / 6f)
                    c.drawLine(xx, top + 30f, xx - 4f, base - 4f, rainP)
                }
            }
        }

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
            c.drawPath(path, stroke(0xE6FFFFFF.toInt(), 2f).apply { strokeJoin = Paint.Join.ROUND })
            val dot = fill(WHITE)
            val tp = textPaint(WHITE, 10.5f, bold = true)
            spec.hours.forEachIndexed { i, h ->
                val t = h?.temp ?: return@forEachIndexed
                if ((start + i) % 3 == 0) {
                    val px = X(start + i + 0.5f); val py = g.ty(t, tr.first, tr.second)
                    c.drawCircle(px, py, 2.5f, dot)
                    c.label("${t.roundToInt()}°", px, py - 6f, tp, w)
                }
            }
        }
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

    /** Hour steps; 10-minute steps inside the 4 h of nowcast after [Spec.nowSec]. */
    fun probe(spec: Spec, g: Geo, xDp: Float): Probe? {
        val tau = g.hourAt(xDp)
        val tSec = spec.day0 + (tau * 3600.0).toLong()
        val nowSec = spec.nowSec
        val fine = nowSec != null && spec.steps.isNotEmpty() && tSec >= nowSec - 600 && tSec <= nowSec + 4 * 3600
        val hs = Weather.hourFloor(tSec)
        val hr = spec.hours.getOrNull(((hs - spec.day0) / 3600).toInt() - spec.start)
        val snapped = if (fine) Math.floorDiv(tSec, 600L) * 600L else hs
        val px = if (fine) g.x(spec.fh(snapped)) else g.x(spec.fh(hs) + 0.5f)
        val probeT = if (fine) snapped else hs + 1800
        var rain = hr?.rainMm ?: 0.0
        if (fine) {
            val m = spec.steps.filter { it.begin < snapped + 600 && it.end > snapped }.maxOfOrNull { it.rate }
            if (m != null) rain = m
        }
        val a = spec.fh(probeT)
        val inWin = spec.wins.any { a >= it.a && a < it.b }
        val inBest = spec.best?.let { a >= it.a && a < it.b } == true
        val night = a < spec.rise || a > spec.set
        var why: String? = null
        if (!inWin) {
            why = spec.plan.why[hs]
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
        return Probe(probeT.let { if (fine) snapped else hs }, px, hr, rain, night, inWin, inBest, why, fine)
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
        val r = RwChart.render(sp, w, dens)
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
        probe?.let { RwChart.drawScrub(canvas, sp, r.geo, it, dens) }
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
