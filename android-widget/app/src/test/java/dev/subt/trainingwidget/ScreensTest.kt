package dev.subt.trainingwidget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Renders every app screen (and each chip view) plus every widget from sample data to PNGs in
 * build/screens, so layout problems show up before a build reaches the phone. Problems rendering
 * a screen are written to build/screens/errors.txt instead of failing the build.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class ScreensTest {
    private lateinit var ctx: Context
    private val outDir = File(System.getProperty("screens.dir") ?: "build/screens").apply { mkdirs() }
    private val errors = StringBuilder()

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        Fixture.seed(ctx)
    }

    private fun settle() {
        repeat(4) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(120) }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun save(name: String, bmp: Bitmap) {
        FileOutputStream(File(outDir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun draw(v: View, w: Int, h: Int): Bitmap {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
    }

    /** The whole screen as the phone shows it, plus the full scrolling content as one tall image. */
    private fun capture(name: String, root: View) {
        val w = root.width.takeIf { it > 0 } ?: (412 * 2); val h = root.height.takeIf { it > 0 } ?: (915 * 2)
        save(name, draw(root, w, h))
        val scroll = find(root) { it is ScrollView }?.let { it as ScrollView }
        val child = scroll?.getChildAt(0) ?: return
        val cw = scroll.width.takeIf { it > 0 } ?: w
        child.measure(View.MeasureSpec.makeMeasureSpec(cw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val ch = child.measuredHeight.coerceIn(1, 16000)
        child.layout(0, 0, cw, ch)
        val bmp = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(0xFF0F1218.toInt()); child.draw(c)
        save("${name}_full", bmp)
    }

    private fun find(v: View, pred: (View) -> Boolean): View? {
        if (pred(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), pred)?.let { return it }
        return null
    }

    private fun safely(name: String, block: () -> Unit) {
        println("screens: $name …")
        try { block() } catch (t: Throwable) { errors.append("$name: ${t.javaClass.simpleName}: ${t.message}\n${t.stackTrace.take(6).joinToString("\n") { "    at $it" }}\n\n") }
    }

    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    @Test fun renderAll() {
        val tabs = listOf("HOME", "WEEK", "STREAKS", "SUBT", "POWER", "RACE", "RECOVERY")
        for (t in tabs) safely("app_$t") {
            val ctl = Robolectric.buildActivity(MainActivity::class.java, Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, t)).setup()
            val act = ctl.get()
            settle()
            val root = act.window.decorView
            capture("app_${t.lowercase()}", root)
            // Each chip (sub-view) on this screen, except arrows / dialogs
            val chips = act.findViewById<ViewGroup>(R.id.chips)
            val labels = (0 until chips.childCount).mapNotNull { (chips.getChildAt(it) as? TextView)?.text?.toString() }
                .filter { it !in setOf("‹", "›", "Now", "Custom…") && !it.endsWith("…") }
            for ((i, label) in labels.withIndex()) {
                if (i == 0 && t != "WEEK" && t != "HOME") continue
                if (t == "HOME" && i > 1) break
                safely("app_${t}_$label") {
                    val cg = act.findViewById<ViewGroup>(R.id.chips)
                    val tv = (0 until cg.childCount).map { cg.getChildAt(it) }.firstOrNull { (it as? TextView)?.text?.toString() == label }
                    tv?.performClick(); settle()
                    capture("app_${t.lowercase()}_${slug(label)}", root)
                }
            }
            ctl.pause().stop().destroy()
        }
        // Scrub the Today chart end to end (into the plan days), then lift the finger
        safely("scrub") {
            val ctl = Robolectric.buildActivity(MainActivity::class.java, Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "HOME")).setup()
            settle()
            val act = ctl.get()
            val iv = act.window.decorView.findViewById<View>(R.id.trend_chart) ?: error("no chart on Today")
            val w = iv.width.toFloat(); val y = iv.height / 2f
            check(w > 0) { "chart not laid out" }
            fun ev(a: Int, x: Float) = android.view.MotionEvent.obtain(0, 0, a, x, y, 0).also { iv.dispatchTouchEvent(it); it.recycle() }
            ev(android.view.MotionEvent.ACTION_DOWN, 2f)
            var x = 2f
            while (x < w - 1) { ev(android.view.MotionEvent.ACTION_MOVE, x); x += w / 60 }
            ev(android.view.MotionEvent.ACTION_MOVE, w + 50)   // past the edge
            capture("app_home_scrubbing", act.window.decorView)
            ev(android.view.MotionEvent.ACTION_UP, w - 1)
            settle()
            capture("app_home_after_scrub", act.window.decorView)
            ctl.pause().stop().destroy()
        }
        // Scrub the SubT pace chart and hold mid-way, so the screenshot shows the label
        safely("scrub_subt") {
            val ctl = Robolectric.buildActivity(MainActivity::class.java, Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "SUBT")).setup()
            settle()
            val act = ctl.get()
            val iv = act.window.decorView.findViewById<View>(R.id.subt_chart) ?: error("no SubT chart")
            check(iv.width > 0) { "SubT chart not laid out" }
            fun ev(a: Int, x: Float) = android.view.MotionEvent.obtain(0, 0, a, x, iv.height / 2f, 0).also { iv.dispatchTouchEvent(it); it.recycle() }
            ev(android.view.MotionEvent.ACTION_DOWN, 5f)
            ev(android.view.MotionEvent.ACTION_MOVE, iv.width * 0.6f)
            capture("app_subt_scrubbing", act.window.decorView)
            ev(android.view.MotionEvent.ACTION_UP, iv.width * 0.6f)
            ctl.pause().stop().destroy()
        }
        // Tap a day column on the Week chart: the category split panel shows under it
        safely("week_day") {
            val ctl = Robolectric.buildActivity(MainActivity::class.java, Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "WEEK")).setup()
            settle()
            val act = ctl.get()
            val iv = act.window.decorView.findViewById<View>(R.id.week_chart) ?: error("no week chart")
            check(iv.width > 0) { "week chart not laid out" }
            fun ev(a: Int, x: Float) = android.view.MotionEvent.obtain(0, 0, a, x, iv.height / 2f, 0).also { iv.dispatchTouchEvent(it); it.recycle() }
            val x = iv.width * 1.5f / 7
            ev(android.view.MotionEvent.ACTION_DOWN, x); ev(android.view.MotionEvent.ACTION_UP, x)
            settle()
            capture("app_week_day_tapped", act.window.decorView)
            ctl.pause().stop().destroy()
        }
        // Workout detail from a made-up 6'x4 workout, with the first work step opened
        safely("workout") {
            WorkoutDetail.save(ctx, "i999", Fixture.workoutRaw())
            val ctl = Robolectric.buildActivity(WorkoutActivity::class.java, Intent(ctx, WorkoutActivity::class.java).putExtra(WorkoutActivity.EXTRA_ID, "i999")).setup()
            settle()
            capture("workout", ctl.get().window.decorView)
            File(outDir, "example_note.txt").writeText(Prefs.coachNotes(ctx).values.first())
            ctl.pause().stop().destroy()
        }
        // Run windows: the day plan for today and tomorrow (the Today card and the Week list show up in app_home / app_week)
        for ((label, offset) in listOf("today" to 0L, "tomorrow" to 1L, "wet_day" to 2L)) safely("runwindow_$label") {
            val i = Intent(ctx, RunWindowActivity::class.java).putExtra(RunWindowActivity.EXTRA_DATE, Fixture.today.plusDays(offset).toString())
            val ctl = Robolectric.buildActivity(RunWindowActivity::class.java, i).setup()
            settle()
            capture("runwindow_$label", ctl.get().window.decorView)
            ctl.pause().stop().destroy()
        }
        // The Today card and the Week list on their own, as plain views
        safely("runwindow_card_and_list") {
            val act = Robolectric.buildActivity(MainActivity::class.java, Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "HOME")).setup().get()
            val col = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.VERTICAL; setBackgroundColor(0xFF0F1218.toInt()) }
            val screens = AppScreens(act, 392)
            screens.runWindowCard(col)
            screens.runWindowsWeek(col)
            val w = (392 * ctx.resources.displayMetrics.density).toInt()
            col.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val h = col.measuredHeight.coerceIn(1, 8000)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            col.layout(0, 0, w, h)
            Canvas(bmp).also { it.drawColor(0xFF0F1218.toInt()); col.draw(it) }
            save("runwindow_card_and_list", bmp)
        }
        // Run windows part 2: tomorrow wet except two dry hours (split run + swap button), today with the accuracy line and "outside now" buttons
        safely("runwindow_split_swap") {
            val tomorrow = Fixture.today.plusDays(1)
            val a = Scoring.Archive()
            val h0 = Scoring.hourOf(System.currentTimeMillis() / 1000)
            for (i in 1..40) {
                val h = h0 - i
                a.fc[h] = hashMapOf(
                    "nws" to Scoring.Call(if (i % 6 == 0) 20 else 90, -1), "gfs" to Scoring.Call(if (i % 3 == 0) 20 else 90, -1),
                    "rainbow" to Scoring.Call(90, 0), "ecmwf" to Scoring.Call(if (i % 4 == 0) 20 else 90, 0),
                    Scoring.ALL to Scoring.Call(if (i % 6 == 0) 20 else 90, 0),
                )
                Scoring.addObs(a, Scoring.Obs(h, 1, if (i % 2 == 0) 0 else -1, "user", h * 3600))
            }
            Scoring.save(ctx, a)
            try {
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
                for ((label, day) in listOf("split_swap" to tomorrow, "today_scored" to Fixture.today)) safely("runwindow_$label") {
                    val i = Intent(ctx, RunWindowActivity::class.java).putExtra(RunWindowActivity.EXTRA_DATE, day.toString())
                    val ctl = Robolectric.buildActivity(RunWindowActivity::class.java, i).setup()
                    settle()
                    capture("runwindow_$label", ctl.get().window.decorView)
                    ctl.pause().stop().destroy()
                }
                safely("runwindow_card_part2") {
                    val act = Robolectric.buildActivity(MainActivity::class.java, Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "HOME")).setup().get()
                    val col = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.VERTICAL; setBackgroundColor(0xFF0F1218.toInt()) }
                    AppScreens(act, 392).runWindowCard(col)
                    val w = (392 * ctx.resources.displayMetrics.density).toInt()
                    col.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    val h = col.measuredHeight.coerceIn(1, 4000)
                    col.layout(0, 0, w, h)
                    save("runwindow_card_part2", Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { Canvas(it).apply { drawColor(0xFF0F1218.toInt()); col.draw(this) } })
                }
            } finally { WxFixture.tweak = null; WxFixture.seed(ctx) }
        }
        // Run windows: a winter-style evening where daylight is wet and only the dark evening hours are dry (night window)
        safely("runwindow_night_window") {
            try {
                WxFixture.tweak = { day, hour, p ->
                    if (day != 1) p
                    else if (hour in 6..19) WxFixture.P(p.temp, 90.0, 0.3, 1.5, p.dew, p.wind)
                    else p
                }
                WxFixture.seed(ctx)
                val i = Intent(ctx, RunWindowActivity::class.java).putExtra(RunWindowActivity.EXTRA_DATE, Fixture.today.plusDays(1).toString())
                val ctl = Robolectric.buildActivity(RunWindowActivity::class.java, i).setup()
                settle()
                capture("runwindow_night_window", ctl.get().window.decorView)
                ctl.pause().stop().destroy()
            } finally { WxFixture.tweak = null; WxFixture.seed(ctx) }
        }
        // Missed-session swaps: the "Fit in a missed session" page from a planned week (workout today marked as skipped, and an at-risk day)
        safely("swap_activity") {
            val t = Fixture.today
            fun w(name: String, d: Int, secs: Int, id: Long) =
                Workout(name, "Run", secs, 50, 10000.0, WorkoutStatus.PLANNED, t.plusDays(d.toLong()).toString(), eventId = id, startLocal = "${t.plusDays(d.toLong())}T07:00:00")
            val week = listOf(
                w("3x10' SubT", 0, 4200, 901), w("Easy run", 1, 3000, 902), w("3x10' SubT", 2, 4200, 903), w("Easy run", 3, 3000, 904),
                w("3x10' SubT", 4, 4200, 905), w("Easy run", 5, 3000, 906), w("Long run 26 km", 6, 7800, 907),
                w("Easy run", 7, 3000, 908), w("3x10' SubT", 8, 4200, 909), w("Easy run", 9, 3000, 910),
            )
            val snap = Prefs.snapshot(ctx)!!
            Prefs.saveSnapshot(ctx, snap.copy(week = week, ahead = emptyList(), workouts = emptyList()), 400)
            for ((label, trig) in listOf("swap_activity" to Swaps.Trigger.MANUAL, "swap_activity_risk" to Swaps.Trigger.AT_RISK, "swap_activity_missed" to Swaps.Trigger.MISSED_PASSED)) safely(label) {
                val i = SwapActivity.intent(ctx, t, 901L, trig, "heavy rain all day")
                val ctl = Robolectric.buildActivity(SwapActivity::class.java, i).setup()
                settle()
                capture(label, ctl.get().window.decorView)
                ctl.pause().stop().destroy()
            }
            safely("swap_card_and_banner") {
                val act = Robolectric.buildActivity(MainActivity::class.java, Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "HOME")).setup().get()
                val col = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.VERTICAL; setBackgroundColor(0xFF0F1218.toInt()) }
                AppScreens(act, 392).runWindowCard(col)
                val wpx = (392 * ctx.resources.displayMetrics.density).toInt()
                col.measure(View.MeasureSpec.makeMeasureSpec(wpx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                val h = col.measuredHeight.coerceIn(1, 4000)
                col.layout(0, 0, wpx, h)
                save("swap_card_and_banner", Bitmap.createBitmap(wpx, h, Bitmap.Config.ARGB_8888).also { Canvas(it).apply { drawColor(0xFF0F1218.toInt()); col.draw(this) } })
            }
            Fixture.seed(ctx)
        }
        // The "Change in location?" dialog body (built as a plain view, no dialog, so it can be captured)
        safely("location_dialog") {
            val act = Robolectric.buildActivity(MainActivity::class.java, Intent(ctx, MainActivity::class.java)).setup().get()
            val opts = listOf(RwDialogs.Option("Use current location") {}, RwDialogs.Option("Keep Boulder, CO, US") {}) + RwDialogs.dismissOptions(act)
            val v = RwDialogs.content(act, LocPrompt.movedBody("Denver, CO", 38.0, "Boulder, CO, US"), opts) {}
            val w = (340 * ctx.resources.displayMetrics.density).toInt()
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val h = v.measuredHeight.coerceIn(1, 4000)
            v.layout(0, 0, w, h)
            save("location_dialog", Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { Canvas(it).apply { drawColor(0xFF2A2F3A.toInt()); v.draw(this) } })
        }
        // The Coach sheet for the made-up workout, built as a plain view (no dialog) so it can be captured
        safely("coach_sheet") {
            val act = Robolectric.buildActivity(WorkoutActivity::class.java, Intent(ctx, WorkoutActivity::class.java).putExtra(WorkoutActivity.EXTRA_ID, "i999")).setup().get()
            val r = Coach.workout(WorkoutDetail.fromRaw(Fixture.workoutRaw(), null), null)
            val sheet = CoachSheet.content(act, r)
            val w = (390 * ctx.resources.displayMetrics.density).toInt()
            sheet.view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val h = sheet.view.measuredHeight.coerceIn(1, 4000)
            save("coach_sheet", draw(sheet.view, w, h))
            File(outDir, "coach_prompt.txt").writeText(Coach.prompt(ctx, r, CoachSheet.question(sheet)))
        }
        safely("settings") {
            val ctl = Robolectric.buildActivity(SetupActivity::class.java).setup()
            settle()
            capture("settings", ctl.get().window.decorView)
        }
        // Widgets at typical phone sizes (dp)
        val d = ctx.resources.displayMetrics.density
        fun widget(name: String, wDp: Int, hDp: Int, rv: () -> RemoteViews) = safely("widget_$name") {
            val parent = FrameLayout(ctx)
            val v = rv().apply(ctx, parent)
            val bmp = draw(v, (wDp * d).toInt(), (hDp * d).toInt())
            val bg = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            Canvas(bg).apply { drawColor(0xFF2A3140.toInt()); drawBitmap(bmp, 0f, 0f, null) }
            save("widget_$name", bg)
        }
        widget("full_4x3", 350, 290) { WidgetRenderer.buildFull(ctx, 1, 350, 290) }
        widget("full_4x4", 350, 400) { WidgetRenderer.buildFull(ctx, 2, 350, 400) }
        widget("trend_4x2", 350, 180) { WidgetRenderer.buildTrend(ctx, 3, 350, 180) }
        widget("compact_4x2", 350, 180) { WidgetRenderer.buildCompact(ctx, 4, 350, 180) }
        widget("workouts_4x1", 350, 90) { WidgetRenderer.buildWorkouts(ctx, 5) }
        widget("week_4x2", 350, 180) { ExtraWidgets.buildWeek(ctx, 350, 180) }
        widget("race_2x2", 170, 180) { ExtraWidgets.buildRace(ctx, 170, 180) }
        widget("recovery_4x2", 350, 180) { ExtraWidgets.buildRecovery(ctx, 6, 350, 180) }
        widget("subt_2x2", 170, 180) { ExtraWidgets.buildSubt(ctx, 170, 180) }
        widget("runwindows_4x2", 350, 180) { RwWidgets.build(ctx, 350, 180) }
        widget("runwindows_4x2_tall", 350, 220) { RwWidgets.build(ctx, 350, 220) }

        File(outDir, "errors.txt").writeText(errors.toString().ifEmpty { "No rendering errors.\n" })
    }
}
