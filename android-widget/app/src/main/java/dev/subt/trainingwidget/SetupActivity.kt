package dev.subt.trainingwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
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
        val fields = listOf(
            Triple(R.id.num_weight, Prefs.weightOverride(this)?.let { String.format(java.util.Locale.US, "%.1f", it) }, autoKg?.let { "auto: %.1f (intervals.icu)".format(it) }),
            Triple(R.id.num_cp, Prefs.cpOverride(this)?.let { it.roundToInt().toString() }, autoCp?.let { "auto: ${it.roundToInt()} W (intervals.icu FTP)" }),
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
