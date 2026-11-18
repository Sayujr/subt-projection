package dev.subt.trainingwidget

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The app: a home screen showing the full widget at full-screen size, plus one screen each for
 * This week, Race, Recovery and SubT. Each screen is the matching widget rendered large, so the
 * app and the home-screen widgets always look the same.
 */
class MainActivity : Activity() {

    private enum class Tab(val icon: String, val label: String, val title: String) {
        HOME("◉", "Today", "Training Today"),
        WEEK("▥", "Week", "This week"),
        RACE("⚑", "Race", "Next race"),
        RECOVERY("♥", "Recovery", "Recovery"),
        SUBT("⏱", "SubT", "SubT pace"),
    }

    private var tab = Tab.HOME
    private lateinit var content: FrameLayout
    private val tabViews = mutableMapOf<Tab, TextView>()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "snapshot" || key == "subt" || key == "refreshing_since" || key == "error") {
            content.post { show() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        content = findViewById(R.id.content)
        savedInstanceState?.getString("tab")?.let { name -> Tab.entries.firstOrNull { it.name == name }?.let { tab = it } }

        findViewById<android.view.View>(R.id.open_settings).setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        buildTabs()

        if (Prefs.credentials(this) == null) {
            startActivity(Intent(this, SetupActivity::class.java))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("tab", tab.name)
    }

    override fun onResume() {
        super.onResume()
        getSharedPreferences("training_widget", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefsListener)
        Refresh.schedulePeriodic(this)
        val snap = Prefs.snapshot(this)
        val stale = snap == null || System.currentTimeMillis() - snap.fetchedAt > 15 * 60_000 ||
            snap.today != java.time.LocalDate.now().toString()
        if ((stale && Prefs.credentials(this) != null) || Prefs.subt(this) == null) Refresh.now(this, replace = false)
        content.post { show() }
    }

    override fun onPause() {
        getSharedPreferences("training_widget", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onPause()
    }

    private fun buildTabs() {
        val bar = findViewById<LinearLayout>(R.id.tabs)
        for (t in Tab.entries) {
            val tv = TextView(this).apply {
                text = "${t.icon}\n${t.label}"
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setLineSpacing(0f, 1.15f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener { tab = t; show() }
            }
            tabViews[t] = tv
            bar.addView(tv)
        }
    }

    private fun show() {
        tabViews.forEach { (t, tv) -> tv.setTextColor(if (t == tab) 0xFF4EA1FF.toInt() else 0xFF6B7386.toInt()) }
        findViewById<TextView>(R.id.screen_title).text = tab.title

        val d = resources.displayMetrics.density
        val wDp = ((content.width - content.paddingLeft - content.paddingRight) / d).roundToInt()
        val hDp = ((content.height - content.paddingBottom) / d).roundToInt()
        if (wDp <= 0 || hDp <= 0) {
            content.post { show() }; return
        }
        val rv: RemoteViews = when (tab) {
            Tab.HOME -> WidgetRenderer.buildFull(this, WidgetRenderer.APP_ID, wDp, hDp)
            Tab.WEEK -> ExtraWidgets.buildWeek(this, wDp, hDp)
            Tab.RACE -> ExtraWidgets.buildRace(this, wDp, hDp)
            Tab.RECOVERY -> ExtraWidgets.buildRecovery(this, WidgetRenderer.APP_ID, wDp, hDp)
            Tab.SUBT -> ExtraWidgets.buildSubt(this, wDp, hDp)
        }
        content.removeAllViews()
        try {
            val view = rv.apply(this, content)
            content.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        } catch (e: Exception) {
            content.addView(TextView(this).apply {
                text = "Couldn't draw this screen: ${e.message}"
                setTextColor(0xFF9AA3B2.toInt())
            })
        }
    }
}
