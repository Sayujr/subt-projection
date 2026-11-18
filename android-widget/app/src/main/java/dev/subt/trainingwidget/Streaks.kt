package dev.subt.trainingwidget

import android.content.Context
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Load-balance streaks, judged on weekly (Mon–Sun) and monthly averages plus how you exit them.
 *
 * Week passes when, over its normal days:
 *   • average Form is in the Grey zone (−10…+5)
 *   • average Fatigue ≥ fatMin × average Fitness
 *   • at exit (Sunday, or today for the current week) Fitness ≥ (1 − band) × reference peak and Form in the Grey zone
 * Day streak (relaxed, allows rest / recovery days): Fitness ≥ floor and 3-day average Form −15…+8
 *   (TT mini-taper / taper / race days pass when the floor holds).
 * Excluded from the averages (Fitness floor still applies):
 *   • TT mini-tapers: 2 days before a scheduled TT, the TT day and the day after
 *   • race tapers (A 14 · B 10 · C 5 days before), race day and the 3 days after
 * A week that's mostly taper / race is judged only on keeping Fitness above the race floor (0.88 × reference).
 *
 * Month passes when: average Form −12…+5, exit Fitness ≥ 97 % of entry (90 % with a race), ≤ 1 failed week.
 */
object Streaks {
    enum class Status { GREEN, AMBER, RED, TAPER_OK, TAPER_MISS, MINI, EXEMPT, NONE }

    data class DayResult(val date: LocalDate, val status: Status, val notes: List<String>, val day: Day? = null, val ref: Double? = null)
    data class WeekResult(
        val monday: LocalDate, val days: List<DayResult>, val passed: Boolean?, val green: Int, val complete: Boolean,
        val avgForm: Double?, val avgRatio: Double?, val exitCtl: Double?, val exitForm: Double?, val floor: Double?,
        val kind: String, val reasons: List<String>,
    )
    data class MonthResult(
        val month: LocalDate, val weeks: List<WeekResult>, val passed: Boolean?,
        val avgForm: Double?, val entryCtl: Double?, val exitCtl: Double?, val failedWeeks: Int, val hadRace: Boolean,
    )
    /** One streak: first and last passing period, its length, and what broke it (null while it's still going). */
    data class Run(val start: LocalDate, val end: LocalDate, val length: Int, val brokenOn: LocalDate?, val brokenBy: String?)

    data class Summary(
        val days: List<DayResult>, val weeks: List<WeekResult>, val months: List<MonthResult>,
        val weekStreak: Int, val monthStreak: Int, val bestWeekStreak: Int, val thisWeek: WeekResult?,
        val dayStreak: Int = 0, val bestDayStreak: Int = 0,
        val dayRuns: List<Run> = emptyList(), val weekRuns: List<Run> = emptyList(), val monthRuns: List<Run> = emptyList(),
    )

    /** Splits a sequence of pass / fail / skip periods into streak runs. */
    private fun <T> runs(items: List<T>, date: (T) -> LocalDate, endOf: (T) -> LocalDate, pass: (T) -> Boolean?, why: (T) -> String): List<Run> {
        val out = mutableListOf<Run>()
        var first: T? = null; var last: T? = null; var n = 0
        for (it in items) {
            when (pass(it)) {
                null -> continue
                true -> { if (first == null) first = it; last = it; n++ }
                false -> {
                    if (first != null) out += Run(date(first!!), endOf(last!!), n, date(it), why(it))
                    first = null; last = null; n = 0
                }
            }
        }
        if (first != null) out += Run(date(first!!), endOf(last!!), n, null, null)
        return out
    }

    private data class RaceRef(val date: LocalDate, val category: String, val name: String)
    /** Names that mark a TT / race / CP test: "race", "TT", "time trial", "parkrun", "CP" (but not "race pace"). */
    val TT = Regex("(\\bTT\\b|time ?trial|parkrun|\\bCP\\b|\\brace\\b(?!\\s*-?\\s*pace))", RegexOption.IGNORE_CASE)
    fun isTtName(name: String) = TT.containsMatchIn(name)

    /** [longSeries]: optional multi-year Fitness history (for the calendar view); defaults to the cached 12 months. */
    fun compute(ctx: Context, longSeries: List<Day>? = null): Summary? {
        val s = Prefs.snapshot(ctx) ?: return null
        val band = Prefs.fitnessBand(ctx) / 100.0
        val fatMin = Prefs.fatigueMin(ctx) / 100.0
        // Merge long history with the fresh snapshot (snapshot wins on overlapping days)
        val series = if (longSeries == null) s.days.sortedBy { it.date } else
            (longSeries.associateBy { it.date } + s.days.associateBy { it.date }).values.sortedBy { it.date }
        if (series.size < 30) return null
        val races = (Prefs.raceHistory(ctx)?.second.orEmpty().map { RaceRef(LocalDate.parse(it.date), it.category, it.name) } +
            s.races.map { RaceRef(LocalDate.parse(it.date), it.category, it.name) }).distinctBy { it.date }
        val raceDates = races.map { it.date }.toSet()
        // Scheduled / done time trials (not races) → mini-taper windows
        val ttDates = (s.week.filter { isTtName(it.name) }.map { it.date } + s.runs.filter { isTtName(it.name) }.map { it.date } +
            Prefs.ttDates(ctx)?.second.orEmpty())
            .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.filter { it !in raceDates }.toSet()

        val byDate = series.associateBy { it.date }
        val idx = series.withIndex().associate { it.value.date to it.index }
        // Peak Fitness over the last [days] days ending at d (walks back from d's index: fast on long histories)
        fun peak(d: LocalDate, days: Long): Double? {
            val i = idx[d.toString()] ?: return null
            val lo = d.minusDays(days).toString()
            var k = i; var m = Double.NEGATIVE_INFINITY
            while (k >= 0 && series[k].date > lo) { if (series[k].ctl > m) m = series[k].ctl; k-- }
            return m.takeIf { it.isFinite() }
        }

        val start = LocalDate.parse(series.first().date).plusDays(60)
        val today = LocalDate.now()
        val dayResults = mutableListOf<DayResult>()
        var d = start
        while (!d.isAfter(today)) {
            val day = byDate[d.toString()]
            if (day == null) { dayResults += DayResult(d, Status.NONE, emptyList()); d = d.plusDays(1); continue }
            val ref = Taper.reference(peak(d, 60), peak(d, 90), peak(d, 120)) ?: day.ctl
            val after = races.firstOrNull { !it.date.isAfter(d) && ChronoUnit.DAYS.between(it.date, d) <= 3 }
            val ahead = races.filter { !it.date.isBefore(d) }.minByOrNull { it.date }
            val taperLen = when (ahead?.category) { "RACE_A" -> 14L; "RACE_B" -> 10L; "RACE_C" -> 5L; else -> 0L }
            val inTaper = ahead != null && ChronoUnit.DAYS.between(d, ahead.date) in 1..taperLen
            val mini = ttDates.any { tt -> ChronoUnit.DAYS.between(d, tt) in -1L..2L }
            val floorOk = day.ctl >= (1 - band) * ref
            val notes = mutableListOf<String>()
            val status = when {
                after != null -> { notes += "Race / recovery (${after.name.take(24)})"; Status.EXEMPT }
                inTaper -> {
                    val ok = day.ctl >= 0.88 * ref
                    notes += "Taper for ${ahead!!.name.take(24)}"
                    if (!ok) notes += "Fitness below taper floor"
                    if (ok) Status.TAPER_OK else Status.TAPER_MISS
                }
                mini -> {
                    notes += "TT mini-taper"
                    if (!floorOk) { notes += "Fitness below floor"; Status.RED } else Status.MINI
                }
                else -> {
                    val fatOk = day.atl >= fatMin * day.ctl
                    val formOk = day.form in -10.0..5.0
                    // Day streak: 3-day average Form, so a single rest or recovery day doesn't break it
                    val f3 = (idx[d.toString()]!!).let { i -> (maxOf(0, i - 2)..i).map { series[it].form } }.average()
                    val dailyOk = floorOk && f3 in -15.0..8.0
                    if (!floorOk) notes += "Fitness below floor"
                    if (floorOk && !dailyOk) notes += "3-day Form ${f3.toInt()} outside −15…+8"
                    if (!fatOk) notes += "Fatigue low"
                    if (!formOk) notes += "Form outside Grey"
                    when { !dailyOk -> Status.RED; fatOk && formOk -> Status.GREEN; else -> Status.AMBER }
                }
            }
            dayResults += DayResult(d, status, notes, day, ref)
            d = d.plusDays(1)
        }

        // ---- Weeks
        val weeks = dayResults.groupBy { it.date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }.toSortedMap().map { (mon, ds) ->
            val complete = mon.plusDays(6).isBefore(today)
            val withData = ds.filter { it.day != null }
            val normal = withData.filter { it.status == Status.GREEN || it.status == Status.AMBER || it.status == Status.RED }
            val taperish = withData.count { it.status == Status.TAPER_OK || it.status == Status.TAPER_MISS || it.status == Status.EXEMPT }
            val exit = withData.lastOrNull()
            val reasons = mutableListOf<String>()
            val green = ds.count { it.status == Status.GREEN }
            if (exit == null) return@map WeekResult(mon, ds, null, green, complete, null, null, null, null, null, "no data", emptyList())
            val exitDay = exit.day!!; val ref = exit.ref ?: exitDay.ctl
            if (taperish >= 4) {
                val ok = withData.none { it.status == Status.TAPER_MISS }
                if (!ok) reasons += "Fitness dipped below the race floor"
                return@map WeekResult(mon, ds, ok, green, complete, null, null, exitDay.ctl, exitDay.form, 0.88 * ref, "taper / race week", reasons)
            }
            val floor = (1 - band) * ref
            val avgForm = normal.mapNotNull { it.day?.form }.takeIf { it.isNotEmpty() }?.average()
            val avgCtl = normal.mapNotNull { it.day?.ctl }.takeIf { it.isNotEmpty() }?.average()
            val avgAtl = normal.mapNotNull { it.day?.atl }.takeIf { it.isNotEmpty() }?.average()
            val ratio = if (avgCtl != null && avgAtl != null && avgCtl > 0) avgAtl / avgCtl else null
            val exitFloorOk = exitDay.ctl >= floor
            // Exit Form must be in Grey too, unless the week ends in a TT mini-taper / race window
            val exitNormal = exit.status == Status.GREEN || exit.status == Status.AMBER || exit.status == Status.RED
            val exitFormOk = !exitNormal || exitDay.form in -10.0..5.0
            if (!exitFloorOk) reasons += "Exited with Fitness ${exitDay.ctl.toInt()} below floor ${floor.toInt()}"
            if (!exitFormOk) reasons += "Exited with Form ${exitDay.form.toInt()} outside Grey"
            var passed = exitFloorOk && exitFormOk
            if (normal.size >= 3) {
                if (avgForm != null && avgForm !in -10.0..5.0) { passed = false; reasons += "Average Form ${avgForm.toInt()} outside Grey zone" }
                if (ratio != null && ratio < fatMin) { passed = false; reasons += "Average Fatigue ${(ratio * 100).toInt()}% of Fitness" }
            }
            val kind = if (ds.any { it.status == Status.MINI }) "TT mini-taper week" else "build week"
            WeekResult(mon, ds, passed, green, complete, avgForm, ratio, exitDay.ctl, exitDay.form, floor, kind, reasons)
        }
        val done = weeks.filter { it.complete }
        fun streak(list: List<Boolean?>): Int { var n = 0; for (p in list.reversed()) { if (p == null) continue; if (p) n++ else break }; return n }
        var best = 0; var run = 0
        for (w in done) { if (w.passed == true) { run++; best = maxOf(best, run) } else if (w.passed == false) run = 0 }

        // ---- Months (weeks assigned by their Monday)
        val months = weeks.groupBy { it.monday.withDayOfMonth(1) }.toSortedMap().map { (m, ws) ->
            val mDays = dayResults.filter { it.date.withDayOfMonth(1) == m && it.day != null }
            val normalForms = mDays.filter { it.status == Status.GREEN || it.status == Status.AMBER || it.status == Status.RED }.mapNotNull { it.day?.form }
            val avgForm = normalForms.takeIf { it.isNotEmpty() }?.average()
            val entry = mDays.firstOrNull()?.day?.ctl; val exit = mDays.lastOrNull()?.day?.ctl
            val hadRace = races.any { it.date.withDayOfMonth(1) == m }
            val failed = ws.count { it.complete && it.passed == false }
            val complete = m.plusMonths(1).minusDays(1).isBefore(today)
            val passed = if (!complete || entry == null || exit == null) null else
                failed <= 1 && exit >= entry * (if (hadRace) 0.90 else 0.97) && (avgForm == null || avgForm in -12.0..5.0)
            MonthResult(m, ws, passed, avgForm, entry, exit, failed, hadRace)
        }
        // Day streak (consecutive passing days up to the latest day with data)
        fun dayPass(r: DayResult) = r.status != Status.RED && r.status != Status.TAPER_MISS
        val judgedDays = dayResults.filter { it.status != Status.NONE }
        var dStreak = 0
        for (r in judgedDays.reversed()) { if (dayPass(r)) dStreak++ else break }
        var bestD = 0; var runD = 0
        for (r in judgedDays) { if (dayPass(r)) { runD++; bestD = maxOf(bestD, runD) } else runD = 0 }
        val dayRuns = runs(judgedDays, { it.date }, { it.date }, { dayPass(it) }, { it.notes.firstOrNull { n -> !n.startsWith("Fatigue") && !n.startsWith("Form outside") } ?: "missed the day rules" })
        val weekRuns = runs(done, { it.monday }, { it.monday.plusDays(6) }, { it.passed }, { it.reasons.joinToString(" · ").ifBlank { "missed" } })
        val monthRuns = runs(months.filter { it.passed != null }, { it.month }, { it.month.plusMonths(1).minusDays(1) }, { it.passed }, { m ->
            listOfNotNull(
                if (m.failedWeeks > 1) "${m.failedWeeks} weeks failed" else null,
                if (m.entryCtl != null && m.exitCtl != null && m.exitCtl < m.entryCtl * (if (m.hadRace) 0.9 else 0.97)) "Fitness fell ${((1 - m.exitCtl / m.entryCtl) * 100).toInt()}%" else null,
                m.avgForm?.takeIf { it !in -12.0..5.0 }?.let { "avg Form ${it.toInt()}" },
            ).joinToString(" · ").ifBlank { "missed" }
        })
        return Summary(dayResults, weeks, months, streak(done.map { it.passed }), streak(months.map { it.passed }), best,
            weeks.lastOrNull()?.takeIf { !it.complete }, dStreak, bestD, dayRuns, weekRuns, monthRuns)
    }
}
