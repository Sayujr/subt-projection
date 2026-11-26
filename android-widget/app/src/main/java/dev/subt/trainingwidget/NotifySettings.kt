package dev.subt.trainingwidget

import android.content.Context
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * Notification categories, each with its own switch and (where it has one) its own time.
 * Recovery-type notifications pause from 2 days before a race through race day.
 */
object NotifySettings {
    /** [defaultTime] in minutes after midnight; null = an alert that goes out when it happens (from the "not before" time). */
    data class Cat(val key: String, val label: String, val help: String, val defaultOn: Boolean, val defaultTime: Int?, val recovery: Boolean = false)

    val CATS = listOf(
        Cat("workout", "Today's workout", "The day's session with its power targets", true, 4 * 60),
        Cat("reminder", "Workout not done yet", "Only if today's session isn't done by then", false, 16 * 60),
        Cat("recovery", "Recovery check", "Today's call with resting HR, HRV, sleep and Form. Waits up to 2 h for today's resting HR", true, 9 * 60, recovery = true),
        Cat("rhr", "Resting HR spike", "Resting HR 5+ bpm above your normal", true, null, recovery = true),
        Cat("deviations", "Numbers differ from intervals.icu", "When your entered numbers and intervals.icu disagree", true, null),
        Cat("eftp", "New eFTP high", "intervals.icu estimates a new best", true, null),
        Cat("taper", "Taper off target", "Race-morning Fatigue projected above your range (last 7 days)", true, null),
        Cat("streak", "Weekly streak result", "Mondays", true, null),
        Cat("update", "App update ready", "A new version is ready in Settings → App updates", true, null),
        Cat("rw_tomorrow", "Tomorrow's run window", "The best time to run tomorrow, the evening before", true, 20 * 60),
        Cat("rw_today", "Today's run window", "Added to the morning workout notification", true, null),
        Cat("rw_shift", "Run window shifted", "When a refresh moves today's or tomorrow's best window by 30 min or more, or rain / risk changes", true, null),
    )
    const val ALERTS_FROM_DEFAULT = 9 * 60

    private fun sp(ctx: Context) = ctx.getSharedPreferences("training_widget", Context.MODE_PRIVATE)

    fun isOn(ctx: Context, c: Cat): Boolean {
        val p = sp(ctx)
        if (p.contains("ncat_${c.key}")) return p.getBoolean("ncat_${c.key}", c.defaultOn)
        // Earlier versions had three switches: carry them over
        return when (c.key) {
            "recovery" -> p.getBoolean("notify_morning", c.defaultOn)
            "workout", "reminder", "rw_tomorrow", "rw_today", "rw_shift" -> c.defaultOn
            else -> p.getBoolean("notify_alerts", c.defaultOn)
        }
    }
    fun setOn(ctx: Context, c: Cat, on: Boolean) = sp(ctx).edit().putBoolean("ncat_${c.key}", on).apply()
    fun time(ctx: Context, c: Cat): Int = sp(ctx).getInt("ntime_${c.key}", c.defaultTime ?: ALERTS_FROM_DEFAULT)
    fun setTime(ctx: Context, c: Cat, min: Int) = sp(ctx).edit().putInt("ntime_${c.key}", min).apply()
    fun alertsFrom(ctx: Context): Int = sp(ctx).getInt("ntime_alerts_from", ALERTS_FROM_DEFAULT)
    fun setAlertsFrom(ctx: Context, min: Int) = sp(ctx).edit().putInt("ntime_alerts_from", min).apply()
    fun pauseNearRace(ctx: Context): Boolean = sp(ctx).getBoolean("npause_race", true)
    fun setPauseNearRace(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("npause_race", on).apply()

    fun cat(key: String) = CATS.first { it.key == key }

    /** Times the app should wake to send notifications (enabled timed categories + the alerts time). */
    fun wakeTimes(ctx: Context): List<LocalTime> =
        (CATS.filter { it.defaultTime != null && isOn(ctx, it) }.map { time(ctx, it) } + alertsFrom(ctx))
            .distinct().map { LocalTime.of(it / 60, it % 60) }

    // ---- pure rules (tested)

    /** True from [atMin] for [hours] hours (no wrap past midnight). */
    fun inWindow(now: LocalTime, atMin: Int, hours: Int = 6): Boolean {
        val m = now.hour * 60 + now.minute
        return m >= atMin && m < atMin + hours * 60
    }

    /** The race that pauses recovery notifications today: 2 days before through race day. */
    fun pausingRace(today: LocalDate, races: List<Race>): Race? =
        races.firstOrNull { r -> runCatching { ChronoUnit.DAYS.between(today, LocalDate.parse(r.date)) }.getOrNull() in 0L..2L }

    fun fmt(min: Int) = "%02d:%02d".format(min / 60, min % 60)
}
