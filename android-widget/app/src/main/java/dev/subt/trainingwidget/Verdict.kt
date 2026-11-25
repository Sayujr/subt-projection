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

    /** The facts behind today's call, as (label, value) rows for "Why this call?". */
    fun explain(s: Snapshot?, rhrToday: Double? = null, hrvToday: Double? = null, loads: Map<String, Int>? = null): List<Pair<String, String>> {
        if (s == null || s.days.isEmpty()) return emptyList()
        val d = s.days.last().let { it.copy(rhr = rhrToday ?: it.rhr, hrv = hrvToday ?: it.hrv) }
        val out = mutableListOf<Pair<String, String>>()
        val f = d.form
        out += "Form" to "${if (f > 0) "+" else ""}${f.roundToInt()}  (${FormZone.of(f).label}; Fitness ${d.ctl.roundToInt()} − Fatigue ${d.atl.roundToInt()})"
        if (d.ctl > 0) out += "Fatigue vs Fitness" to "${(d.atl / d.ctl * 100).roundToInt()}%  (spike above 140%)"
        fun normal(vals: List<Double>): Pair<Double, Double>? {
            val base = vals.dropLast(1).takeLast(42)
            if (base.size < 10) return null
            val m = base.average(); val sd = sqrt(base.sumOf { (it - m) * (it - m) } / maxOf(1, base.size - 1))
            return m to sd
        }
        normal(s.days.mapNotNull { it.rhr })?.let { (m, sd) ->
            out += "Resting HR" to (d.rhr?.let { "${it.roundToInt()} bpm" + (if (rhrToday != null) " (yours)" else "") } ?: "no reading today") +
                "  ·  normal ${m.roundToInt()} ± ${maxOf(1, sd.roundToInt())}"
        }
        val hn = normal(s.days.mapNotNull { it.hrv })
        out += "HRV" to (if (hn == null) (d.hrv?.let { "${it.roundToInt()} ms (not enough history yet)" } ?: "no readings in intervals.icu")
            else (d.hrv?.let { "${it.roundToInt()} ms" + (if (hrvToday != null) " (yours)" else "") } ?: "no reading today") + "  ·  normal ${hn.first.roundToInt()} ± ${maxOf(1, hn.second.roundToInt())}")
        val sl = d.sleepSecs?.div(3600)?.takeIf { it <= 14 }
        val sl7 = s.days.takeLast(7).mapNotNull { it.sleepSecs?.div(3600)?.takeIf { h -> h <= 14 } }
        if (sl != null || sl7.isNotEmpty()) out += "Sleep" to listOfNotNull(sl?.let { "%.1f h last night".format(it) }, sl7.takeIf { it.size >= 3 }?.let { "%.1f h 7-day average".format(it.average()) }).joinToString("  ·  ")
        run {
            val t = LocalDate.now()
            val byDate = s.days.associateBy { it.date }
            val fmt = java.time.format.DateTimeFormatter.ofPattern("EEE", java.util.Locale.getDefault())
            val l3 = (3 downTo 1).map { k ->
                val day = t.minusDays(k.toLong())
                val l = byDate[day.toString()]?.load?.roundToInt() ?: loads?.get(day.toString())
                "${day.format(fmt)} ${l ?: "–"}"
            }
            out += "Load last 3 days" to l3.joinToString(" · ")
        }
        val drift = s.runs.filter { it.seconds >= 1800 }.mapNotNull { it.decoupling }.takeLast(3)
        if (drift.isNotEmpty()) out += "HR drift, last runs" to drift.joinToString(" · ") { "${it.roundToInt()}%" } + "  (over 10% is high)"
        s.races.firstOrNull { it.date >= LocalDate.now().toString() }?.let { r ->
            out += "Next race" to "${r.name.take(24)} in ${ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(r.date))} days"
        }
        return out
    }
}
