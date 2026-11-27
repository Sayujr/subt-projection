package dev.subt.trainingwidget

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * "Fitness & fatigue: am I on track?" Opened from Today's call. The rules live in [FitnessPlan]; suggestions are written to
 * intervals.icu only after you tap Apply and confirm, and Undo puts them back for the rest of the day.
 */
class FitnessActivity : Activity() {
    companion object {
        fun open(ctx: Context) {
            ctx.startActivity(Intent(ctx, FitnessActivity::class.java).apply { if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        }

        private const val TEXT = 0xFFF2F4F8.toInt()
        private const val DIM = 0xFF9AA3B2.toInt()
        private const val FAINT = 0xFF6B7386.toInt()
        private const val GOOD = 0xFF3DD68C.toInt()
        private const val LINK = 0xFF4EA1FF.toInt()
    }

    private lateinit var col: LinearLayout
    private var busy = false
    private var status: String? = null

    private val d get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false; fitsSystemWindows = true }
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(24)) }
        sv.addView(col)
        setContentView(sv)
        render()
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "snapshot") runOnUiThread { if (!isFinishing) render() }
    }

    override fun onResume() {
        super.onResume()
        getSharedPreferences("training_widget", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onPause() {
        getSharedPreferences("training_widget", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onPause()
    }

    // ---------------------------------------------------------------- building blocks

    private fun text(parent: LinearLayout, t: CharSequence, sp: Float = 14f, color: Int = TEXT, bold: Boolean = false, top: Int = 0): TextView {
        val tv = TextView(this).apply {
            text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(top), 0, 0)
        }
        parent.addView(tv)
        return tv
    }

    private fun card(): LinearLayout {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.tile_bg); setPadding(dp(14), dp(12), dp(14), dp(14)) }
        col.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        return c
    }

    private fun header(title: String, sub: String) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(10)) }
        r.addView(TextView(this).apply { text = "‹"; textSize = 28f; setTextColor(TEXT); setPadding(dp(4), 0, dp(14), 0); setOnClickListener { finish() } })
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(TextView(this).apply { text = title; textSize = 19f; setTextColor(TEXT); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); maxLines = 2 })
        if (sub.isNotEmpty()) t.addView(TextView(this).apply { text = sub; textSize = 13f; setTextColor(DIM) })
        r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(r)
    }

    private fun row(parent: LinearLayout, label: String, value: String, color: Int = TEXT) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(5), 0, dp(1)) }
        r.addView(TextView(this).apply { text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(DIM) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(TextView(this).apply { text = value; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(color); gravity = Gravity.END },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f))
        parent.addView(r)
    }

    private fun button(parent: LinearLayout, t: String, enabled: Boolean = true, onClick: () -> Unit) {
        parent.addView(TextView(this).apply {
            text = t; textSize = 14.5f; setTextColor(if (enabled) TEXT else FAINT); gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.input_bg)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            if (enabled) setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    private fun signed(v: Double): String { val r = v.roundToInt(); return if (r > 0) "+$r" else if (r < 0) "−${-r}" else "0" }
    private fun signed1(v: Double): String = (if (v >= 0) "+" else "−") + String.format(Locale.US, "%.1f", Math.abs(v))

    // ---------------------------------------------------------------- the page

    private fun render() {
        col.removeAllViews()
        header("Fitness & fatigue", "Are you on track?")
        val s = Prefs.snapshot(this)
        val call = s?.let { FitnessPlan.call(it) }
        if (s == null || call == null) {
            val c = card()
            text(c, "Not enough data yet", 16f, TEXT, bold = true)
            text(c, "This page needs about a week of Fitness and Fatigue from intervals.icu. Refresh from Today once you are connected.", 13.5f, DIM, top = 4)
            return
        }
        verdict(s, call)
        shortTerm(s, call)
        longTerm(s)
        notes(s, call)
        talk(s)
    }

    private fun verdict(s: Snapshot, call: FitnessPlan.Call) {
        val c = card()
        BandViews.heading(this, c, "VERDICT",
            "Fitness (CTL) gained over the last 7 days against a build of +3 to +5 a week: on track from +3 to +7, slightly behind from +1 to +3, " +
                "behind under +1, building too fast above +7. In a taper (A race 14 days, B 10, C 5) Fitness is meant to ease, so the call is whether it eases as planned.")
        text(c, call.status.label, 26f, call.status.color, bold = true, top = 2).apply { letterSpacing = 0.02f }
        text(c, call.sentence, 14.5f, DIM, top = 2)
    }

    private fun shortTerm(s: Snapshot, call: FitnessPlan.Call) {
        val st = FitnessPlan.shortTerm(s) ?: return
        val c = card()
        BandViews.heading(this, c, "SHORT TERM",
            "Load is training stress (the number intervals.icu adds up). Needed is the weekly load that lifts Fitness by +4 from last Sunday, using " +
                "CTL = yesterday's CTL + (load − yesterday's CTL) ÷ 42 with the same load each day. Fatigue ÷ Fitness is green under 1.2, amber to 1.4, red above. " +
                "Sunday and next week come from your planned sessions.")
        row(c, "Load done this week", "${st.done}")
        row(c, "Planned, still to do", "${st.planned}")
        st.needed?.let { n ->
            row(c, "Needed for +${FitnessPlan.BUILD_TARGET.roundToInt()} Fitness", "$n")
            val gap = n - st.done - st.planned
            row(c, "Plan vs needed", if (gap > 0) "about $gap short" else "covered", if (gap > 0) 0xFFFFC53D.toInt() else GOOD)
            val parts = listOf(st.done.toDouble(), st.planned.toDouble(), max(gap, 0).toDouble())
            if (parts.sum() > 0) c.addView(BandViews.stripView(this, parts, listOf(GOOD, LINK, 0xFF3A4150.toInt()), resources.displayMetrics.widthPixels - dp(52)))
        }
        row(c, "Fatigue ÷ Fitness", String.format(Locale.US, "%.2f  %s", call.ratio, when { call.ratio > FitnessPlan.RATIO_RED -> "high"; call.ratio > FitnessPlan.RATIO_AMBER -> "elevated"; else -> "fine" }), FitnessPlan.ratioColor(call.ratio))
        row(c, "Form", "${signed(st.form)} today" + (st.formSunday?.let { " · ${signed(it)} Sunday (plan)" } ?: ""))
        row(c, "Next week (plan)", if (st.nextCtl != null && st.nextAtl != null) "Fitness ${signed1(st.nextCtl)} · Fatigue ${signed1(st.nextAtl)}" else "not planned far enough")
    }

    private fun longTerm(s: Snapshot) {
        val c = card()
        val band = FitnessPlan.band(s)
        val race = band?.race
        BandViews.heading(this, c, if (race != null) "LONG TERM · TOWARDS ${race.name.take(20).uppercase(Locale.getDefault())}" else "LONG TERM · NEXT ${FitnessPlan.DEFAULT_WEEKS} WEEKS",
            "Blue is your Fitness for the last ${FitnessPlan.HISTORY_WEEKS} weeks, dashed is where your planned sessions take it. The green band starts ${FitnessPlan.HISTORY_WEEKS} weeks ago and rises " +
                "+${FitnessPlan.BUILD_LO.roundToInt()} to +${FitnessPlan.BUILD_HI.roundToInt()} a week, capped at the race-day target (today's Fitness + ${FitnessPlan.BUILD_TARGET.roundToInt()} a week until the taper starts), then eases through the taper. " +
                "The goal is the band's slope: a week counts as below it when Fitness gained less than +${FitnessPlan.BUILD_LO.roundToInt()}. Touch and drag the chart to read values.")
        if (band == null) { text(c, "Not enough history for the chart yet.", 13.5f, DIM, top = 4); return }
        val w = resources.displayMetrics.widthPixels - dp(52)
        val bmp = FitnessChart.render(s, band, w, dp(190), d)
        val iv = ImageView(this).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_XY }
        c.addView(iv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bmp.height).apply { topMargin = dp(6) })
        ChartHits.get(bmp)?.let { Scrub.attach(iv, bmp, it) }
        text(c, "━ Fitness   ┅ plan   ▒ target band", 11.5f, FAINT, top = 4)
        if (race != null) {
            val days = ChronoUnit.DAYS.between(FitnessPlan.todayOf(s), runCatching { LocalDate.parse(race.date) }.getOrDefault(band.end))
            text(c, "${race.name.take(28)} in $days days · target Fitness about ${band.cap.roundToInt()} at the taper" +
                (race.projCtl?.let { " · plan reaches ${it.roundToInt()}" } ?: ""), 13f, DIM, top = 4)
        }
        val reading = FitnessPlan.reading(s)
        if (reading.isNotEmpty()) text(c, reading + ".", 14f, TEXT, top = 6)
    }

    private fun notes(s: Snapshot, call: FitnessPlan.Call) {
        val c = card()
        BandViews.heading(this, c, "COACH'S NOTES",
            "Rule-based, smallest change first, at most 3 a week. Behind: a longer long run, a 60% double on an easy or workout day (never the long-run day), longer easy runs. " +
                "Building too fast or Fatigue over 140% of Fitness: shorter easy runs and long run, skip a double, keep SubT controlled. " +
                "Apply writes to intervals.icu only after you confirm; Undo puts it back today. Your own events are never deleted.")
        FitnessFlow.undoRec(this)?.let { rec ->
            text(c, "Applied today: ${rec.label}", 13.5f, GOOD, top = 4)
            button(c, "Undo", enabled = !busy) { doUndo() }
        }
        status?.let { text(c, it, 13f, DIM, top = 6) }
        val list = FitnessPlan.suggestions(s)
        if (list.isEmpty()) {
            text(c, when (call.status) {
                FitnessPlan.Status.ON_TRACK -> "You are on track: nothing to change."
                FitnessPlan.Status.TAPER_OK -> "The taper is going as planned: nothing to add."
                else -> "No changes fit the sessions planned in intervals.icu right now."
            }, 14f, DIM, top = 6)
            return
        }
        val canWrite = Prefs.credentials(this) != null
        for (wk in 0..1) {
            val ws = list.filter { it.week == wk }
            if (ws.isEmpty()) continue
            text(c, if (wk == 0) "REST OF THIS WEEK" else "NEXT WEEK", 11f, DIM, top = 12).apply { letterSpacing = 0.08f }
            for (sg in ws) {
                text(c, sg.title, 15f, TEXT, bold = true, top = 8)
                text(c, sg.detail, 13f, DIM)
                if (sg.applicable) button(c, "Apply in intervals.icu", enabled = canWrite && !busy) { confirm(sg) }
            }
        }
    }

    private fun talk(s: Snapshot) {
        val r = FitnessPlan.readout(s) ?: return
        val c = card()
        text(c, "Talk it through ›", 15f, LINK, top = 2).setOnClickListener { CoachSheet.talk(this, Coach.prompt(this, r, r.questions.first())) }
        text(c, "Opens the Claude app with these numbers. Nothing is sent until you do.", 12f, FAINT, top = 2)
    }

    // ---------------------------------------------------------------- apply and undo

    private fun confirm(sg: FitnessPlan.Suggestion) {
        val body = sg.changes.joinToString("\n") { "• " + FitnessPlan.describe(it) }
        AlertDialog.Builder(this)
            .setTitle("Apply in intervals.icu?")
            .setMessage("These changes will be made in your intervals.icu calendar:\n\n$body\n\nNo events are deleted. You can undo today.")
            .setPositiveButton("Apply") { _, _ -> doApply(sg) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doApply(sg: FitnessPlan.Suggestion) {
        busy = true; status = "Applying…"
        render()
        Thread {
            val msg = try { FitnessFlow.apply(applicationContext, sg) }
            catch (e: Exception) { "Couldn't apply: ${e.message ?: e.javaClass.simpleName}. Anything already done was put back." }
            runOnUiThread {
                busy = false; status = msg
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (!isFinishing) render()
            }
        }.start()
    }

    private fun doUndo() {
        busy = true; status = "Undoing…"
        render()
        Thread {
            val msg = try { FitnessFlow.undo(applicationContext) } catch (e: Exception) { "Couldn't undo: ${e.message ?: e.javaClass.simpleName}" }
            runOnUiThread {
                busy = false; status = msg
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (!isFinishing) render()
            }
        }.start()
    }
}
