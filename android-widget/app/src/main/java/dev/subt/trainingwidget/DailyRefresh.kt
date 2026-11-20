package dev.subt.trainingwidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Safety net on top of the hourly WorkManager job, so widgets always roll over to the new day:
 * alarms just after midnight, at 5:30 and at 9:00 (notifications) redraw the widgets at once and pulls
 * fresh data. Re-armed after every alarm, reboot, app update and clock/time-zone change.
 */
object DailyRefresh {
    private val TIMES = listOf(LocalTime.of(0, 5), LocalTime.of(5, 30), LocalTime.of(9, 0))

    fun arm(ctx: Context) {
        val now = LocalDateTime.now()
        // Fixed refreshes plus your notification times (a minute early, so data is fresh when they're due)
        val times = (TIMES + NotifySettings.wakeTimes(ctx).map { it.minusMinutes(1) }).distinct()
        val next = (times.map { LocalDate.now().atTime(it) } + times.map { LocalDate.now().plusDays(1).atTime(it) })
            .filter { it.isAfter(now) }.minOrNull() ?: return
        val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pi = PendingIntent.getBroadcast(
            ctx, 7, Intent(ctx, DailyRefreshReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        // On time when Android allows exact alarms; otherwise as close as it lets us while dozing
        val exact = android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }.onFailure { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    /** Called from the app, widgets and receiver: hourly job + daily alarms are in place. */
    fun ensure(ctx: Context) {
        Refresh.schedulePeriodic(ctx)
        arm(ctx)
    }

    fun batteryUnrestricted(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true

    const val ACTION = "dev.subt.trainingwidget.DAILY_REFRESH"
}

class DailyRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // Redraw straight away (new day shows "Updating for today…" rather than yesterday), then fetch
        runCatching { WidgetRenderer.renderAll(ctx) }
        if (Prefs.credentials(ctx) != null) Refresh.now(ctx, replace = false)
        DailyRefresh.ensure(ctx)
    }
}
