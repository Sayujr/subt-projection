package dev.subt.trainingwidget

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * "Hour by hour", like Rainbow's vertical timeline: one row per hour. On the left 40% a strip in the chart's sky colours
 * (day blue, dusk orange, night navy) with the time and a condition pill; on the right compact chips (temperature / feels,
 * UV dot, rain, wind, and dew point on SubT / TT days). A green bar down the left edge marks window hours, a brighter one the
 * best stretch. Sunrise and sunset are small pills between rows, a "now" marker shows on today, dark hours are dimmed.
 */
object RwHourList {
    private const val TEXT = 0xFFF2F4F8.toInt()
    private const val DIM = 0xFF9AA3B2.toInt()
    private const val GOOD = 0xFF2FA36B.toInt()
    private const val BEST = 0xFF5CFFA8.toInt()
    private const val AMBER = 0xFFFFC53D.toInt()
    private const val RAIN = 0xFF4EA1FF.toInt()
    private const val CHIP = 0x26FFFFFF
    private const val NOW = 0xFFFF6B6B.toInt()

    data class Row(
        val hour: Weather.Combined, val sky: String, val label: String, val skyArgb: Int,
        val cloud: String, val uv: String, val uvColor: Int,
        val feels: String, val dew: String, val rain: String, val rainWet: Boolean, val chance: String, val wind: String,
        val bar: Int, val best: Boolean, val night: Boolean, val inWindow: Boolean,
    )

    /** The condition word on the pill: Cloudy, Mostly cloudy, Partly cloudy, Clear, Rain or Drizzle. */
    fun condLabel(cloud: Double?, rainMm: Double): String {
        val c = cloud ?: 50.0
        return when {
            rainMm > 0.5 -> "Rain"
            rainMm > 0.25 -> "Drizzle"
            c >= 80 -> "Cloudy"
            c >= 50 -> "Mostly cloudy"
            c >= 25 -> "Partly cloudy"
            else -> "Clear"
        }
    }

    /** Separator pills placed before the row of hour [t]: sunrise, sunset, and where each window starts. */
    fun separators(p: Weather.DayPlan, t: Long, zone: ZoneId): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        val sun = p.sun
        if (sun != null) {
            if (t >= sun.rise && t - sun.rise < 3600) out += "🌅 ${Weather.hhmm(sun.rise, zone)} Sunrise" to AMBER
            if (t > sun.set && t - 3600 <= sun.set) out += "🌇 ${Weather.hhmm(sun.set, zone)} Sunset" to 0xFFF7933F.toInt()
        }
        for (w in p.windows) if (w.start >= t && w.start < t + 3600) out += "▼ Window ${Weather.range(w, zone)}" to (if (w.narrow) AMBER else BEST)
        return out
    }

    /** "● Now 14:20" before the row of the hour that holds [nowSec]; null otherwise. */
    fun nowMarker(t: Long, nowSec: Long?, zone: ZoneId): String? =
        if (nowSec != null && nowSec >= t && nowSec < t + 3600) "● Now ${Weather.hhmm(nowSec, zone)}" else null

    fun rows(p: Weather.DayPlan, s: RwSettings, zone: ZoneId): List<Row> {
        val day0 = Weather.atMinutes(p.date, 0, zone)
        val rise = p.sun?.let { (it.rise - day0) / 3600f } ?: 6f
        val set = p.sun?.let { (it.set - day0) / 3600f } ?: 20f
        return p.hours.filter { it.t + 3600 > p.lo && it.t < p.hi }.sortedBy { it.t }.map { h ->
            val night = Weather.isNight(h.t, p.sun, zone)
            val win = p.windows.firstOrNull { h.t + 1800 > it.start && h.t < it.end }
            val best = p.best?.let { h.t >= it.start && h.t < it.end } == true
            val t = h.temp?.roundToInt()?.toString() ?: "–"
            val f = (h.feels ?: h.temp)?.roundToInt()?.toString() ?: "–"
            val wet = h.rainMm > 0.25
            val rgb = RwChart.skyColor((h.t + 1800 - day0) / 3600f, rise, set)
            Row(
                hour = h,
                sky = RwText.skyEmoji(h.cloud, h.rainMm, night),
                label = condLabel(h.cloud, h.rainMm),
                skyArgb = Color.rgb(rgb[0], rgb[1], rgb[2]),
                cloud = (h.cloud?.roundToInt()?.toString() ?: "–") + "%",
                uv = RwText.num1(h.uv), uvColor = RwChart.uvColor(h.uv, h.cloud, s),
                feels = "$t°/$f°", dew = (h.dew?.roundToInt()?.toString() ?: "–") + "°",
                rain = if (h.rainMm > 0.0) RwText.num1(h.rainMm) + " mm" else "", rainWet = wet,
                chance = if (h.rainPct >= 20 || wet) "${h.rainPct.roundToInt()}%" else "",
                wind = (h.wind?.roundToInt()?.toString() ?: "–") + (h.gust?.let { " g${it.roundToInt()}" } ?: ""),
                bar = if (win == null) Color.TRANSPARENT else if (best) BEST else if (win.narrow) AMBER else GOOD,
                best = best, night = night, inWindow = win != null,
            )
        }
    }

    fun build(ctx: Context, p: Weather.DayPlan, s: RwSettings, zone: ZoneId, nowSec: Long? = null): LinearLayout {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).roundToInt()
        fun dpf(v: Float) = v * d
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
        val dewDay = p.run.type == Weather.RunType.SUBT || p.run.type == Weather.RunType.TT

        fun pill(parent: LinearLayout, content: CharSequence, color: Int, bg: Int = CHIP, sp: Float = 11.5f, left: Int = 0): TextView {
            val tv = TextView(ctx).apply {
                text = content; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); maxLines = 1
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply { setColor(bg); cornerRadius = dpf(10f) }
                setPadding(dp(8), dp(3), dp(8), dp(3))
            }
            parent.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(left) })
            return tv
        }
        fun marker(text: String, color: Int) {
            val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(3), 0, dp(3)) }
            pill(line, text, color, bg = (color and 0x00FFFFFF) or 0x33000000, sp = 11f)
            col.addView(line)
        }

        val rows = rows(p, s, zone)
        val lastBest = rows.lastOrNull { it.best }?.hour?.t
        var nowShown = false
        for (r in rows) {
            for ((txt, color) in separators(p, r.hour.t, zone)) marker(txt, color)
            if (!nowShown) nowMarker(r.hour.t, nowSec, zone)?.let { marker(it, NOW); nowShown = true }

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                if (r.night) alpha = 0.6f
            }
            // window bar down the left edge
            row.addView(View(ctx).apply { setBackgroundColor(r.bar) }, LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT).apply { rightMargin = dp(6) })

            // sky strip: time and condition pill
            val lighter = Color.rgb((Color.red(r.skyArgb) * 1.25f).roundToInt().coerceAtMost(255), (Color.green(r.skyArgb) * 1.25f).roundToInt().coerceAtMost(255), (Color.blue(r.skyArgb) * 1.25f).roundToInt().coerceAtMost(255))
            val sky = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(lighter, r.skyArgb)).apply { cornerRadius = dpf(12f) }
                setPadding(dp(10), dp(7), dp(8), dp(7))
            }
            sky.addView(TextView(ctx).apply {
                text = Weather.hhmm(r.hour.t, zone); setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            })
            val pillRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            pill(pillRow, r.sky + " " + r.label, Color.WHITE, bg = 0x59000000, sp = 11.5f)
            sky.addView(pillRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            row.addView(sky, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.4f))

            // chips
            val chips = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, 0, 0) }
            val l1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            pill(l1, "🌡 " + r.feels, TEXT)
            val uvText = SpannableStringBuilder("● UV ${r.uv}")
            uvText.setSpan(ForegroundColorSpan(r.uvColor), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            uvText.setSpan(ForegroundColorSpan(TEXT), 1, uvText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            pill(l1, uvText, TEXT, left = 5)
            chips.addView(l1)
            val l2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, 0) }
            if (r.hour.rainMm > 0.0) {
                val txt = "💧 " + r.rain + (if (r.chance.isNotEmpty()) " · " + r.chance else "")
                pill(l2, txt, RAIN, bg = 0x264EA1FF)
            }
            pill(l2, "💨 " + r.wind, DIM, left = if (r.hour.rainMm > 0.0) 5 else 0)
            if (dewDay) pill(l2, "dew " + r.dew, DIM, left = 5)
            chips.addView(l2)
            row.addView(chips, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.6f))

            col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            if (r.hour.t == lastBest) {
                val why = if (dewDay) "lowest dew point and coolest air" else "cloudiest, lowest UV, dry"
                marker("▲ Best for ${p.run.type.label} ($why)", BEST)
            }
        }
        col.addView(TextView(ctx).apply {
            text = "Green bar = runnable window · brighter = best stretch · amber = tight · faded = dark · UV dot: green fine, yellow too sunny, red over your max"
            setTextColor(DIM); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f); setPadding(0, dp(10), 0, 0)
        })
        return col
    }
}
