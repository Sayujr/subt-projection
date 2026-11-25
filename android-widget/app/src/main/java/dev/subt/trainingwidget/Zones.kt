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
    private val ZEROS4 = List(4) { 0.0 }
    private val ZEROS5 = List(5) { 0.0 }

    /** Training-mix bands, bottom to top in the weekly chart; every second of a run lands in exactly one. */
    const val MIX_SUBT = 0
    const val MIX_ABOVE = 1
    const val MIX_LT1 = 2
    const val MIX_BETWEEN = 3
    const val MIX_EASY = 4
    /** Long-run LT1 band: HR from this value up to the LT1 setting. */
    const val MIX_LT1_LOW = 145.0
    /** Easy limits for the training mix: HR under [MIX_LT1_LOW] and power under this share of CP. */
    const val MIX_EASY_CP = 0.65
    private val FIFTEEN = Regex("\\b45\\s*(s|sec)?\\s*[/-]\\s*15", RegexOption.IGNORE_CASE)

    /** Run categories, in the order used for [Run.catSec] and the load split. */
    const val EASY = 0
    const val STEADY = 1
    const val SUBT = 2
    const val OTHER = 3
    /** Relative load per second of each category (about intensity squared at typical intensities). */
    val LOAD_WEIGHT = doubleArrayOf(0.42, 0.61, 0.86, 0.72)

    /**
     * A day's [dayLoad] split over the four categories: load from [loadWithoutZones] (activities with no zone
     * numbers) goes to OTHER, the rest in proportion to category seconds × weight over [runs]. Parts add up
     * to [dayLoad] exactly (largest remainder); with no run seconds everything is OTHER.
     */
    fun loadSplit(dayLoad: Int, runs: List<Run>, loadWithoutZones: Int): IntArray {
        val total = dayLoad.coerceAtLeast(0)
        val free = loadWithoutZones.coerceIn(0, total)
        val zoned = total - free
        val wsec = DoubleArray(4) { k -> runs.sumOf { it.catSec.getOrElse(k) { 0.0 } } * LOAD_WEIGHT[k] }
        val sum = wsec.sum()
        val out = IntArray(4)
        if (zoned > 0 && sum > 0) {
            val exact = DoubleArray(4) { zoned * wsec[it] / sum }
            for (k in 0 until 4) out[k] = Math.floor(exact[k]).toInt()
            var left = zoned - out.sum()
            val order = (0 until 4).sortedWith(compareByDescending<Int> { exact[it] - out[it] }.thenBy { it })
            var j = 0
            while (left > 0) { out[order[j % 4]]++; left--; j++ }
        } else out[OTHER] += zoned
        out[OTHER] += free
        return out
    }

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
        /** SubT work judged under / over the planned power range. */
        val subtUnderSec: Double = 0.0, val subtUnderM: Double = 0.0, val subtOverSec: Double = 0.0, val subtOverM: Double = 0.0,
        /** Every sample of the run in one of four categories ([EASY], [STEADY], [SUBT], [OTHER]): seconds and metres. */
        val catSec: List<Double> = ZEROS4, val catM: List<Double> = ZEROS4,
        /** Training mix: seconds and metres in the five bands ([MIX_SUBT] .. [MIX_EASY]). */
        val mixSec: List<Double> = ZEROS5, val mixM: List<Double> = ZEROS5,
    )

    data class Total(
        val subtInSec: Double, val subtInM: Double, val subtWorkSec: Double,
        val easySec: Double, val easyM: Double, val longSec: Double, val longM: Double, val runs: Int,
        val bandSec: List<Double> = ZEROS, val bandM: List<Double> = ZEROS,
        val longBandSec: List<Double> = ZEROS, val longBandM: List<Double> = ZEROS,
        val subtUnderSec: Double = 0.0, val subtUnderM: Double = 0.0, val subtOverSec: Double = 0.0, val subtOverM: Double = 0.0,
        val catSec: List<Double> = ZEROS4, val catM: List<Double> = ZEROS4,
        val mixSec: List<Double> = ZEROS5, val mixM: List<Double> = ZEROS5,
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
        val over = BooleanArray(n)
        var underSec = 0.0; var underM = 0.0; var overSec = 0.0; var overM = 0.0
        val det = if (subt && raw.optJSONObject("i") != null) WorkoutDetail.fromRaw(raw, fallbackFtp) else null
        // Categories: every sample in a SubT work step is SUBT; outside, HR decides (test steps and missing HR are OTHER)
        val cat = IntArray(n) { -1 }
        if (det != null) for (st in det.steps) {
            if (st.start < 0 || !(st.isWork || st.isTest)) continue
            for (i in st.start..minOf(st.end, n - 1)) cat[i] = if (st.isWork) SUBT else OTHER
        }
        val catSec = DoubleArray(4); val catM = DoubleArray(4)
        for (i in 0 until n) {
            val h = hr.getOrNull(i)
            val k = if (cat[i] >= 0) cat[i] else if (h == null) OTHER else if (h < EASY_HR) EASY else if (h < lt1) STEADY else OTHER
            val d = dt(i)
            catSec[k] += d; catM[k] += dist(i, d)
        }
        if (det != null) {
            for (st in det.steps) {
                val lo = st.loW; val hi = st.hiW
                if (!(st.isWork && !st.hrTarget && lo != null && hi != null && st.start >= 0)) continue
                // same rule as the workout screen: reps under 90 s are judged raw, longer ones on a 10 s average after the ramp
                val first = WorkoutDetail.rampStart(st.start, st.end)
                val series = if (WorkoutDetail.isShort(st.start, st.end)) watts else WorkoutDetail.stepSmooth(watts, first, st.end)
                for (i in first..minOf(st.end, n - 1)) {
                    val w = series.getOrNull(i) ?: continue
                    val d = dt(i)
                    workSec += d
                    when {
                        w < lo - 0.5 -> { underSec += d; underM += dist(i, d) }
                        w > hi + 0.5 -> { overSec += d; overM += dist(i, d); if (i < n) over[i] = true }
                        else -> { inSec += d; inM += dist(i, d) }
                    }
                }
            }
        }
        // ---- Training mix
        val ftp = a.numOrNull("icu_ftp")?.takeIf { it > 0 } ?: fallbackFtp
        val detAll = det ?: if (raw.optJSONObject("i") != null) runCatching { WorkoutDetail.fromRaw(raw, fallbackFtp) }.getOrNull() else null
        val race = a.optBoolean("race") || Streaks.isTtName(name) ||
            (raw.optJSONObject("e")?.optString("category") ?: "").startsWith("RACE", ignoreCase = true)
        val fifteen = FIFTEEN.containsMatchIn(name)
        val kinds = arrayOfNulls<WorkoutDetail.Kind>(n)
        val fast = BooleanArray(n)
        var structured = false
        if (detAll != null) for (st in detAll.steps) {
            if (st.start < 0) continue
            if (st.isWork || st.isTest) structured = true
            val mid = st.mid
            // 45/15-type reps: under 90 s at a power target around CP or harder
            val hardShort = st.isWork && !st.hrTarget && WorkoutDetail.isShort(st.start, st.end) &&
                ((ftp != null && mid != null && mid >= 0.95 * ftp) || fifteen)
            for (i in st.start..minOf(st.end, n - 1)) { kinds[i] = st.kind; if (hardShort) fast[i] = true }
        }
        val easyRun = !subt && !long && !race && !structured
        val mixSec = DoubleArray(5); val mixM = DoubleArray(5)
        for (i in 0 until n) {
            val d = dt(i)
            val h = hr.getOrNull(i)
            val w = watts.getOrNull(i)
            val k = kinds[i]
            val easyOk = (h != null || w != null) && (h == null || h < MIX_LT1_LOW) &&
                (w == null || ftp == null || w < MIX_EASY_CP * ftp)
            val band = when {
                subt && k == WorkoutDetail.Kind.WORK -> if (over[i]) MIX_ABOVE else MIX_SUBT
                k == WorkoutDetail.Kind.TEST || fast[i] -> MIX_ABOVE
                race && (detAll == null || (k != WorkoutDetail.Kind.WARMUP && k != WorkoutDetail.Kind.COOLDOWN && k != WorkoutDetail.Kind.RECOVERY)) -> MIX_ABOVE
                long && h != null && h >= MIX_LT1_LOW && h <= lt1 -> MIX_LT1
                (easyRun || k == WorkoutDetail.Kind.WARMUP || k == WorkoutDetail.Kind.COOLDOWN) && easyOk -> MIX_EASY
                else -> MIX_BETWEEN
            }
            mixSec[band] += d; mixM[band] += dist(i, d)
        }
        return Run(id, date, name, seconds, a.numOrNull("distance") ?: 0.0, subt, long, inSec, inM, workSec, easySec, easyM, longSec, longM, bandSec.toList(), bandM.toList(), underSec, underM, overSec, overM, catSec.toList(), catM.toList(), mixSec.toList(), mixM.toList())
    }

    fun toJson(list: List<Run>): String {
        val arr = JSONArray()
        for (r in list) arr.put(JSONObject().put("id", r.id).put("date", r.date).put("name", r.name).put("seconds", r.seconds)
            .put("distance", r.distance).put("subt", r.subt).put("long", r.long)
            .put("subtInSec", r.subtInSec).put("subtInM", r.subtInM).put("subtWorkSec", r.subtWorkSec)
            .put("easySec", r.easySec).put("easyM", r.easyM).put("longSec", r.longSec).put("longM", r.longM)
            .put("bandSec", JSONArray(r.bandSec)).put("bandM", JSONArray(r.bandM))
            .put("subtUnderSec", r.subtUnderSec).put("subtUnderM", r.subtUnderM).put("subtOverSec", r.subtOverSec).put("subtOverM", r.subtOverM)
            .put("catSec", JSONArray(r.catSec)).put("catM", JSONArray(r.catM))
            .put("mixSec", JSONArray(r.mixSec)).put("mixM", JSONArray(r.mixM)))
        return arr.toString()
    }

    private fun arr4(a: JSONArray?): List<Double> = if (a == null) ZEROS4 else List(4) { a.optDouble(it, 0.0) }

    private fun arr5(a: JSONArray?): List<Double> = if (a == null) ZEROS5 else List(5) { a.optDouble(it, 0.0) }

    private fun arr7(a: JSONArray?): List<Double> = if (a == null) ZEROS else List(7) { a.optDouble(it, 0.0) }

    private fun sumBands(l: List<Run>, pick: (Run) -> List<Double>): List<Double> = List(7) { i -> l.sumOf { pick(it)[i] } }

    fun fromJson(s: String): List<Run> = runCatching {
        val arr = JSONArray(s)
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { o ->
            Run(o.optString("id"), o.optString("date"), o.optString("name"), o.optInt("seconds"), o.optDouble("distance", 0.0),
                o.optBoolean("subt"), o.optBoolean("long"),
                o.optDouble("subtInSec", 0.0), o.optDouble("subtInM", 0.0), o.optDouble("subtWorkSec", 0.0),
                o.optDouble("easySec", 0.0), o.optDouble("easyM", 0.0), o.optDouble("longSec", 0.0), o.optDouble("longM", 0.0),
                arr7(o.optJSONArray("bandSec")), arr7(o.optJSONArray("bandM")),
                o.optDouble("subtUnderSec", 0.0), o.optDouble("subtUnderM", 0.0), o.optDouble("subtOverSec", 0.0), o.optDouble("subtOverM", 0.0),
                arr4(o.optJSONArray("catSec")), arr4(o.optJSONArray("catM")),
                arr5(o.optJSONArray("mixSec")), arr5(o.optJSONArray("mixM")))
        }
    }.getOrDefault(emptyList())

    /** Runs dated [from]..[to], both included. */
    fun total(runs: List<Run>, from: LocalDate, to: LocalDate): Total {
        val f = from.toString(); val t = to.toString()
        val l = runs.filter { it.date in f..t }
        return Total(l.sumOf { it.subtInSec }, l.sumOf { it.subtInM }, l.sumOf { it.subtWorkSec },
            l.sumOf { it.easySec }, l.sumOf { it.easyM }, l.sumOf { it.longSec }, l.sumOf { it.longM }, l.size,
            sumBands(l) { it.bandSec }, sumBands(l) { it.bandM },
            sumBands(l.filter { it.long }) { it.bandSec }, sumBands(l.filter { it.long }) { it.bandM },
            l.sumOf { it.subtUnderSec }, l.sumOf { it.subtUnderM }, l.sumOf { it.subtOverSec }, l.sumOf { it.subtOverM },
            List(4) { k -> l.sumOf { it.catSec[k] } }, List(4) { k -> l.sumOf { it.catM[k] } },
            List(5) { k -> l.sumOf { it.mixSec[k] } }, List(5) { k -> l.sumOf { it.mixM[k] } })
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
