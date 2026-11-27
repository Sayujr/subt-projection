package dev.subt.trainingwidget

import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * "Hour by hour": one row per hour with a green bar for the window, tinted rows for the best stretch (SubT / TT),
 * sunrise / sunset / window separators, dark hours faded. Columns: time, sky, cloud, UV, feels, dew, rain, wind.
 */
object RwHourList {
    private const val TEXT = 0xFFF2F4F8.toInt()
    private const val DIM = 0xFF6B7386.toInt()
    private const val GOOD = 0xFF3DD68C.toInt()
    private const val AMBER = 0xFFFFC53D.toInt()
    private const val RAIN = 0xFF4EA1FF.toInt()

    /** Column widths in dp: bar, time, sky, cloud, UV, feels, dew, rain (wind takes the rest). */
    private val COLS = listOf(6, 42, 24, 38, 32, 48, 30, 62)

    data class Row(
        val hour: Weather.Combined, val sky: String, val cloud: String, val uv: String, val uvColor: Int,
        val feels: String, val dew: String, val rain: String, val rainWet: Boolean, val chance: String, val wind: String,
        val bar: Int, val best: Boolean, val night: Boolean,
    )

    /** Separator lines placed before the row of hour [t], and the "best" line after the last best row. */
    fun separators(p: Weather.DayPlan, t: Long, zone: ZoneId): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        val sun = p.sun
        if (sun != null) {
            if (t >= sun.rise && t - sun.rise < 3600) out += "☀ sunrise ${Weather.hhmm(sun.rise, zone)}" to AMBER
            if (t > sun.set && t - 3600 <= sun.set) out += "☾ sunset ${Weather.hhmm(sun.set, zone)}" to 0xFFF7933F.toInt()
        }
        for (w in p.windows) if (w.start >= t && w.start < t + 3600) out += "▼ window ${Weather.range(w, zone)}" to (if (w.narrow) AMBER else GOOD)
        return out
    }

    fun rows(p: Weather.DayPlan, s: RwSettings, zone: ZoneId): List<Row> {
        val lim = if (p.run.type == Weather.RunType.LONG) s.uvLimit - 1.0 else s.uvLimit
        return p.hours.filter { it.t + 3600 > p.lo && it.t < p.hi }.sortedBy { it.t }.map { h ->
            val night = Weather.isNight(h.t, p.sun, zone)
            val win = p.windows.firstOrNull { h.t + 1800 > it.start && h.t < it.end }
            val best = p.best?.let { h.t >= it.start && h.t < it.end } == true
            val t = h.temp?.roundToInt()?.toString() ?: "–"
            val f = (h.feels ?: h.temp)?.roundToInt()?.toString() ?: "–"
            val wet = h.rainMm > 0.25
            Row(
                hour = h,
                sky = RwText.skyEmoji(h.cloud, h.rainMm, night),
                cloud = (h.cloud?.roundToInt()?.toString() ?: "–") + "%",
                uv = RwText.num1(h.uv), uvColor = RwChart.uvColor(h.uv, lim),
                feels = "$t/$f", dew = (h.dew?.roundToInt()?.toString() ?: "–") + "°",
                rain = if (wet) RwText.num1(h.rainMm) + "mm" else "–", rainWet = wet,
                chance = if (h.rainPct >= 20 || wet) "${h.rainPct.roundToInt()}%" else "",
                wind = (h.wind?.roundToInt()?.toString() ?: "–") + (h.gust?.let { " g${it.roundToInt()}" } ?: ""),
                bar = if (win == null) Color.TRANSPARENT else if (win.narrow) AMBER else GOOD,
                best = best, night = night,
            )
        }
    }

    fun build(ctx: Context, p: Weather.DayPlan, s: RwSettings, zone: ZoneId): LinearLayout {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).roundToInt()
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        fun cell(parent: LinearLayout, t: CharSequence, w: Int?, color: Int, sp: Float = 12.5f, gravity: Int = Gravity.START) {
            val tv = TextView(ctx).apply {
                text = t; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); this.gravity = gravity; maxLines = 1
            }
            parent.addView(tv, if (w != null) LinearLayout.LayoutParams(dp(w), ViewGroup.LayoutParams.WRAP_CONTENT) else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        // header
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(2)) }
        val titles = listOf("", "Time", "", "Cloud", "UV", "Feels", "Dew", "Rain", "Wind")
        titles.forEachIndexed { i, t -> cell(head, t, COLS.getOrNull(i), DIM, 10f) }
        col.addView(head)
        val rows = rows(p, s, zone)
        val lastBest = rows.lastOrNull { it.best }?.hour?.t
        for (r in rows) {
            for ((txt, color) in separators(p, r.hour.t, zone)) {
                col.addView(TextView(ctx).apply { text = txt; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f); setPadding(dp(14), dp(2), 0, dp(2)) })
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(5))
                if (r.best) setBackgroundColor(0x143DD68C)
                if (r.night) alpha = 0.45f
            }
            row.addView(View(ctx).apply { setBackgroundColor(r.bar) }, LinearLayout.LayoutParams(dp(COLS[0] - 2), dp(18)).apply { rightMargin = dp(2) })
            cell(row, Weather.hhmm(r.hour.t, zone), COLS[1], TEXT)
            cell(row, r.sky, COLS[2], TEXT, 13f)
            cell(row, r.cloud, COLS[3], TEXT)
            cell(row, r.uv, COLS[4], r.uvColor)
            cell(row, r.feels, COLS[5], TEXT)
            cell(row, r.dew, COLS[6], DIM)
            val rain = SpannableStringBuilder(r.rain)
            rain.setSpan(ForegroundColorSpan(if (r.rainWet) RAIN else DIM), 0, rain.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (r.chance.isNotEmpty()) {
                val st = rain.length
                rain.append(" ").append(r.chance)
                rain.setSpan(ForegroundColorSpan(DIM), st, rain.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                rain.setSpan(RelativeSizeSpan(0.75f), st, rain.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            cell(row, rain, COLS[7], DIM)
            cell(row, r.wind, null, DIM)
            col.addView(row)
            if (r.hour.t == lastBest) {
                val why = when (p.run.type) { Weather.RunType.SUBT -> "lowest dew point and coolest air"; else -> "coolest, lightest wind" }
                col.addView(TextView(ctx).apply { text = "▲ best for ${p.run.type.label} ($why)"; setTextColor(GOOD); setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f); setPadding(dp(14), dp(2), 0, dp(2)) })
            }
        }
        col.addView(TextView(ctx).apply {
            text = "Green bar = runnable window · amber = tight · tinted rows = best for this workout · faded = dark"
            setTextColor(0xFF9AA3B2.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f); setPadding(0, dp(8), 0, 0)
        })
        return col
    }
}
