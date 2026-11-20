package dev.subt.trainingwidget

/**
 * SubT from your SubT workouts: every work rep in runs named like "3'x10 repeats", "6'x5 repeats",
 * "10'x3 repeats" (or "3' repeats"). Reps are grouped by length:
 *   short  ≈ 3′ (under 4½ min) · medium ≈ 6′ (4½–8 min) · long ≈ 10′+ (8 min and over).
 * SubT pace = average pace over all reps (total time ÷ total distance), per month.
 * CP tests / time trials in the same session are left out: named efforts like "12' cpt" aren't
 * SubT reps, and any rep more than 5 % faster than its siblings is treated as a test effort.
 */
object SubtWorkouts {
    enum class Kind(val label: String, val color: Int) {
        SHORT("Short (3′)", 0xFFFFC53D.toInt()),
        MEDIUM("Medium (6′)", 0xFFFF9F5A.toInt()),
        LONG("Long (10′+)", 0xFFFF7A90.toInt()),
    }

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

    /** Drops test efforts: reps over 5 % faster than the median of their kind in the same workout. */
    fun dropTestEfforts(reps: List<SubtRep>): List<SubtRep> = reps.groupBy { it.activityId to kind(it.seconds) }.values.flatMap { g ->
        val med = g.map { it.pace }.sorted().let { it[it.size / 2] }
        g.filter { it.pace >= med * 0.95 }
    }

    /** Average pace over reps: total time ÷ total distance (min/km). */
    fun avgPace(reps: List<SubtRep>): Double = reps.sumOf { it.seconds.toDouble() } / reps.sumOf { it.seconds / it.pace }

    /** Monthly SubT for [reps] (optionally one kind). Months with fewer than 3 reps are marked as outliers. */
    fun months(reps: List<SubtRep>, kind: Kind? = null): List<SubtMonth> =
        reps.filter { kind == null || kind(it.seconds) == kind }.groupBy { it.date.take(7) }.toSortedMap().map { (m, l) ->
            val t = l.sumOf { it.seconds.toDouble() }
            SubtMonth(m, avgPace(l), l.size, outlier = l.size < 3, hr = l.sumOf { it.avgHr * it.seconds } / t)
        }

    fun toSubtData(reps: List<SubtRep>, today: java.time.LocalDate = java.time.LocalDate.now()): SubtData {
        val cutoff = today.minusDays(30).toString()
        return SubtData(System.currentTimeMillis(), months(reps), reps.count { it.date >= cutoff }, reps.maxOfOrNull { it.date } ?: "", source = "workouts")
    }

    /** Pace change per month (negative = faster) over the last six regular months, or null. */
    fun trend(months: List<SubtMonth>): Double? = Charts.subtTrendPerMonth(months)
}
