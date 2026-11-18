package dev.subt.trainingwidget

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Detailed in-app screens (Race, Recovery, SubT) built from normal views so they can scroll. */
class AppScreens(private val a: Activity, private val wDp: Int) {

    private val d = a.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).roundToInt()
    private fun dpf(v: Float) = (v * d).roundToInt()

    private val TEXT = 0xFFF2F4F8.toInt()
    private val DIM = 0xFF9AA3B2.toInt()
    private val FAINT = 0xFF6B7386.toInt()
    private val GOOD = 0xFF3DD68C.toInt()
    private val WARN = 0xFFFFC53D.toInt()
    private val BAD = 0xFFFF7A7A.toInt()
    private val TILE = 0xFF222733.toInt()

    private val loc = Locale.getDefault()
    private val dm = DateTimeFormatter.ofPattern("d MMM", loc)
    private val edm = DateTimeFormatter.ofPattern("EEE d MMM", loc)

    // ---------------------------------------------------------------- building blocks

    private fun page(): Pair<ScrollView, LinearLayout> {
        val sv = ScrollView(a).apply { isVerticalScrollBarEnabled = false }
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(16)) }
        sv.addView(col)
        return sv to col
    }

    private fun card(parent: LinearLayout): LinearLayout {
        val c = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.tile_bg)
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }
        parent.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        return c
    }

    private fun text(parent: LinearLayout, t: CharSequence, sp: Float = 14f, color: Int = TEXT, bold: Boolean = false, top: Int = 0): TextView {
        val tv = TextView(a).apply {
            text = t
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(dpf(2f).toFloat(), 1f)
            setPadding(0, dp(top), 0, 0)
        }
        parent.addView(tv)
        return tv
    }

    private fun label(parent: LinearLayout, t: String, color: Int = DIM) =
        text(parent, t.uppercase(loc), 11f, color).apply { letterSpacing = 0.08f }

    private fun image(parent: LinearLayout, bmp: Bitmap, hDp: Int) {
        val iv = ImageView(a).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_XY }
        parent.addView(iv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(hDp)).apply { topMargin = dp(8) })
    }

    private fun spans(vararg parts: Pair<String, Int>): CharSequence {
        val sb = SpannableStringBuilder()
        for ((t, c) in parts) {
            val s = sb.length; sb.append(t)
            sb.setSpan(ForegroundColorSpan(c), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    /** A row of cells; weights let the first column be wider. */
    private fun row(parent: LinearLayout, cells: List<Pair<CharSequence, Int>>, header: Boolean = false, weights: List<Float>? = null) {
        val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(if (header) 8 else 5), 0, dp(5)) }
        cells.forEachIndexed { i, (t, c) ->
            val tv = TextView(a).apply {
                text = t
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (header) 11f else 14f)
                setTextColor(c)
                gravity = if (i == 0) Gravity.START else Gravity.END
                if (header) letterSpacing = 0.06f
            }
            r.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weights?.getOrNull(i) ?: if (i == 0) 1.6f else 1f))
        }
        parent.addView(r)
    }

    private fun divider(parent: LinearLayout) {
        val v = View(a).apply { setBackgroundColor(0xFF2C3240.toInt()) }
        parent.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(2) })
    }

    private fun bullet(parent: LinearLayout, color: Int, t: CharSequence) {
        val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(7), 0, 0) }
        r.addView(TextView(a).apply { text = "●"; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f); setPadding(0, dp(3), dp(8), 0) })
        r.addView(TextView(a).apply {
            text = t; setTextColor(TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setLineSpacing(dpf(2f).toFloat(), 1f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(r)
    }

    private fun chartW() = dp(wDp - 28)
    private fun signed(v: Int) = if (v > 0) "+$v" else "$v"
    private fun fmt1(v: Double) = String.format(loc, "%.1f", v)
    private fun hm(minutes: Double): String { val m = minutes.roundToInt(); return "${m / 60}h${"%02d".format(m % 60)}" }
    private fun date(s: String) = runCatching { LocalDate.parse(s) }.getOrNull()

    private fun stats(v: List<Double>): Pair<Double, Double> {
        val m = v.average()
        val sd = sqrt(v.sumOf { (it - m) * (it - m) } / maxOf(1, v.size - 1))
        return m to sd
    }

    // ---------------------------------------------------------------- Race

    /** Form range usually wanted on race morning, by race priority. */
    private fun formTarget(category: String) = when (category) {
        "RACE_A" -> 10 to 25
        "RACE_C" -> 0 to 10
        else -> 5 to 15
    }

    fun race(raceIndex: Int): View {
        val (sv, col) = page()
        val s = Prefs.snapshot(a)
        val today = LocalDate.now()
        val races = s?.races.orEmpty().filter { it.date >= today.toString() }
        if (s == null || races.isEmpty()) {
            val c = card(col)
            text(c, if (s == null) "Loading…" else "No race planned", 18f, bold = true)
            text(c, "Add a race (A, B or C priority) to your intervals.icu calendar and it shows up here with a taper plan.", 14f, DIM, top = 6)
            return sv
        }
        val race = races[raceIndex.coerceIn(0, races.lastIndex)]
        val rd = LocalDate.parse(race.date)
        val eve = rd.minusDays(1)
        val daysTo = ChronoUnit.DAYS.between(today, rd)

        // Header
        val h = card(col)
        label(h, "Race · " + (race.category.removePrefix("RACE_").takeIf { it.length == 1 }?.let { "$it priority" } ?: ""))
        text(h, race.name, 20f, bold = true, top = 2)
        text(h, spans(
            (if (daysTo == 0L) "Today" else "$daysTo days") to TEXT,
            "  ·  ${rd.format(edm)}${race.type.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}" to DIM,
        ), 16f, top = 2)

        // Series: history + projection, from 90 days before the race to race eve
        val series = (s.days + s.proj).distinctBy { it.date }.sortedBy { it.date }
        val start = rd.minusDays(90).toString()
        val window = series.filter { it.date >= start && it.date <= eve.toString() }
        val hasEve = window.lastOrNull()?.date == eve.toString()
        val (dropLo, dropHi) = Prefs.taperDrop(a)

        if (window.size < 2) {
            val c = card(col)
            text(c, "Not enough fitness history yet to chart this race build-up.", 14f, DIM)
            return sv
        }
        val peakIdx = window.indices.maxBy { window[it].ctl }
        val peak = window[peakIdx]
        val targetBand = peak.ctl * (1 - dropHi / 100.0) to peak.ctl * (1 - dropLo / 100.0)
        val todayIdx = window.indexOfLast { it.date <= today.toString() }

        fun idxFor(dd: LocalDate) = window.indexOfFirst { it.date == dd.toString() }
        val cps = listOf(
            "12 wk" to rd.minusWeeks(12), "3 wk" to rd.minusWeeks(3), "1 wk" to rd.minusWeeks(1), "race" to eve,
        ).mapNotNull { (l, dd) -> idxFor(dd).takeIf { it >= 0 }?.let { it to l } }

        // Build-up chart
        val cc = card(col)
        label(cc, "Fitness build-up · last 3 months")
        image(cc, Charts.raceBuild(window, todayIdx, cps, peakIdx, targetBand, chartW(), dp(230), d, TILE), 230)
        text(cc, spans(
            "━ Fitness " to FormColors.FITNESS, "(solid = done, dashed = planned)   " to FAINT,
            "━ Fatigue   " to FormColors.FATIGUE, "▇ taper target" to GOOD,
        ), 11f, top = 6)
        if (!hasEve) text(cc, "Your plan is projected only 12 weeks ahead, so race-day values appear once the race is within 12 weeks.", 12f, WARN, top = 6)

        // Checkpoints table
        val t = card(col)
        label(t, "Checkpoints · race day = morning (end of the day before)")
        row(t, listOf("" to FAINT, "FITNESS" to FAINT, "FATIGUE" to FAINT, "FORM" to FAINT, "% PEAK" to FAINT), header = true)
        divider(t)
        fun cpRow(name: String, dd: Day?, highlight: Boolean = false) {
            if (dd == null) return
            val z = FormZone.of(dd.form)
            val pct = (dd.ctl / peak.ctl * 100).roundToInt()
            val pctColor = when {
                !highlight -> DIM
                pct in (100 - dropHi)..(100 - dropLo) -> GOOD
                else -> WARN
            }
            val dl = date(dd.date)?.format(dm) ?: ""
            row(t, listOf(
                spans(name to (if (highlight) TEXT else DIM), "\n$dl" to FAINT) to TEXT,
                "${dd.ctl.roundToInt()}" to FormColors.FITNESS,
                "${dd.atl.roundToInt()}" to FormColors.FATIGUE,
                signed(dd.form.roundToInt()) to z.color,
                "$pct%" to pctColor,
            ))
        }
        cpRow("Peak (3 mo)", peak)
        cpRow("12 weeks out", window.getOrNull(idxFor(rd.minusWeeks(12))))
        cpRow("3 weeks out", window.getOrNull(idxFor(rd.minusWeeks(3))))
        cpRow("1 week out", window.getOrNull(idxFor(rd.minusWeeks(1))))
        if (todayIdx >= 0 && window[todayIdx].date != eve.toString()) cpRow("Today", window[todayIdx])
        if (hasEve) cpRow("Race day", window.last(), highlight = true)

        // Targets
        val eveDay = if (hasEve) window.last() else null
        val tg = card(col)
        label(tg, "Targets")
        val (fLo, fHi) = formTarget(race.category)
        text(tg, spans(
            "Fitness: " to DIM, "$dropLo–$dropHi% below peak" to TEXT,
            "  →  ${targetBand.first.roundToInt()}–${targetBand.second.roundToInt()}" to GOOD,
        ), 15f, top = 4)
        text(tg, spans("Form: " to DIM, "${signed(fLo)} to ${signed(fHi)}" to TEXT, " for a ${race.category.removePrefix("RACE_")} race" to DIM), 15f, top = 4)
        if (eveDay != null) {
            val pct = eveDay.ctl / peak.ctl * 100
            val okF = pct in (100.0 - dropHi)..(100.0 - dropLo)
            val form = eveDay.form.roundToInt()
            val okForm = form in fLo..fHi
            text(tg, spans(
                "Projected: Fitness ${eveDay.ctl.roundToInt()} (${pct.roundToInt()}% of peak) " to TEXT,
                (if (okF) "✓" else "⚠") to (if (okF) GOOD else WARN),
                "   Form ${signed(form)} " to TEXT, (if (okForm) "✓" else "⚠") to (if (okForm) GOOD else WARN),
            ), 15f, top = 8)
        }
        text(tg, "Change the target drop with the chips above.", 12f, FAINT, top = 6)

        // Coach notes
        val cn = card(col)
        label(cn, "Coach notes")
        coachNotes(cn, s, race, races, eveDay, peak, dropLo, dropHi, daysTo)
        text(cn, "Rule-of-thumb guidance from your intervals.icu data. It isn't medical advice.", 11f, FAINT, top = 10)
        return sv
    }

    private fun coachNotes(
        c: LinearLayout, s: Snapshot, race: Race, races: List<Race>, eve: Day?, peak: Day,
        dropLo: Int, dropHi: Int, daysTo: Long,
    ) {
        val today = LocalDate.now()
        if (eve != null) {
            val drop = (1 - eve.ctl / peak.ctl) * 100
            when {
                drop < dropLo -> bullet(c, WARN, "Fitness is only ${fmt1(drop)}% below your peak of ${peak.ctl.roundToInt()} on race day. For more freshness, cut volume in the last 7–10 days but keep short race-pace efforts.")
                drop > dropHi -> bullet(c, WARN, "Fitness drops ${fmt1(drop)}% from peak by race day, more than your ${dropHi}% limit. The taper may be too long or deep, so keep some intensity and don't add extra rest days.")
                else -> bullet(c, GOOD, "Taper on target: race-day Fitness ${eve.ctl.roundToInt()} is ${fmt1(drop)}% below your peak (${peak.ctl.roundToInt()} on ${date(peak.date)?.format(dm)}).")
            }
            val (fLo, fHi) = formTarget(race.category)
            val form = eve.form.roundToInt()
            when {
                form < fLo -> bullet(c, WARN, "Form ${signed(form)} is below the ${signed(fLo)}…${signed(fHi)} usually wanted. Lighten the last 3–4 days to arrive fresher.")
                form > fHi + 5 -> bullet(c, WARN, "Form ${signed(form)} is very fresh, and some runners feel flat at this level. A short opener with a few race-pace minutes 1–2 days before helps.")
                else -> bullet(c, GOOD, "Race-day Form ${signed(form)} (${FormZone.of(eve.form).label}) is in the ${signed(fLo)}…${signed(fHi)} range for this race.")
            }
        }
        // Phase advice
        val days = s.days
        val ramp = if (days.size >= 8) days.last().ctl - days[days.size - 8].ctl else null
        when {
            daysTo <= 3 -> bullet(c, FormColors.FITNESS, "Final days: keep runs short and easy with a few strides. Sleep, carbs and hydration matter more than training now.")
            daysTo <= 10 -> bullet(c, FormColors.FITNESS, "Taper: cut volume by about 40–60% while keeping some race-pace work, so you stay sharp without adding fatigue.")
            daysTo <= 21 -> bullet(c, FormColors.FITNESS, "Last block: your final long run and key session belong here. Start the taper about 10–14 days before a half or 7–10 days before a 10K.")
            ramp != null -> {
                val rr = fmt1(ramp)
                when {
                    ramp > 8 -> bullet(c, WARN, "Fitness is ramping $rr/week, which is steep. Watch resting HR and sleep, and add a lighter week if they slip.")
                    ramp < 0 -> bullet(c, WARN, "Fitness has fallen $rr over the last week. That's fine in a recovery week; otherwise consistency is what builds it.")
                    else -> bullet(c, GOOD, "Fitness is building $rr/week, a sustainable ramp.")
                }
            }
        }
        // Heavy session right before the race
        val rd = LocalDate.parse(race.date)
        s.week.filter { it.status == WorkoutStatus.PLANNED && it.load >= 60 }
            .firstOrNull { val dd = date(it.date); dd != null && dd.isBefore(rd) && !dd.isBefore(rd.minusDays(2)) }
            ?.let { bullet(c, WARN, "\"${it.name}\" (load ${it.load}) is planned ${ChronoUnit.DAYS.between(date(it.date), rd)} day(s) before the race. Consider moving it or making it easier.") }
        // Recovery signals
        val rhr = s.days.mapNotNull { it.rhr }
        if (rhr.size >= 14) {
            val base = rhr.dropLast(7).average()
            val delta = rhr.takeLast(7).average() - base
            if (delta >= 3) bullet(c, WARN, "Resting HR is up ${fmt1(delta)} bpm on your normal (${base.roundToInt()}). Prioritise sleep, hydrate, and take an extra easy day if it stays high.")
        }
        val sleep = s.days.mapNotNull { it.sleepSecs?.div(60) }
        if (sleep.size >= 5 && sleep.takeLast(7).average() < 420) {
            bullet(c, WARN, "Sleep is averaging ${hm(sleep.takeLast(7).average())}. Aim for 8 hours or more in the nights before the race.")
        }
        // Next race soon after
        val idx = races.indexOf(race)
        races.getOrNull(idx + 1)?.let { nx ->
            val gap = ChronoUnit.DAYS.between(rd, LocalDate.parse(nx.date))
            if (gap in 1..35) bullet(c, FormColors.FITNESS, "Next: ${nx.name} is $gap days later. Plan 3–5 easy days after this race, then a short mini-taper to bring Form back to about +5…+10.")
        }
        if (today.isAfter(rd)) bullet(c, DIM, "This race is in the past.")
    }

    // ---------------------------------------------------------------- Recovery

    fun recovery(period: Int): View {
        val (sv, col) = page()
        val s = Prefs.snapshot(a)
        if (s == null) { text(card(col), "Loading…", 16f); return sv }
        val today = LocalDate.now()
        val start = today.minusDays(period - 1L)
        val days = s.days.filter { it.date >= start.toString() }
        val pLabel = Periods.label(period)
        val signals = mutableListOf<Pair<Int, String>>()

        // Summary first (filled after computing signals)
        val summary = card(col)

        fun dailyCard(
            title: String, color: Int, values: List<Pair<LocalDate, Double>>, unit: String, higherIsBetter: Boolean,
            fmtV: (Double) -> String, whatItMeans: String,
        ) {
            val c = card(col)
            label(c, title, color)
            if (values.isEmpty()) {
                text(c, "No $title data in intervals.icu for this period.", 14f, DIM, top = 4)
                text(c, whatItMeans, 12f, FAINT, top = 4)
                return
            }
            val v = values.map { it.second }
            val baseVals = if (v.size > 10) v.dropLast(7) else v
            val (mean, sd) = stats(baseVals)
            val recent = v.takeLast(7).average()
            val delta = recent - mean
            val good = if (higherIsBetter) delta >= -0.5 * sd else delta <= 0.5 * sd
            val bad = if (higherIsBetter) delta < -sd else delta > sd
            val col2 = if (bad) BAD else if (good) GOOD else WARN
            text(c, spans(fmtV(v.last()) to TEXT, " $unit · latest" to DIM), 24f, bold = true, top = 2)
            text(c, spans(
                "7-day avg ${fmtV(recent)} " to TEXT,
                "(${if (delta >= 0) "+" else ""}${fmt1(delta)} vs your normal ${fmtV(mean - sd)}–${fmtV(mean + sd)})" to col2,
            ), 14f, top = 2)
            image(c, Charts.timeChart(values, start, today, color, chartW(), dp(150), d, TILE,
                band = (mean - sd) to (mean + sd), fmt = fmtV), 150)
            text(c, "Shaded band = your normal range (average ± 1 SD over $pLabel). $whatItMeans", 12f, FAINT, top = 6)
            if (bad) signals += BAD to "$title ${if (higherIsBetter) "below" else "above"} normal (${fmtV(recent)} $unit vs ${fmtV(mean)})"
            else if (!good) signals += WARN to "$title drifting ${if (higherIsBetter) "down" else "up"}"
            else signals += GOOD to "$title normal"
        }

        dailyCard("Resting HR", 0xFFFF7A90.toInt(), days.mapNotNull { dd -> dd.rhr?.let { date(dd.date)!! to it } }, "bpm", false,
            { it.roundToInt().toString() },
            "Higher than normal often means fatigue, illness, heat, alcohol or short sleep.")
        dailyCard("HRV", 0xFF2DD4BF.toInt(), days.mapNotNull { dd -> dd.hrv?.let { date(dd.date)!! to it } }, "ms", true,
            { it.roundToInt().toString() },
            "Lower than normal suggests your body is still absorbing stress. Higher means well recovered.")

        // Sleep (target band 7–9 h)
        run {
            val c = card(col)
            val color = 0xFF8C9EFF.toInt()
            label(c, "Sleep", color)
            val vals = days.mapNotNull { dd -> dd.sleepSecs?.let { date(dd.date)!! to it / 3600.0 } }
            if (vals.isEmpty()) { text(c, "No sleep data in intervals.icu for this period.", 14f, DIM, top = 4); return@run }
            val avg7 = vals.takeLast(7).map { it.second }.average()
            val colr = when { avg7 >= 7 -> GOOD; avg7 < 6 -> BAD; else -> WARN }
            text(c, spans(hm(vals.last().second * 60) to TEXT, " last night" to DIM), 24f, bold = true, top = 2)
            text(c, spans("7-day avg ${hm(avg7 * 60)}" to colr, "   ·   ${pLabel} avg ${hm(vals.map { it.second }.average() * 60)}" to DIM), 14f, top = 2)
            image(c, Charts.timeChart(vals, start, today, color, chartW(), dp(150), d, TILE,
                band = 7.0 to 9.0, bandColor = GOOD, fmt = { "${it.roundToInt()}h" }), 150)
            text(c, "Green band = 7–9 h, the range most athletes recover best in.", 12f, FAINT, top = 6)
            signals += colr to (if (colr == GOOD) "Sleep on target" else "Sleep short (${hm(avg7 * 60)} avg)")
        }

        // Run metrics (last ~90 days available)
        val runStart = maxOf(start, today.minusDays(89))
        val runs = s.runs.filter { it.date >= runStart.toString() }
        run {
            val c = card(col)
            label(c, "HR drift (aerobic decoupling)", 0xFFFF9F5A.toInt())
            val pts = runs.filter { it.seconds >= 1800 && it.decoupling != null }.mapNotNull { r -> date(r.date)?.let { it to r.decoupling!! } }
            if (pts.isEmpty()) {
                text(c, "No decoupling values on runs of 30 min or more. intervals.icu computes them for steady runs with HR.", 14f, DIM, top = 4)
                return@run
            }
            val last5 = pts.takeLast(5).map { it.second }.average()
            val colr = when { last5 < 5 -> GOOD; last5 < 10 -> WARN; else -> BAD }
            text(c, spans(fmt1(last5) to TEXT, " % · average of last ${minOf(5, pts.size)} runs" to DIM), 24f, bold = true, top = 2)
            text(c, when {
                last5 < 5 -> "Under 5%: heart rate stays steady for the same output, so your aerobic durability is good."
                last5 < 10 -> "5–10%: moderate drift. Common with fatigue, heat, dehydration or pacing above aerobic."
                else -> "Over 10%: big drift. You're tired or running too hard for your current base."
            }, 14f, colr, top = 2)
            image(c, Charts.timeChart(pts, runStart, today, 0xFFFF9F5A.toInt(), chartW(), dp(150), d, TILE,
                threshold = 5.0, scatter = true, fmt = { "${it.roundToInt()}%" }), 150)
            text(c, "Dots = runs of 30 min or more, line = 5-run average, dashed = the 5% line. Most meaningful on steady runs.", 12f, FAINT, top = 6)
            signals += colr to "HR drift ${fmt1(last5)}%"
        }
        run {
            val usePower = runs.count { it.powerHr != null } >= 3
            val pts = runs.mapNotNull { r ->
                val v = if (usePower) r.powerHr else r.ef
                v?.let { vv -> date(r.date)?.let { it to vv } }
            }
            val c = card(col)
            label(c, if (usePower) "Power : HR" else "Efficiency factor", 0xFF4EA1FF.toInt())
            if (pts.size < 3) {
                text(c, "Not enough runs with power or efficiency values yet.", 14f, DIM, top = 4)
                return@run
            }
            val v = pts.map { it.second }
            val recent = v.takeLast(5).average()
            val before = v.dropLast(5).takeLast(10).ifEmpty { v }.average()
            val ch = (recent / before - 1) * 100
            val colr = if (ch >= 1) GOOD else if (ch <= -3) BAD else WARN
            text(c, spans(String.format(loc, "%.2f", recent) to TEXT, " · last 5 runs" to DIM), 24f, bold = true, top = 2)
            text(c, "${if (ch >= 0) "+" else ""}${fmt1(ch)}% vs the runs before. " +
                (if (ch >= 1) "More output per heartbeat: aerobic fitness improving or well recovered."
                else if (ch <= -3) "Less output per heartbeat: often fatigue, heat or illness."
                else "Holding steady."), 14f, colr, top = 2)
            image(c, Charts.timeChart(pts, runStart, today, 0xFF4EA1FF.toInt(), chartW(), dp(150), d, TILE,
                scatter = true, fmt = { String.format(loc, "%.2f", it) }), 150)
            text(c, (if (usePower) "Average power ÷ average HR" else "Output ÷ heart rate") + " per run. Higher is better. Compare similar runs (easy with easy).", 12f, FAINT, top = 6)
            signals += colr to "Efficiency ${if (ch >= 0) "+" else ""}${fmt1(ch)}%"
        }
        // SubT HR from the repo's data
        run {
            val sub = Prefs.subt(a) ?: return@run
            val months = sub.months.filter { !it.outlier && it.hr != null }.takeLast(12)
            if (months.size < 2) return@run
            val c = card(col)
            label(c, "Heart rate during SubT laps", 0xFFFF9F5A.toInt())
            val last = months.last()
            val ref = months.getOrNull(months.size - 4) ?: months.first()
            val effCh = ((last.efficiency!! / ref.efficiency!!) - 1) * 100
            val colr = if (effCh >= 1) GOOD else if (effCh <= -2) BAD else WARN
            val mn = { m: SubtMonth -> runCatching { LocalDate.parse(m.month + "-01").format(DateTimeFormatter.ofPattern("MMM", loc)) }.getOrDefault(m.month) }
            text(c, spans("${last.hr!!.roundToInt()}" to TEXT, " bpm at ${Charts.paceText(last.pace)}/km · ${mn(last)}" to DIM), 24f, bold = true, top = 2)
            text(c, "vs ${ref.hr!!.roundToInt()} bpm at ${Charts.paceText(ref.pace)}/km in ${mn(ref)}: ${if (effCh >= 0) "+" else ""}${fmt1(effCh)}% speed per heartbeat. " +
                (if (effCh >= 1) "You're getting faster for the same effort." else if (effCh <= -2) "Less speed per beat. Check fatigue." else "About the same."), 14f, colr, top = 2)
            val pts = months.mapNotNull { m -> runCatching { LocalDate.parse(m.month + "-15") }.getOrNull()?.let { it to m.hr!! } }
            image(c, Charts.timeChart(pts, pts.first().first.minusDays(15), pts.last().first.plusDays(15), 0xFFFF9F5A.toInt(),
                chartW(), dp(140), d, TILE, fmt = { it.roundToInt().toString() }), 140)
            text(c, "Monthly median HR on SubT laps. Lower HR at the same or faster pace means adaptation.", 12f, FAINT, top = 6)
            signals += colr to "SubT efficiency ${if (effCh >= 0) "+" else ""}${fmt1(effCh)}%"
        }

        // Fill summary
        val bads = signals.count { it.first == BAD }
        val warns = signals.count { it.first == WARN }
        val (verdict, vc) = when {
            bads >= 2 -> "Recovery needed" to BAD
            bads == 1 || warns >= 2 -> "Take it a bit easier" to WARN
            else -> "Good to train" to GOOD
        }
        label(summary, "Readiness · $pLabel")
        text(summary, verdict, 22f, vc, bold = true, top = 2)
        signals.forEach { (c, t) -> bullet(summary, c, t) }
        text(summary, "Based on resting HR, HRV, sleep, HR drift, efficiency and SubT HR. One bad night isn't a trend. Look for several signals together.", 12f, FAINT, top = 8)
        return sv
    }

    // ---------------------------------------------------------------- SubT extras (below the widget card)

    fun subtExtras(col: LinearLayout) {
        val sub = Prefs.subt(a) ?: return
        val months = sub.months.filter { !it.outlier }
        val cur = months.lastOrNull()?.pace ?: return

        val c = card(col)
        label(c, "Projection to 5K goals")
        val real = SubtModel.path(cur, SubtModel.REAL, 24)
        val fast = SubtModel.path(cur, SubtModel.FAST, 24)
        val slow = SubtModel.path(cur, SubtModel.SLOW, 24)
        image(c, Charts.subtProjection(months.takeLast(12), real, fast, slow, SubtModel.GOALS.map { it.first to SubtModel.requiredSubtFor5k(it.second) },
            chartW(), dp(220), d, TILE), 220)
        text(c, spans("━ SubT history  " to Charts.SUBT, "┅ realistic projection  " to Charts.SUBT, "▇ fast–slow range  " to FAINT, "┄ goal pace" to WARN), 11f, top = 6)

        text(c, spans(
            "Predicted now (SubT ${Charts.paceText(cur)}): " to DIM,
            "5K ${SubtModel.time(SubtModel.fiveK(cur))} · HM ${SubtModel.time(SubtModel.half(cur))} · FM ${SubtModel.time(SubtModel.full(cur))}" to TEXT,
        ), 14f, top = 10)

        row(c, listOf("GOAL" to FAINT, "NEEDS" to FAINT, "REALISTIC" to FAINT, "RANGE" to FAINT), header = true, weights = listOf(1.3f, 0.9f, 1.1f, 1.4f))
        divider(c)
        val now = LocalDate.now().withDayOfMonth(1)
        val my = DateTimeFormatter.ofPattern("MMM yy", loc)
        val longR = SubtModel.path(cur, SubtModel.REAL, 60)
        val longF = SubtModel.path(cur, SubtModel.FAST, 60)
        val longS = SubtModel.path(cur, SubtModel.SLOW, 60)
        for ((name, sec) in SubtModel.GOALS) {
            val need = SubtModel.requiredSubtFor5k(sec)
            val done = cur <= need
            val r = SubtModel.arrival(longR, need)
            val f = SubtModel.arrival(longF, need)
            val sl = SubtModel.arrival(longS, need)
            fun m(x: Int?) = x?.let { now.plusMonths(it.toLong()).format(my) } ?: ">5 yr"
            row(c, listOf(
                name to (if (done) GOOD else TEXT),
                Charts.paceText(need) to DIM,
                (if (done) "✓ now" else m(r)) to (if (done) GOOD else Charts.SUBT),
                (if (done) "" else "${m(f)} – ${m(sl)}") to FAINT,
            ), weights = listOf(1.3f, 0.9f, 1.1f, 1.4f))
        }
        text(c, "Same model as your projection page: Daniels VDOT, 11% race gap, rates per month of −12 s/km while rebuilding to 4:32, then −2 (realistic; fast −3, slow −1) down to 4:00. Overrides you set on the page aren't visible here.", 12f, FAINT, top = 8)
    }
}
