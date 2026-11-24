package dev.subt.trainingwidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * One completed workout, step by step, against the targets you planned.
 *
 * Steps come from intervals.icu's own split of the activity. Targets come from the paired planned
 * workout and are turned into watts with the FTP intervals.icu recorded for that day (not today's).
 * "Work" steps are the planned steps (not warm-up / cool-down, 40 s or longer) whose target is within
 * 25 % of the hardest step of 2 min or more, so recoveries and short strides drop out, a time trial in
 * the same session doesn't push the SubT reps out, and an easy day where every step is easy still counts.
 * Steps with a heart-rate target instead of power (e.g. bike sessions) are judged against that HR range.
 */
object WorkoutDetail {
    enum class Kind(val label: String) { WARMUP("Warm-up"), WORK("Work"), RECOVERY("Recovery"), COOLDOWN("Cool-down"), OTHER("Step") }

    data class Stat(val avg: Double?, val min: Double?, val max: Double?)
    /** [loW]/[hiW] hold the target range: watts, or bpm when [hrTarget]. */
    data class Plan(val kind: Kind, val seconds: Int, val distance: Double, val loW: Double?, val hiW: Double?, val group: Int, val reps: Int, val hrTarget: Boolean = false)

    data class Step(
        val index: Int, val kind: Kind, val seconds: Int, val distance: Double,
        val power: Stat, val speed: Stat, val hr: Stat, val cadence: Double?, val elevGain: Double?,
        val loW: Double?, val hiW: Double?,
        /** Share of the step (0–1) with power inside the target range. */
        val inTarget: Double?,
        /** Share of the step (0–1) with HR at or under 165 / 172. */
        val hr165: Double?, val hr172: Double?,
        val start: Int, val end: Int, val group: Int, val reps: Int,
        /** The target range is heart rate (bpm), not power. */
        val hrTarget: Boolean = false,
    ) {
        val isWork get() = kind == Kind.WORK
        /** The average the target applies to: HR for HR targets, else power. */
        val targetAvg: Double? get() = if (hrTarget) hr.avg else power.avg
        val mid: Double? get() = if (loW != null && hiW != null) (loW + hiW) / 2 else null
        /** Average power as % of the middle of the target. */
        val pctOfTarget: Double? get() = targetAvg?.let { a -> mid?.takeIf { it > 0 }?.let { a / it * 100 } }
        /** −1 below the range, 0 inside, +1 above. */
        val vsTarget: Int? get() = targetAvg?.let { a -> if (loW == null || hiW == null) null else if (a < loW - 0.5) -1 else if (a > hiW + 0.5) 1 else 0 }
        /** Where the average sits: 0 = bottom of the range, 1 = top (can fall outside 0–1). */
        val position: Double? get() = targetAvg?.let { a -> if (loW == null || hiW == null) null else if (hiW - loW < 1) (a - loW) / (0.04 * hiW) + 0.5 else (a - loW) / (hiW - loW) }
    }

    /** All work steps rolled into one. */
    data class Summary(
        val count: Int, val seconds: Int, val distance: Double,
        val power: Stat, val speed: Stat, val hr: Stat,
        val pctOfTarget: Double?, val position: Double?, val loW: Double?, val hiW: Double?,
        val inTarget: Double?, val hr165: Double?, val hr172: Double?,
        val hrTarget: Boolean = false,
    ) {
        val targetAvg: Double? get() = if (hrTarget) hr.avg else power.avg
    }

    data class Detail(
        val id: String, val name: String, val date: String, val type: String,
        val seconds: Int, val distance: Double, val load: Int?, val elevGain: Double?, val ftp: Double?,
        val power: Stat, val speed: Stat, val hr: Stat, val cadence: Double?,
        val steps: List<Step>, val work: Summary?, val hasTargets: Boolean,
        /** Down-sampled streams for the chart (same length): seconds, watts, HR, speed m/s. */
        val t: List<Int>, val watts: List<Double?>, val heart: List<Double?>, val vel: List<Double?>,
        /** Stream index → chart index factor. */
        val stride: Int,
    ) {
        val isRide get() = type.contains("Ride", ignoreCase = true)
    }

    // ---------------------------------------------------------------- plan

    private fun JSONObject.num(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeUnless { it.isNaN() } else null

    /** Planned steps in the order they're run (repeats expanded), targets in watts. */
    fun flattenPlan(doc: JSONObject?, ftp: Double?, maxHr: Double? = null, lthr: Double? = null): List<Plan> {
        if (doc == null) return emptyList()
        data class Raw(val warm: Boolean, val cool: Boolean, val seconds: Int, val distance: Double, val lo: Double?, val hi: Double?, val group: Int, val reps: Int, val hr: Boolean)
        fun hrTarget(st: JSONObject): Pair<Double?, Double?> {
            val p = st.optJSONObject("hr") ?: return null to null
            val units = p.optString("units")
            val lo = p.num("start") ?: p.num("value") ?: return null to null
            val hi = p.num("end") ?: lo
            fun b(v: Double): Double? = when {
                units.contains("lthr", true) -> lthr?.let { v / 100 * it }
                units.contains("%") -> maxHr?.let { v / 100 * it }
                units.contains("bpm", true) -> v
                else -> null
            }
            val x = b(lo); val y = b(hi)
            return if (x == null || y == null) null to null else minOf(x, y) to maxOf(x, y)
        }
        val out = mutableListOf<Raw>()
        var groupId = 0
        fun target(st: JSONObject): Pair<Double?, Double?> {
            val p = st.optJSONObject("power") ?: return null to null
            val units = p.optString("units")
            val lo = p.num("start") ?: p.num("value") ?: return null to null
            val hi = p.num("end") ?: lo
            fun w(v: Double): Double? = when {
                units.contains("%") || units.contains("ftp", true) -> ftp?.let { v / 100 * it }
                units.equals("w", true) || units.contains("watt", true) -> v
                else -> null
            }
            val a = w(lo); val b = w(hi)
            return if (a == null || b == null) null to null else minOf(a, b) to maxOf(a, b)
        }
        fun walk(arr: JSONArray?, group: Int, reps: Int) {
            for (i in 0 until (arr?.length() ?: 0)) {
                val st = arr!!.optJSONObject(i) ?: continue
                val inner = st.optJSONArray("steps")
                if (inner != null && inner.length() > 0) {
                    val n = st.optInt("reps", 1).coerceAtLeast(1)
                    val g = ++groupId
                    repeat(n) { walk(inner, g, n) }
                } else {
                    var (lo, hi) = target(st)
                    var isHr = false
                    if (lo == null) { val h = hrTarget(st); lo = h.first; hi = h.second; isHr = lo != null }
                    out += Raw(st.optBoolean("warmup"), st.optBoolean("cooldown"), st.optInt("duration", 0), st.num("distance") ?: 0.0, lo, hi, group, reps, isHr)
                }
            }
        }
        walk(doc.optJSONArray("steps"), 0, 1)
        // Hardest step of 2 min+ (or 400 m+) sets the bar, so 20–30 s strides don't; fall back to any step
        val main = out.filter { !it.warm && !it.cool && it.lo != null }
        val hardest = (main.filter { it.seconds >= 120 || it.distance >= 400 }.ifEmpty { main }).maxOfOrNull { (it.lo!! + it.hi!!) / 2 }
        return out.map { r ->
            val kind = when {
                r.warm -> Kind.WARMUP
                r.cool -> Kind.COOLDOWN
                r.lo == null || hardest == null -> Kind.OTHER
                r.seconds in 1..39 && r.distance <= 0 -> if ((r.lo!! + r.hi!!) / 2 >= hardest!! * 0.75) Kind.OTHER else Kind.RECOVERY
                (r.lo!! + r.hi!!) / 2 >= hardest!! * 0.75 -> Kind.WORK
                else -> Kind.RECOVERY
            }
            Plan(kind, r.seconds, r.distance, r.lo, r.hi, r.group, r.reps, r.hr)
        }
    }

    private fun fits(p: Plan, seconds: Double, distance: Double): Boolean = when {
        p.seconds > 0 -> seconds in p.seconds * 0.6..p.seconds * 1.5
        p.distance > 0 -> distance in p.distance * 0.75..p.distance * 1.3
        else -> true
    }

    /** Pairs each interval with its planned step: one-to-one when the counts match, else in order by length. */
    fun align(plan: List<Plan>, lengths: List<Pair<Double, Double>>): List<Plan?> {
        if (plan.isEmpty()) return lengths.map { null }
        if (plan.size == lengths.size && plan.indices.all { fits(plan[it], lengths[it].first, lengths[it].second) }) return plan
        var p = 0
        return lengths.map { (sec, dist) ->
            val k = (p until minOf(p + 3, plan.size)).firstOrNull { fits(plan[it], sec, dist) }
            if (k != null) { p = k + 1; plan[k] } else null
        }
    }

    // ---------------------------------------------------------------- build

    private fun stream(streams: JSONArray?, type: String): List<Double?> {
        for (i in 0 until (streams?.length() ?: 0)) {
            val s = streams!!.optJSONObject(i) ?: continue
            if (s.optString("type") == type) {
                val d = s.optJSONArray("data") ?: return emptyList()
                return (0 until d.length()).map { j -> if (d.isNull(j)) null else d.optDouble(j).takeUnless { it.isNaN() } }
            }
        }
        return emptyList()
    }

    private fun share(vals: List<Double?>, from: Int, to: Int, ok: (Double) -> Boolean): Double? {
        var n = 0; var hit = 0
        for (i in from.coerceAtLeast(0)..to.coerceAtMost(vals.lastIndex)) { val v = vals[i] ?: continue; n++; if (ok(v)) hit++ }
        return if (n == 0) null else hit.toDouble() / n
    }

    private fun range(vals: List<Double?>, from: Int, to: Int, floor: Double = Double.NEGATIVE_INFINITY, smooth: Int = 1): Pair<Double, Double>? {
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        val a = from.coerceAtLeast(0); val b = to.coerceAtMost(vals.lastIndex)
        for (i in a..b) {
            // short rolling average so one noisy sample doesn't set the min / max
            var sum = 0.0; var n = 0
            for (j in maxOf(a, i - smooth + 1)..i) { val v = vals[j] ?: continue; sum += v; n++ }
            if (n == 0) continue
            val v = sum / n
            if (v < floor) continue
            if (v < lo) lo = v; if (v > hi) hi = v
        }
        return if (hi < lo) null else lo to hi
    }

    fun build(
        activity: JSONObject, intervals: JSONObject?, streams: JSONArray?, event: JSONObject?, fallbackFtp: Double?,
        fallbackMaxHr: Double? = 194.0, fallbackLthr: Double? = 172.0,
    ): Detail {
        val ftp = activity.num("icu_ftp")?.takeIf { it > 0 } ?: fallbackFtp
        val watts = stream(streams, "watts"); val heart = stream(streams, "heartrate"); val vel = stream(streams, "velocity_smooth")
        val time = stream(streams, "time")
        val maxHr = activity.num("athlete_max_hr")?.takeIf { it > 100 } ?: fallbackMaxHr
        val lthr = activity.num("lthr")?.takeIf { it > 100 } ?: fallbackLthr
        val plan = flattenPlan(event?.optJSONObject("workout_doc"), ftp, maxHr, lthr)
        val ivs = intervals?.optJSONArray("icu_intervals")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
        fun secs(iv: JSONObject) = iv.num("moving_time") ?: iv.num("elapsed_time") ?: 0.0
        val matched = align(plan, ivs.map { secs(it) to (it.num("distance") ?: 0.0) })
        val steps = ivs.mapIndexed { i, iv ->
            val p = matched[i]
            val from = iv.optInt("start_index", -1); val to = iv.optInt("end_index", -1).let { if (it > from) it - 1 else it }
            val has = from >= 0 && to >= from
            val type = iv.optString("type")
            val kind = p?.kind ?: when {
                plan.any { it.loW != null } -> Kind.OTHER
                type.equals("WORK", true) -> Kind.WORK
                type.equals("RECOVERY", true) -> Kind.RECOVERY
                else -> Kind.OTHER
            }
            val wr = if (has) range(watts, from, to, smooth = 3) else null
            val hrr = if (has) range(heart, from, to) else null
            val vr = if (has) range(vel, from, to, floor = 0.6, smooth = 5) else null
            val sec = secs(iv); val dist = iv.num("distance") ?: 0.0
            Step(
                index = i + 1, kind = kind, seconds = sec.toInt(), distance = dist,
                power = Stat((iv.num("average_watts") ?: iv.num("weighted_average_watts"))?.takeIf { it > 0 }, wr?.first ?: iv.num("min_watts"), wr?.second ?: iv.num("max_watts")),
                speed = Stat(iv.num("average_speed")?.takeIf { it > 0 } ?: if (sec > 0 && dist > 0) dist / sec else null, vr?.first ?: iv.num("min_speed"), vr?.second ?: iv.num("max_speed")),
                hr = Stat(iv.num("average_heartrate")?.takeIf { it > 0 }, hrr?.first ?: iv.num("min_heartrate"), hrr?.second ?: iv.num("max_heartrate")),
                cadence = iv.num("average_cadence")?.takeIf { it > 0 }, elevGain = iv.num("total_elevation_gain"),
                loW = p?.loW, hiW = p?.hiW,
                inTarget = if (has && p?.loW != null && p.hiW != null) share(if (p.hrTarget) heart else watts, from, to) { it >= p.loW - 0.5 && it <= p.hiW + 0.5 } else null,
                hr165 = if (has) share(heart, from, to) { it <= 165.0 } else null,
                hr172 = if (has) share(heart, from, to) { it <= 172.0 } else null,
                start = from, end = to, group = p?.group ?: 0, reps = p?.reps ?: 1,
                hrTarget = p?.hrTarget == true && p.loW != null,
            )
        }
        val work = steps.filter { it.isWork }
        val summary = if (work.isEmpty()) null else {
            val t = work.sumOf { it.seconds.toDouble() }.coerceAtLeast(1.0)
            fun wavg(f: (Step) -> Double?): Double? {
                val l = work.filter { f(it) != null }
                return if (l.isEmpty()) null else l.sumOf { f(it)!! * it.seconds } / l.sumOf { it.seconds.toDouble() }.coerceAtLeast(1.0)
            }
            val dist = work.sumOf { it.distance }
            Summary(
                count = work.size, seconds = t.toInt(), distance = dist,
                power = Stat(wavg { it.power.avg }, work.mapNotNull { it.power.min }.minOrNull(), work.mapNotNull { it.power.max }.maxOrNull()),
                speed = Stat(if (dist > 0) dist / t else wavg { it.speed.avg }, work.mapNotNull { it.speed.min }.minOrNull(), work.mapNotNull { it.speed.max }.maxOrNull()),
                hr = Stat(wavg { it.hr.avg }, work.mapNotNull { it.hr.min }.minOrNull(), work.mapNotNull { it.hr.max }.maxOrNull()),
                pctOfTarget = wavg { it.pctOfTarget }, position = wavg { it.position },
                loW = wavg { it.loW }, hiW = wavg { it.hiW },
                inTarget = wavg { it.inTarget }, hr165 = wavg { it.hr165 }, hr172 = wavg { it.hr172 },
                hrTarget = work.all { it.hrTarget },
            )
        }
        // Chart streams, at most ~700 points
        val n = listOf(watts.size, heart.size, vel.size).max()
        val stride = maxOf(1, n / 700)
        fun down(v: List<Double?>): List<Double?> = (0 until n step stride).map { i ->
            var s = 0.0; var c = 0
            for (j in i until minOf(i + stride, v.size)) { val x = v[j] ?: continue; s += x; c++ }
            if (c == 0) null else s / c
        }
        val wAll = if (watts.isNotEmpty()) range(watts, 0, watts.lastIndex, smooth = 3) else null
        val hAll = if (heart.isNotEmpty()) range(heart, 0, heart.lastIndex) else null
        val vAll = if (vel.isNotEmpty()) range(vel, 0, vel.lastIndex, floor = 0.6, smooth = 5) else null
        val sec = (activity.num("moving_time") ?: 0.0); val dist = activity.num("distance") ?: 0.0
        return Detail(
            id = activity.optString("id"), name = activity.optString("name").ifBlank { activity.optString("type", "Workout") },
            date = activity.optString("start_date_local").take(10), type = activity.optString("type"),
            seconds = sec.toInt(), distance = dist, load = activity.num("icu_training_load")?.toInt(), elevGain = activity.num("total_elevation_gain"), ftp = ftp,
            power = Stat((activity.num("icu_average_watts") ?: activity.num("average_watts"))?.takeIf { it > 0 }, wAll?.first, wAll?.second),
            speed = Stat(activity.num("average_speed")?.takeIf { it > 0 } ?: if (sec > 0 && dist > 0) dist / sec else null, vAll?.first, vAll?.second),
            hr = Stat(activity.num("average_heartrate")?.takeIf { it > 0 }, hAll?.first, hAll?.second ?: activity.num("max_heartrate")),
            cadence = activity.num("average_cadence")?.takeIf { it > 0 },
            steps = steps, work = summary, hasTargets = steps.any { it.loW != null },
            t = (0 until n step stride).map { i -> time.getOrNull(i)?.toInt() ?: i }, watts = down(watts), heart = down(heart), vel = down(vel), stride = stride,
        )
    }

    fun fromRaw(raw: JSONObject, fallbackFtp: Double?): Detail =
        build(raw.getJSONObject("a"), raw.optJSONObject("i"), raw.optJSONArray("s"), raw.optJSONObject("e"), fallbackFtp)

    // ---------------------------------------------------------------- cache (one file per workout)

    private fun file(ctx: Context, id: String) = File(File(ctx.filesDir, "workouts").apply { mkdirs() }, id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")
    fun cached(ctx: Context, id: String): JSONObject? = runCatching { file(ctx, id).takeIf { it.exists() }?.let { JSONObject(it.readText()) } }.getOrNull()
    fun save(ctx: Context, id: String, raw: JSONObject) { runCatching { file(ctx, id).writeText(raw.toString()) } }
}
