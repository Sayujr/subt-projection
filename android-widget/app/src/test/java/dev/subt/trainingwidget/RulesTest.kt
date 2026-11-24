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
        fun rep(i: Int, sec: Int, pace: Double, w: Double? = null) = SubtRep("a1", "2026-11-03", "3'x4 repeats", i, sec, pace, 160.0, 165.0, w)
        // A fast rep alone isn't dropped any more…
        assertEquals(4, SubtWorkouts.dropTestEfforts(listOf(rep(1, 180, 4.40), rep(2, 180, 4.36), rep(3, 180, 4.38), rep(4, 180, 3.95))).size)
        // …only one with power 50 W+ above its siblings
        val reps = listOf(rep(1, 180, 4.40, 315.0), rep(2, 180, 4.36, 318.0), rep(3, 180, 4.38, 320.0), rep(4, 180, 3.95, 372.0))
        val kept = SubtWorkouts.dropTestEfforts(reps)
        assertEquals(3, kept.size)
        assertEquals("49 W above stays", 4, SubtWorkouts.dropTestEfforts(reps.dropLast(1) + rep(4, 180, 4.0, 367.0)).size)
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
        val reps = listOf(rep("2026-05-05", 4.60), rep("2026-05-12", 4.62), rep("2026-05-19", 4.58), rep("2026-06-02", 4.55))
        val m = SubtWorkouts.merge(page, reps, LocalDate.of(2026, 6, 10))!!
        assertEquals(listOf("2026-03", "2026-04", "2026-05", "2026-06"), m.months.map { it.month })
        assertEquals("old rule kept before May", 4.85, m.months[1].pace, 1e-9)
        assertEquals("reps from May", SubtWorkouts.avgPace(reps.subList(0, 3)), m.months[2].pace, 1e-9)
        // once HR-rule laps for the early months are read, they replace the page data
        val withOld = SubtWorkouts.merge(page, reps + rep("2026-04-08", 4.70) + rep("2026-04-15", 4.74), LocalDate.of(2026, 6, 10))!!
        assertEquals(listOf("2026-04", "2026-05", "2026-06"), withOld.months.map { it.month })
        assertEquals(SubtWorkouts.avgPace(listOf(rep("a", 4.70), rep("b", 4.74))), withOld.months[0].pace, 1e-9)
        assertEquals("no reps yet → page data", page, SubtWorkouts.merge(page, emptyList()))
    }

    @Test fun subtMixAdjustedAndRolling() {
        fun rep(d: String, sec: Int, pace: Double) = SubtRep("a$d$sec", d, "x", 1, sec, pace, 160.0, 165.0, null)
        // Same fitness: short reps always 4:20, long reps always 4:40. Month A mostly long, month B mostly short.
        val a = List(6) { rep("2026-06-0${it + 1}", 600, 4 + 40 / 60.0) } + List(2) { rep("2026-06-1${it}", 180, 4 + 20 / 60.0) }
        val b = List(2) { rep("2026-07-0${it + 1}", 600, 4 + 40 / 60.0) } + List(6) { rep("2026-07-1${it}", 180, 4 + 20 / 60.0) }
        val raw = SubtWorkouts.months(a + b)
        assertTrue("raw average is fooled by the mix", raw[1].pace < raw[0].pace - 3 / 60.0)
        val adj = SubtWorkouts.toSubtData(a + b, LocalDate.of(2026, 7, 20)).months
        assertEquals("mix-adjusted months match", adj[0].pace, adj[1].pace, 0.5 / 60)
        // rolling 28 days needs 3+ reps
        assertNotNull(SubtWorkouts.current(a + b, LocalDate.of(2026, 7, 20)))
        assertNull(SubtWorkouts.current(a + b, LocalDate.of(2026, 9, 30)))
    }

    @Test fun trendError() {
        val steady = (0..5).map { SubtMonth("2026-0${it + 5}", 4.6 - it * 2 / 60.0, 6, false) }
        val (sl, se) = SubtWorkouts.trendWithError(steady)!!
        assertEquals(-2.0, sl * 60, 0.01); assertEquals(0.0, se * 60, 0.01)
        assertEquals("˄ 2s faster", SubtWorkouts.trendText(sl to se))
        val noisy = listOf(4.60, 4.50, 4.62, 4.52, 4.61, 4.53).mapIndexed { i, p -> SubtMonth("2026-0${i + 5}", p, 6, false) }
        val (sl2, se2) = SubtWorkouts.trendWithError(noisy)!!
        assertTrue("noise: error bigger than the slope", se2 > kotlin.math.abs(sl2))
    }

    @Test fun subtLapRules() {
        // HR rule (before May): any lap, avg HR 155–172, 2–12 min, not slower than 5:45
        assertTrue(SubtWorkouts.oldRuleLap(160.0, 300.0, 4.6))
        assertTrue(SubtWorkouts.oldRuleLap(155.0, 120.0, 5.75))
        assertFalse("HR too low", SubtWorkouts.oldRuleLap(150.0, 300.0, 4.6))
        assertFalse("HR too high", SubtWorkouts.oldRuleLap(175.0, 300.0, 4.6))
        assertFalse("too short", SubtWorkouts.oldRuleLap(160.0, 100.0, 4.6))
        assertFalse("too long", SubtWorkouts.oldRuleLap(160.0, 800.0, 4.6))
        assertFalse("slower than 5:45", SubtWorkouts.oldRuleLap(160.0, 300.0, 5.9))
        assertFalse("no HR", SubtWorkouts.oldRuleLap(null, 300.0, 4.6))
        // 3-6-10 rule (from May)
        assertTrue(SubtWorkouts.newRuleRep(360.0, 4.5, listOf(6.0)))
        assertFalse("slower than 5:45", SubtWorkouts.newRuleRep(360.0, 6.0, listOf(6.0)))
        assertFalse("wrong length", SubtWorkouts.newRuleRep(600.0, 4.5, listOf(6.0)))
    }

    // ---- Workout detail: steps against targets
    @Test fun workoutDetailSteps() {
        val det = WorkoutDetail.fromRaw(Fixture.workoutRaw(), null)
        assertEquals(350.0, det.ftp!!, 1e-9)                       // CP of that day, from the activity
        assertEquals(9, det.steps.size)
        val kinds = det.steps.map { it.kind }
        assertEquals(WorkoutDetail.Kind.WARMUP, kinds.first()); assertEquals(WorkoutDetail.Kind.COOLDOWN, kinds.last())
        assertEquals(4, det.steps.count { it.isWork })
        assertEquals(3, kinds.count { it == WorkoutDetail.Kind.RECOVERY })
        val w1 = det.steps.first { it.isWork }
        assertEquals(315.0, w1.loW!!, 1e-6); assertEquals(332.5, w1.hiW!!, 1e-6)   // 90–95 % of 350
        assertEquals(0, w1.vsTarget)
        assertTrue(w1.inTarget!! in 0.5..1.0)
        assertTrue(w1.hr165!! >= w1.hr172!! - 1e-9 || w1.hr172 >= w1.hr165)
        assertEquals(1, det.steps.last { it.isWork }.vsTarget)     // 336 W: over the 332.5 top
        // Summary covers work steps only
        val wk = det.work!!
        assertEquals(4, wk.count); assertEquals(4 * 360, wk.seconds)
        assertTrue("work average excludes warm-up and recoveries", wk.power.avg!! > 315)
        assertTrue(wk.power.min!! < wk.power.avg && wk.power.max!! > wk.power.avg)
        assertNotNull(wk.inTarget); assertNotNull(wk.hr172)
    }

    @Test fun workoutPlanAlignment() {
        fun step(lo: Int, hi: Int, dur: Int) = org.json.JSONObject().put("duration", dur).put("power", org.json.JSONObject().put("start", lo).put("end", hi).put("units", "%ftp"))
        // Easy day: every step is easy, so they all count as work
        val easy = org.json.JSONObject().put("steps", org.json.JSONArray().put(step(60, 70, 1800)).put(step(62, 72, 1800)))
        assertTrue(WorkoutDetail.flattenPlan(easy, 350.0).all { it.kind == WorkoutDetail.Kind.WORK })
        // No FTP → no watt targets
        assertTrue(WorkoutDetail.flattenPlan(easy, null).all { it.loW == null })
        // An extra unplanned interval (e.g. a stop) doesn't shift the targets of later steps
        val plan = WorkoutDetail.flattenPlan(Fixture.workoutRaw().getJSONObject("e").getJSONObject("workout_doc"), 350.0)
        assertEquals(10, plan.size)   // warm-up, 4 × (work + recovery), cool-down
        val lengths = listOf(600.0, 360.0, 30.0, 120.0, 360.0, 120.0, 360.0, 120.0, 360.0, 480.0).map { it to 0.0 }
        val m = WorkoutDetail.align(plan, lengths)
        assertNull(m[2]); assertEquals(WorkoutDetail.Kind.RECOVERY, m[3]!!.kind); assertEquals(WorkoutDetail.Kind.COOLDOWN, m[9]!!.kind)
    }

    @Test fun workoutPlanRealShapes() {
        fun pw(lo: Int, hi: Int, dur: Int) = org.json.JSONObject().put("duration", dur).put("power", org.json.JSONObject().put("start", lo).put("end", hi).put("units", "%ftp"))
        fun grp(n: Int, vararg st: org.json.JSONObject) = org.json.JSONObject().put("reps", n).put("steps", org.json.JSONArray().apply { st.forEach { put(it) } })
        // Strides + a 3' time trial + 10' SubT reps in one session: the SubT reps must still be work
        val mixed = org.json.JSONObject().put("steps", org.json.JSONArray()
            .put(pw(55, 70, 900).put("warmup", true))
            .put(grp(2, pw(115, 125, 30), pw(20, 60, 60)))
            .put(pw(120, 150, 20)).put(pw(112, 120, 180)).put(pw(44, 70, 300))
            .put(grp(3, pw(88, 91, 600), pw(40, 60, 120)))
            .put(pw(55, 70, 900).put("cooldown", true)))
        val plan = WorkoutDetail.flattenPlan(mixed, 351.0)
        val tens = plan.filter { it.seconds == 600 }
        assertEquals(3, tens.size); assertTrue("10' reps are work", tens.all { it.kind == WorkoutDetail.Kind.WORK })
        assertTrue("strides aren't work", plan.filter { it.seconds in 1..39 }.none { it.kind == WorkoutDetail.Kind.WORK })
        assertEquals(WorkoutDetail.Kind.RECOVERY, plan.first { it.seconds == 300 }.kind)
        // The 3' at 112–120 % is a test effort and doesn't set the bar for the work rule
        assertEquals(WorkoutDetail.Kind.TEST, plan.first { it.seconds == 180 }.kind)
        // Bike session with HR-only targets (75–85 % of max HR 194 = 145.5–164.9 bpm)
        fun hr(lo: Int, hi: Int, dur: Int) = org.json.JSONObject().put("duration", dur).put("hr", org.json.JSONObject().put("start", lo).put("end", hi).put("units", "%hr"))
        val bike = org.json.JSONObject().put("steps", org.json.JSONArray().put(hr(50, 65, 600).put("warmup", true)).put(grp(5, hr(75, 85, 360), hr(50, 60, 60))).put(hr(50, 65, 600).put("cooldown", true)))
        val bp = WorkoutDetail.flattenPlan(bike, 351.0, 194.0, 172.0)
        val work = bp.filter { it.kind == WorkoutDetail.Kind.WORK }
        assertEquals(5, work.size); assertTrue(work.all { it.hrTarget })
        assertEquals(145.5, work[0].loW!!, 0.01); assertEquals(164.9, work[0].hiW!!, 0.01)
    }

    @Test fun subtKindSeriesCleaned() {
        fun rep(d: String, sec: Int, pace: Double) = SubtRep("a$d$sec$pace", d, "x", 1, sec, pace, 160.0, 165.0, null)
        val june = List(3) { rep("2026-06-0${it + 1}", 180, 4.75) } + List(3) { rep("2026-06-1$it", 600, 5.0) }
        // July: long reps "faster" than short ones (weird data), and only 2 medium reps
        val july = List(3) { rep("2026-07-0${it + 1}", 180, 4.75) } + List(3) { rep("2026-07-1$it", 600, 4.62) } + List(2) { rep("2026-07-2$it", 360, 4.9) }
        val s = SubtWorkouts.kindSeries(june + july).toMap()
        assertEquals(listOf("2026-06"), s[SubtWorkouts.Kind.LONG]!!.map { it.month })
        assertEquals(listOf("2026-06", "2026-07"), s[SubtWorkouts.Kind.SHORT]!!.map { it.month })
        assertNull("2 reps isn't enough for a point", s[SubtWorkouts.Kind.MEDIUM])
    }

    @Test fun workStepIgnoresRamp() {
        // Fixture reps start ~40 W low for 12 s: the step minimum must not show that ramp
        val det = WorkoutDetail.fromRaw(Fixture.workoutRaw(), null)
        val w1 = det.steps.first { it.isWork }
        assertTrue("min ${w1.power.min} reflects the rep, not the ramp", w1.power.min!! > 300)
        assertEquals(5, WorkoutDetail.RAMP)
        assertEquals(5, WorkoutDetail.afterRamp(0, 359)); assertEquals(5, WorkoutDetail.afterRamp(0, 19)); assertEquals(3, WorkoutDetail.afterRamp(0, 11))
        val sm = WorkoutDetail.smooth(listOf(0.0, 10.0, 20.0, null, 40.0), 2)
        assertEquals(15.0, sm[2]!!, 1e-9); assertNull(sm[3])
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

    // ---- Time in target
    @Test fun zonesIsLong() {
        assertTrue(Zones.isLong("1h easy", "2026-11-22", 70 * 60))
        assertFalse(Zones.isLong("1h easy", "2026-11-24", 60 * 60))
        assertTrue(Zones.isLong("2h progressive", "2026-11-21", 100 * 60))
        assertFalse(Zones.isLong("Half marathon race", "2026-11-22", 100 * 60))
        assertFalse(Zones.isLong("6'x5 repeats", "2026-11-25", 95 * 60))
        assertTrue(Zones.isLong("Easy run", "2026-11-25", 91 * 60))
    }

    private fun rawOf(name: String, time: List<Int>?, hr: List<Double>, vel: List<Double>): org.json.JSONObject {
        fun st(type: String, d: List<Number>) = org.json.JSONObject().put("type", type).put("data", org.json.JSONArray(d))
        val s = org.json.JSONArray().put(st("heartrate", hr)).put(st("velocity_smooth", vel))
        if (time != null) s.put(st("time", time))
        return org.json.JSONObject()
            .put("a", org.json.JSONObject().put("id", "x1").put("name", name).put("start_date_local", "2026-11-25T07:00:00").put("moving_time", 7200).put("distance", 20000.0))
            .put("s", s)
    }

    @Test fun zonesCompute() {
        val hr = List(300) { 120.0 } + List(200) { 140.0 } + List(100) { 160.0 }
        val vel = List(600) { 3.0 }
        val time = List(600) { it }
        val r = Zones.compute(rawOf("2h steady", time, hr, vel), 153.0, null)
        assertTrue(r.long)
        assertEquals(300.0, r.easySec, 1e-6); assertEquals(900.0, r.easyM, 1e-6)
        assertEquals(200.0, r.longSec, 1e-6); assertEquals(600.0, r.longM, 1e-6)
        val e = Zones.compute(rawOf("Tuesday jog", time, hr.map { it }, vel).also { it.getJSONObject("a").put("moving_time", 3000) }, 153.0, null)
        assertFalse(e.long); assertEquals(0.0, e.longSec, 0.0); assertEquals(300.0, e.easySec, 1e-6)
        // a 60 s pause (gap in the time stream) adds at most 5 s
        val gap = List(10) { it } + List(10) { 70 + it }
        val g = Zones.compute(rawOf("Easy", gap, List(20) { 100.0 }, List(20) { 3.0 }), 153.0, null)
        assertEquals(9.0 + 5.0 + 9.0 + 1.0, g.easySec, 1e-9)
    }

    private fun bandRaw(name: String, counts: List<Int>): org.json.JSONObject {
        val reps = listOf(120.0, 130.0, 145.0, 160.0, 168.0, 173.0, 180.0)
        val hr = counts.indices.flatMap { i -> List(counts[i]) { reps[i] } }
        return rawOf(name, hr.indices.toList(), hr, List(hr.size) { 3.0 })
    }

    @Test fun bandBuckets() {
        assertEquals(listOf(0, 1, 1, 2, 3, 4, 5, 6),
            listOf(124.9, 125.0, 134.9, 135.0, 155.0, 165.0, 172.0, 175.0).map { Zones.bucket(it) })
    }

    @Test fun bandRowsFromCompute() {
        val counts = listOf(10, 20, 30, 40, 50, 60, 70)
        val r = Zones.compute(bandRaw("Easy", counts), 153.0, null)
        assertEquals(counts.map { it.toDouble() }, r.bandSec)
        assertEquals(counts.map { it * 3.0 }, r.bandM)
        val rows = Zones.bandRows(r.bandSec, r.bandM).associateBy { it.label }
        val exp = mapOf("Under 125" to 10, "Under 135" to 30, "135–155" to 30, "155–165" to 40, "165–172" to 50,
            "155–172" to 90, "165–175" to 110, "Over 172" to 130)
        assertEquals(8, rows.size)
        for ((l, n) in exp) {
            assertEquals(l, n.toDouble(), rows[l]!!.sec, 1e-9)
            assertEquals(l, n * 3.0, rows[l]!!.m, 1e-9)
            assertEquals(l, n / 280.0 * 100, rows[l]!!.pct, 1e-9)
        }
        assertTrue(rows["155–172"]!!.emphasised)
        assertEquals(100.0, rows["Under 135"]!!.pct + rows["135–155"]!!.pct + rows["155–172"]!!.pct + rows["Over 172"]!!.pct, 1.0)
        assertTrue(Zones.bandRows(List(7) { 0.0 }, List(7) { 0.0 }).all { it.pct == 0.0 })
    }

    @Test fun bandsCountForRaces() {
        val r = Zones.compute(bandRaw("Half marathon race", listOf(0, 0, 0, 0, 100, 0, 0)), 153.0, null)
        assertEquals(100.0, r.bandSec[4], 1e-9)
    }

    @Test fun bandsLongOnlyAndJson() {
        val z = List(7) { 1.0 }
        val a = Zones.Run("a", "2026-11-23", "Easy", 3000, 9000.0, false, false, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, z, z)
        val b = Zones.Run("b", "2026-11-24", "2h steady", 7200, 20000.0, false, true, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, List(7) { 2.0 }, List(7) { 5.0 })
        val t = Zones.total(listOf(a, b), LocalDate.parse("2026-11-23"), LocalDate.parse("2026-11-29"))
        assertEquals(List(7) { 3.0 }, t.bandSec); assertEquals(List(7) { 6.0 }, t.bandM)
        assertEquals(List(7) { 2.0 }, t.longBandSec); assertEquals(List(7) { 5.0 }, t.longBandM)
        assertEquals(listOf(a, b), Zones.fromJson(Zones.toJson(listOf(a, b))))
        val old = """[{"id":"o","date":"2026-11-23","name":"Easy","seconds":10,"distance":1.0,"easySec":5.0}]"""
        val o = Zones.fromJson(old).single()
        assertEquals(List(7) { 0.0 }, o.bandSec); assertEquals(List(7) { 0.0 }, o.bandM)
    }

    @Test fun zonesSubtFixture() {
        val r = Zones.compute(Fixture.workoutRaw(), 153.0, null)
        assertTrue(r.subt)
        assertEquals(1440.0 - 4 * 5, r.subtWorkSec, 6.0)   // 4 reps, first 5 s of each left out
        assertTrue(r.subtInSec > 0 && r.subtInSec <= r.subtWorkSec)
        assertTrue(r.subtInM > 0)
    }

    @Test fun zonesJsonAndTotals() {
        val a = Zones.Run("a", "2026-11-23", "Easy", 3000, 9000.0, false, false, 0.0, 0.0, 0.0, 100.0, 300.0, 0.0, 0.0)
        val b = Zones.Run("b", "2026-11-29", "2h steady", 7200, 20000.0, false, true, 0.0, 0.0, 0.0, 50.0, 150.0, 400.0, 1200.0)
        val c = Zones.Run("c", "2026-11-30", "SubT", 3600, 9000.0, true, false, 900.0, 3000.0, 1200.0, 10.0, 30.0, 0.0, 0.0)
        assertEquals(listOf(a, b, c), Zones.fromJson(Zones.toJson(listOf(a, b, c))))
        val t = Zones.total(listOf(a, b, c), LocalDate.parse("2026-11-23"), LocalDate.parse("2026-11-29"))
        assertEquals(2, t.runs); assertEquals(150.0, t.easySec, 1e-9); assertEquals(400.0, t.longSec, 1e-9); assertEquals(0.0, t.subtInSec, 0.0)
        val w = Zones.weekly(listOf(a, b, c), 12, LocalDate.parse("2026-11-26"))
        assertEquals(12, w.size)
        assertTrue(w.all { it.first.dayOfWeek == java.time.DayOfWeek.MONDAY })
        assertEquals(LocalDate.parse("2026-11-23"), w.last().first)
        assertEquals(LocalDate.parse("2026-09-07"), w.first().first)
        assertEquals(2, w.last().second.runs)
    }

    // ---- Your numbers override intervals.icu, and only for today
    @Test fun todayOverrides() {
        Prefs.setRhrOverride(ctx, 61.0)
        assertEquals(61.0, Prefs.rhrOverride(ctx)!!, 1e-9)
        ctx.getSharedPreferences("training_widget", Context.MODE_PRIVATE).edit()
            .putString("rhr_override_date", LocalDate.now().minusDays(1).toString()).commit()
        assertNull("yesterday's entry doesn't carry over", Prefs.rhrOverride(ctx))
    }

    // ---- Under / in / over, 45/15 reps, test efforts, stops

    private class Seg(val type: String, val sec: Int, val w: Double, val v: Double = 3.7, val hr: Double = 150.0, val lead: Int = 0, val stopAt: Int = -1, val stopLen: Int = 0)

    private fun pw(lo: Int, hi: Int, dur: Int) = org.json.JSONObject().put("duration", dur).put("power", org.json.JSONObject().put("start", lo).put("end", hi).put("units", "%ftp"))
    private fun grp(n: Int, vararg st: org.json.JSONObject) = org.json.JSONObject().put("reps", n).put("steps", org.json.JSONArray().apply { st.forEach { put(it) } })

    private fun buildRaw(segs: List<Seg>, steps: List<org.json.JSONObject>, name: String = "x", type: String = "Run"): org.json.JSONObject {
        val watts = org.json.JSONArray(); val hr = org.json.JSONArray(); val vel = org.json.JSONArray(); val time = org.json.JSONArray()
        val ivs = org.json.JSONArray()
        var t = 0
        for (g in segs) {
            val start = t; var sum = 0.0; var dist = 0.0
            repeat(g.sec) { i ->
                val stop = g.stopAt >= 0 && i >= g.stopAt && i < g.stopAt + g.stopLen
                val w = if (stop) 0.0 else if (i < g.lead) 200.0 else g.w
                val v = if (stop) 0.0 else g.v
                watts.put(w); hr.put(g.hr); vel.put(v); time.put(t); sum += w; dist += v; t++
            }
            ivs.put(org.json.JSONObject().put("type", if (g.type == "WORK") "WORK" else "RECOVERY").put("start_index", start).put("end_index", t)
                .put("moving_time", g.sec).put("elapsed_time", g.sec).put("distance", dist).put("average_watts", sum / g.sec)
                .put("average_heartrate", g.hr).put("average_speed", dist / g.sec))
        }
        fun st(ty: String, d: org.json.JSONArray) = org.json.JSONObject().put("type", ty).put("data", d)
        return org.json.JSONObject()
            .put("a", org.json.JSONObject().put("id", "t1").put("name", name).put("type", type).put("start_date_local", "2026-11-20T07:00:00")
                .put("moving_time", t).put("distance", 10000.0).put("icu_ftp", 350.0))
            .put("i", org.json.JSONObject().put("icu_intervals", ivs))
            .put("s", org.json.JSONArray().put(st("time", time)).put(st("watts", watts)).put(st("heartrate", hr)).put(st("velocity_smooth", vel)))
            .put("e", org.json.JSONObject().put("id", 1).put("workout_doc", org.json.JSONObject().put("steps", org.json.JSONArray().apply { steps.forEach { put(it) } })))
    }

    @Test fun underInOverAddUp() {
        val det = WorkoutDetail.fromRaw(Fixture.workoutRaw(), null)
        for (st in det.steps.filter { it.isWork }) {
            assertEquals(1.0, st.under!! + st.inT!! + st.over!!, 1e-9)
            assertEquals((st.seconds - WorkoutDetail.RAMP).toDouble(), st.underSec + st.inSec + st.overSec, 1e-9)
            assertEquals(st.inT!!, st.inTarget!!, 0.0)
            assertEquals(st.inT!! + st.over!!, st.inOrOver!!, 1e-9)
            assertTrue(st.underM >= 0 && st.inM > 0)
        }
        val wk = det.work!!
        assertEquals(1.0, wk.under!! + wk.inT!! + wk.over!!, 1e-9)
        assertEquals(det.steps.filter { it.isWork }.sumOf { it.inSec }, wk.inSec, 1e-9)
        assertEquals(wk.underSec + wk.inSec + wk.overSec, 4.0 * (360 - 5), 1e-9)
        // heart rate on the work: bands cover the work-step samples, with no ramp skipped
        val hn = det.steps.filter { it.isWork }.sumOf { it.hrN }
        assertEquals(4.0 * 360, hn, 1e-9)
        assertTrue(wk.hrA!!.sec <= wk.hrB!!.sec + 1e-9)
        assertEquals(wk.hrB!!.sec / hn, wk.hrB!!.frac, 1e-9)
    }

    @Test fun constantPowerAboveRangeIsOver() {
        // 90–95 % of 350 = 315–332.5; 400 W is over for the whole rep
        val raw = buildRaw(listOf(Seg("WORK", 300, 400.0)), listOf(pw(90, 95, 300)))
        val st = WorkoutDetail.fromRaw(raw, null).steps.single()
        assertTrue(st.isWork)
        assertEquals(1.0, st.over!!, 1e-9); assertEquals(0.0, st.inT!!, 1e-9); assertEquals(0.0, st.under!!, 1e-9)
        assertEquals(295.0 * 3.7, st.overM, 1e-6)
    }

    @Test fun shortRepsJudgedRaw() {
        val doc = listOf(pw(55, 68, 600).put("warmup", true), grp(18, pw(105, 110, 45), pw(40, 55, 15)))
        val plan = WorkoutDetail.flattenPlan(org.json.JSONObject().put("steps", org.json.JSONArray().apply { doc.forEach { put(it) } }), 350.0)
        assertEquals(18, plan.count { it.kind == WorkoutDetail.Kind.WORK })
        assertTrue("45 s reps are work, not tests", plan.none { it.kind == WorkoutDetail.Kind.TEST })
        // 3 s at 200 W, then 375 W (105–110 % = 367.5–385): raw judging counts those 3 s as under, nothing is skipped
        val segs = mutableListOf(Seg("REC", 600, 200.0))
        repeat(18) { segs += Seg("WORK", 45, 375.0, lead = 3); segs += Seg("REC", 15, 160.0) }
        val det = WorkoutDetail.fromRaw(buildRaw(segs, doc, name = "18 x 45/15 repeats"), null)
        val reps = det.steps.filter { it.isWork }
        assertEquals(18, reps.size)
        for (r in reps) {
            assertEquals(3.0, r.underSec, 1e-9); assertEquals(42.0, r.inSec, 1e-9); assertEquals(0.0, r.overSec, 1e-9)
            assertEquals(200.0, r.power.min!!, 1e-9)      // raw: no smoothing, no ramp skip
        }
        assertEquals(18 * 45.0, det.work!!.underSec + det.work!!.inSec + det.work!!.overSec, 1e-9)
        // and Zones judges them the same way
        val z = Zones.compute(buildRaw(segs, doc, name = "6'x18 repeats SubT"), 153.0, null)
        assertEquals(18 * 45.0, z.subtWorkSec, 1e-9)
        assertEquals(18 * 42.0, z.subtInSec, 1e-9)
    }

    @Test fun testEffortsAreSeparate() {
        val doc = org.json.JSONObject().put("steps", org.json.JSONArray()
            .put(pw(55, 70, 900).put("warmup", true))
            .put(pw(112, 120, 180)).put(pw(44, 70, 300))
            .put(grp(3, pw(88, 91, 600), pw(40, 60, 120)))
            .put(pw(55, 70, 900).put("cooldown", true)))
        val plan = WorkoutDetail.flattenPlan(doc, 350.0)
        assertEquals(WorkoutDetail.Kind.TEST, plan.first { it.seconds == 180 }.kind)
        assertEquals(3, plan.count { it.seconds == 600 && it.kind == WorkoutDetail.Kind.WORK })
        // the same session with only the test and the reps built end to end: the test isn't in the work summary
        val segs = listOf(Seg("REC", 900, 200.0), Seg("WORK", 180, 400.0), Seg("REC", 300, 200.0)) +
            (0 until 3).flatMap { listOf(Seg("WORK", 600, 312.0), Seg("REC", 120, 170.0)) } + Seg("REC", 900, 200.0)
        val det = WorkoutDetail.fromRaw(buildRaw(segs, listOf(pw(55, 70, 900).put("warmup", true), pw(112, 120, 180), pw(44, 70, 300),
            grp(3, pw(88, 91, 600), pw(40, 60, 120)), pw(55, 70, 900).put("cooldown", true))), null)
        assertEquals(1, det.tests.size)
        assertEquals(3, det.work!!.count)
        assertEquals(1800, det.work!!.seconds)
        assertTrue(det.work!!.power.avg!! < 320)
    }

    @Test fun minValuesIgnoreStops() {
        // 300 s rep at 330 W with a 20 s stop (speed 0, watts 0) in the middle
        val raw = buildRaw(listOf(Seg("WORK", 300, 330.0, stopAt = 100, stopLen = 20)), listOf(pw(90, 95, 300)))
        val det = WorkoutDetail.fromRaw(raw, null)
        val st = det.steps.single()
        assertEquals(330.0, st.power.min!!, 1e-6)                    // the stop doesn't set the minimum
        assertEquals(330.0 * 280 / 300, st.power.avg!!, 1e-6)        // but the average still includes it
        assertEquals(3.7, st.speed.min!!, 1e-6)
        assertEquals(20, det.stoppedSec)
        assertEquals(330.0, det.power.min!!, 1e-6)
        // rides keep their stops
        val ride = buildRaw(listOf(Seg("WORK", 300, 330.0, stopAt = 100, stopLen = 20)), listOf(pw(90, 95, 300)), type = "Ride")
        assertEquals(0, WorkoutDetail.fromRaw(ride, null).stoppedSec)
    }

    @Test fun zonesSubtUnderOverTotals() {
        // 400 W is over 315–332.5 for the whole rep: everything judged is "over"
        val raw = buildRaw(listOf(Seg("WORK", 300, 400.0)), listOf(pw(90, 95, 300)), name = "5'x1 repeats")
        val r = Zones.compute(raw, 153.0, null)
        assertEquals(295.0, r.subtWorkSec, 1e-9)
        assertEquals(295.0, r.subtOverSec, 1e-9); assertEquals(0.0, r.subtInSec, 0.0); assertEquals(0.0, r.subtUnderSec, 0.0)
        assertEquals(295.0 * 3.7, r.subtOverM, 1e-6)
        assertEquals(r.subtWorkSec, r.subtUnderSec + r.subtInSec + r.subtOverSec, 1e-9)
        assertEquals(listOf(r), Zones.fromJson(Zones.toJson(listOf(r))))
        val t = Zones.total(listOf(r, r), LocalDate.parse("2026-11-16"), LocalDate.parse("2026-11-22"))
        assertEquals(590.0, t.subtOverSec, 1e-9); assertEquals(2 * 295.0 * 3.7, t.subtOverM, 1e-6)
    }
}
