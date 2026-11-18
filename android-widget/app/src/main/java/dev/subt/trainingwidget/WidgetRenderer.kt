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
    private const val FITNESS = 0xFF4EA1FF.toInt()
    private const val FATIGUE = 0xFFC17BFF.toInt()
    private const val PLANNED = 0xFFFFC53D.toInt()
    private const val TEXT = 0xFFF2F4F8.toInt()

    fun renderAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, TrainingWidgetProvider::class.java))
        ids.forEach { render(ctx, mgr, it) }
    }

    fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
        val views = RemoteViews(ctx.packageName, R.layout.widget_training)
        val creds = Prefs.credentials(ctx)
        val snap = Prefs.snapshot(ctx)
        val error = Prefs.error(ctx)
        val refreshing = Prefs.isRefreshing(ctx)
        val today = LocalDate.now()

        val opts = mgr.getAppWidgetOptions(id)
        val widthDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 } ?: 300
        val heightDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).takeIf { it > 0 } ?: 200

        // Header
        val dateLabel = today.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
        views.setTextViewText(R.id.title, "TODAY · ${dateLabel.uppercase(Locale.getDefault())}")
        views.setTextViewText(
            R.id.updated,
            when {
                refreshing -> "updating…"
                error != null && snap != null -> "⚠ ${time(ctx, snap.fetchedAt)}"
                error != null -> "⚠ $error"
                snap != null -> time(ctx, snap.fetchedAt)
                else -> ""
            }
        )
        views.setOnClickPendingIntent(R.id.refresh, refreshIntent(ctx))

        // Taps: no credentials -> setup screen; otherwise open intervals.icu
        if (creds == null) {
            val setup = PendingIntent.getActivity(
                ctx, 10, Intent(ctx, SetupActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.root, setup)
        } else {
            views.setOnClickPendingIntent(R.id.workouts, browser(ctx, 11, "https://intervals.icu/calendar"))
            views.setOnClickPendingIntent(R.id.metrics, browser(ctx, 12, "https://intervals.icu/fitness"))
        }

        // Workouts
        views.removeAllViews(R.id.workouts)
        // Space left for rows after header (~30dp), metric tiles (~90dp) and padding (~30dp); ~22dp per row
        val maxRows = ((heightDp - 150) / 22).coerceIn(1, 7)
        when {
            creds == null -> views.addView(R.id.workouts, message(ctx, "Tap to connect intervals.icu"))
            snap == null -> views.addView(R.id.workouts, message(ctx, if (error != null) "⚠ $error" else "Loading…"))
            snap.today != today.toString() -> views.addView(R.id.workouts, message(ctx, "Updating for today…"))
            snap.workouts.isEmpty() -> views.addView(R.id.workouts, message(ctx, "Rest day · nothing planned"))
            else -> {
                val list = snap.workouts
                val shown = if (list.size > maxRows) list.take(maxRows - 1) else list
                shown.forEach { views.addView(R.id.workouts, row(ctx, it)) }
                if (shown.size < list.size) {
                    views.addView(R.id.workouts, message(ctx, "+${list.size - shown.size} more"))
                }
            }
        }

        // Metrics
        val days = snap?.days.orEmpty()
        if (days.isNotEmpty()) {
            val last = days.last()
            val weekAgo = days.getOrNull(days.size - 8)
            val density = ctx.resources.displayMetrics.density
            val chartWdp = ((widthDp - 24 - 12) / 3 - 18).coerceAtLeast(40)
            val wPx = (chartWdp * density).roundToInt()
            val hPx = (34 * density).roundToInt()

            views.setTextViewText(R.id.ctl_value, last.ctl.roundToInt().toString())
            setDelta(views, R.id.ctl_delta, weekAgo?.let { last.ctl - it.ctl }, upColor = UP, downColor = DOWN)
            views.setImageViewBitmap(R.id.ctl_chart, Charts.line(days.map { it.ctl }, FITNESS, wPx, hPx, density))

            views.setTextViewText(R.id.atl_value, last.atl.roundToInt().toString())
            setDelta(views, R.id.atl_delta, weekAgo?.let { last.atl - it.atl }, upColor = DIM, downColor = DIM)
            views.setImageViewBitmap(R.id.atl_chart, Charts.line(days.map { it.atl }, FATIGUE, wPx, hPx, density))

            val zone = FormZone.of(last.form)
            views.setTextViewText(R.id.form_value, last.form.roundToInt().toString())
            views.setTextColor(R.id.form_value, zone.color)
            views.setTextViewText(R.id.form_zone, zone.label)
            views.setTextColor(R.id.form_zone, zone.color)
            views.setImageViewBitmap(R.id.form_chart, Charts.form(days.map { it.form }, wPx, hPx, density))
        }

        mgr.updateAppWidget(id, views)
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

    private fun row(ctx: Context, w: Workout): RemoteViews {
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

    private fun refreshIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 1,
        Intent(ctx, TrainingWidgetProvider::class.java).setAction(TrainingWidgetProvider.ACTION_REFRESH),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun browser(ctx: Context, req: Int, url: String): PendingIntent = PendingIntent.getActivity(
        ctx, req, Intent(Intent.ACTION_VIEW, Uri.parse(url)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}
