package dev.subt.trainingwidget

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * "Fit in a missed session": 1-3 ways to fit a missed (or about-to-be-missed) run back into the week, each with a
 * before / after grid. Nothing is written until you pick one, tap Apply and confirm. Undo stays here for the day.
 */
class SwapActivity : Activity() {
    companion object {
        const val EXTRA_DATE = "date"
        const val EXTRA_EVENT = "event"
        const val EXTRA_TRIGGER = "trigger"
        const val EXTRA_WHY = "why"

        fun intent(ctx: Context, date: LocalDate?, eventId: Long?, trigger: Swaps.Trigger, why: String?): Intent =
            Intent(ctx, SwapActivity::class.java).apply {
                if (date != null) putExtra(EXTRA_DATE, date.toString())
                if (eventId != null) putExtra(EXTRA_EVENT, eventId)
                putExtra(EXTRA_TRIGGER, trigger.name)
                if (why != null) putExtra(EXTRA_WHY, why)
                if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        fun open(ctx: Context, date: LocalDate?, eventId: Long?, trigger: Swaps.Trigger, why: String? = null) =
            ctx.startActivity(intent(ctx, date, eventId, trigger, why))

        private const val TEXT = 0xFFF2F4F8.toInt()
        private const val DIM = 0xFF9AA3B2.toInt()
        private const val FAINT = 0xFF6B7386.toInt()
        private const val GOOD = 0xFF3DD68C.toInt()
        private const val WARN = 0xFFFFC53D.toInt()
        private const val C_EASY = 0xFF3A6EA5.toInt()
        private const val C_WORK = 0xFFD9822B.toInt()
        private const val C_LONG = 0xFF8E5BD9.toInt()
        private const val C_TT = 0xFFC2506B.toInt()
        private const val C_RACE = 0xFFB8963A.toInt()
        private const val C_MISSED = 0xFF7A3B3B.toInt()
        private const val C_DOUBLE = 0xFF2E9E62.toInt()
        private const val C_SKIP = 0xFF4A5060.toInt()
    }

    private lateinit var col: LinearLayout
    private var date: LocalDate? = null
    private var eventId: Long? = null
    private var trigger: Swaps.Trigger = Swaps.Trigger.MANUAL
    private var why: String? = null
    private var selected = 0
    private var status: String? = null
    private var busy = false
    private var opts: List<Swaps.Option> = emptyList()
    private var missed: SwapFlow.Pickable? = null

    private val d get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        date = runCatching { LocalDate.parse(intent?.getStringExtra(EXTRA_DATE).orEmpty()) }.getOrNull()
        eventId = if (intent?.hasExtra(EXTRA_EVENT) == true) intent.getLongExtra(EXTRA_EVENT, -1L).takeIf { it >= 0 } else null
        trigger = runCatching { Swaps.Trigger.valueOf(intent?.getStringExtra(EXTRA_TRIGGER).orEmpty()) }.getOrDefault(Swaps.Trigger.MANUAL)
        why = intent?.getStringExtra(EXTRA_WHY)
        val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false; fitsSystemWindows = true }
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(24)) }
        sv.addView(col)
        setContentView(sv)
        render()
    }

    // ---------------------------------------------------------------- building blocks

    private fun text(parent: LinearLayout, t: CharSequence, sp: Float = 14f, color: Int = TEXT, bold: Boolean = false, top: Int = 0): TextView {
        val tv = TextView(this).apply {
            text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color)
            if (bold) typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(top), 0, 0)
        }
        parent.addView(tv)
        return tv
    }

    private fun card(selectedCard: Boolean = false): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.tile_bg)
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }
        if (selectedCard) {
            c.background = GradientDrawable().apply { setColor(0xFF222733.toInt()); cornerRadius = dp(14).toFloat(); setStroke(dp(2), GOOD) }
            c.setPadding(dp(14), dp(12), dp(14), dp(14))
        }
        col.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        return c
    }

    private fun header(title: String, sub: String) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(10)) }
        r.addView(TextView(this).apply { text = "‹"; textSize = 28f; setTextColor(TEXT); setPadding(dp(4), 0, dp(14), 0); setOnClickListener { finish() } })
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(TextView(this).apply { text = title; textSize = 19f; setTextColor(TEXT); typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL); maxLines = 2 })
        if (sub.isNotEmpty()) t.addView(TextView(this).apply { text = sub; textSize = 13f; setTextColor(DIM) })
        r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(r)
    }

    private fun button(parent: LinearLayout, t: String, enabled: Boolean = true, onClick: () -> Unit) {
        parent.addView(TextView(this).apply {
            text = t; textSize = 15f; setTextColor(if (enabled) TEXT else FAINT); gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.input_bg)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            if (enabled) setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    private fun kindColor(k: Swaps.Kind) = when (k) {
        Swaps.Kind.EASY -> C_EASY
        Swaps.Kind.WORKOUT -> C_WORK
        Swaps.Kind.LONG -> C_LONG
        Swaps.Kind.TT -> C_TT
        Swaps.Kind.RACE -> C_RACE
        Swaps.Kind.OTHER -> C_SKIP
    }

    private fun kindLetter(k: Swaps.Kind) = when (k) {
        Swaps.Kind.EASY -> "E"
        Swaps.Kind.WORKOUT -> "W"
        Swaps.Kind.LONG -> "L"
        Swaps.Kind.TT -> "TT"
        Swaps.Kind.RACE -> "R"
        Swaps.Kind.OTHER -> "·"
    }

    private fun cellView(c: Swaps.Cell): TextView {
        val (fill, label, stroke) = when (c.tag) {
            Swaps.Tag.DOUBLE -> Triple(C_DOUBLE, "+${c.minutes}′" + (c.startMin?.let { "\n" + Swaps.hhmm(it) } ?: ""), 0)
            Swaps.Tag.MISSED -> Triple(C_MISSED, "✕ ${kindLetter(c.kind)}", 0)
            Swaps.Tag.SKIP -> Triple(C_SKIP, "skip ${kindLetter(c.kind)}", 0)
            Swaps.Tag.MOVED -> Triple(kindColor(c.kind), "${kindLetter(c.kind)} ${c.minutes}′", TEXT)
            Swaps.Tag.ADDED -> Triple(kindColor(c.kind), "${kindLetter(c.kind)} ${c.minutes}′", GOOD)
            Swaps.Tag.PLAN -> Triple(kindColor(c.kind), "${kindLetter(c.kind)} ${c.minutes}′", 0)
        }
        return TextView(this).apply {
            text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f); setTextColor(TEXT); gravity = Gravity.CENTER
            setPadding(dp(1), dp(3), dp(1), dp(3)); maxLines = 2
            background = GradientDrawable().apply { setColor(fill); cornerRadius = dp(5).toFloat(); if (stroke != 0) setStroke(dp(1), stroke) }
        }
    }

    private fun gridRow(label: String, cells: List<List<Swaps.Cell>>, header: List<String>? = null): LinearLayout {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(3), 0, 0) }
        r.addView(TextView(this).apply { text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f); setTextColor(DIM); gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.MATCH_PARENT))
        val n = header?.size ?: cells.size
        for (i in 0 until n) {
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            if (header != null) cell.addView(TextView(this).apply { text = header[i]; setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f); setTextColor(DIM); gravity = Gravity.CENTER; maxLines = 2 })
            else {
                val list = cells[i]
                if (list.isEmpty()) cell.addView(TextView(this).apply { text = "–"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f); setTextColor(FAINT); gravity = Gravity.CENTER; setPadding(0, dp(3), 0, dp(3)) })
                for (c in list) cell.addView(cellView(c), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(2) })
            }
            r.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(1); marginEnd = dp(1) })
        }
        return r
    }

    private fun grid(opt: Swaps.Option): View {
        val w = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
        val fmt = DateTimeFormatter.ofPattern("EEE\nd", Locale.getDefault())
        w.addView(gridRow("", emptyList(), header = opt.rows.map { it.date.format(fmt) }))
        w.addView(gridRow("Now", opt.rows.map { it.before }))
        w.addView(gridRow("After", opt.rows.map { it.after }))
        return w
    }

    // ---------------------------------------------------------------- the screen

    private fun currentMissed(): SwapFlow.Pickable? {
        val all = SwapFlow.pickable(this)
        val id = eventId
        val dt = date
        return all.firstOrNull { id != null && it.workout.eventId == id }
            ?: all.firstOrNull { dt != null && it.date == dt }
            ?: if (id == null && dt == null) all.firstOrNull { it.date >= LocalDate.now() } else null
    }

    private fun render() {
        col.removeAllViews()
        val dayFmt = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())
        val m = currentMissed()
        missed = m
        if (m != null) { date = m.date; eventId = m.workout.eventId }
        header("Fit in a missed session", if (m == null) "" else "${m.workout.name} · ${m.date.format(dayFmt)}")
        if (Prefs.credentials(this) == null) {
            val c = card()
            text(c, "Connect intervals.icu in Settings first: the options come from your planned week.", 14.5f)
            return
        }
        // Session chooser (when more than one run could be the one)
        val all = SwapFlow.pickable(this)
        if (all.size > 1) {
            val c = card()
            text(c, "WHICH SESSION?", 11f, DIM)
            val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
            val chipFmt = DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())
            for (p in all) {
                val sel = m != null && p.workout.eventId == m.workout.eventId
                row.addView(TextView(this).apply {
                    text = p.date.format(chipFmt) + " · " + p.workout.name.take(18)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTextColor(if (sel) TEXT else DIM); maxLines = 1
                    setBackgroundResource(if (sel) R.drawable.chip_on else R.drawable.chip_off)
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    setOnClickListener {
                        if (!sel) { date = p.date; eventId = p.workout.eventId; trigger = Swaps.Trigger.MANUAL; why = null; selected = 0; status = null; render() }
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
            }
            hs.addView(row)
            c.addView(hs)
        }
        if (m == null) {
            val c = card()
            text(c, "No planned run to fit in right now.", 15f)
            text(c, "Pick a day on the Week screen and choose \"Can't make this one\", or wait until a run looks missed.", 13f, DIM, top = 4)
            undoCard()
            return
        }
        text(col, Swaps.reasonText(trigger, why), 13.5f, if (trigger == Swaps.Trigger.MANUAL) DIM else WARN).setPadding(dp(4), 0, 0, dp(10))
        opts = SwapFlow.optionsFor(this, m.date, m.workout)
        if (selected >= opts.size) selected = 0
        if (opts.isEmpty()) {
            val c = card()
            val kind = SwapFlow.kindOf(m.workout)
            text(c, if (kind == Swaps.Kind.RACE) "Races never move." else "No good way to fit this one in this week.", 15f)
            text(c, "Nothing is changed in intervals.icu. Skipping this one is fine.", 13f, DIM, top = 4)
        }
        for ((i, o) in opts.withIndex()) {
            val c = card(i == selected)
            text(c, "Option ${i + 1} · ${o.title}", 15f, TEXT, bold = true)
            c.addView(grid(o))
            for (line in o.summary) text(c, "• $line", 12.5f, DIM, top = 3)
            if (o.summary.isNotEmpty()) text(c, if (i == selected) "Selected" else "Tap to select", 12f, if (i == selected) GOOD else FAINT, top = 6)
            c.setOnClickListener { selected = i; render() }
        }
        if (opts.isNotEmpty()) {
            BandViews.heading(this, col, "READING THESE OPTIONS", "Green boxes are added doubles (with the time of the better window). White outline = moved, green outline = added. Nothing changes in intervals.icu until you tap Apply and confirm. Workouts are never deleted: they are moved, or a missed one stays and shows as not done.")
        }
        val c = card()
        status?.let { text(c, it, 13.5f, if (it.startsWith("Couldn't")) WARN else GOOD) }
        if (opts.isNotEmpty()) {
            button(c, if (busy) "Working…" else if (trigger == Swaps.Trigger.AT_RISK || trigger == Swaps.Trigger.TIGHT) "Apply in intervals.icu (preview until you do)" else "Apply in intervals.icu", enabled = !busy) { confirm(opts[selected]) }
        }
        val dismissLabel = when (trigger) {
            Swaps.Trigger.AT_RISK, Swaps.Trigger.TIGHT -> "I'll still run it"
            Swaps.Trigger.MANUAL -> "Close"
            else -> "Not now"
        }
        button(c, dismissLabel) {
            if (trigger != Swaps.Trigger.MANUAL) m.workout.eventId?.let { SwapFlow.mark(this, it, "dismissed") }
            finish()
        }
        undoCard()
    }

    private fun undoCard() {
        val rec = SwapFlow.undoRec(this) ?: return
        val c = card()
        text(c, "Applied today for ${rec.label}: ${rec.moves.size} moved, ${rec.created.size} added.", 13f, DIM)
        button(c, "Undo", enabled = !busy) { doUndo() }
    }

    private fun confirm(o: Swaps.Option) {
        val body = o.changes.joinToString("\n") { "• " + Swaps.describe(it) }
        AlertDialog.Builder(this)
            .setTitle("Apply in intervals.icu?")
            .setMessage("These changes will be made in your intervals.icu calendar:\n\n$body\n\nMoved sessions keep their time and power targets. You can undo today.")
            .setPositiveButton("Apply") { _, _ -> doApply(o) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doApply(o: Swaps.Option) {
        val m = missed ?: return
        busy = true; status = "Applying…"
        render()
        Thread {
            var ok = true
            val msg = try { SwapFlow.apply(applicationContext, o, m.workout.name, m.workout.eventId) }
            catch (e: Exception) { ok = false; "Couldn't apply: ${e.message ?: e.javaClass.simpleName}. Anything already done was put back." }
            runOnUiThread {
                busy = false
                status = msg
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (!isFinishing) render()
                if (ok) selected = 0
            }
        }.start()
    }

    private fun doUndo() {
        busy = true; status = "Undoing…"
        render()
        Thread {
            val msg = try { SwapFlow.undo(applicationContext) } catch (e: Exception) { "Couldn't undo: ${e.message ?: e.javaClass.simpleName}" }
            runOnUiThread {
                busy = false
                status = msg
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (!isFinishing) render()
            }
        }.start()
    }
}
