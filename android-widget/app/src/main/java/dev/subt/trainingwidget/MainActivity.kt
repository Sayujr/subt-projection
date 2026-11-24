package dev.subt.trainingwidget

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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

    /** [inBar] tabs sit in the bottom bar; every tab is also in the ☰ menu. */
    private enum class Tab(val icon: String, val label: String, val title: String, val inBar: Boolean) {
        HOME("◉", "Today", "Training Today", true),
        WEEK("▥", "Week", "Training load", true),
        STREAKS("✦", "Streaks", "Streaks", true),
        SUBT("⏱", "SubT", "SubT & sub-3", true),
        POWER("ϟ", "Power", "Power", false),
        RACE("⚑", "Race", "Race", false),
        RECOVERY("♥", "Recovery", "Recovery", false),
    }

    private enum class RangeMode(val label: String) {
        WEEK("Week"), MONTH("Month"), M3("3 months"), M6("6 months"), CUSTOM("Custom…")
    }

    private var tab = Tab.HOME
    private var layoutRetries = 0
    /** When each background load last failed: no automatic retry for 5 minutes (stops offline retry loops). */
    private val failedAt = mutableMapOf<String, Long>()
    private val ZC_SUBT = 0xFFFFC53D.toInt()
    private val ZC_EASY = 0xFF3DD68C.toInt()
    private val ZC_LONG = 0xFF4EA1FF.toInt()
    private val ZC_BAND = 0xFFFF9F5A.toInt()
    private var longBandsOpen = false
    /** Day (yyyy-MM-dd) picked by tapping the week chart; its category split shows under the chart. */
    private var selectedDay: String? = null
    private lateinit var content: FrameLayout
    private lateinit var controls: HorizontalScrollView
    private lateinit var chips: LinearLayout
    private val tabViews = mutableMapOf<Tab, TextView>()
    private var menuView: TextView? = null

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
    private val zonesLoading = mutableSetOf<String>()
    private val zonesLoaded = mutableSetOf<String>()
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
        DailyRefresh.ensure(this)
        val snap = Prefs.snapshot(this)
        val stale = snap == null || System.currentTimeMillis() - snap.fetchedAt > 15 * 60_000 ||
            snap.today != LocalDate.now().toString()
        if ((stale && Prefs.credentials(this) != null) || Prefs.subt(this) == null) Refresh.now(this, replace = false)
        // Update check (every few hours) and "What's new" once after an update
        Thread { runCatching { Updater.check(this) }; runOnUiThread { if (tab == Tab.HOME) content.post { drawContent() } } }.start()
        if (Updater.takeWhatsNew(this)) {
            val notes = Updater.whatsNew(this)
            if (notes.isNotEmpty()) android.app.AlertDialog.Builder(this)
                .setTitle("What's new in ${Updater.currentName(this)}")
                .setMessage(notes.joinToString("\n\n") { "• $it" })
                .setPositiveButton("OK", null).show()
        }
        show()
    }

    override fun onPause() {
        getSharedPreferences("training_widget", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onPause()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun buildTabs() {
        val bar = findViewById<LinearLayout>(R.id.tabs)
        for (t in Tab.entries.filter { it.inBar }) {
            val tv = TextView(this).apply {
                text = "${t.icon}\n${t.label}"
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setLineSpacing(0f, 1.15f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener { tab = t; show() }
            }
            tabViews[t] = tv
            bar.addView(tv)
        }
        // ☰ More: the other screens + Settings
        menuView = TextView(this).apply {
            text = "☰\nMore"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setLineSpacing(0f, 1.15f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener { v ->
                val pm = android.widget.PopupMenu(this@MainActivity, v)
                Tab.entries.filter { !it.inBar }.forEachIndexed { i, t -> pm.menu.add(0, t.ordinal, i, "${t.icon}   ${t.label}") }
                pm.menu.add(0, 98, 98, "↗   Open intervals.icu")
                pm.menu.add(0, 99, 99, "⚙   Settings")
                pm.setOnMenuItemClickListener { item ->
                    if (item.itemId == 98) runCatching { startActivity(WidgetRenderer.browserIntent(this@MainActivity, "https://intervals.icu/")) }
                        .onFailure { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://intervals.icu/"))) }
                    else if (item.itemId == 99) startActivity(Intent(this@MainActivity, SetupActivity::class.java))
                    else { tab = Tab.entries[item.itemId]; show() }
                    true
                }
                pm.show()
            }
        }
        bar.addView(menuView)
    }

    /** Updates tabs, title and chips, then draws the screen once the layout has settled. */
    private fun show() {
        tabViews.forEach { (t, tv) -> tv.setTextColor(if (t == tab) 0xFF4EA1FF.toInt() else 0xFF6B7386.toInt()) }
        menuView?.setTextColor(if (!tab.inBar) 0xFF4EA1FF.toInt() else 0xFF6B7386.toInt())
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
                if (tab == Tab.HOME) chips.addView(chip("This week", current == Periods.THIS_WEEK) { setAppPeriod(Periods.THIS_WEEK) })
                for (days in Periods.ALL) {
                    chips.addView(chip(Periods.label(days), days == current) { setAppPeriod(days) })
                }
                val custom = current > 0 && current !in Periods.ALL
                val label = if (custom) "From ${LocalDate.now().minusDays(current - 1L).format(DateTimeFormatter.ofPattern("d MMM yy", Locale.getDefault()))}" else "Custom…"
                chips.addView(chip(label, custom) {
                    pickDate(LocalDate.now().minusDays(maxOf(current, 14) - 1L), max = LocalDate.now().minusDays(6)) { start ->
                        setAppPeriod((ChronoUnit.DAYS.between(start, LocalDate.now()) + 1).toInt().coerceIn(7, 1100))
                    }
                })
            }
            Tab.WEEK -> {
                controls.visibility = View.VISIBLE
                // Back / forward first, side by side, so both are always on screen
                chips.addView(chip("‹", false) { offset -= 1; show() })
                chips.addView(chip("›", false) { offset += 1; show() })
                if (offset != 0) chips.addView(chip("Now", false) { offset = 0; show() })
                for (m in RangeMode.entries) {
                    val label = if (m == RangeMode.CUSTOM && customStart != null && mode == m) {
                        val f = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
                        "${customStart!!.format(f)} – ${customEnd!!.format(f)}"
                    } else m.label
                    chips.addView(chip(label, m == mode) {
                        if (m == RangeMode.CUSTOM) pickCustomRange() else { mode = m; offset = 0; show() }
                    })
                }
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
        if (!force && System.currentTimeMillis() - failedAt.getOrDefault("hist", 0L) < 5 * 60_000L) return
        histLoading = true
        histError = null
        Thread {
            val r = runCatching { IcuApi(creds).fetchRaceHistory() }
            runOnUiThread {
                histLoading = false
                r.onSuccess { Prefs.saveRaceHistory(this, it) }.onFailure { histError = it.message ?: it.javaClass.simpleName; failedAt["hist"] = System.currentTimeMillis() }
                if (tab == Tab.RACE) drawContent()
            }
        }.start()
    }

    /** Time-in-target numbers for runs in [start]..[end]: fetched once per session per range, merged into the cache by id. */
    private fun loadZones(start: LocalDate, end: LocalDate) {
        val creds = Prefs.credentials(this) ?: return
        val last = minOf(end, LocalDate.now())
        if (last < start) return
        val key = "zones:$start..$last:${Prefs.lt1(this)}"
        if (key in zonesLoading || key in zonesLoaded) return
        if (System.currentTimeMillis() - failedAt.getOrDefault(key, 0L) < 5 * 60_000L) return
        zonesLoading += key
        val have = Prefs.zones(this).map { it.id }.toSet()
        val lt1 = Prefs.lt1(this).toDouble()
        val ftp = Prefs.cpOverride(this) ?: Prefs.snapshot(this)?.runFtp
        Thread {
            val r = runCatching { IcuApi(creds).fetchZones(start, last, lt1, ftp, have) }
            runOnUiThread {
                zonesLoading -= key
                r.onSuccess { got ->
                    zonesLoaded += key
                    if (got.isNotEmpty()) {
                        val byId = Prefs.zones(this).associateBy { it.id }.toMutableMap()
                        got.forEach { byId[it.id] = it }
                        Prefs.saveZones(this, byId.values.sortedBy { it.date })
                    }
                }.onFailure { failedAt[key] = System.currentTimeMillis() }
                if (tab == Tab.WEEK) drawContent()
            }
        }.start()
    }

    /** Multi-year Fitness history for the calendar: loaded only when the calendar is opened, cached for a day. */
    private fun loadLongHistory(force: Boolean) {
        val creds = Prefs.credentials(this) ?: return
        val cached = Prefs.longHistory(this)
        if (histLongLoading) return
        if (!force && cached != null && System.currentTimeMillis() - cached.first < 24 * 3600_000L) return
        if (!force && System.currentTimeMillis() - failedAt.getOrDefault("long", 0L) < 5 * 60_000L) return
        histLongLoading = true; histLongError = null
        Thread {
            val from = LocalDate.now().minusYears(5).withDayOfYear(1).minusDays(130)
            val api = IcuApi(creds)
            val r = runCatching { api.fetchFitnessHistory(from) }
            val index = runCatching { api.fetchActivityIndex(from) }
            val planned = runCatching { api.fetchPlannedLoads(LocalDate.now().minusYears(2)) }
            val tts = index.map { it.first }
            runOnUiThread {
                histLongLoading = false
                r.onSuccess { Prefs.saveLongHistory(this, it) }.onFailure { histLongError = it.message ?: it.javaClass.simpleName; failedAt["long"] = System.currentTimeMillis() }
                tts.onSuccess { Prefs.saveTtDates(this, it) }
                if (index.isSuccess && planned.isSuccess) Prefs.saveLoadIndex(this, index.getOrThrow().second, planned.getOrThrow())
                if (tab == Tab.STREAKS) drawContent()
            }
        }.start()
    }

    private fun loadReps(force: Boolean) {
        val creds = Prefs.credentials(this) ?: run { repsError = "Connect intervals.icu in ⚙ settings"; return }
        val cached = Prefs.subtReps(this)
        if (repsLoading) return
        if (!force && cached != null && System.currentTimeMillis() - cached.first < 6 * 3600_000L) return
        if (!force && System.currentTimeMillis() - failedAt.getOrDefault("reps", 0L) < 5 * 60_000L) return
        repsLoading = true
        repsError = null
        Thread {
            val scanned = Prefs.subtScanned(this)
            val r = runCatching { IcuApi(creds).fetchSubtReps(cached = cached?.second.orEmpty(), scanned = scanned) }
            runOnUiThread {
                repsLoading = false
                r.onSuccess { Prefs.saveSubtReps(this, it.reps); Prefs.saveSubtScanned(this, it.scanned); SubtWorkouts.merge(Prefs.subtPage(this), it.reps)?.let { d -> Prefs.saveSubt(this, d) } }.onFailure { repsError = it.message ?: it.javaClass.simpleName; failedAt["reps"] = System.currentTimeMillis() }
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
        val recentlyFailed = System.currentTimeMillis() - failedAt.getOrDefault("range:$key", 0L) < 5 * 60_000L
        if (rangeCache[key] == null && key !in loading && creds != null && !recentlyFailed) {
            loading += key
            Thread {
                val r = runCatching { IcuApi(creds).fetchRange(start, end) }
                runOnUiThread {
                    loading -= key
                    r.onSuccess { rangeCache[key] = it; rangeError = null }
                        .onFailure { rangeError = it.message ?: it.javaClass.simpleName; failedAt["range:$key"] = System.currentTimeMillis() }
                    if (tab == Tab.WEEK) drawContent()
                }
            }.start()
        }

        val today0 = LocalDate.now()
        loadZones(start, end)
        loadZones(monday.minusWeeks(11), today0)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chartH = (hDp * 0.48).roundToInt().coerceAtLeast(200)
        val nDays = (ChronoUnit.DAYS.between(start, end) + 1).toInt()
        val zr = Prefs.zones(this)
        val split = if (nDays <= 35 && items != null) daySplit(items, zr) else null
        val range = WeekRange(
            start, end, items.orEmpty(), rangeTitle(start, end), loading = items == null,
            lastWeekLoad = if (isThisWeek) snap?.lastWeekLoad else null,
            split = split,
        )
        applyInto(col, ExtraWidgets.buildWeek(this, wDp, chartH, inApp = true, range = range), dp(chartH))
        if (split != null) {
            col.addView(TextView(this).apply {
                val sb = android.text.SpannableStringBuilder()
                listOf("Easy", "Steady", "SubT", "Other").forEachIndexed { k, name ->
                    val s0 = sb.length; sb.append("●"); sb.setSpan(android.text.style.ForegroundColorSpan(Charts.SPLIT_COLORS[k]), s0, sb.length, 0)
                    sb.append(" $name" + if (k < 3) "   " else "")
                }
                sb.append("  ·  tap a day")
                text = sb; setTextColor(0xFF9AA3B2.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setPadding(dp(8), dp(2), dp(4), dp(2))
            })
            col.findViewById<android.widget.ImageView>(R.id.week_chart)?.setOnTouchListener { v, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_UP && v.width > 0) {
                    val day = start.plusDays(Charts.dayAt(e.x, v.width.toFloat(), nDays).toLong()).toString()
                    selectedDay = if (selectedDay == day) null else day
                    drawContent()
                }
                true
            }
        }

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(6), dp(4), dp(12)) }
        scroll.addView(list)
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val legend = TextView(this).apply {
            val tol = Prefs.planTolerance(this@MainActivity)
            text = "Green bar = load done (number on top). Dashed outline = load planned: a gap above the bar is how far short you were (number inside). Amber = still to do.\n" +
                "✓ within ±$tol% of plan · ˄ more than planned · ˅ less than planned · no mark = nothing planned"
            setTextColor(0xFF9AA3B2.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(dp(4), dp(2), dp(4), dp(6))
            visibility = if (AppScreens.openInfo.contains("week-legend")) View.VISIBLE else View.GONE
        }
        list.addView(TextView(this).apply {
            text = "ⓘ  How to read the bars"
            setTextColor(0xFF6B7386.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setOnClickListener {
                val open = legend.visibility != View.VISIBLE
                legend.visibility = if (open) View.VISIBLE else View.GONE
                if (open) AppScreens.openInfo += "week-legend" else AppScreens.openInfo -= "week-legend"
            }
        })
        list.addView(legend)
        val picked = selectedDay
        if (split != null && picked != null && picked in start.toString()..end.toString()) list.addView(dayPanel(picked, zr))
        list.addView(zonesCard(wDp, start, end, "zones:$start..${minOf(end, today0)}:${Prefs.lt1(this)}"))
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

    // ---------------------------------------------------------------- Load split by category

    /** Per finished day: the day's done load split into easy, steady, SubT and other (see [Zones.loadSplit]); null when no run has numbers yet. */
    private fun daySplit(items: List<Workout>, zr: List<Zones.Run>): Map<String, IntArray>? {
        val byId = zr.associateBy { it.id }
        val out = mutableMapOf<String, IntArray>()
        var any = false
        for ((date, ws) in items.filter { it.status == WorkoutStatus.DONE }.groupBy { it.date }) {
            val dayLoad = ws.sumOf { it.load }
            if (dayLoad <= 0) continue
            val runs = ws.mapNotNull { w -> w.activityId?.let { byId[it] } }
            if (runs.isNotEmpty()) any = true
            val zoned = ws.filter { w -> w.activityId?.let { byId.containsKey(it) } == true }.sumOf { it.load }
            out[date] = Zones.loadSplit(dayLoad, runs, dayLoad - zoned)
        }
        return if (any) out else null
    }

    /** Small panel for the tapped day: share of running time, time and distance in each category. */
    private fun dayPanel(day: String, zr: List<Zones.Run>): View {
        val runs = zr.filter { it.date == day }
        val sec = List(4) { k -> runs.sumOf { it.catSec[k] } }
        val m = List(4) { k -> runs.sumOf { it.catM[k] } }
        val total = sec.sum()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.tile_bg)
            setPadding(dp(14), dp(10), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4); bottomMargin = dp(4) }
        }
        val title = runCatching { LocalDate.parse(day).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())) }.getOrDefault(day)
        card.addView(TextView(this).apply { text = title; setTextColor(0xFFF2F4F8.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f) })
        if (total <= 0) {
            card.addView(TextView(this).apply { text = "No running time with numbers for this day."; setTextColor(0xFF9AA3B2.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setPadding(0, dp(4), 0, 0) })
        } else {
            val lt1 = Prefs.lt1(this)
            for ((k, name) in listOf(Zones.SUBT to "SubT", Zones.EASY to "Easy under ${Zones.EASY_HR.toInt()}", Zones.STEADY to "Steady ${Zones.EASY_HR.toInt()}–$lt1", Zones.OTHER to "Other")) {
                card.addView(BandViews.statRow(this, name, sec[k], sec[k] / total * 100, m[k]))
            }
        }
        card.addView(TextView(this).apply {
            text = "Load split is an estimate; time and distance are measured."
            setTextColor(0xFF6B7386.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f); setPadding(0, dp(6), 0, 0)
        })
        return card
    }

    // ---------------------------------------------------------------- Time in target

    private fun zonesCard(wDp: Int, start: LocalDate, end: LocalDate, key: String): View {
        val all = Prefs.zones(this)
        val tot = Zones.total(all, start, end)
        val lt1 = Prefs.lt1(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.tile_bg)
            setPadding(dp(14), dp(12), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); bottomMargin = dp(4) }
        }
        fun label(t: String, size: Float, color: Int, top: Int = 0) = TextView(this).apply {
            text = t; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setPadding(0, dp(top), 0, 0)
        }
        BandViews.heading(this, card, "TIME IN TARGET", "SubT workouts only, work steps only. Power is compared with each step's planned range.", 0xFF6B7386.toInt(), 12f)
        if (key in zonesLoading && tot.runs == 0) {
            card.addView(label("Loading…", 14f, 0xFF9AA3B2.toInt(), 6))
        } else {
            fun km(m: Double) = if (m > 0) "%.1f km".format(m / 1000) else "–"
            fun tm(sec: Double) = if (sec > 0) SubtModel.time(sec) else "–"
            fun row(title: String, value: String, color: Int) {
                card.addView(label(title, 12.5f, 0xFF9AA3B2.toInt(), 8))
                card.addView(label(value, 16f, color))
            }
            val pct = if (tot.subtWorkSec > 0) " · ${(tot.subtInSec / tot.subtWorkSec * 100).roundToInt()}% of work time" else ""
            row("SubT in power target", if (tot.subtInSec > 0) "${tm(tot.subtInSec)} · ${km(tot.subtInM)}$pct" else "–", ZC_SUBT)
            if (tot.subtWorkSec > 0) {
                val wk = tot.subtWorkSec
                card.addView(BandViews.stripView(this, listOf(tot.subtUnderSec, tot.subtInSec, tot.subtOverSec), listOf(0xFF6B7386.toInt(), 0xFF3DD68C.toInt(), 0xFF4EA1FF.toInt()), dp(wDp - 56)))
                card.addView(BandViews.statRow(this, "In target", tot.subtInSec, tot.subtInSec / wk * 100, tot.subtInM))
                card.addView(BandViews.statRow(this, "In or over target", tot.subtInSec + tot.subtOverSec, (tot.subtInSec + tot.subtOverSec) / wk * 100, tot.subtInM + tot.subtOverM, emphasised = true))
                card.addView(BandViews.statRow(this, "Under target", tot.subtUnderSec, tot.subtUnderSec / wk * 100, tot.subtUnderM))
            }
            BandViews.heading(this, card, "HEART RATE BANDS · ALL RUNS", "Every run counts here, including races and tests. Rows overlap on purpose; the coloured strip shows the four parts that add up to 100%.", 0xFF6B7386.toInt(), 12f, topDp = 16)
            card.addView(BandViews.build(this, tot.bandSec, tot.bandM, dp(wDp - 56)))
            row("Long-run aerobic · HR ${Zones.EASY_HR.toInt()}–$lt1", if (tot.longSec > 0) "${tm(tot.longSec)} · ${km(tot.longM)}" else "–", ZC_LONG)
            val longBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = if (longBandsOpen) View.VISIBLE else View.GONE }
            longBox.addView(BandViews.build(this, tot.longBandSec, tot.longBandM, dp(wDp - 56)))
            val toggle = label(if (longBandsOpen) "Long runs only: heart rate bands ▴" else "Long runs only: heart rate bands ▾", 13f, 0xFF4EA1FF.toInt(), 8)
            toggle.setOnClickListener {
                longBandsOpen = !longBandsOpen
                longBox.visibility = if (longBandsOpen) View.VISIBLE else View.GONE
                toggle.text = if (longBandsOpen) "Long runs only: heart rate bands ▴" else "Long runs only: heart rate bands ▾"
            }
            card.addView(toggle)
            card.addView(longBox)
        }
        card.addView(label("LAST 12 WEEKS", 12f, 0xFF6B7386.toInt(), 14).apply { letterSpacing = 0.06f })
        val weeks = Zones.weekly(all, 12, LocalDate.now())
        val charts = listOf<Triple<String, Int, (Zones.Total) -> Double>>(
            Triple("SubT in target", ZC_SUBT, { t: Zones.Total -> t.subtInSec }),
            Triple("Under ${Zones.EASY_HR.toInt()}", ZC_EASY, { t: Zones.Total -> t.easySec }),
            Triple("Long-run aerobic", ZC_LONG, { t: Zones.Total -> t.longSec }),
        )
        val widthPx = dp(wDp - 56).coerceAtLeast(dp(200))
        for ((name, color, pick) in charts) {
            val mins = weeks.map { pick(it.second) / 60.0 }
            card.addView(label("$name · this week ${mins.last().roundToInt()}′", 12f, 0xFF9AA3B2.toInt(), 8))
            val bmp = trendBitmap(widthPx, dp(46), mins, weeks.map { it.first }, color)
            card.addView(android.widget.ImageView(this).apply {
                setImageBitmap(bmp); scaleType = android.widget.ImageView.ScaleType.FIT_XY
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
        }
        return card
    }

    /** Minutes per week as bars, the current (last) week bright, the max labelled top-left, month letters underneath. */
    private fun trendBitmap(w: Int, h: Int, mins: List<Double>, mondays: List<LocalDate>, barColor: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val d = resources.displayMetrics.density
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f * d; this.color = 0xFF6B7386.toInt() }
        val top = 11f * d; val bottom = h - 12f * d
        val max = mins.maxOrNull() ?: 0.0
        val n = mins.size
        val slot = w.toFloat() / n
        val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until n) {
            val x0 = i * slot + slot * 0.15f; val x1 = (i + 1) * slot - slot * 0.15f
            val last = i == n - 1
            bar.color = if (last) barColor else (barColor and 0x00FFFFFF) or 0x66000000
            val bh = if (max > 0) ((mins[i] / max) * (bottom - top)).toFloat() else 0f
            c.drawRoundRect(RectF(x0, bottom - maxOf(bh, if (mins[i] > 0) 1.5f * d else 0f), x1, bottom), 2f * d, 2f * d, bar)
            if (i == 0 || mondays[i].month != mondays[i - 1].month) {
                val letter = mondays[i].month.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault())
                c.drawText(letter, x0, h - 2f * d, txt)
            }
        }
        if (max > 0) c.drawText("${max.roundToInt()}′", 0f, 9f * d, txt)
        return bmp
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
        val zr = w.activityId?.let { aid -> Prefs.zones(this).firstOrNull { it.id == aid } }
        val inTarget = when {
            zr == null -> ""
            zr.subt && zr.subtWorkSec > 0 -> " · ${(zr.subtInSec / 60).roundToInt()}′ in target"
            zr.long && zr.longSec > 0 -> " · ${(zr.longSec / 60).roundToInt()}′ aerobic"
            !zr.subt && !zr.long && zr.bandSec.getOrElse(0) { 0.0 } + zr.bandSec.getOrElse(1) { 0.0 } > 0 ->
                " · ${((zr.bandSec[0] + zr.bandSec[1]) / 60).roundToInt()}′ under 135"
            else -> ""
        }
        row.findViewById<TextView>(R.id.row_meta).text = WidgetRenderer.meta(w) + inTarget + (if (missed) " · missed" else "") + (if (w.activityId != null) "  ›" else "")
        // Done workouts open the detail screen (steps against targets)
        w.activityId?.let { aid -> row.setOnClickListener { WorkoutActivity.open(this, aid, w.name) } }
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
        val (days, future) = WidgetRenderer.chartSeries(snap, period, snap.today == LocalDate.now().toString())
        if (days.size < 2) return
        val d = resources.displayMetrics.density
        val ctlV = root.findViewById<TextView>(R.id.ctl_value); val ctlD = root.findViewById<TextView>(R.id.ctl_delta)
        val atlV = root.findViewById<TextView>(R.id.atl_value); val atlD = root.findViewById<TextView>(R.id.atl_delta)
        val formV = root.findViewById<TextView>(R.id.form_value); val formZ = root.findViewById<TextView>(R.id.form_zone)
        val df = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
        // The chart draws history + only the future days after the last history day
        val chartDays = days + future.filter { it.date > days.last().date }
        var lastI = -1
        iv.setOnTouchListener { v, e ->
            val labels = v.height / d >= 110
            if (v.width <= 0 || v.height <= 0) return@setOnTouchListener false
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> try {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    val i = Charts.combinedIndexAt(e.x, chartDays.size, v.width, d, labels)
                    if (i == lastI) return@setOnTouchListener true
                    lastI = i
                    val day = chartDays[i]
                    iv.setImageBitmap(Charts.combined(days, v.width, v.height, d, WidgetRenderer.TILE_BG, labels, highlight = i, future = future))
                    val z = FormZone.of(day.form)
                    ctlV?.text = day.ctl.roundToInt().toString()
                    ctlD?.text = runCatching { LocalDate.parse(day.date).format(df) }.getOrDefault(day.date) + if (i > days.lastIndex) " · plan" else ""
                    ctlD?.setTextColor(0xFFF2F4F8.toInt())
                    atlV?.text = day.atl.roundToInt().toString(); atlD?.text = ""
                    formV?.text = (if (day.form > 0) "+" else "") + day.form.roundToInt(); formV?.setTextColor(z.color)
                    formZ?.text = z.label; formZ?.setTextColor(z.color)
                    true
                } catch (t: Throwable) { true }
                // Rebuild after the touch has finished (removing views mid-touch crashes Android)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { lastI = -1; content.post { drawContent() }; true }
                else -> true
            }
        }
    }

    private fun drawContent() {
        val d = resources.displayMetrics.density
        var wDp = ((content.width - content.paddingLeft - content.paddingRight) / d).roundToInt()
        var hDp = ((content.height - content.paddingBottom) / d).roundToInt()
        if (wDp <= 0 || hDp <= 0) {
            // Wait for layout, but not forever: fall back to the screen size
            if (layoutRetries++ < 20) { content.post { drawContent() }; return }
            wDp = (resources.displayMetrics.widthPixels / d).roundToInt() - 24
            hDp = (resources.displayMetrics.heightPixels / d).roundToInt() - 200
        }
        layoutRetries = 0
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
                    val chartH = (hDp * 0.6).roundToInt().coerceAtLeast(300)   // leave room so the next card peeks in
                    applyInto(col, WidgetRenderer.buildFull(this, WidgetRenderer.APP_ID, wDp, chartH, inApp = true), dp(chartH))
                    col.getChildAt(col.childCount - 1)?.let { card ->
                        enableScrub(card)
                        // Today's done workouts open their detail screen
                        val rows = card.findViewById<ViewGroup>(R.id.workouts)
                        val todays = Prefs.snapshot(this)?.workouts.orEmpty()
                        if (rows != null) for (i in 0 until minOf(rows.childCount, todays.size)) {
                            val w = todays[i]
                            w.activityId?.let { aid -> rows.getChildAt(i).setOnClickListener { WorkoutActivity.open(this, aid, w.name) } }
                        }
                    }
                    col.addView(View(this), LinearLayout.LayoutParams(1, dp(10)))
                    screens.deviationsCard(col)
                    screens.todayPowerCard(col)
                    screens.recentWorkoutsCard(col)
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
                Tab.RECOVERY -> content.addView(AppScreens(this, wDp).recovery(Prefs.period(this, WidgetRenderer.APP_ID).let { if (it < 14) 14 else it }), full)
                Tab.SUBT -> if (subtPage != null) content.addView(subtPage, full) else {
                    // SubT: the widget card at a comfortable height, then the goal projection below
                    val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
                    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    sv.addView(col)
                    val cardH = minOf(hDp, 340)
                    applyInto(col, ExtraWidgets.buildSubt(this, wDp, cardH, inApp = true), dp(cardH))
                    // SubT pace chart: touch and drag to read each month
                    col.getChildAt(col.childCount - 1)?.findViewById<android.widget.ImageView>(R.id.subt_chart)?.let { iv ->
                        val bmp = (iv.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                        val hits = ChartHits.named("subt")
                        if (bmp != null && hits != null) Scrub.attach(iv, bmp, hits)
                    }
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
