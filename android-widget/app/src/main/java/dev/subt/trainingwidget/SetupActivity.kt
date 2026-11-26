package dev.subt.trainingwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Account setup (intervals.icu athlete ID + API key) and, when opened for a specific widget
 * (adding it, or long-press → Settings), that widget's graph style and time period.
 */
class SetupActivity : Activity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val styleIds = mutableMapOf<Int, GraphStyle>()
    private val periodIds = mutableMapOf<Int, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // If the user backs out while adding a widget, the widget isn't placed.
        setResult(RESULT_CANCELED, resultIntent())

        val athlete = findViewById<EditText>(R.id.athlete_id)
        val key = findViewById<EditText>(R.id.api_key)
        val status = findViewById<TextView>(R.id.status)
        val save = findViewById<Button>(R.id.save)
        val styleGroup = findViewById<RadioGroup>(R.id.style_group)
        val periodGroup = findViewById<RadioGroup>(R.id.period_group)

        buildNotifyRows()
        // Your numbers: show entered values, with the automatic value as the hint
        fun tm(sec: Double) = SubtModel.time(sec)
        val autoPb = Prefs.pbs(this)
        val autoKg = Prefs.snapshot(this)?.weight
        val autoCp = Prefs.snapshot(this)?.runFtp
        val autoSubt = Prefs.subt(this)?.months?.lastOrNull { !it.outlier }?.pace
        val todayIcu = Prefs.snapshot(this)?.days?.lastOrNull()?.takeIf { it.date == java.time.LocalDate.now().toString() }
        val fields = listOf(
            Triple(R.id.num_weight, Prefs.weightOverride(this)?.let { String.format(java.util.Locale.US, "%.1f", it) }, autoKg?.let { "auto: %.1f (intervals.icu)".format(it) }),
            Triple(R.id.num_cp, Prefs.cpOverride(this)?.let { it.roundToInt().toString() }, autoCp?.let { "auto: ${it.roundToInt()} W (intervals.icu FTP)" }),
            Triple(R.id.num_rhr, Prefs.rhrOverride(this)?.let { it.roundToInt().toString() }, todayIcu?.rhr?.let { "auto: ${it.roundToInt()} (intervals.icu today)" }),
            Triple(R.id.num_hrv, Prefs.hrvOverride(this)?.let { it.roundToInt().toString() }, todayIcu?.hrv?.let { "auto: ${it.roundToInt()} ms (intervals.icu today)" }),
            Triple(R.id.num_subt, Prefs.subtOverride(this)?.let { Charts.paceText(it) }, autoSubt?.let { "auto: ${Charts.paceText(it)} (latest monthly median)" }),
            Triple(R.id.num_pb5, Prefs.manualPb(this, "5k")?.let { tm(it) }, autoPb?.fiveK?.let { "auto: ${tm(it.seconds)}" }),
            Triple(R.id.num_pb10, Prefs.manualPb(this, "10k")?.let { tm(it) }, autoPb?.tenK?.let { "auto: ${tm(it.seconds)}" }),
            Triple(R.id.num_pbhm, Prefs.manualPb(this, "hm")?.let { tm(it) }, autoPb?.half?.let { "auto: ${tm(it.seconds)}" }),
        )
        for ((id, v, hint) in fields) findViewById<EditText>(id).apply { setText(v ?: ""); this.hint = hint ?: "auto" }
        Deviations.compute(this).takeIf { it.isNotEmpty() }?.let { dev ->
            findViewById<TextView>(R.id.num_deviations).apply {
                visibility = View.VISIBLE
                text = "Differs from intervals.icu (the app uses yours):\n" + dev.joinToString("\n") { "• $it" }
            }
        }

        buildSyncButtons()
        val stored = Prefs.credentials(this)
        stored?.let {
            athlete.setText(if (it.athleteId == "0") "" else it.athleteId)
            key.setText(it.apiKey)
        }

        // Per-widget options (not for the workouts-only widget, which has no graphs)
        val provider = if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider?.className
        } else null
        val graphWidgets = setOf(
            TrainingWidgetProvider::class.java.name, TrendWidgetProvider::class.java.name, CompactWidgetProvider::class.java.name,
        )
        val showStyle = provider in graphWidgets
        val showPeriod = showStyle || provider == RecoveryWidgetProvider::class.java.name
        val hasGraphs = showPeriod
        if (hasGraphs) {
            findViewById<View>(R.id.widget_section).visibility = View.VISIBLE
            findViewById<View>(R.id.widget_hint).visibility = View.GONE
            if (!showStyle) {
                findViewById<View>(R.id.style_label).visibility = View.GONE
                styleGroup.visibility = View.GONE
            }
            val styles = listOf(
                GraphStyle.AUTO to "Automatic (default): ${WidgetRenderer.autoStyleDescription(provider)}",
                GraphStyle.THREE to "Three separate graphs",
                GraphStyle.TWO to "Fitness + Fatigue together, Form separate",
                GraphStyle.ONE to "All three in one chart",
            )
            val current = Prefs.style(this, widgetId)
            for ((style, label) in styles) {
                val rb = radio(label)
                styleGroup.addView(rb)
                styleIds[rb.id] = style
                if (style == current) styleGroup.check(rb.id)
            }
            val currentPeriod = Prefs.period(this, widgetId)
            for (days in Periods.ALL) {
                val rb = radio(Periods.label(days) + if (days == Periods.DEFAULT) " (default)" else "")
                periodGroup.addView(rb)
                periodIds[rb.id] = days
                if (days == currentPeriod) periodGroup.check(rb.id)
            }
        } else if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            findViewById<View>(R.id.widget_hint).visibility = View.GONE
        }

        organiseSections(hasGraphs)

        save.setOnClickListener {
            saveNumbers()
            val apiKey = key.text.toString().trim()
            var ath = athlete.text.toString().trim()
            if (ath.isNotEmpty() && ath.all { it.isDigit() }) ath = "i$ath"
            if (apiKey.isEmpty()) {
                status.text = "Paste your API key first."
                return@setOnClickListener
            }
            if (hasGraphs) {
                Prefs.saveWidget(
                    this, widgetId,
                    styleIds[styleGroup.checkedRadioButtonId] ?: GraphStyle.AUTO,
                    periodIds[periodGroup.checkedRadioButtonId] ?: Periods.DEFAULT,
                )
            }
            val creds = Credentials(ath.ifEmpty { "0" }, apiKey)
            val history = Prefs.historyNeeded(this)
            val needFetch = creds != Prefs.credentials(this) || Prefs.snapshot(this) == null ||
                history > Prefs.historyFetched(this)

            if (!needFetch) {
                WidgetRenderer.renderAll(this)
                setResult(RESULT_OK, resultIntent())
                finish()
                return@setOnClickListener
            }

            save.isEnabled = false
            status.text = "Checking with intervals.icu…"
            Thread {
                val result = runCatching { IcuApi(creds).fetchSnapshot(historyDays = history.toLong()) }
                runOnUiThread {
                    save.isEnabled = true
                    result.onSuccess { snap ->
                        Prefs.saveCredentials(this, creds)
                        Prefs.saveSnapshot(this, snap, history)
                        Prefs.setRefreshing(this, false)
                        Refresh.schedulePeriodic(this)
                        WidgetRenderer.renderAll(this)
                        val last = snap.days.lastOrNull()
                        status.text = if (last != null) {
                            "Connected ✓  Fitness ${last.ctl.roundToInt()} · Fatigue ${last.atl.roundToInt()} · " +
                                "Form ${last.form.roundToInt()}  ·  ${snap.workouts.size} workout(s) today"
                        } else "Connected ✓"
                        setResult(RESULT_OK, resultIntent())
                        status.postDelayed({ finish() }, 900)
                    }.onFailure { e ->
                        status.text = when (e) {
                            is AuthException -> "intervals.icu rejected that API key / athlete ID. Double-check both."
                            else -> "Couldn't reach intervals.icu: ${e.message ?: e.javaClass.simpleName}"
                        }
                    }
                }
            }.start()
        }
    }

    companion object {
        const val EXTRA_OPEN = "open_section"
        private const val REQ_EXPORT = 41
        private const val REQ_IMPORT = 42
        private const val REQ_LOC = 43
        private val sentToday = mutableMapOf<String, String>()
        private val sectionOpen = mutableMapOf<String, Boolean>()
    }

    /**
     * Folds Settings into tappable sections: Account, Your numbers, Send to intervals.icu, Rules,
     * Widgets, Notifications & refresh. Save stays at the bottom.
     */
    private fun organiseSections(widgetMode: Boolean) {
        val root = (findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup).getChildAt(0) as android.widget.LinearLayout
        val kids = (0 until root.childCount).map { root.getChildAt(it) }
        fun idxText(t: String) = kids.indexOfFirst { (it as? TextView)?.text?.toString() == t }
        val iAcc = idxText("Connect intervals.icu"); val iNot = idxText("Notifications")
        val iWid = kids.indexOfFirst { it.id == R.id.widget_hint }; val iNum = idxText("Your numbers")
        val iSend = idxText("Send to intervals.icu"); val iEnd = kids.indexOfFirst { it.id == R.id.status }
        if (listOf(iAcc, iNot, iWid, iNum, iSend, iEnd).any { it < 0 }) return
        val groups = linkedMapOf(
            "acc" to kids.subList(iAcc + 1, iNot), "num" to kids.subList(iNum + 1, iSend),
            "send" to kids.subList(iSend + 1, iEnd), "wid" to kids.subList(iWid, iNum), "not" to kids.subList(iNot + 1, iWid),
        )
        val tail = kids.subList(iEnd, kids.size)
        root.removeAllViews()
        val d = resources.displayMetrics.density
        val hasCreds = Prefs.credentials(this) != null
        val batteryOk = DailyRefresh.batteryUnrestricted(this)
        fun section(key: String, title: String, sub: String?, open: Boolean, views: List<View>) {
            val body = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
            views.forEach { body.addView(it) }
            val isOpen = sectionOpen[key] ?: open
            body.visibility = if (isOpen) View.VISIBLE else View.GONE
            val head = TextView(this).apply {
                fun label(o: Boolean): CharSequence {
                    val sb = android.text.SpannableStringBuilder((if (o) "▾  " else "▸  ") + title)
                    if (sub != null) {
                        val st = sb.length
                        sb.append("\n").append(sub)
                        sb.setSpan(android.text.style.RelativeSizeSpan(0.76f), st, sb.length, 0)
                        sb.setSpan(android.text.style.ForegroundColorSpan(if (sub.startsWith("⚠")) 0xFFFFC53D.toInt() else 0xFF9AA3B2.toInt()), st, sb.length, 0)
                        sb.setSpan(android.text.style.TypefaceSpan("sans-serif"), st, sb.length, 0)
                    }
                    return sb
                }
                text = label(isOpen)
                setTextColor(0xFFF2F4F8.toInt()); textSize = 17f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setPadding(0, (18 * d).toInt(), 0, (8 * d).toInt())
                setLineSpacing(3 * d, 1f)
                setOnClickListener {
                    val o = body.visibility != View.VISIBLE
                    body.visibility = if (o) View.VISIBLE else View.GONE
                    sectionOpen[key] = o; text = label(o)
                }
            }
            root.addView(head); root.addView(body)
            root.addView(View(this).apply { setBackgroundColor(0xFF2C3240.toInt()) }, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        }
        val dev = Deviations.compute(this).size
        section("acc", "Account", if (hasCreds) "Connected to intervals.icu" else "Not connected yet", !hasCreds, groups["acc"]!!)
        section("num", "Your numbers", if (dev > 0) "$dev differ from intervals.icu" else "Weight, CP, RHR, HRV, SubT, PBs", !widgetMode, groups["num"]!!)
        section("send", "Send to intervals.icu", "Optional, asks before each change", false, groups["send"]!!)
        section("rules", "Rules", "Plan tolerance, streak bands, marathon power", false, rulesViews())
        section("rw", "Run windows", rwSub(), intent?.getStringExtra(EXTRA_OPEN) == "rw", runWindowViews())
        section("wid", "Widgets", if (widgetMode) "This widget's graphs and period" else "Long-press a widget to change it", widgetMode, groups["wid"]!!)
        section("coach", "Coach", "Tone of the read-out you take to the Claude app", false, coachViews())
        section("backup", "Backup", "Save or restore your settings and numbers", false, backupViews())
        val crashes = CrashLog.count(this)
        section("crash", "Crash reports", if (crashes == 0) "No crashes recorded" else "⚠ $crashes recorded, last ${CrashLog.lastTime(this)}", false, crashViews())
        section("not", "Notifications & refresh", if (batteryOk) "Background refresh allowed" else "⚠ Battery saving may block refresh", !batteryOk, groups["not"]!!)
        val upd = Updater.available(this)
        section("update", "App updates", if (upd != null) "⚠ ${upd.name} ready to install (you have ${Updater.currentName(this)})" else "Version ${Updater.currentName(this)}",
            upd != null || intent?.getStringExtra(EXTRA_OPEN) == "update", updateViews())
        tail.forEach { root.addView(it) }
    }

    private fun smallButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setTextColor(0xFFF2F4F8.toInt()); setBackgroundResource(R.drawable.input_bg)
        setOnClickListener { onClick() }
        layoutParams = android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = (8 * resources.displayMetrics.density).toInt() }
    }

    private fun note(t: String) = TextView(this).apply { text = t; setTextColor(0xFF6B7386.toInt()); textSize = 12f; setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0) }

    /** One row per notification category: switch, what it is, and its time (tap to change). */
    private fun buildNotifyRows() {
        val box = findViewById<android.widget.LinearLayout>(R.id.notify_rows)
        box.removeAllViews()
        val d = resources.displayMetrics.density
        val N = NotifySettings
        fun timeChip(min: Int, onPick: (Int) -> Unit) = TextView(this).apply {
            text = N.fmt(min); textSize = 14f; setTextColor(0xFF4EA1FF.toInt())
            setBackgroundResource(R.drawable.chip_off); gravity = android.view.Gravity.CENTER
            setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
            setOnClickListener {
                android.app.TimePickerDialog(this@SetupActivity, { _, h, m -> onPick(h * 60 + m); DailyRefresh.ensure(this@SetupActivity); buildNotifyRows() },
                    min / 60, min % 60, true).show()
            }
        }
        fun row(title: String, help: String, checked: Boolean, onToggle: (Boolean) -> Unit, time: Int?, onTime: ((Int) -> Unit)?) {
            val r = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (8 * d).toInt(), 0, (2 * d).toInt())
            }
            val sw = android.widget.CheckBox(this).apply { isChecked = checked; setOnCheckedChangeListener { _, on -> onToggle(on); DailyRefresh.ensure(this@SetupActivity) } }
            val txt = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
            txt.addView(TextView(this).apply { text = title; setTextColor(0xFFF2F4F8.toInt()); textSize = 15f })
            txt.addView(TextView(this).apply { text = help; setTextColor(0xFF6B7386.toInt()); textSize = 12f })
            r.addView(sw); r.addView(txt, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (time != null && onTime != null) r.addView(timeChip(time, onTime))
            box.addView(r)
        }
        fun header(t: String) = box.addView(TextView(this).apply {
            text = t.uppercase(); setTextColor(0xFF9AA3B2.toInt()); textSize = 11f; letterSpacing = 0.08f
            setPadding(0, (14 * d).toInt(), 0, 0)
        })
        header("Workout")
        for (k in listOf("workout", "reminder")) { val c = N.cat(k); row(c.label, c.help, N.isOn(this, c), { N.setOn(this, c, it) }, N.time(this, c)) { N.setTime(this, c, it) } }
        header("Recovery")
        for (k in listOf("recovery", "rhr")) { val c = N.cat(k); row(c.label, c.help, N.isOn(this, c), { N.setOn(this, c, it) }, if (c.defaultTime != null) N.time(this, c) else null, if (c.defaultTime != null) ({ v: Int -> N.setTime(this, c, v) }) else null) }
        row("Pause near races", "No recovery notifications from 2 days before a race through race day", N.pauseNearRace(this), { N.setPauseNearRace(this, it) }, null, null)
        header("Alerts")
        row("Alerts not before", "Alerts that come up earlier wait until this time", true, {}, N.alertsFrom(this)) { N.setAlertsFrom(this, it) }
        (box.getChildAt(box.childCount - 1) as android.widget.LinearLayout).getChildAt(0).visibility = View.INVISIBLE
        for (k in listOf("deviations", "eftp", "taper", "streak", "update")) { val c = N.cat(k); row(c.label, c.help, N.isOn(this, c), { N.setOn(this, c, it) }, null, null) }
        header("Run windows")
        for (k in listOf("rw_tomorrow", "rw_today", "rw_shift")) {
            val c = N.cat(k)
            row(c.label, c.help, N.isOn(this, c), { N.setOn(this, c, it) }, if (c.defaultTime != null) N.time(this, c) else null, if (c.defaultTime != null) ({ v: Int -> N.setTime(this, c, v) }) else null)
        }
        if (android.os.Build.VERSION.SDK_INT >= 31 && getSystemService(android.app.AlarmManager::class.java)?.canScheduleExactAlarms() == false) {
            box.addView(TextView(this).apply {
                text = "⚠ Android may deliver these a few minutes late. Tap to allow exact times."
                setTextColor(0xFFFFC53D.toInt()); textSize = 13f; setPadding(0, (10 * d).toInt(), 0, 0)
                setOnClickListener { runCatching { startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:$packageName"))) } }
            })
        }
    }

    /** Version, what's new, check for updates, and one-tap download → install (Android confirms). */
    private fun updateViews(): List<View> {
        val info = TextView(this).apply { setTextColor(0xFFF2F4F8.toInt()); textSize = 14f; setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0) }
        val notes = TextView(this).apply { setTextColor(0xFF9AA3B2.toInt()); textSize = 13f; setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0) }
        val btn = smallButton("") {}
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var installedFor = 0
        fun render() {
            val l = Updater.available(this)
            if (l == null) {
                info.text = "You have the latest version (${Updater.currentName(this)})."
                notes.text = Updater.whatsNew(this).take(6).joinToString("\n") { "• $it" }.let { if (it.isEmpty()) "" else "In this version:\n$it" }
                btn.text = "Check for updates"
                btn.setOnClickListener {
                    btn.text = "Checking…"
                    Thread {
                        val r = runCatching { Updater.check(this, force = true) }
                        runOnUiThread { if (r.isFailure) info.text = "Couldn't check: ${r.exceptionOrNull()?.message}"; render() }
                    }.start()
                }
                return
            }
            info.text = "Version ${l.name} is ready (you have ${Updater.currentName(this)})."
            notes.text = l.notes.take(8).joinToString("\n") { "• $it" }
            val prog = Updater.progress(this, l.code)
            val st = prog?.first
            val running = st == android.app.DownloadManager.STATUS_RUNNING || st == android.app.DownloadManager.STATUS_PENDING || st == android.app.DownloadManager.STATUS_PAUSED
            btn.text = when {
                running -> "Downloading… ${prog?.second ?: 0}%"
                st == android.app.DownloadManager.STATUS_SUCCESSFUL -> "Install ${l.name}"
                st == android.app.DownloadManager.STATUS_FAILED -> "Download failed: try again"
                else -> "Download and install ${l.name}"
            }
            btn.setOnClickListener {
                when {
                    running -> Unit
                    st == android.app.DownloadManager.STATUS_SUCCESSFUL -> Updater.install(this)
                    else -> { Updater.startDownload(this, l); render() }
                }
            }
            if (running) handler.postDelayed({ if (!isFinishing) render() }, 1000)
            if (st == android.app.DownloadManager.STATUS_SUCCESSFUL && installedFor != l.code) { installedFor = l.code; Updater.install(this) }
        }
        render()
        // Fallback when the phone won't let this app install (e.g. Samsung Auto Blocker greys out the switch)
        val viaBrowser = smallButton("Or download in your browser") {
            runCatching { startActivity(WidgetRenderer.browserIntent(this, "https://github.com/Sayujr/subt-projection/releases/download/android-widget-latest/training-widget.apk")) }
        }
        return listOf(info, notes, btn, viaBrowser,
            note("Android asks you to confirm the install. Your widgets and settings stay. If the \"Install unknown apps\" switch is greyed out, Samsung Auto Blocker is on: turn it off for the install, or use the browser button."))
    }

    /** Tone for the prompt "Talk it through" prepares. */
    private fun coachViews(): List<View> {
        val d = resources.displayMetrics.density
        val names = listOf("Direct", "Encouraging", "Analytical")
        val row = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; setPadding(0, (10 * d).toInt(), 0, 0) }
        val chips = names.map { TextView(this) }
        fun paint() {
            val sel = Prefs.coachTone(this)
            chips.forEachIndexed { i, tv ->
                tv.setBackgroundResource(if (i == sel) R.drawable.chip_on else R.drawable.chip_off)
                tv.setTextColor(if (i == sel) 0xFFF2F4F8.toInt() else 0xFF9AA3B2.toInt())
            }
        }
        chips.forEachIndexed { i, tv ->
            tv.apply {
                text = names[i]; textSize = 14f; gravity = android.view.Gravity.CENTER
                setPadding((8 * d).toInt(), (9 * d).toInt(), (8 * d).toInt(), (9 * d).toInt())
                setOnClickListener { Prefs.setCoachTone(this@SetupActivity, i); paint() }
            }
            row.addView(tv, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = (8 * d).toInt() })
        }
        paint()
        val sync = android.widget.CheckBox(this).apply {
            text = "Keep coach notes in intervals.icu"; setTextColor(0xFFF2F4F8.toInt()); textSize = 14.5f
            isChecked = Prefs.coachSync(this@SetupActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setCoachSync(this@SetupActivity, on) }
        }
        return listOf(
            sync,
            note("Adds one 'Coach notes' note per training day to your intervals.icu calendar, for runs with targets and for questions you send. Nothing else in intervals.icu is changed."),
            TextView(this).apply { text = "Tone"; setTextColor(0xFFF2F4F8.toInt()); textSize = 14.5f; setPadding(0, (10 * d).toInt(), 0, 0) },
            row,
            note("Long-press a card or tap Coach › for a read-out. 'Talk it through' shares the numbers on that card with the Claude app; nothing is sent until you tap it."),
        )
    }

    private fun backupViews(): List<View> = listOf(
        note("Saves your numbers, PBs, rules, widget looks and notification choices to a file you pick (Drive, Downloads…). Your API key isn't included; paste it again after restoring."),
        smallButton("Save backup…") {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "training-today-backup-${java.time.LocalDate.now()}.json"), REQ_EXPORT)
        },
        smallButton("Restore from backup…") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_IMPORT)
        },
    )

    private fun crashViews(): List<View> {
        val log = CrashLog.read(this)
        if (log == null) return listOf(note("If the app ever crashes, the details are kept here so you can send them."))
        return listOf(
            note("The app keeps the last few crashes. Send them (email, chat…) and paste into our conversation so they can be fixed."),
            smallButton("Send crash report") {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Training Today crash report")
                    .putExtra(Intent.EXTRA_TEXT, log.takeLast(60_000)), "Send crash report"))
            },
            smallButton("Clear crash reports") { CrashLog.clear(this); recreate() },
        )
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        val status = findViewById<TextView>(R.id.status)
        val r = runCatching {
            when (requestCode) {
                REQ_EXPORT -> { contentResolver.openOutputStream(uri, "wt")!!.use { it.write(Backup.export(this).toByteArray()) }; "✓ Backup saved" }
                REQ_IMPORT -> {
                    val n = Backup.import(this, contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) })
                    WidgetRenderer.renderAll(this)
                    "✓ Restored $n settings. Paste your API key if it's empty, then Save."
                }
                else -> null
            }
        }
        status.text = r.fold({ it ?: "" }, { "Couldn't ${if (requestCode == REQ_EXPORT) "save" else "restore"}: ${it.message}" })
        if (requestCode == REQ_IMPORT && r.isSuccess) {
            android.widget.Toast.makeText(this, status.text, android.widget.Toast.LENGTH_LONG).show()
            recreate()
        }
    }

    /** −/+ rows for the rule settings the app uses everywhere. */
    private fun rulesViews(): List<View> {
        val d = resources.displayMetrics.density
        fun stepper(label: String, help: String, get: () -> Int, set: (Int) -> Unit, min: Int, max: Int, step: Int, unit: String): View {
            val box = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(0, (10 * d).toInt(), 0, 0) }
            val row = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            val value = TextView(this).apply { setTextColor(0xFFF2F4F8.toInt()); textSize = 16f; gravity = android.view.Gravity.CENTER; minWidth = (64 * d).toInt() }
            fun refresh() { value.text = "${get()}$unit" }
            fun btn(t: String, delta: Int) = TextView(this).apply {
                text = t; textSize = 18f; setTextColor(0xFF4EA1FF.toInt()); gravity = android.view.Gravity.CENTER
                setBackgroundResource(R.drawable.chip_off)
                setOnClickListener { set((get() + delta).coerceIn(min, max)); refresh(); WidgetRenderer.renderAll(this@SetupActivity) }
            }
            row.addView(TextView(this).apply { text = label; setTextColor(0xFFF2F4F8.toInt()); textSize = 14.5f },
                android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(btn("−", -step), android.widget.LinearLayout.LayoutParams((40 * d).toInt(), (36 * d).toInt()))
            row.addView(value)
            row.addView(btn("+", step), android.widget.LinearLayout.LayoutParams((40 * d).toInt(), (36 * d).toInt()))
            refresh()
            box.addView(row)
            box.addView(TextView(this).apply { text = help; setTextColor(0xFF6B7386.toInt()); textSize = 12f })
            return box
        }
        val c = this
        return listOf(
            stepper("Plan tolerance", "A day or week within ± this of planned load counts as on plan (✓ on the week bars, on-plan streak weeks). 0 = off. Same setting as Streaks → Calibrate.",
                { Prefs.planTolerance(c) }, { Prefs.setPlanTolerance(c, it) }, 0, 20, 5, "%"),
            stepper("Fitness floor", "Streaks need Fitness within this % of your reference peak.",
                { Prefs.fitnessBand(c) }, { Prefs.setFitnessBand(c, it) }, 5, 20, 5, "%"),
            stepper("Fatigue minimum", "A streak week needs average Fatigue at least this % of Fitness.",
                { Prefs.fatigueMin(c) }, { Prefs.setFatigueMin(c, it) }, 90, 105, 5, "%"),
            stepper("Marathon power", "Marathon race power as % of CP, used by the power race model.",
                { Prefs.fmPct(c) }, { Prefs.setFmPct(c, it) }, 84, 90, 2, "%"),
            stepper("LT1 heart rate", "Top of the long-run aerobic band. Default 153 = 89% of threshold HR 172 (a rule of thumb, not a lab value).",
                { Prefs.lt1(c) }, { if (it != Prefs.lt1(c)) { Prefs.setLt1(c, it); Prefs.saveZones(c, emptyList()) } }, 135, 170, 1, " bpm"),
        )
    }


    // ---------------------------------------------------------------- Run windows

    private var rwLocLabel: TextView? = null
    private var rwKeyLabel: TextView? = null
    private var rwCalls: TextView? = null
    private var rwStatus: TextView? = null

    private fun rwSub(): String {
        val s = RwStore.load(this)
        return if (s.lat == null || s.lon == null) "⚠ Set your location" else (s.place.ifBlank { "Location set" }) + if (SecureKey.has(this)) " · Rainbow key saved" else " · no Rainbow key"
    }

    private fun rwLocText(): String {
        val s = RwStore.load(this)
        val lat = s.lat; val lon = s.lon
        if (lat == null || lon == null) return "No location set yet."
        return (s.place.ifBlank { "Location" }) + String.format(java.util.Locale.US, " (%.3f, %.3f)", lat, lon)
    }

    private fun rwSay(t: String) { rwStatus?.text = t }

    private fun rwRefreshLabels() {
        rwLocLabel?.text = rwLocText()
        rwKeyLabel?.text = SecureKey.masked(this)?.let { "Rainbow key: $it" } ?: "No Rainbow key saved (optional: the other sources still work)."
        rwCalls?.text = "Rainbow calls this month: ${RwStore.callsThisMonth(this)} of 10,000 (free plan)"
    }

    private fun applyLocation(lat: Double, lon: Double, place: String) {
        RwStore.setLocation(this, lat, lon, place)
        RunWindowSchedule.ensure(this)
        rwRefreshLabels()
        rwSay("Location saved. Fetching the forecast…")
        RunWindows.refreshAsync(this, manual = true) { rep ->
            rwSay(if (rep.anyOk) "✓ Forecast loaded (" + rep.ok.joinToString(", ") + ")" + (if (rep.failed.isEmpty()) "" else "; skipped: " + rep.failed.joinToString(", ")) else "Couldn't load the forecast: " + rep.failed.joinToString(", "))
            rwRefreshLabels()
        }
    }

    /** Last known coarse position, else one network fix. Foreground only: nothing runs in the background. */
    @Suppress("MissingPermission", "DEPRECATION")
    private fun useMyLocation() {
        val lm = getSystemService(android.location.LocationManager::class.java)
        if (lm == null) { rwSay("This phone has no location service."); return }
        var best: android.location.Location? = null
        for (p in listOf(android.location.LocationManager.NETWORK_PROVIDER, android.location.LocationManager.PASSIVE_PROVIDER)) {
            val l = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            val b = best
            if (b == null || l.time > b.time) best = l
        }
        val known = best
        if (known != null) { applyLocation(known.latitude, known.longitude, "Current location"); return }
        val provider = android.location.LocationManager.NETWORK_PROVIDER
        if (!runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)) { rwSay("Turn location on in Android's settings, then tap again."); return }
        rwSay("Getting your location…")
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: android.location.Location) { runOnUiThread { applyLocation(location.latitude, location.longitude, "Current location") } }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        runCatching { lm.requestSingleUpdate(provider, listener, android.os.Looper.getMainLooper()) }
            .onFailure { rwSay("Couldn't get your location. Type a place instead.") }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOC) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) useMyLocation()
            else rwSay("No location permission. Type a place or lat, lon instead.")
        }
    }

    private fun runWindowViews(): List<View> {
        val d = resources.displayMetrics.density
        fun head(t: String) = TextView(this).apply {
            text = t.uppercase(); setTextColor(0xFF9AA3B2.toInt()); textSize = 11f; letterSpacing = 0.08f; setPadding(0, (16 * d).toInt(), 0, 0)
        }
        fun field(hint: String, password: Boolean) = EditText(this).apply {
            this.hint = hint; setTextColor(0xFFF2F4F8.toInt()); setHintTextColor(0xFF6B7386.toInt()); textSize = 15f
            setBackgroundResource(R.drawable.input_bg); isSingleLine = true
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            inputType = if (password) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_CLASS_TEXT
        }
        val out = ArrayList<View>()
        rwStatus = TextView(this).apply { setTextColor(0xFF3DD68C.toInt()); textSize = 13f; setPadding(0, (8 * d).toInt(), 0, 0) }

        // ---- location
        out += head("Location")
        rwLocLabel = TextView(this).apply { setTextColor(0xFFF2F4F8.toInt()); textSize = 14.5f; setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt()) }
        out += rwLocLabel!!
        val place = field("Address, place, ZIP / PIN code, or lat, lon", false)
        out += place
        out += smallButton("Set location") {
            val raw = place.text.toString().trim()
            val c = Weather.parseCoords(raw)
            if (raw.isEmpty()) rwSay("Type a place or lat, lon first.")
            else if (c != null) applyLocation(c.first, c.second, "")
            else if (Regex("\\d").containsMatchIn(raw) && Regex("[°NSEWnsew]").containsMatchIn(raw)) rwSay("Couldn't read those coordinates. Try e.g. 39.95, -75.20")
            else {
                rwSay("Searching for “$raw”…")
                Thread {
                    val found = runCatching { LocPrompt.search(this, raw) }.getOrDefault(emptyList())
                    runOnUiThread {
                        when {
                            found.isEmpty() -> rwSay("No place found for “$raw”. Try a fuller address, a ZIP / PIN code, or lat, lon.")
                            found.size == 1 -> applyLocation(found[0].lat, found[0].lon, found[0].label)
                            else -> android.app.AlertDialog.Builder(this)
                                .setTitle("Which one?")
                                .setItems(found.map { it.label }.toTypedArray()) { _, i -> applyLocation(found[i].lat, found[i].lon, found[i].label) }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    }
                }.start()
            }
        }
        out += smallButton("Use where I am now") {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) useMyLocation()
            else requestPermissions(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOC)
        }
        out += note("Android asks first, and offers \"Only this time\". The app never reads your location in the background; the position is stored on this phone and sent only to the weather services as coordinates.")

        // ---- running hours
        // ---- location check when the app opens
        out += head("Ask to check location")
        val askRow = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(0, (8 * d).toInt(), 0, 0) }
        val askChips = AskMode.entries.map { TextView(this) }
        fun paintAsk() {
            val cur = LocState.mode(this)
            askChips.forEachIndexed { i, tv ->
                val sel = AskMode.entries[i] == cur
                tv.setBackgroundResource(if (sel) R.drawable.chip_on else R.drawable.chip_off)
                tv.setTextColor(if (sel) 0xFFF2F4F8.toInt() else 0xFF9AA3B2.toInt())
            }
        }
        askChips.forEachIndexed { i, tv ->
            tv.apply {
                text = AskMode.entries[i].label; textSize = 14f; gravity = android.view.Gravity.CENTER
                setPadding((8 * d).toInt(), (9 * d).toInt(), (8 * d).toInt(), (9 * d).toInt())
                setOnClickListener { LocState.setMode(this@SetupActivity, AskMode.entries[i]); paintAsk() }
            }
            askRow.addView(tv, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) topMargin = (6 * d).toInt() })
        }
        paintAsk()
        out += askRow
        out += note("When you open the app it can ask to compare where you are with the saved location, so run windows follow you. It asks first (Android then gives a one-time permission), reads one approximate fix and never tracks you in the background. A new time zone makes it ask at once, in the first two modes.")

        out += head("Running hours")
        val modeRow = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; setPadding(0, (8 * d).toInt(), 0, 0) }
        val customBox = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        val modeChips = listOf("Auto (daylight)", "Custom").map { TextView(this) }
        fun timeChip(key: String, def: Int): TextView = TextView(this).apply {
            textSize = 14f; setTextColor(0xFF4EA1FF.toInt()); setBackgroundResource(R.drawable.chip_off); gravity = android.view.Gravity.CENTER
            setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
            fun paint() { text = NotifySettings.fmt(getSharedPreferences("training_widget", MODE_PRIVATE).getInt(key, def)) }
            paint()
            setOnClickListener {
                val cur = getSharedPreferences("training_widget", MODE_PRIVATE).getInt(key, def)
                android.app.TimePickerDialog(this@SetupActivity, { _, h, m -> RwStore.putInt(this@SetupActivity, key, h * 60 + m); paint() }, cur / 60, cur % 60, true).show()
            }
        }
        fun hoursRow(title: String, kf: String, df: Int, kt: String, dt: Int) = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(0, (8 * d).toInt(), 0, 0)
            addView(TextView(this@SetupActivity).apply { text = title; setTextColor(0xFFF2F4F8.toInt()); textSize = 14.5f }, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(timeChip(kf, df))
            addView(TextView(this@SetupActivity).apply { text = "  –  "; setTextColor(0xFF9AA3B2.toInt()) })
            addView(timeChip(kt, dt))
        }
        val dflt = RwSettings()
        customBox.addView(hoursRow("Summer (Apr–Oct)", RwStore.K_S_FROM, dflt.summerFrom, RwStore.K_S_TO, dflt.summerTo))
        customBox.addView(hoursRow("Winter (Nov–Mar)", RwStore.K_W_FROM, dflt.winterFrom, RwStore.K_W_TO, dflt.winterTo))
        fun paintMode() {
            val auto = RwStore.load(this).auto
            modeChips.forEachIndexed { i, tv ->
                val sel = (i == 0) == auto
                tv.setBackgroundResource(if (sel) R.drawable.chip_on else R.drawable.chip_off)
                tv.setTextColor(if (sel) 0xFFF2F4F8.toInt() else 0xFF9AA3B2.toInt())
            }
            customBox.visibility = if (auto) View.GONE else View.VISIBLE
        }
        modeChips.forEachIndexed { i, tv ->
            tv.apply {
                text = if (i == 0) "Auto (daylight)" else "Custom"; textSize = 14f; gravity = android.view.Gravity.CENTER
                setPadding((8 * d).toInt(), (9 * d).toInt(), (8 * d).toInt(), (9 * d).toInt())
                setOnClickListener { RwStore.putBool(this@SetupActivity, RwStore.K_AUTO, i == 0); paintMode() }
            }
            modeRow.addView(tv, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = (8 * d).toInt() })
        }
        paintMode()
        out += modeRow
        out += customBox
        out += note("Auto runs from 30 minutes before sunrise to 30 minutes after sunset. Night windows are only used when nothing fits in daylight.")

        // ---- rules
        out += head("Your rules")
        fun stepper(label: String, help: String, get: () -> Double, set: (Double) -> Unit, min: Double, max: Double, step: Double, unit: String): View {
            val box = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(0, (10 * d).toInt(), 0, 0) }
            val row = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            val value = TextView(this).apply { setTextColor(0xFFF2F4F8.toInt()); textSize = 16f; gravity = android.view.Gravity.CENTER; minWidth = (72 * d).toInt() }
            fun refresh() { value.text = String.format(java.util.Locale.US, "%.0f", get()) + unit }
            fun btn(t: String, delta: Double) = TextView(this).apply {
                text = t; textSize = 18f; setTextColor(0xFF4EA1FF.toInt()); gravity = android.view.Gravity.CENTER
                setBackgroundResource(R.drawable.chip_off)
                setOnClickListener { set((get() + delta).coerceIn(min, max)); refresh() }
            }
            row.addView(TextView(this).apply { text = label; setTextColor(0xFFF2F4F8.toInt()); textSize = 14.5f },
                android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(btn("−", -step), android.widget.LinearLayout.LayoutParams((40 * d).toInt(), (36 * d).toInt()))
            row.addView(value)
            row.addView(btn("+", step), android.widget.LinearLayout.LayoutParams((40 * d).toInt(), (36 * d).toInt()))
            refresh()
            box.addView(row)
            box.addView(TextView(this).apply { text = help; setTextColor(0xFF6B7386.toInt()); textSize = 12f })
            return box
        }
        val c = this
        out += stepper("UV limit", "Windows above this UV are penalised (Long runs use this minus 1; TT may break it, then a cap).",
            { RwStore.load(c).uvLimit }, { RwStore.putFloat(c, RwStore.K_UV, it) }, 1.0, 11.0, 1.0, "")
        out += stepper("Hot (Nov–Mar)", "From this temperature a winter hour counts as hot.",
            { RwStore.load(c).hotWinter }, { RwStore.putFloat(c, RwStore.K_HOT_W, it) }, 8.0, 30.0, 1.0, " °C")
        out += stepper("Hot (Apr–Oct)", "From this temperature a summer hour counts as hot.",
            { RwStore.load(c).hotSummer }, { RwStore.putFloat(c, RwStore.K_HOT_S, it) }, 10.0, 35.0, 1.0, " °C")
        out += stepper("Cold", "Below this an hour counts as cold (feels-like is used below 5 °C and above 25 °C).",
            { RwStore.load(c).cold }, { RwStore.putFloat(c, RwStore.K_COLD, it) }, -10.0, 12.0, 1.0, " °C")
        out += stepper("Humid (dew point)", "At or above this dew point the air counts as humid.",
            { RwStore.load(c).humid }, { RwStore.putFloat(c, RwStore.K_HUMID, it) }, 8.0, 24.0, 1.0, " °C")
        out += stepper("Buffer", "Added to the run's planned length: warm-up, getting ready, shoes on.",
            { RwStore.load(c).bufferMin.toDouble() }, { RwStore.putInt(c, RwStore.K_BUFFER, it.toInt()) }, 0.0, 90.0, 5.0, " min")

        // ---- Rainbow key
        out += head("Rainbow API key")
        rwKeyLabel = TextView(this).apply { setTextColor(0xFFF2F4F8.toInt()); textSize = 14.5f; setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt()) }
        out += rwKeyLabel!!
        val keyField = field("Paste your Rainbow key", true)
        out += keyField
        out += smallButton("Save key") {
            val v = SecureKey.clean(keyField.text.toString())
            if (v.isEmpty()) rwSay("Paste the key first.")
            else if (SecureKey.save(this, v)) {
                keyField.setText("")
                rwRefreshLabels(); rwSay("✓ Key saved (encrypted on this phone). Fetching…")
                RunWindows.refreshAsync(this, manual = true) { rep -> rwSay(if (rep.anyOk) "✓ Updated" + (if (rep.failed.isEmpty()) "" else "; skipped: " + rep.failed.joinToString(", ")) else "Couldn't load: " + rep.failed.joinToString(", ")); rwRefreshLabels() }
            } else rwSay("This phone couldn't store the key securely, so it was not saved.")
        }
        out += smallButton("Test key") {
            val typed = SecureKey.clean(keyField.text.toString())
            val k = typed.ifEmpty { SecureKey.load(this).orEmpty() }
            if (k.isEmpty()) rwSay("Paste or save a key first.")
            else {
                rwSay("Testing…")
                Thread {
                    val msg = WeatherApi.testKey(this, k)
                    runOnUiThread { rwSay(msg); rwRefreshLabels() }
                }.start()
            }
        }
        out += smallButton("Remove key") {
            SecureKey.remove(this)
            RwStore.drop(this, "rainbow"); RwStore.drop(this, "nowcast")
            keyField.setText("")
            rwRefreshLabels(); rwSay("Key removed.")
        }
        rwCalls = TextView(this).apply { setTextColor(0xFF9AA3B2.toInt()); textSize = 13f; setPadding(0, (8 * d).toInt(), 0, 0) }
        out += rwCalls!!
        out += note("The key is stored encrypted with an Android Keystore key, left out of backups and logs, and sent only to api.rainbow.ai. Rainbow's free plan allows 10,000 calls a month; the app uses about 2 per refresh.")
        out += rwStatus!!
        rwRefreshLabels()
        return out
    }

    override fun onResume() {
        super.onResume()
        showBatteryStatus()
    }

    /** Samsung/Android battery saving can stop the daily refresh; offer the one-tap exemption. */
    private fun showBatteryStatus() {
        val tv = findViewById<TextView>(R.id.battery_status)
        if (DailyRefresh.batteryUnrestricted(this)) {
            tv.text = "✓ Background refresh allowed: widgets update hourly and every morning"
            tv.setTextColor(0xFF3DD68C.toInt()); tv.setOnClickListener(null)
        } else {
            tv.text = "⚠ Battery saving may stop widgets refreshing on their own. Tap to allow background refresh."
            tv.setTextColor(0xFFFFC53D.toInt())
            tv.setOnClickListener {
                runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName")))
                }.onFailure { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
        }
    }

    /** "Send to intervals.icu": one button per value you entered, each confirming before it writes. */
    private fun buildSyncButtons() {
        val box = findViewById<android.widget.LinearLayout>(R.id.sync_buttons)
        box.removeAllViews()
        val creds = Prefs.credentials(this)
        val snap = Prefs.snapshot(this)
        val today = java.time.LocalDate.now().toString()
        val todayIcu = snap?.days?.lastOrNull()?.takeIf { it.date == today }
        data class Item(val label: String, val mine: String?, val theirs: String, val send: (IcuApi) -> Unit)
        val items = listOf(
            Item("Weight", Prefs.weightOverride(this)?.let { String.format(java.util.Locale.US, "%.1f kg", it) }, snap?.weight?.let { "%.1f kg".format(it) } ?: "none") { api ->
                api.updateWellness(today, org.json.JSONObject().put("weight", Prefs.weightOverride(this))) },
            Item("Resting HR", Prefs.rhrOverride(this)?.let { "${it.roundToInt()} bpm" }, todayIcu?.rhr?.let { "${it.roundToInt()} bpm" } ?: "none") { api ->
                api.updateWellness(today, org.json.JSONObject().put("restingHR", Prefs.rhrOverride(this)!!.roundToInt())) },
            Item("HRV", Prefs.hrvOverride(this)?.let { "${it.roundToInt()} ms" }, todayIcu?.hrv?.let { "${it.roundToInt()} ms" } ?: "none") { api ->
                api.updateWellness(today, org.json.JSONObject().put("hrv", Prefs.hrvOverride(this)!!)) },
            Item("FTP", Prefs.cpOverride(this)?.let { "${it.roundToInt()} W" }, snap?.runFtp?.let { "${it.roundToInt()} W" } ?: "none") { api ->
                api.updateRunFtp(Prefs.cpOverride(this)!!.roundToInt()) },
        )
        for (it in items) {
            if (it.mine == null) continue
            val inSync = it.mine == it.theirs || sentToday["${today}|${it.label}"] == it.mine
            if (inSync) {
                box.addView(TextView(this).apply {
                    text = "✓ ${it.label} ${it.mine}: in sync with intervals.icu"
                    setTextColor(0xFF3DD68C.toInt()); textSize = 14f
                    setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
                })
                continue
            }
            val b = Button(this).apply {
                text = "Send ${it.label}: ${it.theirs} → ${it.mine}"
                isAllCaps = false
                setTextColor(0xFFF2F4F8.toInt())
                setBackgroundResource(R.drawable.input_bg)
            }
            b.setOnClickListener { _ ->
                if (creds == null) { findViewById<TextView>(R.id.status).text = "Connect intervals.icu first."; return@setOnClickListener }
                android.app.AlertDialog.Builder(this)
                    .setTitle("Change ${it.label} in intervals.icu?")
                    .setMessage("intervals.icu now: ${it.theirs}\nNew value: ${it.mine}" + if (it.label == "FTP") "\n\nThis changes your Run FTP setting and the power zones based on it." else "\n\nThis is written to today's wellness entry.")
                    .setPositiveButton("Send") { _, _ ->
                        Thread {
                            val r = runCatching { it.send(IcuApi(creds)) }
                            runOnUiThread {
                                findViewById<TextView>(R.id.status).text = r.fold({ _ -> "✓ ${it.label} updated in intervals.icu" }, { e -> "Couldn't update ${it.label}: ${e.message}" })
                                if (r.isSuccess) {
                                    sentToday["${today}|${it.label}"] = it.mine
                                    buildSyncButtons()
                                    Refresh.now(this, replace = true)
                                }
                            }
                        }.start()
                    }
                    .setNegativeButton("Cancel", null).show()
            }
            box.addView(b, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (6 * resources.displayMetrics.density).toInt() })
        }
        if (box.childCount == 0) box.addView(TextView(this).apply {
            text = "Enter a value under Your numbers to get a send button here."; setTextColor(0xFF6B7386.toInt()); textSize = 12.5f
        })
    }

    /** Parses and stores the "Your numbers" fields; blank = automatic, unparseable = left unchanged. */
    private fun saveNumbers() {
        fun txt(id: Int) = findViewById<EditText>(id).text.toString().trim()
        fun num(id: Int) = txt(id).replace(',', '.').toDoubleOrNull()
        fun time(id: Int): Double? {
            val p = txt(id).split(":").mapNotNull { it.trim().toIntOrNull() }
            return when (p.size) { 2 -> p[0] * 60.0 + p[1]; 3 -> p[0] * 3600.0 + p[1] * 60 + p[2]; else -> null }
        }
        if (txt(R.id.num_weight).isEmpty()) Prefs.setWeightOverride(this, null) else num(R.id.num_weight)?.takeIf { it in 30.0..200.0 }?.let { Prefs.setWeightOverride(this, it) }
        if (txt(R.id.num_cp).isEmpty()) Prefs.setCpOverride(this, null) else num(R.id.num_cp)?.takeIf { it in 80.0..700.0 }?.let { Prefs.setCpOverride(this, it) }
        if (txt(R.id.num_rhr).isEmpty()) Prefs.setRhrOverride(this, null) else num(R.id.num_rhr)?.takeIf { it in 25.0..120.0 }?.let { Prefs.setRhrOverride(this, it) }
        if (txt(R.id.num_hrv).isEmpty()) Prefs.setHrvOverride(this, null) else num(R.id.num_hrv)?.takeIf { it in 5.0..300.0 }?.let { Prefs.setHrvOverride(this, it) }
        if (txt(R.id.num_subt).isEmpty()) Prefs.setSubtOverride(this, null) else time(R.id.num_subt)?.takeIf { it in 150.0..480.0 }?.let { Prefs.setSubtOverride(this, it / 60) }
        for ((id, key) in listOf(R.id.num_pb5 to "5k", R.id.num_pb10 to "10k", R.id.num_pbhm to "hm")) {
            if (txt(id).isEmpty()) Prefs.setManualPb(this, key, null) else time(id)?.let { Prefs.setManualPb(this, key, it) }
        }
    }

    private fun radio(label: String) = RadioButton(this).apply {
        id = View.generateViewId()
        text = label
        setTextColor(0xFFF2F4F8.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        val pad = (6 * resources.displayMetrics.density).roundToInt()
        setPadding(paddingLeft + pad, pad, pad, pad)
    }

    private fun resultIntent() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
}
