package dev.subt.trainingwidget

import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Race-morning targets from a formula rather than single numbers.
 *
 *  Reference peak P = 50 % × 2-month Fitness peak + 30 % × 3-month peak + 20 % × 4-month peak
 *  (the recent build counts most, older peaks anchor it).
 *
 *  Fatigue target = f × P, with f by priority (A 0.66 · B 0.72 · C 0.80), adjusted for distance
 *  (marathon −0.05 · half −0.02 · 10K 0 · 5K +0.03) and preset (strict −0.04 · relaxed +0.04),
 *  then blended 50/50 with your own past races where you improved (their race-eve Fatigue ÷ P).
 *  Range: (f − 0.04)…(f + 0.03) × P.
 *
 *  Fitness floor = g × P, g by distance (marathon 0.92 · half 0.90 · 10K 0.88 · 5K 0.87),
 *  A race −0.01, preset strict +0.02 · relaxed −0.03.
 */
object Taper {
    data class Targets(
        val p60: Double?, val p90: Double?, val p120: Double?, val ref: Double,
        val distance: RaceDistance,
        val baseF: Double, val personalF: Double?, val personalN: Int, val f: Double,
        val fatLo: Double, val fatTarget: Double, val fatHi: Double,
        val g: Double, val floor: Double,
    )

    fun reference(p60: Double?, p90: Double?, p120: Double?): Double? {
        val parts = listOfNotNull(p60?.let { it to 0.5 }, p90?.let { it to 0.3 }, p120?.let { it to 0.2 })
        if (parts.isEmpty()) return null
        return parts.sumOf { it.first * it.second } / parts.sumOf { it.second }
    }

    private fun baseFactor(category: String) = when (category) { "RACE_A" -> 0.66; "RACE_C" -> 0.80; else -> 0.72 }
    private fun distAdj(d: RaceDistance) = when (d) { RaceDistance.FULL -> -0.05; RaceDistance.HALF -> -0.02; RaceDistance.K5 -> 0.03; else -> 0.0 }
    private fun floorFactor(d: RaceDistance) = when (d) { RaceDistance.FULL -> 0.92; RaceDistance.HALF -> 0.90; RaceDistance.K10 -> 0.88; RaceDistance.K5 -> 0.87; else -> 0.89 }

    /** Past races that beat the previous race at the same distance. */
    fun improvedRaces(history: List<RaceResult>): List<RaceResult> {
        val out = mutableListOf<RaceResult>()
        history.sortedBy { it.date }.groupBy { RaceDistance.of(it.distanceM, it.name) }.forEach { (dist, list) ->
            if (dist == RaceDistance.OTHER) return@forEach
            list.zipWithNext().forEach { (prev, next) ->
                if (prev.seconds != null && next.seconds != null && next.seconds < prev.seconds) out += next
            }
        }
        return out
    }

    fun targets(series: List<Day>, raceDate: LocalDate, category: String, distance: RaceDistance, history: List<RaceResult>, preset: Int): Targets? {
        val eve = raceDate.minusDays(1).toString()
        fun peak(days: Long) = series.filter { it.date >= raceDate.minusDays(days).toString() && it.date <= eve }.maxOfOrNull { it.ctl }
        val p60 = peak(60); val p90 = peak(90); val p120 = peak(120)
        val ref = reference(p60, p90, p120) ?: return null
        val presetF = when (preset) { 0 -> -0.04; 2 -> 0.04; else -> 0.0 }
        val baseF = baseFactor(category) + distAdj(distance) + presetF
        // Personal calibration from races where you improved
        val good = improvedRaces(history).mapNotNull { r ->
            val rr = reference(r.peak60, r.peak90, r.peak120)
            if (rr != null && r.eveAtl != null && rr > 0) r.eveAtl / rr else null
        }.filter { it in 0.4..1.2 }
        val personalF = if (good.isNotEmpty()) good.average() else null
        val f = if (personalF != null) 0.5 * baseF + 0.5 * personalF else baseF
        val presetG = when (preset) { 0 -> 0.02; 2 -> -0.03; else -> 0.0 }
        val g = floorFactor(distance) - (if (category == "RACE_A") 0.01 else 0.0) + presetG
        return Targets(p60, p90, p120, ref, distance, baseF, personalF, good.size, f,
            (f - 0.04) * ref, f * ref, (f + 0.03) * ref, g, g * ref)
    }

    fun pct(v: Double) = "${(v * 100).roundToInt()}%"
}
