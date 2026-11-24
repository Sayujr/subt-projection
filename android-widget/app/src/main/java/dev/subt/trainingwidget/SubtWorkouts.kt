package dev.subt.trainingwidget

/**
 * SubT from your SubT workouts: every work rep in runs named like "3'x10 repeats", "6'x5 repeats",
 * "10'x3 repeats" (or "3' repeats"). Reps are grouped by length:
 *   short  ≈ 3′ (under 4½ min) · medium ≈ 6′ (4½–8 min) · long ≈ 10′+ (8 min and over).
 * SubT pace = average pace over all reps (total time ÷ total distance), per month.
 * CP tests / time trials in the same session are left out: named efforts like "12' cpt" aren't
 * SubT reps, and a rep whose power is 50 W or more above its siblings' is treated as a test effort.
 */
object SubtWorkouts {
    /**
     * Your approach changed in spring 2026. Months before this use the original rule (projection
     * page: laps with avg HR > 160, max HR ≤ 172, 1.5–15 min, monthly median); from this month on,
     * the reps in your named SubT workouts (monthly average).
     */
    const val SWITCH_MONTH = "2026-05"

    enum class Kind(val label: String, val color: Int) {
        SHORT("Short (3′)", 0xFFFFC53D.toInt()),
        MEDIUM("Medium (6′)", 0xFFFF9F5A.toInt()),
        LONG("Long (10′+)", 0xFFFF7A90.toInt()),
    }

    /** Reps slower than this aren't SubT (jogs, aborted reps, GPS glitches): 5:45 /km. */
    const val SLOWEST = 5.75

    /**
     * Before [SWITCH_MONTH] (HR-capped SubT): any lap in any run with average HR 155–172 lasting 2–12 min,
     * no slower than 5:45 /km.
     */
    fun oldRuleLap(avgHr: Double?, seconds: Double, pace: Double) =
        avgHr != null && avgHr in 155.0..172.0 && seconds in 120.0..720.0 && pace in 3.3..SLOWEST

    /** From [SWITCH_MONTH] (3-6-10 at power targets): work reps of the named lengths, no slower than 5:45 /km. */
    fun newRuleRep(seconds: Double, pace: Double, lengths: List<Double>) = matchesRep(seconds, lengths) && pace in 3.3..SLOWEST

    /** "3'x10", "6′ x 5", "10'x3 repeats", "3' repeats": the rep lengths in minutes. */
    private val REP = Regex("(\\d+(?:\\.\\d+)?)\\s*['’′]\\s*(?:x\\s*\\d+|repeats|reps)", RegexOption.IGNORE_CASE)
    private val NOT_SUBT = Regex("45\\s*/\\s*15|hill|\\b(200|400|800)s?\\b|stride|fartlek", RegexOption.IGNORE_CASE)

    fun repLengths(name: String): List<Double> = REP.findAll(name).map { it.groupValues[1].toDouble() }.filter { it in 1.5..20.0 }.toList()
    /** Garmin plan names before your Strava name syncs, e.g. "NSM Flex Sub-Ts 160-165". */
    private val SUBT_WORD = Regex("sub[- ]?t(?:hreshold)?s?\\b", RegexOption.IGNORE_CASE)
    fun isSubtWorkout(name: String) = (repLengths(name).isNotEmpty() || SUBT_WORD.containsMatchIn(name)) && !NOT_SUBT.containsMatchIn(name)

    fun kind(seconds: Int): Kind = when {
        seconds < 270 -> Kind.SHORT
        seconds < 480 -> Kind.MEDIUM
        else -> Kind.LONG
    }

    /** Does an interval of [seconds] match one of the named rep lengths (±25 %)? */
    fun matchesRep(seconds: Double, lengths: List<Double>) =
        if (lengths.isEmpty()) seconds in 120.0..900.0 else lengths.any { seconds in it * 60 * 0.75..it * 60 * 1.3 }

    /** Drops test efforts: reps with power 50 W+ above the median power of their kind in the same workout (only when power is recorded). */
    fun dropTestEfforts(reps: List<SubtRep>): List<SubtRep> = reps.groupBy { it.activityId to kind(it.seconds) }.values.flatMap { g ->
        val watts = g.mapNotNull { it.watts }.sorted()
        if (watts.size < 2) g else {
            val med = watts[watts.size / 2]
            g.filter { it.watts == null || it.watts < med + 50 }
        }
    }

    /** Average pace over reps: total time ÷ total distance (min/km). */
    fun avgPace(reps: List<SubtRep>): Double = reps.sumOf { it.seconds.toDouble() } / reps.sumOf { it.seconds / it.pace }

    /**
     * Rep-mix adjustment: each kind's usual gap to your overall SubT pace (over all reps). Short reps run
     * faster than long ones, so a month with more 3′ reps would look faster without this. Each rep is
     * shifted by its kind's gap, then averaged: the headline then moves only when you get faster.
     */
    fun kindOffsets(reps: List<SubtRep>): Map<Kind, Double> {
        if (reps.isEmpty()) return emptyMap()
        val all = avgPace(reps)
        return Kind.entries.associateWith { k -> reps.filter { kind(it.seconds) == k }.takeIf { it.isNotEmpty() }?.let { avgPace(it) - all } ?: 0.0 }
    }
    fun mixAdjusted(reps: List<SubtRep>, offsets: Map<Kind, Double> = kindOffsets(reps)): List<SubtRep> =
        reps.map { it.copy(pace = it.pace - (offsets[kind(it.seconds)] ?: 0.0)) }

    /** Monthly SubT for [reps] (optionally one kind). Months with fewer than 3 reps are marked as outliers. */
    fun months(reps: List<SubtRep>, kind: Kind? = null): List<SubtMonth> =
        reps.filter { kind == null || kind(it.seconds) == kind }.groupBy { it.date.take(7) }.toSortedMap().map { (m, l) ->
            val withHr = l.filter { it.avgHr > 0 }
            val hr = if (withHr.isEmpty()) null else withHr.sumOf { it.avgHr * it.seconds } / withHr.sumOf { it.seconds.toDouble() }
            SubtMonth(m, avgPace(l), l.size, outlier = l.size < 3, hr = hr)
        }

    fun toSubtData(reps: List<SubtRep>, today: java.time.LocalDate = java.time.LocalDate.now()): SubtData {
        val cutoff = today.minusDays(30).toString()
        return SubtData(System.currentTimeMillis(), months(mixAdjusted(reps)), reps.count { it.date >= cutoff }, reps.maxOfOrNull { it.date } ?: "", source = "workouts")
    }

    /**
     * Current SubT: mix-adjusted average of the reps in the [days] days up to [end] (needs 3+ reps).
     * Updates after every session instead of once a month.
     */
    fun current(reps: List<SubtRep>, end: java.time.LocalDate = java.time.LocalDate.now(), days: Long = 28): Pair<Double, Int>? {
        val newReps = reps.filter { it.date.take(7) >= SWITCH_MONTH }
        if (newReps.isEmpty()) return null
        val from = end.minusDays(days - 1).toString(); val to = end.toString()
        val win = mixAdjusted(newReps, kindOffsets(newReps)).filter { it.date in from..to }
        return if (win.size >= 3) avgPace(win) to win.size else null
    }

    /**
     * Trend over the last six regular months: pace change per month (negative = faster) and its
     * standard error, so a real trend can be told from noise.
     */
    fun trendWithError(months: List<SubtMonth>): Pair<Double, Double>? {
        val reg = months.withIndex().filter { !it.value.outlier }.takeLast(6)
        if (reg.size < 3) return null
        val xs = reg.map { it.index.toDouble() }; val ys = reg.map { it.value.pace }
        val mx = xs.average(); val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        if (sxx == 0.0) return null
        val slope = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx
        val resid = xs.indices.sumOf { val e = ys[it] - (my + slope * (xs[it] - mx)); e * e }
        val se = if (reg.size > 2) kotlin.math.sqrt(resid / (reg.size - 2) / sxx) else 0.0
        return slope to se
    }

    /** "˄ 2s faster ±1" style text for a trend (s/km per month). */
    fun trendText(t: Pair<Double, Double>?): String {
        if (t == null) return "–"
        val s = (t.first * 60).roundToIntSafe(); val e = (t.second * 60).roundToIntSafe()
        val pm = if (e > 0) " ±$e" else ""
        return when { s < 0 -> "˄ ${-s}s faster$pm"; s > 0 -> "˅ ${s}s slower$pm"; else -> "– flat$pm" }
    }
    private fun Double.roundToIntSafe() = if (isNaN()) 0 else Math.round(this).toInt()

    /**
     * Both rules on one timeline: months before [SWITCH_MONTH] from the HR-rule laps (average pace),
     * months after from the SubT-workout reps (rep-mix adjusted). Falls back to the projection page's
     * data for the early months only if no early laps have been read yet.
     */
    fun merge(page: SubtData?, reps: List<SubtRep>, today: java.time.LocalDate = java.time.LocalDate.now()): SubtData? {
        val newReps = reps.filter { it.date.take(7) >= SWITCH_MONTH }
        val oldReps = reps.filter { it.date.take(7) < SWITCH_MONTH }
        if (page == null && reps.isEmpty()) return null
        if (reps.isEmpty()) return page
        val before = if (oldReps.isNotEmpty()) months(oldReps) else page?.months.orEmpty().filter { it.month < SWITCH_MONTH }
        val after = if (newReps.isNotEmpty()) months(mixAdjusted(newReps)) else emptyList()
        val cutoff = today.minusDays(30).toString()
        return SubtData(System.currentTimeMillis(), before + after, reps.count { it.date >= cutoff }, reps.maxOfOrNull { it.date } ?: "", source = "mixed")
    }

    /** First day the new rule needs reps from. */
    fun fetchFrom(): java.time.LocalDate = java.time.LocalDate.parse("$SWITCH_MONTH-01")
    /** How far back the HR rule is read (the charts show about 18 months). */
    const val HISTORY_DAYS = 560L

    /** Pace change per month (negative = faster) over the last six regular months, or null. */
    fun trend(months: List<SubtMonth>): Double? = Charts.subtTrendPerMonth(months)
}
