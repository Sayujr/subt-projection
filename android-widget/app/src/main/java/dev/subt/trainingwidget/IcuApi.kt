package dev.subt.trainingwidget

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.exp

class AuthException : IOException("intervals.icu rejected the athlete ID / API key")

data class Credentials(val athleteId: String, val apiKey: String)

/** Minimal intervals.icu REST client (https://intervals.icu/api/v1). */
class IcuApi(private val creds: Credentials) {

    fun fetchSnapshot(today: LocalDate = LocalDate.now(), historyDays: Long = 42): Snapshot {
        val ath = URLEncoder.encode(creds.athleteId, "UTF-8")
        val todayStr = today.toString()
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val sunday = monday.plusDays(6)
        val lastMonday = monday.minusDays(7)

        // ---- Fitness / fatigue history + wellness
        val wellness = getArray(
            "/athlete/$ath/wellness",
            mapOf("oldest" to today.minusDays(historyDays - 1).toString(), "newest" to todayStr),
        )
        val days = wellness.objects().mapNotNull { o ->
            val ctl = o.optNum("ctl") ?: return@mapNotNull null
            val atl = o.optNum("atl") ?: return@mapNotNull null
            Day(
                o.optString("id"), ctl, atl,
                hrv = o.optNum("hrv")?.takeIf { it > 0 },
                rhr = o.optNum("restingHR")?.takeIf { it > 0 },
                sleepSecs = o.optNum("sleepSecs")?.takeIf { it > 0 },
            )
        }.sortedBy { it.date }

        // ---- Upcoming races (next year)
        val raceArray = try {
            getArray(
                "/athlete/$ath/events",
                mapOf(
                    "oldest" to todayStr, "newest" to today.plusDays(366).toString(),
                    "category" to "RACE_A,RACE_B,RACE_C",
                ),
            )
        } catch (e: AuthException) {
            throw e
        } catch (e: IOException) {
            // Category filter not accepted: look over a shorter window and filter here instead
            getArray("/athlete/$ath/events", mapOf("oldest" to todayStr, "newest" to today.plusDays(180).toString()))
        }
        val raceEvents = raceArray.objects().filter { it.optString("category").startsWith("RACE") }
            .sortedBy { it.optString("start_date_local") }
            .take(6)

        // ---- Calendar: this week, plus planned workouts up to the next race (≤ 12 weeks) for projections
        val nextRaceDate = raceEvents.firstOrNull()?.optString("start_date_local")?.take(10)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val projectTo = listOfNotNull(sunday, nextRaceDate?.takeIf { ChronoUnit.DAYS.between(today, it) <= 84 }).max()
        val events = getArray(
            "/athlete/$ath/events",
            mapOf("oldest" to monday.toString(), "newest" to projectTo.plusDays(1).toString()),
        ).objects().filter {
            val cat = it.optString("category")
            cat == "WORKOUT" || cat.startsWith("RACE")
        }.sortedBy { it.optString("start_date_local") }

        val activities = getArray(
            "/athlete/$ath/activities",
            mapOf("oldest" to lastMonday.toString(), "newest" to today.plusDays(1).toString()),
        ).objects().sortedBy { it.optString("start_date_local") }

        val lastWeekLoad = activities
            .filter { date(it) < monday.toString() }
            .sumOf { (it.optNum("icu_training_load") ?: 0.0).toInt() }

        val weekEvents = events.filter { date(it) in monday.toString()..sunday.toString() }
        val weekActivities = activities.filter { date(it) >= monday.toString() }
        val week = merge(weekEvents, weekActivities)
        val todays = week.filter { it.date == todayStr }

        // ---- Project Fitness / Fatigue forward from today using planned loads
        val doneEventIds = weekActivities.mapNotNull { it.optNum("paired_event_id")?.toLong() }.toSet()
        val plannedByDay = events
            .filter { it.optString("category") == "WORKOUT" && it.optLong("id") !in doneEventIds && date(it) >= todayStr }
            .groupBy { date(it) }
            .mapValues { (_, list) -> list.sumOf { it.optNum("icu_training_load") ?: 0.0 } }
        val projections = project(days.lastOrNull(), today, plannedByDay, projectTo)

        val races = raceEvents.map { e ->
            val d = date(e)
            // Race-morning values = end of the previous day
            val p = runCatching { projections[LocalDate.parse(d).minusDays(1).toString()] }.getOrNull()
            Race(
                date = d,
                name = e.optString("name").ifBlank { "Race" },
                type = e.optString("type"),
                category = e.optString("category"),
                projCtl = p?.first,
                projAtl = p?.second,
            )
        }

        return Snapshot(System.currentTimeMillis(), todayStr, days, todays, week, lastWeekLoad, races)
    }

    /** Planned events (marked done when an activity is paired with them) + unplanned completed activities. */
    private fun merge(events: List<JSONObject>, activities: List<JSONObject>): List<Workout> {
        val activityByEvent = activities.mapNotNull { a ->
            a.optNum("paired_event_id")?.toLong()?.let { it to a }
        }.toMap()
        val used = mutableSetOf<String>()
        val out = mutableListOf<Workout>()
        for (e in events) {
            val done = activityByEvent[e.optLong("id")]
            if (done != null) used += done.optString("id")
            val src = done ?: e
            out += Workout(
                name = e.optString("name").ifBlank { e.optString("type", "Workout") },
                type = e.optString("type").ifBlank { src.optString("type") },
                seconds = (src.optNum("moving_time") ?: 0.0).toInt(),
                load = (src.optNum("icu_training_load") ?: 0.0).toInt(),
                distanceM = src.optNum("distance") ?: 0.0,
                status = when {
                    done != null -> WorkoutStatus.DONE
                    e.optString("category").startsWith("RACE") -> WorkoutStatus.RACE
                    else -> WorkoutStatus.PLANNED
                },
                date = date(e),
            )
        }
        for (a in activities) {
            if (a.optString("id") in used) continue
            out += Workout(
                name = a.optString("name").ifBlank { a.optString("type", "Activity") },
                type = a.optString("type"),
                seconds = (a.optNum("moving_time") ?: 0.0).toInt(),
                load = (a.optNum("icu_training_load") ?: 0.0).toInt(),
                distanceM = a.optNum("distance") ?: 0.0,
                status = WorkoutStatus.DONE,
                date = date(a),
            )
        }
        return out.sortedBy { it.date }
    }

    /**
     * Steps the Fitness (42-day) and Fatigue (7-day) exponential averages forward one day at a time,
     * feeding in planned load. Returns end-of-day (ctl, atl) per date.
     */
    private fun project(
        start: Day?, today: LocalDate, planned: Map<String, Double>, until: LocalDate,
    ): Map<String, Pair<Double, Double>> {
        if (start == null) return emptyMap()
        val kc = 1 - exp(-1.0 / 42)
        val ka = 1 - exp(-1.0 / 7)
        var ctl = start.ctl
        var atl = start.atl
        // Today's value already includes what's been done; add what's still planned for today.
        val rest = planned[today.toString()] ?: 0.0
        ctl += rest * kc
        atl += rest * ka
        val out = mutableMapOf(today.toString() to (ctl to atl))
        var d = today.plusDays(1)
        while (!d.isAfter(until)) {
            val load = planned[d.toString()] ?: 0.0
            ctl += (load - ctl) * kc
            atl += (load - atl) * ka
            out[d.toString()] = ctl to atl
            d = d.plusDays(1)
        }
        return out
    }

    private fun date(o: JSONObject) = o.optString("start_date_local").take(10)

    private fun getArray(path: String, params: Map<String, String>): JSONArray {
        val query = params.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        val conn = URL("$BASE$path?$query").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 25_000
            val token = Base64.encodeToString("API_KEY:${creds.apiKey}".toByteArray(), Base64.NO_WRAP)
            conn.setRequestProperty("Authorization", "Basic $token")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code == 401 || code == 403) throw AuthException()
            if (code == 404) throw IOException("Athlete not found (HTTP 404) – check the athlete ID")
            if (code !in 200..299) throw IOException("intervals.icu returned HTTP $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONArray(body)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val BASE = "https://intervals.icu/api/v1"
    }
}

/** Reads the monthly SubT medians that the repo's GitHub Action keeps in data.json. */
object SubtApi {
    const val DATA_URL = "https://raw.githubusercontent.com/Sayujr/subt-projection/main/data.json"
    const val PAGE_URL = "https://sayujr.github.io/subt-projection/"

    fun fetch(today: LocalDate = LocalDate.now()): SubtData {
        val conn = URL(DATA_URL).openConnection() as HttpURLConnection
        val body = try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 25_000
            if (conn.responseCode !in 200..299) throw IOException("SubT data returned HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val o = JSONObject(body)
        val monthly = o.getJSONArray("monthly").objects().map {
            SubtMonth(
                it.getString("date").take(7), it.getDouble("pace"), it.optInt("n"), it.optBoolean("is_outlier"),
            )
        }.sortedBy { it.month }
        val laps = o.optJSONArray("all_repeats")?.objects().orEmpty()
        val cutoff = today.minusDays(30).toString()
        return SubtData(
            fetchedAt = System.currentTimeMillis(),
            months = monthly,
            lapsLast30d = laps.count { it.optString("date") >= cutoff },
            lastLapDate = laps.maxOfOrNull { it.optString("date") } ?: "",
        )
    }
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

/** Number field or null when missing / JSON null / not a number. */
private fun JSONObject.optNum(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val v = optDouble(key)
    return if (v.isNaN()) null else v
}
