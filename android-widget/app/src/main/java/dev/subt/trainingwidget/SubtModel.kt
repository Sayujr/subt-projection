package dev.subt.trainingwidget

/**
 * Port of the projection page's model (index.html): Daniels VDOT T-pace table, an 11 % race-readiness
 * gap and phase-specific monthly improvement rates (s/km per month), using the page's defaults.
 */
object SubtModel {
    // T pace (min/km) -> 5K, HM, FM (seconds)
    private val VDOT = listOf(
        doubleArrayOf(5.45, 1626.0, 7491.0, 15600.0), doubleArrayOf(5.20, 1488.0, 6843.0, 14164.0),
        doubleArrayOf(5.00, 1351.0, 6213.0, 12854.0), doubleArrayOf(4.90, 1313.0, 6037.0, 12476.0),
        doubleArrayOf(4.80, 1273.0, 5854.0, 12092.0), doubleArrayOf(4.71, 1238.0, 5689.0, 11744.0),
        doubleArrayOf(4.63, 1197.0, 5559.0, 11491.0), doubleArrayOf(4.55, 1168.0, 5402.0, 11148.0),
        doubleArrayOf(4.47, 1140.0, 5260.0, 10851.0), doubleArrayOf(4.38, 1108.0, 5126.0, 10568.0),
        doubleArrayOf(4.30, 1080.0, 5033.0, 10463.0), doubleArrayOf(4.20, 1052.0, 4866.0, 10074.0),
        doubleArrayOf(4.13, 1027.0, 4795.0, 9999.0), doubleArrayOf(4.05, 1007.0, 4691.0, 9784.0),
        doubleArrayOf(3.97, 985.0, 4595.0, 9590.0), doubleArrayOf(3.85, 938.0, 4380.0, 9148.0),
        doubleArrayOf(3.72, 908.0, 4241.0, 8862.0), doubleArrayOf(3.60, 873.0, 4079.0, 8527.0),
        doubleArrayOf(3.48, 840.0, 3922.0, 8231.0), doubleArrayOf(3.37, 810.0, 3777.0, 7935.0),
        doubleArrayOf(3.28, 781.0, 3639.0, 7658.0),
    )
    /** The projection page's race-readiness gap. */
    const val PAGE_GAP = 0.11

    /** Gap for 5K predictions (calibrated from your 5K PB when available). */
    @Volatile var GAP = PAGE_GAP
    /** Gap for half / full marathon predictions (calibrated from your half PB, else the 5K gap). */
    @Volatile var GAP_HM = PAGE_GAP
    @Volatile var GAP_FM = PAGE_GAP
    /** Your PBs: predictions are never shown slower than a time you've already run. */
    @Volatile var PB5K: Double? = null
    @Volatile var PBHM: Double? = null
    @Volatile var PBFM: Double? = null

    /** Race gap implied by an actual race time at a given SubT pace (clamped to −5…25 %). */
    fun gapFrom5k(pbSec: Double, subt: Double) = gapFrom(1, pbSec, subt)
    fun gapFrom(idx: Int, pbSec: Double, subt: Double) = (pbSec / interp(subt, idx) - 1).coerceIn(-0.05, 0.25)

    /** Current SubT pace: your Settings value if entered, else the latest regular monthly median. */
    fun currentPace(ctx: android.content.Context): Double? =
        Prefs.subtOverride(ctx) ?: Prefs.subt(ctx)?.months?.lastOrNull { !it.outlier }?.pace

    /** SubT in the month a PB was set (or the latest month). */
    fun subtAt(ctx: android.content.Context, date: String?): Double? {
        val months = Prefs.subt(ctx)?.months.orEmpty().filter { !it.outlier }
        return date?.take(7)?.let { m -> months.lastOrNull { it.month <= m } }?.pace ?: months.lastOrNull()?.pace
    }

    /** Applies the gap choice: per distance from your PBs (5K → 5K; half → half and marathon) or the page default. */
    fun applyGap(ctx: android.content.Context) {
        val pbs = Prefs.effectivePbs(ctx)
        PB5K = pbs.fiveK?.seconds; PBHM = pbs.half?.seconds; PBFM = null
        if (!Prefs.gapFromPb(ctx)) { GAP = PAGE_GAP; GAP_HM = PAGE_GAP; GAP_FM = PAGE_GAP; return }
        val g5 = pbs.fiveK?.let { p -> subtAt(ctx, p.date)?.let { gapFrom(1, p.seconds, it) } }
        val gh = pbs.half?.let { p -> subtAt(ctx, p.date)?.let { gapFrom(2, p.seconds, it) } }
        GAP = g5 ?: gh ?: PAGE_GAP
        GAP_HM = gh ?: g5 ?: PAGE_GAP
        // Marathons usually come out a little slower than half-equivalent for runners without one
        GAP_FM = (gh ?: g5)?.plus(0.02) ?: PAGE_GAP
    }
    const val PEAK = 4 + 32 / 60.0
    const val FLOOR = 3 + 20 / 60.0

    data class Rates(val rebuild: Double, val pre4: Double, val post4: Double, val elite: Double)
    val FAST = Rates(-18.0, -3.0, -2.0, -0.8)
    val REAL = Rates(-12.0, -2.0, -1.2, -0.4)
    val SLOW = Rates(-7.0, -1.0, -0.6, -0.15)

    /** 5K goals from the page, in seconds. */
    val GOALS = listOf(22, 21, 20, 19, 18, 17, 16).map { "Sub-$it 5K" to it * 60.0 }

    private fun interp(subt: Double, idx: Int): Double {
        val s = VDOT.sortedBy { it[0] }
        if (subt <= s.first()[0]) return s.first()[idx]
        if (subt >= s.last()[0]) return s.last()[idx]
        for (i in 0 until s.size - 1) {
            if (s[i][0] <= subt && subt < s[i + 1][0]) {
                val t = (subt - s[i][0]) / (s[i + 1][0] - s[i][0])
                return s[i][idx] + t * (s[i + 1][idx] - s[i][idx])
            }
        }
        return s.last()[idx]
    }

    fun fiveK(subt: Double) = (interp(subt, 1) * (1 + GAP)).let { t -> PB5K?.let { minOf(t, it) } ?: t }
    fun half(subt: Double) = (interp(subt, 2) * (1 + GAP_HM)).let { t -> PBHM?.let { minOf(t, it) } ?: t }
    fun full(subt: Double) = (interp(subt, 3) * (1 + GAP_FM)).let { t -> PBFM?.let { minOf(t, it) } ?: t }

    private fun gapFor(idx: Int) = when (idx) { 1 -> GAP; 2 -> GAP_HM; else -> GAP_FM }

    fun requiredSubtFor5k(targetSec: Double): Double = requiredSubt(1, targetSec)

    /** SubT pace needed for a race time; [idx] 1 = 5K, 2 = HM, 3 = FM. */
    fun requiredSubt(idx: Int, targetSec: Double): Double {
        val t = targetSec / (1 + gapFor(idx))
        val s = VDOT.sortedBy { it[idx] }
        if (t <= s.first()[idx]) return s.first()[0]
        if (t >= s.last()[idx]) return s.last()[0]
        for (i in 0 until s.size - 1) {
            if (s[i][idx] <= t && t < s[i + 1][idx]) {
                val f = (t - s[i][idx]) / (s[i + 1][idx] - s[i][idx])
                return s[i][0] + f * (s[i + 1][0] - s[i][0])
            }
        }
        return s.last()[0]
    }

    // ---- body weight

    /**
     * Pace scales with body mass to this power for fat-only weight change: about 0.7 % faster per 1 %
     * of body weight lost, roughly 2–3 s/km per kg at these paces (the common rule of thumb).
     */
    const val WEIGHT_EXP = 0.7

    data class WeightPlan(val label: String, val kgPerWeek: Double)
    val WEIGHT_PLANS = listOf(
        WeightPlan("Lose 1 kg/wk", -1.0), WeightPlan("Lose 0.5 kg/wk", -0.5), WeightPlan("Lose 0.1 kg/wk", -0.1),
        WeightPlan("Stable", 0.0), WeightPlan("Gain 0.1 kg/wk", 0.1),
    )

    /** Body weight after [months] on a plan, stopping at [goal] when losing (and capped at +8 kg when gaining). */
    fun weightAt(w0: Double, kgPerWeek: Double, months: Double, goal: Double): Double {
        val w = w0 + kgPerWeek * months * 4.345
        return if (kgPerWeek < 0) maxOf(w, minOf(goal, w0)) else minOf(w, w0 + 8)
    }

    fun weightFactor(w0: Double, w: Double) = Math.pow(w / w0, WEIGHT_EXP)

    /** Training-model path with the weight plan applied month by month. */
    fun pathWithWeight(start: Double, r: Rates, months: Int, w0: Double?, kgPerWeek: Double, goal: Double): List<Double> {
        val base = path(start, r, months)
        if (w0 == null || kgPerWeek == 0.0) return base
        return base.mapIndexed { m, p -> maxOf(FLOOR, p * weightFactor(w0, weightAt(w0, kgPerWeek, m.toDouble(), goal))) }
    }

    /** Monthly SubT pace path from [start], index = months from now. */
    fun path(start: Double, r: Rates, months: Int = 36): List<Double> {
        val out = mutableListOf(start)
        var cur = start
        repeat(months) {
            val rate = when {
                cur > PEAK + 0.02 -> r.rebuild
                cur > 4.0 -> r.pre4
                cur > 3.5 -> r.post4
                else -> r.elite
            }
            cur = maxOf(cur + rate / 60.0, FLOOR)
            out += cur
        }
        return out
    }

    /** Sub-3 marathon (2:59:59). */
    const val SUB3 = 2 * 3600 + 59 * 60 + 59.0

    fun arrival(path: List<Double>, target: Double): Int? = path.indexOfFirst { it <= target }.takeIf { it >= 0 }

    fun time(sec: Double): String {
        val s = Math.round(sec).toInt()
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
