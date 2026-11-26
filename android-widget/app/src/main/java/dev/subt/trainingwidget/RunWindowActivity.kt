package dev.subt.trainingwidget

import android.app.Activity
import android.content.Context
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The day plan: a plain-language summary first, then the strips, an hour table for the window,
 * sunrise and sunset, and what each source thinks. Opened for today, tomorrow, or a day from the Week list.
 */
class RunWindowActivity : Activity() {
    companion object {
        const val EXTRA_DATE = "date"
        fun open(ctx: Context, date: LocalDate) =
            ctx.startActivity(Intent(ctx, RunWindowActivity::class.java).putExtra(EXTRA_DATE, date.toString()))

        private const val TEXT = 0xFFF2F4F8.toInt()
        private const val DIM = 0xFF9AA3B2.toInt()
        private const val FAINT = 0xFF6B7386.toInt()
        private const val GOOD = 0xFF3DD68C.toInt()
        private const val WARN = 0xFFFFC53D.toInt()
        private const val LINK = 0xFF4EA1FF.toInt()
        private const val ORANGE = 0xFFFF9F43.toInt()
    }

    private lateinit var col: LinearLayout
    private var date: LocalDate = LocalDate.now()
    private var status: String? = null
    private val zone = ZoneId.systemDefault()

    private val d get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        date = runCatching { LocalDate.parse(intent?.getStringExtra(EXTRA_DATE).orEmpty()) }.getOrDefault(LocalDate.now())
        val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false; fitsSystemWindows = true }
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(24)) }
        sv.addView(col)
        setContentView(sv)
        render()
    }

    /** A new intervals.icu snapshot (after a swap, say) redraws the plan. */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "snapshot") runOnUiThread { if (!isFinishing) render() }
    }

    override fun onResume() {
        super.onResume()
        getSharedPreferences("training_widget", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefsListener)
        RunWindows.autoRefresh(this) { if (!isFinishing) render() }
    }

    override fun onPause() {
        getSharedPreferences("training_widget", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onPause()
    }

    // ---------------------------------------------------------------- building blocks

    private fun text(parent: LinearLayout, t: CharSequence, sp: Float = 14f, color: Int = TEXT, bold: Boolean = false, top: Int = 0): TextView {
        val tv = TextView(this).apply {
            text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color)
            if (bold) typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(top), 0, 0)
        }
        parent.addView(tv)
        return tv
    }

    private fun label(parent: LinearLayout, t: String, color: Int = DIM) = text(parent, t.uppercase(Locale.getDefault()), 11f, color).apply { letterSpacing = 0.08f }

    private fun card(): LinearLayout {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.tile_bg); setPadding(dp(14), dp(12), dp(14), dp(14)) }
        col.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        return c
    }

    private fun header(title: String, sub: String) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(10)) }
        r.addView(TextView(this).apply { text = "‹"; textSize = 28f; setTextColor(TEXT); setPadding(dp(4), 0, dp(14), 0); setOnClickListener { finish() } })
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(TextView(this).apply { text = title; textSize = 19f; setTextColor(TEXT); typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL); maxLines = 2 })
        if (sub.isNotEmpty()) t.addView(TextView(this).apply { text = sub; textSize = 13f; setTextColor(DIM) })
        r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(r)
    }

    private fun button(parent: LinearLayout, t: String, onClick: () -> Unit) {
        parent.addView(TextView(this).apply {
            text = t; textSize = 15f; setTextColor(TEXT); gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.input_bg)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    private fun image(parent: LinearLayout, bmp: android.graphics.Bitmap) {
        val iv = ImageView(this).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_XY }
        parent.addView(iv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bmp.height).apply { topMargin = dp(6) })
    }

    /** A strip that may be wider than the screen: scrolls sideways, opened with the best window in the middle. */
    private fun scrollImage(parent: LinearLayout, bmp: android.graphics.Bitmap, plan: Weather.DayPlan, viewW: Int) {
        if (bmp.width <= viewW) { image(parent, bmp); return }
        val iv = ImageView(this).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_XY }
        val hsv = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        hsv.addView(iv, ViewGroup.LayoutParams(bmp.width, bmp.height))
        parent.addView(hsv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bmp.height).apply { topMargin = dp(6) })
        val focus = plan.window ?: plan.rainbow ?: plan.split?.first
        if (focus != null) {
            val mid = (focus.start + focus.end) / 2
            val frac = (mid - plan.lo).toFloat() / (plan.hi - plan.lo).coerceAtLeast(1)
            hsv.post { hsv.scrollTo((frac * bmp.width - viewW / 2f).roundToInt().coerceAtLeast(0), 0) }
        }
    }

    private fun cells(parent: LinearLayout, vals: List<String>, weights: List<Float>, header: Boolean = false, color: Int = TEXT) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(if (header) 8 else 5), 0, dp(4)) }
        vals.forEachIndexed { i, v ->
            r.addView(TextView(this).apply {
                text = v; setTextSize(TypedValue.COMPLEX_UNIT_SP, if (header) 10.5f else 13f)
                setTextColor(if (header) DIM else color); gravity = if (i == 0) Gravity.START else Gravity.END
                maxLines = 1
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weights[i]))
        }
        parent.addView(r)
    }

    private fun refreshNow() {
        status = "Refreshing…"
        render()
        RunWindows.refreshAsync(this, manual = true) { rep ->
            status = if (rep.anyOk) "Updated" + if (rep.failed.isNotEmpty()) " (skipped: ${rep.failed.joinToString(", ")})" else ""
            else "Couldn't reach any source: ${rep.failed.joinToString(", ")}"
            if (!isFinishing) render()
        }
    }

    // ---------------------------------------------------------------- the screen

    private fun render() {
        col.removeAllViews()
        val dayName = date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()))
        val s = RwStore.load(this)
        header("Run window", dayName)
        if (s.lat == null || s.lon == null) {
            val c = card()
            text(c, "Set your location in Settings → Run windows", 15f, TEXT)
            button(c, "Open Settings") { startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_OPEN, "rw")) }
            return
        }
        val plan = RunWindows.plan(this, date)
        if (plan == null || plan.noData) {
            val c = card()
            text(c, status ?: "No forecast for this day yet.", 15f, TEXT)
            if (status == null) text(c, "Tap Refresh now to fetch it.", 13f, DIM, top = 4)
            button(c, "Refresh now") { refreshNow() }
            return
        }
        val nowSec = if (date == LocalDate.now()) System.currentTimeMillis() / 1000 else null
        val wPx = resources.displayMetrics.widthPixels - dp(24 + 28)

        // 0. Outside now (today only): one tap tells the app what it's like, to check the forecasts
        if (date == LocalDate.now()) run {
            val c = card()
            label(c, "Outside now?")
            c.addView(RwUi.outsideRow(this))
            text(c, "One tap records what you can see, so the app can check which forecast to trust.", 12f, FAINT, top = 6)
        }
        // 1. Summary box
        run {
            val c = card()
            label(c, "Summary")
            val w = plan.window
            text(c, if (w == null) "No window" else Weather.range(w, zone) + (if (w.icons.isNotEmpty()) "  " + w.icons.joinToString(" ") else ""), 26f, TEXT, bold = true, top = 2)
            text(c, "${plan.run.name} · ${plan.run.type.label} · ${plan.run.minutes} min" + if (plan.run.planned) "" else " (nothing planned: using 60 min)", 13.5f, DIM)
            val lines = plan.summary.lines()
            for ((i, l) in lines.withIndex()) text(c, l, 14f, if (l.startsWith("⛔") || l.startsWith("⚠")) WARN else TEXT, top = if (i == 0) 10 else 8)
            if (date == LocalDate.now()) Watch.reason(this)?.let { text(c, "Watching closely: $it", 13.5f, GOOD, top = 8) }
            Scoring.accuracyLine(this)?.let { text(c, it, 12.5f, DIM, top = 8) }
            RunWindows.asOf(this)?.let { text(c, "Forecast $it", 12f, WARN, top = 8) }
        }
        // 1b. Fit it in (missed / at risk / tight window) and watch closely
        run {
            val cand = SwapFlow.forDate(this, date)
            val today = date == LocalDate.now()
            if (cand != null || today) {
                val c = card()
                if (cand != null) {
                    val dayWord = date.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()))
                    val name = cand.workout.name
                    when (cand.trigger) {
                        Swaps.Trigger.AT_RISK -> {
                            text(c, "No good time to run $dayWord (${cand.why ?: "poor conditions"}). Likely to miss $name? See options.", 13.5f, ORANGE)
                            button(c, "Show options ›") { SwapActivity.open(this, date, cand.workout.eventId, cand.trigger, cand.why) }
                            button(c, "I'll still run it") { cand.workout.eventId?.let { SwapFlow.mark(this, it, "dismissed") }; render() }
                        }
                        Swaps.Trigger.TIGHT -> {
                            text(c, "Tight window ${cand.why ?: ""} — can't fit $name in? Options.".replace("  ", " "), 13.5f, WARN)
                            button(c, "Fit it in ›") { SwapActivity.open(this, date, cand.workout.eventId, cand.trigger, cand.why) }
                        }
                        else -> {
                            text(c, "Missed $name? There are ways to fit it back into the week.", 13.5f, WARN)
                            button(c, "Fit it in ›") { SwapActivity.open(this, date, cand.workout.eventId, cand.trigger, cand.why) }
                        }
                    }
                }
                if (today) {
                    val on = Watch.isOn(this)
                    button(c, if (on) "Stop watching closely" else "Watch today closely") {
                        val nowOn = Watch.toggle(this)
                        Toast.makeText(this, if (nowOn) "Checking every 30 minutes until the window starts" else "No longer watching closely", Toast.LENGTH_SHORT).show()
                        render()
                    }
                    text(c, "Watching checks the forecast every 30 minutes until the window starts (or 22:00, never 22:00–05:00) and tells you about any change. It switches on by itself for a narrow ⚠ window or when sources disagree.", 12f, FAINT, top = 6)
                }
            }
        }
        // 2. Strips
        run {
            val c = card()
            label(c, "Overall pick")
            scrollImage(c, RwViews.strip(this, plan, s, wPx, false, nowSec, other = if (plan.same) null else plan.rainbow, minCellDp = 22f), plan, wPx)
            if (plan.rainbowPresent && plan.rbHours.isNotEmpty()) {
                label(c, if (plan.same) "Rainbow alone (same window)" else "Rainbow pick (dashed on the strip above)").setPadding(0, dp(12), 0, 0)
                scrollImage(c, RwViews.strip(this, plan, s, wPx, false, nowSec, hours = plan.rbHours, win = plan.rainbow, minCellDp = 22f), plan, wPx)
            }
            text(c, "White box = the window, blue line = now. Cell colour is the weather: green cool, orange to red hotter, dark red hot and sunny, grey-blue cold; night cells are darker with a 🌙, hatched cells have no data. Icons: 💧 rain, ☁ cloud, ☀ UV over your limit, 🔥 hot, 🧤 cold, 🧊 ice, 😷 air quality. The line above the cells shows daylight (solid), 'dark OK' (dashed) and night (dotted).", 12f, FAINT, top = 8)
        }
        // 3. Hour table for the window
        val w = plan.window
        if (w != null) {
            val c = card()
            label(c, "The window, hour by hour")
            val wt = listOf(1.0f, 1.2f, 0.8f, 0.6f, 0.7f, 1.4f, 1.3f)
            cells(c, listOf("Hour", "°C feels", "Cloud", "UV", "Dew", "Rain mm·%", "Wind"), wt, header = true)
            val hf = DateTimeFormatter.ofPattern("HH:mm")
            for (h in plan.hours.filter { it.t < w.end && it.t + 3600 > w.start }) {
                val wet = h.rainMm > 0.5
                cells(c, listOf(
                    hf.format(java.time.Instant.ofEpochSecond(h.t).atZone(zone)),
                    "${h.temp?.roundToInt() ?: "–"} / ${h.feels?.roundToInt() ?: "–"}",
                    "${h.cloud?.roundToInt() ?: "–"}%",
                    String.format(Locale.US, "%.1f", h.uv),
                    "${h.dew?.roundToInt() ?: "–"}",
                    String.format(Locale.US, "%.1f·%d%%", h.rainMm, h.rainPct.roundToInt()),
                    "${h.wind?.roundToInt() ?: "–"} (${h.gust?.roundToInt() ?: "–"})",
                ), wt, color = if (wet || h.uv > s.uvLimit) WARN else TEXT)
            }
            text(c, "Rain is mm per hour · chance. Wind and gusts in km/h. Yellow rows: rain over 0.5 mm/h or UV over your limit of ${s.uvLimit.roundToInt()}.", 12f, FAINT, top = 6)
            plan.sun?.let { text(c, "🌅 sunrise ${Weather.hhmm(it.rise, zone)}   🌇 sunset ${Weather.hhmm(it.set, zone)}", 13f, DIM, top = 8) }
        }
        // 4. Sources
        run {
            val c = card()
            label(c, "What each source thinks")
            val data = RunWindows.data(this)
            if (w == null || data == null) text(c, "No window to compare.", 13f, DIM, top = 4)
            else {
                val wt = listOf(1.2f, 0.7f, 0.9f, 0.7f, 1.4f, 0.9f)
                cells(c, listOf("Source", "°C", "Cloud", "UV", "Rain peak", "Right"), wt, header = true)
                val acc = Scoring.cloudAccBySource(this)
                for (id in Weather.ORDER) {
                    val hs = data.src[id]?.filter { it.t < w.end && it.t + 3600 > w.start } ?: continue
                    if (hs.isEmpty()) continue
                    fun avg(f: (Weather.Hour) -> Double?) = hs.mapNotNull(f).takeIf { it.isNotEmpty() }?.average()
                    val rain = hs.mapNotNull { it.rainMm }.maxOrNull()
                    val pct = hs.mapNotNull { it.rainPct }.maxOrNull()
                    val dissent = w.dissent.any { it.first == id }
                    cells(c, listOf(
                        Weather.NAMES[id] ?: id,
                        avg { it.temp }?.let { "${it.roundToInt()}" } ?: "–",
                        avg { it.cloud }?.let { "${it.roundToInt()}%" } ?: "–",
                        hs.mapNotNull { it.uv }.maxOrNull()?.let { String.format(Locale.US, "%.1f", it) } ?: "–",
                        if (rain == null) "–" else String.format(Locale.US, "%.1f", rain) + (pct?.let { "·${it.roundToInt()}%" } ?: ""),
                        acc[id]?.takeIf { it.total >= 3 }?.pct?.let { "$it%" } ?: "–",
                    ), wt, color = if (dissent) WARN else TEXT)
                }
                text(c, "Yellow = disagrees with the median on cloud or rain somewhere in the window. Rain is mm per hour · chance. Right = how often that source's cloudy-or-not call matched what happened (last 30 days; needs a few of your outside-now taps or Rainbow checks). NWS gives cloud only. UV shows each source's own value; the plan uses the highest.", 12f, FAINT, top = 6)
                val fetched = data.fetched.entries.joinToString("  ·  ") { "${it.key} ${Weather.hhmm(it.value / 1000, zone)}" }
                text(c, "Fetched: $fetched", 12f, FAINT, top = 4)
            }
        }
        // 5. Buttons
        run {
            val c = card()
            status?.let { text(c, it, 13f, if (it.startsWith("Couldn't")) WARN else GOOD) }
            button(c, "Refresh now") { refreshNow() }
            button(c, "Open in Rainbow") { openRainbow() }
            text(c, "The forecast refreshes by itself every 3 hours (hourly on a run day until the window starts), and not between 22:00 and 05:00.", 12f, FAINT, top = 10)
        }
        // 6. Quiet manual option: only for a planned run still to do (today or later)
        run {
            val w = Weather.plannedWorkout(RunWindows.weekRuns(this), date.toString())
            if (w != null && w.eventId != null && !date.isBefore(LocalDate.now()) && !plan.run.done) {
                text(col, "Can't make this one ›", 12.5f, FAINT, top = 4).apply {
                    setPadding(dp(6), dp(10), dp(6), dp(10))
                    setOnClickListener { SwapActivity.open(this@RunWindowActivity, date, w.eventId, Swaps.Trigger.MANUAL) }
                }
            }
        }
    }

    /** Launches the Rainbow app when one of its known package names is installed, otherwise its website. */
    private fun openRainbow() {
        val pm = packageManager
        val app = listOf("ai.rainbow.android", "ai.rainbow.weather", "app.rainbow.weather").firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
        runCatching {
            if (app != null) startActivity(app) else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://rainbow.ai")))
        }
    }
}
