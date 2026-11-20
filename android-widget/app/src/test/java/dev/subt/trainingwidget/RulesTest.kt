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
    private fun seedSeries(days: List<Day>) {
        Prefs.saveSnapshot(ctx, Snapshot(System.currentTimeMillis(), LocalDate.now().toString(), days, emptyList()), days.size)
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
