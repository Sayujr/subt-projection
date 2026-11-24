package dev.subt.trainingwidget

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Time in target, from the second-by-second streams of each run:
 *  - SubT: time and distance inside the planned POWER range on work steps (SubT workouts only),
 *  - Easy: time and distance with HR under 130 (every run),
 *  - Long-run aerobic: HR from 130 up to LT1 (long runs only).
 * A sample counts for the time to the next sample, at most 5 s, so pauses never count.
 */
object Zones {
    const val EASY_HR = 130.0

    data class Run(
        val id: String, val date: String, val name: String, val seconds: Int, val distance: Double,
        val subt: Boolean, val long: Boolean,
        val subtInSec: Double, val subtInM: Double, val subtWorkSec: Double,
        val easySec: Double, val easyM: Double,
        val longSec: Double, val longM: Double,
    )

    data class Total(
        val subtInSec: Double, val subtInM: Double, val subtWorkSec: Double,
        val easySec: Double, val easyM: Double, val longSec: Double, val longM: Double, val runs: Int,
    )

    private val LONG_NAME = Regex("long|progress|steady", RegexOption.IGNORE_CASE)

    fun isLong(name: String, date: String, seconds: Int): Boolean {
        if (Streaks.isTtName(name) || SubtWorkouts.isSubtWorkout(name)) return false
        val sunday = runCatching { LocalDate.parse(date).dayOfWeek == DayOfWeek.SUNDAY }.getOrDefault(false)
        return (sunday && seconds >= 3600) || LONG_NAME.containsMatchIn(name) || seconds >= 5400
    }

    private fun JSONObject.numOrNull(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeUnless { it.isNaN() } else null

    fun compute(raw: JSONObject, lt1: Double, fallbackFtp: Double?): Run {
        val a = raw.getJSONObject("a")
        val streams = raw.optJSONArray("s")
        val time = WorkoutDetail.stream(streams, "time")
        val hr = WorkoutDetail.stream(streams, "heartrate")
        val vel = WorkoutDetail.stream(streams, "velocity_smooth")
        val watts = WorkoutDetail.stream(streams, "watts")
        val n = maxOf(time.size, hr.size, vel.size, watts.size)
        fun dt(i: Int): Double {
            if (time.isEmpty() || i >= time.size - 1) return 1.0
            val x = time[i]; val y = time[i + 1]
            if (x == null || y == null) return 1.0
            return (y - x).coerceIn(0.0, 5.0)
        }
        fun dist(i: Int, d: Double): Double = (vel.getOrNull(i) ?: 0.0) * d
        val id = a.optString("id")
        val name = a.optString("name")
        val date = a.optString("start_date_local").take(10)
        val seconds = (a.numOrNull("moving_time") ?: 0.0).toInt()
        val subt = SubtWorkouts.isSubtWorkout(name)
        val long = isLong(name, date, seconds)

        var easySec = 0.0; var easyM = 0.0; var longSec = 0.0; var longM = 0.0
        for (i in 0 until n) {
            val h = hr.getOrNull(i) ?: continue
            val d = dt(i)
            if (h < EASY_HR) { easySec += d; easyM += dist(i, d) }
            else if (long && h < lt1) { longSec += d; longM += dist(i, d) }
        }

        var inSec = 0.0; var inM = 0.0; var workSec = 0.0
        if (subt && raw.optJSONObject("i") != null) {
            val det = WorkoutDetail.fromRaw(raw, fallbackFtp)
            for (st in det.steps) {
                val lo = st.loW; val hi = st.hiW
                if (!(st.isWork && !st.hrTarget && lo != null && hi != null && st.start >= 0)) continue
                for (i in st.start..minOf(st.end, n - 1)) {
                    val w = watts.getOrNull(i) ?: continue
                    val d = dt(i)
                    workSec += d
                    if (w >= lo - 0.5 && w <= hi + 0.5) { inSec += d; inM += dist(i, d) }
                }
            }
        }
        return Run(id, date, name, seconds, a.numOrNull("distance") ?: 0.0, subt, long, inSec, inM, workSec, easySec, easyM, longSec, longM)
    }

    fun toJson(list: List<Run>): String {
        val arr = JSONArray()
        for (r in list) arr.put(JSONObject().put("id", r.id).put("date", r.date).put("name", r.name).put("seconds", r.seconds)
            .put("distance", r.distance).put("subt", r.subt).put("long", r.long)
            .put("subtInSec", r.subtInSec).put("subtInM", r.subtInM).put("subtWorkSec", r.subtWorkSec)
            .put("easySec", r.easySec).put("easyM", r.easyM).put("longSec", r.longSec).put("longM", r.longM))
        return arr.toString()
    }

    fun fromJson(s: String): List<Run> = runCatching {
        val arr = JSONArray(s)
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { o ->
            Run(o.optString("id"), o.optString("date"), o.optString("name"), o.optInt("seconds"), o.optDouble("distance", 0.0),
                o.optBoolean("subt"), o.optBoolean("long"),
                o.optDouble("subtInSec", 0.0), o.optDouble("subtInM", 0.0), o.optDouble("subtWorkSec", 0.0),
                o.optDouble("easySec", 0.0), o.optDouble("easyM", 0.0), o.optDouble("longSec", 0.0), o.optDouble("longM", 0.0))
        }
    }.getOrDefault(emptyList())

    /** Runs dated [from]..[to], both included. */
    fun total(runs: List<Run>, from: LocalDate, to: LocalDate): Total {
        val f = from.toString(); val t = to.toString()
        val l = runs.filter { it.date in f..t }
        return Total(l.sumOf { it.subtInSec }, l.sumOf { it.subtInM }, l.sumOf { it.subtWorkSec },
            l.sumOf { it.easySec }, l.sumOf { it.easyM }, l.sumOf { it.longSec }, l.sumOf { it.longM }, l.size)
    }

    /** The last [weeks] Mon–Sun weeks, oldest first, the last one being the week of [today]. */
    fun weekly(runs: List<Run>, weeks: Int = 12, today: LocalDate = LocalDate.now()): List<Pair<LocalDate, Total>> {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return (weeks - 1 downTo 0).map { k ->
            val m = monday.minusWeeks(k.toLong())
            m to total(runs, m, m.plusDays(6))
        }
    }
}
