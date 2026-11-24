package dev.subt.trainingwidget

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Time in target, from the second-by-second streams of each run:
 *  - SubT: time and distance inside the planned POWER range on work steps (SubT workouts only),
 *  - Easy: time and distance with HR under 135 (every run),
 *  - Long-run aerobic: HR from 135 up to LT1 (long runs only),
 *  - Bands: seven exclusive HR buckets per run (see [EDGES]), for every run.
 * A sample counts for the time to the next sample, at most 5 s, so pauses never count.
 */
object Zones {
    const val EASY_HR = 135.0

    /** Bucket edges (bpm): lower bound inclusive, upper exclusive; seven buckets. */
    val EDGES = doubleArrayOf(125.0, 135.0, 155.0, 165.0, 172.0, 175.0)

    fun bucket(hr: Double): Int {
        var i = 0
        while (i < EDGES.size && hr >= EDGES[i]) i++
        return i
    }

    private val ZEROS = List(7) { 0.0 }

    data class BandRow(val group: String, val label: String, val sec: Double, val m: Double, val pct: Double, val emphasised: Boolean)

    /** The display rows, derived from the seven buckets. pct is the share of all seven buckets (0 when empty). */
    fun bandRows(sec: List<Double>, m: List<Double>): List<BandRow> {
        fun s(i: Int) = sec.getOrElse(i) { 0.0 }
        fun d(i: Int) = m.getOrElse(i) { 0.0 }
        val sum = (0 until 7).sumOf { s(it) }
        fun row(g: String, l: String, idx: List<Int>, em: Boolean = false): BandRow {
            val t = idx.sumOf { s(it) }
            return BandRow(g, l, t, idx.sumOf { d(it) }, if (sum > 0) t / sum * 100.0 else 0.0, em)
        }
        return listOf(
            row("EASY", "Under 125", listOf(0)), row("EASY", "Under 135", listOf(0, 1)),
            row("IN BETWEEN", "135–155", listOf(2)),
            row("SUBT BAND", "155–165", listOf(3)), row("SUBT BAND", "165–172", listOf(4)),
            row("SUBT BAND", "155–172", listOf(3, 4), true), row("SUBT BAND", "165–175", listOf(4, 5)),
            row("TOO HARD", "Over 172", listOf(5, 6)),
        )
    }

    data class Run(
        val id: String, val date: String, val name: String, val seconds: Int, val distance: Double,
        val subt: Boolean, val long: Boolean,
        val subtInSec: Double, val subtInM: Double, val subtWorkSec: Double,
        val easySec: Double, val easyM: Double,
        val longSec: Double, val longM: Double,
        val bandSec: List<Double> = ZEROS, val bandM: List<Double> = ZEROS,
    )

    data class Total(
        val subtInSec: Double, val subtInM: Double, val subtWorkSec: Double,
        val easySec: Double, val easyM: Double, val longSec: Double, val longM: Double, val runs: Int,
        val bandSec: List<Double> = ZEROS, val bandM: List<Double> = ZEROS,
        val longBandSec: List<Double> = ZEROS, val longBandM: List<Double> = ZEROS,
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
        val bandSec = DoubleArray(7); val bandM = DoubleArray(7)
        for (i in 0 until n) {
            val h = hr.getOrNull(i) ?: continue
            val d = dt(i)
            val b = bucket(h)
            bandSec[b] += d; bandM[b] += dist(i, d)
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
        return Run(id, date, name, seconds, a.numOrNull("distance") ?: 0.0, subt, long, inSec, inM, workSec, easySec, easyM, longSec, longM, bandSec.toList(), bandM.toList())
    }

    fun toJson(list: List<Run>): String {
        val arr = JSONArray()
        for (r in list) arr.put(JSONObject().put("id", r.id).put("date", r.date).put("name", r.name).put("seconds", r.seconds)
            .put("distance", r.distance).put("subt", r.subt).put("long", r.long)
            .put("subtInSec", r.subtInSec).put("subtInM", r.subtInM).put("subtWorkSec", r.subtWorkSec)
            .put("easySec", r.easySec).put("easyM", r.easyM).put("longSec", r.longSec).put("longM", r.longM)
            .put("bandSec", JSONArray(r.bandSec)).put("bandM", JSONArray(r.bandM)))
        return arr.toString()
    }

    private fun arr7(a: JSONArray?): List<Double> = if (a == null) ZEROS else List(7) { a.optDouble(it, 0.0) }

    private fun sumBands(l: List<Run>, pick: (Run) -> List<Double>): List<Double> = List(7) { i -> l.sumOf { pick(it)[i] } }

    fun fromJson(s: String): List<Run> = runCatching {
        val arr = JSONArray(s)
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { o ->
            Run(o.optString("id"), o.optString("date"), o.optString("name"), o.optInt("seconds"), o.optDouble("distance", 0.0),
                o.optBoolean("subt"), o.optBoolean("long"),
                o.optDouble("subtInSec", 0.0), o.optDouble("subtInM", 0.0), o.optDouble("subtWorkSec", 0.0),
                o.optDouble("easySec", 0.0), o.optDouble("easyM", 0.0), o.optDouble("longSec", 0.0), o.optDouble("longM", 0.0),
                arr7(o.optJSONArray("bandSec")), arr7(o.optJSONArray("bandM")))
        }
    }.getOrDefault(emptyList())

    /** Runs dated [from]..[to], both included. */
    fun total(runs: List<Run>, from: LocalDate, to: LocalDate): Total {
        val f = from.toString(); val t = to.toString()
        val l = runs.filter { it.date in f..t }
        return Total(l.sumOf { it.subtInSec }, l.sumOf { it.subtInM }, l.sumOf { it.subtWorkSec },
            l.sumOf { it.easySec }, l.sumOf { it.easyM }, l.sumOf { it.longSec }, l.sumOf { it.longM }, l.size,
            sumBands(l) { it.bandSec }, sumBands(l) { it.bandM },
            sumBands(l.filter { it.long }) { it.bandSec }, sumBands(l.filter { it.long }) { it.bandM })
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
