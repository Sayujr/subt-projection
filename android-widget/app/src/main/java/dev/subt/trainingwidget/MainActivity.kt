package dev.subt.trainingwidget

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.ScrollView
import android.widget.TextView
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The app: a home screen showing the full widget at full-screen size, plus one screen each for
 * This week, Race, Recovery and SubT. Each screen is the matching widget rendered large, so the
 * app and the home-screen widgets always look the same. Today and Recovery get period chips;
 * Week can step through weeks / months or show 3 months, 6 months or a custom range.
 */
class MainActivity : Activity() {

    private enum class Tab(val icon: String, val label: String, val title: String) {
        HOME("◉", "Today", "Training Today"),
        WEEK("▥", "Week", "Training load"),
        STREAKS("✦", "Streaks", "Streaks"),
        RACE("⚑", "Race", "Next race"),
        POWER("ϟ", "Power", "Power"),
        RECOVERY("♥", "Recovery", "Recovery"),
        SUBT("⏱", "SubT", "SubT pace"),
    }

    private enum class RangeMode(val label: String) {
        WEEK("Week"), MONTH("Month"), M3("3 months"), M6("6 months"), CUSTOM("Custom…")
    }

    private var tab = Tab.HOME
    private lateinit var content: FrameLayout
    private lateinit var controls: HorizontalScrollView
    private lateinit var chips: LinearLayout
    private val tabViews = mutableMapOf<Tab, TextView>()

    // Week screen state
    private var mode = RangeMode.WEEK
    private var offset = 0
    private var customStart: LocalDate? = null
    private var customEnd: LocalDate? = null
    private val rangeCache = mutableMapOf<String, List<Workout>>()
    private val loading = mutableSetOf<String>()
    private var rangeError: String? = null
    private var raceIndex = 0
    private var racePast = false
    private var histLoading = false
    private var histError: String? = null

    // SubT sub-screens
    private enum class SubtView(val label: String) { PACE("Pace"), ANALYSIS("Analysis"), FORECAST("Sub-3 forecast"), PLAN("Sub-3 plan") }
    private var subtView = SubtView.PACE
    private var powerView = 0

    // Streak calendar
    private var calendarOpen = false
    private var calendarMulti = true
    private var calendarYear = LocalDate.now().year
    private var histLongLoading = false
    private var histLongError: String? = null
    private var repsLoading = false
    private var repsError: String? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "snapshot") rangeCache.clear()
        if (key == "snapshot" || key == "subt" || key == "refreshing_since" || key == "error") {
            content.post { drawContent() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        content = findViewById(R.id.content)
        controls = findViewById(R.id.controls)
        chips = findViewById(R.id.chips)
        savedInstanceState?.let { b ->
            b.getString("tab")?.let { name -> Tab.entries.firstOrNull { it.name == name }?.let { tab = it } }
            b.getString("mode")?.let { name -> RangeMode.entries.firstOrNull { it.name == name }?.let { mode = it } }
            offset = b.getInt("offset")
            b.getString("cs")?.let { customStart = LocalDate.parse(it) }
            b.getString("ce")?.let { customEnd = LocalDate.parse(it) }
        }
        if (savedInstanceState == null) selectFromIntent(intent)

        findViewById<View>(R.id.open_settings).setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        buildTabs()

        if (Prefs.credentials(this) == null) {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }

    /** A tap on a home-screen widget opens the app on that widget's screen. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (selectFromIntent(intent)) show()
    }

    private fun selectFromIntent(intent: Intent?): Boolean {
        val name = intent?.getStringExtra(EXTRA_TAB) ?: return false
        val t = Tab.entries.firstOrNull { it.name == name } ?: return false
        tab = t
        return true
    }

    companion object {
        const val EXTRA_TAB = "tab"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("tab", tab.name)
        outState.putString("mode", mode.name)
        outState.putInt("offset", offset)
        customStart?.let { outState.putString("cs", it.toString()) }
        customEnd?.let { outState.putString("ce", it.toString()) }
    }

    override fun onResume() {
        super.onResume()
        getSharedPreferences("training_widget", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefsListener)
        Refresh.schedulePeriodic(this)
        val snap = Prefs.snapshot(this)
        val stale = snap == null || System.currentTimeMillis() - snap.fetchedAt > 15 * 60_000 ||
            snap.today != LocalDate.now().toString()
        if ((stale && Prefs.credentials(this) != null) || Prefs.subt(this) == null) Refresh.now(this, replace = false)
        show()
    }

    override fun onPause() {
        getSharedPreferences("training_widget", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onPause()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun buildTabs() {
        val bar = findViewById<LinearLayout>(R.id.tabs)
        for (t in Tab.entries) {
            val tv = TextView(this).apply {
                text = "${t.icon}\n${t.label}"
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setLineSpacing(0f, 1.15f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener { tab = t; show() }
            }
            tabViews[t] = tv
            bar.addView(tv)
        }
    }

    /** Updates tabs, title and chips, then draws the screen once the layout has settled. */
    private fun show() {
        tabViews.forEach { (t, tv) -> tv.setTextColor(if (t == tab) 0xFF4EA1FF.toInt() else 0xFF6B7386.toInt()) }
        findViewById<TextView>(R.id.screen_title).text = tab.title
        buildChips()
        content.post { drawContent() }
    }

    // ---------------------------------------------------------------- chips

    private fun chip(label: String, selected: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(if (selected) 0xFFF2F4F8.toInt() else 0xFF9AA3B2.toInt())
        setBackgroundResource(if (selected) R.drawable.chip_on else R.drawable.chip_off)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginEnd = dp(6) }
        setOnClickListener { onClick() }
    }

    private fun buildChips() {
        chips.removeAllViews()
        when (tab) {
            Tab.HOME, Tab.RECOVERY -> {
                controls.visibility = View.VISIBLE
                val current = Prefs.period(this, WidgetRenderer.APP_ID)
                for (days in Periods.ALL) {
                    chips.addView(chip(Periods.label(days), days == current) { setAppPeriod(days) })
                }
                val custom = current !in Periods.ALL
                val label = if (custom) "From ${LocalDate.now().minusDays(current - 1L).format(DateTimeFormatter.ofPattern("d MMM yy", Locale.getDefault()))}" else "Custom…"
                chips.addView(chip(label, custom) {
                    pickDate(LocalDate.now().minusDays(current - 1L), max = LocalDate.now().minusDays(6)) { start ->
                        setAppPeriod((ChronoUnit.DAYS.between(start, LocalDate.now()) + 1).toInt().coerceIn(7, 1100))
                    }
                })
            }
            Tab.WEEK -> {
                controls.visibility = View.VISIBLE
                chips.addView(chip("‹", false) { offset -= 1; show() })
                for (m in RangeMode.entries) {
                    val label = if (m == RangeMode.CUSTOM && customStart != null && mode == m) {
                        val f = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
                        "${customStart!!.format(f)} – ${customEnd!!.format(f)}"
                    } else m.label
                    chips.addView(chip(label, m == mode) {
                        if (m == RangeMode.CUSTOM) pickCustomRange() else { mode = m; offset = 0; show() }
                    })
                }
                chips.addView(chip("›", false) { offset += 1; show() })
                if (offset != 0) chips.addView(chip("Now", false) { offset = 0; show() })
            }
            Tab.RACE -> {
                val races = Prefs.snapshot(this)?.races.orEmpty().filter { it.date >= LocalDate.now().toString() }
                controls.visibility = View.VISIBLE
                chips.addView(chip("‹ Past races", racePast) { racePast = true; show() })
                races.forEachIndexed { i, r ->
                    val name = if (r.name.length > 22) r.name.take(20) + "…" else r.name
                    chips.addView(chip(name, !racePast && i == raceIndex) { racePast = false; raceIndex = i; show() })
                }
                // Taper strictness for the formula targets
                if (!racePast) {
                    val p = Prefs.taperPreset(this)
                    listOf("Strict taper", "Standard taper", "Relaxed taper").forEachIndexed { i, l ->
                        chips.addView(chip(l, i == p) { Prefs.setTaperPreset(this, i); show() })
                    }
                }
            }
            Tab.SUBT -> {
                controls.visibility = View.VISIBLE
                for (v in SubtView.entries) chips.addView(chip(v.label, v == subtView) { subtView = v; show() })
            }
            Tab.POWER -> {
                controls.visibility = View.VISIBLE
                listOf("Dashboard", "Trends", "Zones", "Race model").forEachIndexed { i, l -> chips.addView(chip(l, i == powerView) { powerView = i; show() }) }
            }
            Tab.STREAKS -> {
                controls.visibility = View.VISIBLE
                if (!calendarOpen) chips.addView(chip("▦ Calendar", false) { calendarOpen = true; show() })
                else {
                    chips.addView(chip("‹ Streaks", false) { calendarOpen = false; show() })
                    chips.addView(chip("Multi-year", calendarMulti) { calendarMulti = true; show() })
                    chips.addView(chip("Year", !calendarMulti) { calendarMulti = false; show() })
                    if (!calendarMulti) {
                        chips.addView(chip("‹ ${calendarYear - 1}", false) { calendarYear--; show() })
                        if (calendarYear < LocalDate.now().year) chips.addView(chip("${calendarYear + 1} ›", false) { calendarYear++; show() })
                    }
                }
            }
        }
    }

    /** Past races feed the Past races screen and calibrate the Fatigue target; refreshed daily. */
    private fun loadHistory(force: Boolean) {
        val creds = Prefs.credentials(this) ?: return
        val cached = Prefs.raceHistory(this)
        if (histLoading) return
        if (!force && cached != null && System.currentTimeMillis() - cached.first < 24 * 3600_000L) return
        histLoading = true
        histError = null
        Thread {
            val r = runCatching { IcuApi(creds).fetchRaceHistory() }
            runOnUiThread {
                histLoading = false
                r.onSuccess { Prefs.saveRaceHistory(this, it) }.onFailure { histError = it.message ?: it.javaClass.simpleName }
                if (tab == Tab.RACE) drawContent()
            }
        }.start()
    }

    /** Multi-year Fitness history for the calendar: loaded only when the calendar is opened, cached for a day. */
    private fun loadLongHistory(force: Boolean) {
        val creds = Prefs.credentials(this) ?: return
        val cached = Prefs.longHistory(this)
        if (histLongLoading) return
        if (!force && cached != null && System.currentTimeMillis() - cached.first < 24 * 3600_000L) return
        histLongLoading = true; histLongError = null
        Thread {
            val from = LocalDate.now().minusYears(5).withDayOfYear(1).minusDays(130)
            val api = IcuApi(creds)
            val r = runCatching { api.fetchFitnessHistory(from) }
            val tts = runCatching { api.fetchTtDates(from) }
            runOnUiThread {
                histLongLoading = false
                r.onSuccess { Prefs.saveLongHistory(this, it) }.onFailure { histLongError = it.message ?: it.javaClass.simpleName }
                tts.onSuccess { Prefs.saveTtDates(this, it) }
                if (tab == Tab.STREAKS) drawContent()
            }
        }.start()
    }

    private fun loadReps(force: Boolean) {
        val creds = Prefs.credentials(this) ?: run { repsError = "Connect intervals.icu in ⚙ settings"; return }
        val cached = Prefs.subtReps(this)
        if (repsLoading) return
        if (!force && cached != null && System.currentTimeMillis() - cached.first < 6 * 3600_000L) return
        repsLoading = true
        repsError = null
        Thread {
            val r = runCatching { IcuApi(creds).fetchSubtReps() }
            runOnUiThread {
                repsLoading = false
                r.onSuccess { Prefs.saveSubtReps(this, it) }.onFailure { repsError = it.message ?: it.javaClass.simpleName }
                if (tab == Tab.SUBT && subtView == SubtView.ANALYSIS) drawContent()
            }
        }.start()
    }

    /** Scrollable page; keeps the scroll position when the same screen is redrawn. */
    private fun scrollPage(key: String, fill: (LinearLayout) -> Unit): ScrollView {
        val prev = (content.getChildAt(0) as? ScrollView)?.takeIf { it.tag == key }?.scrollY ?: 0
        val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false; tag = key }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(16)) }
        sv.addView(col)
        fill(col)
        if (prev > 0) sv.post { sv.scrollTo(0, prev) }
        return sv
    }

    private fun setAppPeriod(days: Int) {
        Prefs.saveWidget(this, WidgetRenderer.APP_ID, GraphStyle.AUTO, days)
        if (Prefs.historyNeeded(this) > Prefs.historyFetched(this)) {
            Prefs.setRefreshing(this, true)
            Refresh.now(this, replace = true)
        }
        show()
    }

    private fun pickDate(initial: LocalDate, max: LocalDate? = null, min: LocalDate? = null, onPicked: (LocalDate) -> Unit) {
        val dlg = DatePickerDialog(this, { _, y, m, d -> onPicked(LocalDate.of(y, m + 1, d)) }, initial.year, initial.monthValue - 1, initial.dayOfMonth)
        fun millis(d: LocalDate) = d.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        max?.let { dlg.datePicker.maxDate = millis(it) }
        min?.let { dlg.datePicker.minDate = millis(it) }
        dlg.show()
    }

    private fun pickCustomRange() {
        val (s0, e0) = currentRange()
        pickDate(customStart ?: s0) { start ->
            pickDate(maxOf(customEnd ?: e0, start), min = start) { end ->
                customStart = start
                customEnd = if (ChronoUnit.DAYS.between(start, end) > 400) start.plusDays(400) else end
                mode = RangeMode.CUSTOM
                offset = 0
                show()
            }
        }
    }

    // ---------------------------------------------------------------- Week ranges

    private fun currentRange(): Pair<LocalDate, LocalDate> {
        val today = LocalDate.now()
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return when (mode) {
            RangeMode.WEEK -> monday.plusWeeks(offset.toLong()).let { it to it.plusDays(6) }
            RangeMode.MONTH -> today.withDayOfMonth(1).plusMonths(offset.toLong()).let { it to it.plusMonths(1).minusDays(1) }
            // The current week plus the 12 (or 25) before it, stepping by the same length
            RangeMode.M3 -> monday.minusWeeks(12).plusWeeks(13L * offset).let { it to it.plusWeeks(13).minusDays(1) }
            RangeMode.M6 -> monday.minusWeeks(25).plusWeeks(26L * offset).let { it to it.plusWeeks(26).minusDays(1) }
            RangeMode.CUSTOM -> {
                val s = customStart ?: monday
                val e = customEnd ?: s.plusDays(6)
                val len = ChronoUnit.DAYS.between(s, e) + 1
                s.plusDays(len * offset) to e.plusDays(len * offset)
            }
        }
    }

    private fun rangeTitle(start: LocalDate, end: LocalDate): String {
        val loc = Locale.getDefault()
        val dm = DateTimeFormatter.ofPattern("d MMM", loc)
        val span = if (start.year == end.year && start.month == end.month) {
            "${start.dayOfMonth}–${end.format(dm)}"
        } else "${start.format(dm)} – ${end.format(dm)}"
        val yr = if (end.year != LocalDate.now().year || start.year != end.year) " ${end.year}" else ""
        return when (mode) {
            RangeMode.WEEK -> when (offset) {
                0 -> "THIS WEEK · $span"
                -1 -> "LAST WEEK · $span$yr"
                1 -> "NEXT WEEK · $span$yr"
                else -> "WEEK · $span$yr"
            }
            RangeMode.MONTH -> start.format(DateTimeFormatter.ofPattern("MMMM yyyy", loc))
            else -> "$span$yr"
        }.uppercase(loc)
    }

    private fun drawWeek(wDp: Int, hDp: Int) {
        val (start, end) = currentRange()
        val key = "$start..$end"
        val snap = Prefs.snapshot(this)
        val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val isThisWeek = mode == RangeMode.WEEK && offset == 0
        var items = rangeCache[key]
        if (items == null && isThisWeek && snap != null && snap.today >= monday.toString()) items = snap.week
        val creds = Prefs.credentials(this)
        if (rangeCache[key] == null && key !in loading && creds != null) {
            loading += key
            Thread {
                val r = runCatching { IcuApi(creds).fetchRange(start, end) }
                runOnUiThread {
                    loading -= key
                    r.onSuccess { rangeCache[key] = it; rangeError = null }
                        .onFailure { rangeError = it.message ?: it.javaClass.simpleName }
                    if (tab == Tab.WEEK) drawContent()
                }
            }.start()
        }

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chartH = (hDp * 0.48).roundToInt().coerceAtLeast(200)
        val range = WeekRange(
            start, end, items.orEmpty(), rangeTitle(start, end), loading = items == null,
            lastWeekLoad = if (isThisWeek) snap?.lastWeekLoad else null,
        )
        applyInto(col, ExtraWidgets.buildWeek(this, wDp, chartH, inApp = true, range = range), dp(chartH))

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(6), dp(4), dp(12)) }
        scroll.addView(list)
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        list.addView(TextView(this).apply {
            text = "Green = load done (number on top). Dashed grey = planned but missed (number inside). Amber = still planned.  " +
                "✓ on plan (±10%) · ˄ more than planned · ˅ less than planned"
            setTextColor(0xFF6B7386.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(4), dp(2), dp(4), dp(4))
        })
        when {
            creds == null -> list.addView(note("Connect intervals.icu in ⚙ settings"))
            items == null && rangeError != null -> list.addView(note("Couldn't load: $rangeError"))
            items == null -> list.addView(note("Loading…"))
            items.isEmpty() -> list.addView(note("Nothing planned or done in this period."))
            else -> fillSessions(list, items)
        }
        content.removeAllViews()
        content.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(0xFF9AA3B2.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(8), dp(12), dp(8), dp(12))
    }

    /** Sessions grouped by day, most relevant first: today and upcoming, then the past. */
    private fun fillSessions(list: LinearLayout, items: List<Workout>) {
        val today = LocalDate.now()
        val t = today.toString()
        val byDay = items.groupBy { it.date }.toSortedMap()
        val f = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
        for ((date, sessions) in byDay) {
            val d = runCatching { LocalDate.parse(date) }.getOrNull()
            val load = sessions.sumOf { it.load }
            list.addView(TextView(this).apply {
                text = (if (date == t) "Today · " else "") + (d?.format(f) ?: date) + if (load > 0) "  ·  $load" else ""
                setTextColor(if (date == t) 0xFF4EA1FF.toInt() else 0xFF6B7386.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                letterSpacing = 0.04f
                setPadding(dp(4), dp(12), dp(4), dp(2))
            })
            for (w in sessions) list.addView(sessionRow(list, w, missed = w.status != WorkoutStatus.DONE && date < t))
        }
    }

    private fun sessionRow(parent: ViewGroup, w: Workout, missed: Boolean): View {
        val row = layoutInflater.inflate(R.layout.widget_workout_row, parent, false)
        val status = row.findViewById<TextView>(R.id.row_status)
        val name = row.findViewById<TextView>(R.id.row_name)
        when {
            w.status == WorkoutStatus.DONE -> { status.text = "✓"; status.setTextColor(WidgetRenderer.UP); name.setTextColor(WidgetRenderer.DIM) }
            missed -> { status.text = "✕"; status.setTextColor(0xFF6B7386.toInt()); name.setTextColor(0xFF6B7386.toInt()) }
            w.status == WorkoutStatus.RACE -> { status.text = "★"; status.setTextColor(WidgetRenderer.PLANNED) }
            else -> { status.text = "○"; status.setTextColor(WidgetRenderer.PLANNED) }
        }
        row.findViewById<TextView>(R.id.row_sport).text = WidgetRenderer.sportIcon(w.type)
        name.text = w.name
        row.findViewById<TextView>(R.id.row_meta).text = WidgetRenderer.meta(w) + if (missed) " · missed" else ""
        return row
    }

    // ---------------------------------------------------------------- drawing

    private fun applyInto(parent: ViewGroup, rv: RemoteViews, heightPx: Int) {
        try {
            parent.addView(rv.apply(this, parent), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx))
        } catch (e: Exception) {
            parent.addView(note("Couldn't draw this screen: ${e.message}"))
        }
    }

    /**
     * Press and drag on the Today chart to read any day: the numbers above switch to that day's
     * Fitness, Fatigue and Form and a crosshair marks it. Releasing returns to today.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun enableScrub(root: View) {
        val iv = root.findViewById<android.widget.ImageView>(R.id.trend_chart) ?: return
        val period = Prefs.period(this, WidgetRenderer.APP_ID)
        val snap = Prefs.snapshot(this) ?: return
        val days = snap.days.takeLast(period)
        if (days.size < 2) return
        val future = if (snap.today == LocalDate.now().toString()) snap.proj.take(14).filter { it.date > days.last().date } else emptyList()
        val all = days + future
        val d = resources.displayMetrics.density
        val ctlV = root.findViewById<TextView>(R.id.ctl_value); val ctlD = root.findViewById<TextView>(R.id.ctl_delta)
        val atlV = root.findViewById<TextView>(R.id.atl_value); val atlD = root.findViewById<TextView>(R.id.atl_delta)
        val formV = root.findViewById<TextView>(R.id.form_value); val formZ = root.findViewById<TextView>(R.id.form_zone)
        val df = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
        iv.setOnTouchListener { v, e ->
            val labels = v.height / d >= 110
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    val i = Charts.combinedIndexAt(e.x, all.size, v.width, d, labels)
                    val day = all[i]
                    iv.setImageBitmap(Charts.combined(days, v.width, v.height, d, WidgetRenderer.TILE_BG, labels, highlight = i, future = future))
                    val z = FormZone.of(day.form)
                    ctlV?.text = day.ctl.roundToInt().toString()
                    ctlD?.text = runCatching { LocalDate.parse(day.date).format(df) }.getOrDefault(day.date) + if (i > days.lastIndex) " · plan" else ""
                    ctlD?.setTextColor(0xFFF2F4F8.toInt())
                    atlV?.text = day.atl.roundToInt().toString(); atlD?.text = ""
                    formV?.text = (if (day.form > 0) "+" else "") + day.form.roundToInt(); formV?.setTextColor(z.color)
                    formZ?.text = z.label; formZ?.setTextColor(z.color)
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { drawContent(); true }
                else -> true
            }
        }
    }

    private fun drawContent() {
        val d = resources.displayMetrics.density
        val wDp = ((content.width - content.paddingLeft - content.paddingRight) / d).roundToInt()
        val hDp = ((content.height - content.paddingBottom) / d).roundToInt()
        if (wDp <= 0 || hDp <= 0) {
            content.post { drawContent() }; return
        }
        if (tab == Tab.WEEK) {
            drawWeek(wDp, hDp); return
        }
        val full = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // Build SubT sub-screens before clearing, so the scroll position can be kept
        val subtPage: View? = if (tab == Tab.SUBT && subtView != SubtView.PACE) {
            val screens = AppScreens(this, wDp) { drawContent() }
            when (subtView) {
                SubtView.ANALYSIS -> {
                    loadReps(force = false)
                    val cached = Prefs.subtReps(this)
                    scrollPage("analysis") { screens.subtAnalysis(it, cached?.second, cached?.first, repsLoading, repsError) { loadReps(force = true); drawContent() } }
                }
                SubtView.FORECAST -> scrollPage("forecast") { screens.sub3Forecast(it) }
                else -> scrollPage("plan") { screens.sub3Plan(it) }
            }
        } else null
        content.removeAllViews()
        try {
            when (tab) {
                Tab.WEEK -> Unit
                Tab.HOME -> content.addView(scrollPage("home") { col ->
                    // Verdict, the full view (chart scrubbable), today's power targets, the week checklist, streak
                    val screens = AppScreens(this, wDp) { drawContent() }
                    screens.verdictCard(col)
                    val chartH = (hDp * 0.72).roundToInt().coerceAtLeast(320)
                    applyInto(col, WidgetRenderer.buildFull(this, WidgetRenderer.APP_ID, wDp, chartH, inApp = true), dp(chartH))
                    col.getChildAt(col.childCount - 1)?.let { enableScrub(it) }
                    col.addView(View(this), LinearLayout.LayoutParams(1, dp(10)))
                    screens.todayPowerCard(col)
                    screens.weekChecklist(col)
                    screens.streakLine(col)
                }, full)
                Tab.STREAKS -> if (calendarOpen || run { loadLongHistory(force = false); false }) {
                    loadLongHistory(force = false)
                    content.addView(scrollPage("cal") {
                        AppScreens(this, wDp) { drawContent() }.streakCalendar(it, Prefs.longHistory(this)?.second, histLongLoading, histLongError,
                            calendarMulti, calendarYear) { loadLongHistory(force = true); drawContent() }
                    }, full)
                } else content.addView(scrollPage("streaks") { AppScreens(this, wDp) { drawContent() }.streaks(it) }, full)
                Tab.POWER -> content.addView(scrollPage("power$powerView") { AppScreens(this, wDp) { drawContent() }.power(it, powerView) }, full)
                Tab.RACE -> {
                    loadHistory(force = false)
                    if (racePast) content.addView(scrollPage("past") {
                        AppScreens(this, wDp) { drawContent() }.pastRaces(it, histLoading, histError) { loadHistory(force = true); drawContent() }
                    }, full)
                    else content.addView(AppScreens(this, wDp).race(raceIndex), full)
                }
                Tab.RECOVERY -> content.addView(AppScreens(this, wDp).recovery(Prefs.period(this, WidgetRenderer.APP_ID)), full)
                Tab.SUBT -> if (subtPage != null) content.addView(subtPage, full) else {
                    // SubT: the widget card at a comfortable height, then the goal projection below
                    val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
                    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    sv.addView(col)
                    val cardH = minOf(hDp, 340)
                    applyInto(col, ExtraWidgets.buildSubt(this, wDp, cardH, inApp = true), dp(cardH))
                    col.addView(View(this), LinearLayout.LayoutParams(1, dp(10)))
                    AppScreens(this, wDp).subtExtras(col)
                    content.addView(sv, full)
                }
            }
        } catch (e: Exception) {
            content.removeAllViews()
            content.addView(note("Couldn't draw this screen: ${e.message}"))
        }
    }
}
