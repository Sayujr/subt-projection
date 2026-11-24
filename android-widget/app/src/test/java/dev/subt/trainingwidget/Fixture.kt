package dev.subt.trainingwidget

import android.content.Context
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.PI
import kotlin.math.sin

/**
 * Synthetic (made-up) training data for tests and screenshots: a year of Fitness / Fatigue,
 * a week of planned and done sessions, a marathon in 4 months, SubT history and PBs.
 * No real athlete data.
 */
object Fixture {
    val today: LocalDate = LocalDate.now()

    /** Banister-style series ending today: Fitness builds 38 → ~57 with 3-week load waves. */
    fun days(n: Int = 400): List<Day> {
        val out = mutableListOf<Day>()
        var ctl = 38.0; var atl = 38.0
        val k42 = 1 - Math.exp(-1 / 42.0); val k7 = 1 - Math.exp(-1 / 7.0)
        for (i in n - 1 downTo 0) {
            val d = today.minusDays(i.toLong())
            val dow = d.dayOfWeek.value
            val wave = 1.0 + 0.25 * sin(2 * PI * i / 21.0)
            val load = when (dow) { 1 -> 15.0; 2 -> 85.0; 4 -> 80.0; 6 -> 110.0; else -> 50.0 } * wave * (0.9 + 0.25 * (n - i) / n)
            ctl += (load - ctl) * k42; atl += (load - atl) * k7
            out += Day(d.toString(), ctl, atl,
                hrv = 62 + 6 * sin(i / 3.0), rhr = 52 + 2 * sin(i / 5.0), sleepSecs = 7.2 * 3600 + 1800 * sin(i / 4.0))
        }
        return out
    }

    fun week(): List<Workout> {
        val mon = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        fun d(i: Long) = mon.plusDays(i).toString()
        val t = today.toString()
        fun st(date: String, done: Boolean) = if (done) WorkoutStatus.DONE else WorkoutStatus.PLANNED
        return listOf(
            Workout("Easy bike", "Ride", 1200, 5, 0.0, st(d(0), false), d(0)),
            Workout("Walk", "Walk", 2340, 11, 3000.0, WorkoutStatus.DONE, d(0), plannedLoad = 0),
            Workout("6'x4 repeats", "Run", 3420, 66, 11800.0, WorkoutStatus.DONE, d(1), plannedLoad = 60, activityId = "i999"),
            Workout("60m Easy", "Run", 3600, 34, 10500.0, st(d(2), d(2) < t && false), d(2)),
            Workout("3x10' SubT", "Run", 4200, 65, 14000.0, if (d(3) < t) WorkoutStatus.DONE else WorkoutStatus.PLANNED, d(3), plannedLoad = 87,
                steps = "15′ easy · 3× (10′ @ 315–325 W · 2′ rec) · 10′ easy"),
            Workout("Easy + strides", "Run", 3000, 40, 9000.0, if (d(4) < t) WorkoutStatus.DONE else WorkoutStatus.PLANNED, d(4), plannedLoad = 40),
            Workout("5K TT", "Run", 2400, 55, 8000.0, WorkoutStatus.PLANNED, d(5)),
            Workout("Long run 26 km", "Run", 7800, 120, 26000.0, WorkoutStatus.PLANNED, d(6)),
        )
    }

    fun snapshot(): Snapshot {
        val days = days()
        val wk = week()
        val proj = (1..21).map { i ->
            val last = days.last()
            Day(today.plusDays(i.toLong()).toString(), last.ctl + 0.15 * i, last.atl + 6 * sin(i / 2.0))
        }
        val runs = (0 until 40).map { i ->
            val d = today.minusDays((i * 2 + 1).toLong())
            RunMetric(d.toString(), if (i % 7 == 0) "Threshold 3x10" else "Easy run", 3000 + 60 * (i % 5), 142.0 + i % 6,
                decoupling = 3.0 + (i % 4), ef = 1.75, powerHr = 1.9, watts = 265.0 + i % 9, distanceM = 9800.0 + 100 * (i % 5),
                cp = 340.0 + (40 - i) * 0.15, zoneSecs = listOf(900, 1200, 500, 250, 80, 20, 5))
        }
        return Snapshot(
            fetchedAt = System.currentTimeMillis(), today = today.toString(), days = days,
            workouts = wk.filter { it.date == today.toString() }.ifEmpty { listOf(wk[4].copy(date = today.toString())) },
            week = wk, lastWeekLoad = 290,
            races = listOf(
                Race(today.plusDays(10).toString(), "Parkrun 5K", "Run", "RACE_C", distanceM = 5000.0, eventId = 11L),
                Race(today.plusDays(120).toString(), "Spring Marathon", "Run", "RACE_A", days.last().ctl + 12, days.last().ctl - 2, 42195.0, eventId = 12L),
            ),
            proj = proj, runs = runs, weight = 81.2, runFtp = 351.0, curveCp = 345.0,
        )
    }

    fun subt(): SubtData {
        val months = (17 downTo 0).map { i ->
            val m = today.minusMonths(i.toLong())
            SubtMonth("%d-%02d".format(m.year, m.monthValue), 4.85 - (17 - i) * 0.018, 6 + i % 4, outlier = i == 9, hr = 160.0 - (17 - i) * 0.3)
        }
        return SubtData(System.currentTimeMillis(), months, 9, today.minusDays(3).toString())
    }

    /**
     * A made-up 6′×4 workout as intervals.icu would return it: warm-up, 4 work reps at 90–95 % FTP
     * with 2′ recoveries, cool-down; 1 Hz streams.
     */
    fun workoutRaw(id: String = "i999"): org.json.JSONObject {
        val ftp = 350.0
        val segs = mutableListOf<Triple<String, Int, Double>>()   // type, seconds, watts
        segs += Triple("WARMUP", 600, 215.0)
        repeat(4) { k -> segs += Triple("WORK", 360, 318.0 + k * 6); if (k < 3) segs += Triple("RECOVERY", 120, 170.0) }
        segs += Triple("COOLDOWN", 480, 205.0)
        val watts = org.json.JSONArray(); val hr = org.json.JSONArray(); val vel = org.json.JSONArray(); val time = org.json.JSONArray()
        val ivs = org.json.JSONArray()
        var t = 0; var h = 120.0
        for ((type, sec, w) in segs) {
            val start = t
            var sumW = 0.0; var sumH = 0.0; var dist = 0.0; var maxH = 0.0
            repeat(sec) { i ->
                val pw = w + 9 * sin(t / 7.0) + (if (type == "WORK" && i < 5) -40 + i * 8.0 else 0.0)
                val target = if (type == "WORK") 150 + (w - 300) * 0.9 + 14 else if (type == "RECOVERY") 140.0 else 128.0
                h += (target - h) * 0.02
                val v = pw / 350.0 * 3.75
                watts.put(pw); hr.put(h); vel.put(v); time.put(t)
                sumW += pw; sumH += h; dist += v; if (h > maxH) maxH = h
                t++
            }
            ivs.put(org.json.JSONObject().put("type", if (type == "WORK") "WORK" else "RECOVERY").put("start_index", start).put("end_index", t)
                .put("moving_time", sec).put("elapsed_time", sec).put("distance", dist).put("average_watts", sumW / sec)
                .put("average_heartrate", sumH / sec).put("max_heartrate", maxH).put("average_speed", dist / sec).put("average_cadence", 86.0))
        }
        fun pstep(lo: Int, hi: Int, dur: Int) = org.json.JSONObject().put("duration", dur).put("power", org.json.JSONObject().put("start", lo).put("end", hi).put("units", "%ftp"))
        val doc = org.json.JSONObject().put("steps", org.json.JSONArray()
            .put(pstep(55, 68, 600).put("warmup", true))
            .put(org.json.JSONObject().put("reps", 4).put("steps", org.json.JSONArray().put(pstep(90, 95, 360)).put(pstep(40, 55, 120))))
            .put(pstep(55, 65, 480).put("cooldown", true)))
        fun st(type: String, data: org.json.JSONArray) = org.json.JSONObject().put("type", type).put("data", data)
        return org.json.JSONObject()
            .put("a", org.json.JSONObject().put("id", id).put("name", "6'x4 repeats").put("type", "Run").put("start_date_local", today.minusDays(2).toString() + "T07:30:00")
                .put("moving_time", t).put("distance", 9800.0).put("icu_training_load", 66).put("total_elevation_gain", 92.0).put("icu_ftp", ftp)
                .put("icu_average_watts", 262.0).put("average_heartrate", 148.0).put("average_speed", 2.9).put("average_cadence", 84.0).put("paired_event_id", 5))
            .put("i", org.json.JSONObject().put("icu_intervals", ivs))
            .put("s", org.json.JSONArray().put(st("time", time)).put(st("watts", watts)).put(st("heartrate", hr)).put(st("velocity_smooth", vel)))
            .put("e", org.json.JSONObject().put("id", 5).put("workout_doc", doc))
    }

    /** Seeds the app's storage with everything the screens read, and keeps it offline. */
    fun seed(ctx: Context) {
        Net.offline = true
        ctx.getSharedPreferences("training_widget", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs.saveCredentials(ctx, Credentials("i000000", "test-key"))
        val snap = snapshot()
        Prefs.saveSnapshot(ctx, snap, 400)
        Prefs.saveSubt(ctx, subt())
        Prefs.savePbs(ctx, Pbs(System.currentTimeMillis(),
            Pb(20 * 60 + 40.0, today.minusMonths(2).toString(), "5K TT"),
            Pb(43 * 60 + 30.0, today.minusMonths(7).toString(), "10K race"),
            Pb(95 * 60.0, today.minusMonths(5).toString(), "Half marathon")))
        Prefs.saveLongHistory(ctx, days(900))
        val actual = mutableMapOf<String, Int>(); val planned = mutableMapOf<String, Int>()
        snap.days.forEachIndexed { i, d -> actual[d.date] = 50 + (i % 7) * 5; planned[d.date] = 55 + (i % 7) * 5 }
        Prefs.saveLoadIndex(ctx, actual, planned)
        Prefs.saveTtDates(ctx, listOf(today.minusDays(40).toString(), today.plusDays(5).toString()))
        Prefs.saveRaceHistory(ctx, listOf(
            RaceResult(today.minusMonths(11).toString(), "Autumn Half", "RACE_B", 21097.0, 97 * 60.0, 168.0, 50.0, 36.0, 52.0, 51.0, 49.0),
            RaceResult(today.minusMonths(5).toString(), "Spring Half", "RACE_A", 21097.0, 95 * 60.0, 170.0, 54.0, 35.0, 57.0, 56.0, 54.0),
        ))
        // Reps over ~18 months: short (3'), medium (6') and long (10') sessions, each kind getting faster
        val reps = (0 until 160).map { i ->
            val k = i % 3
            val sec = listOf(180, 360, 600)[k]
            val name = listOf("3'x10 repeats", "6'x5 repeats", "10'x3 repeats")[k]
            val base = listOf(4.40, 4.50, 4.60)[k]
            SubtRep("a${i / 3}", today.minusDays((i * 3).toLong()).toString(), name, i % 5, sec, base + 0.0012 * i, 160.0 + k, 166.0, 318.0)
        }
        Prefs.saveSubtReps(ctx, reps)
        Prefs.saveSubtPage(ctx, subt())
        Prefs.saveSubt(ctx, SubtWorkouts.merge(subt(), reps)!!)
        // Time in target over the last 12 weeks (made-up numbers)
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val zr = mutableListOf<Zones.Run>()
        for (k in 0 until 12) {
            val m = monday.minusWeeks((11 - k).toLong())
            val f = 0.6 + 0.4 * sin(k * 0.9) + k * 0.03
            zr += Zones.Run("z${k}a", m.plusDays(3).toString(), "3x10' SubT", 4200, 14000.0, true, false,
                1100 * f, 4100 * f, 1800.0, 600.0, 1700.0, 0.0, 0.0,
                listOf(100.0, 500.0, 600.0, 1500 * f, 1000 * f, 300.0, 60.0), listOf(300.0, 1400.0, 1900.0, 5000 * f, 3800 * f, 1000.0, 200.0),
                (1800 - 1100 * f) * 0.6, (1800 - 1100 * f) * 0.6 * 3.6, (1800 - 1100 * f) * 0.4, (1800 - 1100 * f) * 0.4 * 3.8)
            zr += Zones.Run("z${k}b", m.plusDays(1).toString(), "Easy run", 3000, 9000.0, false, false,
                0.0, 0.0, 0.0, 2300 * f, 6800 * f, 0.0, 0.0,
                listOf(900 * f, 1400 * f, 600.0, 60.0, 0.0, 0.0, 0.0), listOf(2700 * f, 4100 * f, 1900.0, 200.0, 0.0, 0.0, 0.0))
            zr += Zones.Run("z${k}c", m.plusDays(6).toString(), "Long run", 6600, 21000.0, false, true,
                0.0, 0.0, 0.0, 700.0, 2000.0, 3400 * f, 10600 * f,
                listOf(200.0, 500.0, 3400 * f, 700.0, 200.0, 0.0, 0.0), listOf(600.0, 1500.0, 10600 * f, 2200.0, 600.0, 0.0, 0.0))
        }
        zr += Zones.Run("i999", today.toString(), "6'x4 repeats", 3420, 9800.0, true, false,
            1260.0, 4600.0, 1440.0, 300.0, 800.0, 0.0, 0.0,
            listOf(100.0, 200.0, 400.0, 700.0, 900.0, 600.0, 200.0), listOf(300.0, 500.0, 1300.0, 2500.0, 3300.0, 2000.0, 700.0),
            100.0, 360.0, 80.0, 304.0,
            listOf(600.0, 800.0, 1440.0, 580.0), listOf(1600.0, 2100.0, 4400.0, 1700.0))
        Prefs.saveZones(ctx, zr)
    }
}
