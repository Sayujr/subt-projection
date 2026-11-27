package dev.subt.trainingwidget

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Zones calendar: a Monday-first month grid. Each day shows a small stacked bar of the time its runs spent in the
 * heart-rate or power bands (chips at the top), the day's minutes, and a weekly total (minutes and bar) on the right.
 * Tap a day for its band rows and runs. The data is the cached [Zones.Run] list; months it doesn't cover are
 * fetched in the background (one intervals.icu list call per month, plus the streams of runs not cached yet).
 */
class ZonesCalendarActivity : Activity() {
    companion object {
        const val EXTRA_POWER = "power"
        const val EXTRA_MONTH = "month"
        fun open(ctx: Context, power: Boolean) =
            ctx.startActivity(Intent(ctx, ZonesCalendarActivity::class.java).putExtra(EXTRA_POWER, power))

        private const val TEXT = 0xFFF2F4F8.toInt()
        private const val DIM = 0xFF9AA3B2.toInt()
        private const val FAINT = 0xFF6B7386.toInt()
        private const val WARN = 0xFFFFC53D.toInt()
        private const val LINK = 0xFF4EA1FF.toInt()
        private const val CELL = 0xFF1B1F28.toInt()
        private const val CELL_TODAY = 0xFF2B3342.toInt()
        private const val PANEL = 0xFF222733.toInt()
    }

    private lateinit var col: LinearLayout
    private var power = false
    private var month: YearMonth = YearMonth.now()
    private var loading = false
    private var failed: String? = null
    private val loaded = mutableSetOf<String>()
    private var dialog: AlertDialog? = null

    private val d get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        power = savedInstanceState?.getBoolean(EXTRA_POWER) ?: intent?.getBooleanExtra(EXTRA_POWER, false) ?: false
        val m = savedInstanceState?.getString(EXTRA_MONTH) ?: intent?.getStringExtra(EXTRA_MONTH)
        if (m != null) runCatching { month = YearMonth.parse(m) }
        val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false; fitsSystemWindows = true }
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(24)) }
        sv.addView(col)
        setContentView(sv)
        draw()
        loadMonth()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(EXTRA_POWER, power); outState.putString(EXTRA_MONTH, month.toString())
    }

    override fun onDestroy() {
        dialog?.dismiss()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- screen

    private fun tv(parent: LinearLayout?, t: CharSequence, sp: Float, color: Int, bold: Boolean = false, gravity: Int = Gravity.START): TextView =
        TextView(this).apply {
            text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color); this.gravity = gravity
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            parent?.addView(this)
        }

    private fun chip(label: String, selected: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(if (selected) TEXT else DIM)
        setBackgroundResource(if (selected) R.drawable.chip_on else R.drawable.chip_off)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
        setOnClickListener { onClick() }
    }

    private fun cols() = if (power) BandViews.POWER_COLS else BandViews.HR_COLS

    private fun draw() {
        col.removeAllViews()
        val today = LocalDate.now()
        // header
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(8)) }
        head.addView(tv(null, "‹", 28f, TEXT).apply { setPadding(dp(4), 0, dp(14), 0); contentDescription = "Back"; setOnClickListener { finish() } })
        head.addView(tv(null, "Zones calendar", 19f, TEXT, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(head)
        // mode chips
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(8)) }
        chips.addView(chip("Heart rate", !power) { if (power) { power = false; draw() } })
        chips.addView(chip("Power", power) { if (!power) { power = true; draw() } })
        col.addView(chips)
        // month navigation
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(6)) }
        nav.addView(tv(null, "‹", 26f, TEXT).apply { setPadding(dp(8), 0, dp(16), 0); contentDescription = "Previous month"; setOnClickListener { go(month.minusMonths(1)) } })
        val title = month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
        nav.addView(tv(null, title, 17f, TEXT, bold = true, gravity = Gravity.CENTER), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nav.addView(tv(null, "›", 26f, TEXT).apply { setPadding(dp(16), 0, dp(8), 0); contentDescription = "Next month"; setOnClickListener { go(month.plusMonths(1)) } })
        col.addView(nav)

        val runs = Prefs.zones(this)
        val daily = Zones.daily(runs, power)
        val withRuns = runs.groupBy { it.date }
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.tile_bg); setPadding(dp(8), dp(10), dp(8), dp(10)) }
        col.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fun weights(row: LinearLayout, v: View, w: Float) = row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w))
        val hdr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (dow in DayOfWeek.values()) weights(hdr, tv(null, dow.getDisplayName(TextStyle.SHORT, Locale.getDefault()), 11f, FAINT, gravity = Gravity.CENTER), 1f)
        weights(hdr, tv(null, "Week", 11f, FAINT, gravity = Gravity.CENTER), 1.3f)
        card.addView(hdr)

        val first = month.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val last = month.atEndOfMonth().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        var monday = first
        var monthSec = 0.0
        while (!monday.isAfter(last)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in 0 until 7) {
                val day = monday.plusDays(k.toLong())
                weights(row, dayCell(day, today, daily[day.toString()], withRuns[day.toString()].orEmpty()), 1f)
                if (YearMonth.from(day) == month) monthSec += daily[day.toString()]?.sum() ?: 0.0
            }
            val wk = Zones.sumDays(daily, monday, monday.plusDays(6), power)
            weights(row, weekCell(monday, wk), 1.3f)
            card.addView(row)
            monday = monday.plusDays(7)
        }

        // legend and status
        val names = if (power) Zones.PW_NAMES.mapIndexed { i, n -> n to cols()[i] } else listOf("Easy <135" to cols()[0], "In between" to cols()[1], "SubT band 155–172" to cols()[2], "Too hard >172" to cols()[3])
        val sb = SpannableStringBuilder()
        for ((n, c) in names) { val s = sb.length; sb.append("● "); sb.setSpan(ForegroundColorSpan(c), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); sb.append("$n   ") }
        tv(col, sb, 11.5f, DIM).setPadding(dp(4), dp(10), dp(4), 0)
        tv(col, (if (power) "Time in % of CP (that day's CP)" else "Time in heart-rate bands") + " · month total ${minutes(monthSec)} · tap a day for the rows and runs",
            12f, FAINT).setPadding(dp(4), dp(4), dp(4), 0)
        status()
    }

    private fun minutes(sec: Double): String {
        val m = (sec / 60).roundToInt()
        return if (m >= 60) "${m / 60}h${"%02d".format(m % 60)}" else "$m min"
    }

    private fun dayCell(day: LocalDate, today: LocalDate, parts: List<Double>?, runs: List<Zones.Run>): View {
        val inMonth = YearMonth.from(day) == month
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(if (day == today) CELL_TODAY else CELL); cornerRadius = dp(6).toFloat() }
            setPadding(dp(3), dp(3), dp(3), dp(3))
            minimumHeight = dp(58)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) }
            tag = "day:$day"
        }
        cell.addView(tv(null, "${day.dayOfMonth}", 11f, if (day == today) TEXT else if (inMonth) DIM else 0xFF4A5160.toInt(), bold = day == today))
        val total = parts?.sum() ?: 0.0
        if (total > 0) {
            cell.addView(ImageView(this).apply { setImageBitmap(BandViews.strip(this@ZonesCalendarActivity, parts!!, cols(), 120)); scaleType = ImageView.ScaleType.FIT_XY },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(6) })
            cell.addView(tv(null, "${(total / 60).roundToInt()}′", 10.5f, if (inMonth) TEXT else DIM, gravity = Gravity.CENTER), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
            cell.contentDescription = "$day, ${(total / 60).roundToInt()} min"
        }
        if (runs.isNotEmpty()) cell.setOnClickListener { showDay(day.toString()) }
        return cell
    }

    private fun weekCell(monday: LocalDate, parts: List<Double>): View {
        val total = parts.sum()
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            minimumHeight = dp(58)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) }
            tag = "week:$monday"
        }
        if (total > 0) {
            cell.addView(tv(null, "${(total / 60).roundToInt()}′", 13f, TEXT, bold = true, gravity = Gravity.CENTER))
            cell.addView(ImageView(this).apply { setImageBitmap(BandViews.strip(this@ZonesCalendarActivity, parts, cols(), 120)); scaleType = ImageView.ScaleType.FIT_XY },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(6) })
            cell.contentDescription = "Week of $monday, ${(total / 60).roundToInt()} min"
        }
        return cell
    }

    private fun status() {
        val msg = when {
            loading -> "Loading…"
            failed != null -> "Couldn't load more history ($failed) · tap to retry"
            else -> return
        }
        tv(col, msg, 12.5f, if (failed != null && !loading) WARN else DIM).apply {
            setPadding(dp(4), dp(8), dp(4), 0); tag = "status"
            if (!loading) setOnClickListener { failed = null; loaded.clear(); loadMonth(); draw() }
        }
    }

    private fun go(m: YearMonth) {
        month = m
        draw()
        loadMonth()
    }

    // ---------------------------------------------------------------- background load

    private fun gridRange(): Pair<LocalDate, LocalDate> =
        month.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) to month.atEndOfMonth().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))

    /** Fetches the runs of the shown weeks that aren't cached (complete) yet; once per range per visit. Never blocks the UI. */
    private fun loadMonth() {
        val creds = Prefs.credentials(this) ?: return
        val (from, to0) = gridRange()
        val to = minOf(to0, LocalDate.now())
        if (to < from) return
        val lt1 = Prefs.lt1(this).toDouble()
        val key = "$from..$to:${lt1.toInt()}"
        if (key in loaded || loading) return
        val have = Prefs.zones(this).filter { it.complete }.map { it.id }.toSet()
        val ftp = Prefs.cpOverride(this) ?: Prefs.snapshot(this)?.runFtp
        loading = true; failed = null
        draw()
        val shown = month
        Thread {
            val r = runCatching { IcuApi(creds).fetchZones(from, to, lt1, ftp, have) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                loading = false
                r.onSuccess { got ->
                    loaded += key
                    if (got.isNotEmpty()) {
                        val byId = Prefs.zones(this).associateBy { it.id }.toMutableMap()
                        got.forEach { byId[it.id] = it }
                        Prefs.saveZones(this, byId.values.sortedBy { it.date })
                    }
                }.onFailure { failed = it.message ?: it.javaClass.simpleName }
                draw()
                // the person moved on while this was loading: load the month now shown
                if (month != shown) loadMonth()
            }
        }.start()
    }

    // ---------------------------------------------------------------- day sheet

    internal fun dayContent(date: String, onRun: (Zones.Run) -> Unit): View {
        val runs = Prefs.zones(this).filter { it.date == date }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(14)) }
        val title = runCatching { LocalDate.parse(date).format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())) }.getOrDefault(date)
        tv(box, title, 17f, TEXT, bold = true)
        tv(box, if (power) "Power bands" else "Heart rate bands", 12f, FAINT).setPadding(0, dp(2), 0, 0)
        val w = resources.displayMetrics.widthPixels - dp(36 + 24)
        if (power) {
            val sec = List(5) { k -> runs.sumOf { it.pwSec[k] } }; val m = List(5) { k -> runs.sumOf { it.pwM[k] } }
            if (sec.sum() > 0) box.addView(BandViews.buildPower(this, sec, m, w, decimals = 2))
            else tv(box, "No running power for this day.", 13f, DIM).setPadding(0, dp(8), 0, 0)
        } else {
            val sec = List(7) { k -> runs.sumOf { it.bandSec[k] } }; val m = List(7) { k -> runs.sumOf { it.bandM[k] } }
            if (sec.sum() > 0) box.addView(BandViews.build(this, sec, m, w, decimals = 2))
            else tv(box, "No heart rate for this day.", 13f, DIM).setPadding(0, dp(8), 0, 0)
        }
        tv(box, "RUNS", 10.5f, FAINT).apply { letterSpacing = 0.08f; setPadding(0, dp(14), 0, dp(2)) }
        for (r in runs) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)); tag = "run:${r.id}" }
            line.addView(tv(null, r.name.ifBlank { "Run" }, 14.5f, TEXT).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(tv(null, SubtModel.time(r.seconds.toDouble()), 13f, DIM, gravity = Gravity.END).apply { setPadding(dp(8), 0, 0, 0) })
            line.addView(tv(null, String.format(Locale.US, "%.1f km", r.distance / 1000), 13f, DIM, gravity = Gravity.END).apply { setPadding(dp(10), 0, 0, 0) })
            line.addView(tv(null, "›", 16f, LINK).apply { setPadding(dp(10), 0, 0, 0) })
            line.setOnClickListener { onRun(r) }
            box.addView(line)
        }
        return box
    }

    private fun showDay(date: String) {
        dialog?.dismiss()
        lateinit var dlg: AlertDialog
        val v = dayContent(date) { r -> dlg.dismiss(); WorkoutActivity.open(this, r.id, r.name) }
        dlg = AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(v) }).create()
        dlg.window?.setBackgroundDrawable(GradientDrawable().apply { setColor(PANEL); cornerRadius = dp(16).toFloat() })
        dlg.show()
        dialog = dlg
    }
}
