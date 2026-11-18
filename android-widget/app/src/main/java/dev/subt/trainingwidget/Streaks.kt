package dev.subt.trainingwidget

import android.content.Context
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Load-balance rules, judged day by day:
 *  - Outside a taper: Fitness ≥ (1 − band) × reference peak (blended 2/3/4-month peak).
 *  - Build day (all rules): Fatigue ≥ fatMin × Fitness and Form in the Grey zone (−10…+5).
 *  - Taper (A 14, B 10, C 5 days before a race): Fitness ≥ the race's floor (g × reference) and Form ≥ −10.
 *  - Race day and the 3 days after: not judged.
 * A week passes with no red day and ≥ N green days; a month with ≥ 75 % of its weeks passing.
 */
object Streaks {
    enum class Status { GREEN, AMBER, RED, TAPER_OK, TAPER_MISS, EXEMPT, NONE }

    data class DayResult(val date: LocalDate, val status: Status, val notes: List<String>)
    data class WeekResult(val monday: LocalDate, val days: List<DayResult>, val passed: Boolean?, val green: Int, val complete: Boolean)
    data class MonthResult(val month: LocalDate, val weeks: List<WeekResult>, val passed: Boolean?)
    data class Summary(
        val days: List<DayResult>, val weeks: List<WeekResult>, val months: List<MonthResult>,
        val weekStreak: Int, val monthStreak: Int, val bestWeekStreak: Int, val thisWeek: WeekResult?,
    )

    private data class RaceRef(val date: LocalDate, val category: String, val name: String)

    fun compute(ctx: Context): Summary? {
        val s = Prefs.snapshot(ctx) ?: return null
        val band = Prefs.fitnessBand(ctx) / 100.0
        val fatMin = Prefs.fatigueMin(ctx) / 100.0
        val needGreen = Prefs.greyDays(ctx)
        val series = (s.days).sortedBy { it.date }
        if (series.size < 30) return null
        val races = (Prefs.raceHistory(ctx)?.second.orEmpty().map { RaceRef(LocalDate.parse(it.date), it.category, it.name) } +
            s.races.map { RaceRef(LocalDate.parse(it.date), it.category, it.name) }).distinctBy { it.date }

        val byDate = series.associateBy { it.date }
        fun peak(d: LocalDate, days: Long) = series.filter { it.date > d.minusDays(days).toString() && it.date <= d.toString() }.maxOfOrNull { it.ctl }

        val start = LocalDate.parse(series.first().date).plusDays(60)   // need 2 months for the peak
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
            val notes = mutableListOf<String>()
            val status = when {
                after != null -> { notes += "Race / recovery (${after.name.take(24)})"; Status.EXEMPT }
                inTaper -> {
                    val g = 0.89 - (if (ahead!!.category == "RACE_A") 0.01 else 0.0)
                    val ok = day.ctl >= g * ref && day.form >= -10
                    notes += "Taper for ${ahead.name.take(24)}"
                    if (day.ctl < g * ref) notes += "Fitness below taper floor"
                    if (day.form < -10) notes += "Form still ${day.form.toInt()}"
                    if (ok) Status.TAPER_OK else Status.TAPER_MISS
                }
                else -> {
                    val floorOk = day.ctl >= (1 - band) * ref
                    val fatOk = day.atl >= fatMin * day.ctl
                    val formOk = day.form in -10.0..5.0
                    if (!floorOk) notes += "Fitness ${day.ctl.toInt()} below floor ${((1 - band) * ref).toInt()}"
                    if (!fatOk) notes += "Fatigue ${day.atl.toInt()} under ${(fatMin * 100).toInt()}% of Fitness"
                    if (!formOk) notes += "Form ${day.form.toInt()} outside Grey zone"
                    when { !floorOk -> Status.RED; fatOk && formOk -> Status.GREEN; else -> Status.AMBER }
                }
            }
            dayResults += DayResult(d, status, notes)
            d = d.plusDays(1)
        }

        // Weeks (Mon–Sun)
        val weeks = dayResults.groupBy { it.date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }.toSortedMap().map { (mon, ds) ->
            val complete = mon.plusDays(6).isBefore(today)
            val judged = ds.filter { it.status != Status.EXEMPT && it.status != Status.NONE }
            val green = judged.count { it.status == Status.GREEN || it.status == Status.TAPER_OK }
            val bad = judged.any { it.status == Status.RED || it.status == Status.TAPER_MISS }
            val need = minOf(needGreen, judged.size)
            val passed = if (judged.isEmpty()) null else !bad && green >= need
            WeekResult(mon, ds, passed, green, complete)
        }
        val done = weeks.filter { it.complete }
        fun streak(list: List<Boolean?>): Int { var n = 0; for (p in list.reversed()) { if (p == null) continue; if (p) n++ else break }; return n }
        var best = 0; var run = 0
        for (w in done) { if (w.passed == true) { run++; best = maxOf(best, run) } else if (w.passed == false) run = 0 }

        val months = done.groupBy { it.monday.withDayOfMonth(1) }.toSortedMap().map { (m, ws) ->
            val judged = ws.filter { it.passed != null }
            MonthResult(m, ws, if (judged.isEmpty()) null else judged.count { it.passed == true } >= kotlin.math.ceil(judged.size * 0.75))
        }
        val completeMonths = months.filter { it.month.plusMonths(1).isBefore(today.withDayOfMonth(1).plusDays(1)) }
        return Summary(dayResults, weeks, months, streak(done.map { it.passed }), streak(completeMonths.map { it.passed }), best,
            weeks.lastOrNull()?.takeIf { !it.complete })
    }
}
