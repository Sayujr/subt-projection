package dev.subt.trainingwidget

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Today's Push / Hold / Back off call, from load balance and recovery signals. */
object Verdict {
    enum class Call(val label: String, val color: Int) {
        PUSH("Push", 0xFF3DD68C.toInt()),
        HOLD("Hold", 0xFFFFC53D.toInt()),
        BACK_OFF("Back off", 0xFFFF7A7A.toInt()),
        TAPER("Taper", 0xFF5EC8FF.toInt()),
    }

    data class Result(val call: Call, val reasons: List<Pair<Int, String>>, val headline: String)

    private const val GOOD = 0xFF3DD68C.toInt()
    private const val WARN = 0xFFFFC53D.toInt()
    private const val BAD = 0xFFFF7A7A.toInt()

    fun compute(s: Snapshot?, rhrToday: Double? = null, hrvToday: Double? = null): Result? {
        if (s == null || s.days.isEmpty()) return null
        // Your entered RHR / HRV (Settings) are treated as today's readings
        val d = s.days.last().let { it.copy(rhr = rhrToday ?: it.rhr, hrv = hrvToday ?: it.hrv) }
        val reasons = mutableListOf<Pair<Int, String>>()
        var score = 0

        // Form & fatigue balance
        val form = d.form
        when {
            form < -25 -> { score -= 2; reasons += BAD to "Form ${form.roundToInt()}: deep fatigue" }
            form < -15 -> { score -= 1; reasons += WARN to "Form ${form.roundToInt()}: carrying fatigue" }
            form > 15 -> reasons += GOOD to "Form +${form.roundToInt()}: fresh"
            else -> reasons += GOOD to "Form ${if (form > 0) "+" else ""}${form.roundToInt()}: in balance"
        }
        val ratio = if (d.ctl > 0) d.atl / d.ctl else 1.0
        if (ratio > 1.4) { score -= 1; reasons += WARN to "Fatigue is ${(ratio * 100).roundToInt()}% of Fitness (spike)" }

        // Resting HR vs normal
        val rhr = s.days.mapNotNull { it.rhr }
        if (rhr.size >= 10 && d.rhr != null) {
            val base = rhr.dropLast(1).takeLast(42)
            val m = base.average(); val sd = sqrt(base.sumOf { (it - m) * (it - m) } / maxOf(1, base.size - 1))
            val dlt = d.rhr - m
            when {
                dlt >= maxOf(5.0, 2 * sd) -> { score -= 2; reasons += BAD to "Resting HR ${d.rhr.roundToInt()} (+${dlt.roundToInt()} on normal)" }
                dlt >= maxOf(3.0, sd) -> { score -= 1; reasons += WARN to "Resting HR up ${dlt.roundToInt()} bpm" }
                else -> reasons += GOOD to "Resting HR normal"
            }
        }
        // HRV vs normal
        val hrv = s.days.mapNotNull { it.hrv }
        if (hrv.size >= 10 && d.hrv != null) {
            val base = hrv.dropLast(1).takeLast(42)
            val m = base.average(); val sd = sqrt(base.sumOf { (it - m) * (it - m) } / maxOf(1, base.size - 1))
            if (d.hrv < m - sd) { score -= 2; reasons += BAD to "HRV ${d.hrv.roundToInt()} below normal" }
            else reasons += GOOD to "HRV normal"
        }
        // Sleep
        val sleep = s.days.mapNotNull { it.sleepSecs?.div(3600)?.takeIf { h -> h <= 14 } }
        val last = d.sleepSecs?.div(3600)?.takeIf { it <= 14 }
        if (last != null && last < 6) { score -= 1; reasons += WARN to "Short sleep (${"%.1f".format(last)} h)" }
        if (sleep.size >= 5 && sleep.takeLast(7).average() < 6.5) { score -= 1; reasons += WARN to "Sleep averaging ${"%.1f".format(sleep.takeLast(7).average())} h" }
        // HR drift on recent runs
        val drift = s.runs.filter { it.seconds >= 1800 }.mapNotNull { it.decoupling }.takeLast(3)
        if (drift.size >= 2 && drift.average() > 10) { score -= 1; reasons += WARN to "High HR drift on recent runs" }

        // Taper window overrides
        val today = LocalDate.now()
        val race = s.races.firstOrNull { it.date >= today.toString() }
        val taperDays = when (race?.category) { "RACE_A" -> 14L; "RACE_B" -> 10L; "RACE_C" -> 5L; else -> 0L }
        val daysTo = race?.let { ChronoUnit.DAYS.between(today, LocalDate.parse(it.date)) } ?: Long.MAX_VALUE
        val call = when {
            daysTo <= taperDays -> Call.TAPER
            score <= -3 -> Call.BACK_OFF
            score <= -1 -> Call.HOLD
            else -> Call.PUSH
        }
        val headline = when (call) {
            Call.PUSH -> "Good day for quality work"
            Call.HOLD -> "Train, but keep intensity controlled"
            Call.BACK_OFF -> "Easy or rest today"
            Call.TAPER -> "Taper: ${race?.name?.take(22)} in $daysTo d. Keep it short and sharp"
        }
        return Result(call, reasons, headline)
    }
}
