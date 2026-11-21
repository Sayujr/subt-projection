package dev.subt.trainingwidget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** The training rules the app shows you. A change that breaks one fails the build before it reaches the phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RulesTest {
    private lateinit var ctx: Context

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        Net.offline = true
        ctx.getSharedPreferences("training_widget", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // ---- Form zones (intervals.icu bands)
    @Test fun formZones() {
        assertEquals(FormZone.HIGH_RISK, FormZone.of(-31.0))
        assertEquals(FormZone.OPTIMAL, FormZone.of(-30.0))
        assertEquals(FormZone.OPTIMAL, FormZone.of(-10.1))
        assertEquals(FormZone.GREY, FormZone.of(-10.0))
        assertEquals(FormZone.GREY, FormZone.of(4.9))
        assertEquals(FormZone.FRESH, FormZone.of(5.0))
        assertEquals(FormZone.TRANSITION, FormZone.of(25.0))
    }

    // ---- Week bars: the mark must agree with the bar and the dashed plan outline
    @Test fun planMarks() {
        val tol = 15
        assertNull("nothing planned → no mark", Charts.planMark(56, 0, tol))
        assertEquals("unplanned walk on a missed-bike day → more than planned", 1, Charts.planMark(11, 5, tol))
        assertEquals(0, Charts.planMark(100, 100, tol))
        assertEquals(0, Charts.planMark(115, 100, tol))
        assertEquals(1, Charts.planMark(116, 100, tol))
        assertEquals(0, Charts.planMark(85, 100, tol))
        assertEquals(2, Charts.planMark(84, 100, tol))
        assertEquals("missed whole day", 2, Charts.planMark(0, 34, tol))
        assertEquals("short of plan", 2, Charts.planMark(65, 87, tol))
    }

    // ---- Period trend arrows on the Today chart
    @Test fun periodTrend() {
        assertEquals(1, Charts.trend((0..27).map { 50.0 + it * 0.2 }, 1.0))     // +5.4 over 4 weeks → up
        assertEquals(-1, Charts.trend((0..27).map { 60.0 - it * 0.2 }, 1.0))
        assertEquals(0, Charts.trend((0..27).map { 55.0 + (it % 2) * 0.5 }, 1.0)) // noise only → flat
    }

    // ---- Daily load survives the app's cache
    @Test fun dayLoadRoundTrip() {
        val t = LocalDate.now().toString()
        val snap = Snapshot(1L, t, listOf(Day(t, 50.0, 45.0, load = 47.0)), emptyList(),
            races = listOf(Race(t, "Half", "Run", "RACE_A", distanceM = 21097.0, eventId = 104230892L)))
        val back = Snapshot.fromJson(snap.toJson())!!
        assertEquals(47.0, back.days.first().load!!, 1e-9)
        assertEquals(104230892L, back.races.first().eventId)
    }

    // ---- Notifications: time windows and the pre-race pause
    @Test fun notifyRules() {
        val t = java.time.LocalTime.of(4, 0)
        assertTrue(NotifySettings.inWindow(t, 4 * 60))
        assertFalse(NotifySettings.inWindow(java.time.LocalTime.of(3, 59), 4 * 60))
        assertFalse(NotifySettings.inWindow(java.time.LocalTime.of(10, 0), 4 * 60, 6))
        val today = LocalDate.of(2026, 11, 19)
        val race = Race("2026-11-21", "Half", "Run", "RACE_A")
        assertNotNull("2 days before pauses", NotifySettings.pausingRace(today, listOf(race)))
        assertNotNull("race day pauses", NotifySettings.pausingRace(today.plusDays(2), listOf(race)))
        assertNull("3 days before doesn't", NotifySettings.pausingRace(today.minusDays(1), listOf(race)))
        assertNull("day after doesn't", NotifySettings.pausingRace(today.plusDays(3), listOf(race)))
    }

    // ---- SubT from SubT workouts
    @Test fun subtWorkoutNames() {
        assertEquals(listOf(3.0), SubtWorkouts.repLengths("3'x10 repeats"))
        assertEquals(listOf(6.0), SubtWorkouts.repLengths("6'x5 repeats"))
        assertEquals(listOf(3.0, 10.0), SubtWorkouts.repLengths("3'x3 + 10'x3 repeats cpt"))
        assertEquals("12' cpt isn't a SubT rep", listOf(3.0), SubtWorkouts.repLengths("3'x3, 12' cpt repeats"))
        assertTrue(SubtWorkouts.isSubtWorkout("3' repeats & 5k TT 🎃"))
        assertTrue(SubtWorkouts.isSubtWorkout("Philadelphia - NSM Flex Sub-Ts 160-165"))
        assertFalse(SubtWorkouts.isSubtWorkout("18 x 45/15 repeats"))
        assertFalse(SubtWorkouts.isSubtWorkout("400s, 800 repeats cpt"))
        assertFalse(SubtWorkouts.isSubtWorkout("1h easy"))
        assertEquals(SubtWorkouts.Kind.SHORT, SubtWorkouts.kind(180))
        assertEquals(SubtWorkouts.Kind.MEDIUM, SubtWorkouts.kind(360))
        assertEquals(SubtWorkouts.Kind.LONG, SubtWorkouts.kind(600))
        assertTrue(SubtWorkouts.matchesRep(185.0, listOf(3.0)))
        assertFalse("a 12-min CP effort isn't a 3' rep", SubtWorkouts.matchesRep(720.0, listOf(3.0)))
    }

    @Test fun subtAverageAndTestEfforts() {
        fun rep(i: Int, sec: Int, pace: Double) = SubtRep("a1", "2026-11-03", "3'x4 repeats", i, sec, pace, 160.0, 165.0, null)
        val reps = listOf(rep(1, 180, 4.40), rep(2, 180, 4.36), rep(3, 180, 4.38), rep(4, 180, 3.95))   // last one = a test effort
        val kept = SubtWorkouts.dropTestEfforts(reps)
        assertEquals(3, kept.size)
        // time-weighted average pace = total time / total distance
        val avg = SubtWorkouts.avgPace(listOf(rep(1, 600, 4.0), rep(2, 300, 5.0)))
        assertEquals(900.0 / (600 / 4.0 + 300 / 5.0), avg, 1e-9)
        val m = SubtWorkouts.months(kept)
        assertEquals(1, m.size); assertEquals(3, m[0].laps); assertFalse(m[0].outlier)
    }

    @Test fun subtRuleSwitch() {
        val page = SubtData(1L, listOf(
            SubtMonth("2026-03", 4.90, 8, false), SubtMonth("2026-04", 4.85, 9, false),
            SubtMonth("2026-05", 5.20, 7, false), SubtMonth("2026-06", 5.10, 6, false),
        ), 5, "2026-06-20")
        fun rep(d: String, p: Double) = SubtRep("x$d", d, "6'x5 repeats", 1, 360, p, 158.0, 164.0, null)
        val reps = listOf(rep("2026-05-05", 4.60), rep("2026-05-12", 4.62), rep("2026-05-19", 4.58), rep("2026-06-02", 4.55),
            rep("2026-03-10", 9.99))  // a pre-switch rep is ignored
        val m = SubtWorkouts.merge(page, reps, LocalDate.of(2026, 6, 10))!!
        assertEquals(listOf("2026-03", "2026-04", "2026-05", "2026-06"), m.months.map { it.month })
        assertEquals("old rule kept before May", 4.85, m.months[1].pace, 1e-9)
        assertEquals("reps from May", SubtWorkouts.avgPace(reps.subList(0, 3)), m.months[2].pace, 1e-9)
        assertEquals("no reps yet → page data", page, SubtWorkouts.merge(page, emptyList()))
    }

    // ---- TT / race / CP test detection by name
    @Test fun ttNames() {
        listOf("5K TT", "Time trial 3K", "Parkrun", "CP test", "CPT 9min", "Race", "Spring race").forEach {
            assertTrue("should count as TT/race: $it", Streaks.isTtName(it))
        }
        listOf("Race pace intervals", "race-pace 2x5k", "Easy run", "Attack the hills", "Recovery", "CPU").forEach {
            assertFalse("should not count: $it", Streaks.isTtName(it))
        }
    }

    // ---- Taper formula
    @Test fun taperReference() {
        assertEquals(0.5 * 60 + 0.3 * 58 + 0.2 * 55, Taper.reference(60.0, 58.0, 55.0)!!, 1e-9)
        assertEquals("missing peaks re-weight", 60.0, Taper.reference(60.0, null, null)!!, 1e-9)
        assertNull(Taper.reference(null, null, null))
    }

    @Test fun taperTargetsMarathonA() {
        val race = LocalDate.of(2027, 4, 20)
        val series = (0..150).map { Day(race.minusDays(it.toLong()).toString(), 60.0, 55.0) }
        val t = Taper.targets(series, race, "RACE_A", RaceDistance.FULL, emptyList(), 1)!!
        assertEquals(60.0, t.ref, 1e-9)
        assertEquals(0.61, t.f, 1e-9)                      // A 0.66, marathon −0.05
        assertEquals((0.61 - 0.03) * 60, t.fatTarget, 1e-9) // aim at the low end
        assertTrue(t.fatLo < t.fatTarget && t.fatTarget < t.fatHi)
        assertEquals(0.91 * 60, t.floor, 1e-9)              // 0.92 − 0.01 for an A race
    }

    @Test fun taperBlendsImprovedRaces() {
        val race = LocalDate.of(2027, 4, 20)
        val series = (0..150).map { Day(race.minusDays(it.toLong()).toString(), 60.0, 55.0) }
        val hist = listOf(
            RaceResult("2025-10-01", "Half", "RACE_B", 21097.0, 5900.0, null, 50.0, 30.0, 50.0, 50.0, 50.0),
            RaceResult("2026-03-01", "Half", "RACE_B", 21097.0, 5700.0, null, 50.0, 35.0, 50.0, 50.0, 50.0),
        )
        assertEquals(1, Taper.improvedRaces(hist).size)
        val t = Taper.targets(series, race, "RACE_A", RaceDistance.FULL, hist, 1)!!
        assertEquals(0.5 * 0.61 + 0.5 * 0.70, t.f, 1e-9)
    }

    // ---- SubT model: projections never slower than a PB, never faster than the floor
    @Test fun predictionsNeverSlowerThanPb() {
        SubtModel.GAP = 0.20; SubtModel.PB5K = 20 * 60 + 40.0
        for (p in listOf(4.2, 4.5, 4.8, 5.2)) assertTrue(SubtModel.fiveK(p) <= SubtModel.PB5K!! + 1e-9)
        SubtModel.PB5K = null; SubtModel.GAP = SubtModel.PAGE_GAP
    }

    @Test fun subtPathImprovesAndRespectsFloor() {
        val path = SubtModel.path(4.6, SubtModel.FAST, 60)
        assertTrue(path.zipWithNext().all { (a, b) -> b <= a })
        assertTrue(path.all { it >= SubtModel.FLOOR })
    }

    // ---- Verdict
    private fun snapWith(rhrToday: Double, formToday: Double): Snapshot {
        val t = LocalDate.now()
        val days = (40 downTo 1).map { Day(t.minusDays(it.toLong()).toString(), 55.0, 57.0, rhr = 50.0 + (it % 3) * 0.5) } +
            Day(t.toString(), 55.0, 55.0 - formToday, rhr = rhrToday)
        return Snapshot(System.currentTimeMillis(), t.toString(), days, emptyList())
    }

    @Test fun verdictCalls() {
        assertEquals(Verdict.Call.PUSH, Verdict.compute(snapWith(50.5, -2.0))!!.call)
        assertEquals(Verdict.Call.HOLD, Verdict.compute(snapWith(58.0, -2.0))!!.call)          // RHR spike
        assertEquals(Verdict.Call.BACK_OFF, Verdict.compute(snapWith(58.0, -28.0))!!.call)     // spike + deep fatigue
        // Your entered RHR wins over intervals.icu
        assertEquals(Verdict.Call.PUSH, Verdict.compute(snapWith(58.0, -2.0), rhrToday = 50.5)!!.call)
    }

    // ---- Streaks: steady balanced training passes every week; a week gone fresh fails
    private fun seedSeries(days: List<Day>, races: List<Race> = emptyList()) {
        Prefs.saveSnapshot(ctx, Snapshot(System.currentTimeMillis(), LocalDate.now().toString(), days, emptyList(), races = races), days.size)
    }

    @Test fun taperNeverEndsStreaks() {
        val t = LocalDate.now()
        // Steady block, then a 10-day B-race taper where Fitness drops well below the race floor
        val race = Race(t.plusDays(2).toString(), "Half", "Run", "RACE_B")
        seedSeries((150 downTo 0).map {
            val d = t.minusDays(it.toLong())
            val taper = it <= 8
            Day(d.toString(), if (taper) 50.0 - (9 - it) * 1.5 else 50.0, if (taper) 25.0 else 52.0)
        }, listOf(race))
        val s = Streaks.compute(ctx)!!
        // ~91 judged days incl. 9 taper days, which count as passed: one unbroken streak
        assertTrue("day streak survives the taper: ${s.dayStreak}", s.dayStreak >= 88)
        assertEquals("never broken", s.bestDayStreak, s.dayStreak)
        assertTrue("taper days aren't failures", s.days.takeLast(8).none { it.status == Streaks.Status.RED })
        val taperWeeks = s.weeks.filter { it.kind == "taper / race week" }
        assertTrue("taper weeks count as passed", taperWeeks.isNotEmpty() && taperWeeks.all { it.passed == true })
    }

    @Test fun steadyWeeksPass() {
        val t = LocalDate.now()
        seedSeries((150 downTo 0).map { Day(t.minusDays(it.toLong()).toString(), 50.0, 52.0) })   // Form −2, Fatigue 104 %
        val s = Streaks.compute(ctx)!!
        val done = s.weeks.filter { it.complete && it.passed != null }
        assertTrue(done.size >= 8)
        assertTrue("all steady weeks pass: ${done.filter { it.passed == false }.map { it.reasons }}", done.all { it.passed == true })
        assertTrue(s.weekStreak >= done.size - 1)
    }

    @Test fun freshWeekFails() {
        val t = LocalDate.now()
        val badMonday = t.minusWeeks(3).with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        seedSeries((150 downTo 0).map {
            val d = t.minusDays(it.toLong())
            val fresh = !d.isBefore(badMonday) && d.isBefore(badMonday.plusDays(7))
            Day(d.toString(), 50.0, if (fresh) 30.0 else 52.0)  // Form +20 that week
        })
        val s = Streaks.compute(ctx)!!
        val wk = s.weeks.first { it.monday == badMonday }
        assertEquals(false, wk.passed)
        assertNotNull(wk.reasons.firstOrNull())
    }

    // ---- Your numbers override intervals.icu, and only for today
    @Test fun todayOverrides() {
        Prefs.setRhrOverride(ctx, 61.0)
        assertEquals(61.0, Prefs.rhrOverride(ctx)!!, 1e-9)
        ctx.getSharedPreferences("training_widget", Context.MODE_PRIVATE).edit()
            .putString("rhr_override_date", LocalDate.now().minusDays(1).toString()).commit()
        assertNull("yesterday's entry doesn't carry over", Prefs.rhrOverride(ctx))
    }
}
