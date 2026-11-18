package dev.subt.trainingwidget

import org.json.JSONArray
import org.json.JSONObject

/** One day of intervals.icu wellness data. Form (TSB) = Fitness (CTL) - Fatigue (ATL). */
data class Day(
    val date: String,
    val ctl: Double,
    val atl: Double,
    val hrv: Double? = null,
    val rhr: Double? = null,
    val sleepSecs: Double? = null,
) {
    val form: Double get() = ctl - atl
}

enum class WorkoutStatus { PLANNED, RACE, DONE }

data class Workout(
    val name: String,
    val type: String,
    val seconds: Int,
    val load: Int,
    val distanceM: Double,
    val status: WorkoutStatus,
    /** Local date yyyy-MM-dd. */
    val date: String = "",
)

/** An upcoming race from the intervals.icu calendar, with projected race-day Fitness / Fatigue when known. */
data class Race(
    val date: String,
    val name: String,
    val type: String,
    val category: String,
    val projCtl: Double? = null,
    val projAtl: Double? = null,
) {
    val projForm: Double? get() = if (projCtl != null && projAtl != null) projCtl - projAtl else null
}

data class Snapshot(
    val fetchedAt: Long,
    /** Local date (yyyy-MM-dd) the data belongs to. */
    val today: String,
    val days: List<Day>,
    /** Today's workouts. */
    val workouts: List<Workout>,
    /** Everything this week (Mon–Sun): planned, races and completed. */
    val week: List<Workout> = emptyList(),
    /** Total load completed last week (Mon–Sun). */
    val lastWeekLoad: Int = 0,
    val races: List<Race> = emptyList(),
) {
    fun toJson(): String = JSONObject().apply {
        put("fetchedAt", fetchedAt)
        put("today", today)
        put("days", JSONArray().apply {
            days.forEach {
                put(
                    JSONObject().put("d", it.date).put("c", it.ctl).put("a", it.atl)
                        .putOpt("h", it.hrv).putOpt("r", it.rhr).putOpt("s", it.sleepSecs)
                )
            }
        })
        put("workouts", workoutsJson(workouts))
        put("week", workoutsJson(week))
        put("lastWeekLoad", lastWeekLoad)
        put("races", JSONArray().apply {
            races.forEach {
                put(
                    JSONObject().put("d", it.date).put("n", it.name).put("t", it.type).put("c", it.category)
                        .putOpt("pc", it.projCtl).putOpt("pa", it.projAtl)
                )
            }
        })
    }.toString()

    companion object {
        private fun workoutsJson(list: List<Workout>) = JSONArray().apply {
            list.forEach {
                put(
                    JSONObject()
                        .put("n", it.name).put("t", it.type).put("s", it.seconds)
                        .put("l", it.load).put("m", it.distanceM).put("st", it.status.name).put("d", it.date)
                )
            }
        }

        private fun parseWorkouts(a: JSONArray?): List<Workout> = if (a == null) emptyList() else
            (0 until a.length()).map {
                val x = a.getJSONObject(it)
                Workout(
                    x.getString("n"), x.getString("t"), x.getInt("s"), x.getInt("l"),
                    x.getDouble("m"), WorkoutStatus.valueOf(x.getString("st")), x.optString("d"),
                )
            }

        private fun JSONObject.optNullableDouble(k: String): Double? =
            if (has(k) && !isNull(k)) optDouble(k).takeUnless { it.isNaN() } else null

        fun fromJson(s: String): Snapshot? = try {
            val o = JSONObject(s)
            val d = o.getJSONArray("days")
            val r = o.optJSONArray("races")
            Snapshot(
                fetchedAt = o.getLong("fetchedAt"),
                today = o.getString("today"),
                days = (0 until d.length()).map {
                    val x = d.getJSONObject(it)
                    Day(
                        x.getString("d"), x.getDouble("c"), x.getDouble("a"),
                        x.optNullableDouble("h"), x.optNullableDouble("r"), x.optNullableDouble("s"),
                    )
                },
                workouts = parseWorkouts(o.getJSONArray("workouts")),
                week = parseWorkouts(o.optJSONArray("week")),
                lastWeekLoad = o.optInt("lastWeekLoad"),
                races = if (r == null) emptyList() else (0 until r.length()).map {
                    val x = r.getJSONObject(it)
                    Race(
                        x.getString("d"), x.getString("n"), x.optString("t"), x.optString("c"),
                        x.optNullableDouble("pc"), x.optNullableDouble("pa"),
                    )
                },
            )
        } catch (e: Exception) {
            null
        }
    }
}

/** Monthly SubT lap medians from the projection page's data.json. */
data class SubtMonth(val month: String, val pace: Double, val laps: Int, val outlier: Boolean)

data class SubtData(val fetchedAt: Long, val months: List<SubtMonth>, val lapsLast30d: Int, val lastLapDate: String) {
    fun toJson(): String = JSONObject().apply {
        put("fetchedAt", fetchedAt)
        put("lapsLast30d", lapsLast30d)
        put("lastLapDate", lastLapDate)
        put("months", JSONArray().apply {
            months.forEach { put(JSONObject().put("m", it.month).put("p", it.pace).put("n", it.laps).put("o", it.outlier)) }
        })
    }.toString()

    companion object {
        fun fromJson(s: String): SubtData? = try {
            val o = JSONObject(s)
            val m = o.getJSONArray("months")
            SubtData(
                o.getLong("fetchedAt"),
                (0 until m.length()).map {
                    val x = m.getJSONObject(it)
                    SubtMonth(x.getString("m"), x.getDouble("p"), x.getInt("n"), x.getBoolean("o"))
                },
                o.getInt("lapsLast30d"),
                o.getString("lastLapDate"),
            )
        } catch (e: Exception) {
            null
        }
    }
}

/** intervals.icu form zones (absolute Form values). */
enum class FormZone(val label: String, val lower: Double, val upper: Double, val color: Int) {
    HIGH_RISK("High risk", Double.NEGATIVE_INFINITY, -30.0, 0xFFFF5C5C.toInt()),
    OPTIMAL("Optimal", -30.0, -10.0, 0xFF3DD68C.toInt()),
    GREY("Grey zone", -10.0, 5.0, 0xFF9AA3B2.toInt()),
    FRESH("Fresh", 5.0, 25.0, 0xFF5EC8FF.toInt()),
    TRANSITION("Transition", 25.0, Double.POSITIVE_INFINITY, 0xFFFFC53D.toInt());

    companion object {
        fun of(form: Double): FormZone = entries.first { form < it.upper }
    }
}

/** How a widget lays out its Fitness / Fatigue / Form graphs. AUTO keeps each widget's original look. */
enum class GraphStyle { AUTO, THREE, TWO, ONE }

/** Selectable graph time periods (days). 42 is the default. */
object Periods {
    const val DEFAULT = 42
    val ALL = listOf(14, 28, 42, 90, 180, 365)

    fun label(days: Int): String = when (days) {
        14 -> "2 weeks"
        28 -> "4 weeks"
        42 -> "6 weeks"
        90 -> "3 months"
        180 -> "6 months"
        365 -> "1 year"
        else -> "$days days"
    }
}
