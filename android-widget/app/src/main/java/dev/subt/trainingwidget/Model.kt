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
    /** Training load done that day (intervals.icu wellness ctlLoad). */
    val load: Double? = null,
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
    /** Load the plan had for this session (0 for unplanned activities). */
    val plannedLoad: Int = 0,
    /** Planned steps with power targets, e.g. "15′ easy · 3× (10′ @ 285–295 W · 2′ rec)". */
    val steps: String? = null,
    /** intervals.icu activity id once done (opens the workout detail screen). */
    val activityId: String? = null,
)

/** An upcoming race from the intervals.icu calendar, with projected race-day Fitness / Fatigue when known. */
data class Race(
    val date: String,
    val name: String,
    val type: String,
    val category: String,
    val projCtl: Double? = null,
    val projAtl: Double? = null,
    /** Planned race distance in metres, when the event has one. */
    val distanceM: Double? = null,
    /** intervals.icu calendar event id (to change its priority). */
    val eventId: Long? = null,
) {
    val projForm: Double? get() = if (projCtl != null && projAtl != null) projCtl - projAtl else null
}

/** Per-run aerobic metrics from intervals.icu (each may be missing). */
data class RunMetric(
    val date: String,
    val name: String,
    val seconds: Int,
    val avgHr: Double?,
    /** Aerobic decoupling / HR drift, percent. */
    val decoupling: Double?,
    /** Efficiency factor (output per heartbeat). */
    val ef: Double?,
    /** Power : HR ratio. */
    val powerHr: Double?,
    val watts: Double? = null,
    val distanceM: Double? = null,
    /** Critical power / eFTP intervals.icu estimated around this run (W). */
    val cp: Double? = null,
    /** Seconds in each power zone (Z1…), when intervals.icu provides them. */
    val zoneSecs: List<Int> = emptyList(),
) {
    /** Running effectiveness: speed (m/s) ÷ power per kg. */
    fun re(kg: Double?): Double? {
        val w = watts ?: return null
        val d = distanceM ?: return null
        if (kg == null || seconds <= 0 || w <= 0) return null
        return (d / seconds) / (w / kg)
    }
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
    /** Projected end-of-day Fitness / Fatigue from tomorrow, using planned workouts. */
    val proj: List<Day> = emptyList(),
    /** Runs over the last ~90 days with HR drift / efficiency. */
    val runs: List<RunMetric> = emptyList(),
    /** Latest body weight recorded in intervals.icu wellness, kg. */
    val weight: Double? = null,
    /** Running FTP / CP set in intervals.icu (W). */
    val runFtp: Double? = null,
    /** CP from the 90-day power curve (3-min and 12-min bests), W. */
    val curveCp: Double? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("fetchedAt", fetchedAt)
        put("today", today)
        put("days", JSONArray().apply {
            days.forEach {
                put(
                    JSONObject().put("d", it.date).put("c", it.ctl).put("a", it.atl)
                        .putOpt("h", it.hrv).putOpt("r", it.rhr).putOpt("s", it.sleepSecs).putOpt("l", it.load)
                )
            }
        })
        put("workouts", workoutsJson(workouts))
        put("week", workoutsJson(week))
        put("lastWeekLoad", lastWeekLoad)
        putOpt("weight", weight)
        putOpt("runFtp", runFtp)
        putOpt("curveCp", curveCp)
        put("proj", JSONArray().apply { proj.forEach { put(JSONObject().put("d", it.date).put("c", it.ctl).put("a", it.atl)) } })
        put("runs", JSONArray().apply {
            runs.forEach {
                put(
                    JSONObject().put("d", it.date).put("n", it.name).put("s", it.seconds)
                        .putOpt("hr", it.avgHr).putOpt("dc", it.decoupling).putOpt("ef", it.ef).putOpt("ph", it.powerHr)
                        .putOpt("w", it.watts).putOpt("dm", it.distanceM).putOpt("cp", it.cp)
                        .put("z", JSONArray(it.zoneSecs))
                )
            }
        })
        put("races", JSONArray().apply {
            races.forEach {
                put(
                    JSONObject().put("d", it.date).put("n", it.name).put("t", it.type).put("c", it.category)
                        .putOpt("pc", it.projCtl).putOpt("pa", it.projAtl).putOpt("dm", it.distanceM).putOpt("id", it.eventId)
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
                        .put("l", it.load).put("m", it.distanceM).put("st", it.status.name).put("d", it.date).put("pl", it.plannedLoad).putOpt("sp", it.steps).putOpt("id", it.activityId)
                )
            }
        }

        private fun parseWorkouts(a: JSONArray?): List<Workout> = if (a == null) emptyList() else
            (0 until a.length()).map {
                val x = a.getJSONObject(it)
                Workout(
                    x.getString("n"), x.getString("t"), x.getInt("s"), x.getInt("l"),
                    x.getDouble("m"), WorkoutStatus.valueOf(x.getString("st")), x.optString("d"), x.optInt("pl"), x.optString("sp").ifBlank { null },
                    x.optString("id").ifBlank { null },
                )
            }

        private fun JSONObject.optNullableDouble(k: String): Double? =
            if (has(k) && !isNull(k)) optDouble(k).takeUnless { it.isNaN() } else null

        fun fromJson(s: String): Snapshot? = try {
            val o = JSONObject(s)
            val d = o.getJSONArray("days")
            val r = o.optJSONArray("races")
            val pj = o.optJSONArray("proj")
            val rn = o.optJSONArray("runs")
            Snapshot(
                fetchedAt = o.getLong("fetchedAt"),
                today = o.getString("today"),
                days = (0 until d.length()).map {
                    val x = d.getJSONObject(it)
                    Day(
                        x.getString("d"), x.getDouble("c"), x.getDouble("a"),
                        x.optNullableDouble("h"), x.optNullableDouble("r"), x.optNullableDouble("s"),
                        x.optNullableDouble("l"),
                    )
                },
                workouts = parseWorkouts(o.getJSONArray("workouts")),
                week = parseWorkouts(o.optJSONArray("week")),
                lastWeekLoad = o.optInt("lastWeekLoad"),
                proj = if (pj == null) emptyList() else (0 until pj.length()).map {
                    val x = pj.getJSONObject(it)
                    Day(x.getString("d"), x.getDouble("c"), x.getDouble("a"))
                },
                runs = if (rn == null) emptyList() else (0 until rn.length()).map {
                    val x = rn.getJSONObject(it)
                    RunMetric(
                        x.getString("d"), x.optString("n"), x.optInt("s"), x.optNullableDouble("hr"),
                        x.optNullableDouble("dc"), x.optNullableDouble("ef"), x.optNullableDouble("ph"),
                        x.optNullableDouble("w"), x.optNullableDouble("dm"), x.optNullableDouble("cp"),
                        x.optJSONArray("z")?.let { z -> (0 until z.length()).map { i -> z.optInt(i) } } ?: emptyList(),
                    )
                },
                weight = o.optNullableDouble("weight"),
                runFtp = o.optNullableDouble("runFtp"),
                curveCp = o.optNullableDouble("curveCp"),
                races = if (r == null) emptyList() else (0 until r.length()).map {
                    val x = r.getJSONObject(it)
                    Race(
                        x.getString("d"), x.getString("n"), x.optString("t"), x.optString("c"),
                        x.optNullableDouble("pc"), x.optNullableDouble("pa"), x.optNullableDouble("dm"),
                        x.optLong("id", 0L).takeIf { it > 0 },
                    )
                },
            )
        } catch (e: Exception) {
            null
        }
    }
}

/** A completed race with its result and the Fitness picture going into it. */
data class RaceResult(
    val date: String,
    val name: String,
    val category: String,
    val distanceM: Double?,
    val seconds: Double?,
    val avgHr: Double?,
    val eveCtl: Double?,
    val eveAtl: Double?,
    val peak60: Double?,
    val peak90: Double?,
    val peak120: Double?,
) {
    fun toJson(): JSONObject = JSONObject().put("d", date).put("n", name).put("c", category)
        .putOpt("dm", distanceM).putOpt("s", seconds).putOpt("hr", avgHr).putOpt("ec", eveCtl).putOpt("ea", eveAtl)
        .putOpt("p60", peak60).putOpt("p90", peak90).putOpt("p120", peak120)

    companion object {
        private fun JSONObject.d(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeUnless { it.isNaN() } else null
        fun fromJson(o: JSONObject) = RaceResult(
            o.getString("d"), o.optString("n"), o.optString("c"), o.d("dm"), o.d("s"), o.d("hr"),
            o.d("ec"), o.d("ea"), o.d("p60"), o.d("p90"), o.d("p120"),
        )
        fun listToJson(l: List<RaceResult>, t: Long) = JSONObject().put("t", t).put("r", JSONArray().apply { l.forEach { put(it.toJson()) } }).toString()
        fun listFromJson(s: String): Pair<Long, List<RaceResult>>? = try {
            val o = JSONObject(s); val a = o.getJSONArray("r")
            o.getLong("t") to (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        } catch (e: Exception) { null }
    }
}

/** Standard race distances for comparing like with like. */
enum class RaceDistance(val label: String, val metres: Double) {
    K5("5K", 5000.0), K10("10K", 10000.0), HALF("Half", 21097.5), FULL("Marathon", 42195.0), OTHER("Other", 0.0);

    companion object {
        fun of(distanceM: Double?, name: String = ""): RaceDistance {
            val n = name.lowercase()
            if (distanceM != null && distanceM > 0) {
                val km = distanceM / 1000
                return when {
                    km in 4.7..5.4 -> K5
                    km in 9.6..10.6 -> K10
                    km in 20.6..21.8 -> HALF
                    km in 41.5..43.0 -> FULL
                    else -> OTHER
                }
            }
            return when {
                "half" in n -> HALF
                "marathon" in n -> FULL
                "10k" in n || "10 k" in n -> K10
                "5k" in n || "5 k" in n || "parkrun" in n -> K5
                else -> OTHER
            }
        }
    }
}

/** A best time for a distance. */
data class Pb(val seconds: Double, val date: String?, val name: String?)

data class Pbs(val fetchedAt: Long, val fiveK: Pb?, val tenK: Pb?, val half: Pb?) {
    fun toJson(): String {
        fun j(p: Pb?) = p?.let { JSONObject().put("s", it.seconds).putOpt("d", it.date).putOpt("n", it.name) }
        return JSONObject().put("t", fetchedAt).putOpt("5k", j(fiveK)).putOpt("10k", j(tenK)).putOpt("hm", j(half)).toString()
    }

    companion object {
        fun fromJson(s: String): Pbs? = try {
            val o = JSONObject(s)
            fun p(k: String) = o.optJSONObject(k)?.let {
                Pb(it.getDouble("s"), it.optString("d").ifBlank { null }, it.optString("n").ifBlank { null })
            }
            Pbs(o.getLong("t"), p("5k"), p("10k"), p("hm"))
        } catch (e: Exception) {
            null
        }
    }
}

/** One SubT rep (lap) from an intervals.icu activity. */
data class SubtRep(
    val activityId: String,
    val date: String,
    val activityName: String,
    val index: Int,
    val seconds: Int,
    /** min/km */
    val pace: Double,
    val avgHr: Double,
    val maxHr: Double,
    val watts: Double?,
) {
    /** Metres per minute per heartbeat. */
    val efficiency: Double get() = (1000.0 / pace) / avgHr

    companion object {
        fun listToJson(list: List<SubtRep>, fetchedAt: Long): String = JSONObject().put("t", fetchedAt).put("r", JSONArray().apply {
            list.forEach {
                put(
                    JSONObject().put("a", it.activityId).put("d", it.date).put("n", it.activityName).put("i", it.index)
                        .put("s", it.seconds).put("p", it.pace).put("h", it.avgHr).put("m", it.maxHr).putOpt("w", it.watts)
                )
            }
        }).toString()

        fun listFromJson(s: String): Pair<Long, List<SubtRep>>? = try {
            val o = JSONObject(s)
            val a = o.getJSONArray("r")
            o.getLong("t") to (0 until a.length()).map {
                val x = a.getJSONObject(it)
                SubtRep(
                    x.getString("a"), x.getString("d"), x.optString("n"), x.getInt("i"), x.getInt("s"),
                    x.getDouble("p"), x.getDouble("h"), x.getDouble("m"),
                    if (x.has("w") && !x.isNull("w")) x.getDouble("w") else null,
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}

/** Monthly SubT lap medians from the projection page's data.json. */
data class SubtMonth(val month: String, val pace: Double, val laps: Int, val outlier: Boolean, val hr: Double? = null) {
    /** Metres per minute per heartbeat: higher = more speed for the same HR. */
    val efficiency: Double? get() = hr?.let { (1000.0 / pace) / it }
}

data class SubtData(
    val fetchedAt: Long, val months: List<SubtMonth>, val lapsLast30d: Int, val lastLapDate: String,
    /** "workouts" = your SubT workouts' reps (intervals.icu); "page" = the projection page's data.json. */
    val source: String = "page",
) {
    fun toJson(): String = JSONObject().apply {
        put("fetchedAt", fetchedAt)
        put("src", source)
        put("lapsLast30d", lapsLast30d)
        put("lastLapDate", lastLapDate)
        put("months", JSONArray().apply {
            months.forEach { put(JSONObject().put("m", it.month).put("p", it.pace).put("n", it.laps).put("o", it.outlier).putOpt("h", it.hr)) }
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
                    SubtMonth(
                        x.getString("m"), x.getDouble("p"), x.getInt("n"), x.getBoolean("o"),
                        if (x.has("h") && !x.isNull("h")) x.getDouble("h") else null,
                    )
                },
                o.getInt("lapsLast30d"),
                o.getString("lastLapDate"),
                o.optString("src", "page"),
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
    /** Special period: this week so far (from Monday) plus the rest of this week and next week projected. */
    const val THIS_WEEK = -7
    val ALL = listOf(14, 28, 42, 90, 180, 365)

    fun label(days: Int): String = when (days) {
        THIS_WEEK -> "This week"
        14 -> "2 weeks"
        28 -> "4 weeks"
        42 -> "6 weeks"
        90 -> "3 months"
        180 -> "6 months"
        365 -> "1 year"
        else -> "$days days"
    }
}
