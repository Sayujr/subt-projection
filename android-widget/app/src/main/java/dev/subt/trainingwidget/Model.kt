package dev.subt.trainingwidget

import org.json.JSONArray
import org.json.JSONObject

/** One day of the intervals.icu fitness model. Form (TSB) = Fitness (CTL) - Fatigue (ATL). */
data class Day(val date: String, val ctl: Double, val atl: Double) {
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
)

data class Snapshot(
    val fetchedAt: Long,
    /** Local date (yyyy-MM-dd) the workout list belongs to. */
    val today: String,
    val days: List<Day>,
    val workouts: List<Workout>,
) {
    fun toJson(): String = JSONObject().apply {
        put("fetchedAt", fetchedAt)
        put("today", today)
        put("days", JSONArray().apply {
            days.forEach { put(JSONObject().put("d", it.date).put("c", it.ctl).put("a", it.atl)) }
        })
        put("workouts", JSONArray().apply {
            workouts.forEach {
                put(
                    JSONObject()
                        .put("n", it.name).put("t", it.type).put("s", it.seconds)
                        .put("l", it.load).put("m", it.distanceM).put("st", it.status.name)
                )
            }
        })
    }.toString()

    companion object {
        fun fromJson(s: String): Snapshot? = try {
            val o = JSONObject(s)
            val d = o.getJSONArray("days")
            val w = o.getJSONArray("workouts")
            Snapshot(
                fetchedAt = o.getLong("fetchedAt"),
                today = o.getString("today"),
                days = (0 until d.length()).map {
                    val x = d.getJSONObject(it)
                    Day(x.getString("d"), x.getDouble("c"), x.getDouble("a"))
                },
                workouts = (0 until w.length()).map {
                    val x = w.getJSONObject(it)
                    Workout(
                        x.getString("n"), x.getString("t"), x.getInt("s"), x.getInt("l"),
                        x.getDouble("m"), WorkoutStatus.valueOf(x.getString("st"))
                    )
                },
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
