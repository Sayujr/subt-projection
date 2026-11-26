package dev.subt.trainingwidget

import android.app.Activity
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/** Pieces of run-window screens shared by the day plan and the Today card. */
object RwUi {
    /** "Right now: ☁ Cloudy · ☀ Sunny · 💧 Raining": one tap records what it's like outside, to check the forecasts. */
    fun outsideRow(a: Activity, onDone: () -> Unit = {}): LinearLayout {
        val d = a.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).roundToInt()
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
        row.addView(TextView(a).apply {
            text = "Right now:"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTextColor(0xFF9AA3B2.toInt()); setPadding(0, 0, dp(8), 0)
        })
        for ((label, kind) in listOf("☁ Cloudy" to "cloudy", "☀ Sunny" to "sunny", "💧 Raining" to "rain")) {
            row.addView(TextView(a).apply {
                text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f); setTextColor(0xFFF2F4F8.toInt()); gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.chip_off); setPadding(dp(6), dp(9), dp(6), dp(9)); maxLines = 1
                setOnClickListener {
                    Scoring.recordUser(a, kind)
                    Toast.makeText(a, "Thanks — used to check the forecasts", Toast.LENGTH_SHORT).show()
                    onDone()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4) })
        }
        return row
    }
}
