package dev.subt.trainingwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt
import dev.subt.trainingwidget.WidgetRenderer as R0

/** Medium widget: today and tomorrow, each with its window and weather strip. */
class RunWindowsWidgetProvider : BaseWidgetProvider() {
    override fun render(ctx: Context, mgr: AppWidgetManager, id: Int) = RwWidgets.render(ctx, mgr, id)

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        super.onUpdate(ctx, mgr, ids)
        RunWindowSchedule.ensure(ctx)
    }
}

/** Run windows on the home screen: one line and a mini strip on the Today widget, and the medium Run windows widget. */
object RwWidgets {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** A day's plan when a location is set and a forecast is cached; null otherwise (the widget then shows nothing). */
    fun planFor(ctx: Context, date: LocalDate): Weather.DayPlan? {
        if (!RunWindows.hasLocation(ctx)) return null
        return runCatching { RunWindows.plan(ctx, date) }.getOrNull()?.takeIf { !it.noData }
    }

    fun openDay(ctx: Context, date: LocalDate): PendingIntent = PendingIntent.getActivity(
        ctx, ("rw-day:$date").hashCode(),
        Intent(ctx, RunWindowActivity::class.java).putExtra(RunWindowActivity.EXTRA_DATE, date.toString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openSettings(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 11, Intent(ctx, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_OPEN, "rw"),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** "☁ Run any time 07:00–15:00 · Cool, cloudy · 9–12° · dry", "☁ Tight: 05:30–07:10 ⚠", or the split suggestion. */
    fun lineText(p: Weather.DayPlan): String {
        val w = p.window
        val sp = p.split
        val now = System.currentTimeMillis() / 1000
        val head = RwText.headline(p, zone, if (p.date == LocalDate.now()) now else null)
        return when {
            p.windows.isNotEmpty() && head.none -> "☁ Windows passed (${RwText.windowsList(p.windows, zone)})"
            p.windows.isNotEmpty() -> "☁ " + head.text + RwText.bestTag(p.best, p.run, p.hours, zone)?.let { " · $it" }.orEmpty()
            sp != null -> "☁ " + Weather.splitLabel(sp, zone)
            w == null -> "☁ No window fits today"
            p.passed -> "☁ Best was ${Weather.range(w, zone)}"
            else -> "☁ Best ${Weather.range(w, zone)}" + (if (w.icons.isEmpty()) "" else " " + w.icons.joinToString(" ")) + " · " + p.summary.short
        }
    }

    /** Height in dp the Today widget gives up for the line and strip. */
    const val TODAY_EXTRA_DP = 40

    /**
     * Fills the line and mini strip (no axis) of the Today widget. Returns true when they are shown, which is only when
     * a run-window plan exists.
     */
    fun addToday(ctx: Context, v: RemoteViews, widthDp: Int, plan: Weather.DayPlan?): Boolean {
        if (plan == null) return false
        val s = RwStore.load(ctx)
        val d = ctx.resources.displayMetrics.density
        v.setViewVisibility(R.id.rw_line, View.VISIBLE)
        v.setViewVisibility(R.id.rw_strip, View.VISIBLE)
        v.setTextViewText(R.id.rw_line, lineText(plan))
        val wPx = ((widthDp - 24).coerceAtLeast(120) * d).roundToInt()
        v.setImageViewBitmap(R.id.rw_strip, RwViews.strip(ctx, plan, s, wPx, mini = true, nowSec = System.currentTimeMillis() / 1000))
        val tap = openDay(ctx, plan.date)
        v.setOnClickPendingIntent(R.id.rw_line, tap)
        v.setOnClickPendingIntent(R.id.rw_strip, tap)
        return true
    }

    // ---------------------------------------------------------------- medium widget

    fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
        val (w, h) = R0.sizeDp(mgr, id, 300, 150)
        mgr.updateAppWidget(id, build(ctx, w, h))
    }

    fun renderAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        mgr.getAppWidgetIds(ComponentName(ctx, RunWindowsWidgetProvider::class.java)).forEach { render(ctx, mgr, it) }
    }

    private fun dayLabel(offset: Int, p: Weather.DayPlan): String {
        val name = if (offset == 0) "Today" else "Tomorrow"
        return name + " · " + (if (p.run.planned) p.run.name else "no run planned")
    }

    private fun winText(p: Weather.DayPlan): String {
        val w = p.window
        val sp = p.split
        val head = RwText.headline(p, zone, if (p.date == LocalDate.now()) System.currentTimeMillis() / 1000 else null)
        return when {
            p.windows.isNotEmpty() && head.none -> "was " + RwText.windowsList(p.windows, zone)
            p.windows.isNotEmpty() -> head.text.removePrefix("Run any time ")
            sp != null -> "Split " + Weather.hhmm(sp.first.start, zone) + " + " + Weather.hhmm(sp.second.start, zone)
            w == null -> "no window"
            p.passed -> "was " + Weather.range(w, zone)
            else -> Weather.range(w, zone) + (if (w.icons.isEmpty()) "" else " " + w.icons.joinToString(" "))
        }
    }

    fun build(ctx: Context, wDp: Int, hDp: Int): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_runwindows)
        val d = ctx.resources.displayMetrics.density
        v.setOnClickPendingIntent(R.id.refresh, R0.refreshIntent(ctx, RunWindowsWidgetProvider::class.java))
        val s = RwStore.load(ctx)
        fun message(t: String) {
            v.setViewVisibility(R.id.rw_msg, View.VISIBLE)
            v.setViewVisibility(R.id.row0, View.GONE)
            v.setViewVisibility(R.id.row1, View.GONE)
            v.setTextViewText(R.id.rw_msg, t)
        }
        if (s.lat == null || s.lon == null) {
            message("Set your location in Settings → Run windows")
            v.setOnClickPendingIntent(R.id.root, openSettings(ctx))
            return v
        }
        val today = LocalDate.now()
        val plans = runCatching { RunWindows.plans(ctx, today, 2) }.getOrDefault(emptyList())
        if (plans.isEmpty() || plans.all { it.noData }) {
            message("Forecast not loaded yet. Tap ↻ to fetch it.")
            return v
        }
        v.setTextViewText(R.id.updated, RunWindows.data(ctx)?.lastFetch?.takeIf { it > 0 }?.let { R0.time(ctx, it) } ?: "")
        // Two rows share the height left after padding (20), header (24) and gaps (8); each keeps ~16dp for its label
        val stripDp = ((hDp - 20 - 24 - 8) / 2f - 16f).coerceAtLeast(30f)
        val scale = (stripDp / RwViews.heightDp(false)).coerceIn(0.55f, 1f)
        val wPx = ((wDp - 20).coerceAtLeast(150) * d).roundToInt()
        val nowSec = System.currentTimeMillis() / 1000
        val rows = listOf(
            Triple(R.id.row0, R.id.rw_label0, R.id.rw_win0) to R.id.rw_strip0,
            Triple(R.id.row1, R.id.rw_label1, R.id.rw_win1) to R.id.rw_strip1,
        )
        rows.forEachIndexed { i, (ids, strip) ->
            val p = plans.getOrNull(i)
            val (row, label, win) = ids
            if (p == null || p.noData) {
                v.setViewVisibility(row, View.INVISIBLE)
                return@forEachIndexed
            }
            v.setViewVisibility(row, View.VISIBLE)
            v.setTextViewText(label, dayLabel(i, p))
            v.setTextViewText(win, winText(p))
            v.setImageViewBitmap(strip, RwViews.strip(ctx, p, s, wPx, mini = false, nowSec = if (i == 0) nowSec else null, dScale = scale))
            v.setOnClickPendingIntent(row, openDay(ctx, p.date))
            v.setOnClickPendingIntent(strip, openDay(ctx, p.date))
        }
        return v
    }
}
