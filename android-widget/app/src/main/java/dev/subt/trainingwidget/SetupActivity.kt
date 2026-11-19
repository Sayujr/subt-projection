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

        findViewById<android.widget.CheckBox>(R.id.notify_morning).apply {
            isChecked = Prefs.notifyMorning(this@SetupActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setNotifyMorning(this@SetupActivity, on) }
        }
        findViewById<android.widget.CheckBox>(R.id.notify_alerts).apply {
            isChecked = Prefs.notifyAlerts(this@SetupActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setNotifyAlerts(this@SetupActivity, on) }
        }
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
            Triple(R.id.num_stryd, Prefs.strydCp(this)?.let { it.roundToInt().toString() }, "from the Stryd app (optional)"),
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

    private companion object {
        const val REQ_EXPORT = 41
        const val REQ_IMPORT = 42
        val sentToday = mutableMapOf<String, String>()
        val sectionOpen = mutableMapOf<String, Boolean>()
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
                fun label(o: Boolean) = (if (o) "▾  " else "▸  ") + title + (sub?.let { "\n     $it" } ?: "")
                text = label(isOpen)
                setTextColor(0xFFF2F4F8.toInt()); textSize = 17f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setPadding(0, (18 * d).toInt(), 0, (8 * d).toInt())
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
        section("wid", "Widgets", if (widgetMode) "This widget's graphs and period" else "Long-press a widget to change it", widgetMode, groups["wid"]!!)
        section("backup", "Backup", "Save or restore your settings and numbers", false, backupViews())
        val crashes = CrashLog.count(this)
        section("crash", "Crash reports", if (crashes == 0) "No crashes recorded" else "⚠ $crashes recorded, last ${CrashLog.lastTime(this)}", false, crashViews())
        section("not", "Notifications & refresh", if (batteryOk) "Background refresh allowed" else "⚠ Battery saving may block refresh", !batteryOk, groups["not"]!!)
        tail.forEach { root.addView(it) }
    }

    private fun smallButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setTextColor(0xFFF2F4F8.toInt()); setBackgroundResource(R.drawable.input_bg)
        setOnClickListener { onClick() }
        layoutParams = android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = (8 * resources.displayMetrics.density).toInt() }
    }

    private fun note(t: String) = TextView(this).apply { text = t; setTextColor(0xFF6B7386.toInt()); textSize = 12f; setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0) }

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
        )
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
        if (txt(R.id.num_stryd).isEmpty()) Prefs.setStrydCp(this, null) else num(R.id.num_stryd)?.takeIf { it in 80.0..700.0 }?.let { Prefs.setStrydCp(this, it) }
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
