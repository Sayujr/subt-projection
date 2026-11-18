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

    /** Pseudo widget id used for the in-app screens' settings. */
    const val APP_ID = -1

    internal const val UP = 0xFF3DD68C.toInt()
    internal const val DOWN = 0xFFFF7A7A.toInt()
    internal const val DIM = 0xFF9AA3B2.toInt()
    internal const val PLANNED = 0xFFFFC53D.toInt()
    internal const val TEXT = 0xFFF2F4F8.toInt()
    internal const val WIDGET_BG = 0xFF161A22.toInt()
    internal const val TILE_BG = 0xFF222733.toInt()

    internal const val CALENDAR_URL = "https://intervals.icu/calendar"
    internal const val FITNESS_URL = "https://intervals.icu/fitness"

    /** Everything a render needs, read once. */
    internal class State(ctx: Context) {
        val creds = Prefs.credentials(ctx)
        val snap = Prefs.snapshot(ctx)
        val error = Prefs.error(ctx)
        val refreshing = Prefs.isRefreshing(ctx)
        val today: LocalDate = LocalDate.now()
        val isToday get() = snap?.today == today.toString()
    }

    private enum class OneVariant { TILE, PLAIN, SLIM }

    // ---------------------------------------------------------------- all widgets

    private val providers = listOf(
        TrainingWidgetProvider::class.java,
        TrendWidgetProvider::class.java,
        CompactWidgetProvider::class.java,
        WorkoutsWidgetProvider::class.java,
        WeekWidgetProvider::class.java,
        RaceWidgetProvider::class.java,
        RecoveryWidgetProvider::class.java,
        SubtWidgetProvider::class.java,
    )

    fun allIds(ctx: Context): List<Int> {
        val mgr = AppWidgetManager.getInstance(ctx)
        return providers.flatMap { mgr.getAppWidgetIds(ComponentName(ctx, it)).toList() }
    }

    private fun ids(ctx: Context, mgr: AppWidgetManager, cls: Class<*>) = mgr.getAppWidgetIds(ComponentName(ctx, cls))

    fun renderAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        ids(ctx, mgr, TrainingWidgetProvider::class.java).forEach { renderFull(ctx, mgr, it) }
        ids(ctx, mgr, TrendWidgetProvider::class.java).forEach { renderTrend(ctx, mgr, it) }
        val compact = ids(ctx, mgr, CompactWidgetProvider::class.java)
        compact.forEach { renderCompact(ctx, mgr, it) }
        val list = ids(ctx, mgr, WorkoutsWidgetProvider::class.java)
        list.forEach { renderWorkouts(ctx, mgr, it) }
        ExtraWidgets.renderAll(ctx, mgr)
        val withList = compact + list
        if (withList.isNotEmpty()) mgr.notifyAppWidgetViewDataChanged(withList, R.id.workout_list)
    }

    internal fun sizeDp(mgr: AppWidgetManager, id: Int, defW: Int, defH: Int): Pair<Int, Int> {
        val o = mgr.getAppWidgetOptions(id)
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 } ?: defW
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).takeIf { it > 0 } ?: defH
        return w to h
    }

    /** What AUTO means for each widget: the look each one shipped with. */
    @Suppress("UNUSED_PARAMETER")
    fun autoStyleDescription(providerClass: String?): String = "All three in one chart"

    // ---------------------------------------------------------------- Full: workouts + graphs

    fun renderFull(ctx: Context, mgr: AppWidgetManager, id: Int) {
        val (w, h) = sizeDp(mgr, id, 300, 250)
        mgr.updateAppWidget(id, buildFull(ctx, id, w, h))
    }

    /** Full widget view at the given size. Also used for the app's home screen (id = [APP_ID]). */
    fun buildFull(ctx: Context, id: Int, widthDp: Int, heightDp: Int, inApp: Boolean = false): RemoteViews {
        val s = State(ctx)
        val large = heightDp >= 360 // roughly 4×4 and up
        val style = Prefs.style(ctx, id).let { if (it == GraphStyle.AUTO) GraphStyle.ONE else it }
        val views = RemoteViews(ctx.packageName, R.layout.widget_full)

        views.setTextViewText(R.id.title, titleText(s.today))
        views.setTextViewText(R.id.updated, updatedText(ctx, s))
        views.setOnClickPendingIntent(R.id.refresh, refreshIntent(ctx, TrainingWidgetProvider::class.java))
        setVerdict(views, s)
        if (s.creds == null) {
            views.setOnClickPendingIntent(R.id.root, setupIntent(ctx))
        } else {
            // On the home screen a tap opens the app; inside the app it opens intervals.icu
            views.setOnClickPendingIntent(R.id.workouts, if (inApp) browser(ctx, 11, CALENDAR_URL) else appIntent(ctx, "HOME"))
            views.setOnClickPendingIntent(R.id.graphs, if (inApp) browser(ctx, 12, FITNESS_URL) else appIntent(ctx, "HOME"))
        }

        // Height left after padding (24), header (28) and margins (14), shared by workout rows (22dp each) and graphs
        val available = heightDp - 66
        val minGraphs = if (style == GraphStyle.ONE) 110 else 92
        val rowsCap = if (large) (heightDp * 0.4 / 22).toInt() else 12
        val maxRows = minOf((available - minGraphs) / 22, rowsCap).coerceIn(1, 12)

        views.removeAllViews(R.id.workouts)
        var rows = 1
        val msg = workoutMessage(s)
        if (msg != null) {
            views.addView(R.id.workouts, message(ctx, msg))
        } else {
            val list = s.snap!!.workouts
            val shown = if (list.size > maxRows) list.take(maxRows - 1) else list
            shown.forEach { views.addView(R.id.workouts, row(ctx, it)) }
            if (shown.size < list.size) views.addView(R.id.workouts, message(ctx, "+${list.size - shown.size} more"))
            rows = minOf(list.size, maxRows)
        }

        views.removeAllViews(R.id.graphs)
        views.addView(
            R.id.graphs,
            graphs(ctx, s, style, OneVariant.TILE, widthDp - 24, available - rows * 22, Prefs.period(ctx, id))
        )
        return views
    }

    // ---------------------------------------------------------------- Trends: graphs only

    fun renderTrend(ctx: Context, mgr: AppWidgetManager, id: Int) {
        val s = State(ctx)
        val (widthDp, heightDp) = sizeDp(mgr, id, 300, 140)
        val style = Prefs.style(ctx, id).let { if (it == GraphStyle.AUTO) GraphStyle.ONE else it }
        val period = Prefs.period(ctx, id)
        val views = RemoteViews(ctx.packageName, R.layout.widget_trend)

        views.setOnClickPendingIntent(R.id.refresh, refreshIntent(ctx, TrendWidgetProvider::class.java))
        views.setOnClickPendingIntent(R.id.root, if (s.creds == null) setupIntent(ctx) else appIntent(ctx, "HOME"))
        views.setTextViewText(
            R.id.updated,
            if (s.creds == null) "Tap to connect intervals.icu" else "${Periods.label(period)} · ${updatedText(ctx, s)}"
        )
        views.removeAllViews(R.id.graphs)
        views.addView(R.id.graphs, graphs(ctx, s, style, OneVariant.PLAIN, widthDp - 24, heightDp - 42, period))
        mgr.updateAppWidget(id, views)
    }

    // ---------------------------------------------------------------- Compact: scrolling workout strip + graphs

    fun renderCompact(ctx: Context, mgr: AppWidgetManager, id: Int) {
        val s = State(ctx)
        val (widthDp, heightDp) = sizeDp(mgr, id, 300, 150)
        val style = Prefs.style(ctx, id).let { if (it == GraphStyle.AUTO) GraphStyle.ONE else it }
        val views = RemoteViews(ctx.packageName, R.layout.widget_compact)

        views.setTextViewText(R.id.title, titleText(s.today) + (if (s.refreshing) "  ·  updating…" else ""))
        views.setOnClickPendingIntent(R.id.refresh, refreshIntent(ctx, CompactWidgetProvider::class.java))
        setVerdict(views, s)
        setupList(ctx, views, s, id)
        if (s.creds != null) views.setOnClickPendingIntent(R.id.graphs, appIntent(ctx, "HOME"))

        // padding 18, header 18, list 26, margin 6
        views.removeAllViews(R.id.graphs)
        views.addView(R.id.graphs, graphs(ctx, s, style, OneVariant.SLIM, widthDp - 22, heightDp - 68, Prefs.period(ctx, id)))
        mgr.updateAppWidget(id, views)
    }

    // ---------------------------------------------------------------- Workouts: scrolling list only

    fun renderWorkouts(ctx: Context, mgr: AppWidgetManager, id: Int) {
        val s = State(ctx)
        val views = RemoteViews(ctx.packageName, R.layout.widget_workouts)
        views.setTextViewText(R.id.title, titleText(s.today) + (if (s.refreshing) "  ·  updating…" else ""))
        views.setOnClickPendingIntent(R.id.refresh, refreshIntent(ctx, WorkoutsWidgetProvider::class.java))
        setupList(ctx, views, s, id)
        mgr.updateAppWidget(id, views)
    }

    /** Wires the scrolling workout list used by the Compact and Workouts widgets. */
    private fun setupList(ctx: Context, views: RemoteViews, s: State, id: Int) {
        val count = if (workoutMessage(s) == null) s.snap!!.workouts.size else 0
        views.setTextViewText(R.id.more, if (count > 1) "+${count - 1} ↓" else "")

        val svc = Intent(ctx, WorkoutListService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        svc.data = Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME))
        @Suppress("DEPRECATION")
        views.setRemoteAdapter(R.id.workout_list, svc)
        views.setEmptyView(R.id.workout_list, R.id.workout_empty)
        views.setTextViewText(R.id.workout_empty, workoutMessage(s) ?: "")

        if (s.creds == null) {
            views.setOnClickPendingIntent(R.id.root, setupIntent(ctx))
            views.setOnClickPendingIntent(R.id.workout_empty, setupIntent(ctx))
        } else {
            views.setOnClickPendingIntent(R.id.root, appIntent(ctx, "HOME"))
            views.setOnClickPendingIntent(R.id.workout_empty, appIntent(ctx, "HOME"))
            views.setPendingIntentTemplate(R.id.workout_list, appIntent(ctx, "HOME"))
        }
    }

    /** Items for the scrolling list: still-to-do first, then completed. */
    fun listItems(ctx: Context): List<Workout> {
        val s = State(ctx)
        if (workoutMessage(s) != null) return emptyList()
        return s.snap!!.workouts.sortedBy { it.status == WorkoutStatus.DONE }
    }

    // ---------------------------------------------------------------- graphs block

    /**
     * Builds the graphs block for [style] sized to roughly [wDp] × [hDp].
     * Bitmaps are drawn at the estimated size; ImageViews stretch them to the exact space.
     */
    private fun graphs(
        ctx: Context, s: State, style: GraphStyle, variant: OneVariant, wDp: Int, hDp: Int, period: Int,
    ): RemoteViews {
        val layout = when (style) {
            GraphStyle.THREE -> R.layout.graphs_three
            GraphStyle.TWO -> R.layout.graphs_two
            else -> when (variant) {
                OneVariant.TILE -> R.layout.graphs_one_tile
                OneVariant.PLAIN -> R.layout.graphs_one_plain
                OneVariant.SLIM -> R.layout.graphs_one_slim
            }
        }
        val rv = RemoteViews(ctx.packageName, layout)
        val all = s.snap?.days.orEmpty()
        if (all.isEmpty()) return rv
        setNumbers(rv, all, period)

        val (days, futureDays) = chartSeries(s.snap!!, period, s.isToday)
        val d = ctx.resources.displayMetrics.density
        fun px(dp: Int) = (dp.coerceAtLeast(20) * d).roundToInt()

        when (style) {
            GraphStyle.THREE -> {
                val w = px((wDp - 12) / 3 - 18)
                val h = px(hDp - 58)
                rv.setImageViewBitmap(R.id.ctl_chart, Charts.line(days.map { it.ctl }, FormColors.FITNESS, w, h, d))
                rv.setImageViewBitmap(R.id.atl_chart, Charts.line(days.map { it.atl }, FormColors.FATIGUE, w, h, d))
                rv.setImageViewBitmap(R.id.form_chart, Charts.form(days.map { it.form }, w, h, d))
            }
            GraphStyle.TWO -> {
                val hDpChart = hDp - 58
                val h = px(hDpChart)
                rv.setImageViewBitmap(
                    R.id.ff_chart,
                    Charts.fitFat(days, px((wDp - 6) * 2 / 3 - 18), h, d, TILE_BG, labels = hDpChart >= 90)
                )
                rv.setImageViewBitmap(R.id.form_chart, Charts.form(days.map { it.form }, px((wDp - 6) / 3 - 18), h, d))
            }
            else -> {
                val (cw, ch, bg) = when (variant) {
                    OneVariant.TILE -> Triple(wDp - 20, hDp - 72, TILE_BG)
                    OneVariant.PLAIN -> Triple(wDp, hDp - 48, WIDGET_BG)
                    OneVariant.SLIM -> Triple(wDp, hDp - 38, WIDGET_BG)
                }
                // Two weeks of the plan ahead, dashed
                rv.setImageViewBitmap(R.id.trend_chart, Charts.combined(days, px(cw), px(ch), d, bg, labels = ch >= 110, future = futureDays))
            }
        }
        return rv
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

    /** "● PUSH / HOLD / BACK OFF / TAPER" in the widget header. */
    private fun setVerdict(views: RemoteViews, s: State) {
        val v = if (s.isToday) Verdict.compute(s.snap) else null
        views.setTextViewText(R.id.verdict, v?.let { "● " + it.call.label.uppercase(Locale.getDefault()) } ?: "")
        v?.let { views.setTextColor(R.id.verdict, it.call.color) }
    }

    /**
     * History and planned future for a chart period. THIS_WEEK = Monday so far (plus last Sunday as the
     * starting point) and the plan through next Sunday; other periods = the last N days + 2 weeks of plan.
     */
    fun chartSeries(snap: Snapshot, period: Int, fresh: Boolean): Pair<List<Day>, List<Day>> {
        val all = snap.days
        if (period == Periods.THIS_WEEK) {
            val monday = LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            val hist = all.filter { it.date >= monday.minusDays(1).toString() }.ifEmpty { all.takeLast(2) }
            val until = monday.plusDays(13).toString()
            val fut = if (fresh) snap.proj.filter { it.date > (hist.lastOrNull()?.date ?: "") && it.date <= until } else emptyList()
            return hist to fut
        }
        val hist = all.takeLast(period)
        val fut = if (fresh) snap.proj.filter { it.date > (hist.lastOrNull()?.date ?: "") }.take(14) else emptyList()
        return hist to fut
    }

    /** Short label for a period: 2w, 6w, 3mo, 1y, or Nd for a custom one. */
    internal fun periodShort(days: Int) = when (days) {
        Periods.THIS_WEEK -> "wk"
        14 -> "2w"; 28 -> "4w"; 42 -> "6w"; 90 -> "3mo"; 180 -> "6mo"; 365 -> "1y"
        else -> if (days % 7 == 0) "${days / 7}w" else "${days}d"
    }

    private fun setNumbers(views: RemoteViews, days: List<Day>, period: Int) {
        // Change over the period the chart shows (first day of the window → today)
        val last = days.last()
        val monday = LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).toString()
        val ref = if (period == Periods.THIS_WEEK) days.lastOrNull { it.date < monday } ?: days.first()
            else days.getOrNull(days.size - period) ?: days.first()
        val lbl = if (period == Periods.THIS_WEEK) "wk" else periodShort(minOf(period, days.size))
        views.setTextViewText(R.id.ctl_value, last.ctl.roundToInt().toString())
        setDelta(views, R.id.ctl_delta, last.ctl - ref.ctl, upColor = UP, downColor = DOWN, label = lbl)
        views.setTextViewText(R.id.atl_value, last.atl.roundToInt().toString())
        setDelta(views, R.id.atl_delta, last.atl - ref.atl, upColor = DIM, downColor = DIM, label = lbl)
        val zone = FormZone.of(last.form)
        views.setTextViewText(R.id.form_value, last.form.roundToInt().toString())
        views.setTextColor(R.id.form_value, zone.color)
        views.setTextViewText(R.id.form_zone, zone.label)
        views.setTextColor(R.id.form_zone, zone.color)
    }

    internal fun titleText(today: LocalDate): String {
        val d = today.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
        return "TODAY · ${d.uppercase(Locale.getDefault())}"
    }

    internal fun updatedText(ctx: Context, s: State): String = when {
        s.refreshing -> "updating…"
        s.error != null && s.snap != null -> "⚠ ${time(ctx, s.snap.fetchedAt)}"
        s.error != null -> "⚠ ${s.error}"
        s.snap != null -> time(ctx, s.snap.fetchedAt)
        else -> ""
    }

    internal fun setDelta(views: RemoteViews, viewId: Int, delta: Double?, upColor: Int, downColor: Int, label: String = "7d") {
        if (delta == null) {
            views.setTextViewText(viewId, ""); return
        }
        val rounded = (delta * 10).roundToInt() / 10.0
        val text = when {
            rounded > 0 -> "▲${fmt1(rounded)}"
            rounded < 0 -> "▼${fmt1(abs(rounded))}"
            else -> "＝"
        }
        views.setTextViewText(viewId, "$text $label")
        views.setTextColor(viewId, if (rounded > 0) upColor else if (rounded < 0) downColor else DIM)
    }

    internal fun fmt1(v: Double) = if (v >= 10) v.roundToInt().toString() else String.format(Locale.US, "%.1f", v)

    fun row(ctx: Context, w: Workout, namePrefix: String = ""): RemoteViews {
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
        r.setTextViewText(R.id.row_name, namePrefix + w.name)
        r.setTextViewText(R.id.row_meta, meta(w))
        return r
    }

    internal fun message(ctx: Context, text: String): RemoteViews =
        RemoteViews(ctx.packageName, R.layout.widget_message_row).apply { setTextViewText(R.id.message, text) }

    internal fun meta(w: Workout): String {
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

    internal fun sportIcon(type: String): String {
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

    internal fun time(ctx: Context, millis: Long): String =
        android.text.format.DateFormat.getTimeFormat(ctx).format(Date(millis))

    internal fun refreshIntent(ctx: Context, provider: Class<*>): PendingIntent = PendingIntent.getBroadcast(
        ctx, provider.name.hashCode(),
        Intent(ctx, provider).setAction(BaseWidgetProvider.ACTION_REFRESH),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    internal fun setupIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 10, Intent(ctx, SetupActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** Opens the app on the given screen (MainActivity tab name). */
    internal fun appIntent(ctx: Context, tab: String): PendingIntent = PendingIntent.getActivity(
        ctx, ("tab:$tab").hashCode(),
        Intent(ctx, MainActivity::class.java)
            .setData(Uri.parse("trainingtoday://screen/$tab"))
            .putExtra(MainActivity.EXTRA_TAB, tab)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    internal fun browser(ctx: Context, req: Int, url: String): PendingIntent = PendingIntent.getActivity(
        ctx, req, Intent(Intent.ACTION_VIEW, Uri.parse(url)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}
