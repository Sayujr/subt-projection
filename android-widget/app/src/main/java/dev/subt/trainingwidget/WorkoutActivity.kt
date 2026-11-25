package dev.subt.trainingwidget

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * One workout in detail: summary, the average of all work steps, power / pace / HR over time, and
 * every step against its planned power target. Tap a work step for its own numbers.
 */
class WorkoutActivity : Activity() {
    companion object {
        const val EXTRA_ID = "activity_id"
        const val EXTRA_NAME = "activity_name"
        fun open(ctx: Context, id: String, name: String? = null) =
            ctx.startActivity(Intent(ctx, WorkoutActivity::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_NAME, name))

        private const val TEXT = 0xFFF2F4F8.toInt()
        private const val DIM = 0xFF9AA3B2.toInt()
        private const val FAINT = 0xFF6B7386.toInt()
        private const val GOOD = 0xFF3DD68C.toInt()
        private const val WARN = 0xFFFFC53D.toInt()
        private const val BAD = 0xFFFF7A7A.toInt()
        private const val TILE = 0xFF222733.toInt()
        private const val TEST_C = 0xFFC17BFF.toInt()
        private const val UNDER_C = 0xFF6B7386.toInt()
        private const val IN_C = 0xFF3DD68C.toInt()
        private const val OVER_C = 0xFF4EA1FF.toInt()
        const val POWER = 0xFFFFB02E.toInt()
        const val PACE = 0xFF4EA1FF.toInt()
        const val HEART = 0xFFFF7A90.toInt()
    }

    private lateinit var col: LinearLayout
    private var id = ""
    private val opened = mutableSetOf<Int>()
    private var loading = false

    private val d get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent?.getStringExtra(EXTRA_ID).orEmpty()
        val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false; fitsSystemWindows = true }
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(24)) }
        sv.addView(col)
        setContentView(sv)
        val raw = WorkoutDetail.cached(this, id)
        if (raw != null) show(raw) else load()
    }

    private fun load() {
        val creds = Prefs.credentials(this)
        col.removeAllViews()
        header(intent?.getStringExtra(EXTRA_NAME) ?: "Workout", "")
        if (creds == null || id.isEmpty()) { text(col, "Connect intervals.icu in Settings to see workout details.", 14f, DIM); return }
        if (loading) return
        loading = true
        text(col, "Loading from intervals.icu…", 14f, DIM)
        Thread {
            val r = runCatching { IcuApi(creds).fetchWorkoutRaw(id) }
            runOnUiThread {
                loading = false
                r.onSuccess { WorkoutDetail.save(this, id, it); show(it) }
                    .onFailure { col.removeAllViews(); header(intent?.getStringExtra(EXTRA_NAME) ?: "Workout", ""); text(col, "Couldn't load: ${it.message ?: it.javaClass.simpleName}", 14f, WARN) }
            }
        }.start()
    }

    // ---------------------------------------------------------------- formatting

    private fun w(v: Double?) = v?.let { "${it.roundToInt()} W" } ?: "–"
    private fun bpm(v: Double?) = v?.let { "${it.roundToInt()} bpm" } ?: "–"
    private fun pct(v: Double?) = v?.let { "${(it * 100).roundToInt()}%" } ?: "–"
    private fun dur(sec: Int) = SubtModel.time(sec.toDouble())
    private fun km(m: Double) = String.format(Locale.US, "%.2f km", m / 1000)
    /** Run: pace per km. Ride: km/h. */
    private fun spd(v: Double?, ride: Boolean, unit: Boolean = true): String {
        if (v == null || v <= 0) return "–"
        return if (ride) String.format(Locale.US, "%.1f", v * 3.6) + (if (unit) " km/h" else "")
        else Charts.paceText(1000 / v / 60) + (if (unit) " /km" else "")
    }

    // ---------------------------------------------------------------- building blocks

    private fun text(parent: LinearLayout, t: CharSequence, sp: Float = 14f, color: Int = TEXT, bold: Boolean = false, top: Int = 0): TextView {
        val tv = TextView(this).apply {
            text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(top), 0, 0)
        }
        parent.addView(tv)
        return tv
    }

    private fun label(parent: LinearLayout, t: String, color: Int = DIM) = text(parent, t.uppercase(Locale.getDefault()), 11f, color).apply { letterSpacing = 0.08f }

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
        r.addView(TextView(this).apply { text = "⟳"; textSize = 22f; setTextColor(DIM); setPadding(dp(12), 0, dp(4), 0); setOnClickListener { load() } })
        col.addView(r)
    }

    /** Label on the left; value on the right with "Min · Max" underneath (as in the Stryd metrics list). */
    private fun metric(parent: LinearLayout, name: String, color: Int, value: String, sub: String?) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(9), 0, dp(3)) }
        r.addView(TextView(this).apply { text = "●"; textSize = 10f; setTextColor(color); setPadding(0, 0, dp(10), 0) })
        r.addView(TextView(this).apply { text = name; textSize = 15.5f; setTextColor(TEXT) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        v.addView(TextView(this).apply { text = value; textSize = 18f; setTextColor(TEXT); gravity = Gravity.END; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
        if (sub != null) v.addView(TextView(this).apply { text = sub; textSize = 12f; setTextColor(DIM); gravity = Gravity.END })
        r.addView(v)
        parent.addView(r)
    }

    private fun tiles(parent: LinearLayout, items: List<Pair<String, String>>) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
        for ((k, v) in items) {
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            cell.addView(TextView(this).apply { text = k; textSize = 11.5f; setTextColor(DIM) })
            cell.addView(TextView(this).apply { text = v; textSize = 17f; setTextColor(TEXT); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
            r.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        parent.addView(r)
    }

    private fun image(parent: LinearLayout, bmp: Bitmap, hDp: Int, top: Int = 6): ImageView {
        val iv = ImageView(this).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_XY }
        parent.addView(iv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(hDp)).apply { topMargin = dp(top) })
        return iv
    }

    private fun contentW() = resources.displayMetrics.widthPixels - dp(12 + 12 + 14 + 14)

    /**
     * Target bar: the middle third is the target range (ticks at its ends), the fill runs to where
     * your average sat. Green inside the range, yellow outside.
     */
    private fun targetBar(wPx: Int, position: Double?, vs: Int?, lo: Double?, hi: Double?, labels: Boolean): Bitmap {
        val h = dp(if (labels) 34 else 16)
        val bmp = Bitmap.createBitmap(wPx.coerceAtLeast(10), h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bh = 6f * d; val y = h - bh - 5f * d
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val x0 = bmp.width / 3f; val x1 = bmp.width * 2 / 3f
        // The whole line takes the verdict colour (green = average inside the range, yellow just outside,
        // red more than 3 % outside); it is not a progress fill.
        val mid = if (lo != null && hi != null) (lo + hi) / 2 else null
        val avg = if (lo != null && hi != null && position != null) lo + position * (hi - lo) else null
        val far = mid != null && avg != null && lo != null && hi != null && (avg < lo - 0.03 * mid || avg > hi + 0.03 * mid)
        val lineC = if (position == null) 0xFF3A4150.toInt() else if (vs == 0) GOOD else if (far) BAD else WARN
        p.color = (lineC and 0x00FFFFFF) or 0x66000000
        c.drawRoundRect(RectF(0f, y, bmp.width.toFloat(), y + bh), bh / 2, bh / 2, p)
        // Target range between the ticks, in the full colour
        p.color = lineC
        c.drawRect(x0, y, x1, y + bh, p)
        // Target ticks (thin) and the actual average (a taller, thicker line)
        p.color = 0xFFB8C0CE.toInt(); p.strokeWidth = 1.5f * d
        c.drawLine(x0, y - 3f * d, x0, y + bh + 2f * d, p)
        c.drawLine(x1, y - 3f * d, x1, y + bh + 2f * d, p)
        if (position != null) {
            val mx = (x0 + position * (x1 - x0)).toFloat().coerceIn(3f * d, bmp.width - 3f * d)
            p.color = 0xFF161A22.toInt(); p.strokeWidth = 6f * d
            c.drawLine(mx, y - 5f * d, mx, y + bh + 4f * d, p)
            p.color = TEXT; p.strokeWidth = 3f * d; p.strokeCap = Paint.Cap.ROUND
            c.drawLine(mx, y - 5f * d, mx, y + bh + 4f * d, p)
            p.strokeCap = Paint.Cap.BUTT
        }
        if (labels && lo != null && hi != null) {
            val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DIM; textSize = 11f * d }
            val a = lo.roundToInt().toString(); val b = hi.roundToInt().toString()
            c.drawText(a, x0 - t.measureText(a) / 2, y - 8f * d, t)
            c.drawText(b, x1 - t.measureText(b) / 2, y - 8f * d, t)
        }
        return bmp
    }

    /** Bar / dot colour from the average against the actual range: green inside, yellow within 3 % outside, red further. */
    private fun rangeColor(st: WorkoutDetail.Step): Int {
        val a = st.targetAvg; val lo = st.loW; val hi = st.hiW
        if (a == null || lo == null || hi == null) return 0xFF4A5263.toInt()
        val mid = (lo + hi) / 2
        return if (a >= lo - 0.5 && a <= hi + 0.5) GOOD else if (a < lo - 0.03 * mid || a > hi + 0.03 * mid) BAD else WARN
    }

    /**
     * All steps at a glance: each work / test step is a small gauge (band = target range, dot = where
     * the average landed, low = under, high = over) with its on-target % above and its number below;
     * every other step is a small dot coloured by whether it was in its range.
     */
    private fun stepStrip(det: WorkoutDetail.Detail): Bitmap {
        val colW = 34f * d; val dotW = 8f * d
        val hPx = dp(92)
        val total = det.steps.sumOf { (if (it.isWork || it.isTest) colW else dotW).toDouble() }.toFloat()
        val bmp = Bitmap.createBitmap(maxOf(total.toInt() + dp(4), 10), hPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val top = 20f * d; val bottom = hPx - 18f * d
        val gh = bottom - top
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * d }
        var x = 0f; var workNo = 0; var testNo = 0
        for (st in det.steps) {
            if (st.isWork || st.isTest) {
                val cx = x + colW / 2
                // gauge: track, target band in the middle third
                p.color = 0xFF3A4150.toInt()
                c.drawRoundRect(RectF(cx - 4f * d, top, cx + 4f * d, bottom), 4f * d, 4f * d, p)
                p.color = 0xFF56607A.toInt()
                c.drawRect(cx - 4f * d, top + gh / 3, cx + 4f * d, top + gh * 2 / 3, p)
                st.position?.let { pos ->
                    val y = (top + gh * 2 / 3 - pos * gh / 3).toFloat().coerceIn(top + 4f * d, bottom - 4f * d)
                    p.color = 0xFF161A22.toInt(); c.drawCircle(cx, y, 6.5f * d, p)
                    p.color = rangeColor(st); c.drawCircle(cx, y, 5f * d, p)
                }
                val under = st.under; val inT = st.inT
                val share = if (st.hrTarget) (if (inT != null && under != null) inT + under else null) else st.inOrOver
                share?.let { sh ->
                    val t = "${(sh * 100).roundToInt()}%"
                    small.color = onColor(sh); small.isFakeBoldText = true
                    c.drawText(t, cx - small.measureText(t) / 2, 13f * d, small)
                }
                val lbl = if (st.isTest) "T${++testNo}" else "${++workNo}"
                small.color = if (st.isTest) TEST_C else DIM; small.isFakeBoldText = false
                c.drawText(lbl, cx - small.measureText(lbl) / 2, hPx - 4f * d, small)
                x += colW
            } else {
                p.color = rangeColor(st)
                c.drawCircle(x + dotW / 2, top + gh / 2, 3f * d, p)
                x += dotW
            }
        }
        return bmp
    }

    private fun vsText(avg: Double?, lo: Double?, hi: Double?, unit: String = "W"): Pair<String, Int> {
        if (avg == null || lo == null || hi == null) return "" to DIM
        return when {
            avg < lo - 0.5 -> "${(lo - avg).roundToInt()} $unit under" to WARN
            avg > hi + 0.5 -> "${(avg - hi).roundToInt()} $unit over" to WARN
            else -> "in range" to GOOD
        }
    }

    /** Headline colour for "on or over target": green 85 %+, yellow 70–85 %, red below. */
    private fun onColor(share: Double) = if (share >= 0.85) GOOD else if (share >= 0.70) WARN else BAD
    /** HR colour: red when over 172 for more than 20 % of the step, green when 70 %+ sat in 155–172, else yellow. */
    private fun hrColor(inBand: Double?, over: Double?) = when {
        inBand == null -> DIM
        (over ?: 0.0) > 0.20 -> BAD
        inBand >= 0.70 -> GOOD
        else -> WARN
    }

    private fun spans(vararg parts: Pair<String, Int>): CharSequence {
        val sb = SpannableStringBuilder()
        for ((t, c) in parts) { val s = sb.length; sb.append(t); sb.setSpan(ForegroundColorSpan(c), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        return sb
    }


    // ---------------------------------------------------------------- coach's recap

    private fun coachLevelColor(l: Coach.Level) = when (l) { Coach.Level.GOOD -> GOOD; Coach.Level.WATCH -> WARN; Coach.Level.ACT -> BAD; Coach.Level.INFO -> DIM }

    /** Runs with targets only: the coach's recap from the day's note, or the app's draft until it arrives; plus questions. */
    private fun coachCard(det: WorkoutDetail.Detail, z: Zones.Run?, raw: JSONObject) {
        if (!CoachNotes.eligible(det.type, det.hasTargets)) return
        val note = Prefs.coachNotes(this)[det.date]?.let { CoachNotes.parse(it) }
        val section = note?.workouts?.firstOrNull { it.activityId == det.id }
        val c = card()
        label(c, "Coach's recap", GOOD)
        val recap = section?.recap
        if (recap != null) {
            val lines = recap.split("\n")
            val tv = text(c, lines.take(4).joinToString("\n"), 14f, TEXT, top = 4)
            if (lines.size > 4) {
                var open = false
                val more = text(c, "More", 13f, OVER_C, top = 4)
                more.setOnClickListener {
                    open = !open
                    tv.text = if (open) recap else lines.take(4).joinToString("\n")
                    more.text = if (open) "Less" else "More"
                }
            }
            text(c, "From your coach notes in intervals.icu", 11.5f, FAINT, top = 6)
        } else {
            text(c, "Draft · full recap arrives after the evening coach run", 12f, DIM, top = 2)
            for (l in Coach.workout(det, z).lines) {
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(7), 0, 0) }
                r.addView(TextView(this).apply { text = "●"; setTextColor(coachLevelColor(l.level)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f); setPadding(0, dp(3), dp(8), 0) })
                r.addView(TextView(this).apply { text = l.text; setTextColor(TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                c.addView(r)
            }
        }
        for (q in note?.questions.orEmpty().filter { it.topic == det.name }) {
            text(c, "You: ${q.question}", 13.5f, DIM, top = 10)
            if (q.answer != null) text(c, "Coach: ${q.answer}", 14f, TEXT, top = 3) else text(c, "Coach: (waiting for the evening run)", 13f, FAINT, top = 3)
        }
        val ask = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, 0) }
        val input = android.widget.EditText(this).apply {
            hint = if (Prefs.credentials(this@WorkoutActivity) == null) "Connect intervals.icu to ask" else "Ask about this workout"
            setHintTextColor(FAINT); setTextColor(TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setSingleLine(true); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isEnabled = Prefs.credentials(this@WorkoutActivity) != null
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF262C3A.toInt()); cornerRadius = dp(10).toFloat() }
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        val send = TextView(this).apply {
            text = "➤"; setTextColor(OVER_C); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); gravity = Gravity.CENTER
            setPadding(dp(14), dp(4), dp(6), dp(4))
            setOnClickListener {
                val blocked = CoachSync.blocker(this@WorkoutActivity)
                val q = input.text.toString().trim()
                val creds = Prefs.credentials(this@WorkoutActivity)
                if (blocked != null || creds == null) { android.widget.Toast.makeText(this@WorkoutActivity, blocked, android.widget.Toast.LENGTH_LONG).show(); return@setOnClickListener }
                if (q.isEmpty()) return@setOnClickListener
                val ctxLines = Coach.workout(det, z).lines.joinToString("; ") { it.text }
                isEnabled = false
                Thread {
                    val r = runCatching { CoachSync.sendQuestion(this@WorkoutActivity, creds, det.date, det.name, q, ctxLines) }
                    runOnUiThread {
                        isEnabled = true
                        r.onSuccess { android.widget.Toast.makeText(this@WorkoutActivity, "Sent to your coach notes", android.widget.Toast.LENGTH_SHORT).show(); show(raw) }
                            .onFailure { android.widget.Toast.makeText(this@WorkoutActivity, "Couldn't send: ${it.message ?: it.javaClass.simpleName}", android.widget.Toast.LENGTH_LONG).show() }
                    }
                }.start()
            }
        }
        ask.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ask.addView(send)
        c.addView(ask)
    }

    // ---------------------------------------------------------------- screen

    private fun show(raw: JSONObject) {
        val det = try { WorkoutDetail.fromRaw(raw, Prefs.cpOverride(this) ?: Prefs.snapshot(this)?.runFtp) } catch (e: Exception) {
            col.removeAllViews(); header("Workout", ""); text(col, "Couldn't read this workout: ${e.message}", 14f, WARN); return
        }
        col.removeAllViews()
        val ride = det.isRide
        val dateTxt = runCatching { LocalDate.parse(det.date).format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())) }.getOrDefault(det.date)
        header(det.name, "$dateTxt · ${det.type}")

        val z = if (det.heart.any { it != null }) runCatching { Zones.compute(raw, Prefs.lt1(this).toDouble(), Prefs.cpOverride(this) ?: Prefs.snapshot(this)?.runFtp) }.getOrNull() else null
        coachCard(det, z, raw)

        // Summary
        val s = card()
        label(s, "Summary")
        tiles(s, listOf("Moving time" to dur(det.seconds), "Distance" to km(det.distance), "Load" to (det.load?.toString() ?: "–"), "Elev gain" to (det.elevGain?.let { "${it.roundToInt()} m" } ?: "–")))
        val cpTxt = det.ftp?.let { f -> det.power.avg?.let { "Average ${w(it)} · ${(it / f * 100).roundToInt()}% of CP ${f.roundToInt()} W (your CP that day)" } }
        if (cpTxt != null) text(s, cpTxt, 12.5f, DIM, top = 8)
        if (det.stoppedSec >= 10) text(s, "Min values leave out ${dur(det.stoppedSec)} of walking or stops (slower than 10:00 /km).", 12f, FAINT, top = 4)

        // Average of all work steps: the main takeaway
        det.work?.let { wk ->
            val c = card()
            // On an easy day every planned step counts, so don't call them "work"
            val allSteps = det.steps.all { it.isWork }
            label(c, (if (allSteps) "Planned steps" else "Work steps") + " · average of ${wk.count}", GOOD)
            text(c, "${dur(wk.seconds)}" + (if (allSteps) "" else " of work") + (if (wk.distance > 0) " · ${km(wk.distance)}" else ""), 12.5f, DIM, top = 2)
            metric(c, "Power", POWER, w(wk.power.avg), "Min ${wk.power.min?.roundToInt() ?: "–"} · Max ${wk.power.max?.roundToInt() ?: "–"}")
            metric(c, if (ride) "Speed" else "Pace", PACE, spd(wk.speed.avg, ride), "Min ${spd(wk.speed.min, ride, false)} · Max ${spd(wk.speed.max, ride, false)}")
            metric(c, "Heart rate", HEART, bpm(wk.hr.avg), "Min ${wk.hr.min?.roundToInt() ?: "–"} · Max ${wk.hr.max?.roundToInt() ?: "–"}")
            // An easy run: every planned step, no warm-up or recoveries, and targets well under threshold (or HR targets)
            val ftpD = det.ftp
            val easyRun = allSteps && (wk.hrTarget || (ftpD != null && wk.loW != null && wk.hiW != null && (wk.loW + wk.hiW) / 2 < 0.8 * ftpD))
            if (wk.loW != null && wk.hiW != null) {
                val (vt, vc) = vsText(wk.targetAvg, wk.loW, wk.hiW, if (wk.hrTarget) "bpm" else "W")
                BandViews.heading(this, c, if (wk.hrTarget) "Against HR target" else "Against target",
                    "The big % is how much of the work was at or above the bottom of the planned range (for an HR target, which is a ceiling: at or under the top). Green 85%+, yellow 70–85%, red below. On the bar, the thin ticks mark your target range and the thick white line is your average. The bar's colour is the verdict: green when the average is inside the range, yellow just outside, red more than 3% outside. The HR figure is green when 70%+ of the work was at 155–172, red when more than 20% was over 172.", topDp = 14)
                val avgTxt = if (wk.hrTarget) bpm(wk.targetAvg) else w(wk.targetAvg)
                // An HR target on an easy run is a ceiling: what counts is staying in or under it
                val ceiling = wk.hrTarget
                val share = if (ceiling) (if (wk.inT != null && wk.under != null) wk.inT + wk.under else null) else wk.inOrOver
                // Same layout as a step row: details on the left, the big % on the right, the bar below
                val bandAll = wk.hrB
                val cpTxt2 = if (!wk.hrTarget && ftpD != null) wk.power.avg?.let { "${(it / ftpD * 100).roundToInt()}% of CP" } ?: "" else ""
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
                val left = TextView(this).apply {
                    textSize = 13f
                    text = spans(
                        avgTxt + (if (vt.isEmpty()) "" else " $vt") to vc,
                        "\nin range ${SubtModel.time(wk.inSec)} (${((wk.inT ?: 0.0) * 100).roundToInt()}%)" to DIM,
                        (if (bandAll != null && !wk.hrTarget) "\nHR ${(bandAll.frac * 100).roundToInt()}% in 155–172" else "") to hrColor(bandAll?.frac, wk.hrOver?.frac),
                    )
                }
                row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val right = TextView(this).apply {
                    gravity = Gravity.END
                    val sb = SpannableStringBuilder(share?.let { "${(it * 100).roundToInt()}%" } ?: avgTxt)
                    sb.setSpan(ForegroundColorSpan(share?.let { onColor(it) } ?: vc), 0, sb.length, 0)
                    val sub = (if (ceiling) "on or under target" else "on or over target") + (if (cpTxt2.isNotEmpty()) "\n$cpTxt2" else "")
                    val s0 = sb.length; sb.append("\n$sub"); sb.setSpan(RelativeSizeSpan(0.45f), s0, sb.length, 0); sb.setSpan(ForegroundColorSpan(FAINT), s0, sb.length, 0)
                    text = sb; textSize = 26f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }
                row.addView(right)
                c.addView(row)
                val iv = ImageView(this).apply { setImageBitmap(targetBar(contentW(), wk.position, if (vt == "in range") 0 else 1, wk.loW, wk.hiW, labels = true)); scaleType = ImageView.ScaleType.FIT_XY }
                c.addView(iv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)).apply { topMargin = dp(4) })
            }
            if (wk.inT != null && wk.over != null && wk.under != null) {
                BandViews.heading(this, c, "TIME ON TARGET",
                    "How long your power was under, inside or over the planned range. Running power wobbles, so with a narrow range you can average in range yet spend many seconds just outside it. 'In or over' is the share of the rep done at or above target. Reps of 90 s or more are judged on a 10 s average and skip the first 5 s; shorter reps (e.g. 45/15) are judged second by second.",
                    topDp = 14)
                c.addView(BandViews.stripView(this, listOf(wk.underSec, wk.inSec, wk.overSec), listOf(UNDER_C, IN_C, OVER_C), contentW()))
                c.addView(BandViews.statRow(this, "In target", wk.inSec, wk.inT * 100, wk.inM, decimals = 2))
                c.addView(BandViews.statRow(this, "In or over target", wk.inSec + wk.overSec, (wk.inT + wk.over) * 100, wk.inM + wk.overM, emphasised = true, decimals = 2))
                c.addView(BandViews.statRow(this, "Under target", wk.underSec, wk.under * 100, wk.underM, decimals = 2))
            }
            if (easyRun) {
                if (z != null && z.bandSec.sum() > 0) {
                    val tot = z.bandSec.sum()
                    fun sec(vararg i: Int) = i.sumOf { z.bandSec[it] }
                    fun mm(vararg i: Int) = i.sumOf { z.bandM[it] }
                    BandViews.heading(this, c, "EASY CHECK", null, topDp = 14)
                    c.addView(BandViews.statRow(this, "Under 125", sec(0), sec(0) / tot * 100, mm(0), decimals = 2))
                    c.addView(BandViews.statRow(this, "Under 135", sec(0, 1), sec(0, 1) / tot * 100, mm(0, 1), emphasised = true, decimals = 2))
                    c.addView(BandViews.statRow(this, "Over 135", sec(2, 3, 4, 5, 6), sec(2, 3, 4, 5, 6) / tot * 100, mm(2, 3, 4, 5, 6), decimals = 2))
                }
            } else if (wk.hrA != null && wk.hrB != null && wk.hrOver != null) {
                BandViews.heading(this, c, "HEART RATE ON THE WORK", "Heart rate during work steps only. 155–172 is your SubT band; over 172 means the rep went too hard.", topDp = 14)
                c.addView(BandViews.statRow(this, "155–165", wk.hrA.sec, wk.hrA.frac * 100, wk.hrA.m, decimals = 2))
                c.addView(BandViews.statRow(this, "155–172", wk.hrB.sec, wk.hrB.frac * 100, wk.hrB.m, emphasised = true, decimals = 2))
                c.addView(BandViews.statRow(this, "Over 172", wk.hrOver.sec, wk.hrOver.frac * 100, wk.hrOver.m, decimals = 2))
            }
            CoachSheet.attach(this, c) { Coach.workout(det, z) }
        }

        // Test efforts (long hard steps at or above CP) are kept out of the work numbers
        if (det.tests.isNotEmpty()) {
            val c = card()
            label(c, "Test efforts")
            det.tests.forEachIndexed { k, st ->
                text(c, "Test ${k + 1} · ${dur(st.seconds)} · ${w(st.power.avg)}", 15f, bold = true, top = 8)
                val cpT = det.ftp?.let { f -> st.power.avg?.let { "${(it / f * 100).roundToInt()}% of CP" } }
                text(c, listOfNotNull(cpT, "${spd(st.speed.avg, ride)}", "HR avg ${st.hr.avg?.roundToInt() ?: "–"} · max ${st.hr.max?.roundToInt() ?: "–"} bpm").joinToString(" · "), 12.5f, DIM, top = 1)
            }
        }

        // Heart-rate bands for this run (every run with an HR stream)
        if (det.heart.any { it != null }) {
            if (z != null && z.bandSec.sum() > 0) {
                val c = card()
                label(c, "Heart rate bands")
                c.addView(BandViews.build(this, z.bandSec, z.bandM, contentW()))
            }
        }

        // Power / pace / HR over time
        if (det.watts.any { it != null } || det.heart.any { it != null }) {
            val c = card()
            label(c, "Power · ${if (ride) "speed" else "pace"} · heart rate")
            val bmp = WorkoutChart.draw(det, contentW(), dp(260), d, TILE)
            val iv = image(c, bmp, 260)
            ChartHits.get(bmp)?.let { Scrub.attach(iv, bmp, it) }
            text(c, "Shaded = work steps · green band = power target · dashed = HR 165 and 172. Touch and drag to read values.", 11.5f, FAINT, top = 4)
        }

        // Steps against target
        if (det.steps.isNotEmpty()) {
            val c = card()
            label(c, "Steps" + if (det.hasTargets) " · against your targets" else "")
            if (!det.hasTargets) text(c, "No planned power targets found for this workout, so steps show what you did.", 12.5f, DIM, top = 2)
            if (det.hasTargets && det.steps.count { it.isWork || it.isTest } >= 2) {
                val strip = stepStrip(det)
                val hs = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
                hs.addView(ImageView(this).apply { setImageBitmap(strip) }, ViewGroup.LayoutParams(strip.width, strip.height))
                c.addView(hs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, strip.height).apply { topMargin = dp(8); bottomMargin = dp(4) })
            }
            var workNo = 0; var testNo = 0
            for (st in det.steps) {
                val no = when { st.isWork -> ++workNo; st.isTest -> ++testNo; else -> 0 }
                stepRow(c, det, st, no)
            }
            if (det.work == null) CoachSheet.attach(this, c) { Coach.workout(det, z) }
        } else text(card(), "intervals.icu has no steps for this activity.", 13f, DIM)
    }

    private fun stepRow(parent: LinearLayout, det: WorkoutDetail.Detail, st: WorkoutDetail.Step, no: Int) {
        val ride = det.isRide
        val judged = st.isWork || st.isTest
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(9), 0, dp(4)) }
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val blockColor = when (st.kind) {
            WorkoutDetail.Kind.WORK -> PACE
            WorkoutDetail.Kind.TEST -> TEST_C
            WorkoutDetail.Kind.WARMUP -> GOOD
            WorkoutDetail.Kind.COOLDOWN -> 0xFF5EC8FF.toInt()
            else -> 0xFF4A5263.toInt()
        }
        r.addView(View(this).apply { setBackgroundColor(blockColor) }, LinearLayout.LayoutParams(dp(if (judged) 22 else 14), dp(if (judged) 22 else 14)).apply { marginEnd = dp(10) })
        val name = st.kind.label + (if (judged) " $no" else "")
        val left = TextView(this).apply {
            val sb = SpannableStringBuilder(dur(st.seconds))
            val s0 = sb.length
            sb.append("\n$name")
            sb.setSpan(RelativeSizeSpan(0.72f), s0, sb.length, 0); sb.setSpan(ForegroundColorSpan(DIM), s0, sb.length, 0)
            // Small print, each part coloured by its own rule: time in the power range, average vs range, HR in band
            fun part(t: String, color: Int) { val a = sb.length; sb.append(t); sb.setSpan(RelativeSizeSpan(0.72f), a, sb.length, 0); sb.setSpan(ForegroundColorSpan(color), a, sb.length, 0) }
            val inShare = st.inT
            if (judged && inShare != null) {
                part("\non target ${SubtModel.time(st.inSec)} (${(inShare * 100).roundToInt()}%)", DIM)
                part("  ·  ", FAINT)
                part(if (st.hrTarget) bpm(st.hr.avg) else w(st.power.avg), if (st.vsTarget == 0) GOOD else WARN)
            }
            val band = st.hrB
            if (judged && band != null && !st.hrTarget) {
                part("\n${bpm(st.hr.avg)}  ·  ${(band.frac * 100).roundToInt()}% in 155–172", hrColor(band.frac, st.hrOver?.frac))
            }
            text = sb; textSize = if (judged) 17f else 15f; setTextColor(if (judged) TEXT else DIM)
        }
        r.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val vs = st.vsTarget
        val pctT = st.pctOfTarget
        val right = TextView(this).apply {
            gravity = Gravity.END
            // Headline for a judged step: how much of it was on or over target
            val stUnder = st.under; val stIn = st.inT
            val oo = if (!judged) null else if (st.hrTarget) (if (stIn != null && stUnder != null) stIn + stUnder else null) else st.inOrOver
            val main = if (oo != null) "${(oo * 100).roundToInt()}%" else if (judged && st.hrTarget) bpm(st.hr.avg) else w(st.power.avg)
            val sb = SpannableStringBuilder(main)
            sb.setSpan(ForegroundColorSpan(if (oo != null) onColor(oo) else if (!judged || vs == null) DIM else if (vs == 0) GOOD else WARN), 0, sb.length, 0)
            val cp = if (st.hrTarget) "" else det.ftp?.let { f -> st.power.avg?.let { "${(it / f * 100).roundToInt()}% CP" } } ?: ""
            val sub = if (oo != null) (if (st.hrTarget) "on or under target" else "on or over target") + (if (cp.isNotEmpty()) "\n$cp" else "")
                else if (judged) vsText(st.targetAvg, st.loW, st.hiW, if (st.hrTarget) "bpm" else "W").first
                else if (pctT != null) vsText(st.targetAvg, st.loW, st.hiW, if (st.hrTarget) "bpm" else "W").first else ""
            if (sub.isNotEmpty()) { val s0 = sb.length; sb.append("\n$sub"); sb.setSpan(RelativeSizeSpan(0.62f), s0, sb.length, 0); sb.setSpan(ForegroundColorSpan(FAINT), s0, sb.length, 0) }
            text = sb; textSize = if (judged) 19f else 15f
            setPadding(0, 0, dp(if (judged) 0 else 6), 0)
        }
        r.addView(right)
        if (judged) r.addView(TextView(this).apply { text = if (st.index in opened) "⌃" else "›"; textSize = 18f; setTextColor(FAINT); setPadding(dp(10), 0, 0, 0) })
        box.addView(r)
        if (st.loW != null) {
            // Target range (watts or bpm) written above the ticks, visible without opening the step
            val iv = ImageView(this).apply { setImageBitmap(targetBar(contentW(), st.position, vs, st.loW, st.hiW, labels = true)); scaleType = ImageView.ScaleType.FIT_XY }
            box.addView(iv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)).apply { topMargin = dp(2) })
        }
        // Work and test steps open up for the full numbers; other steps only show % against target
        if (judged) {
            val detail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(2), 0, dp(6)); visibility = if (st.index in opened) View.VISIBLE else View.GONE }
            if (st.loW != null && st.hiW != null) {
                val u = if (st.hrTarget) "bpm" else "W"
                val (vt, vc) = vsText(st.targetAvg, st.loW, st.hiW, u)
                text(detail, spans("Target ${st.loW.roundToInt()}–${st.hiW.roundToInt()} $u  " to DIM, vt to vc), 13f, top = 6)
            }
            tiles(detail, listOf("Time" to dur(st.seconds), "Distance" to km(st.distance)))
            if (st.inT != null) {
                detail.addView(BandViews.statRow(this, "Under target", st.underSec, (st.under ?: 0.0) * 100, st.underM, decimals = 2))
                detail.addView(BandViews.statRow(this, "In target", st.inSec, st.inT * 100, st.inM, decimals = 2))
                detail.addView(BandViews.statRow(this, "Over target", st.overSec, (st.over ?: 0.0) * 100, st.overM, decimals = 2))
            }
            metric(detail, "Power", POWER, w(st.power.avg), "Min ${st.power.min?.roundToInt() ?: "–"} · Max ${st.power.max?.roundToInt() ?: "–"}")
            metric(detail, if (ride) "Speed" else "Pace", PACE, spd(st.speed.avg, ride), "Min ${spd(st.speed.min, ride, false)} · Max ${spd(st.speed.max, ride, false)}")
            metric(detail, "Heart rate", HEART, bpm(st.hr.avg), "Min ${st.hr.min?.roundToInt() ?: "–"} · Max ${st.hr.max?.roundToInt() ?: "–"}")
            st.hrA?.let { detail.addView(BandViews.statRow(this, "HR 155–165", it.sec, it.frac * 100, null)) }
            st.hrB?.let { detail.addView(BandViews.statRow(this, "HR 155–172", it.sec, it.frac * 100, null)) }
            st.cadence?.let { metric(detail, "Cadence", 0xFFC17BFF.toInt(), "${(if (ride) it else it * 2).roundToInt()} ${if (ride) "rpm" else "spm"}", null) }
            box.addView(detail)
            box.setOnClickListener {
                val o = detail.visibility != View.VISIBLE
                detail.visibility = if (o) View.VISIBLE else View.GONE
                if (o) opened += st.index else opened -= st.index
            }
        }
        parent.addView(box)
        parent.addView(View(this).apply { setBackgroundColor(0xFF2C3240.toInt()) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
    }
}

/** Power, pace (or speed) and heart rate over the workout, stacked, with work steps and targets marked. */
object WorkoutChart {
    fun draw(det: WorkoutDetail.Detail, w: Int, h: Int, d: Float, bg: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(10), h.coerceAtLeast(10), Bitmap.Config.ARGB_8888)
        val n = det.t.size
        if (n < 2) return bmp
        val c = Canvas(bmp)
        val left = 34f * d; val axis = 14f * d; val gap = 8f * d
        val ph = (h - axis - 2 * gap) / 3f
        val panels = (0..2).map { RectF(left, it * (ph + gap), w.toFloat(), it * (ph + gap) + ph) }
        fun x(i: Int) = left + (w - left) * i / (n - 1).toFloat()
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8891A3.toInt(); textSize = 9.5f * d }
        val grid = Paint().apply { color = 0xFF2C3240.toInt(); strokeWidth = 1f }

        // Work steps shaded across all panels
        val shade = Paint().apply { color = 0x14FFFFFF }
        for (st in det.steps) if (st.isWork && st.start >= 0) c.drawRect(x((st.start / det.stride).coerceIn(0, n - 1)), 0f, x((st.end / det.stride).coerceIn(0, n - 1)), h - axis, shade)

        val ride = det.isRide
        // Pace is plotted as speed (faster = higher) and labelled as pace
        val series = listOf(
            Triple(det.watts, WorkoutActivity.POWER) { v: Double -> v.roundToInt().toString() },
            Triple(det.vel.map { v -> v?.takeIf { it > 0.6 } }, WorkoutActivity.PACE) { v: Double -> if (ride) String.format(Locale.US, "%.0f", v * 3.6) else Charts.paceText(1000 / v / 60) },
            Triple(det.heart, WorkoutActivity.HEART) { v: Double -> v.roundToInt().toString() },
        )
        val ys = ArrayList<(Double) -> Float>()
        series.forEachIndexed { k, (vals, color, fmt) ->
            val r = panels[k]
            val present = vals.filterNotNull()
            if (present.isEmpty()) { ys += { _: Double -> r.bottom }; return@forEachIndexed }
            // robust range: ignore the top / bottom 1 % so a spike doesn't flatten the line
            val sorted = present.sorted()
            var lo = sorted[(sorted.size * 0.01).toInt()]; var hi = sorted[((sorted.size - 1) * 0.995).toInt()]
            if (k == 0) det.steps.filter { !it.hrTarget }.forEach { st -> st.hiW?.let { if (it > hi) hi = it }; st.loW?.let { if (st.isWork && it < lo) lo = it } }
            if (k == 2) { if (hi < 174) hi = 174.0 }
            if (hi - lo < 1e-6) { hi += 1; lo -= 1 }
            val pad = (hi - lo) * 0.08; lo -= pad; hi += pad
            val y = { v: Double -> (r.bottom - ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0) * r.height()).toFloat() }
            ys += y
            c.drawLine(r.left, r.bottom, r.right, r.bottom, grid)
            c.drawText(fmt(hi - pad), 0f, r.top + 9f * d, label)
            c.drawText(fmt(lo + pad), 0f, r.bottom - 1f * d, label)
            if (k == 0 || k == 2) {
                // target band per step (power targets on the power panel, HR targets on the HR panel)
                val band = Paint().apply { this.color = 0x553DD68C }
                for (st in det.steps) {
                    if (st.hrTarget != (k == 2)) continue
                    val a = st.loW ?: continue; val b = st.hiW ?: continue
                    if (st.start < 0) continue
                    val top = y(b); val bot = maxOf(y(a), top + 2f * d)
                    c.drawRect(x((st.start / det.stride).coerceIn(0, n - 1)), top, x((st.end / det.stride).coerceIn(0, n - 1)), bot, band)
                }
            }
            if (k == 2) {
                val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0x99FFFFFF.toInt(); strokeWidth = 1f * d; style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(3f * d, 3f * d), 0f) }
                for (lim in listOf(165.0, 172.0)) if (lim in lo..hi) {
                    c.drawLine(r.left, y(lim), r.right, y(lim), dash)
                    c.drawText(lim.roundToInt().toString(), r.right - label.measureText("172") - 2f * d, if (lim < 170) y(lim) + 10f * d else y(lim) - 2f * d, label)
                }
            }
            val path = Path(); var pen = false
            for (i in 0 until n) {
                val v = vals[i]
                if (v == null) { pen = false; continue }
                if (!pen) { path.moveTo(x(i), y(v)); pen = true } else path.lineTo(x(i), y(v))
            }
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.4f * d; strokeJoin = Paint.Join.ROUND })
        }
        // Time axis
        val total = det.t.last().coerceAtLeast(1)
        val step = listOf(300, 600, 900, 1200, 1800, 3600).firstOrNull { total / it <= 6 } ?: 3600
        var tt = step
        while (tt < total) {
            val i = det.t.indexOfFirst { it >= tt }.takeIf { it >= 0 } ?: break
            val s = SubtModel.time(tt.toDouble())
            c.drawText(s, x(i) - label.measureText(s) / 2, h - 2f * d, label)
            c.drawLine(x(i), 0f, x(i), h - axis, grid)
            tt += step
        }
        // Scrub
        ChartHits.put(bmp, (0 until n).map { i ->
            val p = det.watts[i]; val v = det.vel[i]; val hr = det.heart[i]
            val parts = listOfNotNull(SubtModel.time(det.t[i].toDouble()), p?.let { "${it.roundToInt()} W" },
                v?.takeIf { it > 0.6 }?.let { if (ride) String.format(Locale.US, "%.1f km/h", it * 3.6) else Charts.paceText(1000 / it / 60) + "/km" }, hr?.let { "${it.roundToInt()} bpm" })
            ChartHits.Hit(x(i), p?.let { ys[0](it) } ?: hr?.let { ys[2](it) } ?: panels[0].bottom, parts.joinToString(" · "), WorkoutActivity.POWER)
        })
        return bmp
    }
}
