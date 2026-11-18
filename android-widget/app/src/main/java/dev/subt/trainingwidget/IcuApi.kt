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
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

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
        // Project far enough to cover every race in the next 12 weeks
        val raceDates = raceEvents.mapNotNull { runCatching { LocalDate.parse(date(it)) }.getOrNull() }
            .filter { ChronoUnit.DAYS.between(today, it) <= 84 }
        val projectTo = (raceDates + sunday + today.plusDays(14) + listOfNotNull(nextRaceDate?.takeIf { ChronoUnit.DAYS.between(today, it) <= 84 })).max()
        val events = getArray(
            "/athlete/$ath/events",
            mapOf("oldest" to monday.toString(), "newest" to projectTo.plusDays(1).toString()),
        ).objects().filter {
            val cat = it.optString("category")
            cat == "WORKOUT" || cat.startsWith("RACE")
        }.sortedBy { it.optString("start_date_local") }

        val activities = getArray(
            "/athlete/$ath/activities",
            mapOf("oldest" to minOf(lastMonday, today.minusDays(89)).toString(), "newest" to today.plusDays(1).toString()),
        ).objects().sortedBy { it.optString("start_date_local") }

        // Run aerobic metrics (HR drift, efficiency) over ~90 days
        val runs = activities.filter { it.optString("type").contains("Run", ignoreCase = true) }.map {
            val zt = it.optJSONArray("icu_zone_times")
            // Power zones Z1…Z7; intervals.icu also lists an overlapping "Sweet Spot" entry, so skip it
            val zones = if (zt == null) emptyList() else (0 until zt.length()).mapNotNull { i ->
                val zo = zt.optJSONObject(i)
                when {
                    zo == null -> zt.optInt(i)
                    zo.optString("id") == "SS" || zo.optBoolean("gap") -> null
                    else -> zo.optInt("secs")
                }
            }
            RunMetric(
                watts = (it.optNum("icu_average_watts") ?: it.optNum("average_watts"))?.takeIf { v -> v > 0 },
                distanceM = it.optNum("distance")?.takeIf { v -> v > 0 },
                cp = listOf("icu_pm_cp", "icu_rolling_cp", "icu_pm_ftp_watts", "icu_rolling_ftp", "icu_ftp")
                    .firstNotNullOfOrNull { k -> it.optNum(k)?.takeIf { v -> v > 50 } },
                zoneSecs = zones,
                date = date(it),
                name = it.optString("name"),
                seconds = (it.optNum("moving_time") ?: 0.0).toInt(),
                avgHr = it.optNum("average_heartrate")?.takeIf { v -> v > 0 },
                decoupling = it.optNum("decoupling"),
                ef = it.optNum("icu_efficiency_factor")?.takeIf { v -> v > 0 },
                powerHr = it.optNum("icu_power_hr")?.takeIf { v -> v > 0 },
            )
        }

        val lastWeekLoad = activities
            .filter { date(it) >= lastMonday.toString() && date(it) < monday.toString() }
            .sumOf { (it.optNum("icu_training_load") ?: 0.0).toInt() }

        runFtp = fetchRunFtp(ath)
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
                distanceM = e.optNum("distance")?.takeIf { it > 0 },
            )
        }

        val proj = projections.entries.filter { it.key > todayStr }.sortedBy { it.key }
            .map { Day(it.key, it.value.first, it.value.second) }
        val weight = wellness.objects().sortedBy { it.optString("id") }.mapNotNull { it.optNum("weight")?.takeIf { w -> w > 25 } }.lastOrNull()
        val curveCp = runCatching { fetchCurveCp(ath) }.getOrNull()
        return Snapshot(System.currentTimeMillis(), todayStr, days, todays, week, lastWeekLoad, races, proj, runs, weight ?: athleteWeight, runFtp, curveCp)
    }

    /** Planned workouts, races and completed activities between [start] and [end] (inclusive), for the app's Week screen. */
    fun fetchRange(start: LocalDate, end: LocalDate): List<Workout> {
        val ath = URLEncoder.encode(creds.athleteId, "UTF-8")
        val s = start.toString()
        val e = end.toString()
        val events = getArray(
            "/athlete/$ath/events", mapOf("oldest" to s, "newest" to end.plusDays(1).toString()),
        ).objects().filter {
            val cat = it.optString("category")
            (cat == "WORKOUT" || cat.startsWith("RACE")) && date(it) in s..e
        }.sortedBy { it.optString("start_date_local") }
        val activities = if (start.isAfter(LocalDate.now())) emptyList() else getArray(
            "/athlete/$ath/activities", mapOf("oldest" to s, "newest" to end.plusDays(1).toString()),
        ).objects().filter { date(it) in s..e }.sortedBy { it.optString("start_date_local") }
        return merge(events, activities)
    }

    /**
     * SubT reps from runs in the last [daysBack] days, using the same lap filter as the projection
     * page (refresh_data.py): avg HR > 160, max HR ≤ 172, 1.5–15 min, pace 3:30–9:00 /km.
     */
    fun fetchSubtReps(daysBack: Long = 35, today: LocalDate = LocalDate.now()): List<SubtRep> {
        val ath = URLEncoder.encode(creds.athleteId, "UTF-8")
        val runs = getArray(
            "/athlete/$ath/activities",
            mapOf("oldest" to today.minusDays(daysBack).toString(), "newest" to today.plusDays(1).toString()),
        ).objects().filter { it.optString("type").contains("Run", ignoreCase = true) }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val futures = runs.map { act ->
                pool.submit<List<SubtRep>> {
                    val id = act.optString("id")
                    val o = runCatching { getObject("/activity/${URLEncoder.encode(id, "UTF-8")}/intervals") }.getOrNull()
                        ?: return@submit emptyList()
                    val ivs = o.optJSONArray("icu_intervals")?.objects().orEmpty()
                    ivs.mapIndexedNotNull { i, iv ->
                        val hr = iv.optNum("average_heartrate") ?: return@mapIndexedNotNull null
                        val mx = iv.optNum("max_heartrate") ?: return@mapIndexedNotNull null
                        val el = iv.optNum("elapsed_time") ?: return@mapIndexedNotNull null
                        val sp = iv.optNum("average_speed") ?: return@mapIndexedNotNull null
                        if (sp <= 0 || hr <= 0 || el <= 0) return@mapIndexedNotNull null
                        val pace = (1000 / sp) / 60
                        val min = el / 60
                        if (pace !in 3.5..9.0 || hr <= 160 || mx > 172 || min !in 1.5..15.0) return@mapIndexedNotNull null
                        SubtRep(
                            id, date(act), act.optString("name"), i + 1, el.toInt(), pace, hr, mx,
                            (iv.optNum("average_watts") ?: iv.optNum("weighted_average_watts"))?.takeIf { it > 0 },
                        )
                    }
                }
            }
            return futures.flatMap { it.get() }.sortedWith(compareBy({ it.date }, { it.activityId }, { it.index }))
        } finally {
            pool.shutdown()
        }
    }

    /**
     * Best 5K / 10K / half-marathon times over the last year: from intervals.icu's pace curve
     * when available, and from runs of those distances (time trials, races), keeping the faster.
     */
    fun fetchPbs(today: LocalDate = LocalDate.now()): Pbs {
        val ath = URLEncoder.encode(creds.athleteId, "UTF-8")
        val targets = listOf(5000.0, 10000.0, 21097.5)
        val best = arrayOfNulls<Pb>(3)
        fun offer(i: Int, pb: Pb) { if (best[i] == null || pb.seconds < best[i]!!.seconds) best[i] = pb }

        // 1) Pace curve (defensive parsing: distances in metres + times in seconds, or speeds)
        runCatching {
            val text = getText("/athlete/$ath/pace-curves", mapOf("type" to "Run", "curves" to "1y"))
            val root = org.json.JSONTokener(text).nextValue()
            val curves = when (root) {
                is JSONObject -> root.optJSONArray("list")?.objects() ?: listOf(root)
                is JSONArray -> root.objects()
                else -> emptyList()
            }
            for (cv in curves) {
                val dist = cv.optJSONArray("distance") ?: continue
                val vals = cv.optJSONArray("values") ?: cv.optJSONArray("secs") ?: continue
                for ((ti, target) in targets.withIndex()) {
                    var bi = -1; var bd = Double.MAX_VALUE
                    for (k in 0 until minOf(dist.length(), vals.length())) {
                        val dd = abs(dist.optDouble(k) - target)
                        if (dd < bd) { bd = dd; bi = k }
                    }
                    if (bi < 0 || bd > target * 0.02) continue
                    var v = vals.optDouble(bi)
                    if (v.isNaN() || v <= 0) continue
                    if (v < 20) v = target / v          // a speed (m/s), not a time
                    if (v in target * 0.12..target * 0.6) offer(ti, Pb(v, null, "pace curve"))
                }
            }
        }
        // 2) Runs of (about) those distances in the last year
        runCatching {
            val acts = getArray(
                "/athlete/$ath/activities",
                mapOf("oldest" to today.minusDays(365).toString(), "newest" to today.plusDays(1).toString()),
            ).objects().filter { it.optString("type").contains("Run", ignoreCase = true) }
            for (a in acts) {
                val dist = a.optNum("distance") ?: continue
                val secs = a.optNum("moving_time") ?: a.optNum("elapsed_time") ?: continue
                for ((ti, target) in targets.withIndex()) {
                    if (dist in target * 0.99..target * 1.05) offer(ti, Pb(secs * target / dist, date(a), a.optString("name")))
                }
            }
        }
        return Pbs(System.currentTimeMillis(), best[0], best[1], best[2])
    }

    /**
     * Past races (A/B/C events) over [years] with the activity that was raced and the Fitness /
     * Fatigue going in: race-eve values and the Fitness peaks over the 2, 3 and 4 months before.
     */
    fun fetchRaceHistory(years: Long = 3, today: LocalDate = LocalDate.now()): List<RaceResult> {
        val ath = URLEncoder.encode(creds.athleteId, "UTF-8")
        val evs = getArray(
            "/athlete/$ath/events",
            mapOf("oldest" to today.minusYears(years).toString(), "newest" to today.toString(), "category" to "RACE_A,RACE_B,RACE_C"),
        ).objects().filter { it.optString("category").startsWith("RACE") && date(it) < today.toString() }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            return evs.map { ev ->
                pool.submit<RaceResult> {
                    val d = LocalDate.parse(date(ev))
                    val acts = runCatching {
                        getArray("/athlete/$ath/activities", mapOf("oldest" to d.toString(), "newest" to d.plusDays(1).toString()))
                            .objects().filter { date(it) == d.toString() && it.optString("type").contains("Run", ignoreCase = true) }
                    }.getOrDefault(emptyList())
                    val act = acts.firstOrNull { it.optNum("paired_event_id")?.toLong() == ev.optLong("id") }
                        ?: acts.maxByOrNull { it.optNum("distance") ?: 0.0 }
                    val well = runCatching {
                        getArray("/athlete/$ath/wellness", mapOf("oldest" to d.minusDays(121).toString(), "newest" to d.minusDays(1).toString()))
                            .objects().mapNotNull { o -> o.optNum("ctl")?.let { c -> Triple(o.optString("id"), c, o.optNum("atl")) } }
                    }.getOrDefault(emptyList())
                    fun peak(days: Long) = well.filter { it.first >= d.minusDays(days).toString() }.maxOfOrNull { it.second }
                    val eve = well.maxByOrNull { it.first }
                    val elapsed = act?.optNum("elapsed_time"); val moving = act?.optNum("moving_time")
                    RaceResult(
                        date = d.toString(),
                        name = ev.optString("name").ifBlank { act?.optString("name") ?: "Race" },
                        category = ev.optString("category"),
                        distanceM = act?.optNum("distance") ?: ev.optNum("distance"),
                        seconds = if (elapsed != null && moving != null && elapsed <= moving * 1.1) elapsed else moving ?: elapsed,
                        avgHr = act?.optNum("average_heartrate"),
                        eveCtl = eve?.second, eveAtl = eve?.third,
                        peak60 = peak(60), peak90 = peak(90), peak120 = peak(120),
                    )
                }
            }.map { it.get() }.sortedBy { it.date }
        } finally {
            pool.shutdown()
        }
    }

    /** Fitness / Fatigue history from [oldest] to today (for the multi-year calendar). */
    fun fetchFitnessHistory(oldest: LocalDate, today: LocalDate = LocalDate.now()): List<Day> {
        val ath = URLEncoder.encode(creds.athleteId, "UTF-8")
        return getArray("/athlete/$ath/wellness", mapOf("oldest" to oldest.toString(), "newest" to today.toString()))
            .objects().mapNotNull { o ->
                val c = o.optNum("ctl") ?: return@mapNotNull null
                val a = o.optNum("atl") ?: return@mapNotNull null
                Day(o.optString("id"), c, a)
            }.sortedBy { it.date }
    }

    /** Sets one wellness field for a day in intervals.icu (used to correct an obviously wrong sleep value). */
    fun updateWellness(date: String, fields: JSONObject) {
        val ath = URLEncoder.encode(creds.athleteId, "UTF-8")
        val conn = URL("$BASE/athlete/$ath/wellness/${URLEncoder.encode(date, "UTF-8")}").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 25_000
            val token = Base64.encodeToString("API_KEY:${creds.apiKey}".toByteArray(), Base64.NO_WRAP)
            conn.setRequestProperty("Authorization", "Basic $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(fields.toString().toByteArray()) }
            val code = conn.responseCode
            if (code == 401 || code == 403) throw AuthException()
            if (code !in 200..299) throw IOException("intervals.icu returned HTTP $code")
        } finally {
            conn.disconnect()
        }
    }

    private fun getText(path: String, params: Map<String, String>): String {
        val query = params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val conn = URL("$BASE$path?$query").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 25_000
            val token = Base64.encodeToString("API_KEY:${creds.apiKey}".toByteArray(), Base64.NO_WRAP)
            conn.setRequestProperty("Authorization", "Basic $token")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code == 401 || code == 403) throw AuthException()
            if (code !in 200..299) throw IOException("intervals.icu returned HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun getObject(path: String): JSONObject {
        val conn = URL("$BASE$path").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 25_000
            val token = Base64.encodeToString("API_KEY:${creds.apiKey}".toByteArray(), Base64.NO_WRAP)
            conn.setRequestProperty("Authorization", "Basic $token")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code == 401 || code == 403) throw AuthException()
            if (code !in 200..299) throw IOException("intervals.icu returned HTTP $code")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    /** Running FTP from the athlete's sport settings (used to turn %FTP steps into watts). */
    private var runFtp: Double? = null

    /** Weight on the athlete profile (used when wellness has none). */
    private var athleteWeight: Double? = null

    private fun fetchRunFtp(ath: String): Double? = runCatching {
        val o = JSONObject(getText("/athlete/$ath", emptyMap()))
        athleteWeight = o.optNum("icu_weight")?.takeIf { it > 25 } ?: o.optNum("weight")?.takeIf { it > 25 }
        val ss = o.optJSONArray("sportSettings")?.objects().orEmpty()
        ss.firstOrNull { s -> s.optJSONArray("types")?.let { t -> (0 until t.length()).any { t.optString(it).contains("Run") } } == true }
            ?.optNum("ftp")?.takeIf { it > 50 }
    }.getOrNull()

    /** CP from the 90-day power curve: 2-parameter model from the best 3-min and 12-min powers. */
    private fun fetchCurveCp(ath: String): Double? {
        val text = getText("/athlete/$ath/power-curves", mapOf("type" to "Run", "curves" to "90d"))
        val root = org.json.JSONTokener(text).nextValue()
        val curves = when (root) {
            is JSONObject -> root.optJSONArray("list")?.objects() ?: listOf(root)
            is JSONArray -> root.objects()
            else -> emptyList()
        }
        for (cv in curves) {
            val secs = cv.optJSONArray("secs") ?: continue
            val watts = cv.optJSONArray("watts") ?: cv.optJSONArray("values") ?: continue
            fun at(t: Int): Double? {
                var bi = -1; var bd = Int.MAX_VALUE
                for (k in 0 until minOf(secs.length(), watts.length())) {
                    val dd = abs(secs.optInt(k) - t); if (dd < bd) { bd = dd; bi = k }
                }
                return if (bi >= 0 && bd <= t / 10) watts.optDouble(bi).takeIf { !it.isNaN() && it > 50 } else null
            }
            val p3 = at(180) ?: continue
            val p12 = at(720) ?: continue
            val cp = (p12 * 720 - p3 * 180) / (720 - 180)
            if (cp in 80.0..700.0) return cp
        }
        return null
    }

    /** Turns an intervals.icu workout_doc into a short line of steps with power targets. */
    private fun stepsText(doc: JSONObject): String {
        val ftp = runFtp
        fun dur(s: Int) = if (s >= 60) "${(s + 30) / 60}′" else "${s}″"
        fun target(st: JSONObject): String {
            val hr = st.optJSONObject("hr")
            if (st.optJSONObject("power") == null && hr != null) {
                val lo = hr.optNum("start") ?: hr.optNum("value"); val hi = hr.optNum("end")
                return when { lo == null -> "easy"; hi == null || hi == lo -> "${lo.roundToInt()}% HR"; else -> "${lo.roundToInt()}–${hi.roundToInt()}% HR" }
            }
            val p = st.optJSONObject("power") ?: return st.optString("text").takeIf { it.isNotBlank() }?.take(24) ?: "easy"
            val units = p.optString("units")
            val lo = p.optNum("start") ?: p.optNum("value")
            val hi = p.optNum("end") ?: lo
            if (lo == null) return "easy"
            fun w(v: Double) = if (units.contains("%") || units.contains("ftp", true)) ftp?.let { (v / 100 * it).roundToInt() } else v.roundToInt()
            val a = w(lo); val b = w(hi ?: lo)
            return if (a == null || b == null) "${lo.roundToInt()}–${(hi ?: lo).roundToInt()}% FTP"
            else if (a == b) "$a W" else "$a–$b W"
        }
        fun render(arr: JSONArray?): List<String> = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            val st = arr!!.optJSONObject(i) ?: return@mapNotNull null
            val reps = st.optInt("reps", 0)
            val inner = st.optJSONArray("steps")
            if (inner != null && inner.length() > 0) {
                val body = render(inner).joinToString(" · ")
                if (reps > 1) "$reps× ($body)" else body
            } else {
                val d = st.optInt("duration", 0)
                val dist = st.optNum("distance") ?: 0.0
                val t = target(st)
                val tag = if (st.optBoolean("warmup")) "WU " else if (st.optBoolean("cooldown")) "CD " else ""
                val len = if (dist > 0 && st.optJSONObject("power") != null && dist > 1000) String.format(java.util.Locale.US, "%.1f km", dist / 1000) else if (d > 0) dur(d) else ""
                if (len.isNotEmpty()) "$tag$len @ $t" else "$tag$t"
            }
        }
        return render(doc.optJSONArray("steps")).joinToString(" · ")
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
                plannedLoad = (e.optNum("icu_training_load") ?: 0.0).toInt(),
                steps = e.optJSONObject("workout_doc")?.let { stepsText(it) }?.takeIf { it.isNotBlank() },
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
        val laps = o.optJSONArray("all_repeats")?.objects().orEmpty()
        val hrByMonth = laps.groupBy { it.optString("date").take(7) }
            .mapValues { (_, l) -> l.mapNotNull { x -> x.optDouble("hr").takeIf { !it.isNaN() && it > 0 } }.sorted() }
            .mapValues { (_, v) -> if (v.isEmpty()) null else if (v.size % 2 == 1) v[v.size / 2] else (v[v.size / 2 - 1] + v[v.size / 2]) / 2 }
        val monthly = o.getJSONArray("monthly").objects().map {
            SubtMonth(
                it.getString("date").take(7), it.getDouble("pace"), it.optInt("n"), it.optBoolean("is_outlier"),
                hrByMonth[it.getString("date").take(7)],
            )
        }.sortedBy { it.month }
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
