package dev.subt.trainingwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import kotlin.math.roundToInt

/** First-run / reconfigure screen: enter intervals.icu athlete ID + API key, test, save. */
class SetupActivity : Activity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

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

        Prefs.credentials(this)?.let {
            athlete.setText(if (it.athleteId == "0") "" else it.athleteId)
            key.setText(it.apiKey)
        }

        save.setOnClickListener {
            val apiKey = key.text.toString().trim()
            var ath = athlete.text.toString().trim()
            if (ath.isNotEmpty() && ath.all { it.isDigit() }) ath = "i$ath"
            if (apiKey.isEmpty()) {
                status.text = "Paste your API key first."
                return@setOnClickListener
            }
            val creds = Credentials(ath.ifEmpty { "0" }, apiKey)
            save.isEnabled = false
            status.text = "Checking with intervals.icu…"

            Thread {
                val result = runCatching { IcuApi(creds).fetchSnapshot() }
                runOnUiThread {
                    save.isEnabled = true
                    result.onSuccess { snap ->
                        Prefs.saveCredentials(this, creds)
                        Prefs.saveSnapshot(this, snap)
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

    private fun resultIntent() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
}
