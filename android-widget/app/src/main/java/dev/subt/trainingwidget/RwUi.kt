package dev.subt.trainingwidget

import android.app.Activity
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
import android.widget.Toast
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** Pieces of run-window screens shared by the day plan and the Today card. */
object RwUi {
    /** "Right now: ☁ Cloudy · ☀ Sunny · 💧 Raining": one tap records what it's like outside, to check the forecasts. */
    fun outsideRow(a: Activity, onDone: () -> Unit = {}): LinearLayout {
        val d = a.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).roundToInt()
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
        row.addView(TextView(a).apply {
            text = "Right now:"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTextColor(0xFF9AA3B2.toInt()); setPadding(0, 0, dp(8), 0)
        })
        for ((label, kind) in listOf("☁ Cloudy" to "cloudy", "☀ Sunny" to "sunny", "💧 Raining" to "rain")) {
            row.addView(TextView(a).apply {
                text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f); setTextColor(0xFFF2F4F8.toInt()); gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.chip_off); setPadding(dp(6), dp(9), dp(6), dp(9)); maxLines = 1
                setOnClickListener {
                    Scoring.recordUser(a, kind)
                    Toast.makeText(a, "Thanks — used to check the forecasts", Toast.LENGTH_SHORT).show()
                    onDone()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4) })
        }
        return row
    }

    private const val TEXT = 0xFFF2F4F8.toInt()
    private const val DIM = 0xFF9AA3B2.toInt()
    private const val BLUE = 0xFF8FD3FF.toInt()
    private const val BAD = 0xFFFF9A9A.toInt()

    /** The sky chart's key (the ⓘ on the Today card and the day plan). */
    const val CHART_KEY = "Clouds: more and thicker = cloudier (0–100%). Blue streaks = rain. White line = temperature. Dots = UV (green: low, or under enough cloud for your rule; yellow: too sunny for it; red: over your max). Stars = clear night."

    /**
     * The graphic chart for [p] (scrubbable). Today's chart gets the live nowcast for 10-minute steps. Without a forecast for the
     * day, or if the chart can't be built, a plain "Forecast not loaded yet" line stands in (never a crash).
     */
    fun chart(a: Activity, p: Weather.DayPlan, s: RwSettings, today: Boolean): View {
        val zone = ZoneId.systemDefault()
        val v = runCatching {
            if (p.noData || p.hours.isEmpty()) return@runCatching null
            val steps = if (today) runCatching { RunWindows.data(a)?.now }.getOrNull().orEmpty() else emptyList()
            RwChartView(a).also { it.set(RwChart.spec(p, s, zone, if (today) System.currentTimeMillis() / 1000 else null, steps)) }
        }.getOrNull()
        if (v != null) return v
        return TextView(a).apply { text = "Forecast not loaded yet"; setTextColor(DIM); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f); setPadding(0, (8 * a.resources.displayMetrics.density).roundToInt(), 0, 0) }
    }

    /** ① and ② lines on a two-run day (② in light blue, or red when it has no window). */
    fun twoRunLines(a: Activity, parent: LinearLayout, p: Weather.DayPlan, zone: ZoneId) {
        val l = RwText.twoRunLines(p, zone) ?: return
        fun tv(t: CharSequence, top: Int) = TextView(a).apply {
            text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f); setTextColor(DIM)
            setPadding(0, (top * a.resources.displayMetrics.density).roundToInt(), 0, 0)
        }
        parent.addView(tv(l.first, 3))
        val sb = SpannableStringBuilder(l.prefix)
        val st = sb.length
        sb.append(l.hi)
        sb.setSpan(ForegroundColorSpan(if (l.bad) BAD else BLUE), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(l.rest)
        parent.addView(tv(sb, 3))
    }

    /** "🚴 Swap ② for an indoor bike" with Add to intervals.icu / Keep the run. Hidden once chosen. */
    fun bikeBox(a: Activity, parent: LinearLayout, p: Weather.DayPlan, onChange: () -> Unit) {
        val b = p.bikeSwap ?: return
        val d = a.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).roundToInt()
        val state = RwBike.state(a, p.date)
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xFF262C3A.toInt()); cornerRadius = dp(12).toFloat() }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        box.addView(TextView(a).apply { text = "🚴 Swap ② for an indoor bike"; setTextColor(TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f); typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) })
        if (state == "added") {
            box.addView(TextView(a).apply { text = "✓ Added to intervals.icu: ${RwText.bikeLine(b)}"; setTextColor(0xFF3DD68C.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setPadding(0, dp(2), 0, 0) })
        } else if (state == "kept") {
            return
        } else {
            box.addView(TextView(a).apply { text = RwText.bikeLine(b); setTextColor(DIM); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setPadding(0, dp(2), 0, 0) })
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
            fun btn(t: String, color: Int, click: () -> Unit) = TextView(a).apply {
                text = t; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.chip_off); setPadding(dp(8), dp(9), dp(8), dp(9)); setOnClickListener { click() }
            }
            row.addView(btn("Add to intervals.icu", TEXT) { RwBike.confirmAndAdd(a, p.date, b, onChange) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(btn("Keep the run", DIM) { RwBike.keep(a, p.date); onChange() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
            box.addView(row)
            box.addView(TextView(a).apply { text = "Adds the ride on that day at 17:00; the planned run stays (not deleted) and shows as not done."; setTextColor(0xFF6B7386.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f); setPadding(0, dp(6), 0, 0) })
        }
        parent.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
    }
}
