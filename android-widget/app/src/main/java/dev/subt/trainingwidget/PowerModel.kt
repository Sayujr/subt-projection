package dev.subt.trainingwidget

import android.content.Context
import java.time.LocalDate
import kotlin.math.pow

/**
 * Power-based race model. Race power as a share of CP (Stryd-style): 5K 104 %, 10K 100 %, half 94 %,
 * marathon user-set (default 88 %). Speed = running effectiveness × W/kg.
 */
object PowerModel {
    data class Now(
        val cp: Double, val cpSource: String, val kg: Double?, val re: Double?, val powerHr: Double?,
        val cp6wAgo: Double?, val cp3moAgo: Double?, val slopePerMonth: Double?,
    ) {
        val wkg: Double? get() = kg?.let { cp / it }
    }

    val SHARES = listOf(5000.0 to 1.04, 10000.0 to 1.00, 21097.5 to 0.94)

    fun now(ctx: Context): Now? {
        val s = Prefs.snapshot(ctx) ?: return null
        val kg = Prefs.weightOverride(ctx) ?: s.weight
        val withCp = s.runs.filter { it.cp != null }
        val latestCp = withCp.lastOrNull()?.cp
        val (cp, src) = when {
            s.curveCp != null && latestCp != null -> maxOf(s.curveCp, latestCp) to "intervals.icu eFTP / 90-day power curve"
            latestCp != null -> latestCp to "intervals.icu eFTP"
            s.curveCp != null -> s.curveCp to "90-day power curve (3 & 12 min)"
            s.runFtp != null -> s.runFtp to "FTP set in intervals.icu"
            else -> return null
        }
        val today = LocalDate.now()
        fun cpAround(daysAgo: Long) = withCp.lastOrNull { it.date <= today.minusDays(daysAgo).toString() }?.cp
        val pts = withCp.mapNotNull { r -> runCatching { LocalDate.parse(r.date).toEpochDay().toDouble() }.getOrNull()?.let { it to r.cp!! } }
        val slope = if (pts.size >= 5) {
            val mx = pts.map { it.first }.average(); val my = pts.map { it.second }.average()
            val den = pts.sumOf { (it.first - mx).pow(2) }
            if (den > 0) pts.sumOf { (it.first - mx) * (it.second - my) } / den * 30 else null
        } else null
        val recent = s.runs.takeLast(10)
        val re = recent.mapNotNull { it.re(kg) }.takeIf { it.size >= 3 }?.average()
        val phr = recent.mapNotNull { r -> r.watts?.let { w -> r.avgHr?.let { h -> w / h } } }.takeIf { it.size >= 3 }?.average()
        return Now(cp, src, kg, re, phr, cpAround(42), cpAround(90), slope)
    }

    /** Race time (s) for a distance at a share of CP, given RE and weight. */
    fun time(distance: Double, cp: Double, share: Double, re: Double, kg: Double): Double = distance / (re * (cp * share / kg))

    fun fmShare(ctx: Context) = Prefs.fmPct(ctx) / 100.0

    /** CP needed for a target marathon time. */
    fun cpFor(targetSec: Double, share: Double, re: Double, kg: Double): Double = (42195.0 / targetSec) / re * kg / share
}
