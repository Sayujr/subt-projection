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

        val stored = Prefs.credentials(this)
        stored?.let {
            athlete.setText(if (it.athleteId == "0") "" else it.athleteId)
            key.setText(it.apiKey)
        }

        // Per-widget options (not for the workouts-only widget, which has no graphs)
        val provider = if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider?.className
        } else null
        val hasGraphs = provider != null && provider != WorkoutsWidgetProvider::class.java.name
        if (hasGraphs) {
            findViewById<View>(R.id.widget_section).visibility = View.VISIBLE
            findViewById<View>(R.id.widget_hint).visibility = View.GONE
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
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) finish() else status.text = "Saved ✓"
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
                        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                            status.postDelayed({ finish() }, 900)
                        }
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
