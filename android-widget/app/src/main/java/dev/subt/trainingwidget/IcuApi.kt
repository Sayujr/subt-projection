package dev.subt.trainingwidget

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

class AuthException : IOException("intervals.icu rejected the athlete ID / API key")

data class Credentials(val athleteId: String, val apiKey: String)

/** Minimal intervals.icu REST client (https://intervals.icu/api/v1). */
class IcuApi(private val creds: Credentials) {

    fun fetchSnapshot(today: LocalDate = LocalDate.now(), historyDays: Long = 42): Snapshot {
        val todayStr = today.toString()
        val tomorrow = today.plusDays(1).toString()
        val ath = URLEncoder.encode(creds.athleteId, "UTF-8")

        // Fitness / fatigue history
        val wellness = getArray(
            "/athlete/$ath/wellness",
            mapOf("oldest" to today.minusDays(historyDays - 1).toString(), "newest" to todayStr),
        )
        val days = (0 until wellness.length()).mapNotNull { i ->
            val o = wellness.getJSONObject(i)
            val ctl = o.optNum("ctl") ?: return@mapNotNull null
            val atl = o.optNum("atl") ?: return@mapNotNull null
            Day(o.optString("id"), ctl, atl)
        }.sortedBy { it.date }

        // Today's planned workouts (calendar events) and completed activities.
        // Ask for today..tomorrow and filter by date so it works whether `newest` is inclusive or not.
        val events = getArray("/athlete/$ath/events", mapOf("oldest" to todayStr, "newest" to tomorrow))
            .objects().filter { it.optString("start_date_local").startsWith(todayStr) }
            .filter {
                val cat = it.optString("category")
                cat == "WORKOUT" || cat.startsWith("RACE")
            }
            .sortedBy { it.optString("start_date_local") }

        val activities = getArray("/athlete/$ath/activities", mapOf("oldest" to todayStr, "newest" to tomorrow))
            .objects().filter { it.optString("start_date_local").startsWith(todayStr) }
            .sortedBy { it.optString("start_date_local") }

        val activityByEvent = activities.mapNotNull { a ->
            a.optNum("paired_event_id")?.toLong()?.let { it to a }
        }.toMap()
        val usedActivities = mutableSetOf<String>()

        val workouts = mutableListOf<Workout>()
        for (e in events) {
            val done = activityByEvent[e.optLong("id")]
            if (done != null) usedActivities += done.optString("id")
            val src = done ?: e
            workouts += Workout(
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
            )
        }
        // Completed activities that weren't on the plan
        for (a in activities) {
            if (a.optString("id") in usedActivities) continue
            workouts += Workout(
                name = a.optString("name").ifBlank { a.optString("type", "Activity") },
                type = a.optString("type"),
                seconds = (a.optNum("moving_time") ?: 0.0).toInt(),
                load = (a.optNum("icu_training_load") ?: 0.0).toInt(),
                distanceM = a.optNum("distance") ?: 0.0,
                status = WorkoutStatus.DONE,
            )
        }

        return Snapshot(System.currentTimeMillis(), todayStr, days, workouts)
    }

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

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

/** Number field or null when missing / JSON null / not a number. */
private fun JSONObject.optNum(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val v = optDouble(key)
    return if (v.isNaN()) null else v
}
