package dev.subt.trainingwidget

import android.content.Context
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The coach: an instant, rule-based read-out of numbers the app already has, plus a self-contained prompt
 * the athlete can hand to the Claude app. Pure logic: no views, no network. Nothing leaves the phone
 * unless the athlete taps "Talk it through" (see [CoachSheet]).
 */
object Coach {
    enum class Topic { TODAY, LOAD, WORKOUT, WEEK, SUBT, RACE }
    enum class Level { GOOD, WATCH, ACT, INFO }
    data class Line(val level: Level, val text: String)
    /** [data] is a plain-text dump of the facts used, for the hand-off prompt. */
    data class Readout(val title: String, val lines: List<Line>, val data: String, val questions: List<String>)

    private val dm: DateTimeFormatter get() = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

    // ---------------------------------------------------------------- small helpers

    private fun pct(x: Double) = "${(x * 100).roundToInt()}%"
    private fun signed(v: Double): String { val r = v.roundToInt(); return if (r > 0) "+$r" else if (r < 0) "−${-r}" else "0" }
    private fun w(v: Double?) = v?.let { "${it.roundToInt()} W" } ?: "–"
    private fun dur(sec: Number) = SubtModel.time(sec.toDouble())
    private fun mins(sec: Double) = "${(sec / 60).roundToInt()} min"
    private fun paceStr(minPerKm: Double): String {
        val total = (minPerKm * 60).roundToInt()
        return "${total / 60}:${"%02d".format(total % 60)} /km"
    }
    private fun shortDate(s: String) = runCatching { LocalDate.parse(s).format(dm) }.getOrDefault(s)
    private fun km(m: Double) = String.format(Locale.US, "%.2f km", m / 1000)

    /** Share of work on or over target: 85 %+ good, 70–85 % watch, below act. */
    private fun onLevel(share: Double) = when { share >= 0.85 -> Level.GOOD; share >= 0.70 -> Level.WATCH; else -> Level.ACT }

    private fun formLevel(z: FormZone) = when (z) {
        FormZone.HIGH_RISK -> Level.ACT
        FormZone.OPTIMAL -> Level.GOOD
        FormZone.GREY, FormZone.FRESH -> Level.INFO
        FormZone.TRANSITION -> Level.WATCH
    }

    private fun colorLevel(color: Int) = when (color) {
        0xFF3DD68C.toInt() -> Level.GOOD
        0xFFFF7A7A.toInt() -> Level.ACT
        0xFFFFC53D.toInt() -> Level.WATCH
        else -> Level.INFO
    }

    private fun Level.tag() = when (this) { Level.GOOD -> "[good] "; Level.WATCH -> "[watch] "; Level.ACT -> "[act] "; Level.INFO -> "" }

    private fun questionsFor(t: Topic): List<String> = when (t) {
        Topic.TODAY -> listOf("Should I train hard today or keep it easy?", "What is behind today's call, in plain terms?", "How should I shape the next few days?")
        Topic.LOAD -> listOf("Is my training load build sensible for my goal?", "Am I at risk of overdoing it?", "What should the next two weeks of load look like?")
        Topic.WORKOUT -> listOf("What should I change next time?", "Was this the right effort for a SubT session?", "What does my HR response say about my fitness?")
        Topic.WEEK -> listOf("Did I balance hard and easy running this week?", "What should I change next week?", "Is too much of my running in the in-between zone?")
        Topic.SUBT -> listOf("Am I on track for a sub-3 marathon?", "What should I focus on to speed up my SubT pace?", "Is my trend real or just noise?")
        Topic.RACE -> listOf("How should I taper for this race?", "Is my projected race-morning form right?", "What power and pace should I plan to race at?")
    }

    // ---------------------------------------------------------------- TODAY

    fun today(ctx: Context): Readout? {
        val s = Prefs.snapshot(ctx) ?: return null
        val rhrToday = Prefs.rhrOverride(ctx); val hrvToday = Prefs.hrvOverride(ctx)
        val v = Verdict.compute(s, rhrToday, hrvToday) ?: return null
        val facts = Verdict.explain(s, rhrToday, hrvToday, Prefs.loadIndex(ctx)?.first)
        val d = s.days.last()
        val lines = mutableListOf<Line>()
        val callLevel = when (v.call) { Verdict.Call.PUSH, Verdict.Call.BUILD, Verdict.Call.RECOVER -> Level.GOOD; Verdict.Call.HOLD -> Level.WATCH; Verdict.Call.BACK_OFF -> Level.ACT; Verdict.Call.TAPER -> Level.INFO }
        lines += Line(callLevel, if (v.call == Verdict.Call.TAPER) v.headline else "Today's call is ${v.call.label}: ${v.headline}")
        val z = FormZone.of(d.form)
        lines += Line(formLevel(z), "Form ${signed(d.form)} (${z.label}): Fitness ${d.ctl.roundToInt()}, Fatigue ${d.atl.roundToInt()}")
        // Resting HR against your normal, same baseline as the call itself
        val rhrAll = s.days.mapNotNull { it.rhr }
        val rhrNow = rhrToday ?: d.rhr
        if (rhrAll.size >= 10 && rhrNow != null) {
            val base = rhrAll.dropLast(1).takeLast(42)
            val normal = base.average()
            val delta = rhrNow - normal
            val lvl = when { delta >= 5.0 -> Level.ACT; delta >= 3.0 -> Level.WATCH; else -> Level.GOOD }
            lines += Line(lvl, when (lvl) {
                Level.GOOD -> "Resting HR ${rhrNow.roundToInt()}, in line with your normal ${normal.roundToInt()}"
                else -> "Resting HR ${rhrNow.roundToInt()} is ${delta.roundToInt()} bpm above your normal ${normal.roundToInt()}: you may not be fully recovered"
            })
        }
        // One more warning from the call's own reasons, if it isn't the form or resting HR line
        v.reasons.firstOrNull { (c, t) -> colorLevel(c) != Level.GOOD && !t.startsWith("Form") && !t.startsWith("Resting HR") }
            ?.let { (c, t) -> lines += Line(colorLevel(c), t) }
        // Taper window
        val todayDate = LocalDate.now()
        val race = s.races.firstOrNull { it.date >= todayDate.toString() }
        if (race != null) {
            val taperDays = when (race.category) { "RACE_A" -> 14L; "RACE_B" -> 10L; "RACE_C" -> 5L; else -> 0L }
            val daysTo = ChronoUnit.DAYS.between(todayDate, LocalDate.parse(race.date))
            if (taperDays > 0 && daysTo <= taperDays) lines += Line(Level.INFO, "${race.name.take(30)} is in $daysTo days, inside its $taperDays-day taper window")
        }
        val data = buildString {
            appendLine("Date: ${todayDate}")
            appendLine("Call: ${v.call.label} (${v.headline})")
            for ((k, x) in facts) appendLine("$k: $x")
            if (v.reasons.isNotEmpty()) appendLine("Signals: " + v.reasons.joinToString("; ") { it.second })
        }.trim()
        return Readout("Today", lines.take(5), data, questionsFor(Topic.TODAY))
    }

    // ---------------------------------------------------------------- LOAD

    fun load(ctx: Context): Readout? = loadReadout(Prefs.snapshot(ctx)?.days.orEmpty())

    /** The last 6 weeks of Fitness / Fatigue / Form. [days] oldest first. */
    internal fun loadReadout(days: List<Day>): Readout? {
        if (days.size < 14) return null
        val win = days.takeLast(43)
        val first = win.first(); val last = win.last()
        val n = win.size - 1
        val lines = mutableListOf<Line>()
        val change = last.ctl - first.ctl
        lines += when {
            change > 2 -> Line(Level.GOOD, "Fitness rising: ${first.ctl.roundToInt()} to ${last.ctl.roundToInt()} (${signed(change)}) over $n days")
            change < -2 -> Line(Level.WATCH, "Fitness falling: ${first.ctl.roundToInt()} to ${last.ctl.roundToInt()} (${signed(change)}) over $n days")
            else -> Line(Level.INFO, "Fitness holding at ${last.ctl.roundToInt()} over $n days (${signed(change)})")
        }
        val ramp = if (win.size >= 8) last.ctl - win[win.size - 8].ctl else null
        if (ramp != null) when {
            ramp > 4 -> lines += Line(Level.ACT, "Ramping fast: Fitness ${signed(ramp)} in the last 7 days")
            ramp > 2 -> lines += Line(Level.GOOD, "Steady build: Fitness ${signed(ramp)} in the last 7 days")
        }
        val z = FormZone.of(last.form)
        lines += Line(formLevel(z), "Form now ${signed(last.form)} (${z.label})")
        val deep = win.takeLast(14).count { it.form < -20 }
        lines += when {
            deep >= 5 -> Line(Level.WATCH, "$deep of the last 14 days had Form below −20: a lot of time deeply fatigued")
            deep > 0 -> Line(Level.INFO, "$deep of the last 14 days had Form below −20")
            else -> Line(Level.GOOD, "No days in the last 14 with Form below −20")
        }
        val peak = win.maxByOrNull { it.ctl }!!
        val below = peak.ctl - last.ctl
        lines += if (below < 0.5) Line(Level.GOOD, "Fitness ${last.ctl.roundToInt()} is at its highest of the period")
        else Line(Level.INFO, "Peak Fitness ${peak.ctl.roundToInt()} on ${shortDate(peak.date)}; today is ${below.roundToInt()} below it (${(below / peak.ctl * 100).roundToInt()}%)")
        val data = buildString {
            appendLine("Period: ${first.date} to ${last.date} ($n days)")
            appendLine("Fitness: ${first.ctl.roundToInt()} at the start, ${last.ctl.roundToInt()} now (${signed(change)}); peak ${peak.ctl.roundToInt()} on ${peak.date}")
            appendLine("Fatigue now: ${last.atl.roundToInt()}")
            appendLine("Form now: ${signed(last.form)} (${z.label})")
            if (ramp != null) appendLine("Fitness change over the last 7 days: ${signed(ramp)}")
            appendLine("Days with Form below -20 in the last 14: $deep")
            val weekly = (0..5).mapNotNull { k -> win.getOrNull(win.size - 1 - 7 * k) }.reversed()
            appendLine("Weekly snapshots (date: Fitness / Fatigue / Form): " + weekly.joinToString("; ") { "${it.date}: ${it.ctl.roundToInt()} / ${it.atl.roundToInt()} / ${signed(it.form)}" })
        }.trim()
        return Readout("Training load", lines.take(5), data, questionsFor(Topic.LOAD))
    }

    // ---------------------------------------------------------------- WORKOUT

    fun workout(det: WorkoutDetail.Detail, zones: Zones.Run?): Readout {
        val lines = mutableListOf<Line>()
        val wk = det.work
        val workSteps = det.steps.filter { it.isWork }
        val allSteps = det.steps.isNotEmpty() && det.steps.all { it.isWork }
        val noun = if (allSteps) "Step" else "Rep"
        if (wk == null) {
            lines += Line(Level.INFO, "No planned work steps were matched in this workout, so there is nothing to judge against a target")
            val bits = listOfNotNull(det.power.avg?.let { "average ${w(it)}" }, det.hr.avg?.let { "average HR ${it.roundToInt()} bpm" })
            if (bits.isNotEmpty()) lines += Line(Level.INFO, "Whole workout: ${dur(det.seconds)}, " + bits.joinToString(", "))
        } else {
            val ftp = det.ftp
            val lo = wk.loW; val hi = wk.hiW
            val easy = allSteps && (wk.hrTarget || (ftp != null && lo != null && hi != null && (lo + hi) / 2 < 0.8 * ftp))
            val ceiling = wk.hrTarget || easy
            if (!ceiling) {
                val share = wk.inOrOver
                if (share != null) {
                    val lvl = onLevel(share)
                    lines += Line(lvl, "${pct(share)} of the work time was on or over target" + when (lvl) {
                        Level.GOOD -> ": right where it should be"; Level.WATCH -> ": a little short of target"; else -> ": well short of target"
                    })
                } else lines += Line(Level.INFO, "No planned power targets found for this workout, so there is nothing to judge the reps against")
                val unders = workSteps.mapIndexedNotNull { i, st ->
                    val a = st.power.avg; val l = st.loW
                    if (!st.hrTarget && st.vsTarget == -1 && a != null && l != null) "$noun ${i + 1} averaged ${maxOf(1, (l - a).roundToInt())} W under the target range" else null
                }
                if (unders.isNotEmpty()) lines += Line(Level.WATCH, unders.joinToString("; "))
                if (workSteps.size >= 2) {
                    val f = workSteps.first().power.avg; val l = workSteps.last().power.avg
                    if (f != null && l != null && f > 0) {
                        val drop = (f - l) / f
                        lines += if (drop > 0.03) Line(Level.WATCH, "Faded: the last ${noun.lowercase()} averaged ${w(l)}, ${(drop * 100).roundToInt()}% below the first (${w(f)})")
                        else Line(Level.GOOD, "Held power: last ${noun.lowercase()} ${w(l)} against ${w(f)} for the first")
                    }
                }
                val over172 = wk.hrOver?.frac ?: wk.hr172?.let { 1 - it }
                if (over172 != null) lines += when {
                    over172 > 0.20 -> Line(Level.ACT, "Too hard: ${pct(over172)} of the work had HR over 172. Start about 5 W lower next time")
                    over172 >= 0.05 -> Line(Level.WATCH, "HR went over 172 for ${pct(over172)} of the work")
                    else -> Line(Level.GOOD, "HR stayed under 172 (${pct(over172)} over)")
                }
            } else {
                val over = wk.over
                val what = if (wk.hrTarget) "the HR ceiling" + (hi?.let { " of ${it.roundToInt()} bpm" } ?: "") else "the top of the easy range" + (hi?.let { " (${it.roundToInt()} W)" } ?: "")
                if (over != null) lines += if (over > 0.25) Line(Level.WATCH, "Easy run wasn't easy: ${pct(over)} of the time was over $what")
                else Line(Level.GOOD, "Stayed easy: only ${pct(over)} of the time was over $what")
                val zs = zones
                if (zs != null) {
                    val tot = zs.bandSec.sum()
                    if (tot > 0) lines += Line(Level.INFO, "${pct((zs.bandSec[0] + zs.bandSec[1]) / tot)} of the run was under 135 bpm")
                }
            }
        }
        if (det.tests.isNotEmpty()) {
            val f = det.ftp
            lines += Line(Level.INFO, det.tests.mapIndexed { k, st ->
                "Test ${k + 1} averaged ${w(st.power.avg)}" + (if (f != null && f > 0) st.power.avg?.let { " (${(it / f * 100).roundToInt()}% of CP ${f.roundToInt()} W)" } ?: "" else "")
            }.joinToString("; "))
        }

        val data = buildString {
            appendLine("Workout: ${det.name}")
            appendLine("Date: ${det.date} · Type: ${det.type}")
            appendLine("Duration: ${dur(det.seconds)} · Distance: ${km(det.distance)}" + (det.load?.let { " · Load: $it" } ?: ""))
            appendLine("Average power: ${w(det.power.avg)} · Average HR: ${det.hr.avg?.let { "${it.roundToInt()} bpm" } ?: "–"}")
            det.ftp?.let { appendLine("CP that day: ${it.roundToInt()} W") }
            if (wk != null) {
                appendLine("Work steps: ${wk.count}, ${dur(wk.seconds)} in total, average ${if (wk.hrTarget) "HR ${wk.hr.avg?.roundToInt() ?: "–"} bpm" else w(wk.power.avg)}" +
                    (if (wk.loW != null && wk.hiW != null) ", target ${wk.loW.roundToInt()}–${wk.hiW.roundToInt()} ${if (wk.hrTarget) "bpm" else "W"}" else ""))
                if (wk.inT != null && wk.under != null && wk.over != null)
                    appendLine("Work time under / in / over target: ${pct(wk.under)} / ${pct(wk.inT)} / ${pct(wk.over)}; on or over: ${wk.inOrOver?.let { pct(it) } ?: "–"}")
                wk.hrOver?.let { appendLine("Share of work with HR over 172: ${pct(it.frac)}") }
                wk.hrB?.let { appendLine("Share of work with HR 155–172: ${pct(it.frac)}") }
            }
            zones?.let { z ->
                val tot = z.bandSec.sum()
                if (tot > 0) appendLine("Whole run by HR: under 135 ${pct((z.bandSec[0] + z.bandSec[1]) / tot)}, 135–155 ${pct(z.bandSec[2] / tot)}, 155–172 ${pct((z.bandSec[3] + z.bandSec[4]) / tot)}, over 172 ${pct((z.bandSec[5] + z.bandSec[6]) / tot)}")
            }
            appendLine("Steps (kind | duration | avg power | target | on or over target | avg HR | pace):")
            var workNo = 0; var testNo = 0
            for (st in det.steps) {
                val label = when {
                    st.isWork -> "Work rep ${++workNo}"
                    st.isTest -> "Test ${++testNo}"
                    else -> st.kind.label
                }
                val unit = if (st.hrTarget) "bpm" else "W"
                val target = if (st.loW != null && st.hiW != null) "${st.loW.roundToInt()}–${st.hiW.roundToInt()} $unit" else "–"
                val onOver = st.inOrOver?.let { pct(it) } ?: "–"
                val pc = st.speed.avg?.takeIf { it > 0 }?.let { if (det.isRide) String.format(Locale.US, "%.1f km/h", it * 3.6) else paceStr(1000 / it / 60) } ?: "–"
                appendLine("${st.index}. $label | ${dur(st.seconds)} | ${w(st.power.avg)} | $target | $onOver | ${st.hr.avg?.let { "${it.roundToInt()} bpm" } ?: "–"} | $pc")
            }
        }.trim()
        return Readout(det.name.take(40), lines.take(5), data, questionsFor(Topic.WORKOUT))
    }

    // ---------------------------------------------------------------- WEEK

    fun week(ctx: Context, start: LocalDate, end: LocalDate): Readout? {
        val tot = Zones.total(Prefs.zones(ctx), start, end)
        val title = if (start == end) "Day · ${start.format(dm)}" else "${start.format(dm)} – ${end.format(dm)}"
        return weekReadout(tot, title, Prefs.lt1(ctx))
    }

    internal fun weekReadout(tot: Zones.Total, title: String, lt1: Int = 153): Readout? {
        if (tot.runs == 0) return null
        val lines = mutableListOf<Line>()
        if (tot.subtWorkSec > 0) {
            val share = (tot.subtInSec + tot.subtOverSec) / tot.subtWorkSec
            val lvl = onLevel(share)
            lines += Line(lvl, "SubT: ${pct(share)} of work time on or over target (${mins(tot.subtInSec + tot.subtOverSec)} of ${mins(tot.subtWorkSec)})")
        }
        val sum = tot.bandSec.sum()
        if (sum > 0) {
            val easy = (tot.bandSec[0] + tot.bandSec[1]) / sum
            lines += when {
                easy >= 0.60 -> Line(Level.GOOD, "${pct(easy)} of running time under 135 bpm: plenty of truly easy running")
                easy >= 0.45 -> Line(Level.INFO, "${pct(easy)} of running time under 135 bpm")
                else -> Line(Level.WATCH, "Only ${pct(easy)} of running time under 135 bpm: little truly easy running")
            }
            val mid = tot.bandSec[2] / sum
            if (mid > 0) lines += if (mid > 0.35) Line(Level.WATCH, "${pct(mid)} at 135–155 bpm: a lot of in-between running")
            else Line(Level.INFO, "${pct(mid)} at 135–155 bpm")
            val over = (tot.bandSec[5] + tot.bandSec[6]) / sum
            if (over > 0) lines += if (over > 0.05) Line(Level.WATCH, "${pct(over)} of running time over 172 bpm")
            else Line(Level.INFO, "${pct(over)} of running time over 172 bpm")
        }
        if (tot.longSec > 0) lines += Line(Level.INFO, "Long-run aerobic: ${mins(tot.longSec)} at HR 135–$lt1")
        if (lines.isEmpty()) return null
        val data = buildString {
            appendLine("Period: $title · ${tot.runs} runs")
            appendLine("SubT work time: ${dur(tot.subtWorkSec)}; under / in / over target: ${dur(tot.subtUnderSec)} / ${dur(tot.subtInSec)} / ${dur(tot.subtOverSec)}")
            if (sum > 0) {
                appendLine("Running time by HR (all runs, ${dur(sum)} in total):")
                for (r in Zones.bandRows(tot.bandSec, tot.bandM)) appendLine("  ${r.label}: ${dur(r.sec)} (${r.pct.roundToInt()}%)")
            }
            appendLine("Long-run aerobic time (HR 135–$lt1, long runs only): ${dur(tot.longSec)}")
        }.trim()
        return Readout("Week · $title", lines.take(5), data, questionsFor(Topic.WEEK))
    }

    // ---------------------------------------------------------------- SUBT

    /** The 6-month trend in words: "real improvement" only when the slope is bigger than its error. */
    internal fun trendLine(t: Pair<Double, Double>?): Line? {
        if (t == null) return null
        val (slope, err) = t
        val text = SubtWorkouts.trendText(t)
        return when {
            abs(slope) > err && slope < 0 -> Line(Level.GOOD, "6-month trend $text per month: real improvement")
            abs(slope) > err && slope > 0 -> Line(Level.WATCH, "6-month trend $text per month: a real slowing, not noise")
            else -> Line(Level.INFO, "6-month trend $text per month: within noise, no clear change")
        }
    }

    fun subt(ctx: Context): Readout? {
        val pace = SubtModel.currentPace(ctx) ?: return null
        SubtModel.applyGap(ctx)
        val reps = Prefs.subtReps(ctx)?.second.orEmpty()
        val today = LocalDate.now()
        val lines = mutableListOf<Line>()
        val nowReps = SubtWorkouts.current(reps)?.first
        val prev = SubtWorkouts.current(reps, today.minusDays(28))?.first
        val change: Double? = if (nowReps != null && prev != null) (nowReps - prev) * 60 else null
        val base = "SubT pace now ${paceStr(pace)} (last 28 days)"
        lines += when {
            change == null -> Line(Level.INFO, base)
            change <= -1.0 -> Line(Level.GOOD, "$base, ${abs(change).roundToInt()} s/km faster than the 28 days before")
            change >= 1.0 -> Line(Level.WATCH, "$base, ${change.roundToInt()} s/km slower than the 28 days before")
            else -> Line(Level.INFO, "$base, about the same as the 28 days before")
        }
        val months = Prefs.subt(ctx)?.months.orEmpty()
        val trend = SubtWorkouts.trendWithError(months)
        trendLine(trend)?.let { lines += it }
        val cutoff = today.minusDays(30).toString()
        val reps30 = if (reps.isNotEmpty()) reps.count { it.date >= cutoff } else Prefs.subt(ctx)?.lapsLast30d ?: 0
        lines += if (reps30 < 6) Line(Level.WATCH, "Only $reps30 reps in the last 30 days: few reps, so the trend is unreliable")
        else Line(Level.GOOD, "$reps30 reps in the last 30 days: enough for a reliable read")
        val t5 = SubtModel.fiveK(pace); val th = SubtModel.half(pace); val tf = SubtModel.full(pace)
        lines += Line(Level.INFO, "This pace supports about ${SubtModel.time(t5)} for 5K, ${SubtModel.time(th)} for the half, ${SubtModel.time(tf)} for the marathon")
        val gap = tf - 10800.0
        lines += if (gap <= 0) Line(Level.GOOD, "Marathon ${SubtModel.time(-gap)} inside 3:00:00")
        else Line(Level.INFO, "Marathon ${SubtModel.time(gap)} outside 3:00:00")
        val data = buildString {
            appendLine("SubT pace now (28 days, rep-mix adjusted): ${paceStr(pace)}")
            nowReps?.let { appendLine("From reps: ${paceStr(it)}") }
            prev?.let { appendLine("28 days earlier: ${paceStr(it)}") }
            appendLine("6-month trend: ${SubtWorkouts.trendText(trend)} per month")
            appendLine("Reps in the last 30 days: $reps30")
            appendLine("Recent months (month: pace, reps): " + months.takeLast(6).joinToString("; ") { "${it.month}: ${paceStr(it.pace)}, ${it.laps}${if (it.outlier) " (few, ignored in trend)" else ""}" })
            appendLine("Times this pace supports: 5K ${SubtModel.time(t5)}, half ${SubtModel.time(th)}, marathon ${SubtModel.time(tf)}")
            appendLine("Gap to 3:00:00 marathon: ${if (gap <= 0) "${SubtModel.time(-gap)} inside" else "${SubtModel.time(gap)} outside"}")
            appendLine("Race-readiness gaps used: 5K ${pct(SubtModel.GAP)}, half ${pct(SubtModel.GAP_HM)}, marathon ${pct(SubtModel.GAP_FM)}")
        }.trim()
        return Readout("SubT pace", lines.take(5), data, questionsFor(Topic.SUBT))
    }

    // ---------------------------------------------------------------- RACE

    fun race(ctx: Context, index: Int = 0): Readout? {
        val s = Prefs.snapshot(ctx) ?: return null
        val today = LocalDate.now()
        val races = s.races.filter { it.date >= today.toString() }
        if (races.isEmpty()) return null
        val race = races[index.coerceIn(0, races.lastIndex)]
        val rd = LocalDate.parse(race.date)
        val daysTo = ChronoUnit.DAYS.between(today, rd)
        val series = (s.days + s.proj).distinctBy { it.date }.sortedBy { it.date }
        val eveDay = series.firstOrNull { it.date == rd.minusDays(1).toString() }
        val ctl = eveDay?.ctl ?: race.projCtl
        val atl = eveDay?.atl ?: race.projAtl
        val dist = RaceDistance.of(race.distanceM, race.name)
        val tt = Taper.targets(series, rd, race.category, dist, Prefs.raceHistory(ctx)?.second.orEmpty(), Prefs.taperPreset(ctx))
        val prio = race.category.removePrefix("RACE_").takeIf { it.length == 1 }?.let { "$it priority" } ?: "no priority set"
        val lines = mutableListOf<Line>()
        lines += Line(Level.INFO, "${race.name.take(30)}: ${if (daysTo == 0L) "today" else "$daysTo days to go"} (${rd.format(dm)}, $prio)")
        if (ctl != null && atl != null) {
            val z = FormZone.of(ctl - atl)
            lines += Line(Level.INFO, "Projected race morning: Fitness ${ctl.roundToInt()}, Fatigue ${atl.roundToInt()}, Form ${signed(ctl - atl)} (${z.label})")
        } else lines += Line(Level.INFO, "No race-morning projection yet: it needs planned sessions up to the race in intervals.icu")
        if (tt != null) {
            if (atl != null) lines += when {
                atl > tt.fatHi -> Line(Level.WATCH, "Projected Fatigue ${atl.roundToInt()} is above the target range ${tt.fatLo.roundToInt()}–${tt.fatHi.roundToInt()}: you would arrive carrying too much fatigue")
                atl < tt.fatLo -> Line(Level.WATCH, "Projected Fatigue ${atl.roundToInt()} is below the target range ${tt.fatLo.roundToInt()}–${tt.fatHi.roundToInt()}: you may be tapering more than needed")
                else -> Line(Level.GOOD, "Projected Fatigue ${atl.roundToInt()} is inside the target range ${tt.fatLo.roundToInt()}–${tt.fatHi.roundToInt()}")
            }
            if (ctl != null) lines += if (ctl >= tt.floor) Line(Level.GOOD, "Projected Fitness ${ctl.roundToInt()} stays above the floor of ${tt.floor.roundToInt()}")
            else Line(Level.WATCH, "Projected Fitness ${ctl.roundToInt()} is below the floor of ${tt.floor.roundToInt()}: the taper may cost too much fitness")
        } else lines += Line(Level.INFO, "No taper targets yet: they need a couple of months of Fitness history")
        val now = s.days.lastOrNull()
        val data = buildString {
            appendLine("Race: ${race.name} · ${race.date} · ${dist.label} · $prio · $daysTo days to go")
            now?.let { appendLine("Now: Fitness ${it.ctl.roundToInt()}, Fatigue ${it.atl.roundToInt()}, Form ${signed(it.form)}") }
            if (ctl != null && atl != null) appendLine("Projected race morning (from planned sessions): Fitness ${ctl.roundToInt()}, Fatigue ${atl.roundToInt()}, Form ${signed(ctl - atl)}")
            if (tt != null) {
                appendLine("Reference Fitness peak (2/3/4-month blend): ${tt.ref.roundToInt()}")
                appendLine("Fatigue target range: ${tt.fatLo.roundToInt()}–${tt.fatHi.roundToInt()} (aim ${tt.fatTarget.roundToInt()})")
                appendLine("Fitness floor: ${tt.floor.roundToInt()}")
            }
        }.trim()
        return Readout("Race · ${race.name.take(24)}", lines.take(5), data, questionsFor(Topic.RACE))
    }

    // ---------------------------------------------------------------- hand-off

    fun background(ctx: Context): String {
        val s = Prefs.snapshot(ctx)
        val pn = PowerModel.now(ctx)
        val pbs = Prefs.effectivePbs(ctx)
        val d = s?.days?.lastOrNull()
        val race = s?.races?.firstOrNull { it.date >= LocalDate.now().toString() }
        return buildString {
            appendLine("- Goal: a sub-3 marathon, with matching shorter times (especially 5K).")
            appendLine("- I train by POWER (Stryd on Garmin); heart rate is the secondary check.")
            appendLine("- My method is \"SubT\": threshold reps of 3, 6 or 10 minutes at set power targets, keeping HR under 172. Easy running is HR under 135. I also do 45/15 sessions.")
            appendLine("- I use intervals.icu Fitness / Fatigue / Form. You only advise: nothing here changes anything in intervals.icu.")
            appendLine("Current numbers:")
            pn?.let { appendLine("- CP / FTP: ${it.cp.roundToInt()} W (${it.cpSource})") } ?: s?.runFtp?.let { appendLine("- CP / FTP: ${it.roundToInt()} W") }
            (pn?.kg ?: Prefs.weightOverride(ctx) ?: s?.weight)?.let { appendLine("- Weight: ${String.format(Locale.US, "%.1f", it)} kg") }
            val pbTxt = listOfNotNull(pbs.fiveK?.let { "5K ${SubtModel.time(it.seconds)}" }, pbs.tenK?.let { "10K ${SubtModel.time(it.seconds)}" }, pbs.half?.let { "half ${SubtModel.time(it.seconds)}" })
            if (pbTxt.isNotEmpty()) appendLine("- PBs: " + pbTxt.joinToString(", "))
            SubtModel.currentPace(ctx)?.let { appendLine("- Current SubT pace: ${paceStr(it)}") }
            d?.let { appendLine("- Today: Fitness ${it.ctl.roundToInt()}, Fatigue ${it.atl.roundToInt()}, Form ${signed(it.form)}") }
            race?.let { appendLine("- Next race: ${it.name} on ${it.date} (${ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(it.date))} days)") }
        }.trim()
    }

    fun toneText(ctx: Context): String = when (Prefs.coachTone(ctx)) {
        1 -> "encouraging but honest"
        2 -> "analytical, show your reasoning"
        else -> "direct and concise"
    }

    fun prompt(ctx: Context, r: Readout, question: String): String =
        "You are my running coach. Be ${toneText(ctx)}. Advise only; never suggest editing my training platform's settings for me.\n\n" +
            "ABOUT ME\n${background(ctx)}\n\n" +
            "WHAT I'M LOOKING AT (${r.title})\n${r.data}\n\n" +
            "THE APP'S READ-OUT\n${r.lines.joinToString("\n") { "- " + it.level.tag() + it.text }}\n\n" +
            "MY QUESTION\n$question"
}
