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
    const val GAP = 0.11
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

    fun fiveK(subt: Double) = interp(subt, 1) * (1 + GAP)
    fun half(subt: Double) = interp(subt, 2) * (1 + GAP)
    fun full(subt: Double) = interp(subt, 3) * (1 + GAP)

    fun requiredSubtFor5k(targetSec: Double): Double {
        val t = targetSec / (1 + GAP)
        val s = VDOT.sortedBy { it[1] }
        if (t <= s.first()[1]) return s.first()[0]
        if (t >= s.last()[1]) return s.last()[0]
        for (i in 0 until s.size - 1) {
            if (s[i][1] <= t && t < s[i + 1][1]) {
                val f = (t - s[i][1]) / (s[i + 1][1] - s[i][1])
                return s[i][0] + f * (s[i + 1][0] - s[i][0])
            }
        }
        return s.last()[0]
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

    fun arrival(path: List<Double>, target: Double): Int? = path.indexOfFirst { it <= target }.takeIf { it >= 0 }

    fun time(sec: Double): String {
        val s = Math.round(sec).toInt()
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
