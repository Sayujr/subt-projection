package dev.subt.trainingwidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.RemoteViews
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import dev.subt.trainingwidget.WidgetRenderer as R0

/** A date range shown on the app's Week screen (week, month, 3 / 6 months or custom). */
data class WeekRange(
    val start: LocalDate,
    val end: LocalDate,
    val items: List<Workout>,
    val title: String,
    val loading: Boolean,
    /** Last week's completed load, shown only for the current week. */
    val lastWeekLoad: Int? = null,
)

/** This week, Race countdown, Recovery and SubT pace: as widgets and as in-app screens. */
object ExtraWidgets {

    private const val HRV = 0xFF2DD4BF.toInt()
    private const val RHR = 0xFFFF7A90.toInt()
    private const val SLEEP = 0xFF8C9EFF.toInt()
    private const val AMBER = 0xFFFFC53D.toInt()

    fun renderAll(ctx: Context, mgr: AppWidgetManager) {
        fun ids(c: Class<*>) = mgr.getAppWidgetIds(ComponentName(ctx, c))
        ids(WeekWidgetProvider::class.java).forEach { renderWeek(ctx, mgr, it) }
        ids(RaceWidgetProvider::class.java).forEach { renderRace(ctx, mgr, it) }
        ids(RecoveryWidgetProvider::class.java).forEach { renderRecovery(ctx, mgr, it) }
        ids(SubtWidgetProvider::class.java).forEach { renderSubt(ctx, mgr, it) }
    }

    fun renderWeek(ctx: Context, mgr: AppWidgetManager, id: Int) {
        val (w, h) = R0.sizeDp(mgr, id, 300, 150); mgr.updateAppWidget(id, buildWeek(ctx, w, h))
    }

    fun renderRace(ctx: Context, mgr: AppWidgetManager, id: Int) {
        val (w, h) = R0.sizeDp(mgr, id, 150, 150); mgr.updateAppWidget(id, buildRace(ctx, w, h))
    }

    fun renderRecovery(ctx: Context, mgr: AppWidgetManager, id: Int) {
        val (w, h) = R0.sizeDp(mgr, id, 300, 150); mgr.updateAppWidget(id, buildRecovery(ctx, id, w, h))
    }

    fun renderSubt(ctx: Context, mgr: AppWidgetManager, id: Int) {
        val (w, h) = R0.sizeDp(mgr, id, 150, 150); mgr.updateAppWidget(id, buildSubt(ctx, w, h))
    }

    private fun px(ctx: Context, dp: Int) = (dp.coerceAtLeast(20) * ctx.resources.displayMetrics.density).roundToInt()

    private fun spans(vararg parts: Pair<String, Int?>): CharSequence {
        val sb = SpannableStringBuilder()
        for ((text, color) in parts) {
            val start = sb.length
            sb.append(text)
            if (color != null) sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun dayName(d: LocalDate) = d.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault()))
    private fun dateLabel(d: LocalDate) = d.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))

    private fun header(
        ctx: Context, v: RemoteViews, s: R0.State, title: String, provider: Class<*>, url: String,
        tab: String, inApp: Boolean, needsLogin: Boolean = true,
    ) {
        v.setTextViewText(R.id.title, title)
        v.setTextViewText(R.id.updated, R0.updatedText(ctx, s))
        v.setOnClickPendingIntent(R.id.refresh, R0.refreshIntent(ctx, provider))
        v.setOnClickPendingIntent(
            R.id.root,
            when {
                needsLogin && s.creds == null -> R0.setupIntent(ctx)
                inApp -> null                                    // inside the app: taps stay in the app
                else -> R0.appIntent(ctx, tab)                   // on the home screen: open this screen in the app
            }
        )
    }

    // ---------------------------------------------------------------- This week

    fun buildWeek(ctx: Context, wDp: Int, hDp: Int, inApp: Boolean = false, range: WeekRange? = null): RemoteViews {
        val s = R0.State(ctx)
        val v = RemoteViews(ctx.packageName, R.layout.widget_week)
        val today = s.today
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val sunday = monday.plusDays(6)
        val span = if (monday.month == sunday.month) {
            "${monday.dayOfMonth}–${sunday.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))}"
        } else {
            "${monday.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))} – " +
                sunday.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
        }
        header(ctx, v, s, "THIS WEEK · ${span.uppercase(Locale.getDefault())}", WeekWidgetProvider::class.java, R0.CALENDAR_URL, "WEEK", inApp)

        if (range != null) return buildRange(ctx, v, s, range, wDp, hDp)

        val snap = s.snap
        // Only trust a snapshot from this same week
        val sameWeek = snap != null && runCatching {
            !LocalDate.parse(snap.today).isBefore(monday)
        }.getOrDefault(false)
        if (s.creds == null || snap == null || !sameWeek) {
            v.setTextViewText(R.id.week_summary, if (s.creds == null) "Tap to connect intervals.icu" else "Loading…")
            return v
        }
        val week = snap.week
        val done = week.filter { it.status == WorkoutStatus.DONE }.sumOf { it.load }
        val todo = week.filter { it.status != WorkoutStatus.DONE && it.date >= today.toString() }.sumOf { it.load }
        v.setTextViewText(
            R.id.week_summary,
            spans(
                "Done " to R0.DIM, "$done" to R0.UP, "   To do " to R0.DIM, "$todo" to AMBER,
                "   Last week " to R0.DIM, "${snap.lastWeekLoad}" to R0.TEXT,
            )
        )

        // Upcoming sessions list when there's room
        val upcoming = week.filter { it.date >= today.toString() && it.status != WorkoutStatus.DONE }
        val rowsFit = ((hDp - 24 - 28 - 18 - 6 - 100 - 6) / 22).coerceAtLeast(0)
        val shown = if (upcoming.size > rowsFit) upcoming.take((rowsFit - 1).coerceAtLeast(0)) else upcoming
        v.removeAllViews(R.id.week_list)
        if (rowsFit > 0) {
            shown.forEach { wk ->
                val d = runCatching { LocalDate.parse(wk.date) }.getOrNull()
                val prefix = when (d) {
                    today -> "Today · "
                    null -> ""
                    else -> "${dayName(d)} · "
                }
                v.addView(R.id.week_list, R0.row(ctx, wk, prefix))
            }
            if (shown.size < upcoming.size && rowsFit > 0) {
                v.addView(R.id.week_list, R0.message(ctx, "+${upcoming.size - shown.size} more this week"))
            }
        }
        val listRows = if (rowsFit > 0) minOf(upcoming.size, rowsFit) else 0
        val chartH = hDp - 24 - 28 - 18 - 6 - listRows * 22 - (if (listRows > 0) 6 else 0)
        v.setImageViewBitmap(
            R.id.week_chart,
            Charts.weekBars(week, monday, today, px(ctx, wDp - 24), px(ctx, chartH), ctx.resources.displayMetrics.density, Prefs.planTolerance(ctx))
        )
        return v
    }

    /** Week screen in the app: bars and totals for any range; the session list is drawn by the app below. */
    private fun buildRange(ctx: Context, v: RemoteViews, s: R0.State, r: WeekRange, wDp: Int, hDp: Int): RemoteViews {
        v.setTextViewText(R.id.title, r.title)
        v.removeAllViews(R.id.week_list)
        val t = s.today.toString()
        val done = r.items.filter { it.status == WorkoutStatus.DONE }.sumOf { it.load }
        // Plan for days already past: planned load of what you did + load of what you skipped
        val planPast = r.items.filter { it.date < t }.sumOf { if (it.status == WorkoutStatus.DONE) it.plannedLoad else it.load }
        val donePast = r.items.filter { it.date < t && it.status == WorkoutStatus.DONE }.sumOf { it.load }
        val todo = r.items.filter { it.status != WorkoutStatus.DONE && it.date >= t }.sumOf { it.load }
        val parts = mutableListOf<Pair<String, Int?>>()
        if (done > 0 || r.start.toString() <= t) parts += listOf("Done " to R0.DIM, "$done" to R0.UP)
        if (todo > 0 || r.end.toString() >= t) parts += listOf("   To do " to R0.DIM, "$todo" to AMBER)
        if (planPast > 0) parts += listOf("   Planned " to R0.DIM, "$planPast" to R0.TEXT, " (${(donePast * 100.0 / planPast).roundToInt()}%)" to R0.DIM)
        if (r.lastWeekLoad != null) parts += listOf("   Last week " to R0.DIM, "${r.lastWeekLoad}" to R0.TEXT)
        val days = ChronoUnit.DAYS.between(r.start, r.end) + 1
        if (days > 10) parts += listOf("   ${((done + todo) * 7.0 / days).roundToInt()}/wk" to R0.DIM)
        v.setTextViewText(R.id.week_summary, if (r.loading) "Loading from intervals.icu…" else spans(*parts.toTypedArray()))
        val chartH = hDp - 24 - 28 - 18 - 6
        v.setImageViewBitmap(
            R.id.week_chart,
            Charts.rangeBars(r.items, r.start, r.end, s.today, px(ctx, wDp - 24), px(ctx, chartH), ctx.resources.displayMetrics.density, Prefs.planTolerance(ctx))
        )
        return v
    }

    // ---------------------------------------------------------------- Race countdown

    fun buildRace(ctx: Context, wDp: Int, hDp: Int, inApp: Boolean = false): RemoteViews {
        val s = R0.State(ctx)
        val v = RemoteViews(ctx.packageName, R.layout.widget_race)
        header(ctx, v, s, "NEXT RACE", RaceWidgetProvider::class.java, R0.CALENDAR_URL, "RACE", inApp)
        v.setTextViewText(R.id.updated, "")

        if (s.creds == null) {
            v.setTextViewText(R.id.race_name, "Tap to connect intervals.icu")
            return v
        }
        val today = s.today
        val races = s.snap?.races.orEmpty().filter { it.date >= today.toString() }
        val next = races.firstOrNull()
        if (next == null) {
            v.setTextViewText(R.id.race_name, if (s.snap == null) "Loading…" else "No race planned")
            v.setTextViewText(
                R.id.race_date,
                if (s.snap == null) "" else "Add a race (A, B or C) to your intervals.icu calendar"
            )
            return v
        }
        val d = LocalDate.parse(next.date)
        val n = ChronoUnit.DAYS.between(today, d)
        v.setTextViewText(R.id.race_name, next.name)
        when (n) {
            0L -> { v.setTextViewText(R.id.race_days, "Today"); v.setTextViewText(R.id.race_days_unit, "good luck!") }
            1L -> { v.setTextViewText(R.id.race_days, "1"); v.setTextViewText(R.id.race_days_unit, "day") }
            else -> {
                v.setTextViewText(R.id.race_days, "$n")
                v.setTextViewText(R.id.race_days_unit, if (n >= 14) "days · ${n / 7} wk" else "days")
            }
        }
        val prio = next.category.removePrefix("RACE_").takeIf { it.length == 1 }?.let { " · $it race" } ?: ""
        val sport = next.type.takeIf { it.isNotBlank() && wDp >= 250 }?.let { " · $it" } ?: ""
        v.setTextViewText(R.id.race_date, dateLabel(d) + sport + prio)
        val narrow = wDp < 250

        val pf = next.projForm
        if (pf != null && next.projCtl != null) {
            val z = FormZone.of(pf)
            v.setTextViewText(
                R.id.race_proj,
                spans(
                    (if (narrow) "Race day " else "Projected race day ") to R0.DIM,
                    "Fit ${next.projCtl.roundToInt()}" to Charts.FITNESS_COLOR,
                    " · " to R0.DIM,
                    "Fat ${next.projAtl?.roundToInt() ?: "–"}" to FormColors.FATIGUE,
                    " · " to R0.DIM,
                    (if (narrow) "Form ${signed(pf.roundToInt())}" else "Form ${signed(pf.roundToInt())} ${z.label}") to z.color,
                )
            )
        } else if (n > 84) {
            v.setTextViewText(R.id.race_proj, "Race-day projection appears 12 weeks out")
        }

        // Later races when tall
        v.removeAllViews(R.id.race_list)
        val rowsFit = ((hDp - 24 - 20 - 40 - 46 - 18 - 36 - 8) / 22).coerceAtLeast(0)
        races.drop(1).take(rowsFit).forEach { r ->
            val rd = LocalDate.parse(r.date)
            val days = ChronoUnit.DAYS.between(today, rd)
            v.addView(R.id.race_list, R0.message(ctx, "${dateLabel(rd)} · ${r.name} · ${days}d"))
        }
        return v
    }

    private fun signed(v: Int) = if (v > 0) "+$v" else "$v"

    // ---------------------------------------------------------------- Recovery

    fun buildRecovery(ctx: Context, id: Int, wDp: Int, hDp: Int, inApp: Boolean = false): RemoteViews {
        val s = R0.State(ctx)
        val v = RemoteViews(ctx.packageName, R.layout.widget_recovery)
        val period = Prefs.period(ctx, id)
        header(ctx, v, s, "RECOVERY · ${Periods.label(period).uppercase(Locale.getDefault())}", RecoveryWidgetProvider::class.java, "https://intervals.icu/", "RECOVERY", inApp)

        val days = s.snap?.days.orEmpty().takeLast(period)
        val hrv = days.mapNotNull { it.hrv }
        val rhr = days.mapNotNull { it.rhr }
        val sleep = days.mapNotNull { it.sleepSecs?.div(60)?.takeIf { m -> m <= 14 * 60 } } // minutes; >14 h = tracking error
        val note = when {
            s.creds == null -> "Tap to connect intervals.icu"
            s.snap == null -> "Loading…"
            hrv.isEmpty() && rhr.isEmpty() && sleep.isEmpty() ->
                "No HRV, resting HR or sleep in intervals.icu yet. They appear here once your watch or wellness app syncs them."
            else -> null
        }
        if (note != null) {
            v.setTextViewText(R.id.rec_note, note)
            v.setViewVisibility(R.id.rec_tiles, View.GONE)
            return v
        }
        v.setViewVisibility(R.id.rec_note, View.GONE)

        val d = ctx.resources.displayMetrics.density
        val cw = px(ctx, (wDp - 24 - 12) / 3 - 18)
        val ch = px(ctx, hDp - 129)
        val baseLabel = Periods.label(period).replace(" weeks", "w").replace(" months", "mo").replace(" year", "y")

        fun avg7(list: List<Double>) = list.takeLast(7).average()

        // HRV: higher than usual is good
        if (hrv.isNotEmpty()) {
            v.setTextViewText(R.id.hrv_value, "${hrv.last().roundToInt()} ms")
            val delta = avg7(hrv) - hrv.average()
            v.setTextViewText(R.id.hrv_delta, "7d ${arrow(delta)} vs $baseLabel")
            v.setTextColor(R.id.hrv_delta, if (delta >= 0) R0.UP else R0.DOWN)
            v.setImageViewBitmap(R.id.hrv_chart, Charts.line(hrv, HRV, cw, ch, d))
        }
        // Resting HR: lower than usual is good
        if (rhr.isNotEmpty()) {
            v.setTextViewText(R.id.rhr_value, "${rhr.last().roundToInt()} bpm")
            val delta = avg7(rhr) - rhr.average()
            v.setTextViewText(R.id.rhr_delta, "7d ${arrow(delta)} vs $baseLabel")
            v.setTextColor(R.id.rhr_delta, if (delta <= 0) R0.UP else R0.DOWN)
            v.setImageViewBitmap(R.id.rhr_chart, Charts.line(rhr, RHR, cw, ch, d))
        }
        // Sleep: last night, coloured by the 7-day average
        if (sleep.isNotEmpty()) {
            v.setTextViewText(R.id.sleep_value, hm(sleep.last()))
            val a = avg7(sleep)
            v.setTextViewText(R.id.sleep_delta, "7d avg ${hm(a)}")
            v.setTextColor(R.id.sleep_delta, when { a >= 420 -> R0.UP; a < 360 -> R0.DOWN; else -> AMBER })
            v.setImageViewBitmap(R.id.sleep_chart, Charts.line(sleep, SLEEP, cw, ch, d))
        }
        return v
    }

    private fun arrow(delta: Double): String {
        val r = (delta * 10).roundToInt() / 10.0
        return when {
            r > 0 -> "▲${R0.fmt1(r)}"
            r < 0 -> "▼${R0.fmt1(abs(r))}"
            else -> "＝"
        }
    }

    private fun hm(minutes: Double): String {
        val m = minutes.roundToInt()
        return "${m / 60}h${"%02d".format(m % 60)}"
    }

    // ---------------------------------------------------------------- SubT pace

    fun buildSubt(ctx: Context, wDp: Int, hDp: Int, inApp: Boolean = false): RemoteViews {
        val s = R0.State(ctx)
        val v = RemoteViews(ctx.packageName, R.layout.widget_subt)
        val data = Prefs.subt(ctx)
        header(ctx, v, s, "SUBT PACE", SubtWidgetProvider::class.java, SubtApi.PAGE_URL, "SUBT", inApp, needsLogin = false)
        v.setTextViewText(R.id.updated, data?.let { R0.time(ctx, it.fetchedAt) } ?: "")

        val months = data?.months.orEmpty()
        if (months.isEmpty()) {
            v.setTextViewText(R.id.subt_sub, if (data == null) "Loading SubT data…" else "No SubT laps yet")
            return v
        }
        val last = months.last()
        val prev = months.dropLast(1).lastOrNull { !it.outlier }
        v.setTextViewText(R.id.subt_value, Charts.paceText(last.pace))
        val monthName = runCatching {
            LocalDate.parse(last.month + "-01").format(DateTimeFormatter.ofPattern("MMM", Locale.getDefault()))
        }.getOrDefault(last.month)
        val narrow = wDp < 250
        val parts = mutableListOf<Pair<String, Int?>>(
            (if (narrow) "$monthName · ${last.laps} laps" else "$monthName median · ${last.laps} laps") to R0.DIM
        )
        if (prev != null) {
            val diff = ((last.pace - prev.pace) * 60).roundToInt()
            val prevName = runCatching {
                LocalDate.parse(prev.month + "-01").format(DateTimeFormatter.ofPattern("MMM", Locale.getDefault()))
            }.getOrDefault(prev.month)
            parts += " · " to R0.DIM
            parts += when {
                narrow && diff < 0 -> "▲${-diff}s" to R0.UP
                narrow && diff > 0 -> "▼${diff}s" to R0.DOWN
                narrow -> "＝" to R0.DIM
                diff < 0 -> "${-diff}s faster than $prevName" to R0.UP
                diff > 0 -> "${diff}s slower than $prevName" to R0.DOWN
                else -> "same as $prevName" to R0.DIM
            }
        }
        v.setTextViewText(R.id.subt_sub, spans(*parts.toTypedArray()))

        val shown = months.takeLast(if (wDp >= 250) 18 else 12)
        val chartH = hDp - 24 - 20 - 36 - 16 - 6 - 16
        v.setImageViewBitmap(
            R.id.subt_chart,
            Charts.subtPace(shown, px(ctx, wDp - 24), px(ctx, chartH), ctx.resources.displayMetrics.density, R0.WIDGET_BG, labels = chartH >= 90)
        )
        val slope = Charts.subtTrendPerMonth(months)
        v.setTextViewText(
            R.id.subt_trend,
            when {
                slope == null -> "${data!!.lapsLast30d} laps in the last 30 days"
                else -> {
                    val sec = (slope * 60).roundToInt()
                    val trend = if (sec < 0) "${-sec}s/km faster" else if (sec > 0) "${sec}s/km slower" else "flat"
                    if (narrow) "Trend $trend /mo" else "Trend: $trend per month · ${data!!.lapsLast30d} laps in 30 days"
                }
            }
        )
        return v
    }
}
