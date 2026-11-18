package dev.subt.trainingwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object WidgetRenderer {

    private const val UP = 0xFF3DD68C.toInt()
    private const val DOWN = 0xFFFF7A7A.toInt()
    private const val DIM = 0xFF9AA3B2.toInt()
    private const val PLANNED = 0xFFFFC53D.toInt()
    private const val TEXT = 0xFFF2F4F8.toInt()
    private const val WIDGET_BG = 0xFF161A22.toInt()

    private const val CALENDAR_URL = "https://intervals.icu/calendar"
    private const val FITNESS_URL = "https://intervals.icu/fitness"

    /** Everything a render needs, read once. */
    private class State(ctx: Context) {
        val creds = Prefs.credentials(ctx)
        val snap = Prefs.snapshot(ctx)
        val error = Prefs.error(ctx)
        val refreshing = Prefs.isRefreshing(ctx)
        val today: LocalDate = LocalDate.now()
        val isToday get() = snap?.today == today.toString()
    }

    // ---------------------------------------------------------------- all widgets

    private val providers = listOf(
        TrainingWidgetProvider::class.java,
        TrendWidgetProvider::class.java,
        WorkoutsWidgetProvider::class.java,
    )

    fun allIds(ctx: Context): List<Int> {
        val mgr = AppWidgetManager.getInstance(ctx)
        return providers.flatMap { mgr.getAppWidgetIds(ComponentName(ctx, it)).toList() }
    }

    fun renderAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        mgr.getAppWidgetIds(ComponentName(ctx, TrainingWidgetProvider::class.java)).forEach { renderFull(ctx, mgr, it) }
        mgr.getAppWidgetIds(ComponentName(ctx, TrendWidgetProvider::class.java)).forEach { renderTrend(ctx, mgr, it) }
        val listIds = mgr.getAppWidgetIds(ComponentName(ctx, WorkoutsWidgetProvider::class.java))
        listIds.forEach { renderWorkouts(ctx, mgr, it) }
        if (listIds.isNotEmpty()) mgr.notifyAppWidgetViewDataChanged(listIds, R.id.workout_list)
    }

    private fun sizeDp(mgr: AppWidgetManager, id: Int, defW: Int, defH: Int): Pair<Int, Int> {
        val o = mgr.getAppWidgetOptions(id)
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 } ?: defW
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).takeIf { it > 0 } ?: defH
        return w to h
    }

    // ---------------------------------------------------------------- full widget

    fun renderFull(ctx: Context, mgr: AppWidgetManager, id: Int) {
        val s = State(ctx)
        val (widthDp, heightDp) = sizeDp(mgr, id, 300, 200)
        val large = heightDp >= 300
        val views = RemoteViews(ctx.packageName, if (large) R.layout.widget_training_large else R.layout.widget_training)

        views.setTextViewText(R.id.title, titleText(s.today))
        views.setTextViewText(R.id.updated, updatedText(ctx, s))
        views.setOnClickPendingIntent(R.id.refresh, refreshIntent(ctx, TrainingWidgetProvider::class.java))
        if (s.creds == null) {
            views.setOnClickPendingIntent(R.id.root, setupIntent(ctx))
        } else {
            views.setOnClickPendingIntent(R.id.workouts, browser(ctx, 11, CALENDAR_URL))
            views.setOnClickPendingIntent(R.id.metrics, browser(ctx, 12, FITNESS_URL))
        }

        // Workouts: rows fill whatever height is left above the tiles (~22dp each)
        views.removeAllViews(R.id.workouts)
        val tilesDp = if (large) 128 else 90
        val maxRows = ((heightDp - 60 - tilesDp) / 22).coerceIn(1, 12)
        val msg = workoutMessage(s)
        if (msg != null) {
            views.addView(R.id.workouts, message(ctx, msg))
        } else {
            val list = s.snap!!.workouts
            val shown = if (list.size > maxRows) list.take(maxRows - 1) else list
            shown.forEach { views.addView(R.id.workouts, row(ctx, it)) }
            if (shown.size < list.size) views.addView(R.id.workouts, message(ctx, "+${list.size - shown.size} more"))
        }

        // Metric tiles
        val days = s.snap?.days.orEmpty()
        if (days.isNotEmpty()) {
            val density = ctx.resources.displayMetrics.density
            val chartWdp = ((widthDp - 24 - 12) / 3 - 18).coerceAtLeast(40)
            val wPx = (chartWdp * density).roundToInt()
            val hPx = ((if (large) 64 else 34) * density).roundToInt()
            setNumbers(views, days)
            views.setImageViewBitmap(R.id.ctl_chart, Charts.line(days.map { it.ctl }, FormColors.FITNESS, wPx, hPx, density))
            views.setImageViewBitmap(R.id.atl_chart, Charts.line(days.map { it.atl }, FormColors.FATIGUE, wPx, hPx, density))
            views.setImageViewBitmap(R.id.form_chart, Charts.form(days.map { it.form }, wPx, hPx, density))
        }
        mgr.updateAppWidget(id, views)
    }

    // ---------------------------------------------------------------- trends-only widget

    fun renderTrend(ctx: Context, mgr: AppWidgetManager, id: Int) {
        val s = State(ctx)
        val (widthDp, heightDp) = sizeDp(mgr, id, 300, 140)
        val views = RemoteViews(ctx.packageName, R.layout.widget_trend)

        views.setOnClickPendingIntent(R.id.refresh, refreshIntent(ctx, TrendWidgetProvider::class.java))
        views.setOnClickPendingIntent(R.id.root, if (s.creds == null) setupIntent(ctx) else browser(ctx, 13, FITNESS_URL))
        views.setTextViewText(
            R.id.updated,
            if (s.creds == null) "Tap to connect intervals.icu" else "42 days · ${updatedText(ctx, s)}"
        )

        val days = s.snap?.days.orEmpty()
        if (days.isNotEmpty()) {
            setNumbers(views, days)
            val density = ctx.resources.displayMetrics.density
            val wPx = ((widthDp - 24).coerceAtLeast(60) * density).roundToInt()
            // padding 24 + numbers ~42 + footer ~14 + margin 6
            val hPx = ((heightDp - 86).coerceAtLeast(30) * density).roundToInt()
            views.setImageViewBitmap(R.id.trend_chart, Charts.combined(days, wPx, hPx, density, WIDGET_BG))
        }
        mgr.updateAppWidget(id, views)
    }

    // ---------------------------------------------------------------- workouts-only (scrolling) widget

    fun renderWorkouts(ctx: Context, mgr: AppWidgetManager, id: Int) {
        val s = State(ctx)
        val views = RemoteViews(ctx.packageName, R.layout.widget_workouts)
        views.setTextViewText(R.id.title, titleText(s.today) + (if (s.refreshing) "  ·  updating…" else ""))
        views.setOnClickPendingIntent(R.id.refresh, refreshIntent(ctx, WorkoutsWidgetProvider::class.java))

        val count = if (workoutMessage(s) == null) s.snap!!.workouts.size else 0
        views.setTextViewText(R.id.more, if (count > 1) "+${count - 1} ↓" else "")

        val svc = Intent(ctx, WorkoutListService::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        svc.data = Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME))
        @Suppress("DEPRECATION")
        views.setRemoteAdapter(R.id.workout_list, svc)
        views.setEmptyView(R.id.workout_list, R.id.workout_empty)
        views.setTextViewText(R.id.workout_empty, workoutMessage(s) ?: "")

        if (s.creds == null) {
            views.setOnClickPendingIntent(R.id.root, setupIntent(ctx))
            views.setOnClickPendingIntent(R.id.workout_empty, setupIntent(ctx))
        } else {
            views.setOnClickPendingIntent(R.id.workout_empty, browser(ctx, 14, CALENDAR_URL))
            views.setPendingIntentTemplate(R.id.workout_list, browser(ctx, 15, CALENDAR_URL))
        }
        mgr.updateAppWidget(id, views)
    }

    /** Items for the scrolling list: still-to-do first, then completed. */
    fun listItems(ctx: Context): List<Workout> {
        val s = State(ctx)
        if (workoutMessage(s) != null) return emptyList()
        return s.snap!!.workouts.sortedBy { it.status == WorkoutStatus.DONE }
    }

    // ---------------------------------------------------------------- shared pieces

    /** Non-null when there's no workout list to show. */
    private fun workoutMessage(s: State): String? = when {
        s.creds == null -> "Tap to connect intervals.icu"
        s.snap == null -> if (s.error != null) "⚠ ${s.error}" else "Loading…"
        !s.isToday -> "Updating for today…"
        s.snap.workouts.isEmpty() -> "Rest day · nothing planned"
        else -> null
    }

    private fun setNumbers(views: RemoteViews, days: List<Day>) {
        val last = days.last()
        val weekAgo = days.getOrNull(days.size - 8)
        views.setTextViewText(R.id.ctl_value, last.ctl.roundToInt().toString())
        setDelta(views, R.id.ctl_delta, weekAgo?.let { last.ctl - it.ctl }, upColor = UP, downColor = DOWN)
        views.setTextViewText(R.id.atl_value, last.atl.roundToInt().toString())
        setDelta(views, R.id.atl_delta, weekAgo?.let { last.atl - it.atl }, upColor = DIM, downColor = DIM)
        val zone = FormZone.of(last.form)
        views.setTextViewText(R.id.form_value, last.form.roundToInt().toString())
        views.setTextColor(R.id.form_value, zone.color)
        views.setTextViewText(R.id.form_zone, zone.label)
        views.setTextColor(R.id.form_zone, zone.color)
    }

    private fun titleText(today: LocalDate): String {
        val d = today.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
        return "TODAY · ${d.uppercase(Locale.getDefault())}"
    }

    private fun updatedText(ctx: Context, s: State): String = when {
        s.refreshing -> "updating…"
        s.error != null && s.snap != null -> "⚠ ${time(ctx, s.snap.fetchedAt)}"
        s.error != null -> "⚠ ${s.error}"
        s.snap != null -> time(ctx, s.snap.fetchedAt)
        else -> ""
    }

    private fun setDelta(views: RemoteViews, viewId: Int, delta: Double?, upColor: Int, downColor: Int) {
        if (delta == null) {
            views.setTextViewText(viewId, ""); return
        }
        val rounded = (delta * 10).roundToInt() / 10.0
        val text = when {
            rounded > 0 -> "▲${fmt1(rounded)}"
            rounded < 0 -> "▼${fmt1(abs(rounded))}"
            else -> "＝"
        }
        views.setTextViewText(viewId, "$text 7d")
        views.setTextColor(viewId, if (rounded > 0) upColor else if (rounded < 0) downColor else DIM)
    }

    private fun fmt1(v: Double) = if (v >= 10) v.roundToInt().toString() else String.format(Locale.US, "%.1f", v)

    fun row(ctx: Context, w: Workout): RemoteViews {
        val r = RemoteViews(ctx.packageName, R.layout.widget_workout_row)
        when (w.status) {
            WorkoutStatus.DONE -> {
                r.setTextViewText(R.id.row_status, "✓"); r.setTextColor(R.id.row_status, UP)
                r.setTextColor(R.id.row_name, DIM)
            }
            WorkoutStatus.RACE -> {
                r.setTextViewText(R.id.row_status, "★"); r.setTextColor(R.id.row_status, PLANNED)
                r.setTextColor(R.id.row_name, TEXT)
            }
            WorkoutStatus.PLANNED -> {
                r.setTextViewText(R.id.row_status, "○"); r.setTextColor(R.id.row_status, PLANNED)
                r.setTextColor(R.id.row_name, TEXT)
            }
        }
        r.setTextViewText(R.id.row_sport, sportIcon(w.type))
        r.setTextViewText(R.id.row_name, w.name)
        r.setTextViewText(R.id.row_meta, meta(w))
        return r
    }

    private fun message(ctx: Context, text: String): RemoteViews =
        RemoteViews(ctx.packageName, R.layout.widget_message_row).apply { setTextViewText(R.id.message, text) }

    private fun meta(w: Workout): String {
        val parts = mutableListOf<String>()
        when {
            w.seconds > 0 -> {
                val m = (w.seconds + 30) / 60
                parts += if (m >= 60) "${m / 60}h${"%02d".format(m % 60)}" else "${m}m"
            }
            w.distanceM > 0 -> parts += String.format(Locale.US, "%.1f km", w.distanceM / 1000)
        }
        if (w.load > 0) parts += "${w.load}"
        return parts.joinToString(" · ")
    }

    private fun sportIcon(type: String): String {
        val t = type.lowercase(Locale.US)
        return when {
            "run" in t -> "🏃"
            "ride" in t || "cycl" in t || "bike" in t -> "🚴"
            "swim" in t -> "🏊"
            "weight" in t || "strength" in t || "crossfit" in t -> "🏋️"
            "yoga" in t || "pilates" in t -> "🧘"
            "hike" in t -> "🥾"
            "walk" in t -> "🚶"
            "row" in t -> "🚣"
            "ski" in t -> "⛷️"
            else -> "•"
        }
    }

    private fun time(ctx: Context, millis: Long): String =
        android.text.format.DateFormat.getTimeFormat(ctx).format(Date(millis))

    private fun refreshIntent(ctx: Context, provider: Class<*>): PendingIntent = PendingIntent.getBroadcast(
        ctx, provider.name.hashCode(),
        Intent(ctx, provider).setAction(BaseWidgetProvider.ACTION_REFRESH),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun setupIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 10, Intent(ctx, SetupActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun browser(ctx: Context, req: Int, url: String): PendingIntent = PendingIntent.getActivity(
        ctx, req, Intent(Intent.ACTION_VIEW, Uri.parse(url)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}
