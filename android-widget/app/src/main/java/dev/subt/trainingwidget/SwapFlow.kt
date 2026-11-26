package dev.subt.trainingwidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * The Android side of missed-session swaps: finds sessions that look missed or at risk, builds the engine's input from
 * the intervals.icu week and the forecast, remembers what you dismissed or applied, and (only after you confirm on the
 * swap page) writes to intervals.icu, with Undo.
 */
object SwapFlow {
    class Cand(val date: LocalDate, val workout: Workout, val trigger: Swaps.Trigger, val why: String?)

    class Pickable(val date: LocalDate, val workout: Workout)

    fun kindOf(w: Workout): Swaps.Kind = when {
        !w.type.contains("Run", ignoreCase = true) -> Swaps.Kind.OTHER
        w.status == WorkoutStatus.RACE -> Swaps.Kind.RACE
        else -> when (Weather.typeOf(w)) {
            Weather.RunType.TT -> Swaps.Kind.TT
            Weather.RunType.SUBT -> Swaps.Kind.WORKOUT
            Weather.RunType.LONG -> Swaps.Kind.LONG
            Weather.RunType.EASY -> Swaps.Kind.EASY
        }
    }

    fun minutesOf(w: Workout): Int = when {
        w.seconds > 0 -> (w.seconds / 60.0).roundToInt().coerceAtLeast(10)
        w.distanceM > 0 -> (w.distanceM / 1000.0 * 5.5).roundToInt().coerceAtLeast(10)
        else -> 60
    }

    fun sessionOf(w: Workout) = Swaps.Session(w.eventId, w.name, kindOf(w), minutesOf(w), w.startLocal)

    private fun dateOf(w: Workout): LocalDate? = runCatching { LocalDate.parse(w.date) }.getOrNull()

    /** Every planned (not done) session this week and the days after, as engine slots. */
    fun slots(ctx: Context): List<Swaps.Slot> =
        RunWindows.weekRuns(ctx).filter { it.status != WorkoutStatus.DONE }
            .mapNotNull { w -> dateOf(w)?.let { it to sessionOf(w) } }
            .groupBy({ it.first }, { it.second })
            .map { (d, ss) -> Swaps.Slot(d, ss) }

    /** Planned runs that could be marked as missed: from yesterday to a week ahead, with an event id so they can move. */
    fun pickable(ctx: Context): List<Pickable> {
        val today = LocalDate.now()
        return RunWindows.weekRuns(ctx)
            .filter { it.status == WorkoutStatus.PLANNED && it.eventId != null && kindOf(it) != Swaps.Kind.OTHER && kindOf(it) != Swaps.Kind.RACE }
            .mapNotNull { w -> dateOf(w)?.let { Pickable(it, w) } }
            .filter { !it.date.isBefore(today.minusDays(1)) && !it.date.isAfter(today.plusDays(8)) }
            .sortedWith(compareBy<Pickable>({ it.date }, { it.workout.name }))
    }

    /** Good windows per day from the run-window plans (a day with no forecast counts as 1). */
    fun winFn(ctx: Context, dbl: Int): (LocalDate) -> Swaps.DayWin {
        val today = LocalDate.now()
        val s = RwStore.load(ctx)
        val data = RunWindows.data(ctx)
        val plans = RunWindows.plans(ctx, today, 7).associateBy { it.date }
        val zone = ZoneId.systemDefault()
        val nowSec = System.currentTimeMillis() / 1000
        return { d: LocalDate ->
            val p = plans[d]
            if (p == null || data == null) Swaps.DayWin(1)
            else Swaps.goodWindows(p, s, data.suns, zone, dbl, if (d == today) nowSec else null)
        }
    }

    /** The options for one session (empty when nothing can be offered). */
    fun optionsFor(ctx: Context, date: LocalDate, w: Workout): List<Swaps.Option> {
        val slots = slots(ctx)
        val missed = sessionOf(w)
        val dbl = Swaps.doubleMinutes(slots, date)
        return Swaps.options(missed, date, slots, winFn(ctx, dbl), LocalDate.now())
    }

    // ---------------------------------------------------------------- what you have dealt with

    private fun sp(ctx: Context) = ctx.getSharedPreferences("training_widget", Context.MODE_PRIVATE)

    /** "applied" or "dismissed" once you have dealt with that session; null before. */
    fun state(ctx: Context, eventId: Long): String? = sp(ctx).getString("swap_state_$eventId", null)
    fun mark(ctx: Context, eventId: Long, st: String) { sp(ctx).edit().putString("swap_state_$eventId", st).apply() }

    // ---------------------------------------------------------------- detection

    /** Sessions that look missed, or are at risk of being missed, that you haven't dealt with. Strongest first. */
    fun candidates(ctx: Context): List<Cand> {
        if (Prefs.credentials(ctx) == null) return emptyList()
        val today = LocalDate.now()
        val week = RunWindows.weekRuns(ctx)
        val out = ArrayList<Cand>()
        fun eligible(date: LocalDate): Workout? {
            val w = Weather.plannedWorkout(week, date.toString()) ?: return null
            val id = w.eventId ?: return null
            val k = kindOf(w)
            if (k == Swaps.Kind.OTHER || k == Swaps.Kind.RACE) return null
            if (state(ctx, id) != null) return null
            if (Weather.runOf(week, date.toString()).done) return null
            return w
        }
        val y = today.minusDays(1)
        eligible(y)?.let { out += Cand(y, it, Swaps.Trigger.MISSED_YESTERDAY, null) }
        val s = RwStore.load(ctx)
        val plans = RunWindows.plans(ctx, today, 7)
        val nowMin = LocalTime.now().let { it.hour * 60 + it.minute }
        val p0 = plans.getOrNull(0)
        val wToday = eligible(today)
        if (wToday != null) {
            if (p0 != null && Swaps.isMissedNow(p0, nowMin)) {
                out += Cand(today, wToday, if (p0.passed) Swaps.Trigger.MISSED_PASSED else Swaps.Trigger.MISSED_LATE, null)
            } else if (p0 == null && nowMin >= 21 * 60) {
                out += Cand(today, wToday, Swaps.Trigger.MISSED_LATE, null)
            } else if (p0 != null) {
                val risk = Swaps.atRisk(p0, s)
                if (risk != null) out += Cand(today, wToday, Swaps.Trigger.AT_RISK, risk)
                else if (Swaps.isTight(p0)) out += Cand(today, wToday, Swaps.Trigger.TIGHT, p0.window?.let { "(" + Weather.range(it, ZoneId.systemDefault()) + ")" })
            }
        }
        for (i in 1..6) {
            val p = plans.getOrNull(i) ?: continue
            val why = Swaps.atRisk(p, s) ?: continue
            eligible(p.date)?.let { out += Cand(p.date, it, Swaps.Trigger.AT_RISK, why) }
        }
        return out
    }

    /** The one prompt for the Today card: missed first, then at-risk today / tomorrow, then a tight window today. */
    fun banner(ctx: Context): Cand? {
        val today = LocalDate.now()
        val all = candidates(ctx)
        return all.firstOrNull { it.trigger == Swaps.Trigger.MISSED_YESTERDAY || it.trigger == Swaps.Trigger.MISSED_PASSED || it.trigger == Swaps.Trigger.MISSED_LATE }
            ?: all.filter { it.trigger == Swaps.Trigger.AT_RISK && !it.date.isAfter(today.plusDays(1)) }.minByOrNull { it.date }
            ?: all.firstOrNull { it.trigger == Swaps.Trigger.TIGHT }
    }

    /** The prompt for one day's plan (any trigger), or null. */
    fun forDate(ctx: Context, date: LocalDate): Cand? = candidates(ctx).firstOrNull { it.date == date }

    // ---------------------------------------------------------------- applying (only from the confirm dialog) and undo

    class Mv(val id: Long, val from: String, val fromStart: String?, val to: String)
    class UndoRec(val day: String, val label: String, val moves: List<Mv>, val created: List<Long>) {
        fun toJson(): String = JSONObject().put("day", day).put("label", label)
            .put("moves", JSONArray().apply { moves.forEach { put(JSONObject().put("id", it.id).put("from", it.from).put("fromStart", it.fromStart ?: "").put("to", it.to)) } })
            .put("created", JSONArray().apply { created.forEach { put(it) } }).toString()
        companion object {
            fun fromJson(s: String?): UndoRec? = runCatching {
                val o = JSONObject(s ?: return null)
                val mv = o.getJSONArray("moves")
                val cr = o.getJSONArray("created")
                UndoRec(o.getString("day"), o.optString("label"),
                    (0 until mv.length()).map { val m = mv.getJSONObject(it); Mv(m.getLong("id"), m.getString("from"), m.optString("fromStart").ifEmpty { null }, m.getString("to")) },
                    (0 until cr.length()).map { cr.getLong(it) })
            }.getOrNull()
        }
    }

    /** What Undo can still reverse: only on the day it was applied. */
    fun undoRec(ctx: Context): UndoRec? =
        UndoRec.fromJson(sp(ctx).getString("swap_undo", null))?.takeIf { it.day == LocalDate.now().toString() }

    /** Writes the option to intervals.icu. If anything fails, what was already done is reversed and the error is thrown. */
    fun apply(ctx: Context, opt: Swaps.Option, missedName: String, missedEventId: Long?): String {
        val creds = Prefs.credentials(ctx) ?: throw IllegalStateException("Connect intervals.icu first")
        val api = IcuApi(creds)
        val moved = ArrayList<Mv>()
        val created = ArrayList<Long>()
        // intervals.icu only counts load for planned runs written as workout steps ("- 35m 61-65%"),
        // so added runs copy the power range of this week's own easy run (default 61-65 % of CP)
        val easyPct = run {
            val ref = RunWindows.weekRuns(ctx).firstOrNull { it.eventId != null && it.status != WorkoutStatus.DONE && Weather.typeOf(it) == Weather.RunType.EASY }
            val desc = ref?.eventId?.let { api.eventDescription(it) }
            desc?.let { Regex("""(\d{2,3})\s*-\s*(\d{2,3})\s*%""").find(it) }?.let { "${it.groupValues[1]}-${it.groupValues[2]}%" } ?: "61-65%"
        }
        fun easyDoc(minutes: Int, note: String) = "- ${minutes}m $easyPct\n\n$note"
        try {
            for (ch in opt.changes) {
                when (ch) {
                    is Swaps.Change.Move -> {
                        api.moveEvent(ch.eventId, ch.to.toString(), ch.startLocal)
                        moved += Mv(ch.eventId, ch.from.toString(), ch.startLocal, ch.to.toString())
                    }
                    is Swaps.Change.AddEasy -> created += api.createRunEvent(ch.date.toString(), ch.startMin, ch.name, ch.minutes,
                        easyDoc(ch.minutes, "Added by Training Today (easy run to keep the week balanced, for $missedName)"))
                    is Swaps.Change.AddDouble -> created += api.createRunEvent(ch.date.toString(), ch.startMin, "Easy run (double)", ch.minutes,
                        easyDoc(ch.minutes, "Added by Training Today (double for ${ch.forName})"))
                    is Swaps.Change.AddCopy -> created += api.createRunEvent(ch.date.toString(), ch.startMin, ch.name, ch.minutes,
                        (missedEventId?.let { api.eventDescription(it) }?.let { "$it\n\n" } ?: "") + "Added by Training Today (copy of missed ${ch.name})")
                    is Swaps.Change.LeaveInPlace -> Unit
                }
            }
        } catch (e: Exception) {
            for (m in moved.reversed()) runCatching { api.moveEvent(m.id, m.from, m.fromStart) }
            for (id in created) runCatching { api.deleteEvent(id) }
            throw e
        }
        sp(ctx).edit().putString("swap_undo", UndoRec(LocalDate.now().toString(), missedName, moved, created).toJson()).apply()
        missedEventId?.let { mark(ctx, it, "applied") }
        Refresh.now(ctx.applicationContext, replace = true)
        return "Applied in intervals.icu: ${moved.size} moved, ${created.size} added"
    }

    /** Moves moved events back and deletes only the events the apply created. */
    fun undo(ctx: Context): String {
        val rec = undoRec(ctx) ?: throw IllegalStateException("Nothing to undo")
        val creds = Prefs.credentials(ctx) ?: throw IllegalStateException("Connect intervals.icu first")
        val api = IcuApi(creds)
        var failed = 0
        for (m in rec.moves.reversed()) try { api.moveEvent(m.id, m.from, m.fromStart) } catch (e: Exception) { failed++ }
        for (id in rec.created) try { api.deleteEvent(id) } catch (e: Exception) { failed++ }
        if (failed == 0) sp(ctx).edit().remove("swap_undo").apply()
        Refresh.now(ctx.applicationContext, replace = true)
        if (failed > 0) throw java.io.IOException("$failed change(s) couldn't be undone; try again")
        return "Undone: everything is back as it was"
    }
}
