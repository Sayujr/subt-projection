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
            Workout("18x45/15 progressive", "Run", 3420, 66, 11800.0, WorkoutStatus.DONE, d(1), plannedLoad = 60),
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
                Race(today.plusDays(10).toString(), "Parkrun 5K", "Run", "RACE_C", distanceM = 5000.0),
                Race(today.plusDays(120).toString(), "Spring Marathon", "Run", "RACE_A", days.last().ctl + 12, days.last().ctl - 2, 42195.0),
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
        Prefs.saveSubtReps(ctx, (0 until 30).map { i ->
            SubtRep("a$i", today.minusDays((i * 5).toLong()).toString(), "3x10' SubT", i % 3, 600, 4.55 - 0.002 * (30 - i), 162.0, 168.0, 318.0)
        })
    }
}
