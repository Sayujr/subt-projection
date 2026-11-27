package dev.subt.trainingwidget

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Missed-session swaps: a pure engine (no Android) that turns "this run was missed (or will be)" into 1-3 ways to fit
 * it in, following docs/swaps-spec.md. Nothing here writes anywhere: it only proposes [Option]s made of [Change]s.
 */
object Swaps {
    enum class Kind { EASY, WORKOUT, LONG, TT, RACE, OTHER }

    /** One planned session on a day. [OTHER] (bike, gym, ...) is ignored by every rule. */
    data class Session(val eventId: Long?, val name: String, val kind: Kind, val minutes: Int, val startLocal: String? = null)

    data class Slot(val date: LocalDate, val sessions: List<Session>)

    /** Good, non-overlapping run windows on a day: [count] 0..2 and their start times in minutes after midnight (earliest first). */
    data class DayWin(val count: Int, val times: List<Int> = emptyList())

    const val DEFAULT_DOUBLE_START = 16 * 60
    private const val DEFAULT_EASY_START = 7 * 60

    sealed class Change {
        /** The day this change lands on. */
        abstract val date: LocalDate

        data class Move(val eventId: Long, val name: String, val kind: Kind, val from: LocalDate, val to: LocalDate, val startLocal: String?) : Change() {
            override val date: LocalDate get() = to
        }
        data class AddEasy(override val date: LocalDate, val minutes: Int, val startMin: Int = DEFAULT_EASY_START, val name: String = "Easy run") : Change()
        data class AddDouble(override val date: LocalDate, val minutes: Int, val startMin: Int, val forName: String) : Change()
        data class AddCopy(override val date: LocalDate, val minutes: Int, val name: String, val startMin: Int) : Change()
        data class LeaveInPlace(val eventId: Long?, val name: String, override val date: LocalDate, val note: String = "not deleted: it stays in intervals.icu and shows as not done") : Change()
    }

    enum class Tag { PLAN, MOVED, ADDED, DOUBLE, SKIP, MISSED }

    /** One box in the before/after grid. [startMin] is set for doubles. */
    data class Cell(val kind: Kind, val minutes: Int, val tag: Tag, val startMin: Int? = null)

    data class Row(val date: LocalDate, val before: List<Cell>, val after: List<Cell>)

    data class Option(
        val id: String,
        val title: String,
        val summary: List<String>,
        val changes: List<Change>,
        val rows: List<Row>,
        val moves: Int,
        val score: Double,
    )

    enum class Trigger { MISSED_YESTERDAY, MISSED_PASSED, MISSED_LATE, AT_RISK, TIGHT, MANUAL }

    // ================================================================ small helpers

    private fun isWk(k: Kind) = k == Kind.WORKOUT || k == Kind.LONG
    private fun isHard(k: Kind) = k == Kind.WORKOUT || k == Kind.LONG || k == Kind.TT || k == Kind.RACE
    private fun isFixed(k: Kind) = k == Kind.TT || k == Kind.RACE

    private val DAY_FMT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
    fun dayLabel(d: LocalDate): String = d.format(DAY_FMT)
    fun hhmm(min: Int): String = String.format(Locale.US, "%02d:%02d", min / 60, min % 60)

    /** The last day changes may reach: Mon-Fri misses this Sunday; Sat/Sun misses next Sunday. */
    fun horizon(d0: LocalDate): LocalDate {
        val sun = d0.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        return if (d0.dayOfWeek == DayOfWeek.SATURDAY || d0.dayOfWeek == DayOfWeek.SUNDAY) sun.plusDays(7) else sun
    }

    private fun median(v: List<Int>): Double? {
        if (v.isEmpty()) return null
        val s = v.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m].toDouble() else (s[m - 1] + s[m]) / 2.0
    }

    private fun weekMedianEasy(slots: Map<LocalDate, List<Session>>, d0: LocalDate): Double? {
        val mon = d0.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val mins = (0..6).flatMap { slots[mon.plusDays(it.toLong())].orEmpty() }.filter { it.kind == Kind.EASY && it.minutes > 0 }.map { it.minutes }
        return median(mins)
    }

    /** A double is 60% of the week's median planned easy run, rounded to 5 minutes (30 when no easy run is planned). */
    fun doubleMinutes(slots: List<Slot>, d0: LocalDate): Int {
        val med = weekMedianEasy(group(slots), d0) ?: return 30
        return (((med * 0.6) / 5.0).roundToInt() * 5).coerceAtLeast(5)
    }

    private fun group(slots: List<Slot>): Map<LocalDate, List<Session>> {
        val m = LinkedHashMap<LocalDate, List<Session>>()
        for (s in slots) m[s.date] = m[s.date].orEmpty() + s.sessions
        return m
    }

    private fun primary(slots: Map<LocalDate, List<Session>>, d: LocalDate, skip: Session? = null): Session? =
        slots[d].orEmpty().filter { it.kind != Kind.OTHER && (skip == null || it != skip) }.maxByOrNull { it.minutes }

    // ================================================================ engine

    private enum class Strategy { GREEDY, OWN }

    private class Item(val s: Session, val orig: LocalDate)

    private class Ctx(
        val slots: Map<LocalDate, List<Session>>,
        val d0: LocalDate,
        val missed: Session,
        val today: LocalDate,
        val win: (LocalDate) -> DayWin,
        val horizon: LocalDate,
        val dbl: Int,
    )

    private class Cas(
        val target: Map<LocalDate, Item?>,
        val limit: LocalDate,
        val spilled: List<Item>,
        val pushedEasy: List<Item>,
        val blockedAdd: List<LocalDate>,
    )

    private fun place(c: Ctx, d1: LocalDate, limit: LocalDate, forced: Set<LocalDate>): Cas {
        val pending = ArrayDeque<Item>()
        pending.addLast(Item(c.missed, c.d0))
        val target = LinkedHashMap<LocalDate, Item?>()
        val pushed = ArrayList<Item>()
        val adds = ArrayList<LocalDate>()
        var t = d1
        while (!t.isAfter(limit)) {
            val o = primary(c.slots, t, c.missed)?.let { Item(it, t) }
            if (pending.isEmpty()) {
                target[t] = o
                t = t.plusDays(1)
                continue
            }
            val cand = pending.first()
            val prev = target[t.minusDays(1)]
            val candWk = isWk(cand.s.kind)
            val blocked = candWk && (t in forced || (prev != null && isHard(prev.s.kind) && prev.orig.plusDays(1) != cand.orig))
            if (t == limit && o != null && isWk(o.s.kind)) {
                // the last day keeps its workout / long run: what was queued spills instead
                target[t] = o
            } else if (blocked) {
                if (o == null) target[t] = null
                else if (!isWk(o.s.kind)) target[t] = o
                else {
                    pending.addLast(o)
                    target[t] = null
                    adds += t
                }
            } else {
                target[t] = cand
                pending.removeFirst()
                if (o != null) {
                    pending.addLast(o)
                    if (o.s.kind == Kind.EASY) pushed += o
                }
            }
            t = t.plusDays(1)
        }
        return Cas(target, limit, pending.toList(), pushed, adds)
    }

    /** A workout / long run newly next to another hard day (not a pair that was already adjacent): the day to block, or null. */
    private fun violation(c: Ctx, r: Cas, d1: LocalDate): LocalDate? {
        fun itemAt(t: LocalDate): Item? =
            if (!t.isBefore(d1) && !t.isAfter(r.limit)) r.target[t] else primary(c.slots, t, c.missed)?.let { Item(it, t) }
        var t = d1
        while (!t.isAfter(r.limit)) {
            val x = itemAt(t)
            val y = itemAt(t.plusDays(1))
            if (x != null && y != null && isHard(x.s.kind) && isHard(y.s.kind) && (isWk(x.s.kind) || isWk(y.s.kind)) &&
                x.orig.plusDays(1) != y.orig) {
                val xMoved = x.orig != t
                val yMoved = y.orig != t.plusDays(1)
                if (xMoved && isWk(x.s.kind)) return t
                if (yMoved && isWk(y.s.kind)) return t.plusDays(1)
            }
            t = t.plusDays(1)
        }
        return null
    }

    private fun cascade(c: Ctx, forcedIn: Set<LocalDate>): Cas {
        val d1 = c.d0.plusDays(1)
        var fixedFirst: LocalDate? = null
        var t = d1
        while (!t.isAfter(c.horizon)) {
            val p = primary(c.slots, t, c.missed)
            if (p != null && isFixed(p.kind)) { fixedFirst = t; break }
            t = t.plusDays(1)
        }
        val limit = fixedFirst?.minusDays(1) ?: c.horizon
        val forced = HashSet<LocalDate>(forcedIn)
        var r = place(c, d1, limit, forced)
        for (i in 0 until 14) {
            val v = violation(c, r, d1) ?: return r
            if (!forced.add(v)) return r
            r = place(c, d1, limit, forced)
        }
        return r
    }

    private fun startFor(c: Ctx, day: LocalDate): Int {
        val w = c.win(day)
        return if (w.times.size >= 2) w.times[1] else DEFAULT_DOUBLE_START
    }

    private fun easyMinutes(c: Ctx): Int = weekMedianEasy(c.slots, c.d0)?.let { ((it / 5.0).roundToInt() * 5).coerceAtLeast(10) } ?: 40

    private fun changesOf(c: Ctx, r: Cas, strategy: Strategy): List<Change>? {
        val d1 = c.d0.plusDays(1)
        val out = ArrayList<Change>()
        for ((t, item) in r.target) {
            if (item != null && item.orig != t) {
                val id = item.s.eventId ?: return null
                out += Change.Move(id, item.s.name, item.s.kind, item.orig, t, item.s.startLocal)
            }
        }
        for (s in r.spilled) out += Change.LeaveInPlace(s.s.eventId, s.s.name, s.orig)
        val easyDays = HashSet<LocalDate>()
        for ((t, item) in r.target) if (item != null && item.s.kind == Kind.EASY) easyDays += t
        val addDays = HashSet<LocalDate>()
        for (t in r.blockedAdd) if (t !in easyDays && addDays.add(t)) out += Change.AddEasy(t, easyMinutes(c), c.win(t).times.firstOrNull() ?: DEFAULT_EASY_START)
        for (s in r.spilled) {
            if (isWk(s.s.kind) && s.orig != c.d0 && s.orig !in easyDays && s.orig !in addDays) {
                addDays += s.orig
                out += Change.AddEasy(s.orig, easyMinutes(c), c.win(s.orig).times.firstOrNull() ?: DEFAULT_EASY_START)
            }
        }
        fun kindAfter(t: LocalDate): Kind? = when {
            t.isBefore(d1) -> null
            !t.isAfter(r.limit) -> r.target[t]?.s?.kind ?: if (t in addDays) Kind.EASY else null
            else -> primary(c.slots, t)?.kind
        }
        val first = maxOf(d1, c.today)
        fun eligible(t: LocalDate) = !t.isBefore(first) && !t.isAfter(c.horizon) && (kindAfter(t) == Kind.EASY || kindAfter(t) == Kind.WORKOUT)
        val used = HashSet<LocalDate>()
        for (e in r.pushedEasy) {
            val start = maxOf(first, if (strategy == Strategy.OWN) e.orig else r.pushedEasy.first().orig)
            var day: LocalDate? = null
            var t = start
            while (!t.isAfter(c.horizon)) {
                if (eligible(t) && t !in used && c.win(t).count >= 2) { day = t; break }
                t = t.plusDays(1)
            }
            if (day == null) {
                t = start
                while (!t.isAfter(c.horizon)) {
                    if (eligible(t) && t !in used && kindAfter(t) == Kind.EASY) { day = t; break }
                    t = t.plusDays(1)
                }
            }
            if (day != null) {
                used += day
                out += Change.AddDouble(day, c.dbl, startFor(c, day), c.missed.name)
            }
        }
        return out
    }

    // ================================================================ rows, text, scoring

    fun describe(ch: Change): String = when (ch) {
        is Change.Move -> "Move ${ch.name} from ${dayLabel(ch.from)} to ${dayLabel(ch.to)}"
        is Change.AddEasy -> "Add an easy run (${ch.minutes}′) on ${dayLabel(ch.date)}"
        is Change.AddDouble -> "Add a ${ch.minutes}′ double on ${dayLabel(ch.date)} at ${hhmm(ch.startMin)}"
        is Change.AddCopy -> "Add a copy of ${ch.name} on ${dayLabel(ch.date)}"
        is Change.LeaveInPlace -> "${ch.name} stays on ${dayLabel(ch.date)} (${ch.note})"
    }

    private fun rowsFor(c: Ctx, changes: List<Change>): List<Row> {
        val last = (changes.map { it.date } + c.horizon).max()
        val dates = ArrayList<LocalDate>()
        var t = c.d0
        while (!t.isAfter(last) && dates.size < 10) { dates += t; t = t.plusDays(1) }
        val before = HashMap<LocalDate, List<Cell>>()
        val after = HashMap<LocalDate, MutableList<Pair<Long?, Cell>>>()
        for (d in dates) {
            val runs = c.slots[d].orEmpty().filter { it.kind != Kind.OTHER }
            before[d] = runs.map { Cell(it.kind, it.minutes, if (d == c.d0 && it == c.missed) Tag.MISSED else Tag.PLAN) }
            after[d] = runs.map { it.eventId to Cell(it.kind, it.minutes, if (d == c.d0 && it == c.missed) Tag.MISSED else Tag.PLAN) }.toMutableList()
        }
        for (ch in changes) {
            val list = after[ch.date]
            when (ch) {
                is Change.Move -> {
                    val fl = after[ch.from]
                    if (ch.from != c.d0) fl?.removeAll { it.first == ch.eventId }
                    after[ch.to]?.add(ch.eventId to Cell(ch.kind, c.slots[ch.from].orEmpty().firstOrNull { it.eventId == ch.eventId }?.minutes ?: 0, Tag.MOVED))
                }
                is Change.AddEasy -> list?.add(null to Cell(Kind.EASY, ch.minutes, Tag.ADDED))
                is Change.AddDouble -> list?.add(null to Cell(Kind.EASY, ch.minutes, Tag.DOUBLE, ch.startMin))
                is Change.AddCopy -> list?.add(null to Cell(Kind.TT, ch.minutes, Tag.ADDED))
                is Change.LeaveInPlace -> {
                    val i = list?.indexOfFirst { it.first != null && it.first == ch.eventId } ?: -1
                    if (list != null && i >= 0 && list[i].second.tag != Tag.MISSED) list[i] = list[i].first to list[i].second.copy(tag = Tag.SKIP)
                }
            }
        }
        return dates.map { d -> Row(d, before[d].orEmpty(), after[d].orEmpty().map { it.second }) }
    }

    private fun score(c: Ctx, changes: List<Change>): Double {
        var s = 0.0
        for (ch in changes) when (ch) {
            is Change.Move -> { s += 10; if (isWk(ch.kind) && c.win(ch.to).count == 0) s += 50 }
            is Change.AddEasy -> s += 2
            is Change.AddDouble -> s += if (c.win(ch.date).count >= 2) -1.0 else 3.0
            is Change.AddCopy -> { s += 5; if (c.win(ch.date).count == 0) s += 50 }
            is Change.LeaveInPlace -> s += 1
        }
        return s
    }

    private fun option(c: Ctx, id: String, title: String, changes: List<Change>): Option {
        val moves = changes.count { it is Change.Move }
        return Option(id, title, changes.map { describe(it) }, changes, rowsFor(c, changes), moves, score(c, changes))
    }

    private fun sig(changes: List<Change>) = changes.map { it.toString() }.sorted().joinToString("|")

    // ================================================================ the options

    /**
     * 1-3 distinct options for the [missed] session planned on [d0] (a past day, today, or a future day for a planned miss),
     * best first. [slots] are the planned sessions around it; [win] gives each day's good windows (a missing forecast = 1).
     * Races never move: no options.
     */
    fun options(missed: Session, d0: LocalDate, slots: List<Slot>, win: (LocalDate) -> DayWin, today: LocalDate): List<Option> {
        if (missed.kind == Kind.RACE || missed.kind == Kind.OTHER) return emptyList()
        val map = group(slots)
        val c = Ctx(map, d0, missed, today, win, horizon(d0), doubleMinutes(slots, d0))
        val out = ArrayList<Option>()
        when (missed.kind) {
            Kind.EASY -> easyOptions(c, out)
            Kind.TT -> ttOptions(c, out)
            Kind.WORKOUT, Kind.LONG -> {
                if (missed.kind == Kind.WORKOUT && d0.dayOfWeek == DayOfWeek.THURSDAY) thursdayOption(c, out)
                else shiftOptions(c, out)
            }
            else -> Unit
        }
        val seen = HashSet<String>()
        return out.filter { it.changes.any { ch -> ch !is Change.LeaveInPlace } && seen.add(sig(it.changes)) }
            .sortedBy { it.score }.take(3)
    }

    private fun shiftOptions(c: Ctx, out: MutableList<Option>) {
        val r = cascade(c, emptySet())
        changesOf(c, r, Strategy.GREEDY)?.let { out += option(c, "shift", "Shift the week by a day", it) }
        changesOf(c, r, Strategy.OWN)?.let { out += option(c, "shift-own", "Shift the week, doubles on their own days", it) }
        // keep the missed session off days that have no good window
        val bad = HashSet<LocalDate>()
        var t = c.d0.plusDays(1)
        while (!t.isAfter(c.horizon)) { if (c.win(t).count == 0) bad += t; t = t.plusDays(1) }
        if (bad.isNotEmpty()) {
            val r2 = cascade(c, bad)
            changesOf(c, r2, Strategy.GREEDY)?.let { out += option(c, "shift-avoid", "Shift the week, skipping days with no good window", it) }
        }
        // swap with the next day's easy run (fewest moves)
        val d1 = c.d0.plusDays(1)
        val next = primary(c.slots, d1, c.missed)
        val after = primary(c.slots, d1.plusDays(1))
        val missedId = c.missed.eventId
        val nextId = next?.eventId
        if (!c.d0.isBefore(c.today) && !d1.isAfter(c.horizon) && next != null && next.kind == Kind.EASY && missedId != null && nextId != null &&
            (after == null || !isHard(after.kind))) {
            val ch = listOf<Change>(
                Change.Move(missedId, c.missed.name, c.missed.kind, c.d0, d1, c.missed.startLocal),
                Change.Move(nextId, next.name, next.kind, d1, c.d0, next.startLocal),
            )
            out += option(c, "swap", "Swap with ${dayLabel(d1)}'s easy run", ch)
        }
    }

    private fun thursdayOption(c: Ctx, out: MutableList<Option>) {
        val fri = c.d0.plusDays(1)
        val p = primary(c.slots, fri)
        if (p != null && isFixed(p.kind)) return
        if (fri.isAfter(c.horizon)) return
        val ch = listOf<Change>(
            Change.LeaveInPlace(c.missed.eventId, c.missed.name, c.d0),
            Change.AddDouble(fri, c.dbl, startFor(c, fri), c.missed.name),
        )
        out += option(c, "thursday", "Let it go, add a double on Friday", ch)
    }

    private fun easyOptions(c: Ctx, out: MutableList<Option>) {
        val first = maxOf(c.d0.plusDays(1), c.today)
        val days = ArrayList<LocalDate>()
        var t = first
        while (!t.isAfter(c.horizon)) {
            val p = primary(c.slots, t)
            if (p != null && (p.kind == Kind.EASY || p.kind == Kind.WORKOUT)) days += t
            t = t.plusDays(1)
        }
        val good = days.filter { c.win(it).count >= 2 }
        val chosen: List<LocalDate> = if (good.isNotEmpty()) {
            listOf(good.first()) + good.drop(1).sortedWith(compareByDescending<LocalDate> { c.win(it).count }.thenBy { it })
        } else {
            days.filter { primary(c.slots, it)?.kind == Kind.EASY }.take(1)
        }
        for ((i, d) in chosen.take(3).withIndex()) {
            val ch = listOf<Change>(Change.AddDouble(d, c.dbl, startFor(c, d), c.missed.name))
            out += option(c, "easy-$i", "Add a ${c.dbl}′ double on ${dayLabel(d)}", ch)
        }
    }

    private fun ttOptions(c: Ctx, out: MutableList<Option>) {
        var t = c.d0.plusDays(1)
        while (!t.isAfter(c.horizon)) {
            if (t.dayOfWeek == DayOfWeek.SATURDAY && !t.isBefore(c.today)) {
                val onDay = c.slots[t].orEmpty().filter { it.kind != Kind.OTHER }
                if (onDay.none { isFixed(it.kind) }) {
                    val ch = listOf<Change>(Change.AddCopy(t, c.missed.minutes, c.missed.name, c.win(t).times.firstOrNull() ?: 8 * 60))
                    out += option(c, "copy-${t}", "Add ${c.missed.name} on ${dayLabel(t)} (that day's workout stays)", ch)
                }
            }
            t = t.plusDays(1)
        }
    }

    // ================================================================ adjacency check (for tests and a safety net)

    /**
     * Pairs of days in an option's after-grid where a workout / long run sits next to another hard day, other than pairs
     * that were already adjacent in the plan. Empty for every option the engine builds.
     */
    fun adjacencyViolations(opt: Option, missed: Session, d0: LocalDate, slots: List<Slot>): List<Pair<LocalDate, LocalDate>> {
        val map = group(slots)
        val origDay = HashMap<Long, LocalDate>()
        for ((d, ss) in map) for (s in ss) s.eventId?.let { origDay[it] = d }
        val moved = opt.changes.filterIsInstance<Change.Move>().associateBy { it.eventId }
        fun hardOn(d: LocalDate): List<Pair<Kind, LocalDate>> {
            val res = ArrayList<Pair<Kind, LocalDate>>()
            for (s in map[d].orEmpty()) {
                if (!isHard(s.kind)) continue
                val id = s.eventId
                if (id != null && moved.containsKey(id)) continue
                if (id != null && opt.changes.any { it is Change.LeaveInPlace && it.eventId == id }) continue
                if (d == d0 && s == missed) continue
                res += s.kind to d
            }
            for (m in moved.values) if (m.to == d && isHard(m.kind)) res += m.kind to m.from
            for (a in opt.changes) if (a is Change.AddCopy && a.date == d) res += Kind.TT to d
            return res
        }
        val out = ArrayList<Pair<LocalDate, LocalDate>>()
        for (r in opt.rows) {
            val d = r.date
            val n = d.plusDays(1)
            for (x in hardOn(d)) for (y in hardOn(n)) {
                if ((isWk(x.first) || isWk(y.first)) && x.second.plusDays(1) != y.second) out += d to n
            }
        }
        return out.distinct()
    }

    // ================================================================ detection (pure)

    /** Today's run counts as missed once its window has passed with no later one, or after 21:00. [nowMin] = minutes after midnight. */
    fun isMissedNow(p: Weather.DayPlan, nowMin: Int): Boolean = p.run.planned && !p.run.done && (p.passed || nowMin >= 21 * 60)

    /** Why a day has no good time to run (null when it has one): dangerous, wet, icy, UV over your limit, or no window at all. */
    fun atRisk(p: Weather.DayPlan, s: RwSettings): String? {
        if (!p.run.planned || p.run.done || p.noData || p.passed) return null
        if (p.split != null) return null
        val w = p.window ?: return "no window fits your running hours"
        if (Weather.acceptable(w, s)) return null
        return when {
            w.dangerous || p.risky -> "dangerous conditions all day"
            w.wet -> "heavy rain all day"
            w.frozen -> "ice or snow around"
            else -> "UV above your limit all day"
        }
    }

    /** A narrow ⚠ window on a day with a run still to do. */
    fun isTight(p: Weather.DayPlan): Boolean = p.run.planned && !p.run.done && !p.noData && !p.passed && p.window?.narrow == true && (p.windows.isEmpty() || p.windows.all { it.narrow })

    fun reasonText(t: Trigger, why: String?): String = when (t) {
        Trigger.MISSED_YESTERDAY -> "Looks missed: yesterday's run wasn't done"
        Trigger.MISSED_PASSED -> "Looks missed: the window has passed"
        Trigger.MISSED_LATE -> "Looks missed: the day is nearly over"
        Trigger.AT_RISK -> "No good window: " + (why ?: "poor conditions")
        Trigger.TIGHT -> "Tight window" + (why?.let { ": $it" } ?: "")
        Trigger.MANUAL -> "You marked it: skipping"
    }

    /** Good, non-overlapping windows for a double-length run on this day (daylight, dry, safe, UV within your limit). */
    fun goodWindows(p: Weather.DayPlan, s: RwSettings, suns: List<Weather.Sun>, zone: ZoneId, minutes: Int, nowSec: Long?): DayWin {
        if (p.noData || p.hours.isEmpty()) return DayWin(1)
        val run = Weather.PlannedRun("Easy run", minutes, Weather.RunType.EASY, planned = true)
        val pk = Weather.pick(p.hours, p.date, run, suns, s, nowSec, zone)
        val good = pk.candidates.filter { Weather.acceptable(it, s) && !it.night }.sortedBy { it.score }
        val a = good.firstOrNull() ?: return DayWin(0)
        val b = good.firstOrNull { it.start >= a.end + 3600 || it.end + 3600 <= a.start }
        val day0 = Weather.atMinutes(p.date, 0, zone)
        fun m(w: Weather.Window) = ((w.start - day0) / 60).toInt()
        val times = listOfNotNull(a, b).map { m(it) }.sorted()
        return DayWin(if (b == null) 1 else 2, times)
    }
}
