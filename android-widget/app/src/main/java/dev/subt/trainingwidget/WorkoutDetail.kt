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
    enum class Kind(val label: String) { WARMUP("Warm-up"), WORK("Work"), TEST("Test"), RECOVERY("Recovery"), COOLDOWN("Cool-down"), OTHER("Step") }

    data class Stat(val avg: Double?, val min: Double?, val max: Double?)
    /** Seconds and metres in a heart-rate band, and its share (0–1) of the step's heart-rate samples. */
    data class Cut(val sec: Double, val m: Double, val frac: Double)
    /** [loW]/[hiW] hold the target range: watts, or bpm when [hrTarget]. */
    data class Plan(val kind: Kind, val seconds: Int, val distance: Double, val loW: Double?, val hiW: Double?, val group: Int, val reps: Int, val hrTarget: Boolean = false)

    data class Step(
        val index: Int, val kind: Kind, val seconds: Int, val distance: Double,
        val power: Stat, val speed: Stat, val hr: Stat, val cadence: Double?, val elevGain: Double?,
        val loW: Double?, val hiW: Double?,
        /** Shares (0–1, sum to 1) of judged samples under / inside / over the target range, with seconds and metres. */
        val under: Double?, val inT: Double?, val over: Double?,
        val underSec: Double, val inSec: Double, val overSec: Double,
        val underM: Double, val inM: Double, val overM: Double,
        /** Share of the step (0–1) with HR at or under 165 / 172. */
        val hr165: Double?, val hr172: Double?,
        /** HR 155–165, 155–172 and over 172 across all samples of the step; [hrN] = samples with HR. */
        val hrA: Cut?, val hrB: Cut?, val hrOver: Cut?, val hrN: Double,
        val start: Int, val end: Int, val group: Int, val reps: Int,
        /** The target range is heart rate (bpm), not power. */
        val hrTarget: Boolean = false,
    ) {
        val isWork get() = kind == Kind.WORK
        val isTest get() = kind == Kind.TEST
        /** Share of the step judged at or above the bottom of the range. */
        val inOrOver: Double? get() = if (inT != null && over != null) inT + over else null
        val inTarget: Double? get() = inT
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
        val under: Double?, val inT: Double?, val over: Double?,
        val underSec: Double, val inSec: Double, val overSec: Double,
        val underM: Double, val inM: Double, val overM: Double,
        val hr165: Double?, val hr172: Double?,
        val hrA: Cut?, val hrB: Cut?, val hrOver: Cut?,
        val hrTarget: Boolean = false,
    ) {
        val inTarget: Double? get() = inT
        val inOrOver: Double? get() = if (inT != null && over != null) inT + over else null
        val targetAvg: Double? get() = if (hrTarget) hr.avg else power.avg
    }

    data class Detail(
        val id: String, val name: String, val date: String, val type: String,
        val seconds: Int, val distance: Double, val load: Int?, val elevGain: Double?, val ftp: Double?,
        val power: Stat, val speed: Stat, val hr: Stat, val cadence: Double?,
        val steps: List<Step>, val work: Summary?, val hasTargets: Boolean,
        /** Samples (s) where speed was under 10:00 /km or power under 40 % of FTP: left out of min values (runs only). */
        val stoppedSec: Int = 0,
        /** Down-sampled streams for the chart (same length): seconds, watts, HR, speed m/s. */
        val t: List<Int>, val watts: List<Double?>, val heart: List<Double?>, val vel: List<Double?>,
        /** Stream index → chart index factor. */
        val stride: Int,
    ) {
        val isRide get() = type.contains("Ride", ignoreCase = true)
        val tests get() = steps.filter { it.isTest }
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
        // A test effort: a long (1 min / 400 m) power step at 105 % of FTP or more. It doesn't set the bar for work steps.
        fun isTest(r: Raw) = !r.warm && !r.cool && !r.hr && r.lo != null && ftp != null && ftp > 0 &&
            (r.lo + r.hi!!) / 2 >= ftp * 1.05 && (r.seconds >= 60 || r.distance >= 400)
        val basis = main.filter { !isTest(it) }.ifEmpty { main }
        val hardest = (basis.filter { it.seconds >= 120 || it.distance >= 400 }.ifEmpty { basis }).maxOfOrNull { (it.lo!! + it.hi!!) / 2 }
        return out.map { r ->
            val kind = when {
                r.warm -> Kind.WARMUP
                r.cool -> Kind.COOLDOWN
                isTest(r) -> Kind.TEST
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

    internal fun stream(streams: JSONArray?, type: String): List<Double?> {
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

    /**
     * Min and max of [vals] over from..to (lo/hi null when nothing counts). A short rolling average of [smooth]
     * samples keeps one noisy sample from setting either. With [stop], windows touching a stopped sample
     * don't count towards the min.
     */
    private fun range(vals: List<Double?>, from: Int, to: Int, floor: Double = Double.NEGATIVE_INFINITY, smooth: Int = 1, stop: BooleanArray? = null): Pair<Double?, Double?>? {
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        val a = from.coerceAtLeast(0); val b = to.coerceAtMost(vals.lastIndex)
        for (i in a..b) {
            var sum = 0.0; var n = 0; var clean = true
            for (j in maxOf(a, i - smooth + 1)..i) {
                if (stop != null && stop.getOrElse(j) { false }) clean = false
                val v = vals[j] ?: continue; sum += v; n++
            }
            if (n == 0) continue
            val v = sum / n
            if (v < floor) continue
            if (clean && v < lo) lo = v
            if (v > hi) hi = v
        }
        return if (hi == -Double.MAX_VALUE) null else (if (lo == Double.MAX_VALUE) null else lo) to hi
    }

    private class Tally { var u = 0; var i = 0; var o = 0; var um = 0.0; var im = 0.0; var om = 0.0 }

    /** Counts samples under / inside / over [lo]..[hi] (±0.5); metres are speed × 1 s, no speed = 0. */
    private fun tally(vals: List<Double?>, vel: List<Double?>, from: Int, to: Int, lo: Double, hi: Double): Tally? {
        val t = Tally(); var n = 0
        for (k in from.coerceAtLeast(0)..to.coerceAtMost(vals.lastIndex)) {
            val v = vals[k] ?: continue
            val m = vel.getOrNull(k) ?: 0.0
            n++
            when {
                v < lo - 0.5 -> { t.u++; t.um += m }
                v > hi + 0.5 -> { t.o++; t.om += m }
                else -> { t.i++; t.im += m }
            }
        }
        return if (n == 0) null else t
    }

    /** Samples of [from]..[to] with HR in [lo, hi) (hi null = no upper limit), as seconds and metres. */
    private fun cut(heart: List<Double?>, vel: List<Double?>, from: Int, to: Int, lo: Double, hi: Double?): Pair<Double, Double> {
        var s = 0.0; var m = 0.0
        for (k in from.coerceAtLeast(0)..to.coerceAtMost(heart.lastIndex)) {
            val h = heart[k] ?: continue
            if (h >= lo && (hi == null || h < hi)) { s += 1.0; m += vel.getOrNull(k) ?: 0.0 }
        }
        return s to m
    }

    /** Seconds at the start of a work step left out of min / max and time in target (getting up to power). */
    const val RAMP = 5
    /** HR lags power: its minimum is read after this many seconds into a work step. */
    const val HR_LAG = 60
    /** Rolling average over the last [window] samples (how targets are judged: 1-second power is too jumpy). */
    fun smooth(v: List<Double?>, window: Int = 10): List<Double?> {
        val out = ArrayList<Double?>(v.size)
        var sum = 0.0; var cnt = 0
        for (i in v.indices) {
            v[i]?.let { sum += it; cnt++ }
            if (i >= window) v[i - window]?.let { sum -= it; cnt-- }
            out += if (v[i] == null || cnt == 0) null else sum / cnt
        }
        return out
    }
    /** [smooth] over from..to only (windows never reach back into the previous step); null outside. Same size as [v]. */
    fun stepSmooth(v: List<Double?>, from: Int, to: Int, window: Int = 10): List<Double?> {
        val a = from.coerceAtLeast(0); val b = (to + 1).coerceAtMost(v.size)
        if (a >= b) return emptyList()
        return List<Double?>(a) { null } + smooth(v.subList(a, b), window)
    }
    /** First sample of a work step that counts, after the ramp (at most a quarter of the step). */
    fun afterRamp(from: Int, to: Int, skip: Int = RAMP): Int = from + minOf(skip, (to - from + 1) / 4)
    /** Steps shorter than this (e.g. 45 s reps) are judged second by second: no ramp skip, no smoothing, no HR lag skip. */
    const val SHORT_STEP = 90
    fun isShort(from: Int, to: Int): Boolean = to - from + 1 < SHORT_STEP
    /** First sample that counts for a work step: the start for short steps, else after the ramp. */
    fun rampStart(from: Int, to: Int): Int = if (isShort(from, to)) from else afterRamp(from, to)
    /** Speed under 10:00 /km, or power under 40 % of FTP: a stop or walking, left out of min values. */
    const val STOP_SPEED = 1.667
    fun isStopped(speed: Double?, watts: Double?, ftp: Double?): Boolean =
        (speed != null && speed < STOP_SPEED) || (ftp != null && watts != null && watts < 0.4 * ftp)

    fun build(
        activity: JSONObject, intervals: JSONObject?, streams: JSONArray?, event: JSONObject?, fallbackFtp: Double?,
        fallbackMaxHr: Double? = 194.0, fallbackLthr: Double? = 172.0,
    ): Detail {
        val ftp = activity.num("icu_ftp")?.takeIf { it > 0 } ?: fallbackFtp
        val watts = stream(streams, "watts"); val heart = stream(streams, "heartrate"); val vel = stream(streams, "velocity_smooth")
        val time = stream(streams, "time")
        val wattsS = smooth(watts)
        val maxHr = activity.num("athlete_max_hr")?.takeIf { it > 100 } ?: fallbackMaxHr
        val lthr = activity.num("lthr")?.takeIf { it > 100 } ?: fallbackLthr
        val isRun = activity.optString("type").contains("Run", ignoreCase = true)
        val nAll = maxOf(watts.size, vel.size)
        val stop: BooleanArray? = if (isRun) BooleanArray(nAll) { isStopped(vel.getOrNull(it), watts.getOrNull(it), ftp) } else null
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
            // Work steps: ignore the first seconds (getting up to power; HR catching up) so min / max
            // and time in target describe the rep itself. Power is judged on a 10 s rolling average.
            // Steps under 90 s (45/15 reps…) are judged raw: no ramp skip, no smoothing.
            val isWorkStep = kind == Kind.WORK || kind == Kind.TEST
            val short = isWorkStep && has && isShort(from, to)
            val f2 = if (isWorkStep && has) rampStart(from, to) else from
            val fHr = if (isWorkStep && has) (if (short) from else afterRamp(from, to, HR_LAG)) else from
            val pSeries = if (short) watts else if (isWorkStep && has) stepSmooth(watts, f2, to) else wattsS
            val wr = if (has) range(pSeries, f2, to, stop = stop) else null
            val hrr = if (has) range(heart, fHr, to, stop = stop) else null
            val vr = if (has) range(vel, f2, to, floor = 0.6, smooth = 5, stop = stop) else null
            val tl = if (has && p?.loW != null && p.hiW != null) tally(if (p.hrTarget) heart else pSeries, vel, if (p.hrTarget) fHr else f2, to, p.loW, p.hiW) else null
            val jn = tl?.let { (it.u + it.i + it.o).toDouble() }
            fun frac(c: Int?) = if (tl == null || jn == null) null else c!! / jn
            val hn = if (has) (from.coerceAtLeast(0)..to.coerceAtMost(heart.lastIndex)).count { heart[it] != null }.toDouble() else 0.0
            fun hcut(lo: Double, hi: Double?): Cut? {
                if (!has || hn <= 0) return null
                val (cs, cm) = cut(heart, vel, from, to, lo, hi)
                return Cut(cs, cm, cs / hn)
            }
            val sec = secs(iv); val dist = iv.num("distance") ?: 0.0
            Step(
                index = i + 1, kind = kind, seconds = sec.toInt(), distance = dist,
                power = Stat((iv.num("average_watts") ?: iv.num("weighted_average_watts"))?.takeIf { it > 0 }, wr?.first ?: iv.num("min_watts").takeIf { stop == null || !has }, wr?.second ?: iv.num("max_watts")),
                speed = Stat(iv.num("average_speed")?.takeIf { it > 0 } ?: if (sec > 0 && dist > 0) dist / sec else null, vr?.first ?: iv.num("min_speed").takeIf { stop == null || !has }, vr?.second ?: iv.num("max_speed")),
                hr = Stat(iv.num("average_heartrate")?.takeIf { it > 0 }, hrr?.first ?: iv.num("min_heartrate").takeIf { stop == null || !has }, hrr?.second ?: iv.num("max_heartrate")),
                cadence = iv.num("average_cadence")?.takeIf { it > 0 }, elevGain = iv.num("total_elevation_gain"),
                loW = p?.loW, hiW = p?.hiW,
                under = frac(tl?.u), inT = frac(tl?.i), over = frac(tl?.o),
                underSec = tl?.u?.toDouble() ?: 0.0, inSec = tl?.i?.toDouble() ?: 0.0, overSec = tl?.o?.toDouble() ?: 0.0,
                underM = tl?.um ?: 0.0, inM = tl?.im ?: 0.0, overM = tl?.om ?: 0.0,
                hr165 = if (has) share(heart, from, to) { it <= 165.0 } else null,
                hr172 = if (has) share(heart, from, to) { it <= 172.0 } else null,
                hrA = hcut(155.0, 165.0), hrB = hcut(155.0, 172.0), hrOver = hcut(172.0, null), hrN = hn,
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
            val jw = work.filter { it.inT != null }
            val uS = jw.sumOf { it.underSec }; val iS = jw.sumOf { it.inSec }; val oS = jw.sumOf { it.overSec }
            val uM = jw.sumOf { it.underM }; val iM = jw.sumOf { it.inM }; val oM = jw.sumOf { it.overM }
            val jt = uS + iS + oS
            val hnAll = work.sumOf { it.hrN }
            fun hsum(f: (Step) -> Cut?): Cut? {
                if (hnAll <= 0) return null
                val s = work.sumOf { f(it)?.sec ?: 0.0 }
                return Cut(s, work.sumOf { f(it)?.m ?: 0.0 }, s / hnAll)
            }
            Summary(
                count = work.size, seconds = t.toInt(), distance = dist,
                power = Stat(wavg { it.power.avg }, work.mapNotNull { it.power.min }.minOrNull(), work.mapNotNull { it.power.max }.maxOrNull()),
                speed = Stat(if (dist > 0) dist / t else wavg { it.speed.avg }, work.mapNotNull { it.speed.min }.minOrNull(), work.mapNotNull { it.speed.max }.maxOrNull()),
                hr = Stat(wavg { it.hr.avg }, work.mapNotNull { it.hr.min }.minOrNull(), work.mapNotNull { it.hr.max }.maxOrNull()),
                pctOfTarget = wavg { it.pctOfTarget }, position = wavg { it.position },
                loW = wavg { it.loW }, hiW = wavg { it.hiW },
                under = if (jt > 0) uS / jt else null, inT = if (jt > 0) iS / jt else null, over = if (jt > 0) oS / jt else null,
                underSec = uS, inSec = iS, overSec = oS, underM = uM, inM = iM, overM = oM,
                hr165 = wavg { it.hr165 }, hr172 = wavg { it.hr172 },
                hrA = hsum { it.hrA }, hrB = hsum { it.hrB }, hrOver = hsum { it.hrOver },
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
        val wAll = if (watts.isNotEmpty()) range(watts, 0, watts.lastIndex, smooth = 3, stop = stop) else null
        val hAll = if (heart.isNotEmpty()) range(heart, 0, heart.lastIndex, stop = stop) else null
        val vAll = if (vel.isNotEmpty()) range(vel, 0, vel.lastIndex, floor = 0.6, smooth = 5, stop = stop) else null
        val sec = (activity.num("moving_time") ?: 0.0); val dist = activity.num("distance") ?: 0.0
        return Detail(
            id = activity.optString("id"), name = activity.optString("name").ifBlank { activity.optString("type", "Workout") },
            date = activity.optString("start_date_local").take(10), type = activity.optString("type"),
            seconds = sec.toInt(), distance = dist, load = activity.num("icu_training_load")?.toInt(), elevGain = activity.num("total_elevation_gain"), ftp = ftp,
            power = Stat((activity.num("icu_average_watts") ?: activity.num("average_watts"))?.takeIf { it > 0 }, wAll?.first, wAll?.second),
            speed = Stat(activity.num("average_speed")?.takeIf { it > 0 } ?: if (sec > 0 && dist > 0) dist / sec else null, vAll?.first, vAll?.second),
            hr = Stat(activity.num("average_heartrate")?.takeIf { it > 0 }, hAll?.first, hAll?.second ?: activity.num("max_heartrate")),
            cadence = activity.num("average_cadence")?.takeIf { it > 0 },
            steps = steps, work = summary, hasTargets = steps.any { it.loW != null }, stoppedSec = stop?.count { it } ?: 0,
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
