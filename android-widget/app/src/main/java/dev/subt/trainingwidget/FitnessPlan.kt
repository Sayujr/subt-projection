package dev.subt.trainingwidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * "Fitness & fatigue: am I on track?" Pure rules, no views and no network: the verdict, this week's load maths,
 * the 12-week target band, the reading under the chart and the rule-based suggestions. [FitnessFlow] writes the
 * suggestions to intervals.icu, but only after you tap and confirm.
 */
object FitnessPlan {
    // ---------------------------------------------------------------- defaults (no settings UI for these yet)
    /** Fitness (CTL) gained per week while building: 3 to 5 is on track, 4 is the aim. */
    const val BUILD_LO = 3.0
    const val BUILD_TARGET = 4.0
    const val BUILD_HI = 5.0
    /** Gains from here up to [BUILD_LO] are "slightly behind"; below it "behind". */
    const val SLIGHT_LO = 1.0
    /** More than this a week is building too fast. */
    const val TOO_FAST = 7.0
    /** Fatigue ÷ Fitness: green below, amber up to [RATIO_RED], red above. */
    const val RATIO_AMBER = 1.2
    const val RATIO_RED = 1.4
    /** Inside a taper Fitness may drop; between these weekly changes it counts as tapering as planned. */
    const val TAPER_FLOOR = -6.0
    const val TAPER_CEIL = 1.5
    /** The band eases to this share of the race-day target by race day (lower / upper edge). */
    const val TAPER_END_LO = 0.90
    const val TAPER_END_HI = 0.97
    const val HISTORY_WEEKS = 12
    const val DEFAULT_WEEKS = 8
    const val MAX_SUGGESTIONS = 3
    /** intervals.icu's Fitness time constant in days. */
    const val CTL_DAYS = 42.0
    private const val DEFAULT_EASY_RATE = 0.55

    private const val GOOD = 0xFF3DD68C.toInt()
    private const val WARN = 0xFFFFC53D.toInt()
    private const val BAD = 0xFFFF7A7A.toInt()

    enum class Status(val label: String, val color: Int) {
        ON_TRACK("On track", GOOD),
        SLIGHTLY_BEHIND("Slightly behind", WARN),
        BEHIND("Behind", BAD),
        TOO_FAST("Building too fast", BAD),
        TAPER_OK("Tapering as planned", GOOD),
        TAPER_BUILDING("Tapering: still building", WARN),
        TAPER_DROPPING("Tapering: dropping fast", WARN),
    }

    // ---------------------------------------------------------------- small helpers

    private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun signed(v: Double): String { val r = v.roundToInt(); return if (r > 0) "+$r" else if (r < 0) "−${-r}" else "0" }
    private fun signedInt(r: Int): String = if (r > 0) "+$r" else if (r < 0) "−${-r}" else "0"
    private fun round5(v: Double) = (v / 5.0).roundToInt() * 5

    fun todayOf(s: Snapshot): LocalDate = runCatching { LocalDate.parse(s.today) }.getOrDefault(LocalDate.now())
    private fun dateOf(w: Workout): LocalDate? = runCatching { LocalDate.parse(w.date) }.getOrNull()
    private fun dayOf(d: Day): LocalDate? = runCatching { LocalDate.parse(d.date) }.getOrNull()

    fun dur(min: Int): String = if (min >= 60) "${min / 60}h${"%02d".format(min % 60)}" else "$min min"

    fun ratioColor(r: Double) = when { r > RATIO_RED -> BAD; r > RATIO_AMBER -> WARN; else -> GOOD }
    private fun fatigueWord(r: Double) = when { r > RATIO_RED -> "too high"; r > RATIO_AMBER -> "elevated"; else -> "fine" }

    // ---------------------------------------------------------------- the maths

    /** Fitness change over the last 7 days, or null without a week of data. */
    fun weeklyRate(days: List<Day>): Double? {
        if (days.size < 8) return null
        val last = days.last()
        val ago = dayOf(last)?.minusDays(7)?.toString()
        val prev = days.firstOrNull { it.date == ago } ?: days[days.size - 8]
        return last.ctl - prev.ctl
    }

    fun classify(rate: Double, taper: Boolean = false): Status =
        if (taper) when {
            rate > TAPER_CEIL -> Status.TAPER_BUILDING
            rate < TAPER_FLOOR -> Status.TAPER_DROPPING
            else -> Status.TAPER_OK
        } else when {
            rate > TOO_FAST -> Status.TOO_FAST
            rate >= BUILD_LO -> Status.ON_TRACK
            rate >= SLIGHT_LO -> Status.SLIGHTLY_BEHIND
            else -> Status.BEHIND
        }

    /**
     * The load over a 7-day week that moves Fitness from [ctl0] to [ctl0] + [ramp], with intervals.icu's model
     * CTL_t = CTL_t-1 + (load_t − CTL_t-1) / 42 and the same load every day. Weekly load = 7 × the daily value.
     */
    fun weeklyLoadFor(ctl0: Double, ramp: Double): Double {
        val r = ((CTL_DAYS - 1) / CTL_DAYS).pow(7)
        val daily = (ctl0 + ramp - ctl0 * r) / (1 - r)
        return daily * 7
    }

    fun taperDays(category: String): Int = when (category) { "RACE_A" -> 14; "RACE_B" -> 10; "RACE_C" -> 5; else -> 0 }

    private fun daysTo(r: Race, today: LocalDate): Long? = runCatching { ChronoUnit.DAYS.between(today, LocalDate.parse(r.date)) }.getOrNull()

    /** The next race when its taper window (A 14 days, B 10, C 5) has begun. */
    fun taperRace(s: Snapshot, today: LocalDate = todayOf(s)): Race? =
        s.races.filter { it.date >= today.toString() }.minByOrNull { it.date }
            ?.takeIf { r -> val td = taperDays(r.category); val dt = daysTo(r, today); td > 0 && dt != null && dt <= td }

    /** The race this page aims at: the next A race, else the next B, else null (8 weeks ahead). */
    fun targetRace(s: Snapshot, today: LocalDate = todayOf(s)): Race? {
        val up = s.races.filter { it.date >= today.toString() }.sortedBy { it.date }
        return up.firstOrNull { it.category == "RACE_A" } ?: up.firstOrNull { it.category == "RACE_B" }
    }

    // ---------------------------------------------------------------- 1. verdict

    data class Call(val status: Status, val sentence: String, val rate: Double, val ratio: Double, val form: Double, val taper: Race?)

    fun call(s: Snapshot): Call? {
        if (s.days.isEmpty()) return null
        val rate = weeklyRate(s.days) ?: return null
        val today = todayOf(s)
        val d = s.days.last()
        val ratio = if (d.ctl > 0) d.atl / d.ctl else 1.0
        val taper = taperRace(s, today)
        val status = classify(rate, taper != null)
        val move = if (rate >= 0) "rose ${f1(rate)}" else "slipped ${f1(-rate)}"
        val formTxt = "Form ${signed(d.form)}, ${FormZone.of(d.form).label.lowercase(Locale.ROOT)}"
        val fat = "Fatigue is ${fatigueWord(ratio)} ($formTxt)."
        val left = taper?.let { daysTo(it, today) }
        val sentence = when (status) {
            Status.TOO_FAST -> "Fitness $move this week, more than the +${TOO_FAST.roundToInt()} a week ceiling. $fat"
            Status.TAPER_OK -> "Fitness $move this week, as expected in the taper for ${taper?.name?.take(24)} (${left} days to go). $fat"
            Status.TAPER_BUILDING -> "Fitness $move this week, but you are ${left} days from ${taper?.name?.take(24)}: a taper should hold or ease it. $fat"
            Status.TAPER_DROPPING -> "Fitness $move this week, faster than a taper needs, with ${left} days to ${taper?.name?.take(24)}. $fat"
            else -> "Fitness $move this week. $fat"
        }
        return Call(status, sentence, rate, ratio, d.form, taper)
    }

    // ---------------------------------------------------------------- 2. short term

    data class ShortTerm(
        val monday: LocalDate, val sunday: LocalDate,
        val done: Int, val planned: Int,
        /** Load this week for +4 Fitness, or null when last Sunday's Fitness isn't known. */
        val needed: Int?,
        val ratio: Double, val form: Double, val formSunday: Double?,
        /** Projected change over next week from the plan. */
        val nextCtl: Double?, val nextAtl: Double?,
    ) {
        /** Positive = short of the target. */
        val gap: Int? get() = needed?.let { it - done - planned }
    }

    private fun plannedLoad(w: Workout) = if (w.load > 0) w.load else w.plannedLoad

    fun shortTerm(s: Snapshot): ShortTerm? {
        if (s.days.isEmpty()) return null
        val today = todayOf(s)
        val mon = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val sun = mon.plusDays(6)
        val inWeek = s.week.filter { w -> dateOf(w)?.let { it >= mon && it <= sun } ?: true }
        val dayLoads = s.days.filter { d -> dayOf(d)?.let { it >= mon && it <= today } ?: false }.mapNotNull { it.load }
        val done = if (dayLoads.isNotEmpty()) dayLoads.sum().roundToInt() else inWeek.filter { it.status == WorkoutStatus.DONE }.sumOf { it.load }
        val planned = inWeek.filter { it.status != WorkoutStatus.DONE }.sumOf { plannedLoad(it) }
        val base = s.days.firstOrNull { it.date == mon.minusDays(1).toString() }
        val needed = base?.let { weeklyLoadFor(it.ctl, BUILD_TARGET).roundToInt() }
        val d = s.days.last()
        val ratio = if (d.ctl > 0) d.atl / d.ctl else 1.0
        val projByDate = s.proj.associateBy { it.date }
        fun at(date: LocalDate): Day? = if (date == today) d else projByDate[date.toString()]
        val thisSun = at(sun)
        val nextSun = at(sun.plusDays(7))
        return ShortTerm(
            mon, sun, done, planned, needed, ratio, d.form, thisSun?.form,
            if (thisSun != null && nextSun != null) nextSun.ctl - thisSun.ctl else null,
            if (thisSun != null && nextSun != null) nextSun.atl - thisSun.atl else null,
        )
    }

    // ---------------------------------------------------------------- 3. long term: target band and chart data

    /** Per-day target band from [start] to [end] inclusive. */
    class Band(
        val start: LocalDate, val end: LocalDate, val race: Race?, val taperStart: LocalDate?,
        val lo: List<Double>, val hi: List<Double>,
        /** Race-day target: today's Fitness + 4 a week for the weeks left before the taper starts. */
        val cap: Double,
    )

    fun band(s: Snapshot): Band? {
        if (s.days.isEmpty()) return null
        val today = todayOf(s)
        val race = targetRace(s, today)
        val raceDate = race?.let { runCatching { LocalDate.parse(it.date) }.getOrNull() }
        val end = raceDate ?: today.plusWeeks(DEFAULT_WEEKS.toLong())
        var start = today.minusWeeks(HISTORY_WEEKS.toLong())
        val first = dayOf(s.days.first())
        if (first != null && first.isAfter(start)) start = first
        val startCtl = s.days.firstOrNull { (dayOf(it) ?: LocalDate.MIN) >= start }?.ctl ?: s.days.first().ctl
        val nowCtl = s.days.last().ctl
        val taperStart = if (race != null && raceDate != null) raceDate.minusDays(taperDays(race.category).toLong()) else null
        val buildEnd = taperStart ?: end
        val weeksLeft = maxOf(0L, ChronoUnit.DAYS.between(today, buildEnd)) / 7.0
        val cap = nowCtl + BUILD_TARGET * weeksLeft
        val n = ChronoUnit.DAYS.between(start, end).toInt()
        if (n < 1) return null
        val lo = ArrayList<Double>(); val hi = ArrayList<Double>()
        for (i in 0..n) {
            val d = start.plusDays(i.toLong())
            val t = i / 7.0
            var l = minOf(startCtl + BUILD_LO * t, cap)
            var h = minOf(startCtl + BUILD_HI * t, cap)
            if (taperStart != null && raceDate != null && d.isAfter(taperStart)) {
                val span = maxOf(1L, ChronoUnit.DAYS.between(taperStart, raceDate)).toDouble()
                val p = (ChronoUnit.DAYS.between(taperStart, d) / span).coerceIn(0.0, 1.0)
                l *= 1 - (1 - TAPER_END_LO) * p
                h *= 1 - (1 - TAPER_END_HI) * p
            }
            lo += l; hi += h
        }
        return Band(start, end, race, taperStart, lo, hi, cap)
    }

    private fun durText(sec: Int): String = dur((sec / 60.0).roundToInt())

    /** The one-line reading under the chart: weeks below the build rate, and the long-run trend over the last 4 weeks. */
    fun reading(s: Snapshot): String {
        if (s.days.isEmpty()) return ""
        val today = todayOf(s)
        val byDate = s.days.associateBy { it.date }
        var known = 0; var below = 0
        for (k in 0..3) {
            val end = byDate[today.minusDays(7L * k).toString()] ?: continue
            val beg = byDate[today.minusDays(7L * k + 7).toString()] ?: continue
            known++
            if (end.ctl - beg.ctl < BUILD_LO) below++
        }
        val parts = ArrayList<String>()
        if (known > 0) parts += "$below of the last $known weeks below the +${BUILD_LO.roundToInt()} a week build rate"
        // longest run in each of the last 4 weeks
        val cutoff = today.minusDays(27).toString()
        val longs = s.runs.filter { it.date >= cutoff && it.date <= today.toString() && Zones.isLong(it.name, it.date, it.seconds) }
        val perWeek = sortedMapOf<Int, Int>()
        for (r in longs) {
            val ago = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(r.date), today).toInt() }.getOrNull() ?: continue
            val wk = ago / 7
            perWeek[wk] = maxOf(perWeek[wk] ?: 0, r.seconds)
        }
        if (perWeek.size >= 2) {
            val oldest = perWeek.getValue(perWeek.lastKey()); val newest = perWeek.getValue(perWeek.firstKey())
            val diffMin = (newest - oldest) / 60
            parts += when {
                diffMin <= -5 -> "long runs down from ${durText(oldest)} to ${durText(newest)}"
                diffMin >= 5 -> "long runs up from ${durText(oldest)} to ${durText(newest)}"
                else -> "long runs steady at about ${durText(newest)}"
            }
        } else if (perWeek.size == 1) parts += "one long run of ${durText(perWeek.getValue(perWeek.firstKey()))} in the last 4 weeks"
        return parts.joinToString("; ").replaceFirstChar { it.uppercase() }
    }

    // ---------------------------------------------------------------- 4. suggestions

    sealed class Change {
        abstract val date: LocalDate
        abstract val loadDelta: Int
        /** Lengthen (or shorten) a planned run. [fromSec] is its exact planned moving time, kept for Undo. */
        data class Resize(val eventId: Long, val name: String, override val date: LocalDate, val fromSec: Int, val fromMin: Int, val toMin: Int, override val loadDelta: Int) : Change()
        data class AddDouble(override val date: LocalDate, val minutes: Int, val startMin: Int, val forName: String, override val loadDelta: Int) : Change()
    }

    fun describe(c: Change): String = when (c) {
        is Change.Resize -> "${Swaps.dayLabel(c.date)}: ${c.name}, ${c.fromMin} → ${c.toMin} min (${signedInt(c.toMin - c.fromMin)} min, about ${signedInt(c.loadDelta)} load)"
        is Change.AddDouble -> "${Swaps.dayLabel(c.date)} ${Swaps.hhmm(c.startMin)}: add Easy run (double), ${c.minutes} min (about ${signedInt(c.loadDelta)} load)"
    }

    /** [changes] empty = advice only (the app never deletes your events, so skipping a session is yours to do). */
    data class Suggestion(
        val id: String,
        /** 0 = the rest of this week, 1 = next week. */
        val week: Int,
        val title: String,
        val detail: String,
        val loadDelta: Int,
        val changes: List<Change>,
    ) {
        val applicable: Boolean get() = changes.isNotEmpty()
    }

    private fun allWorkouts(s: Snapshot): List<Workout> =
        s.week + s.ahead.filter { a -> s.week.none { it.date == a.date && it.name == a.name && it.eventId == a.eventId } }

    private fun rate(w: Workout): Double = if (w.load > 0 && w.seconds > 0) w.load * 60.0 / w.seconds else 0.0

    private fun median(v: List<Double>): Double? {
        if (v.isEmpty()) return null
        val x = v.sorted(); val m = x.size / 2
        return if (x.size % 2 == 1) x[m] else (x[m - 1] + x[m]) / 2
    }

    private fun startMinOf(w: Workout): Int? = w.startLocal?.takeIf { it.length >= 16 && it[10] == 'T' }?.let {
        val h = it.substring(11, 13).toIntOrNull(); val m = it.substring(14, 16).toIntOrNull()
        if (h != null && m != null) h * 60 + m else null
    }

    private enum class Mode { ADD, TRIM }

    private class Cand(val w: Workout, val date: LocalDate, val kind: Swaps.Kind, val minutes: Int)

    /**
     * Up to [MAX_SUGGESTIONS] per week (this week's remaining days, then next week), smallest change first.
     * Behind: lengthen the long run, add a 60 % double on an easy or workout day, lengthen easy runs.
     * Building too fast or Fatigue over 140 % of Fitness: shorten easy runs and the long run, skip a double, keep SubT controlled.
     * On track or tapering as planned: nothing to change.
     */
    fun suggestions(s: Snapshot): List<Suggestion> {
        val c = call(s) ?: return emptyList()
        val mode = when {
            c.ratio > RATIO_RED || c.status == Status.TOO_FAST || c.status == Status.TAPER_BUILDING -> Mode.TRIM
            c.status == Status.BEHIND || c.status == Status.SLIGHTLY_BEHIND -> Mode.ADD
            else -> return emptyList()
        }
        val today = todayOf(s)
        val all = allWorkouts(s)
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val out = ArrayList<Suggestion>()
        for (wk in 0..1) {
            val mon = monday.plusDays(7L * wk); val sun = mon.plusDays(6)
            val first = if (wk == 0) today else mon
            val weekAll = all.filter { w -> dateOf(w)?.let { it >= mon && it <= sun } ?: false }
            val slotsByDate = weekAll.filter { it.status != WorkoutStatus.DONE }
                .mapNotNull { w -> dateOf(w)?.let { it to SwapFlow.sessionOf(w) } }
                .groupBy({ it.first }, { it.second })
            val slots = slotsByDate.map { (d, ss) -> Swaps.Slot(d, ss) }
            val runs = weekAll.filter { it.status == WorkoutStatus.PLANNED && it.eventId != null && it.seconds > 0 }
                .mapNotNull { w ->
                    val d = dateOf(w) ?: return@mapNotNull null
                    val k = SwapFlow.kindOf(w)
                    if (d.isBefore(first) || k == Swaps.Kind.OTHER || k == Swaps.Kind.RACE) null
                    else Cand(w, d, k, SwapFlow.minutesOf(w))
                }.sortedBy { it.date }
            val easyRate = median(weekAll.filter { it.status != WorkoutStatus.RACE && SwapFlow.kindOf(it) == Swaps.Kind.EASY }.map { rate(it) }.filter { it > 0 }) ?: DEFAULT_EASY_RATE
            fun rateOf(x: Cand) = rate(x.w).takeIf { it > 0 } ?: easyRate
            val week = ArrayList<Suggestion>()

            if (mode == Mode.ADD) {
                // lengthen easy runs by 10 min (up to 3, none already long)
                val easy = runs.filter { it.kind == Swaps.Kind.EASY && it.minutes <= 80 }.take(3)
                if (easy.isNotEmpty()) {
                    val ch = easy.map { Change.Resize(it.w.eventId!!, it.w.name, it.date, it.w.seconds, it.minutes, it.minutes + 10, (10 * rateOf(it)).roundToInt()) }
                    val load = ch.sumOf { it.loadDelta }
                    week += Suggestion("w$wk-easy-up", wk, "Extend ${if (ch.size == 1) "an easy run" else "${ch.size} easy runs"} by 10 min",
                        ch.joinToString(", ") { Swaps.dayLabel(it.date) } + " · about ${signedInt(load)} load", load, ch)
                }
                // a double (60 % of the week's median easy run) on a day with one easy or workout run, never the long-run day
                val dblFrom = maxOf(first, today.plusDays(1))
                val dblDays = runs.filter { r -> r.date >= dblFrom && (r.kind == Swaps.Kind.EASY || r.kind == Swaps.Kind.WORKOUT) }
                    .filter { r -> slotsByDate[r.date].orEmpty().count { it.kind != Swaps.Kind.OTHER } == 1 }
                    .sortedWith(compareBy({ it.kind != Swaps.Kind.EASY }, { it.date }))
                dblDays.firstOrNull()?.let { r ->
                    val m = Swaps.doubleMinutes(slots, mon)
                    val load = (m * easyRate).roundToInt()
                    val start = if ((startMinOf(r.w) ?: 0) >= 13 * 60) 7 * 60 else Swaps.DEFAULT_DOUBLE_START
                    week += Suggestion("w$wk-double", wk, "Add a ${m} min double on ${Swaps.dayLabel(r.date)}",
                        "Easy, 60% of your median easy run · about ${signedInt(load)} load", load,
                        listOf(Change.AddDouble(r.date, m, start, r.w.name, load)))
                }
                // long run +10 (slightly behind) or +20 (behind) minutes
                runs.firstOrNull { it.kind == Swaps.Kind.LONG }?.let { r ->
                    val add = if (c.status == Status.BEHIND) 20 else 10
                    if (r.minutes + add <= 180) {
                        val load = (add * (rate(r.w).takeIf { it > 0 } ?: easyRate)).roundToInt()
                        week += Suggestion("w$wk-long-up", wk, "Extend the long run by $add min",
                            "${Swaps.dayLabel(r.date)}: ${dur(r.minutes)} → ${dur(r.minutes + add)} · about ${signedInt(load)} load", load,
                            listOf(Change.Resize(r.w.eventId!!, r.w.name, r.date, r.w.seconds, r.minutes, r.minutes + add, load)))
                    }
                }
            } else {
                val easy = runs.filter { it.kind == Swaps.Kind.EASY && it.minutes >= 40 }.take(3)
                if (easy.isNotEmpty()) {
                    val ch = easy.map { Change.Resize(it.w.eventId!!, it.w.name, it.date, it.w.seconds, it.minutes, it.minutes - 10, -(10 * rateOf(it)).roundToInt()) }
                    val load = ch.sumOf { it.loadDelta }
                    week += Suggestion("w$wk-easy-down", wk, "Shorten ${if (ch.size == 1) "an easy run" else "${ch.size} easy runs"} by 10 min",
                        ch.joinToString(", ") { Swaps.dayLabel(it.date) } + " · about ${signedInt(load)} load", load, ch)
                }
                runs.firstOrNull { it.kind == Swaps.Kind.LONG && it.minutes >= 90 }?.let { r ->
                    val cut = if (c.ratio > RATIO_RED) 15 else 10
                    val load = -(cut * (rate(r.w).takeIf { it > 0 } ?: easyRate)).roundToInt()
                    week += Suggestion("w$wk-long-down", wk, "Shorten the long run by $cut min",
                        "${Swaps.dayLabel(r.date)}: ${dur(r.minutes)} → ${dur(r.minutes - cut)} · about ${signedInt(load)} load", load,
                        listOf(Change.Resize(r.w.eventId!!, r.w.name, r.date, r.w.seconds, r.minutes, r.minutes - cut, load)))
                }
                // advice only: a planned double is yours to skip, the app never deletes events
                val dbl = slotsByDate.entries.filter { (d, ss) -> d >= first && ss.count { it.kind != Swaps.Kind.OTHER } >= 2 }
                    .mapNotNull { (d, ss) -> ss.filter { it.kind == Swaps.Kind.EASY }.minByOrNull { it.minutes }?.let { d to it } }
                    .minByOrNull { it.first }
                if (dbl != null) {
                    val load = -(dbl.second.minutes * easyRate).roundToInt()
                    week += Suggestion("w$wk-skip-double", wk, "Skip the double on ${Swaps.dayLabel(dbl.first)}",
                        "${dbl.second.minutes} min · about ${signedInt(load)} load · advice only, remove it yourself", load, emptyList())
                }
                runs.firstOrNull { it.kind == Swaps.Kind.WORKOUT }?.let { r ->
                    week += Suggestion("w$wk-subt", wk, "Keep ${r.w.name.take(26)} controlled",
                        "${Swaps.dayLabel(r.date)}: the low end of the power range, no extra reps · advice only", 0, emptyList())
                }
            }
            out += week.sortedWith(compareBy({ !it.applicable }, { abs(it.loadDelta) })).take(MAX_SUGGESTIONS)
        }
        return out
    }

    // ---------------------------------------------------------------- editing a planned session's steps

    private val STEP = Regex("""^(\s*-\s*)(\d+)m(\b.*)$""")

    /**
     * The workout text after moving a session from [fromMin] to [toMin] minutes. intervals.icu counts load from steps written
     * as "- 35m 61-65%": one step is resized, several get the extra minutes as a last easy step (or lose them from the last
     * step that can), and text without steps becomes a single easy step. Null when the minutes can't be taken off.
     */
    fun editSteps(desc: String?, fromMin: Int, toMin: Int, easyPct: String): String? {
        val delta = toMin - fromMin
        val lines = (desc ?: "").lines()
        val idx = lines.indices.filter { STEP.matches(lines[it]) }
        fun withMinutes(line: String, m: Int): String {
            val mt = STEP.matchEntire(line)!!
            return mt.groupValues[1] + m + "m" + mt.groupValues[3]
        }
        return when {
            idx.isEmpty() -> "- ${toMin}m $easyPct" + (if (desc.isNullOrBlank()) "" else "\n\n" + desc.trim())
            idx.size == 1 -> {
                val old = STEP.matchEntire(lines[idx[0]])!!.groupValues[2].toInt()
                lines.mapIndexed { i, l -> if (i == idx[0]) withMinutes(l, (old + delta).coerceAtLeast(5)) else l }.joinToString("\n")
            }
            delta > 0 -> lines.toMutableList().apply { add(idx.last() + 1, "- ${delta}m $easyPct") }.joinToString("\n")
            else -> {
                val cut = -delta
                val pick = idx.lastOrNull { STEP.matchEntire(lines[it])!!.groupValues[2].toInt() > cut + 4 } ?: return null
                val old = STEP.matchEntire(lines[pick])!!.groupValues[2].toInt()
                lines.mapIndexed { i, l -> if (i == pick) withMinutes(l, old - cut) else l }.joinToString("\n")
            }
        }
    }

    // ---------------------------------------------------------------- 5. hand-off to the Claude app

    fun readout(s: Snapshot): Coach.Readout? {
        val c = call(s) ?: return null
        val st = shortTerm(s)
        val d = s.days.last()
        val lvl = when (c.status.color) { GOOD -> Coach.Level.GOOD; WARN -> Coach.Level.WATCH; else -> Coach.Level.ACT }
        val lines = ArrayList<Coach.Line>()
        lines += Coach.Line(lvl, "${c.status.label}: ${c.sentence}")
        if (st != null) {
            lines += Coach.Line(Coach.Level.INFO, "This week: ${st.done} load done, ${st.planned} planned" + (st.needed?.let { ", about $it needed for +${BUILD_TARGET.roundToInt()} Fitness" } ?: ""))
            lines += Coach.Line(when { c.ratio > RATIO_RED -> Coach.Level.ACT; c.ratio > RATIO_AMBER -> Coach.Level.WATCH; else -> Coach.Level.GOOD },
                "Fatigue is ${(c.ratio * 100).roundToInt()}% of Fitness")
        }
        val race = targetRace(s)
        val data = buildString {
            appendLine("Date: ${todayOf(s)}")
            appendLine("Verdict: ${c.status.label}. ${c.sentence}")
            appendLine("Fitness ${d.ctl.roundToInt()}, Fatigue ${d.atl.roundToInt()}, Form ${signed(d.form)}; Fitness change in the last 7 days ${f1(c.rate)}")
            appendLine("Target build rate: +${BUILD_LO.roundToInt()} to +${BUILD_HI.roundToInt()} Fitness a week (on track); over +${TOO_FAST.roundToInt()} is too fast")
            if (st != null) {
                appendLine("Load this week: ${st.done} done, ${st.planned} planned still to do, ${st.needed ?: "unknown"} needed for +${BUILD_TARGET.roundToInt()} Fitness")
                st.formSunday?.let { appendLine("Form projected for Sunday: ${signed(it)}") }
                if (st.nextCtl != null && st.nextAtl != null) appendLine("Next week from the plan: Fitness ${if (st.nextCtl >= 0) "+" else ""}${f1(st.nextCtl)}, Fatigue ${if (st.nextAtl >= 0) "+" else ""}${f1(st.nextAtl)}")
            }
            race?.let { appendLine("Target race: ${it.name} (${it.category}) on ${it.date}" + (it.projCtl?.let { p -> ", projected Fitness ${p.roundToInt()}" } ?: "")) }
            val rd = reading(s); if (rd.isNotEmpty()) appendLine("Reading: $rd")
            val sg = suggestions(s)
            if (sg.isNotEmpty()) appendLine("App suggestions: " + sg.joinToString("; ") { it.title })
        }.trim()
        return Coach.Readout("Fitness & fatigue", lines, data, listOf(
            "Am I on track for ${race?.name ?: "my next block"}, and what should I change this week?",
            "Is my fatigue under control for the load I am carrying?",
        ))
    }
}

/** Writes [FitnessPlan] suggestions to intervals.icu (only from the confirm dialog) and undoes them. Never deletes your events. */
object FitnessFlow {
    class Ext(val id: Long, val fromSec: Int, val fromDesc: String?, val toSec: Int)
    class UndoRec(val day: String, val label: String, val resized: List<Ext>, val created: List<Long>) {
        fun toJson(): String = JSONObject().put("day", day).put("label", label)
            .put("resized", JSONArray().apply { resized.forEach { put(JSONObject().put("id", it.id).put("fromSec", it.fromSec).put("fromDesc", it.fromDesc ?: JSONObject.NULL).put("toSec", it.toSec)) } })
            .put("created", JSONArray().apply { created.forEach { put(it) } }).toString()
        companion object {
            fun fromJson(s: String?): UndoRec? = runCatching {
                val o = JSONObject(s ?: return null)
                val r = o.getJSONArray("resized"); val c = o.getJSONArray("created")
                UndoRec(o.getString("day"), o.optString("label"),
                    (0 until r.length()).map { val x = r.getJSONObject(it); Ext(x.getLong("id"), x.getInt("fromSec"), if (x.isNull("fromDesc")) null else x.getString("fromDesc"), x.getInt("toSec")) },
                    (0 until c.length()).map { c.getLong(it) })
            }.getOrNull()
        }
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences("training_widget", Context.MODE_PRIVATE)

    /** What Undo can still reverse: only on the day it was applied. */
    fun undoRec(ctx: Context): UndoRec? =
        UndoRec.fromJson(sp(ctx).getString("fitness_undo", null))?.takeIf { it.day == LocalDate.now().toString() }

    fun apply(ctx: Context, sug: FitnessPlan.Suggestion): String {
        val creds = Prefs.credentials(ctx) ?: throw IllegalStateException("Connect intervals.icu first")
        if (!sug.applicable) throw IllegalStateException("This one is advice only")
        val api = IcuApi(creds)
        // Same easy power range as this week's own easy run (default 61-65 % of CP), so intervals.icu counts the load
        val easyPct = run {
            val ref = RunWindows.weekRuns(ctx).firstOrNull { it.eventId != null && it.status != WorkoutStatus.DONE && Weather.typeOf(it) == Weather.RunType.EASY }
            val desc = ref?.eventId?.let { api.eventDescription(it) }
            desc?.let { Regex("""(\d{2,3})\s*-\s*(\d{2,3})\s*%""").find(it) }?.let { "${it.groupValues[1]}-${it.groupValues[2]}%" } ?: "61-65%"
        }
        val resized = ArrayList<Ext>(); val created = ArrayList<Long>()
        try {
            for (ch in sug.changes) when (ch) {
                is FitnessPlan.Change.Resize -> {
                    val old = api.eventDescription(ch.eventId)
                    val text = FitnessPlan.editSteps(old, ch.fromMin, ch.toMin, easyPct) ?: throw IllegalStateException("Couldn't change the steps of ${ch.name}")
                    api.updateEventDuration(ch.eventId, ch.toMin * 60, text)
                    resized += Ext(ch.eventId, ch.fromSec, old, ch.toMin * 60)
                }
                is FitnessPlan.Change.AddDouble -> created += api.createRunEvent(ch.date.toString(), ch.startMin, "Easy run (double)", ch.minutes,
                    "- ${ch.minutes}m $easyPct\n\nAdded by Training Today (double to build Fitness, after ${ch.forName})")
            }
        } catch (e: Exception) {
            for (r in resized.reversed()) runCatching { api.updateEventDuration(r.id, r.fromSec, r.fromDesc ?: "") }
            for (id in created) runCatching { api.deleteEvent(id) }
            throw e
        }
        sp(ctx).edit().putString("fitness_undo", UndoRec(LocalDate.now().toString(), sug.title, resized, created).toJson()).apply()
        Refresh.now(ctx.applicationContext, replace = true)
        return "Applied in intervals.icu: ${resized.size} changed, ${created.size} added"
    }

    /** Puts durations and steps back and deletes only the events the apply created. */
    fun undo(ctx: Context): String {
        val rec = undoRec(ctx) ?: throw IllegalStateException("Nothing to undo")
        val creds = Prefs.credentials(ctx) ?: throw IllegalStateException("Connect intervals.icu first")
        val api = IcuApi(creds)
        var failed = 0
        for (r in rec.resized.reversed()) try { api.updateEventDuration(r.id, r.fromSec, r.fromDesc ?: "") } catch (e: Exception) { failed++ }
        for (id in rec.created) try { api.deleteEvent(id) } catch (e: Exception) { failed++ }
        if (failed == 0) sp(ctx).edit().remove("fitness_undo").apply()
        Refresh.now(ctx.applicationContext, replace = true)
        if (failed > 0) throw java.io.IOException("$failed change(s) couldn't be undone; try again")
        return "Undone: everything is back as it was"
    }
}
