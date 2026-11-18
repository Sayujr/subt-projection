package dev.subt.trainingwidget

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.text.InputType
import android.widget.EditText
import android.widget.HorizontalScrollView
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
class AppScreens(private val a: Activity, private val wDp: Int, private val onChange: () -> Unit = {}) {

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

    /** Three side-by-side numbers: label, big value, small caption. */
    private fun stats3(parent: LinearLayout, items: List<Triple<String, Pair<String, Int>, String>>) {
        val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        for ((lbl, value, cap) in items) {
            val c = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            c.addView(TextView(a).apply { text = lbl.uppercase(loc); letterSpacing = 0.08f; setTextColor(DIM); setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f) })
            c.addView(TextView(a).apply {
                text = value.first; setTextColor(value.second); setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            c.addView(TextView(a).apply { text = cap; setTextColor(FAINT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f) })
            r.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
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
        val dist = RaceDistance.of(race.distanceM, race.name)
        val history = Prefs.raceHistory(a)?.second.orEmpty()
        val preset = Prefs.taperPreset(a)
        val tt = Taper.targets(series, rd, race.category, dist, history, preset)
        val fatTarget = tt?.fatTarget

        if (window.size < 2) {
            val c = card(col)
            text(c, "Not enough fitness history yet to chart this race build-up.", 14f, DIM)
            return sv
        }
        val nowDay = s.days.lastOrNull()
        val eveTop = if (hasEve) window.last() else null
        if (eveTop != null) {
            val z = FormZone.of(eveTop.form)
            label(h, "Race day (morning)", DIM).apply { setPadding(0, dp(12), 0, 0) }
            stats3(h, listOf(
                Triple("Fitness", "${eveTop.ctl.roundToInt()}" to FormColors.FITNESS, nowDay?.let { "now ${it.ctl.roundToInt()}" } ?: ""),
                Triple("Fatigue", "${eveTop.atl.roundToInt()}" to FormColors.FATIGUE, nowDay?.let { "now ${it.atl.roundToInt()}" } ?: ""),
                Triple("Form", signed(eveTop.form.roundToInt()) to z.color, z.label),
            ))
        }
        val peakIdx = window.indices.maxBy { window[it].ctl }
        val peak = window[peakIdx]
        val floor = tt?.floor ?: peak.ctl * 0.9
        val ref = tt?.ref ?: peak.ctl
        val todayIdx = window.indexOfLast { it.date <= today.toString() }

        fun idxFor(dd: LocalDate) = window.indexOfFirst { it.date == dd.toString() }
        // Chart runs through race day itself; the race-morning values (end of the day before) get dots
        val chartDays = series.filter { it.date >= start && it.date <= rd.toString() }
        fun cIdx(dd: LocalDate) = chartDays.indexOfFirst { it.date == dd.toString() }
        val cps = listOf(
            "12 wk" to rd.minusWeeks(12), "3 wk" to rd.minusWeeks(3), "1 wk" to rd.minusWeeks(1),
            "race ${rd.format(dm)}" to rd,
        ).mapNotNull { (l, dd) -> cIdx(dd).takeIf { it >= 0 }?.let { it to l } }
        val chartToday = chartDays.indexOfLast { it.date <= today.toString() }
        val chartPeak = chartDays.indexOfFirst { it.date == peak.date }

        // Build-up chart
        val cc = card(col)
        label(cc, "Fitness build-up · last 3 months")
        image(cc, Charts.raceBuild(chartDays, chartToday, cps, chartPeak, floor, fatTarget, chartW(), dp(240), d, TILE,
            eveIndex = cIdx(eve).takeIf { it >= 0 }), 240)
        text(cc, spans(
            "━ Fitness " to FormColors.FITNESS, "(solid = done, dashed = planned)   " to FAINT,
            "━ Fatigue   " to FormColors.FATIGUE, "┅ fatigue target · fitness floor   " to DIM, "● race morning" to TEXT,
        ), 11f, top = 6)
        if (!hasEve) text(cc, "Your plan is projected only 12 weeks ahead, so race-day values appear once the race is within 12 weeks.", 12f, WARN, top = 6)

        // Checkpoints table
        val t = card(col)
        label(t, "Checkpoints · race day = morning (end of the day before)")
        row(t, listOf("" to FAINT, "FITNESS" to FAINT, "FATIGUE" to FAINT, "FORM" to FAINT, "% REF" to FAINT), header = true)
        divider(t)
        fun cpRow(name: String, dd: Day?, highlight: Boolean = false) {
            if (dd == null) return
            val z = FormZone.of(dd.form)
            val pct = (dd.ctl / ref * 100).roundToInt()
            val pctColor = when {
                !highlight -> DIM
                dd.ctl >= floor -> GOOD
                else -> BAD
            }
            val dl = date(dd.date)?.format(dm) ?: ""
            row(t, listOf(
                spans(name to (if (highlight) TEXT else DIM), "\n$dl" to FAINT) to TEXT,
                "${dd.ctl.roundToInt()}" to FormColors.FITNESS,
                "${dd.atl.roundToInt()}" to (if (highlight && tt != null) fatColor(dd.atl, tt) else FormColors.FATIGUE),
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

        // Targets from the formula: Fatigue is the target, Fitness a guardrail, Form the result
        val eveDay = if (hasEve) window.last() else null
        val tg = card(col)
        label(tg, "Race-morning targets · ${dist.label}, ${race.category.removePrefix("RACE_")} race")
        if (tt == null) {
            text(tg, "Not enough Fitness history before this race to set targets.", 14f, DIM, top = 4)
        } else {
            stats3(tg, listOf(
                Triple("Fatigue target", "${tt.fatTarget.roundToInt()}" to FormColors.FATIGUE, "range ${tt.fatLo.roundToInt()}–${tt.fatHi.roundToInt()}"),
                Triple("Fitness floor", "${tt.floor.roundToInt()}" to FormColors.FITNESS, "higher is fine"),
                Triple("Reference peak", "${tt.ref.roundToInt()}" to TEXT, "blended"),
            ))
            if (eveDay != null) {
                val okFit = eveDay.ctl >= tt.floor
                val fc = fatColor(eveDay.atl, tt)
                text(tg, spans(
                    "Projected: " to DIM,
                    "Fatigue ${eveDay.atl.roundToInt()} (${Taper.pct(eveDay.atl / tt.ref)}) " to fc, fatVerdict(eveDay.atl, tt) to fc,
                    "   Fitness ${eveDay.ctl.roundToInt()} (${Taper.pct(eveDay.ctl / tt.ref)}) " to TEXT, (if (okFit) "✓" else "⚠ below floor") to (if (okFit) GOOD else BAD),
                    "   → Form ${signed(eveDay.form.roundToInt())}" to FormZone.of(eveDay.form).color,
                ), 15f, top = 10)
            }
            // Show the working
            val f = DateTimeFormatter.ofPattern("d MMM", loc)
            text(tg, spans(
                "How it's worked out\n" to DIM,
                "Reference peak = 50% × 2-month peak ${tt.p60?.roundToInt() ?: "–"} + 30% × 3-month ${tt.p90?.roundToInt() ?: "–"} + 20% × 4-month ${tt.p120?.roundToInt() ?: "–"} = ${tt.ref.roundToInt()}\n" to FAINT,
                "Fatigue range = ${Taper.pct(tt.f - 0.04)}–${Taper.pct(tt.f + 0.03)} of reference, target at the low end (${Taper.pct(tt.f - 0.03)}). Centre ${Taper.pct(tt.f)}: ${race.category.removePrefix("RACE_")} race, ${dist.label.lowercase(loc)}, ${listOf("strict", "standard", "relaxed")[preset]} preset → ${Taper.pct(tt.baseF)}" to FAINT,
                (if (tt.personalF != null) ", blended 50/50 with your ${tt.personalN} improved race${if (tt.personalN > 1) "s" else ""} (${Taper.pct(tt.personalF)})" else ", not yet calibrated (load Past races)") to FAINT,
                "\nFitness floor = ${Taper.pct(tt.g)} of reference (by distance and priority)" to FAINT,
            ), 12f, top = 10)
            text(tg, "Form is Fitness minus Fatigue, so it follows from these two. Change the strictness with the chips above.", 12f, FAINT, top = 6)
        }

        // Coach notes
        val cn = card(col)
        label(cn, "Coach notes")
        coachNotes(cn, s, race, races, eveDay, peak, tt, daysTo)
        text(cn, "Rule-of-thumb guidance from your intervals.icu data. It isn't medical advice.", 11f, FAINT, top = 10)
        return sv
    }

    private fun coachNotes(
        c: LinearLayout, s: Snapshot, race: Race, races: List<Race>, eve: Day?, peak: Day,
        tt: Taper.Targets?, daysTo: Long,
    ) {
        val today = LocalDate.now()
        if (eve != null) {
            // Fatigue target first: it's what decides race-day freshness
            if (tt != null) {
                val ka = 1 - Math.exp(-1.0 / 7)
                when {
                    eve.atl > tt.fatHi -> {
                        // ATL falls by ~13 % of any load removed per day; spread a cut over the last 3 days
                        val excess = eve.atl - tt.fatTarget
                        val perDay = excess / (ka * (1 + (1 - ka) + (1 - ka) * (1 - ka)))
                        bullet(c, WARN, "Race-morning Fatigue ${eve.atl.roundToInt()} is above your ${tt.fatLo.roundToInt()}–${tt.fatHi.roundToInt()} range. " +
                            "Trimming about ${perDay.roundToInt()} load a day from the last 3 days (shorter or easier sessions, not total rest) would bring it to ${tt.fatTarget.roundToInt()}.")
                    }
                    eve.atl < tt.fatLo -> bullet(c, FormColors.FITNESS, "Race-morning Fatigue ${eve.atl.roundToInt()} is below the ${tt.fatLo.roundToInt()}–${tt.fatHi.roundToInt()} range, so you'd be fresher than you need to be. " +
                        "You could keep a little more steady running in the final week to protect Fitness.")
                    eve.atl > tt.fatTarget + 1 -> bullet(c, GOOD, "Race-morning Fatigue ${eve.atl.roundToInt()} is in your ${tt.fatLo.roundToInt()}–${tt.fatHi.roundToInt()} range but above the low-end target of ${tt.fatTarget.roundToInt()}. A slightly easier last 2–3 days would get you there.")
                    else -> bullet(c, GOOD, "Race-morning Fatigue ${eve.atl.roundToInt()} is at the low end of your ${tt.fatLo.roundToInt()}–${tt.fatHi.roundToInt()} range, right on target.")
                }
                if (eve.ctl < tt.floor) bullet(c, BAD, "Fitness ${eve.ctl.roundToInt()} would be under your ${tt.floor.roundToInt()} floor (${Taper.pct(tt.g)} of the ${tt.ref.roundToInt()} reference peak). The taper is too deep, so keep some volume and drop Fatigue with shorter, easier sessions rather than rest days.")
                else bullet(c, GOOD, "Fitness ${eve.ctl.roundToInt()} stays above the ${tt.floor.roundToInt()} floor. High Fitness is fine as long as Fatigue is managed.")
            }
            val form = eve.form.roundToInt()
            if (form < 0) bullet(c, WARN, "Form ${signed(form)}: you'd start the race carrying fatigue.")
            else if (form > 25) bullet(c, DIM, "Form ${signed(form)} is very fresh, and some runners feel flat at this level. A short opener with a few race-pace minutes 1–2 days before helps.")
        }
        // Fatigue: how much it sheds into the race
        if (eve != null) {
            val recent = (s.days + s.proj).filter { it.date >= LocalDate.parse(race.date).minusDays(21).toString() && it.date <= eve.date }
            val peakAtl = recent.maxByOrNull { it.atl }
            if (peakAtl != null && peakAtl.atl > 0) {
                val dropPct = (1 - eve.atl / peakAtl.atl) * 100
                val txt = "Fatigue falls from ${peakAtl.atl.roundToInt()} (${date(peakAtl.date)?.format(dm)}) to ${eve.atl.roundToInt()} by race morning, " +
                    "${dropPct.roundToInt()}% lower. That drop is where race-day freshness comes from."
                when {
                    eve.atl > eve.ctl -> bullet(c, WARN, "$txt Fatigue is still above Fitness, so you'd start tired. Ease off in the final days.")
                    dropPct < 25 -> bullet(c, WARN, "$txt That's a small drop. Shorter, easier runs in the last week would help more.")
                    else -> bullet(c, GOOD, txt)
                }
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
        val sleep = s.days.mapNotNull { it.sleepSecs?.div(60)?.takeIf { m -> m <= 14 * 60 } }
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

    private fun fatColor(atl: Double, tt: Taper.Targets) = when {
        atl > tt.fatHi -> WARN
        atl < tt.fatLo -> 0xFF5EC8FF.toInt()
        else -> GOOD
    }

    private fun fatVerdict(atl: Double, tt: Taper.Targets) = when {
        atl > tt.fatHi -> "⚠ above range"
        atl < tt.fatLo -> "fresher than needed"
        else -> "✓"
    }

    // ---------------------------------------------------------------- Past races

    fun pastRaces(col: LinearLayout, loading: Boolean, error: String?, onRefresh: () -> Unit) {
        val cached = Prefs.raceHistory(a)
        val top = card(col)
        label(top, "Past races · last 3 years")
        text(top, when {
            loading -> "Loading your races from intervals.icu…"
            error != null -> "Couldn't load: $error"
            cached != null -> "Updated ${android.text.format.DateFormat.getTimeFormat(a).format(java.util.Date(cached.first))} · tap to refresh"
            else -> "Tap to load"
        }, 13f, if (error != null) WARN else FAINT, top = 2).setOnClickListener { onRefresh() }
        val list = cached?.second.orEmpty().filter { it.seconds != null }
        if (cached == null) return
        if (list.isEmpty()) { text(top, "No past A/B/C races with a recorded run found.", 15f, DIM, top = 8); return }

        // Summary by distance
        val byDist = list.groupBy { RaceDistance.of(it.distanceM, it.name) }
        val sum = card(col)
        label(sum, "By distance")
        row(sum, listOf("DISTANCE" to FAINT, "RACES" to FAINT, "BEST" to FAINT, "LATEST" to FAINT, "CHANGE" to FAINT), header = true,
            weights = listOf(1.2f, 0.7f, 1.1f, 1.1f, 1.1f))
        divider(sum)
        for (dd in RaceDistance.entries) {
            val l = byDist[dd]?.sortedBy { it.date } ?: continue
            val best = l.minOf { it.seconds!! }
            val change = if (l.size >= 2) l.last().seconds!! - l.first().seconds!! else null
            row(sum, listOf(
                dd.label to TEXT, "${l.size}" to DIM, t(best) to GOOD, t(l.last().seconds!!) to TEXT,
                (change?.let { (if (it < 0) "−" else "+") + t(abs1(it)) } ?: "–") to (if (change != null && change < 0) GOOD else if (change != null) BAD else FAINT),
            ), weights = listOf(1.2f, 0.7f, 1.1f, 1.1f, 1.1f))
        }
        // Times chart for the distance raced most
        byDist.filterKeys { it != RaceDistance.OTHER }.maxByOrNull { it.value.size }?.takeIf { it.value.size >= 2 }?.let { (dd, l) ->
            val pts = l.mapNotNull { r -> date(r.date)?.let { it to r.seconds!! / 60 } }
            image(sum, Charts.timeChart(pts, pts.minOf { it.first }.minusDays(20), pts.maxOf { it.first }.plusDays(20), GOOD, chartW(), dp(150), d, TILE,
                scatter = false, invert = true, fmt = { m -> "%d:%02d".format(m.toInt(), ((m - m.toInt()) * 60).roundToInt()) }), 150)
            text(sum, "${dd.label} times (minutes), with faster plotted higher.", 12f, FAINT, top = 4)
        }

        // Each race, newest first, against the previous race at the same distance
        for (r in list.sortedByDescending { it.date }) {
            val dd = RaceDistance.of(r.distanceM, r.name)
            val prev = byDist[dd].orEmpty().filter { it.date < r.date }.maxByOrNull { it.date }.takeIf { dd != RaceDistance.OTHER }
            val c = card(col)
            label(c, "${date(r.date)?.format(edm) ?: r.date} ${date(r.date)?.year ?: ""} · ${r.category.removePrefix("RACE_")} race · ${dd.label}")
            text(c, r.name, 17f, bold = true, top = 2)
            val km = (r.distanceM ?: dd.metres) / 1000
            val pace = if (km > 0) r.seconds!! / km / 60 else null
            stats3(c, listOf(
                Triple("Time", t(r.seconds!!) to TEXT, pace?.let { "${Charts.paceText(it)} /km" } ?: ""),
                Triple("Avg HR", (r.avgHr?.roundToInt()?.toString() ?: "–") to 0xFFFF7A90.toInt(), String.format(loc, "%.2f km", km)),
                Triple("Race-morning Form", (if (r.eveCtl != null && r.eveAtl != null) signed((r.eveCtl - r.eveAtl).roundToInt()) else "–") to
                    (if (r.eveCtl != null && r.eveAtl != null) FormZone.of(r.eveCtl - r.eveAtl).color else DIM), ""),
            ))
            val refP = Taper.reference(r.peak60, r.peak90, r.peak120)
            if (r.eveCtl != null && r.eveAtl != null && refP != null) {
                text(c, spans(
                    "Fitness ${r.eveCtl.roundToInt()} (${Taper.pct(r.eveCtl / refP)} of ref peak ${refP.roundToInt()})" to FormColors.FITNESS,
                    "  ·  " to FAINT,
                    "Fatigue ${r.eveAtl.roundToInt()} (${Taper.pct(r.eveAtl / refP)})" to FormColors.FATIGUE,
                ), 13f, top = 6)
            }
            val prevSec = prev?.seconds
            if (prev != null && prevSec != null) {
                val delta = r.seconds!! - prevSec
                val per = if (km > 0) delta / km else 0.0
                text(c, spans(
                    (if (delta < 0) "▲ ${t(-delta)} faster" else if (delta > 0) "▼ ${t(delta)} slower" else "＝ same time") to (if (delta < 0) GOOD else if (delta > 0) BAD else DIM),
                    " than ${prev.name.take(30)} (${date(prev.date)?.format(dm)} ${date(prev.date)?.year}), ${if (per < 0) "−" else "+"}${abs1(per).roundToInt()} s/km" to DIM,
                ), 14f, top = 6)
                val pr = Taper.reference(prev.peak60, prev.peak90, prev.peak120)
                if (pr != null && refP != null && prev.eveAtl != null && r.eveAtl != null) {
                    text(c, "Going in: reference peak ${pr.roundToInt()} → ${refP.roundToInt()}, race-morning Fatigue ${Taper.pct(prev.eveAtl / pr)} → ${Taper.pct(r.eveAtl / refP)} of peak.", 12f, FAINT, top = 4)
                }
            } else if (dd != RaceDistance.OTHER) text(c, "Your first ${dd.label} in this period.", 12f, FAINT, top = 6)
        }
        text(card(col), "Races where you beat your previous time at that distance calibrate your Fatigue target on upcoming races.", 12f, FAINT)
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
            val raw = days.mapNotNull { dd -> dd.sleepSecs?.let { dd.date to it / 3600.0 } }
            // Nights over 14 h are tracking errors (watch left recording): leave them out
            val bad = raw.filter { it.second > 14 }
            val vals = raw.filter { it.second <= 14 }.map { date(it.first)!! to it.second }
            if (vals.isEmpty()) { text(c, "No sleep data in intervals.icu for this period.", 14f, DIM, top = 4); return@run }
            for ((bd, hrs) in bad) {
                val dl = date(bd)?.format(edm) ?: bd
                text(c, spans("Left out $dl: ${hm(hrs * 60)} recorded, likely a tracking error.  " to WARN, "Set to 8h in intervals.icu ›" to FormColors.FITNESS), 13f, top = 4)
                    .setOnClickListener { fixSleep(bd, hrs) }
            }
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

    /** Corrects a wrong sleep value in intervals.icu (after confirmation), then refreshes. */
    private fun fixSleep(dateStr: String, hrs: Double) {
        val creds = Prefs.credentials(a) ?: return
        AlertDialog.Builder(a)
            .setTitle("Fix sleep on ${date(dateStr)?.format(edm) ?: dateStr}?")
            .setMessage("intervals.icu has ${hm(hrs * 60)} of sleep for this night. Set it to 8h 00m in your intervals.icu wellness data?")
            .setPositiveButton("Set to 8 h") { _, _ ->
                Thread {
                    val r = runCatching { IcuApi(creds).updateWellness(dateStr, org.json.JSONObject().put("sleepSecs", 8 * 3600)) }
                    a.runOnUiThread {
                        android.widget.Toast.makeText(a, r.fold({ "Updated. Refreshing…" }, { "Couldn't update: ${it.message}" }), android.widget.Toast.LENGTH_LONG).show()
                        if (r.isSuccess) Refresh.now(a, replace = true)
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Asks for a race time as m:ss or h:mm:ss. */
    private fun askTime(title: String, current: Double?, onValue: (Double?) -> Unit) {
        val et = EditText(a).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "e.g. 20:45 or 1:32:10"
            setText(current?.let { t(it) } ?: "")
            setTextColor(TEXT)
        }
        AlertDialog.Builder(a).setTitle(title).setView(et)
            .setPositiveButton("Save") { _, _ ->
                val parts = et.text.toString().trim().split(":").mapNotNull { it.trim().toIntOrNull() }
                val sec = when (parts.size) { 2 -> parts[0] * 60 + parts[1]; 3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]; else -> null }
                onValue(sec?.toDouble()); onChange()
            }
            .setNeutralButton("Clear") { _, _ -> onValue(null); onChange() }
            .setNegativeButton("Cancel", null).show()
    }

    /** Your actual bests (intervals.icu or entered) plus the race-gap choice they feed. */
    private fun pbCard(col: LinearLayout, cur: Double) {
        val pbs = Prefs.effectivePbs(a)
        val c = card(col)
        label(c, "Your bests · last 12 months")
        fun cap(p: Pb?) = p?.let { listOfNotNull(it.date?.let { d -> date(d)?.format(dm) }, it.name?.take(18)).joinToString(" · ") } ?: "tap Edit to add"
        stats3(c, listOf(
            Triple("5K", (pbs.fiveK?.let { t(it.seconds) } ?: "–") to GOOD, cap(pbs.fiveK)),
            Triple("10K", (pbs.tenK?.let { t(it.seconds) } ?: "–") to TEXT, cap(pbs.tenK)),
            Triple("Half", (pbs.half?.let { t(it.seconds) } ?: "–") to TEXT, cap(pbs.half)),
        ))
        val edits = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
        for ((key, lbl) in listOf("5k" to "5K", "10k" to "10K", "hm" to "Half")) {
            edits.addView(TextView(a).apply {
                text = "Edit $lbl"; setTextColor(FormColors.FITNESS); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setOnClickListener { askTime("Your $lbl PB", Prefs.manualPb(a, key)) { Prefs.setManualPb(a, key, it) } }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        c.addView(edits)
        if (Prefs.pbs(a) == null) text(c, "Still loading PBs from intervals.icu. Tap ⟳ on any screen, or enter them with Edit.", 12f, FAINT, top = 4)
        if (pbs.fiveK != null || pbs.half != null) {
            val usePb = Prefs.gapFromPb(a)
            SubtModel.applyGap(a)
            text(c, spans(
                "Calibrated race gaps: " to DIM,
                "5K ${fmt1(SubtModel.GAP * 100)}% · half ${fmt1(SubtModel.GAP_HM * 100)}% · marathon ${fmt1(SubtModel.GAP_FM * 100)}%" to TEXT,
                (if (usePb) "  (from your PBs at the SubT you had then; the page assumes 11%)" else "  (page default)") to FAINT,
            ), 13f, top = 10)
            chipRow(c, listOf("Use my PBs", "Page default (11%)"), if (usePb) 0 else 1) {
                Prefs.setGapFromPb(a, it == 0); SubtModel.applyGap(a); onChange()
            }
            text(c, "Predictions are never shown slower than a PB you've already run.", 12f, FAINT, top = 6)
        }
    }

    // ---------------------------------------------------------------- SubT extras (below the widget card)

    fun subtExtras(col: LinearLayout) {
        val sub = Prefs.subt(a) ?: return
        val months = sub.months.filter { !it.outlier }
        val cur = months.lastOrNull()?.pace ?: return
        SubtModel.applyGap(a)
        pbCard(col, cur)
        val pb5 = Prefs.effectivePbs(a).fiveK

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
            val byPb = pb5 != null && pb5.seconds < sec
            val done = byPb || cur <= need
            val r = SubtModel.arrival(longR, need)
            val f = SubtModel.arrival(longF, need)
            val sl = SubtModel.arrival(longS, need)
            fun m(x: Int?) = x?.let { now.plusMonths(it.toLong()).format(my) } ?: ">5 yr"
            row(c, listOf(
                name to (if (done) GOOD else TEXT),
                Charts.paceText(need) to DIM,
                (if (byPb) "✓ PB ${t(pb5!!.seconds)}" else if (done) "✓ now" else m(r)) to (if (done) GOOD else Charts.SUBT),
                (if (done) "" else "${m(f)} – ${m(sl)}") to FAINT,
            ), weights = listOf(1.3f, 0.9f, 1.1f, 1.4f))
        }
        text(c, "Same model as your projection page: Daniels VDOT, ${fmt1(SubtModel.GAP * 100)}% race gap (${if (Prefs.gapFromPb(a) && pb5 != null) "from your PB" else "page default"}; half/marathon ${fmt1(SubtModel.GAP_HM * 100)}/${fmt1(SubtModel.GAP_FM * 100)}%), rates per month of −12 s/km while rebuilding to 4:32, then −2 (realistic; fast −3, slow −1) down to 4:00. Overrides you set on the page aren't visible here.", 12f, FAINT, top = 8)
    }

    // ---------------------------------------------------------------- shared controls

    private fun chipRow(parent: LinearLayout, items: List<String>, selected: Int, onPick: (Int) -> Unit) {
        val hs = HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false }
        val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        items.forEachIndexed { i, t ->
            r.addView(TextView(a).apply {
                text = t
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(if (i == selected) TEXT else DIM)
                setBackgroundResource(if (i == selected) R.drawable.chip_on else R.drawable.chip_off)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener { onPick(i) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        }
        hs.addView(r)
        parent.addView(hs)
    }

    private fun askNumber(title: String, current: Double?, onValue: (Double?) -> Unit) {
        val et = EditText(a).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(current?.let { String.format(Locale.US, "%.1f", it) } ?: "")
            setTextColor(TEXT)
        }
        AlertDialog.Builder(a).setTitle(title).setView(et)
            .setPositiveButton("Save") { _, _ -> onValue(et.text.toString().replace(',', '.').toDoubleOrNull()); onChange() }
            .setNeutralButton("Clear") { _, _ -> onValue(null); onChange() }
            .setNegativeButton("Cancel", null).show()
    }

    private val my = DateTimeFormatter.ofPattern("MMM yy", loc)
    private fun monthLabel(m: Int) = LocalDate.now().withDayOfMonth(1).plusMonths(m.toLong()).format(my)
    private fun t(sec: Double) = SubtModel.time(sec)

    /** Weight inputs: current (override or intervals.icu), goal floor and selected plan. */
    private data class WeightIn(val w0: Double?, val fromIcu: Boolean, val goal: Double, val plan: SubtModel.WeightPlan, val planIndex: Int)

    private fun weightIn(): WeightIn {
        val icu = Prefs.snapshot(a)?.weight
        val ov = Prefs.weightOverride(a)
        val w0 = ov ?: icu
        val goal = Prefs.goalWeight(a) ?: (w0?.minus(5) ?: 0.0)
        val i = Prefs.weightPlan(a).coerceIn(0, SubtModel.WEIGHT_PLANS.lastIndex)
        return WeightIn(w0, ov == null && icu != null, goal, SubtModel.WEIGHT_PLANS[i], i)
    }

    private fun weightCard(col: LinearLayout, cur: Double, need: Double) {
        val w = weightIn()
        val c = card(col)
        label(c, "Body weight scenario")
        if (w.w0 == null) {
            text(c, "No weight found in intervals.icu. Tap to enter yours so the weight scenarios work.", 14f, DIM, top = 4)
                .setOnClickListener { askNumber("Your weight (kg)", null) { Prefs.setWeightOverride(a, it) } }
            return
        }
        val now = text(c, spans("Now ${fmt1(w.w0)} kg" to TEXT, (if (w.fromIcu) "  (intervals.icu) · edit" else "  (entered) · edit") to FAINT), 15f, top = 4)
        now.setOnClickListener { askNumber("Your weight (kg)", w.w0) { Prefs.setWeightOverride(a, it) } }
        val g = text(c, spans("Goal / floor ${fmt1(w.goal)} kg" to TEXT, "  · edit (losing stops here)" to FAINT), 15f, top = 2)
        g.setOnClickListener { askNumber("Goal weight (kg)", w.goal) { v -> if (v != null) Prefs.setGoalWeight(a, v) } }
        chipRow(c, SubtModel.WEIGHT_PLANS.map { it.label }, w.planIndex) { Prefs.setWeightPlan(a, it); onChange() }

        row(c, listOf("PLAN" to FAINT, "IN 6 MO" to FAINT, "IN 12 MO" to FAINT, "SUBT 12 MO" to FAINT, "SUB-3" to FAINT), header = true,
            weights = listOf(1.5f, 1f, 1f, 1.1f, 1.1f))
        divider(c)
        for ((i, p) in SubtModel.WEIGHT_PLANS.withIndex()) {
            val path = SubtModel.pathWithWeight(cur, SubtModel.REAL, 72, w.w0, p.kgPerWeek, w.goal)
            val eta = SubtModel.arrival(path, need)
            val sel = i == w.planIndex
            row(c, listOf(
                p.label to (if (sel) TEXT else DIM),
                "${fmt1(SubtModel.weightAt(w.w0, p.kgPerWeek, 6.0, w.goal))} kg" to DIM,
                "${fmt1(SubtModel.weightAt(w.w0, p.kgPerWeek, 12.0, w.goal))} kg" to DIM,
                Charts.paceText(path[12]) to Charts.SUBT,
                (eta?.let { monthLabel(it) } ?: ">6 yr") to (if (sel) GOOD else DIM),
            ), weights = listOf(1.5f, 1f, 1f, 1.1f, 1.1f))
        }
        text(c, "Assumes fat-only change: about 0.7% faster per 1% of body weight lost (roughly 2–3 s/km per kg), the usual rule of thumb. " +
            "Losing more than about 0.5 kg/week while training hard risks low energy availability (RED-S), injury and lost fitness, so fuel your key sessions.", 12f, FAINT, top = 8)
    }

    private data class SubtNow(val months: List<SubtMonth>, val cur: Double)

    private fun subtNow(col: LinearLayout): SubtNow? {
        val sub = Prefs.subt(a)
        val months = sub?.months.orEmpty().filter { !it.outlier }
        val cur = months.lastOrNull()?.pace
        if (cur == null) { text(card(col), "Loading SubT data…", 16f); return null }
        return SubtNow(months, cur)
    }

    // ---------------------------------------------------------------- Sub-3 forecast

    fun sub3Forecast(col: LinearLayout) {
        val sn = subtNow(col) ?: return
        SubtModel.applyGap(a)
        val cur = sn.cur
        val w = weightIn()
        val need = SubtModel.requiredSubt(3, SubtModel.SUB3)
        val real = SubtModel.pathWithWeight(cur, SubtModel.REAL, 72, w.w0, w.plan.kgPerWeek, w.goal)
        val fast = SubtModel.pathWithWeight(cur, SubtModel.FAST, 72, w.w0, w.plan.kgPerWeek, w.goal)
        val slow = SubtModel.pathWithWeight(cur, SubtModel.SLOW, 72, w.w0, w.plan.kgPerWeek, w.goal)
        val eR = SubtModel.arrival(real, need); val eF = SubtModel.arrival(fast, need); val eS = SubtModel.arrival(slow, need)

        pbCard(col, cur)
        val h = card(col)
        label(h, "Sub-3 marathon forecast · weight: ${w.plan.label.lowercase(loc)}")
        text(h, spans((eR?.let { monthLabel(it) } ?: "Beyond 6 years") to GOOD, "  realistic" to DIM), 26f, bold = true, top = 4)
        text(h, spans("Fast ${eF?.let { monthLabel(it) } ?: ">6 yr"}" to TEXT, "  ·  " to FAINT, "Slow ${eS?.let { monthLabel(it) } ?: ">6 yr"}" to TEXT), 15f, top = 2)
        stats3(h, listOf(
            Triple("SubT now", Charts.paceText(cur) to Charts.SUBT, "/km"),
            Triple("Sub-3 needs", Charts.paceText(need) to TEXT, "SubT /km"),
            Triple("Gap", "${((cur - need) * 60).roundToInt()} s" to WARN, "per km to find"),
        ))
        text(h, spans(
            "Sub-3 equivalents: " to DIM, "5K ${t(SubtModel.fiveK(need))} · HM ${t(SubtModel.half(need))}" to TEXT,
        ), 14f, top = 10)
        Charts.subtTrendPerMonth(sn.months)?.takeIf { it < 0 }?.let { slope ->
            val months = ((cur - need) / -slope).roundToInt()
            text(h, spans(
                "If your last-6-month trend (${(slope * 60).roundToInt()} s/km/mo) simply continued: " to DIM, monthLabel(months) to TEXT,
                ". That's optimistic, because gains slow as you get fitter, which the model allows for." to FAINT,
            ), 13f, top = 8)
        }

        // Chart
        val horizon = ((eS ?: eR ?: 48) + 4).coerceIn(12, 72)
        val c = card(col)
        label(c, "SubT path to sub-3")
        val fmGoals = listOf(3 * 3600 + 30 * 60.0 to "3:30 FM", 3 * 3600 + 15 * 60.0 to "3:15 FM", SubtModel.SUB3 to "Sub-3")
            .map { (sec, n) -> n to SubtModel.requiredSubt(3, sec) }
        image(c, Charts.subtProjection(sn.months.takeLast(12), real.take(horizon + 1), fast.take(horizon + 1), slow.take(horizon + 1), fmGoals,
            chartW(), dp(230), d, TILE), 230)
        text(c, spans("━ history  " to Charts.SUBT, "┅ realistic  " to Charts.SUBT, "▇ fast–slow  " to FAINT, "┄ marathon goal paces" to WARN), 11f, top = 6)

        // Month-by-month expectations
        val tb = card(col)
        label(tb, "What to expect each month (realistic)")
        row(tb, listOf("MONTH" to FAINT, "SUBT" to FAINT, "5K" to FAINT, "HM" to FAINT, "FM" to FAINT), header = true, weights = listOf(1f, 0.9f, 1f, 1.2f, 1.2f))
        divider(tb)
        val last = minOf(real.lastIndex, (eR ?: 36) + 3)
        val step = if (last > 30) 3 else 1
        var shownSub3 = false
        for (m in 0..last step step) {
            val p = real[m]
            val fm = SubtModel.full(p)
            val hit = fm < SubtModel.SUB3 + 1
            val first = hit && !shownSub3
            if (hit) shownSub3 = true
            row(tb, listOf(
                monthLabel(m) to (if (first) GOOD else DIM),
                Charts.paceText(p) to Charts.SUBT,
                t(SubtModel.fiveK(p)) to TEXT,
                t(SubtModel.half(p)) to TEXT,
                t(fm) to (if (hit) GOOD else TEXT),
            ), weights = listOf(1f, 0.9f, 1f, 1.2f, 1.2f))
        }
        text(tb, "These are the race times your SubT supports each month, using Daniels VDOT plus your race-readiness gaps (5K ${fmt1(SubtModel.GAP * 100)}%, half ${fmt1(SubtModel.GAP_HM * 100)}%, marathon ${fmt1(SubtModel.GAP_FM * 100)}%), never slower than your PBs. A 5K at or under the time shown means you're on or ahead of the curve.", 12f, FAINT, top = 8)

        weightCard(col, cur, need)
    }

    // ---------------------------------------------------------------- Sub-3 plan (work back from a date)

    private fun defaultMarathonDate(): LocalDate {
        val today = LocalDate.now()
        val year = if (today.monthValue >= 11) today.year + 1 else today.year
        return LocalDate.of(year, 11, 1).with(java.time.temporal.TemporalAdjusters.firstInMonth(java.time.DayOfWeek.SUNDAY))
    }

    fun sub3Plan(col: LinearLayout) {
        val sn = subtNow(col) ?: return
        SubtModel.applyGap(a)
        val cur = sn.cur
        val w = weightIn()
        val today = LocalDate.now()
        val race = Prefs.marathonDate(a)?.let { date(it) } ?: defaultMarathonDate()
        val target = Prefs.marathonTarget(a).toDouble()
        val months = ChronoUnit.DAYS.between(today, race) / 30.44
        val need = SubtModel.requiredSubt(3, target)

        // Controls
        val ctl = card(col)
        label(ctl, "Target")
        val targets = listOf(SubtModel.SUB3.toInt(), 3 * 3600 + 5 * 60, 3 * 3600 + 10 * 60, 3 * 3600 + 15 * 60, 3 * 3600 + 30 * 60)
        chipRow(ctl, targets.map { if (it == SubtModel.SUB3.toInt()) "Sub-3" else t(it.toDouble()).dropLast(3) }, targets.indexOf(target.toInt())) {
            Prefs.setMarathonTarget(a, targets[it]); onChange()
        }
        val dt = text(ctl, spans("Race date: " to DIM, race.format(edm) + " ${race.year}" to TEXT, "  · change" to FAINT), 15f, top = 8)
        dt.setOnClickListener {
            DatePickerDialog(a, { _, y, m, dd -> Prefs.setMarathonDate(a, LocalDate.of(y, m + 1, dd).toString()); onChange() },
                race.year, race.monthValue - 1, race.dayOfMonth).apply { datePicker.minDate = System.currentTimeMillis() + 30L * 86400000 }.show()
        }

        if (months < 1) { text(ctl, "Pick a date at least a month away.", 14f, WARN, top = 6); return }

        // Weight contribution
        val wRace = w.w0?.let { SubtModel.weightAt(it, w.plan.kgPerWeek, months, w.goal) }
        val factor = if (w.w0 != null && wRace != null) SubtModel.weightFactor(w.w0, wRace) else 1.0
        val needAtCurrentWeight = need / factor
        val fromWeight = (needAtCurrentWeight - need) * 60
        val fromTraining = (cur - needAtCurrentWeight) * 60
        val perMonth = fromTraining / months

        // Feasibility vs the model
        val mInt = months.roundToInt().coerceAtLeast(1)
        val realAt = SubtModel.path(cur, SubtModel.REAL, mInt)[mInt]
        val fastAt = SubtModel.path(cur, SubtModel.FAST, mInt)[mInt]
        val (verdict, vc) = when {
            cur <= needAtCurrentWeight -> "Already there" to GOOD
            realAt <= needAtCurrentWeight -> "On track with realistic progress" to GOOD
            fastAt <= needAtCurrentWeight -> "Stretch: needs fast-rate progress" to WARN
            else -> "Unlikely by this date" to BAD
        }
        val h = card(col)
        label(h, "${t(target)} marathon on ${race.format(dm)} ${race.year} · in ${fmt1(months)} months")
        text(h, verdict, 22f, vc, bold = true, top = 4)
        stats3(h, listOf(
            Triple("SubT now", Charts.paceText(cur) to Charts.SUBT, "/km"),
            Triple("Race-day SubT", Charts.paceText(need) to TEXT, "needed /km"),
            Triple("Per month", "${String.format(loc, "%.1f", perMonth)} s" to (if (perMonth <= 2.2) GOOD else if (perMonth <= 3.2) WARN else BAD), "from training"),
        ))
        text(h, spans(
            "Gap ${((cur - need) * 60).roundToInt()} s/km = " to DIM,
            "${fromWeight.roundToInt()} s from weight (${w.plan.label.lowercase(loc)}${wRace?.let { ", ${fmt1(it)} kg on race day" } ?: ""})" to TEXT,
            " + " to DIM, "${fromTraining.roundToInt()} s from training" to TEXT,
        ), 14f, top = 10)
        text(h, "For reference, the model expects about −2 s/km per month at your level (fast −3, slow −1), slowing to −1.2 below 4:00.", 12f, FAINT, top = 6)
        if (verdict == "Unlikely by this date") {
            val path = SubtModel.pathWithWeight(cur, SubtModel.REAL, 72, w.w0, w.plan.kgPerWeek, w.goal)
            SubtModel.arrival(path, need)?.let { text(h, "Earliest realistic month for this target: ${monthLabel(it)}.", 14f, WARN, top = 6) }
        }

        // Monthly targets
        val tb = card(col)
        label(tb, "Monthly checkpoints to stay on track")
        row(tb, listOf("MONTH" to FAINT, "SUBT" to FAINT, "5K" to FAINT, "HM" to FAINT, "WEIGHT" to FAINT), header = true, weights = listOf(1f, 0.9f, 1f, 1.2f, 1f))
        divider(tb)
        val steps = (0..mInt).toList()
        for (m in steps) {
            val frac = (m / months).coerceAtMost(1.0)
            val trainPace = cur - (cur - needAtCurrentWeight) * frac
            val wm = w.w0?.let { SubtModel.weightAt(it, w.plan.kgPerWeek, m.toDouble(), w.goal) }
            val p = if (w.w0 != null && wm != null) trainPace * SubtModel.weightFactor(w.w0, wm) else trainPace
            val isRace = m == mInt
            row(tb, listOf(
                (if (isRace) "Race" else monthLabel(m)) to (if (isRace) GOOD else DIM),
                Charts.paceText(p) to Charts.SUBT,
                t(SubtModel.fiveK(p)) to TEXT,
                t(SubtModel.half(p)) to TEXT,
                (wm?.let { "${fmt1(it)}" } ?: "–") to DIM,
            ), weights = listOf(1f, 0.9f, 1f, 1.2f, 1f))
        }
        text(tb, "SubT = the median pace your SubT reps should average that month (HR kept under your 172 ceiling). " +
            "5K and HM are the race times that SubT supports. Run a 5K or a hard parkrun every 4–6 weeks: matching the 5K column means you're on schedule.", 12f, FAINT, top = 8)

        val g = card(col)
        label(g, "How to train for it")
        bullet(g, FormColors.FITNESS, "SubT sessions 2–3 a week (e.g. 3×10 min, 5×6 min or 10×3 min) at this month's SubT pace, at least 48 h apart, with everything else easy.")
        bullet(g, FormColors.FITNESS, "Volume builds the marathon. Grow weekly load by roughly 5–8% at most, using the Week screen, with a lighter week every 3–4 weeks.")
        bullet(g, FormColors.FITNESS, "From 12–16 weeks out, add marathon-pace work (${t(target / 42.195).let { it }}/km) inside long runs. The SubT plan keeps the engine growing.")
        bullet(g, WARN, "Re-check monthly: if your SubT median falls behind the table for two months, move the date or the target rather than forcing pace.")

        weightCard(col, cur, need)
    }

    // ---------------------------------------------------------------- SubT analysis

    fun subtAnalysis(col: LinearLayout, reps: List<SubtRep>?, fetchedAt: Long?, loading: Boolean, error: String?, onRefresh: () -> Unit) {
        val top = card(col)
        label(top, "SubT analysis · last 5 weeks")
        val status = when {
            loading -> "Loading SubT laps from intervals.icu (one request per run, so this takes a few seconds)…"
            error != null -> "Couldn't load: $error"
            fetchedAt != null -> "Updated ${android.text.format.DateFormat.getTimeFormat(a).format(java.util.Date(fetchedAt))} · tap to refresh"
            else -> "Tap to load"
        }
        text(top, status, 13f, if (error != null) WARN else FAINT, top = 2).setOnClickListener { onRefresh() }
        text(top, "Laps count as SubT with your projection page's filter: average HR over 160, max HR up to 172, 1.5–15 min long.", 12f, FAINT, top = 4)
        if (reps == null) return
        if (reps.isEmpty()) { text(top, "No SubT laps found in the last 5 weeks.", 15f, DIM, top = 8); return }

        val sessions = reps.groupBy { it.activityId }.values.sortedBy { it.first().date }
        val latest = sessions.last().sortedBy { it.index }
        val allPaces = reps.map { it.pace }.sorted()
        val medPace = allPaces[allPaces.size / 2]
        val allHr = reps.map { it.avgHr }.sorted()
        val medHr = allHr[allHr.size / 2]

        // ---- Latest session
        val ls = card(col)
        val ld = date(latest.first().date)
        label(ls, "Latest session · ${ld?.format(edm) ?: latest.first().date}")
        text(ls, latest.first().activityName, 17f, bold = true, top = 2)
        val totalMin = latest.sumOf { it.seconds } / 60.0
        val avgPace = latest.sumOf { it.pace * it.seconds } / latest.sumOf { it.seconds }
        val avgHr = latest.sumOf { it.avgHr * it.seconds } / latest.sumOf { it.seconds }
        stats3(ls, listOf(
            Triple("SubT time", "${totalMin.roundToInt()}′" to TEXT, "${latest.size} reps"),
            Triple("Avg pace", Charts.paceText(avgPace) to Charts.SUBT, "5-wk median ${Charts.paceText(medPace)}"),
            Triple("Avg HR", "${avgHr.roundToInt()}" to 0xFFFF7A90.toInt(), "5-wk median ${medHr.roundToInt()}"),
        ))
        val hasW = latest.any { it.watts != null }
        val cols = mutableListOf("#" to FAINT, "TIME" to FAINT, "PACE" to FAINT, "HR" to FAINT, "MAX" to FAINT)
        if (hasW) cols += "W" to FAINT
        cols += "M/BEAT" to FAINT
        row(ls, cols, header = true, weights = List(cols.size) { if (it == 0) 0.5f else 1f })
        divider(ls)
        for (r in latest) {
            val cells = mutableListOf<Pair<CharSequence, Int>>(
                "${r.index}" to FAINT, "%d:%02d".format(r.seconds / 60, r.seconds % 60) to DIM,
                Charts.paceText(r.pace) to Charts.SUBT, "${r.avgHr.roundToInt()}" to TEXT,
                "${r.maxHr.roundToInt()}" to (if (r.maxHr >= 171) WARN else DIM),
            )
            if (hasW) cells += (r.watts?.roundToInt()?.toString() ?: "–") to DIM
            cells += String.format(loc, "%.2f", r.efficiency) to DIM
            row(ls, cells, weights = List(cells.size) { if (it == 0) 0.5f else 1f })
        }
        text(ls, "M/BEAT = metres per minute per heartbeat (speed ÷ HR). Higher means more speed for the same cardiac effort.", 12f, FAINT, top = 6)

        val notes = card(col)
        label(notes, "Coach notes · this session")
        val first = latest.first(); val lastR = latest.last()
        val hrRise = lastR.avgHr - first.avgHr
        val paceChange = (lastR.pace / first.pace - 1) * 100   // + = slower
        val (pm, psd) = stats(latest.map { it.pace })
        val cv = psd / pm * 100
        val peak = latest.maxOf { it.maxHr }
        when {
            totalMin < 20 -> bullet(notes, WARN, "Short SubT dose (${totalMin.roundToInt()} min). Sub-threshold sessions usually accumulate about 25–40 min (3×10, 5×6 or 10×3 min) to give a real training stimulus.")
            totalMin > 45 -> bullet(notes, WARN, "Big dose (${totalMin.roundToInt()} min of SubT). Make the next day genuinely easy.")
            else -> bullet(notes, GOOD, "${totalMin.roundToInt()} min at SubT, a solid dose in the usual 25–40 min range.")
        }
        if (latest.size >= 2) when {
            hrRise > 6 && paceChange > -1 -> bullet(notes, WARN, "HR crept up ${hrRise.roundToInt()} bpm from first to last rep without faster running. That's cardiac drift from heat, dehydration, fatigue or a pace a touch too hot. Start 2–3 s/km slower next time.")
            paceChange < -2 && hrRise > 4 -> bullet(notes, DIM, "You sped up ${fmt1(-paceChange)}% through the session and HR rose with it. Fine if you planned to, but SubT works best kept even and controlled.")
            hrRise <= 3 && abs1(paceChange) <= 1.5 -> bullet(notes, GOOD, "HR stayed within ${hrRise.roundToInt().coerceAtLeast(0)} bpm across reps at a steady pace. That's well-controlled sub-threshold work.")
            else -> bullet(notes, DIM, "HR ${if (hrRise >= 0) "+" else ""}${hrRise.roundToInt()} bpm and pace ${if (paceChange >= 0) "+" else ""}${fmt1(paceChange)}% first to last rep. A normal amount of drift.")
        }
        if (peak >= 171) bullet(notes, WARN, "Peak HR reached ${peak.roundToInt()}, right at your 172 ceiling. Sub-threshold should sit below lactate threshold, so ease off slightly to keep lactate controlled.")
        if (latest.size >= 3) {
            if (cv < 1.5) bullet(notes, GOOD, "Even pacing: reps within ±${fmt1(cv)}%.")
            else if (cv > 3) bullet(notes, WARN, "Uneven pacing (±${fmt1(cv)}%). Aim for reps within 2–3 s/km of each other.")
        }
        val faster = avgPace < medPace - 0.01
        val lowerHr = avgHr <= medHr + 1
        if (faster && lowerHr) bullet(notes, GOOD, "Faster than your 5-week median (${Charts.paceText(medPace)}) at the same or lower HR, a sign of adaptation.")
        else if (!faster && avgHr > medHr + 3) bullet(notes, WARN, "Slower than usual at a higher HR. You may be tired; check the Recovery tab before the next hard day.")

        // ---- 5 weeks trends
        val start = LocalDate.now().minusDays(35)
        val today = LocalDate.now()
        val tr = card(col)
        label(tr, "Trends · every SubT rep, last 5 weeks")
        val weeks = 5.0
        val mins = reps.sumOf { it.seconds } / 60.0
        stats3(tr, listOf(
            Triple("Sessions", "${sessions.size}" to TEXT, String.format(loc, "%.1f / week", sessions.size / weeks)),
            Triple("SubT time", "${mins.roundToInt()}′" to TEXT, "${(mins / weeks).roundToInt()}′ / week"),
            Triple("Reps", "${reps.size}" to TEXT, "median ${Charts.paceText(medPace)}"),
        ))
        fun pts(f: (SubtRep) -> Double?) = reps.mapNotNull { r -> f(r)?.let { v -> date(r.date)?.let { it to v } } }
        fun slopePerMonth(p: List<Pair<LocalDate, Double>>): Double? {
            if (p.size < 4) return null
            val xs = p.map { it.first.toEpochDay().toDouble() }; val ys = p.map { it.second }
            val mx = xs.average(); val myy = ys.average()
            val den = xs.sumOf { (it - mx) * (it - mx) }
            if (den == 0.0) return null
            return xs.indices.sumOf { (xs[it] - mx) * (ys[it] - myy) } / den * 30
        }
        val paceP = pts { it.pace }
        label(tr, "Pace per rep").apply { setPadding(0, dp(12), 0, 0) }
        image(tr, Charts.timeChart(paceP, start, today, Charts.SUBT, chartW(), dp(140), d, TILE, scatter = true, invert = true,
            fmt = { Charts.paceText(it) }), 140)
        slopePerMonth(paceP)?.let { sl ->
            val sec = (sl * 60)
            text(tr, if (sec < -0.5) "Getting faster: ${fmt1(-sec)} s/km per month." else if (sec > 0.5) "Getting slower: ${fmt1(sec)} s/km per month. Check fatigue, heat or terrain." else "Pace steady.", 13f, if (sec < -0.5) GOOD else if (sec > 0.5) WARN else DIM, top = 4)
        }
        val hrP = pts { it.avgHr }
        label(tr, "Heart rate per rep").apply { setPadding(0, dp(12), 0, 0) }
        image(tr, Charts.timeChart(hrP, start, today, 0xFFFF7A90.toInt(), chartW(), dp(140), d, TILE, scatter = true, threshold = 172.0,
            fmt = { it.roundToInt().toString() }), 140)
        text(tr, "Dashed line = your 172 bpm ceiling.", 12f, FAINT, top = 4)
        val effP = pts { it.efficiency }
        label(tr, "Speed per heartbeat (efficiency)").apply { setPadding(0, dp(12), 0, 0) }
        image(tr, Charts.timeChart(effP, start, today, GOOD, chartW(), dp(140), d, TILE, scatter = true,
            fmt = { String.format(loc, "%.2f", it) }), 140)
        slopePerMonth(effP)?.let { sl ->
            val pct = sl / effP.map { it.second }.average() * 100
            text(tr, "${if (pct >= 0) "+" else ""}${fmt1(pct)}% per month. " + when {
                pct >= 1 -> "More speed for the same HR: your SubT engine is improving."
                pct <= -1.5 -> "Less speed per beat: often accumulated fatigue, heat or illness."
                else -> "Holding steady."
            }, 13f, if (pct >= 1) GOOD else if (pct <= -1.5) WARN else DIM, top = 4)
        }
        val powerP = pts { r -> r.watts?.let { it / r.avgHr } }
        if (powerP.size >= 4) {
            label(tr, "Power : HR per rep").apply { setPadding(0, dp(12), 0, 0) }
            image(tr, Charts.timeChart(powerP, start, today, FormColors.FITNESS, chartW(), dp(140), d, TILE, scatter = true,
                fmt = { String.format(loc, "%.2f", it) }), 140)
            slopePerMonth(powerP)?.let { sl ->
                val pct = sl / powerP.map { it.second }.average() * 100
                text(tr, "${if (pct >= 0) "+" else ""}${fmt1(pct)}% per month in watts per beat. Unlike pace, power ignores wind and hills, so this is the cleaner signal.", 13f, if (pct >= 0) GOOD else WARN, top = 4)
            }
        }

        // ---- Program notes
        val pn = card(col)
        label(pn, "Coach notes · last 5 weeks")
        val perWeek = sessions.size / weeks
        when {
            perWeek < 1.5 -> bullet(pn, WARN, "${String.format(loc, "%.1f", perWeek)} SubT sessions a week. Most sub-threshold programmes use 2–3 a week, which is what drives the steady monthly gains the projection assumes.")
            perWeek > 3.5 -> bullet(pn, WARN, "${String.format(loc, "%.1f", perWeek)} SubT sessions a week is a lot. Make sure the rest stays truly easy.")
            else -> bullet(pn, GOOD, "${String.format(loc, "%.1f", perWeek)} SubT sessions a week, right in the 2–3 range.")
        }
        val dates = sessions.mapNotNull { date(it.first().date) }
        val tight = dates.zipWithNext().count { (x, y) -> ChronoUnit.DAYS.between(x, y) < 2 }
        if (tight > 0) bullet(pn, WARN, "$tight time(s) SubT sessions were on back-to-back days. Leave at least 48 h between them so each one gets absorbed.")
        val capHits = reps.count { it.maxHr >= 171 }
        if (capHits > reps.size / 4) bullet(pn, WARN, "$capHits of ${reps.size} reps touched 171–172 bpm. SubT is being run a little hot, and a few s/km slower would keep it sub-threshold.")
        else bullet(pn, GOOD, "Only $capHits of ${reps.size} reps reached the top of your HR ceiling, so intensity is well controlled.")

        val why = card(col)
        label(why, "Why these matter")
        text(why, "• Sub-threshold training (the \"Norwegian singles\" approach) keeps reps just under lactate threshold. You can accumulate more quality time with less fatigue than at or above threshold, then repeat it 2–3 times a week.\n" +
            "• Cardiac drift: at a steady pace, rising HR reflects heat, dehydration and fatigue, a long-established exercise-physiology finding. Over a whole run, under about 5% decoupling is a common guide (Friel).\n" +
            "• Daniels' threshold (T) pace is \"comfortably hard\", roughly what you could hold for about an hour. SubT sits a little slower.\n" +
            "• More speed per heartbeat at the same effort over weeks is one of the clearest signs aerobic fitness is improving.", 13f, DIM, top = 4)
    }

    private fun abs1(v: Double) = if (v < 0) -v else v

    // ================================================================ Home cards

    /** Push / Hold / Back off with the reasons behind it. */
    fun verdictCard(col: LinearLayout) {
        val v = Verdict.compute(Prefs.snapshot(a)) ?: return
        val c = card(col)
        val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(TextView(a).apply {
            text = v.call.label.uppercase(loc); setTextColor(v.call.color); letterSpacing = 0.08f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        r.addView(TextView(a).apply { text = "   " + v.headline; setTextColor(DIM); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(r)
        val reasons = TextView(a).apply {
            text = spans(*v.reasons.flatMapIndexed { i, (col2, t) -> listOf((if (i > 0) "  ·  " else "") to FAINT, t to col2) }.toTypedArray())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f); setPadding(0, dp(6), 0, 0); setLineSpacing(dpf(2f).toFloat(), 1f)
        }
        c.addView(reasons)
    }

    /** Today's planned sessions with their power steps (app only). */
    fun todayPowerCard(col: LinearLayout) {
        val s = Prefs.snapshot(a) ?: return
        val todo = s.workouts.filter { it.status != WorkoutStatus.DONE }
        if (todo.isEmpty()) return
        val c = card(col)
        label(c, "Today's targets")
        val cp = PowerModel.now(a)?.cp
        for (w in todo) {
            text(c, spans(WidgetRenderer.sportIcon(w.type) + "  " to TEXT, w.name to TEXT, "   " + WidgetRenderer.meta(w) to FAINT), 15f, top = 8)
            val steps = w.steps
            if (steps != null) text(c, steps, 13.5f, Charts.SUBT, top = 2)
            else if (cp != null && Regex("sub.?t|threshold", RegexOption.IGNORE_CASE).containsMatchIn(w.name))
                text(c, "SubT reps ≈ ${(cp * 0.88).roundToInt()}–${(cp * 0.92).roundToInt()} W (88–92% of CP ${cp.roundToInt()} W), HR under 172", 13.5f, Charts.SUBT, top = 2)
        }
    }

    /** Quiet weekly checklist: key sessions and load against plan. */
    fun weekChecklist(col: LinearLayout) {
        val s = Prefs.snapshot(a) ?: return
        val wk = s.week
        if (wk.isEmpty()) return
        val c = card(col)
        label(c, "This week")
        fun matches(w: Workout, re: Regex) = re.containsMatchIn(w.name)
        val subt = Regex("sub.?t|threshold", RegexOption.IGNORE_CASE)
        val strength = Regex("gym|strength|weights", RegexOption.IGNORE_CASE)
        fun line(name: String, done: Int, total: Int, caption: String = "") {
            val r = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
            val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            val complete = total > 0 && done >= total
            top.addView(TextView(a).apply { text = name; setTextColor(if (complete) DIM else TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(TextView(a).apply {
                text = (if (caption.isNotEmpty()) "$caption  " else "") + (if (total > 0) "$done / $total" else "–") + if (complete) "  ✓" else ""
                setTextColor(if (complete) GOOD else FAINT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
            r.addView(top)
            // hairline progress
            val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
            val frac = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
            bar.addView(View(a).apply { setBackgroundColor(if (complete) 0x993DD68C.toInt() else 0x994EA1FF.toInt()) }, LinearLayout.LayoutParams(0, dp(2), frac.coerceAtLeast(0.001f)))
            bar.addView(View(a).apply { setBackgroundColor(0xFF2C3240.toInt()) }, LinearLayout.LayoutParams(0, dp(2), (1 - frac).coerceAtLeast(0.001f)))
            r.addView(bar)
            c.addView(r)
        }
        val subtAll = wk.filter { matches(it, subt) }
        line("SubT sessions", subtAll.count { it.status == WorkoutStatus.DONE }, subtAll.size)
        val longDone = wk.any { it.status == WorkoutStatus.DONE && it.type.contains("Run", true) && it.seconds >= 75 * 60 }
        val longPlanned = wk.any { it.type.contains("Run", true) && it.seconds >= 75 * 60 }
        line("Long run (75′+)", if (longDone) 1 else 0, if (longPlanned || longDone) 1 else 0)
        val str = wk.filter { matches(it, strength) }
        line("Strength", str.count { it.status == WorkoutStatus.DONE }, str.size)
        val loadDone = wk.filter { it.status == WorkoutStatus.DONE }.sumOf { it.load }
        val loadPlan = wk.sumOf { if (it.status == WorkoutStatus.DONE) maxOf(it.plannedLoad, it.load) else it.load }
        line("Load", loadDone, loadPlan, "vs plan")
    }

    /** One-line streak summary for home. */
    fun streakLine(col: LinearLayout) {
        val st = Streaks.compute(a) ?: return
        val c = card(col)
        label(c, "Load-balance streak")
        text(c, spans("${st.dayStreak}" to TEXT, " day${if (st.dayStreak == 1) "" else "s"}" to DIM, "   ·   " to FAINT,
            "${st.weekStreak}" to TEXT, " week${if (st.weekStreak == 1) "" else "s"}" to DIM, "   ·   " to FAINT,
            "${st.monthStreak}" to TEXT, " month${if (st.monthStreak == 1) "" else "s"}" to DIM), 18f, top = 2)
        st.thisWeek?.let { dayDots(c, it.days, LocalDate.now()) }
    }

    private fun statusColor(st: Streaks.Status) = when (st) {
        Streaks.Status.GREEN, Streaks.Status.TAPER_OK -> GOOD
        Streaks.Status.AMBER -> WARN
        Streaks.Status.RED, Streaks.Status.TAPER_MISS -> BAD
        Streaks.Status.EXEMPT -> 0xFF5EC8FF.toInt()
        Streaks.Status.MINI -> 0xFF8C9EFF.toInt()
        Streaks.Status.NONE -> 0xFF2C3240.toInt()
    }

    private fun dayDots(parent: LinearLayout, days: List<Streaks.DayResult>, today: LocalDate) {
        val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        val monday = days.firstOrNull()?.date ?: return
        for (i in 0 until 7) {
            val dd = monday.plusDays(i.toLong())
            val res = days.firstOrNull { it.date == dd }
            val cell = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            cell.addView(TextView(a).apply {
                text = if (res == null) "○" else "●"; gravity = Gravity.CENTER
                setTextColor(if (res == null) 0xFF2C3240.toInt() else statusColor(res.status)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            })
            cell.addView(TextView(a).apply {
                text = dd.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, loc); gravity = Gravity.CENTER
                setTextColor(if (dd == today) TEXT else FAINT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            })
            r.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        parent.addView(r)
    }

    // ================================================================ Streaks tab

    fun streaks(col: LinearLayout) {
        val st = Streaks.compute(a)
        val top = card(col)
        label(top, "Load-balance streaks")
        if (st == null) { text(top, "Needs about two months of Fitness history. Loading…", 14f, DIM, top = 4); return }
        stats3(top, listOf(
            Triple("Day streak", "${st.dayStreak}" to GOOD, "best ${st.bestDayStreak}"),
            Triple("Week streak", "${st.weekStreak}" to GOOD, "best ${st.bestWeekStreak}"),
            Triple("Month streak", "${st.monthStreak}" to GOOD, "${st.months.count { it.passed == true }} passed"),
        ))
        text(top, spans("This week so far: " to DIM, (st.thisWeek?.avgForm?.let { "avg Form ${signed(it.roundToInt())}" } ?: "–") to
            (st.thisWeek?.avgForm?.let { if (it in -10.0..5.0) GOOD else WARN } ?: TEXT)), 13.5f, top = 6)
        st.thisWeek?.let { dayDots(top, it.days, LocalDate.now()) }

        // Rules + adjusters
        val rules = card(col)
        label(rules, "Your rules")
        val band = Prefs.fitnessBand(a); val fat = Prefs.fatigueMin(a)
        text(rules, spans("Day streak (relaxed) passes when:" to TEXT), 14f, bold = true, top = 6)
        text(rules, "• Fitness stays within $band% of your reference peak\n• the 3-day average Form is −15 to +8, so one rest or recovery day is fine but several in a row aren't\n• TT mini-taper, taper and race days pass if the floor holds", 13.5f, DIM, top = 2)
        text(rules, spans("Week (Mon–Sun) passes when:" to TEXT), 14f, bold = true, top = 12)
        text(rules, "• average Form across the week is in the Grey zone (−10 to +5)\n" +
            "• average Fatigue is at least $fat% of average Fitness\n" +
            "• you exit the week (Sunday) with Fitness within $band% of your reference peak and Form in the Grey zone too (unless Sunday is a TT or race)", 13.5f, DIM, top = 2)
        chipRow(rules, listOf(5, 10, 15, 20).map { "Fitness within $it%" }, listOf(5, 10, 15, 20).indexOf(band)) { Prefs.setFitnessBand(a, listOf(5, 10, 15, 20)[it]); onChange() }
        chipRow(rules, listOf(90, 95, 100, 105).map { "Fatigue ≥ $it%" }, listOf(90, 95, 100, 105).indexOf(fat)) { Prefs.setFatigueMin(a, listOf(90, 95, 100, 105)[it]); onChange() }
        text(rules, spans("Month passes when:" to TEXT), 14f, bold = true, top = 12)
        text(rules, "• average Form is −12 to +5\n• exit Fitness is at least 97% of entry (90% in a race month)\n• no more than one week failed", 13.5f, DIM, top = 2)
        text(rules, spans("Lighter rules:" to TEXT), 14f, bold = true, top = 12)
        text(rules, "• TT mini-taper (2 days before a scheduled TT, the TT day and the day after): left out of the Form and Fatigue averages, but the Fitness floor still applies\n" +
            "• Race taper (A 14 · B 10 · C 5 days): judged only on Fitness staying above the race floor. Race day and the 3 days after aren't judged", 13.5f, DIM, top = 2)
        text(rules, spans("Day dots: " to FAINT, "● all build rules  " to GOOD, "● day streak OK  " to WARN, "● day streak broken  " to BAD, "● TT mini-taper  " to 0xFF8C9EFF.toInt(), "● race / taper" to 0xFF5EC8FF.toInt()), 11.5f, top = 10)
        text(rules, "Dots follow the day streak. Week and month results come from averages and how you exit.", 11.5f, FAINT, top = 2)

        // Weeks, newest first
        val wc = card(col)
        label(wc, "Weeks")
        for (w in st.weeks.reversed()) {
            val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
            head.addView(TextView(a).apply { text = "${w.monday.format(dm)} – ${w.monday.plusDays(6).format(dm)}"; setTextColor(TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(TextView(a).apply {
                text = when { !w.complete -> "in progress"; w.passed == true -> "✓ passed"; w.passed == false -> "missed"; else -> "not judged" }
                setTextColor(when { !w.complete -> DIM; w.passed == true -> GOOD; w.passed == false -> BAD; else -> FAINT }); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            })
            wc.addView(head)
            dayDots(wc, w.days, LocalDate.now())
            val facts = listOfNotNull(
                w.kind.takeIf { it != "build week" },
                w.avgForm?.let { "avg Form ${signed(it.roundToInt())}" },
                w.avgRatio?.let { "Fatigue ${(it * 100).roundToInt()}% of Fitness" },
                if (w.exitCtl != null && w.floor != null) "exit Fitness ${w.exitCtl.roundToInt()} (floor ${w.floor.roundToInt()})" else null,
                w.exitForm?.let { "exit Form ${signed(it.roundToInt())}" },
            )
            text(wc, facts.joinToString(" · "), 11.5f, FAINT, top = 2)
            if (w.reasons.isNotEmpty() && w.passed == false) text(wc, w.reasons.joinToString(" · "), 11.5f, WARN, top = 1)
        }
        // Months
        val mc = card(col)
        label(mc, "Months")
        for (m in st.months.reversed()) {
            row(mc, listOf(
                m.month.format(DateTimeFormatter.ofPattern("MMMM yyyy", loc)) to TEXT,
                (when (m.passed) { true -> "✓ passed"; false -> "missed"; null -> "in progress" }) to (when (m.passed) { true -> GOOD; false -> BAD; null -> FAINT }),
            ), weights = listOf(1.4f, 1f))
            val parts = listOfNotNull(
                m.avgForm?.let { "avg Form ${signed(it.roundToInt())}" },
                if (m.entryCtl != null && m.exitCtl != null) "Fitness ${m.entryCtl.roundToInt()} → ${m.exitCtl.roundToInt()} (${if (m.exitCtl >= m.entryCtl) "+" else "−"}${abs1((m.exitCtl / m.entryCtl - 1) * 100).roundToInt()}%)" else null,
                "${m.failedWeeks} week${if (m.failedWeeks == 1) "" else "s"} failed",
                if (m.hadRace) "race month" else null,
            )
            text(mc, parts.joinToString(" · "), 11.5f, FAINT, top = 0)
        }
    }

    // ================================================================ Power tab

    fun power(col: LinearLayout, view: Int) {
        val p = PowerModel.now(a)
        val s = Prefs.snapshot(a)
        if (p == null || s == null) {
            val c = card(col)
            label(c, "Power")
            text(c, "No running power found yet. Make sure Stryd (or Garmin) power syncs to intervals.icu, and set your running FTP or CP under intervals.icu Settings → Run.", 14f, DIM, top = 4)
            return
        }
        when (view) {
            0 -> powerDashboard(col, p, s)
            1 -> powerTrends(col, p, s)
            2 -> powerZones(col, s)
            else -> powerRace(col, p)
        }
        text(card(col), "Power comes from intervals.icu's primary power source (CP source: ${p.cpSource}). Stryd and Garmin read about 20% apart, so keep one source, ideally Stryd, as primary.", 12f, FAINT)
    }

    private fun fmtW(v: Double?) = v?.let { "${it.roundToInt()} W" } ?: "–"
    private fun delta(now: Double, then: Double?, unit: String = "W") =
        then?.let { val dd = now - it; (if (dd >= 0) "+" else "−") + "${abs1(dd).roundToInt()} $unit" } ?: ""

    private fun powerDashboard(col: LinearLayout, p: PowerModel.Now, s: Snapshot) {
        val c = card(col)
        label(c, "Power dashboard")
        stats3(c, listOf(
            Triple("CP", "${p.cp.roundToInt()}" to FormColors.FITNESS, delta(p.cp, p.cp6wAgo).takeIf { it.isNotEmpty() }?.let { "W · $it in 6 wk" } ?: "W"),
            Triple("W/kg", (p.wkg?.let { String.format(loc, "%.2f", it) } ?: "–") to TEXT, p.kg?.let { "${fmt1(it)} kg" } ?: "add weight"),
            Triple("RE", (p.re?.let { String.format(loc, "%.3f", it) } ?: "–") to GOOD, "speed per W/kg"),
        ))
        text(c, spans("Weight ${p.kg?.let { "${fmt1(it)} kg" } ?: "not set"}" to DIM,
            (if (Prefs.weightOverride(a) != null) "  (yours) · update ›" else "  (intervals.icu) · enter yours ›") to FormColors.FITNESS), 13f, top = 4)
            .setOnClickListener { askNumber("Your weight today (kg)", p.kg) { Prefs.setWeightOverride(a, it) } }
        val longDrift = s.runs.filter { it.seconds >= 75 * 60 && it.decoupling != null }.takeLast(5).map { it.decoupling!! }
        val reps = Prefs.subtReps(a)?.second.orEmpty().filter { it.watts != null }
        val subtW = reps.mapNotNull { it.watts }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        stats3(c, listOf(
            Triple("Power : HR", (p.powerHr?.let { String.format(loc, "%.2f", it) } ?: "–") to TEXT, "W per beat · 10 runs"),
            Triple("Durability", (if (longDrift.isEmpty()) "–" else "${fmt1(longDrift.average())}%") to (if (longDrift.isEmpty()) DIM else if (longDrift.average() < 5) GOOD else WARN), "drift on 75′+ runs"),
            Triple("SubT power", fmtW(subtW) to Charts.SUBT, subtW?.let { "${(it / p.cp * 100).roundToInt()}% of CP" } ?: "load Analysis"),
        ))
        // Reading
        val n = card(col)
        label(n, "What it says")
        p.slopePerMonth?.let { sl ->
            bullet(n, if (sl > 0.5) GOOD else if (sl < -0.5) WARN else DIM, "CP ${if (sl >= 0) "rising" else "falling"} ${fmt1(abs1(sl))} W/month over the last 90 days" +
                (p.cp3moAgo?.let { " (${delta(p.cp, it)} vs 3 months ago)" } ?: "") + ".")
        }
        p.re?.let { re ->
            val reRuns = s.runs.mapNotNull { it.re(p.kg) }
            val ch = if (reRuns.size >= 10) (reRuns.takeLast(5).average() / reRuns.take(5).average() - 1) * 100 else null
            bullet(n, if (ch == null) DIM else if (ch >= 0) GOOD else WARN, "Running effectiveness ${String.format(loc, "%.3f", re)}" +
                (ch?.let { ", ${if (it >= 0) "up" else "down"} ${fmt1(abs1(it))}% over 90 days" } ?: "") +
                ". Judge RE by its trend rather than its absolute level: Garmin power usually gives about 0.85–0.90 and Stryd about 1.0. Strides, hills, strength and cadence work tend to lift it.")
        }
        if (longDrift.isNotEmpty()) bullet(n, if (longDrift.average() < 5) GOOD else WARN,
            if (longDrift.average() < 5) "Power:HR drift under 5% on long runs, which is good marathon durability." else "Power:HR drift ${fmt1(longDrift.average())}% on long runs. Build durability with longer easy runs and late-run steady segments.")
        subtW?.let { w -> val pct = w / p.cp * 100
            bullet(n, if (pct in 86.0..93.0) GOOD else WARN, "SubT reps at ${pct.roundToInt()}% of CP. Sub-threshold usually sits at 88–92%${if (pct > 93) ", so you're running them a bit hot" else if (pct < 86) ", so you could press a little" else ""}.") }
        // Predictions
        powerPredictions(col, p)
    }

    private fun powerPredictions(col: LinearLayout, p: PowerModel.Now) {
        val re = p.re; val kg = p.kg
        val c = card(col)
        label(c, "Race predictions from power")
        if (re == null || kg == null) { text(c, "Needs your weight and a few runs with power to work out running effectiveness.", 14f, DIM, top = 4); return }
        val fm = PowerModel.fmShare(a)
        SubtModel.applyGap(a)
        val months = Prefs.subt(a)?.months.orEmpty().filter { !it.outlier }
        val subt = months.lastOrNull()?.pace
        val pbs = Prefs.effectivePbs(a)
        row(c, listOf("" to FAINT, "POWER" to FAINT, "@ W" to FAINT, "VDOT" to FAINT, "PB" to FAINT), header = true, weights = listOf(0.9f, 1.1f, 0.8f, 1.1f, 1.1f))
        divider(c)
        val rows = listOf(Triple("5K", 5000.0, 1.04), Triple("10K", 10000.0, 1.00), Triple("Half", 21097.5, 0.94), Triple("Marathon", 42195.0, fm))
        for ((name, dist, share) in rows) {
            val tp = PowerModel.time(dist, p.cp, share, re, kg)
            val vd = subt?.let { when (name) { "5K" -> SubtModel.fiveK(it); "Half" -> SubtModel.half(it); "Marathon" -> SubtModel.full(it); else -> null } }
            val pb = when (name) { "5K" -> pbs.fiveK; "10K" -> pbs.tenK; "Half" -> pbs.half; else -> null }?.seconds
            row(c, listOf(name to TEXT, t(tp) to FormColors.FITNESS, "${(p.cp * share).roundToInt()}" to DIM, (vd?.let { t(it) } ?: "–") to Charts.SUBT, (pb?.let { t(it) } ?: "–") to GOOD),
                weights = listOf(0.9f, 1.1f, 0.8f, 1.1f, 1.1f))
        }
        text(c, "Power: time = distance ÷ (RE × W/kg at race power). Race power as % of CP: 5K 104, 10K 100, half 94, marathon ${(fm * 100).roundToInt()} (set in Race model). Where power and VDOT disagree, the gap is usually RE or durability.", 12f, FAINT, top = 8)
    }

    private fun powerTrends(col: LinearLayout, p: PowerModel.Now, s: Snapshot) {
        val today = LocalDate.now(); val start = today.minusDays(89)
        fun pts(f: (RunMetric) -> Double?) = s.runs.mapNotNull { r -> f(r)?.let { v -> date(r.date)?.let { it to v } } }
        val charts = listOf(
            Triple("CP / eFTP (W)", pts { it.cp }, FormColors.FITNESS),
            Triple("W/kg (CP)", pts { r -> r.cp?.let { cp -> p.kg?.let { cp / it } } }, GOOD),
            Triple("Running effectiveness", pts { it.re(p.kg) }, 0xFF5EC8FF.toInt()),
            Triple("Power : HR (W per beat)", pts { r -> r.watts?.let { w -> r.avgHr?.let { w / it } } }, 0xFFFF7A90.toInt()),
            Triple("Average power per run (W)", pts { it.watts }, Charts.SUBT),
        )
        for ((title, data, color) in charts) {
            val c = card(col)
            label(c, title, color)
            if (data.size < 2) { text(c, "Not enough runs with this value yet.", 13f, DIM, top = 4); continue }
            val first = data.take(5).map { it.second }.average(); val last = data.takeLast(5).map { it.second }.average()
            val ch = (last / first - 1) * 100
            text(c, spans(String.format(loc, if (last < 10) "%.2f" else "%.0f", last) to TEXT, "  recent  ·  ${if (ch >= 0) "+" else ""}${fmt1(ch)}% over 90 days" to (if (ch >= 0) GOOD else WARN)), 16f, top = 2)
            image(c, Charts.timeChart(data, start, today, color, chartW(), dp(140), d, TILE, scatter = data.size > 12,
                fmt = { v -> if (v < 10) String.format(loc, "%.2f", v) else v.roundToInt().toString() }), 140)
        }
    }

    private fun powerZones(col: LinearLayout, s: Snapshot) {
        val since = LocalDate.now().minusDays(27).toString()
        val runs = s.runs.filter { it.date >= since && it.zoneSecs.isNotEmpty() }
        val c = card(col)
        label(c, "Time in power zones · last 4 weeks")
        if (runs.isEmpty()) { text(c, "intervals.icu hasn't sent power-zone times for recent runs. Check your power zones are set under Settings → Run.", 14f, DIM, top = 4); return }
        val n = runs.maxOf { it.zoneSecs.size }
        val totals = IntArray(n); runs.forEach { r -> r.zoneSecs.forEachIndexed { i, v -> totals[i] += v } }
        val all = totals.sum().coerceAtLeast(1)
        val colors = listOf(0xFF5EC8FF, 0xFF3DD68C, 0xFFFFC53D, 0xFFFF9F5A, 0xFFFF7A7A, 0xFFC17BFF, 0xFFFF5CA8).map { it.toInt() }
        for (i in 0 until n) {
            val pct = totals[i] * 100.0 / all
            val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
            r.addView(TextView(a).apply { text = "Z${i + 1}"; setTextColor(TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) }, LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.WRAP_CONTENT))
            val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            bar.addView(View(a).apply { setBackgroundColor(colors[i % colors.size]) }, LinearLayout.LayoutParams(0, dp(8), pct.toFloat().coerceAtLeast(0.2f)))
            bar.addView(View(a).apply { setBackgroundColor(0xFF2C3240.toInt()) }, LinearLayout.LayoutParams(0, dp(8), (100 - pct).toFloat().coerceAtLeast(0.2f)))
            r.addView(bar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(TextView(a).apply { text = "  ${pct.roundToInt()}% · ${totals[i] / 60}′"; setTextColor(DIM); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f) }, LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
            c.addView(r)
        }
        val low = (totals.getOrElse(0) { 0 } + totals.getOrElse(1) { 0 }) * 100.0 / all
        text(c, "Zones are your intervals.icu power zones. Easy (Z1–Z2) is ${low.roundToInt()}% of your time. " +
            (if (low >= 75) "That's a healthy base for sub-threshold training." else "Most SubT-based plans keep 75–80% easy, so the easy days may be drifting too hard."), 12.5f, if (low >= 75) GOOD else WARN, top = 10)
    }

    private fun powerRace(col: LinearLayout, p: PowerModel.Now) {
        val re = p.re; val kg = p.kg
        val c = card(col)
        label(c, "Sub-3 by power")
        val fmPct = Prefs.fmPct(a)
        chipRow(c, listOf(84, 86, 88, 90).map { "Marathon at $it% CP" }, listOf(84, 86, 88, 90).indexOf(fmPct)) { Prefs.setFmPct(a, listOf(84, 86, 88, 90)[it]); onChange() }
        if (re == null || kg == null) { text(c, "Needs your weight and runs with power.", 14f, DIM, top = 8); return }
        val share = fmPct / 100.0
        val need = PowerModel.cpFor(SubtModel.SUB3, share, re, kg)
        val gap = need - p.cp
        stats3(c, listOf(
            Triple("CP now", "${p.cp.roundToInt()} W" to FormColors.FITNESS, String.format(loc, "%.2f W/kg", p.cp / kg)),
            Triple("Sub-3 needs", "${need.roundToInt()} W" to TEXT, String.format(loc, "%.2f W/kg", need / kg)),
            Triple("Gap", (if (gap <= 0) "✓" else "+${gap.roundToInt()} W") to (if (gap <= 0) GOOD else WARN), "marathon @ ${(need * share).roundToInt()} W"),
        ))
        // ETA from the CP trend, with the weight plan
        val w = weightIn()
        val slope = p.slopePerMonth
        if (gap > 0 && slope != null && slope > 0.1) {
            var m = 0; var hit: Int? = null
            while (m <= 72) {
                val w0 = w.w0
                val kgM = if (w0 != null) SubtModel.weightAt(w0, w.plan.kgPerWeek, m.toDouble(), w.goal) * kg / w0 else kg
                if (p.cp + slope * m >= PowerModel.cpFor(SubtModel.SUB3, share, re, kgM)) { hit = m; break }
                m++
            }
            text(c, spans("At your current CP trend (+${fmt1(slope)} W/month) and weight plan (${w.plan.label.lowercase(loc)}): " to DIM,
                (hit?.let { monthLabel(it) } ?: "beyond 6 years") to GOOD), 15f, top = 8)
        } else if (gap > 0) text(c, "Your CP isn't trending up over the last 90 days, so there's no ETA yet. The levers below show what moves it.", 14f, WARN, top = 8)

        // What each lever is worth on marathon time
        val base = PowerModel.time(42195.0, p.cp, share, re, kg)
        fun diff(x: Double) = (base - x).let { (if (it >= 0) "−" else "+") + t(abs1(it)) }
        val lv = card(col)
        label(lv, "What each lever is worth (marathon time)")
        row(lv, listOf("+5 W CP" to TEXT, diff(PowerModel.time(42195.0, p.cp + 5, share, re, kg)) to GOOD), weights = listOf(1.5f, 1f))
        row(lv, listOf("+1% running effectiveness" to TEXT, diff(PowerModel.time(42195.0, p.cp, share, re * 1.01, kg)) to GOOD), weights = listOf(1.5f, 1f))
        row(lv, listOf("−1 kg body weight" to TEXT, diff(PowerModel.time(42195.0, p.cp, share, re, kg - 1)) to GOOD), weights = listOf(1.5f, 1f))
        row(lv, listOf("+1% durability (race at a higher % of CP)" to TEXT, diff(PowerModel.time(42195.0, p.cp, share + 0.01, re, kg)) to GOOD), weights = listOf(1.5f, 1f))
        text(lv, "Predicted marathon now: ${t(base)} at ${(p.cp * share).roundToInt()} W. Compare it with the Sub-3 forecast (VDOT) on the SubT tab.", 12.5f, FAINT, top = 8)
        weightCard(col, Prefs.subt(a)?.months?.lastOrNull { !it.outlier }?.pace ?: 4.5, SubtModel.requiredSubt(3, SubtModel.SUB3))
    }
}
