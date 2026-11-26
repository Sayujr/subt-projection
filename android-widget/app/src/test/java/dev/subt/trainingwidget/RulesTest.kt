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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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

    @Test fun trainingMixSubtFixture() {
        val r = Zones.compute(Fixture.workoutRaw(), 153.0, null)
        assertEquals(5, r.mixSec.size); assertEquals(5, r.mixM.size)
        assertTrue(r.mixSec.all { it >= 0.0 } && r.mixM.all { it >= 0.0 })
        assertEquals(r.catSec.sum(), r.mixSec.sum(), 1e-6)
        assertTrue(r.mixSec[Zones.MIX_SUBT] > 0.0)
        assertTrue(r.subtOverSec > 0.0)
        assertEquals(r.subtOverSec, r.mixSec[Zones.MIX_ABOVE], 1e-6)
        assertEquals(r, Zones.fromJson(Zones.toJson(listOf(r))).single())
        val t = Zones.total(listOf(r), LocalDate.now().minusDays(30), LocalDate.now())
        assertEquals(r.mixSec, t.mixSec)
    }

    @Test fun trainingMixEasyAndInBetween() {
        // CP 300 W: 150 W is 50 % of CP
        fun run(name: String, hr: Double, watts: Double): Zones.Run {
            val raw = rawOf(name, List(600) { it }, List(600) { hr }, List(600) { 3.0 }).also {
                it.getJSONArray("s").put(org.json.JSONObject().put("type", "watts").put("data", org.json.JSONArray(List(600) { watts })))
                it.getJSONObject("a").put("moving_time", 600)
            }
            return Zones.compute(raw, 153.0, 300.0)
        }
        val easy = run("Tuesday jog", 140.0, 150.0)
        assertEquals(600.0, easy.mixSec[Zones.MIX_EASY], 1e-6)
        assertEquals(1800.0, easy.mixM[Zones.MIX_EASY], 1e-6)
        val hi = run("Tuesday jog", 150.0, 150.0)
        assertEquals(600.0, hi.mixSec[Zones.MIX_BETWEEN], 1e-6)
        val pw = run("Tuesday jog", 140.0, 220.0)   // 73 % of CP
        assertEquals(600.0, pw.mixSec[Zones.MIX_BETWEEN], 1e-6)
        val lt1 = run("Long run", 150.0, 150.0)
        assertEquals(600.0, lt1.mixSec[Zones.MIX_LT1], 1e-6)
        val race = run("Spring 10K race", 175.0, 280.0)
        assertEquals(600.0, race.mixSec[Zones.MIX_ABOVE], 1e-6)
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
        // Totals count the full work time: the skipped first 5 s of each rep count as under
        assertEquals(wk.underSec + wk.inSec + wk.overSec, 4.0 * 360, 1e-9)
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

    // ---- Categories and the load split

    @Test fun categoriesAddUp() {
        // fixture 6'x4: every sample in a category; SubT = the four work steps, whole length
        val r = Zones.compute(Fixture.workoutRaw(), 153.0, null)
        assertEquals(2880.0, r.catSec.sum(), 1e-9)
        assertEquals(4 * 360.0, r.catSec[Zones.SUBT], 1e-9)
        assertTrue(r.catSec[Zones.EASY] > 0)
        assertEquals(r.catSec.sum() > 0, r.catM.sum() > 0)
        // hand-built easy run: 300 s under 135, 200 s at 140 (under LT1 153), 100 s at 160
        val hr = List(300) { 120.0 } + List(200) { 140.0 } + List(100) { 160.0 }
        val e = Zones.compute(rawOf("Tuesday jog", List(600) { it }, hr, List(600) { 3.0 }), 153.0, null)
        assertEquals(listOf(300.0, 200.0, 0.0, 100.0), e.catSec)
        assertEquals(listOf(900.0, 600.0, 0.0, 300.0), e.catM)
        assertEquals(e, Zones.fromJson(Zones.toJson(listOf(e))).single())
    }

    @Test fun subtWorkStepsAndTestsInCategories() {
        val segs = listOf(Seg("REC", 900, 200.0), Seg("WORK", 180, 400.0), Seg("REC", 300, 200.0)) +
            (0 until 3).flatMap { listOf(Seg("WORK", 600, 312.0), Seg("REC", 120, 170.0)) } + Seg("REC", 900, 200.0)
        val doc = listOf(pw(55, 70, 900).put("warmup", true), pw(112, 120, 180), pw(44, 70, 300),
            grp(3, pw(88, 91, 600), pw(40, 60, 120)), pw(55, 70, 900).put("cooldown", true))
        val r = Zones.compute(buildRaw(segs, doc, name = "10'x3 repeats"), 153.0, null)
        assertEquals(4440.0, r.catSec.sum(), 1e-9)
        assertEquals(1800.0, r.catSec[Zones.SUBT], 1e-9)      // the three 10' reps, nothing else
        assertEquals(180.0, r.catSec[Zones.OTHER], 1e-9)      // the test effort
        assertEquals(4440.0 - 1980.0, r.catSec[Zones.STEADY], 1e-9)   // HR 150: between 135 and LT1
    }

    @Test fun loadSplitAddsUp() {
        val run = Zones.Run("a", "2026-11-23", "x", 0, 0.0, false, false, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, catSec = listOf(600.0, 0.0, 1200.0, 0.0))
        // weights 0.42 and 0.86: 252 : 1032
        assertEquals(listOf(20, 0, 80, 0), Zones.loadSplit(100, listOf(run), 0).toList())
        assertEquals(listOf(18, 0, 72, 10), Zones.loadSplit(100, listOf(run), 10).toList())
        for (load in listOf(0, 1, 7, 33, 100, 251)) for (free in listOf(0, 3, 500)) {
            val p = Zones.loadSplit(load, listOf(run, run.copy(catSec = listOf(10.0, 700.0, 50.0, 300.0))), free)
            assertEquals(4, p.size); assertEquals(load, p.sum()); assertTrue(p.all { it >= 0 })
        }
        // no runs: everything is OTHER
        assertEquals(listOf(0, 0, 0, 64), Zones.loadSplit(64, emptyList(), 0).toList())
        assertEquals(listOf(0, 0, 0, 64), Zones.loadSplit(64, emptyList(), 20).toList())
    }

    @Test fun splitBarsStackWithSameHeight() {
        val d = LocalDate.parse("2026-11-18")
        val wk = listOf(Workout("x", "Run", 3000, 40, 9000.0, WorkoutStatus.DONE, date = d.toString(), plannedLoad = 40))
        fun col(split: Map<String, IntArray>?): List<Int> {
            val bmp = Charts.rangeBars(wk, LocalDate.parse("2026-11-16"), LocalDate.parse("2026-11-22"), LocalDate.parse("2026-11-24"), 700, 400, 1f, 10, split)
            val x = (700 / 7 * 2 + 700 / 14)
            return (0 until 400).map { bmp.getPixel(x, it) }
        }
        val plain = col(null); val stacked = col(mapOf(d.toString() to intArrayOf(10, 0, 30, 0)))
        val green = Charts.SPLIT_COLORS[0]; val orange = Charts.SPLIT_COLORS[2]
        assertTrue(plain.none { it == orange })
        assertTrue(stacked.contains(green) && stacked.contains(orange))
        // the bar spans the same rows with or without the split
        fun rows(c: List<Int>, vararg k: Int) = c.indices.filter { c[it] in k }
        val p = rows(plain, green); val q = rows(stacked, green, orange)
        assertTrue("top ${p.first()} vs ${q.first()}", kotlin.math.abs(p.first() - q.first()) <= 2)
        assertTrue("bottom ${p.last()} vs ${q.last()}", kotlin.math.abs(p.last() - q.last()) <= 2)
    }

    // ---- Coach: rule-based read-outs and the hand-off prompt
    private fun coachLine(r: Coach.Readout, needle: String) = r.lines.firstOrNull { it.text.contains(needle, ignoreCase = true) }
    private fun ctlDays(n: Int = 43, f: (Int) -> Double) =
        (0 until n).map { i -> Day(LocalDate.now().minusDays((n - 1 - i).toLong()).toString(), f(i), f(i) - 5.0) }

    @Test fun coachWorkoutReadout() {
        val det = WorkoutDetail.fromRaw(Fixture.workoutRaw(), null)
        val r = Coach.workout(det, null)
        assertTrue(r.lines.isNotEmpty() && r.lines.size <= 5)
        val share = det.work!!.inOrOver!!
        val line = coachLine(r, "on or over target")!!
        val expected = if (share >= 0.85) Coach.Level.GOOD else if (share >= 0.70) Coach.Level.WATCH else Coach.Level.ACT
        assertEquals(expected, line.level)
        assertTrue(line.text, line.text.contains("${Math.round(share * 100)}%"))
        for (n in 1..4) assertTrue("data lists rep $n", r.data.contains("Work rep $n"))
        assertEquals(3, r.questions.size)
    }

    @Test fun coachWorkoutFade() {
        val raw = Fixture.workoutRaw()
        val ivs = raw.getJSONObject("i").getJSONArray("icu_intervals")
        val work = (0 until ivs.length()).map { ivs.getJSONObject(it) }.filter { it.getString("type") == "WORK" }
        val firstAvg = work.first().getDouble("average_watts")
        val last = work.last()
        val factor = firstAvg * 0.95 / last.getDouble("average_watts")
        last.put("average_watts", firstAvg * 0.95)
        val streams = raw.getJSONArray("s")
        for (i in 0 until streams.length()) {
            val st = streams.getJSONObject(i)
            if (st.getString("type") != "watts") continue
            val data = st.getJSONArray("data")
            for (k in last.getInt("start_index") until last.getInt("end_index")) data.put(k, data.getDouble(k) * factor)
        }
        val r = Coach.workout(WorkoutDetail.fromRaw(raw, null), null)
        val faded = coachLine(r, "faded")
        assertNotNull(r.lines.toString(), faded)
        assertEquals(Coach.Level.WATCH, faded!!.level)
        // the unmodified fixture's last rep is higher than its first: no fade
        assertNull(coachLine(Coach.workout(WorkoutDetail.fromRaw(Fixture.workoutRaw(), null), null), "faded"))
    }

    @Test fun coachLoadLines() {
        val rising = Coach.loadReadout(ctlDays { 40.0 + it * 10.0 / 42 })!!
        val up = coachLine(rising, "rising")!!
        assertEquals(Coach.Level.GOOD, up.level)
        assertTrue("slow rise is not a fast ramp", rising.lines.none { it.level == Coach.Level.ACT })
        val spike = Coach.loadReadout(ctlDays { if (it <= 36) 50.0 else 50.0 + (it - 36) })!!   // +6 in the last 7 days
        val ramp = coachLine(spike, "ramping fast")!!
        assertEquals(Coach.Level.ACT, ramp.level)
        val flat = Coach.loadReadout(ctlDays { 50.0 })!!
        assertEquals(Coach.Level.INFO, coachLine(flat, "holding")!!.level)
        val deep = Coach.loadReadout((0 until 43).map { Day(LocalDate.now().minusDays((42 - it).toLong()).toString(), 50.0, 50.0 + (if (it >= 36) 25.0 else 0.0)) })!!
        assertEquals(Coach.Level.WATCH, coachLine(deep, "below −20")!!.level)   // 7 of the last 14 days
        assertNull(Coach.loadReadout(ctlDays(10) { 50.0 }))
    }

    private fun totalWithEasyShare(share: Double): Zones.Total {
        val bands = listOf(share * 4000, share * 6000, 0.0, (1 - share) * 10000, 0.0, 0.0, 0.0)
        return Zones.Total(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 3, bandSec = bands, bandM = List(7) { 0.0 })
    }

    @Test fun coachWeekLines() {
        assertEquals(Coach.Level.WATCH, coachLine(Coach.weekReadout(totalWithEasyShare(0.30), "w")!!, "under 135")!!.level)
        assertEquals(Coach.Level.INFO, coachLine(Coach.weekReadout(totalWithEasyShare(0.50), "w")!!, "under 135")!!.level)
        assertEquals(Coach.Level.GOOD, coachLine(Coach.weekReadout(totalWithEasyShare(0.70), "w")!!, "under 135")!!.level)
        // lines with no data are left out
        val r = Coach.weekReadout(totalWithEasyShare(0.70), "w")!!
        assertNull(coachLine(r, "SubT:")); assertNull(coachLine(r, "Long-run aerobic")); assertNull(coachLine(r, "over 172"))
        // SubT share of work time on or over target: 600 in + 300 over of 1000
        val subt = Zones.Total(600.0, 0.0, 1000.0, 0.0, 0.0, 0.0, 0.0, 1, bandSec = List(7) { 100.0 }, bandM = List(7) { 0.0 }, subtOverSec = 300.0, subtUnderSec = 100.0)
        assertEquals(Coach.Level.GOOD, coachLine(Coach.weekReadout(subt, "w")!!, "SubT:")!!.level)
        val mid = Zones.Total(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1, bandSec = listOf(0.0, 100.0, 500.0, 0.0, 0.0, 0.0, 0.0), bandM = List(7) { 0.0 })
        assertEquals(Coach.Level.WATCH, coachLine(Coach.weekReadout(mid, "w")!!, "in-between")!!.level)
        assertNull(Coach.weekReadout(Zones.Total(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0), "w"))
    }

    @Test fun coachSubtTrendWording() {
        val real = Coach.trendLine(-2.0 / 60 to 0.5 / 60)!!
        assertTrue(real.text, real.text.contains("real improvement")); assertEquals(Coach.Level.GOOD, real.level)
        val noise = Coach.trendLine(-1.0 / 60 to 2.0 / 60)!!
        assertTrue(noise.text, noise.text.contains("within noise")); assertFalse(noise.text.contains("real improvement"))
        assertFalse("a real slowing is not an improvement", Coach.trendLine(2.0 / 60 to 0.5 / 60)!!.text.contains("real improvement"))
        assertNull(Coach.trendLine(null))
    }

    @Test fun coachTodayRestingHr() {
        Prefs.saveSnapshot(ctx, snapWith(51.0, -2.0), 60)
        assertEquals(Coach.Level.GOOD, coachLine(Coach.today(ctx)!!, "Resting HR")!!.level)
        Prefs.saveSnapshot(ctx, snapWith(54.0, -2.0), 60)       // about 3.5 above normal
        assertEquals(Coach.Level.WATCH, coachLine(Coach.today(ctx)!!, "Resting HR")!!.level)
        Prefs.saveSnapshot(ctx, snapWith(57.0, -2.0), 60)       // about 6.5 above
        assertEquals(Coach.Level.ACT, coachLine(Coach.today(ctx)!!, "Resting HR")!!.level)
        ctx.getSharedPreferences("training_widget", Context.MODE_PRIVATE).edit().clear().commit()
        assertNull(Coach.today(ctx))
    }

    @Test fun coachPrompt() {
        Fixture.seed(ctx)
        val det = WorkoutDetail.fromRaw(Fixture.workoutRaw(), null)
        val r = Coach.workout(det, null)
        val p = Coach.prompt(ctx, r, "What should I change next time?")
        val bg = Coach.background(ctx)
        assertTrue(p.startsWith("You are my running coach. Be direct and concise."))
        assertTrue(p.contains("ABOUT ME\n$bg"))
        assertTrue(p.contains("WHAT I'M LOOKING AT (${r.title})\n${r.data}"))
        for (l in r.lines) assertTrue(l.text, p.contains(l.text))
        assertTrue(p.endsWith("MY QUESTION\nWhat should I change next time?"))
        for (k in listOf("sub-3", "Stryd", "172", "135", "SubT", "intervals.icu", "CP / FTP", "351", "Current SubT pace")) assertTrue("background has $k", bg.contains(k))
        Prefs.setCoachTone(ctx, 1); assertTrue(Coach.prompt(ctx, r, "q").contains("Be encouraging but honest."))
        Prefs.setCoachTone(ctx, 2); assertTrue(Coach.prompt(ctx, r, "q").contains("Be analytical, show your reasoning."))
    }

    @Test fun coachReadoutsFromSeededData() {
        Fixture.seed(ctx)
        for ((name, r) in listOf("today" to Coach.today(ctx), "load" to Coach.load(ctx), "subt" to Coach.subt(ctx), "race" to Coach.race(ctx),
            "week" to Coach.week(ctx, LocalDate.now().minusDays(6), LocalDate.now()))) {
            assertNotNull("$name read-out", r)
            assertTrue("$name has 3-5 lines: ${r!!.lines}", r.lines.size in 3..5)
            assertEquals(3, r.questions.size)
            assertTrue(r.data.isNotBlank())
        }
    }

    // ---- coach notes
    private fun sampleNote() = CoachNotes.Note(
        "- Goal: sub-3\n- Weight: 80 kg",
        listOf(
            CoachNotes.WorkoutNote("i1", "6'x4 repeats", "Rep 1: 320 W\nRep 2: 322 W", listOf("Reps on target", "HR settled"),
                "Good work.\n\n- keep targets\n- add 5 W later\n\nSee you Thursday."),
            CoachNotes.WorkoutNote("i2", "3x10' SubT", "Rep 1: 300 W", listOf("Slightly low"), null),
        ),
        listOf(
            CoachNotes.Qa(1, "2026-01-05 19:40", "6'x4 repeats", "Raise targets?", "Reps on target; HR settled", "Not yet.\nHold one more week."),
            CoachNotes.Qa(2, "2026-01-05 19:42", "3x10' SubT", "Too slow?", "Slightly low", null),
        ),
    )

    @Test fun coachNotesRoundTrip() {
        val n = sampleNote()
        assertEquals(n, CoachNotes.parse(CoachNotes.render(n)))
        assertEquals(CoachNotes.render(n), CoachNotes.render(CoachNotes.parse(CoachNotes.render(n))!!))
    }

    @Test fun coachNotesExactFormat() {
        val n = CoachNotes.Note("- Goal: sub-3", listOf(CoachNotes.WorkoutNote("i9", "Easy 8", "Avg 200 W", listOf("On target", "HR fine"), null)),
            listOf(CoachNotes.Qa(1, "2026-01-05 19:40", "Easy 8", "Push more?", "On target; HR fine", null)))
        val expected = "TRAINING TODAY COACH NOTES v1\n" +
            "(Written by the Training Today app. A coach fills in the lines marked (pending). Keep the === lines unchanged.)\n\n" +
            "ABOUT ME\n- Goal: sub-3\n\n" +
            "=== WORKOUT i9 | Easy 8 ===\nDATA\nAvg 200 W\nDRAFT\n- On target\n- HR fine\nRECAP\n(pending)\n\n" +
            "=== QUESTIONS ===\nQ1 | 2026-01-05 19:40 | Easy 8\nPush more?\nCONTEXT: On target; HR fine\nA1\n(pending)"
        assertEquals(expected, CoachNotes.render(n))
        assertEquals(n, CoachNotes.parse(expected))
    }

    @Test fun coachNotesWithWorkout() {
        val n = sampleNote()
        val again = CoachNotes.withWorkout(n, "new about", CoachNotes.WorkoutNote("i1", "6'x4 repeats", "other", listOf("x"), null))
        assertEquals(2, again.workouts.size)
        assertEquals(n.workouts[0], again.workouts[0])
        assertEquals("new about", again.about)
        val added = CoachNotes.withWorkout(n, "a", CoachNotes.WorkoutNote("i3", "Run", "d", listOf("l"), null))
        assertEquals(3, added.workouts.size)
        assertEquals(n.workouts[0].recap, added.workouts[0].recap)
        val fresh = CoachNotes.withWorkout(null, "me", CoachNotes.WorkoutNote("i3", "Run", "d", listOf("l"), null))
        val txt = CoachNotes.render(fresh)
        assertTrue(txt.startsWith("TRAINING TODAY COACH NOTES v1\n"))
        assertTrue(txt.contains("ABOUT ME\nme\n"))
        assertTrue(txt.endsWith("RECAP\n(pending)"))
        assertTrue(fresh.questions.isEmpty())
    }

    @Test fun coachNotesWithQuestion() {
        val n = sampleNote()
        val q = CoachNotes.withQuestion(n, "a", "2026-01-06 08:00", "Topic\nline", "first line\nsecond line\n\nthird", "x".repeat(900))
        assertEquals(3, q.questions.size)
        assertEquals(3, q.questions.last().n)
        assertEquals("first line second line third", q.questions.last().question)
        assertEquals("Topic line", q.questions.last().topic)
        assertEquals(400, q.questions.last().context.length)
        assertEquals(n.questions, q.questions.take(2))
        val first = CoachNotes.withQuestion(null, "a", "t", "tp", "q", "c")
        assertEquals(1, first.questions.single().n)
        assertEquals(q, CoachNotes.parse(CoachNotes.render(q)))
    }

    @Test fun coachNotesParseRobustness() {
        assertNull(CoachNotes.parse("Remember to stretch"))
        assertNull(CoachNotes.parse(""))
        val back = CoachNotes.parse(CoachNotes.render(sampleNote()))!!
        assertEquals("Good work.\n\n- keep targets\n- add 5 W later\n\nSee you Thursday.", back.workouts[0].recap)
        assertEquals("Not yet.\nHold one more week.", back.questions[0].answer)
        assertNull(back.workouts[1].recap)
        assertNull(back.questions[1].answer)
        assertEquals(sampleNote(), CoachNotes.parse(CoachNotes.render(sampleNote()).replace("\n", "\r\n")))
    }

    @Test fun coachNotesOnlyRunsWithTargets() {
        assertTrue(CoachNotes.eligible("Run", true))
        assertTrue(CoachNotes.eligible("VirtualRun", true))
        assertFalse(CoachNotes.eligible("Run", false))
        assertFalse(CoachNotes.eligible("Ride", true))
        assertFalse(CoachNotes.eligible("VirtualRide", true))
        assertFalse(CoachNotes.eligible("Walk", true))
    }

    @Test fun coachNotesFixtureSeeded() {
        Fixture.seed(ctx)
        val note = CoachNotes.parse(Prefs.coachNotes(ctx).values.single())!!
        assertEquals("i999", note.workouts.single().activityId)
        assertNotNull(note.workouts.single().recap)
        assertEquals(listOf(true, false), note.questions.map { it.answer != null })
        assertTrue(Prefs.coachSync(ctx))
    }

    // ================================================================ Run windows

    private val UTC: ZoneId = ZoneId.of("UTC")
    private val RW_DATE: LocalDate = LocalDate.of(2026, 6, 15)       // a Monday in summer: hot from 20 °C
    private val RW_WINTER: LocalDate = LocalDate.of(2026, 1, 15)     // winter: hot from 15 °C
    private val rwS = RwSettings()

    private fun rwT(date: LocalDate, hour: Int, min: Int = 0): Long =
        date.atStartOfDay(UTC).plusHours(hour.toLong()).plusMinutes(min.toLong()).toEpochSecond()

    private fun rwHour(
        date: LocalDate, hour: Int, temp: Double = 12.0, feels: Double? = null, dew: Double = 6.0, cloud: Double = 80.0,
        uv: Double = 0.5, rain: Double = 0.0, pct: Double = 0.0, wind: Double = 8.0, gust: Double = 12.0,
        frozen: Boolean = false, aqi: Double? = null,
    ) = Weather.Combined(
        t = rwT(date, hour), temp = temp, feels = feels ?: temp, dew = dew, cloud = cloud, uv = uv, rainMm = rain, rainPct = pct,
        wind = wind, gust = gust, aqi = aqi, frozen = frozen, agree = 5, total = 5,
    )

    private fun rwDay(f: (Int) -> Weather.Combined): List<Weather.Combined> = (0..23).map(f)
    private fun rwSun(date: LocalDate) = Weather.Sun(date, rwT(date, 6), rwT(date, 20))
    private fun rwRun(type: Weather.RunType, min: Int = 40) = Weather.PlannedRun("run", min, type, planned = true)
    private fun rwPick(date: LocalDate, hours: List<Weather.Combined>, run: Weather.PlannedRun, s: RwSettings = rwS) =
        Weather.pick(hours, date, run, listOf(rwSun(date)), s, null, UTC)
    private fun rwPlan(date: LocalDate, all: List<Weather.Combined>, rb: List<Weather.Combined> = emptyList(), run: Weather.PlannedRun = rwRun(Weather.RunType.EASY)) =
        Weather.plan(date, all, rb, listOf(rwSun(date)), run, rwS, null, UTC)
    private fun rwCls(
        temp: Double, feels: Double? = null, dew: Double = 8.0, cloud: Double = 80.0, uv: Double = 0.5, rain: Double = 0.0,
        night: Boolean = false, date: LocalDate = RW_DATE, s: RwSettings = rwS,
    ) = Weather.classify(rwHour(date, 12, temp = temp, feels = feels, dew = dew, cloud = cloud, uv = uv, rain = rain), s, date.monthValue, night)

    // ---- parsers (field shapes from the spec)

    /** A real Rainbow response (trimmed): hours sit under timelines.hourly with startTimestamp / startTimeIso. */
    @Test fun rwParseRainbowRealShape() {
        val json = """{"timelines": {"hourly": [{"startTimestamp": 1795640400, "startTimeIso": "2026-11-25T16:00:00-05:00", "precipitationChance": 0.2, "temperature": 7.98, "temperatureDewPoint": -2.21, "feelsLikeTemperature": 2.22, "precipitationType": "no_precipitation", "precipitationAmount": 0.0, "precipitationRate": 0.0, "windSpeed": 4.96, "windGust": 9.8, "windDirection": 56, "visibility": 24128, "uvIndex": 0, "humidity": 48, "pressure": 1036.67, "condition": "Cloudy"}, {"startTimestamp": 1795644000, "startTimeIso": "2026-11-25T17:00:00-05:00", "precipitationChance": 3.1, "temperature": 7.95, "temperatureDewPoint": -2.68, "feelsLikeTemperature": 2.18, "precipitationType": "no_precipitation", "precipitationAmount": 0.0, "precipitationRate": 0.0, "windSpeed": 4.88, "windGust": 11.06, "windDirection": 56, "visibility": 24128, "uvIndex": 0, "humidity": 47, "pressure": 1036.67, "condition": "Cloudy"}, {"startTimestamp": 1795647600, "startTimeIso": "2026-11-25T18:00:00-05:00", "precipitationChance": 6.0, "temperature": 8.08, "temperatureDewPoint": -1.28, "feelsLikeTemperature": 2.34, "precipitationType": "no_precipitation", "precipitationAmount": 0.0, "precipitationRate": 0.0, "windSpeed": 5.31, "windGust": 11.78, "windDirection": 57, "visibility": 24128, "uvIndex": 0, "humidity": 51, "pressure": 1036.24, "condition": "MostlyCloudy"}], "daily": [{"startTimestamp": 1795604400, "startTimeIso": "2026-11-25T06:00:00-05:00", "endTimestamp": 1795690800, "endTimeIso": "2026-11-26T06:00:00-05:00", "precipitationChance": 90.5, "temperatureMin": 3.43, "temperatureMax": 9.53, "uvIndexMax": 2, "windSpeedMax": 10.18, "windDirectionAvg": 62, "precipitationType": "rain", "precipitationAmount": 1.31, "condition": "Rain"}]}, "location": {"lat": 1.0, "lon": 2.0}, "units": {"temperature": "celsius", "feelsLikeTemperature": "celsius", "temperatureDewPoint": "celsius", "precipitationAmount": "millimeters", "precipitationRate": "millimeters_per_hour", "precipitationChance": "percent", "windSpeed": "meter_per_second", "windGust": "meter_per_second", "windDirection": "degree", "visibility": "meter", "uvIndex": "index", "humidity": "percent", "pressure": "hectopascal"}, "generatedAtTimestamp": 1795642333, "generatedAtTimeIso": "2026-11-25T21:32:13+00:00"}"""
        val h = Weather.parseRainbow(json)
        assertEquals(3, h.size)
        assertEquals(1795640400L, h[0].t)
        assertEquals(7.98, h[0].temp!!, 1e-9)
        assertEquals(95.0, h[0].cloud!!, 1e-9)           // Cloudy
        assertFalse(Weather.isFrozen(h[0].precipType))   // "no_precipitation"
    }

    @Test fun homeOrder() {
        assertEquals(HomeOrder.DEFAULT, HomeOrder.merge(emptyList()))
        val o = HomeOrder.move(HomeOrder.DEFAULT, "window", -2)
        assertEquals(listOf("verdict", "window", "today", "power"), o.take(4))
        assertEquals(o, HomeOrder.move(o, "verdict", -1))            // can't move above the top
        // A saved order from before a card existed gets that card back in its default place
        val merged = HomeOrder.merge(listOf("window", "verdict"))
        assertEquals(HomeOrder.DEFAULT.size, merged.size)
        assertEquals("window", merged[0])
    }

    @Test fun rwParseCoords() {
        fun ok(s: String, lat: Double, lon: Double) {
            val c = Weather.parseCoords(s); assertNotNull(s, c)
            assertEquals(s, lat, c!!.first, 1e-3); assertEquals(s, lon, c.second, 1e-3)
        }
        ok("39.95, -75.20", 39.95, -75.20)
        ok("39.9532° N, 75.1972° W", 39.9532, -75.1972)
        ok("39.9532N 75.1972W", 39.9532, -75.1972)
        ok("N 39.95 W 75.20", 39.95, -75.20)
        ok("75.1972° W, 39.9532° N", 39.9532, -75.1972)
        ok("39°57'11\"N 75°11'50\"W", 39.9531, -75.1972)
        ok("-33.87 151.21", -33.87, 151.21)
        assertNull(Weather.parseCoords("Philadelphia"))
        assertNull(Weather.parseCoords("New York 10001"))
        assertNull(Weather.parseCoords("95, 200"))
    }

    @Test fun rwParseRainbow() {
        val json = """{"hourly":[
            {"time":"2026-06-15T14:00:00Z","temperature":18.5,"feelsLikeTemperature":18.0,"temperatureDewPoint":9.0,"uvIndex":5.2,
             "precipitationChance":40,"precipitationAmount":0.3,"precipitationRate":0.3,"precipitationType":"rain",
             "windSpeed":5.0,"windGust":9.0,"visibility":10000,"humidity":55,"condition":"PartlyCloudy"},
            {"time":"2026-06-15T15:00:00Z","temperature":19.0,"condition":"Breezy"}]}"""
        val h = Weather.parseRainbow(json)
        assertEquals(2, h.size)
        assertEquals(Instant.parse("2026-06-15T14:00:00Z").epochSecond, h[0].t)
        assertEquals(18.5, h[0].temp!!, 1e-9)
        assertEquals(18.0, h[0].wind!!, 1e-9)        // 5 m/s → km/h
        assertEquals(32.4, h[0].gust!!, 1e-9)
        assertEquals(50.0, h[0].cloud!!, 1e-9)       // PartlyCloudy → 50 %
        assertEquals(5.2, h[0].uv!!, 1e-9)
        assertEquals(0.3, h[0].rainMm!!, 1e-9)
        assertEquals(40.0, h[0].rainPct!!, 1e-9)
        assertNull("Breezy says nothing about cloud", h[1].cloud)
        assertEquals(95.0, Weather.condCloud("Drizzle")!!, 1e-9)
        assertEquals(5.0, Weather.condCloud("Clear")!!, 1e-9)
        assertEquals(25.0, Weather.condCloud("MostlyClear")!!, 1e-9)
        assertEquals(75.0, Weather.condCloud("MostlyCloudy")!!, 1e-9)
        assertTrue(Weather.parseRainbow("not json").isEmpty())
    }

    @Test fun rwParseOpenMeteoModelsAndLocalTimes() {
        // Denver in summer: UTC−6, so 08:00 local is 14:00 UTC. HRRR has nothing here (all null).
        val json = """{"utc_offset_seconds":-21600,"hourly":{"time":["2026-06-15T08:00","2026-06-15T09:00"],
            "temperature_2m_ecmwf_ifs025":[10.0,12.0],"cloud_cover_ecmwf_ifs025":[20,30],"uv_index_ecmwf_ifs025":[1.0,3.0],
            "temperature_2m_ncep_hrrr_conus":[null,null],"cloud_cover_ncep_hrrr_conus":[null,null],
            "temperature_2m_gfs_seamless":[11.0,13.0],"cloud_cover_gfs_seamless":[60,70],"weather_code_gfs_seamless":[3,73]},
            "daily":{"time":["2026-06-15"],"sunrise":["2026-06-15T05:30"],"sunset":["2026-06-15T20:30"]}}"""
        val om = Weather.parseOpenMeteo(json)!!
        assertEquals(setOf("ecmwf", "gfs"), om.models.keys)
        assertEquals(Instant.parse("2026-06-15T14:00:00Z").epochSecond, om.models.getValue("ecmwf")[0].t)
        assertEquals(3.0, om.models.getValue("ecmwf")[1].uv!!, 1e-9)
        assertEquals("snow", om.models.getValue("gfs")[1].precipType)
        assertEquals(Instant.parse("2026-06-15T11:30:00Z").epochSecond, om.sun[0].rise)
        assertEquals(Instant.parse("2026-06-16T02:30:00Z").epochSecond, om.sun[0].set)
        assertNull(Weather.parseOpenMeteo("{}"))
    }

    @Test fun rwParseNwsAndNowcastAndAqi() {
        val grid = """{"properties":{"skyCover":{"values":[
            {"validTime":"2026-06-15T14:00:00+00:00/PT3H","value":80},{"validTime":"2026-06-15T17:00:00+00:00/PT1H","value":20}]}}}"""
        val h = Weather.parseNws(grid)
        assertEquals(4, h.size)
        assertEquals(listOf(80.0, 80.0, 80.0, 20.0), h.map { it.cloud })
        assertEquals(Instant.parse("2026-06-15T17:00:00Z").epochSecond, h[3].t)
        assertEquals("https://api.weather.gov/gridpoints/BOU/62,61",
            Weather.parseNwsGridUrl("""{"properties":{"forecastGridData":"https://api.weather.gov/gridpoints/BOU/62,61"}}"""))
        assertEquals(3, Weather.isoHours("PT3H")); assertEquals(26, Weather.isoHours("P1DT2H")); assertEquals(1, Weather.isoHours("PT30M"))

        val now = Weather.parseNowcast("""{"forecast":[
            {"precipRate":0.0,"precipType":"rain","timestampBegin":1781532000,"timestampEnd":1781532060},
            {"precipRate":1.5,"precipType":"snow","timestampBegin":1781532060,"timestampEnd":1781532120}],"summary":{"intensity":"light"}}""")
        assertEquals(2, now.size)
        assertEquals(1.5, now[1].rate, 1e-9)
        assertEquals(1781532120L, now[1].end)

        val aqi = Weather.parseAqi("""{"utc_offset_seconds":0,"hourly":{"time":["2026-06-15T12:00","2026-06-15T13:00"],"us_aqi":[40,120]}}""")
        assertEquals(120.0, aqi.getValue(Instant.parse("2026-06-15T13:00:00Z").epochSecond), 1e-9)
    }

    // ---- combining

    @Test fun rwCombineMedianUvMaxAndAgreement() {
        val t = rwT(RW_DATE, 12)
        val src = mapOf(
            "rainbow" to listOf(Weather.Hour(t, temp = 10.0, feels = 9.0, dew = 5.0, cloud = 50.0, uv = 1.0, rainMm = 0.0, wind = 10.0)),
            "ecmwf" to listOf(Weather.Hour(t, temp = 12.0, feels = 12.0, dew = 7.0, cloud = 20.0, uv = 3.0, rainMm = 0.0, wind = 14.0)),
            "gfs" to listOf(Weather.Hour(t, temp = 20.0, feels = 20.0, dew = 9.0, cloud = 10.0, uv = 2.0, rainMm = 0.0, wind = 18.0)),
            "nws" to listOf(Weather.Hour(t, cloud = 90.0)),
            "nbm" to listOf(Weather.Hour(t, cloud = 80.0)),
            "hrrr" to listOf(Weather.Hour(t, cloud = 70.0)),
        )
        val c = Weather.combine(src).single()
        assertEquals("median of 10, 12, 20", 12.0, c.temp!!, 1e-9)
        assertEquals("median of 9, 12, 20", 12.0, c.feels!!, 1e-9)
        assertEquals("median dew point", 7.0, c.dew!!, 1e-9)
        assertEquals("median wind", 14.0, c.wind!!, 1e-9)
        assertEquals("UV is the highest of the sources", 3.0, c.uv, 1e-9)
        assertEquals("median of six cloud votes", 60.0, c.cloud!!, 1e-9)
        assertEquals(6, c.total)
        assertEquals("3 cloudy, 3 clear → 3 agree with a cloudy median", 3, c.agree)
    }

    @Test fun rwCombineDissentAndRainChance() {
        val t = rwT(RW_DATE, 12)
        val src = mapOf(
            "rainbow" to listOf(Weather.Hour(t, temp = 10.0, cloud = 90.0, rainMm = 0.0, rainPct = 10.0)),
            "ecmwf" to listOf(Weather.Hour(t, temp = 10.0, cloud = 10.0, rainMm = 0.0, rainPct = 55.0)),
            "gfs" to listOf(Weather.Hour(t, temp = 10.0, cloud = 90.0, rainMm = 0.0, rainPct = 20.0)),
            "nws" to listOf(Weather.Hour(t, cloud = 85.0)),
            "nbm" to listOf(Weather.Hour(t, cloud = 90.0)),
            "hrrr" to listOf(Weather.Hour(t, cloud = 90.0)),
        )
        val c = Weather.combine(src).single()
        assertEquals(5, c.agree); assertEquals(6, c.total)
        assertEquals(listOf("ecmwf" to "clear"), c.dissent)
        assertEquals("rain chance is the maximum", 55.0, c.rainPct, 1e-9)
        assertEquals("rain amount is the median", 0.0, c.rainMm, 1e-9)
    }

    @Test fun rwNowcastOverridesRain() {
        val t = rwT(RW_DATE, 12)
        val dry = mapOf("rainbow" to listOf(Weather.Hour(t, temp = 10.0, cloud = 90.0, rainMm = 0.0)), "gfs" to listOf(Weather.Hour(t, temp = 11.0, cloud = 90.0, rainMm = 0.0)))
        // the whole hour at 2 mm/h
        val wet = (0 until 60).map { Weather.NowStep(t + it * 60L, t + it * 60L + 60, 2.0, "rain") }
        assertEquals(2.0, Weather.combine(dry, wet).single().rainMm, 1e-9)
        // half the hour at 4 mm/h, half dry: mean of the 10-minute maxima
        val half = (0 until 60).map { Weather.NowStep(t + it * 60L, t + it * 60L + 60, if (it < 30) 4.0 else 0.0, "rain") }
        assertEquals(2.0, Weather.combine(dry, half).single().rainMm, 1e-9)
        // only 20 minutes of nowcast covers the hour: ignored, the sources' median stays
        val short = (0 until 20).map { Weather.NowStep(t + it * 60L, t + it * 60L + 60, 5.0, "rain") }
        assertEquals(0.0, Weather.combine(dry, short).single().rainMm, 1e-9)
        // the 10-minute step keeps its maximum
        val spike = (0 until 60).map { Weather.NowStep(t + it * 60L, t + it * 60L + 60, if (it == 5) 6.0 else 0.0, "rain") }
        assertEquals(1.0, Weather.combine(dry, spike).single().rainMm, 1e-9)
    }

    @Test fun rwIceAfterRainAndFrozenType() {
        val t = rwT(RW_DATE, 12)
        val src = mapOf("gfs" to listOf(
            Weather.Hour(t - 3600, temp = 1.0, cloud = 90.0, rainMm = 1.0),
            Weather.Hour(t, temp = -2.0, cloud = 90.0, rainMm = 0.0),
            Weather.Hour(t + 3600, temp = 6.0, cloud = 90.0, rainMm = 0.0),
        ))
        val c = Weather.combine(src)
        assertFalse(c[0].icy)
        assertTrue("at or below 0 °C within 12 h of rain", c[1].icy)
        assertFalse(c[2].icy)
        assertTrue(Weather.dangerous(c[1]))
        assertTrue(Weather.combine(mapOf("rainbow" to listOf(Weather.Hour(t, temp = 0.5, cloud = 90.0, rainMm = 1.0, precipType = "snow")))).single().frozen)
    }

    // ---- classification and colour

    @Test fun rwEffectiveTemperature() {
        assertEquals("feels-like below 5", 3.0, Weather.effTemp(8.0, 3.0)!!, 1e-9)
        assertEquals("feels-like above 25", 27.0, Weather.effTemp(24.0, 27.0)!!, 1e-9)
        assertEquals("air temperature in between", 15.0, Weather.effTemp(15.0, 12.0)!!, 1e-9)
        assertEquals(15.0, Weather.effTemp(15.0, null)!!, 1e-9)
        assertEquals(Weather.Band.COLD, rwCls(temp = 8.0, feels = 3.0).band)
        assertEquals(Weather.Band.COOL, rwCls(temp = 8.0).band)
        val hot26 = rwS.copy(hotSummer = 26.0)
        assertEquals("feels-like 27 is hot", Weather.Band.HOT, rwCls(temp = 24.0, feels = 27.0, s = hot26).band)
        assertEquals(Weather.Band.WARM, rwCls(temp = 24.0, feels = 24.0, s = hot26).band)
    }

    @Test fun rwHotThresholdDependsOnMonth() {
        assertEquals(Weather.Band.HOT, rwCls(temp = 16.0, date = RW_WINTER).band)
        assertEquals(Weather.Band.WARM, rwCls(temp = 16.0, date = RW_DATE).band)
        assertEquals(Weather.Band.HOT, rwCls(temp = 20.0, date = RW_DATE).band)
        assertEquals(Weather.Band.COLD, rwCls(temp = 4.9).band)
        assertEquals(Weather.Band.COOL, rwCls(temp = 5.0).band)
        assertEquals(Weather.Band.WARM, rwCls(temp = 14.0).band)
    }

    @Test fun rwColourTable() {
        assertEquals("hot, sunny and humid", Weather.Tone.DEEP, rwCls(temp = 28.0, dew = 18.0, cloud = 20.0, uv = 7.0).tone)
        assertEquals("hot and sunny", Weather.Tone.DRED, rwCls(temp = 28.0, dew = 10.0, cloud = 20.0, uv = 7.0).tone)
        assertEquals("hot and humid with rain", Weather.Tone.RED, rwCls(temp = 26.0, dew = 18.0, cloud = 90.0, uv = 1.0, rain = 1.0).tone)
        assertEquals("hot, humid, cloudy", Weather.Tone.ORR, rwCls(temp = 26.0, dew = 18.0, cloud = 90.0, uv = 1.0).tone)
        assertEquals("warm and sunny", Weather.Tone.ORR, rwCls(temp = 17.0, cloud = 20.0, uv = 5.0).tone)
        assertEquals("warm, humid, cloudy", Weather.Tone.OR, rwCls(temp = 17.0, dew = 17.0, cloud = 90.0, uv = 1.0).tone)
        assertEquals("warm, cloudy, low UV", Weather.Tone.LO2, rwCls(temp = 17.0, dew = 8.0, cloud = 90.0, uv = 1.0).tone)
        assertEquals("cool, sunny and humid", Weather.Tone.LO2, rwCls(temp = 10.0, dew = 17.0, cloud = 20.0, uv = 4.0).tone)
        assertEquals("cool, cloudy, low UV", Weather.Tone.BEST, rwCls(temp = 10.0, cloud = 90.0, uv = 0.5).tone)
        assertEquals("cold and cloudy", Weather.Tone.CHILL, rwCls(temp = 2.0, cloud = 90.0, uv = 0.3).tone)
        assertEquals("below 0 °C with UV 0–1", Weather.Tone.CHILL, rwCls(temp = -4.0, cloud = 30.0, uv = 0.8).tone)
        assertEquals("cold and sunny", Weather.Tone.LO1, rwCls(temp = 2.0, cloud = 20.0, uv = 3.0).tone)
        assertEquals("night hours are drawn dark", Weather.Tone.NIGHT, rwCls(temp = 10.0, night = true).tone)
        assertEquals(0xFF7FB59A.toInt(), Weather.Tone.BEST.argb); assertEquals(0xFFA3241C.toInt(), Weather.Tone.DEEP.argb); assertEquals(0xFF2A303D.toInt(), Weather.Tone.NIGHT.argb)
    }

    @Test fun rwHourFlagsAndIcons() {
        val h = rwHour(RW_DATE, 12, uv = 4.0, cloud = 20.0, rain = 0.8, aqi = 120.0, gust = 40.0)
        val c = Weather.classify(h, rwS, 6, false)
        assertTrue(c.sunny); assertTrue(c.wet)
        val easy = Weather.icons(h, c, Weather.RunType.EASY, rwS)
        assertTrue("💧" in easy && "😷" in easy && "☀" in easy)
        assertFalse("gust icon only on TT days", "💨" in easy)
        assertTrue("💨" in Weather.icons(h, c, Weather.RunType.TT, rwS))
        assertFalse("AQI 120 is a caution, not dangerous", Weather.dangerous(h))
        assertTrue(Weather.dangerous(rwHour(RW_DATE, 12, aqi = 160.0)))
        assertTrue(rwCls(temp = 10.0, cloud = 90.0).cloudy)
        // light rain counts as dry
        assertFalse(rwCls(temp = 10.0, rain = 0.3).wet)
    }

    // ---- window picks per run type

    @Test fun rwTtPicksCoolestAbove5() {
        val d = RW_DATE
        val hours = rwDay { h -> when (h) {
            in 0..8 -> rwHour(d, h, temp = 3.0, uv = 0.5, dew = 4.0)
            in 9..11 -> rwHour(d, h, temp = 8.0, uv = 1.5, dew = 4.0)
            else -> rwHour(d, h, temp = 16.0 + (h - 12), uv = 3.5, cloud = 40.0, dew = 8.0)
        } }
        val w = rwPick(d, hours, rwRun(Weather.RunType.TT, 40)).best!!
        assertTrue("never below 5 °C: ${w.minTemp}", w.minTemp!! >= 5.0)
        assertTrue("in the 8 °C stretch, starting ${(w.start - rwT(d, 0)) / 60} min in", w.start >= rwT(d, 9) && w.start <= rwT(d, 11))
    }

    @Test fun rwTtMayBreakUvLimitThenCap() {
        val d = RW_DATE
        // 5 °C all day but sunny: TT accepts the UV and asks for a cap
        val hours = rwDay { h -> rwHour(d, h, temp = 8.0, cloud = 20.0, uv = 5.0, dew = 3.0) }
        val w = rwPick(d, hours, rwRun(Weather.RunType.TT, 40)).best!!
        assertTrue("🧢" in w.icons)
        assertTrue(w.warnings.any { it.contains("cap") })
    }

    @Test fun rwLongAvoidsColdAndUvButAcceptsHeat() {
        val d = RW_DATE
        val hours = rwDay { h -> when (h) {
            in 6..8 -> rwHour(d, h, temp = 6.0, feels = 3.0, uv = 0.3, cloud = 90.0)
            in 9..12 -> rwHour(d, h, temp = 12.0, uv = 0.8, cloud = 70.0)
            in 13..19 -> rwHour(d, h, temp = 29.0, uv = 6.0, cloud = 20.0, dew = 12.0)
            else -> rwHour(d, h, temp = 12.0, uv = 0.5, cloud = 80.0)
        } }
        val w = rwPick(d, hours, rwRun(Weather.RunType.LONG, 90)).best!!
        assertTrue("feels no colder than 5 °C: ${w.minFeels}", w.minFeels!! >= 5.0)
        assertTrue("UV within limit − 1: ${w.maxUv}", w.maxUv <= 1.0)
        assertTrue(w.start >= rwT(d, 9) && w.end <= rwT(d, 13))

        val hot = rwDay { h -> rwHour(d, h, temp = 28.0, uv = 0.5, cloud = 95.0, dew = 10.0) }
        val long = rwPick(d, hot, rwRun(Weather.RunType.LONG, 90)).best!!
        val easy = rwPick(d, hot, rwRun(Weather.RunType.EASY, 90)).best!!
        val subt = rwPick(d, hot, rwRun(Weather.RunType.SUBT, 90)).best!!
        assertTrue("heat costs a long run nothing: ${long.score}", long.score < 3.0)
        assertTrue("heat costs an easy run nothing: ${easy.score}", easy.score < 3.0)
        assertTrue("but SubT feels it: ${subt.score}", subt.score > long.score + 10)
        assertFalse(long.dangerous)
    }

    @Test fun rwEasyAcceptsHeatTtDoesNot() {
        val d = RW_DATE
        // hot, cloudy, low-UV morning vs a cooler but sunny midday
        val hours = rwDay { h ->
            if (h in 11..19) rwHour(d, h, temp = 15.0, uv = 3.0, cloud = 10.0, dew = 8.0)
            else rwHour(d, h, temp = 24.0, uv = 0.8, cloud = 90.0, dew = 14.0)
        }
        val easy = rwPick(d, hours, rwRun(Weather.RunType.EASY, 40)).best!!
        val tt = rwPick(d, hours, rwRun(Weather.RunType.TT, 40)).best!!
        assertTrue("easy takes the hot, cloudy morning", easy.end <= rwT(d, 11, 30))
        assertTrue("TT wants the cooler midday", tt.start >= rwT(d, 11))
    }

    @Test fun rwHeavyRainAllDayAndIce() {
        val d = RW_DATE
        val rain = rwDay { h -> rwHour(d, h, temp = 10.0, cloud = 100.0, uv = 0.0, rain = 3.0, pct = 90.0) }
        val pk = rwPick(d, rain, rwRun(Weather.RunType.EASY))
        val w = pk.best!!
        assertTrue(w.wet); assertFalse(pk.risky)
        assertTrue("💧" in w.icons)
        assertTrue(w.warnings.any { it.contains("gym or outside") })
        assertTrue(rwPlan(d, rain).summary.suggestion.startsWith("Gym or outside"))

        val ice = rwDay { h -> rwHour(d, h, temp = -2.0, cloud = 100.0, uv = 0.0, rain = 1.0, frozen = true) }
        val ik = rwPick(d, ice, rwRun(Weather.RunType.EASY))
        assertTrue("every window has a dangerous hour", ik.risky)
        assertTrue(ik.best!!.dangerous)
        assertTrue("⛔" in ik.best!!.icons)
        assertTrue(ik.best!!.warnings.any { it.startsWith("⛔") && it.contains("gym recommended") })
        val plan = rwPlan(d, ice)
        assertTrue(plan.risky)
        assertTrue(plan.summary.suggestion.contains("Gym recommended"))
        assertTrue("risky shows in the shift signature", "risky" in Weather.sigOf(plan)!!.warns)
        assertTrue("rain shows in the shift signature", "rain" in Weather.sigOf(rwPlan(d, rain))!!.warns)
    }

    @Test fun rwNarrowWindowWarns() {
        val d = RW_DATE
        // only 07:00-08:00 is dry; a 30 min run + 30 min buffer needs exactly that hour
        val hours = rwDay { h -> if (h == 7) rwHour(d, h, rain = 0.0) else rwHour(d, h, rain = 3.0, pct = 90.0) }
        val w = rwPick(d, hours, rwRun(Weather.RunType.EASY, 30)).best!!
        assertEquals(rwT(d, 7), w.start)
        assertTrue(w.narrow)
        assertTrue("⚠" in w.icons)
        // a wide dry stretch is not narrow
        val wide = rwDay { h -> rwHour(d, h, rain = 0.0) }
        assertFalse(rwPick(d, wide, rwRun(Weather.RunType.EASY, 30)).best!!.narrow)
    }

    @Test fun rwNightWindowOnlyWhenNothingFitsInDaylight() {
        val d = RW_DATE
        val hours = rwDay { h -> rwHour(d, h) }
        // 16 h of running (930 min + 30 min buffer): nothing fits between sunrise − 30 min and sunset + 30 min (15 h)
        val w = rwPick(d, hours, rwRun(Weather.RunType.EASY, 930)).best
        assertNotNull(w)
        assertTrue(w!!.night); assertTrue("🌙" in w.icons)
        assertFalse(rwPick(d, hours, rwRun(Weather.RunType.EASY, 40)).best!!.night)
    }

    @Test fun rwRainbowPickDiffersFromOverall() {
        val d = RW_DATE
        val all = rwDay { h -> if (h < 10) rwHour(d, h, rain = 0.0) else rwHour(d, h, rain = 3.0, pct = 90.0) }
        val rb = rwDay { h -> if (h < 10) rwHour(d, h, rain = 3.0, pct = 90.0) else rwHour(d, h, rain = 0.0) }
        val p = rwPlan(d, all, rb)
        assertNotNull(p.window); assertNotNull(p.rainbow)
        assertFalse("different windows", p.same)
        assertTrue(p.window!!.end <= rwT(d, 10, 30))
        assertTrue(p.rainbow!!.start >= rwT(d, 10))
        assertNotNull(p.summary.rainbow)
        assertTrue(p.summary.rainbow!!.contains("Rainbow alone would pick"))

        val same = rwPlan(d, all, all)
        assertTrue(same.same)
        assertNull(same.summary.rainbow)
        assertTrue(same.summary.agree!!.contains("✓"))

        val noRainbow = rwPlan(d, all)
        assertFalse(noRainbow.rainbowPresent)
        assertTrue(noRainbow.summary.agree!!.contains("Rainbow isn't connected"))
    }

    @Test fun rwWindowOverlapRule() {
        val d = RW_DATE
        fun win(a: Long, b: Long) = rwPick(d, rwDay { rwHour(d, it) }, rwRun(Weather.RunType.EASY, 40)).best!!.copy(start = a, end = b)
        val a = win(rwT(d, 7), rwT(d, 8, 30))
        assertEquals(1.0, Weather.overlap(a, a), 1e-9)
        assertTrue(Weather.overlap(a, win(rwT(d, 7, 30), rwT(d, 9))) < 0.8)
        assertTrue(Weather.overlap(a, win(rwT(d, 7, 15), rwT(d, 8, 45))) >= 0.8)
        assertEquals(0.0, Weather.overlap(a, win(rwT(d, 12), rwT(d, 13, 30))), 1e-9)
    }

    @Test fun rwSummaryHasTheDayShapeAndOneSuggestion() {
        val d = RW_DATE
        val hours = rwDay { h -> rwHour(d, h, temp = 9.0 + h / 3.0, cloud = 80.0, uv = if (h in 11..15) 4.0 else 0.5, rain = if (h in 16..18) 1.0 else 0.0) }
        val p = rwPlan(d, hours)
        val lines = p.summary.lines()
        assertTrue(lines[0], lines[0].contains("°C") && lines[0].contains("rain 16:00–19:00") && lines[0].contains("UV up to 4"))
        assertTrue(lines.any { it.startsWith("Best for your Easy run") })
        assertTrue(p.summary.suggestion.startsWith("Go at") || p.summary.suggestion.startsWith("Gym"))
        assertTrue(p.summary.short.isNotBlank())
        assertFalse(rwPlan(d, emptyList()).let { !it.noData })
    }

    @Test fun rwSwapHintIsTextOnly() {
        val d = RW_DATE
        val bad = rwPlan(d, rwDay { h -> rwHour(d, h, rain = 3.0, pct = 90.0) }, run = rwRun(Weather.RunType.EASY))
        val d2 = d.plusDays(1)
        val good = rwPlan(d2, rwDay { h -> rwHour(d2, h, rain = 0.0) })
        val hints = Weather.swapHints(listOf(bad, good), UTC)
        assertEquals(setOf(d), hints.keys)
        assertTrue(hints.getValue(d).contains("swap with Tuesday"))
        assertTrue(bad.withSwap(hints[d]).summary.lines().any { it.contains("swap with Tuesday") })
    }

    // ---- shift detection

    @Test fun rwShiftDetection() {
        val d = RW_DATE
        val a = Weather.Sig(rwT(d, 7), rwT(d, 8, 30), emptySet())
        assertNull(Weather.shiftMessage(null, a, UTC))
        assertNull("unchanged", Weather.shiftMessage(a, a, UTC))
        assertNull("20 minutes is not a shift", Weather.shiftMessage(a, a.copy(start = a.start + 1200, end = a.end + 1200), UTC))
        val moved = Weather.shiftMessage(a, Weather.Sig(rwT(d, 8), rwT(d, 9, 30), emptySet()), UTC)!!
        assertEquals("Run window shifted: forecast changed → best is 08:00–09:30 (was 07:00–08:30)", moved)
        assertNotNull("30 minutes is a shift", Weather.shiftMessage(a, a.copy(start = a.start + 1800, end = a.end + 1800), UTC))
        assertTrue(Weather.shiftMessage(a, a.copy(warns = setOf("rain")), UTC)!!.contains("rain now expected"))
        assertTrue(Weather.shiftMessage(a.copy(warns = setOf("rain")), a, UTC)!!.contains("rain has cleared"))
        assertTrue(Weather.shiftMessage(a, a.copy(warns = setOf("risky")), UTC)!!.contains("conditions now risky"))
        assertTrue(Weather.shiftMessage(a, a.copy(warns = setOf("narrow")), UTC)!!.contains("window has narrowed"))
        assertTrue(Weather.shiftMessage(a, null, UTC)!!.contains("no window fits"))
        assertEquals(a.copy(warns = setOf("narrow", "rain")), Weather.Sig.decode(a.copy(warns = setOf("rain", "narrow")).encode()))
        assertNull(Weather.Sig.decode("garbage"))
    }

    @Test fun rwQuietHoursAndStripRange() {
        assertTrue(Weather.quiet(22)); assertTrue(Weather.quiet(23)); assertTrue(Weather.quiet(0)); assertTrue(Weather.quiet(4))
        assertFalse(Weather.quiet(5)); assertFalse(Weather.quiet(12)); assertFalse(Weather.quiet(21))
        val d = RW_DATE
        // no sun times: 05:00-21:00
        val none = Weather.stripRange(d, null, rwS, UTC)
        assertEquals(rwT(d, 5), none.first); assertEquals(rwT(d, 21), none.second)
        // daylight 06:00-20:00 ± 30 min ± 2 h
        val auto = Weather.stripRange(d, rwSun(d), rwS, UTC)
        assertEquals(rwT(d, 3), auto.first); assertEquals(rwT(d, 23), auto.second)
        // custom summer hours 07:00-19:00
        val custom = Weather.stripRange(d, rwSun(d), rwS.copy(auto = false, summerFrom = 7 * 60, summerTo = 19 * 60), UTC)
        assertEquals(rwT(d, 5), custom.first); assertEquals(rwT(d, 21), custom.second)
    }

    // ---- the planned run

    @Test fun rwPlannedRunAndType() {
        val ds = RW_DATE.toString()
        fun wk(name: String, type: String = "Run", sec: Int = 0, dist: Double = 0.0, st: WorkoutStatus = WorkoutStatus.PLANNED) = Workout(name, type, sec, 0, dist, st, ds)
        assertEquals(Weather.RunType.TT, Weather.runOf(listOf(wk("5K TT", sec = 2400)), ds).type)
        assertEquals(40, Weather.runOf(listOf(wk("5K TT", sec = 2400)), ds).minutes)
        assertEquals("a race is a TT day", Weather.RunType.TT, Weather.runOf(listOf(wk("Marathon", st = WorkoutStatus.RACE, sec = 12000)), ds).type)
        assertEquals(Weather.RunType.SUBT, Weather.runOf(listOf(wk("3x10' SubT", sec = 4200)), ds).type)
        assertEquals(Weather.RunType.LONG, Weather.runOf(listOf(wk("Long run", sec = 5400)), ds).type)
        assertEquals(Weather.RunType.EASY, Weather.runOf(listOf(wk("60m Easy", sec = 3600)), ds).type)
        assertEquals("no duration: distance at 5:30/km", 55, Weather.runOf(listOf(wk("Easy", dist = 10000.0)), ds).minutes)
        val none = Weather.runOf(listOf(wk("Easy bike", type = "Ride", sec = 3600)), ds)
        assertFalse(none.planned); assertEquals(60, none.minutes); assertEquals(Weather.RunType.EASY, none.type)
        assertEquals(60, Weather.runOf(listOf(wk("Easy")), ds).minutes)
        val done = Weather.runOf(listOf(wk("60m Easy", sec = 3600, st = WorkoutStatus.DONE)), ds)
        assertTrue(done.done)
        assertEquals("planned beats done", "Hill reps", Weather.runOf(listOf(wk("60m Easy", sec = 3600, st = WorkoutStatus.DONE), wk("Hill reps", sec = 1800)), ds).name)
    }

    // ---- storage, key security, notifications

    @Test fun rwSettingsRoundTripAndBackupLeavesKeyOut() {
        RwStore.setLocation(ctx, 40.0, -105.0, "Test place")
        RwStore.putFloat(ctx, RwStore.K_UV, 3.0)
        RwStore.putInt(ctx, RwStore.K_BUFFER, 45)
        RwStore.putBool(ctx, RwStore.K_AUTO, false)
        val s = RwStore.load(ctx)
        assertEquals(40.0, s.lat!!, 1e-9); assertEquals("Test place", s.place)
        assertEquals(3.0, s.uvLimit, 1e-9); assertEquals(45, s.bufferMin); assertFalse(s.auto)
        assertEquals(15.0, s.hotWinter, 1e-9); assertEquals(20.0, s.hotSummer, 1e-9); assertEquals(5.0, s.cold, 1e-9); assertEquals(16.0, s.humid, 1e-9)

        // the encrypted key, cached forecasts, call counts and last-seen windows never go into a backup
        val sp = ctx.getSharedPreferences("training_widget", Context.MODE_PRIVATE)
        sp.edit().putString("rw_key_enc", "ENCRYPTED-BLOB").putString("rw_key_iv", "IV-BLOB").putString("rw_cache_om", "{\"cached\":1}")
            .putInt("rw_calls_2026-06", 12).putString("rw_sig_2026-06-15", "1|2|").apply()
        val backup = Backup.export(ctx)
        assertFalse(backup.contains("rw_key")); assertFalse(backup.contains("ENCRYPTED-BLOB"))
        assertFalse(backup.contains("rw_cache_")); assertFalse(backup.contains("rw_calls_")); assertFalse(backup.contains("rw_sig_"))
        assertTrue("settings are backed up", backup.contains("rw_uv") && backup.contains("rw_lat"))
    }

    @Test fun rwSecureKeyNeverCrashes() {
        // Robolectric has no AndroidKeyStore: saving reports false instead of throwing; with a Keystore the key round-trips
        val fake = "test-not-a-real-key"
        val saved = SecureKey.save(ctx, fake)
        if (saved) { assertEquals(fake, SecureKey.load(ctx)); assertEquals("••••-key", SecureKey.masked(ctx)) }
        else { assertFalse(SecureKey.has(ctx)); assertNull(SecureKey.load(ctx)); assertNull(SecureKey.masked(ctx)) }
        SecureKey.remove(ctx)
        assertFalse(SecureKey.has(ctx))
        assertNull(SecureKey.load(ctx))
    }

    @Test fun rwRainbowCallsAreCountedPerMonth() {
        assertEquals(0, RwStore.callsThisMonth(ctx))
        RwStore.addCall(ctx); RwStore.addCall(ctx)
        assertEquals(2, RwStore.callsThisMonth(ctx))
        assertTrue(WeatherApi.rainbowForecastUrl(40.015, -105.27).startsWith("https://api.rainbow.ai/weather/v1/forecast/-105.2700/40.0150?forecast_days=7&forecast_hours=96"))
        assertTrue(WeatherApi.rainbowNowcastUrl(40.015, -105.27).endsWith("/nowcast/v1/precip/-105.2700/40.0150"))
    }

    @Test fun rwNoLocationMeansNoFetchAndNoPlans() {
        assertFalse(RunWindows.hasLocation(ctx))
        val rep = WeatherApi.fetchAll(ctx)
        assertFalse(rep.anyOk)
        assertTrue(RunWindows.plans(ctx, LocalDate.now(), 7).isEmpty())
        assertNull(RunWindows.plan(ctx, LocalDate.now()))
        assertFalse(RunWindows.needsHourly(ctx))
    }

    @Test fun rwOfflineFetchFailsEachSourceWithoutThrowing() {
        RwStore.setLocation(ctx, 40.0, -105.0, "Test place")
        val rep = WeatherApi.fetchAll(ctx)      // Net.offline: every source fails, nothing is thrown
        assertFalse(rep.anyOk)
        assertTrue(rep.failed.isNotEmpty())
        assertEquals("no Rainbow key, so no Rainbow calls", 0, RwStore.callsThisMonth(ctx))
    }

    @Test fun rwNotificationCategories() {
        val N = NotifySettings
        for (k in listOf("rw_tomorrow", "rw_today", "rw_shift")) assertTrue("$k on by default", N.isOn(ctx, N.cat(k)))
        assertEquals(20 * 60, N.time(ctx, N.cat("rw_tomorrow")))
        assertTrue("tomorrow's window wakes the app at its time", N.wakeTimes(ctx).any { it.hour == 20 && it.minute == 0 })
        N.setOn(ctx, N.cat("rw_shift"), false)
        assertFalse(N.isOn(ctx, N.cat("rw_shift")))
    }

    // ---- the whole chain on the made-up forecast

    @Test fun rwFixtureForecastMakesSevenPlans() {
        WxFixture.seed(ctx)
        val today = LocalDate.now()
        val plans = RunWindows.plans(ctx, today, 7)
        assertEquals(7, plans.size)
        for (p in plans) {
            assertFalse("${p.date} has a forecast", p.noData)
            assertNotNull("${p.date} has a window", p.window)
            assertTrue(p.rainbowPresent)
            assertTrue(p.summary.lines().isNotEmpty())
        }
        val wet = plans[2]            // fixture day 2 has heavy rain 14:00-17:59
        assertTrue("the wet afternoon is avoided: ${wet.window}", wet.window!!.maxRain <= 0.5)
        assertNull("fresh data is not labelled stale", RunWindows.asOf(ctx))
        RwStore.putCache(ctx, "om", WxFixture.omJson(), System.currentTimeMillis() - 5 * 3600_000L)
        RwStore.putCache(ctx, "rainbow", WxFixture.rainbowJson(), System.currentTimeMillis() - 5 * 3600_000L)
        RwStore.putCache(ctx, "nws", WxFixture.nwsGridJson(), System.currentTimeMillis() - 5 * 3600_000L)
        RwStore.putCache(ctx, "nowcast", WxFixture.nowcastJson(), System.currentTimeMillis() - 5 * 3600_000L)
        RwStore.putCache(ctx, "aqi", WxFixture.aqiJson(), System.currentTimeMillis() - 5 * 3600_000L)
        assertTrue(RunWindows.asOf(ctx)!!.startsWith("as of "))
        assertNotNull("old data still shows rather than blanking", RunWindows.plan(ctx, today))
    }

    // ================================================================ Run windows, part 2

    // ---- location change prompt

    @Test fun locDistanceAndMoved() {
        val boulder = 40.015 to -105.27
        val denver = LocRules.distanceKm(boulder.first, boulder.second, 39.74, -104.99)
        assertTrue("Denver is about 38 km away: $denver", denver in 34.0..42.0)
        assertTrue(LocRules.moved(denver))
        val louisville = LocRules.distanceKm(boulder.first, boulder.second, 39.98, -105.13)
        assertTrue("Louisville is about 13 km away: $louisville", louisville in 10.0..16.0)
        assertFalse(LocRules.moved(louisville))
        assertEquals(0.0, LocRules.distanceKm(10.0, 20.0, 10.0, 20.0), 1e-9)
        assertEquals(111.2, LocRules.distanceKm(0.0, 0.0, 1.0, 0.0), 0.3)
        assertFalse("exactly 25 km is not moved", LocRules.moved(25.0))
        assertTrue(LocRules.moved(25.01))
        assertEquals(40.01, LocRules.round2(40.0149), 1e-9)
        assertEquals("40.01, -105.27", LocRules.latLon(40.0149, -105.2701))
    }

    @Test fun locDismissAndDue() {
        val today = LocalDate.of(2026, 11, 26)
        assertEquals(today.plusDays(1), LocRules.dismissUntil(today, 1))
        assertEquals(today.plusDays(7), LocRules.dismissUntil(today, 7))
        assertTrue(LocRules.isDismissed(today, today.plusDays(1)))
        assertFalse("on the date itself the prompt is back", LocRules.isDismissed(today, today))
        assertFalse(LocRules.isDismissed(today, null))
        val d = AskMode.DAILY; val z = AskMode.ZONE_ONLY
        assertTrue("never checked", LocRules.isDue(today, null, null, d, false))
        assertFalse("already checked today", LocRules.isDue(today, today, null, d, false))
        assertTrue("checked yesterday", LocRules.isDue(today, today.minusDays(1), null, d, false))
        assertFalse("dismissed", LocRules.isDue(today, null, today.plusDays(3), d, false))
        assertFalse("dismissed beats a zone change", LocRules.isDue(today, null, today.plusDays(3), d, true))
        assertTrue("dismissal over", LocRules.isDue(today, null, today, d, false))
        assertTrue("zone change is due at once", LocRules.isDue(today, today, null, d, true))
        assertTrue("also in zone-only mode", LocRules.isDue(today, today, null, z, true))
        assertFalse("zone-only without a change", LocRules.isDue(today, null, null, z, false))
        assertFalse("never", LocRules.isDue(today, null, null, AskMode.NEVER, true))
        assertFalse(LocRules.zoneChanged(null, "Europe/London"))
        assertFalse(LocRules.zoneChanged("Europe/London", "Europe/London"))
        assertTrue(LocRules.zoneChanged("Europe/London", "America/Denver"))
        assertEquals(AskMode.DAILY, AskMode.of(null))
        assertEquals(AskMode.NEVER, AskMode.of("never"))
    }

    @Test fun locStateStored() {
        assertEquals(AskMode.DAILY, LocState.mode(ctx))
        LocState.setMode(ctx, AskMode.ZONE_ONLY)
        assertEquals(AskMode.ZONE_ONLY, LocState.mode(ctx))
        assertNull(LocState.dismissUntil(ctx))
        LocState.setDismissUntil(ctx, LocalDate.of(2026, 12, 1))
        assertEquals(LocalDate.of(2026, 12, 1), LocState.dismissUntil(ctx))
        assertFalse("first look just records the zone", LocState.zoneChanged(ctx))
        RwStore.putStr(ctx, "rw_loc_zone", "Pacific/Auckland")
        assertTrue(LocState.zoneChanged(ctx))
        LocState.markChecked(ctx)
        assertFalse(LocState.zoneChanged(ctx))
        RwStore.setLocation(ctx, 40.0149, -105.2749, "Boulder")
        assertEquals(40.01, RwStore.load(ctx).lat!!, 1e-9)
        assertEquals(-105.27, RwStore.load(ctx).lon!!, 1e-9)
    }

    // ---- self-scoring

    @Test fun weightedMedian() {
        assertEquals(25.0, Weather.wMedian(listOf(10.0 to 1.0, 20.0 to 1.0, 30.0 to 1.0, 40.0 to 1.0))!!, 1e-9)
        assertEquals(90.0, Weather.wMedian(listOf(10.0 to 1.0, 20.0 to 2.0, 90.0 to 7.0))!!, 1e-9)
        assertEquals("exactly half the weight: midpoint", 15.0, Weather.wMedian(listOf(10.0 to 2.0, 20.0 to 1.0, 30.0 to 1.0))!!, 1e-9)
        assertEquals(7.0, Weather.wMedian(listOf(7.0 to 0.3))!!, 1e-9)
        assertNull(Weather.wMedian(emptyList()))
    }

    private fun archiveWith(n: Int = 12, hour0: Long = 1000): Scoring.Archive {
        val a = Scoring.Archive()
        for (i in 0 until n) {
            val h = hour0 + i
            a.fc[h] = hashMapOf(
                "nws" to Scoring.Call(90, -1), "gfs" to Scoring.Call(20, 0), "ecmwf" to Scoring.Call(80, 5), Scoring.ALL to Scoring.Call(85, 0),
            )
            Scoring.addObs(a, Scoring.Obs(h, 1, if (i < 4) 0 else -1, "user", h * 3600))
        }
        return a
    }

    @Test fun scoringAccuracy() {
        val a = archiveWith()
        assertEquals(100, Scoring.cloudAcc(a, "nws", 0).pct)
        assertEquals(0, Scoring.cloudAcc(a, "gfs", 0).pct)
        assertEquals(12, Scoring.cloudAcc(a, "ecmwf", 0).total)
        assertEquals("only calls with a forecast count", 0, Scoring.cloudAcc(a, "hrrr", 0).total)
        assertNull(Scoring.cloudAcc(a, "hrrr", 0).pct)
        assertEquals("observations before the window are ignored", 0, Scoring.cloudAcc(a, "nws", 5000).total)
        // wet calls: only the 4 taps that said "not raining" carry a wet answer; gfs/ecmwf/all said dry (0) / 0.05 mm (5)
        assertEquals(4, Scoring.wetAcc(a, "gfs", 0).total)
        assertEquals(100, Scoring.wetAcc(a, "gfs", 0).pct)
        assertEquals(0, Scoring.wetAcc(a, "nws", 0).total)
        val rained = Scoring.Archive(hashMapOf(7L to hashMapOf("gfs" to Scoring.Call(50, 0))), arrayListOf(Scoring.Obs(7, 1, 1, "user", 0)))
        assertEquals("forecast dry, it rained", 0, Scoring.wetAcc(rained, "gfs", 0).pct)
        // a tap and a tile reading for the same hour are separate observations; the same source replaces itself
        Scoring.addObs(a, Scoring.Obs(1000, 0, 0, "user", 1))
        assertEquals(12, a.obs.size)
        Scoring.addObs(a, Scoring.Obs(1000, 1, -1, "tile", 2))
        assertEquals(13, a.obs.size)
    }

    @Test fun scoringWeights() {
        val a = archiveWith()
        val w = Scoring.weights(Scoring.pooled(a, 0))
        assertTrue("accurate nws outweighs gfs: $w", w.getValue("nws") > w.getValue("gfs"))
        assertEquals("scaled to average 1", 1.0, w.values.average(), 1e-9)
        assertTrue(w.values.all { it > 0 })
        assertFalse("the combined pseudo-source gets no weight", Scoring.ALL in w)
        // default: equal weights until at least two sources have 10 observations
        assertTrue(Scoring.weights(Scoring.pooled(archiveWith(5), 0)).isEmpty())
        assertTrue(Scoring.weights(mapOf("nws" to Scoring.Acc(12, 12), "gfs" to Scoring.Acc(1, 3))).isEmpty())
        // a source with too few checks gets the average
        val m = Scoring.weights(mapOf("nws" to Scoring.Acc(12, 12), "gfs" to Scoring.Acc(6, 12), "hrrr" to Scoring.Acc(1, 2)))
        assertEquals(1.0, m.values.average(), 1e-9)
        val raw = (m.getValue("nws") + m.getValue("gfs")) / 2
        assertEquals(raw, m.getValue("hrrr"), 1e-9)
    }

    @Test fun weightsChangeTheCombinedCloud() {
        val t = rwT(RW_DATE, 12)
        val src = mapOf(
            "nws" to listOf(Weather.Hour(t, cloud = 90.0)),
            "gfs" to listOf(Weather.Hour(t, cloud = 20.0)),
            "ecmwf" to listOf(Weather.Hour(t, cloud = 20.0)),
        )
        assertEquals("equal weights: ordinary median", 20.0, Weather.combine(src).single().cloud!!, 1e-9)
        val w = mapOf("nws" to 5.0, "gfs" to 1.0, "ecmwf" to 1.0)
        assertEquals("trusting NWS", 90.0, Weather.combine(src, weights = w).single().cloud!!, 1e-9)
    }

    @Test fun scoringLineAndRecord() {
        val a = archiveWith()
        val now = 1012 * 3600L
        val line = Scoring.line(a, now)!!
        assertTrue(line, line.startsWith("Cloud calls right 100% (last 30 days)"))
        assertTrue(line, line.endsWith("best: NWS"))
        assertNull(Scoring.line(Scoring.Archive(), now))
        val few = Scoring.Archive()
        Scoring.addObs(few, Scoring.Obs(1010, 1, -1, "user", 0))
        assertTrue(Scoring.line(few, now)!!.contains("1 observation so far"))
        // record(): hours under 2 h ahead keep their earlier call; later ones are stored
        val rec = Scoring.Archive()
        val hrs = listOf(Weather.Hour(now + 3600, cloud = 40.0, rainMm = 0.1), Weather.Hour(now + 3 * 3600, cloud = 70.0, rainMm = 1.234))
        Scoring.record(rec, mapOf("nws" to hrs), emptyList(), now)
        assertNull(rec.fc[Scoring.hourOf(now + 3600)])
        assertEquals(Scoring.Call(70, 123), rec.fc[Scoring.hourOf(now + 3 * 3600)]!!["nws"])
        // prune drops what is older than 60 days
        rec.fc[-1000L] = hashMapOf("nws" to Scoring.Call(1, 1))
        Scoring.addObs(rec, Scoring.Obs(-1000L, 1, 1, "user", 0))
        Scoring.prune(rec, now)
        assertNull(rec.fc[-1000L]); assertTrue(rec.obs.isEmpty())
        assertNotNull("recent entries stay", rec.fc[Scoring.hourOf(now + 3 * 3600)])
        // JSON round trip
        val back = Scoring.decode(Scoring.encode(archiveWith()))
        assertEquals(12, back.obs.size)
        assertEquals(Scoring.Call(90, -1), back.fc[1003L]!!["nws"])
        assertTrue(Scoring.decode("not json").obs.isEmpty())
    }

    @Test fun scoringFileAndTaps() {
        Scoring.recordUser(ctx, "sunny")
        Scoring.recordUser(ctx, "rain")
        val a = Scoring.load(ctx)
        assertEquals("a second tap in the same hour replaces the first", 1, a.obs.size)
        assertEquals(1, a.obs[0].wet)                       // rain
        assertEquals(1, a.obs[0].cloudy)                    // rain implies cloud
        assertNotNull(Scoring.accuracyLine(ctx))
    }

    // ---- Rainbow clouds tile heuristic

    @Test fun cloudTilePixels() {
        val clear = 0x00FFFFFF
        val thick = 0xE6FFFFFF.toInt()                      // white, alpha 230
        val thin = 0x40FFFFFF                               // white, alpha 64
        assertEquals(0.0, CloudTile.fraction(clear), 1e-9)
        assertEquals(230 / 255.0, CloudTile.fraction(thick), 1e-9)
        assertEquals(1.0, CloudTile.fraction(0xFFFFFFFF.toInt()), 1e-9)
        assertEquals("opaque black is clear sky", 0.0, CloudTile.fraction(0xFF000000.toInt()), 1e-9)
        assertEquals("true", CloudTile.cloudy(IntArray(25) { thick })?.toString())
        assertEquals("false", CloudTile.cloudy(IntArray(25) { clear })?.toString())
        assertEquals("false", CloudTile.cloudy(IntArray(25) { thin })?.toString())
        assertEquals("true", CloudTile.cloudy(IntArray(25) { 0xFFFFFFFF.toInt() })?.toString())
        assertEquals("false", CloudTile.cloudy(IntArray(25) { 0xFF000000.toInt() })?.toString())
        // a mix is judged on the average: 12 thick + 13 clear = 0.43 → clear; 18 thick + 7 clear = 0.65 → cloudy
        assertEquals("false", CloudTile.cloudy(IntArray(25) { if (it < 12) thick else clear })?.toString())
        assertEquals("true", CloudTile.cloudy(IntArray(25) { if (it < 18) thick else clear })?.toString())
        assertNull(CloudTile.cloudy(IntArray(0)))
    }

    @Test fun cloudTileMathAndSnapshotId() {
        val o = CloudTile.tileFor(0.0, 0.0, 1)
        assertEquals(1, o.x); assertEquals(1, o.y); assertEquals(0.0, o.fx, 1e-6); assertEquals(0.0, o.fy, 1e-6)
        val b = CloudTile.tileFor(40.015, -105.27, 7)
        assertEquals(26, b.x); assertEquals(48, b.y)
        assertTrue(b.fx in 0.0..1.0 && b.fy in 0.0..1.0)
        assertEquals("1795640400", CloudTile.parseSnapshotId("1795640400\n"))
        assertEquals("1700000000", CloudTile.parseSnapshotId("""{"snapshot": 1700000000}"""))
        assertEquals("123", CloudTile.parseSnapshotId("""{"snapshot": "123"}"""))
        assertEquals("111", CloudTile.parseSnapshotId("[111, 222]"))
        assertNull(CloudTile.parseSnapshotId("oops"))
        assertNull(CloudTile.parseSnapshotId("""{"error": "no"}"""))
    }

    // ---- split runs

    private fun wetExcept(date: LocalDate, dryHours: Set<Int>) = rwDay { h ->
        if (h in dryHours) rwHour(date, h, rain = 0.0) else rwHour(date, h, rain = 2.0, pct = 80.0)
    }

    @Test fun splitRunFound() {
        val date = RW_DATE
        val run = rwRun(Weather.RunType.EASY, 70)
        val hours = wetExcept(date, setOf(7, 17))
        val sp = Weather.findSplit(hours, date, run, listOf(rwSun(date)), rwS, null, UTC)!!
        assertEquals(70, sp.minA + sp.minB)
        assertTrue(sp.minA >= Weather.SPLIT_MIN && sp.minB >= Weather.SPLIT_MIN)
        assertEquals(rwT(date, 7), sp.first.start)
        assertEquals(rwT(date, 17), sp.second.start)
        assertTrue(Weather.acceptable(sp.first, rwS) && Weather.acceptable(sp.second, rwS))
        val label = Weather.splitLabel(sp, UTC)
        assertTrue(label, label.startsWith("Split: ") && label.contains("07:00") && label.contains("17:00"))
        // the day plan carries it into the summary
        val plan = rwPlan(date, hours, run = run)
        assertNotNull(plan.split)
        assertTrue(plan.summary.suggestion.startsWith("Split: "))
        assertTrue(plan.summary.short.startsWith("Split: "))
    }

    @Test fun splitRunNotNeeded() {
        val date = RW_DATE
        val run = rwRun(Weather.RunType.EASY, 70)
        val dry = rwDay { rwHour(date, it) }
        assertNull("a single window works", Weather.findSplit(dry, date, run, listOf(rwSun(date)), rwS, null, UTC))
        assertNull(rwPlan(date, dry, run = run).split)
        val allWet = wetExcept(date, emptySet())
        assertNull("no good window at all", Weather.findSplit(allWet, date, run, listOf(rwSun(date)), rwS, null, UTC))
        val gaps = wetExcept(date, setOf(7, 17))
        assertNull("nothing planned", Weather.findSplit(gaps, date, run.copy(planned = false), listOf(rwSun(date)), rwS, null, UTC))
        assertNull("too short to split", Weather.findSplit(gaps, date, rwRun(Weather.RunType.EASY, 40), listOf(rwSun(date)), rwS, null, UTC))
        // windows that touch (a dry hour split in two) are not "two windows": they need an hour between them
        val oneGap = wetExcept(date, setOf(7, 8))
        val s2 = Weather.findSplit(oneGap, date, rwRun(Weather.RunType.EASY, 90), listOf(rwSun(date)), rwS, null, UTC)
        assertNull(s2)
    }

    @Test fun splitRunSkipsWindowsAlreadyStarted() {
        val date = RW_DATE
        val run = rwRun(Weather.RunType.EASY, 70)
        val gaps = wetExcept(date, setOf(7, 17))
        val noon = rwT(date, 12)
        assertNull("the morning slot has gone", Weather.findSplit(gaps, date, run, listOf(rwSun(date)), rwS, noon, UTC))
    }

    // ---- swap with another day

    private fun rwWorkout(name: String, date: LocalDate, id: Long?, status: WorkoutStatus = WorkoutStatus.PLANNED) =
        Workout(name, "Run", 3600, 50, 10000.0, status, date.toString(), eventId = id, startLocal = "${date}T07:00:00")

    private fun swapPlans(wet: Set<Int>, week: List<Workout>): List<Weather.DayPlan> = (0..2).map { i ->
        val d = RW_DATE.plusDays(i.toLong())
        val hours = if (i in wet) rwDay { rwHour(d, it, rain = 2.0, pct = 90.0) } else rwDay { rwHour(d, it) }
        Weather.plan(d, hours, emptyList(), listOf(rwSun(d)), Weather.runOf(week, d.toString()), rwS, null, UTC)
    }

    @Test fun swapSuggested() {
        val d0 = RW_DATE; val d1 = d0.plusDays(1)
        val week = listOf(rwWorkout("Long run 26 km", d0, 101), rwWorkout("Easy + strides", d1, 102))
        val plans = swapPlans(setOf(0, 2), week)
        val s = Weather.swapSuggestions(plans, week, d0)
        val sp = s.getValue(d0)
        assertEquals(101L, sp.a.eventId); assertEquals(d1, sp.aTo)
        assertEquals(102L, sp.b!!.eventId); assertEquals(d0, sp.bTo)
        val text = Weather.swapText(sp)
        assertTrue(text, text.startsWith("Move 'Long run 26 km' from ") && text.contains("and 'Easy + strides' from ") && text.endsWith("?"))
        assertNull("tomorrow is fine, nothing to swap", s[d1])
        // a day with no run: only the bad day's session moves
        val alone = listOf(rwWorkout("Long run 26 km", d0, 101))
        val s1 = Weather.swapSuggestions(swapPlans(setOf(0, 2), alone), alone, d0).getValue(d0)
        assertNull(s1.b); assertNull(s1.bTo); assertEquals(d1, s1.aTo)
        assertTrue(Weather.swapText(s1).let { it.startsWith("Move 'Long run 26 km' from ") && !it.contains(" and ") && it.endsWith("?") })
    }

    @Test fun swapNotSuggested() {
        val d0 = RW_DATE; val d1 = d0.plusDays(1)
        val week = listOf(rwWorkout("Long run 26 km", d0, 101), rwWorkout("Easy + strides", d1, 102))
        assertTrue("dry today: nothing to fix", Weather.swapSuggestions(swapPlans(emptySet(), week), week, d0).isEmpty())
        assertTrue("every day is wet: nothing clearly better", Weather.swapSuggestions(swapPlans(setOf(0, 1, 2), week), week, d0).isEmpty())
        val noIds = listOf(rwWorkout("Long run 26 km", d0, null), rwWorkout("Easy + strides", d1, 102))
        assertTrue("can't move a session without its event id", Weather.swapSuggestions(swapPlans(setOf(0, 2), noIds), noIds, d0).isEmpty())
        val otherNoId = listOf(rwWorkout("Long run 26 km", d0, 101), rwWorkout("Easy + strides", d1, null))
        assertTrue(Weather.swapSuggestions(swapPlans(setOf(0, 2), otherNoId), otherNoId, d0).isEmpty())
        val done = listOf(rwWorkout("Long run 26 km", d0, 101, WorkoutStatus.DONE), rwWorkout("Easy + strides", d1, 102))
        assertTrue("a run that is already done stays", Weather.swapSuggestions(swapPlans(setOf(0, 2), done), done, d0).isEmpty())
        val nothing = emptyList<Workout>()
        assertTrue("no run planned: nothing to move", Weather.swapSuggestions(swapPlans(setOf(0, 2), nothing), nothing, d0).isEmpty())
        // only today and tomorrow get an offer: the third day is bad, but it's two days out
        val week3 = week + rwWorkout("Hills", d0.plusDays(2), 103)
        val plans3 = swapPlans(setOf(2), week3)
        assertTrue(Weather.swapSuggestions(plans3, week3, d0).isEmpty())
    }

    // ---- watch closely

    @Test fun watchAutoRule() {
        assertEquals("narrow window ⚠", Watch.autoReason(true, false, false, true, 5, 5))
        assertEquals("sources disagree (3/5 agree)", Watch.autoReason(true, false, false, false, 3, 5))
        assertNull("70% is not below 70%", Watch.autoReason(true, false, false, false, 7, 10))
        assertNotNull(Watch.autoReason(true, false, false, false, 69, 100))
        assertNull(Watch.autoReason(true, false, false, false, 4, 5))
        assertNull("one source can't disagree", Watch.autoReason(true, false, false, false, 0, 1))
        assertNull("not a run day", Watch.autoReason(false, false, false, true, 1, 5))
        assertNull("already run", Watch.autoReason(true, true, false, true, 1, 5))
        assertNull("window has passed", Watch.autoReason(true, false, true, true, 1, 5))
    }

    @Test fun watchToggleAndEvaluate() {
        assertFalse(Watch.isOn(ctx))
        assertTrue(Watch.toggle(ctx))
        assertTrue(Watch.isOn(ctx)); assertEquals("you asked", Watch.reason(ctx))
        assertFalse(Watch.toggle(ctx))
        assertFalse(Watch.isOn(ctx)); assertNull(Watch.reason(ctx))
        Watch.evaluate(ctx)                                  // no location: nothing happens
        assertFalse(Watch.isOn(ctx))
    }

    @Test fun shiftMessageInWatchMode() {
        val old = Weather.Sig(rwT(RW_DATE, 7), rwT(RW_DATE, 8), emptySet())
        val moved10 = Weather.Sig(rwT(RW_DATE, 7, 10), rwT(RW_DATE, 8, 10), emptySet())
        assertNull("normally 10 minutes is ignored", Weather.shiftMessage(old, moved10, UTC))
        assertNotNull("watching: any change counts", Weather.shiftMessage(old, moved10, UTC, any = true))
        assertNull(Weather.shiftMessage(old, old, UTC, any = true))
    }

    // ---- whole chain: snapshot with event ids, split and swap on the made-up forecast

    @Test fun snapshotKeepsEventIds() {
        Fixture.seed(ctx)
        val snap = Prefs.snapshot(ctx)!!
        assertTrue(snap.week.filter { it.status == WorkoutStatus.PLANNED }.all { it.eventId != null && it.startLocal != null })
        assertEquals(snap.ahead.size, snap.ahead.count { it.eventId != null })
        val back = Snapshot.fromJson(snap.toJson())!!
        assertEquals(snap.ahead, back.ahead)
        assertEquals(snap.week, back.week)
    }

    @Test fun fixtureSplitAndSwap() {
        Fixture.seed(ctx)
        val tomorrow = LocalDate.now().plusDays(1)
        try {
            // tomorrow: rain all day except 07:00 and 18:00 (low UV there)
            WxFixture.tweak = { day, hour, p ->
                if (day != 1) p
                else if (hour in listOf(7, 18)) WxFixture.P(p.temp, p.cloud, 0.5, 0.0, p.dew, p.wind)
                else if (hour in 5..21) WxFixture.P(p.temp, p.cloud, 0.5, 2.0, p.dew, p.wind)
                else p
            }
            WxFixture.seed(ctx)
            val snap = Prefs.snapshot(ctx)!!
            val run = Workout("Easy + strides", "Run", 4200, 55, 12000.0, WorkoutStatus.PLANNED, tomorrow.toString(), eventId = 777L, startLocal = "${tomorrow}T07:00:00")
            Prefs.saveSnapshot(ctx, snap.copy(
                week = snap.week.filter { it.date != tomorrow.toString() },
                ahead = snap.ahead.filter { it.date != tomorrow.toString() } + run), 400)
            val plan = RunWindows.plan(ctx, tomorrow)!!
            assertTrue("a planned 70 min run", plan.run.planned && plan.run.minutes == 70)
            assertNotNull("tomorrow is split: ${plan.summary.suggestion}", plan.split)
            assertTrue(plan.window!!.wet)
            val swap = RunWindows.swapFor(ctx, tomorrow)
            assertNotNull("tomorrow is wet and a later day is clearly better", swap)
            assertEquals(777L, swap!!.a.eventId)
            assertTrue(swap.aTo.isAfter(LocalDate.now().minusDays(1)) && swap.aTo != tomorrow)
        } finally { WxFixture.tweak = null }
    }

    // ================================================================ missed-session swaps

    private val swMon: LocalDate = LocalDate.of(2026, 11, 23)   // a Monday
    private fun swS(id: Long, name: String, kind: Swaps.Kind, min: Int) = Swaps.Session(id, name, kind, min)
    private fun swWeek(mon: LocalDate, base: Long, kinds: List<Swaps.Kind>): List<Swaps.Slot> = kinds.mapIndexed { i, k ->
        Swaps.Slot(mon.plusDays(i.toLong()), listOf(swS(base + i, "${k.name} $i", k, when (k) { Swaps.Kind.LONG -> 120; Swaps.Kind.EASY -> 50; else -> 70 })))
    }
    private val swPattern = listOf(Swaps.Kind.EASY, Swaps.Kind.WORKOUT, Swaps.Kind.EASY, Swaps.Kind.WORKOUT, Swaps.Kind.EASY, Swaps.Kind.WORKOUT, Swaps.Kind.LONG)
    private fun swSlots(): List<Swaps.Slot> = swWeek(swMon, 0, swPattern) + swWeek(swMon.plusDays(7), 10, swPattern)
    private val swTwo: (LocalDate) -> Swaps.DayWin = { Swaps.DayWin(2, listOf(7 * 60, 16 * 60)) }
    private val swOne: (LocalDate) -> Swaps.DayWin = { Swaps.DayWin(1) }
    private fun swMoves(o: Swaps.Option) = o.changes.filterIsInstance<Swaps.Change.Move>().associate { it.eventId to it.to }
    private fun swDoubles(o: Swaps.Option) = o.changes.filterIsInstance<Swaps.Change.AddDouble>().map { it.date }

    @Test fun swapsTuesdayWorkoutMissed() {
        assertEquals(java.time.DayOfWeek.MONDAY, swMon.dayOfWeek)
        val slots = swSlots()
        val tue = swMon.plusDays(1)
        val missed = slots[1].sessions[0]
        val opts = Swaps.options(missed, tue, slots, swTwo, swMon)
        assertTrue(opts.size in 1..3)
        val o = opts.first { it.id == "shift" }
        val m = swMoves(o)
        assertEquals("A to Wed", swMon.plusDays(2), m[1L])
        assertEquals("easy pushed to Thu", swMon.plusDays(3), m[2L])
        assertEquals("B to Fri", swMon.plusDays(4), m[3L])
        assertEquals("easy pushed to Sat", swMon.plusDays(5), m[4L])
        assertNull("long stays on Sunday", m[6L])
        val leave = o.changes.filterIsInstance<Swaps.Change.LeaveInPlace>()
        assertEquals("C is left in place", listOf(5L), leave.map { it.eventId })
        assertEquals(swMon.plusDays(5), leave[0].date)
        assertEquals("doubles on Wed and Thu", setOf(swMon.plusDays(2), swMon.plusDays(3)), swDoubles(o).toSet())
        assertTrue(o.changes.filterIsInstance<Swaps.Change.AddDouble>().all { it.minutes == 30 })
        assertTrue("nothing past this Sunday", o.changes.all { !it.date.isAfter(swMon.plusDays(6)) })
        val sat = o.rows.first { it.date == swMon.plusDays(5) }
        assertTrue(sat.after.any { it.kind == Swaps.Kind.EASY && it.tag != Swaps.Tag.DOUBLE })
        assertTrue(sat.after.any { it.tag == Swaps.Tag.SKIP })
        val wed = o.rows.first { it.date == swMon.plusDays(2) }
        assertTrue(wed.after.any { it.tag == Swaps.Tag.DOUBLE && it.startMin == 16 * 60 })
        for (op in opts) assertEquals(op.id, emptyList<Pair<LocalDate, LocalDate>>(), Swaps.adjacencyViolations(op, missed, tue, slots))
    }

    @Test fun swapsSaturdaySpillsIntoNextWeek() {
        val slots = swSlots()
        val sat = swMon.plusDays(5)
        val missed = slots[5].sessions[0]
        // only next Tuesday has two good windows
        val win: (LocalDate) -> Swaps.DayWin = { if (it == swMon.plusDays(8)) Swaps.DayWin(2, listOf(7 * 60, 17 * 60)) else Swaps.DayWin(1) }
        val opts = Swaps.options(missed, sat, slots, win, sat)
        val o = opts.first { it.id == "shift" }
        val m = swMoves(o)
        assertEquals("workout to Sun", swMon.plusDays(6), m[5L])
        assertEquals("long to Mon", swMon.plusDays(7), m[6L])
        assertEquals("easy to Tue", swMon.plusDays(8), m[10L])
        assertEquals("workout to Wed", swMon.plusDays(9), m[11L])
        assertNull("next Sunday's long run stays", m[16L])
        assertTrue("double on Tue", swMon.plusDays(8) in swDoubles(o))
        val sunNext = swMon.plusDays(13)
        assertTrue("nothing past next Sunday", o.changes.all { !it.date.isAfter(sunNext) })
        assertEquals(sunNext, Swaps.horizon(sat))
        for (op in opts) assertEquals(op.id, emptyList<Pair<LocalDate, LocalDate>>(), Swaps.adjacencyViolations(op, missed, sat, slots))
    }

    @Test fun swapsThursdayWorkoutNoReshuffle() {
        val slots = swSlots()
        val thu = swMon.plusDays(3)
        val tue = swMon.plusDays(1)
        // marked on Tuesday as a planned miss for Thursday
        val opts = Swaps.options(slots[3].sessions[0], thu, slots, swTwo, tue)
        assertEquals(1, opts.size)
        val o = opts[0]
        assertTrue("nothing moves", o.changes.none { it is Swaps.Change.Move })
        assertEquals(listOf(swMon.plusDays(4)), swDoubles(o))
        assertEquals(listOf(3L), o.changes.filterIsInstance<Swaps.Change.LeaveInPlace>().map { it.eventId })
    }

    @Test fun swapsLongRunMissed() {
        val slots = swSlots()
        val sun = swMon.plusDays(6)
        val missed = slots[6].sessions[0]
        val opts = Swaps.options(missed, sun, slots, swTwo, sun)
        val o = opts.first { it.id == "shift" }
        val m = swMoves(o)
        assertEquals("long to Monday", swMon.plusDays(7), m[6L])
        assertEquals("the day after is easy", swMon.plusDays(8), m[10L])
        assertEquals("workout moves on", swMon.plusDays(9), m[11L])
        assertTrue(o.changes.all { !it.date.isAfter(Swaps.horizon(sun)) })
        for (op in opts) assertEquals(op.id, emptyList<Pair<LocalDate, LocalDate>>(), Swaps.adjacencyViolations(op, missed, sun, slots))
    }

    @Test fun swapsTtIsAddedToNextSaturdayAndRaceNeverMoves() {
        val tt = swS(50, "5K TT", Swaps.Kind.TT, 40)
        val base = swWeek(swMon, 0, swPattern)
        val slots = base.map { if (it.date == swMon.plusDays(1)) Swaps.Slot(it.date, listOf(tt)) else it }
        val o = Swaps.options(tt, swMon.plusDays(1), slots, swTwo, swMon).single()
        val copy = o.changes.filterIsInstance<Swaps.Change.AddCopy>().single()
        assertEquals(swMon.plusDays(5), copy.date)
        assertTrue("that Saturday's workout stays", o.changes.none { it is Swaps.Change.Move || it is Swaps.Change.LeaveInPlace })
        assertTrue(o.rows.first { it.date == copy.date }.after.size == 2)
        // a Saturday that already has a TT or race is skipped
        val busy = slots.map { if (it.date == swMon.plusDays(5)) Swaps.Slot(it.date, listOf(swS(51, "Race", Swaps.Kind.RACE, 60))) else it }
        assertTrue(Swaps.options(tt, swMon.plusDays(1), busy, swTwo, swMon).isEmpty())
        // races never move
        assertTrue(Swaps.options(swS(51, "Race", Swaps.Kind.RACE, 60), swMon.plusDays(5), busy, swTwo, swMon).isEmpty())
    }

    @Test fun swapsEasyMissedDoublePlacement() {
        val slots = swSlots()
        // Mon easy missed. Wed and Fri have two windows; Sunday (long) too but never gets a double
        val win: (LocalDate) -> Swaps.DayWin = {
            val dow = it.dayOfWeek.value
            if (dow == 3 || dow == 5 || dow == 7) Swaps.DayWin(2, listOf(7 * 60, 17 * 60)) else Swaps.DayWin(1)
        }
        val opts = Swaps.options(slots[0].sessions[0], swMon, slots, win, swMon)
        assertEquals(swMon.plusDays(2), (opts[0].changes.single() as Swaps.Change.AddDouble).date)
        assertEquals(30, (opts[0].changes.single() as Swaps.Change.AddDouble).minutes)
        assertTrue(opts.all { o -> swDoubles(o).none { it.dayOfWeek == java.time.DayOfWeek.SUNDAY } })
        assertTrue("alternatives use other eligible days", opts.size >= 2 && swDoubles(opts[1]).single() == swMon.plusDays(4))
        // nobody has two windows: falls back to the next easy day
        val one = Swaps.options(slots[0].sessions[0], swMon, slots, swOne, swMon)
        assertEquals(listOf(swMon.plusDays(2)), swDoubles(one.single()))
        assertEquals(1, Swaps.options(slots[0].sessions[0], swMon, slots, swOne, swMon).size)
    }

    @Test fun swapsHorizonByWeekday() {
        for (i in 0..4) assertEquals(swMon.plusDays(6), Swaps.horizon(swMon.plusDays(i.toLong())))
        assertEquals(swMon.plusDays(13), Swaps.horizon(swMon.plusDays(5)))
        assertEquals(swMon.plusDays(13), Swaps.horizon(swMon.plusDays(6)))
    }

    @Test fun swapsDoubleIsSixtyPercentOfMedianEasyRounded() {
        fun slotsOf(vararg mins: Int) = mins.mapIndexed { i, m -> Swaps.Slot(swMon.plusDays(i.toLong()), listOf(swS(i.toLong(), "e", Swaps.Kind.EASY, m))) }
        assertEquals(30, Swaps.doubleMinutes(slotsOf(50), swMon))
        assertEquals(30, Swaps.doubleMinutes(slotsOf(40, 50, 60), swMon))
        assertEquals(30, Swaps.doubleMinutes(slotsOf(45, 55), swMon))
        assertEquals(35, Swaps.doubleMinutes(slotsOf(55), swMon))
        assertEquals(25, Swaps.doubleMinutes(slotsOf(45), swMon))
        assertEquals("no easy run planned", 30, Swaps.doubleMinutes(emptyList(), swMon))
    }

    @Test fun swapsNoAdjacencyViolationsAndStayInHorizon() {
        val wins = listOf(swTwo, swOne, { d: LocalDate -> if (d.dayOfWeek.value % 2 == 0) Swaps.DayWin(0) else Swaps.DayWin(2, listOf(7 * 60, 16 * 60)) })
        val patterns = listOf(
            swPattern,
            listOf(Swaps.Kind.EASY, Swaps.Kind.WORKOUT, Swaps.Kind.EASY, Swaps.Kind.EASY, Swaps.Kind.WORKOUT, Swaps.Kind.EASY, Swaps.Kind.LONG),
            listOf(Swaps.Kind.WORKOUT, Swaps.Kind.EASY, Swaps.Kind.WORKOUT, Swaps.Kind.EASY, Swaps.Kind.EASY, Swaps.Kind.WORKOUT, Swaps.Kind.LONG),
        )
        for (pat in patterns) {
            val slots = swWeek(swMon, 0, pat) + swWeek(swMon.plusDays(7), 10, pat)
            for (i in 0..6) {
                val missed = slots[i].sessions[0]
                val d0 = slots[i].date
                for (w in wins) for (op in Swaps.options(missed, d0, slots, w, d0)) {
                    assertTrue("$pat day $i ${op.id}", op.changes.all { !it.date.isAfter(Swaps.horizon(d0)) })
                    assertEquals("$pat day $i ${op.id}", emptyList<Pair<LocalDate, LocalDate>>(), Swaps.adjacencyViolations(op, missed, d0, slots))
                    assertTrue(op.moves == op.changes.count { it is Swaps.Change.Move })
                }
            }
        }
    }

    @Test fun swapsAvoidDaysWithoutGoodWindow() {
        val slots = swSlots()
        val tue = swMon.plusDays(1)
        val wedBad: (LocalDate) -> Swaps.DayWin = { if (it == swMon.plusDays(2)) Swaps.DayWin(0) else Swaps.DayWin(2, listOf(7 * 60, 16 * 60)) }
        val opts = Swaps.options(slots[1].sessions[0], tue, slots, wedBad, swMon)
        val best = opts.first()
        assertTrue("the best option doesn't put the workout on the bad day", swMoves(best)[1L] != swMon.plusDays(2))
    }

    @Test fun swapsDetectionRules() {
        val d0 = RW_DATE
        val wet = swapPlans(setOf(1), listOf(rwWorkout("Easy run", d0.plusDays(1), 7)))[1]
        assertEquals("heavy rain all day", Swaps.atRisk(wet, rwS))
        val dry = swapPlans(emptySet(), listOf(rwWorkout("Easy run", d0.plusDays(1), 7)))[1]
        assertNull(Swaps.atRisk(dry, rwS))
        val done = swapPlans(setOf(1), listOf(rwWorkout("Easy run", d0.plusDays(1), 7, WorkoutStatus.DONE)))[1]
        assertNull("a run that is done is never at risk", Swaps.atRisk(done, rwS))
        val none = swapPlans(setOf(1), emptyList())[1]
        assertNull("no run planned", Swaps.atRisk(none, rwS))
        assertFalse(Swaps.isMissedNow(dry, 20 * 60))
        assertTrue("after 21:00 a planned run that isn't done counts as missed", Swaps.isMissedNow(dry, 21 * 60 + 1))
        assertFalse(Swaps.isMissedNow(done, 22 * 60))
        // good windows: a dry day has two, a wet day none
        val hours = rwDay { rwHour(d0, it) }
        val dryPlan = rwPlan(d0, hours)
        val gw = Swaps.goodWindows(dryPlan, rwS, listOf(rwSun(d0)), UTC, 30, null)
        assertEquals(2, gw.count); assertEquals(2, gw.times.size); assertTrue(gw.times[0] < gw.times[1])
        val wetPlan = rwPlan(d0, rwDay { rwHour(d0, it, rain = 2.0, pct = 90.0) })
        assertEquals(0, Swaps.goodWindows(wetPlan, rwS, listOf(rwSun(d0)), UTC, 30, null).count)
        assertEquals("no forecast counts as one window", 1, Swaps.goodWindows(rwPlan(d0, emptyList()), rwS, emptyList(), UTC, 30, null).count)
    }
}
