package dev.subt.trainingwidget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt

/** Morning summary + a few alerts; each goes out at most once per day (or per new value). */
object Notifier {
    private const val DAILY = "daily"
    private const val ALERTS = "alerts"

    private fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(DAILY, "Morning summary", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(ALERTS, "Training alerts", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun allowed(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun post(ctx: Context, channel: String, id: Int, title: String, text: String, tab: String) {
        if (!allowed(ctx)) return
        channels(ctx)
        val pi = PendingIntent.getActivity(
            ctx, id, Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, tab)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = android.app.Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    /** "Update ready" once per new build (shown even with alerts off: it's how you get fixes). */
    fun updateReady(ctx: Context) {
        val l = Updater.available(ctx) ?: return
        once(ctx, "update", l.code.toString()) {
            post(ctx, ALERTS, 40, "Training Today ${l.name} is ready",
                (l.notes.take(3).joinToString("\n") { "• $it" }).ifEmpty { "Open the app to install it." }, "HOME")
        }
    }

    private fun once(ctx: Context, key: String, value: String, send: () -> Unit) {
        if (Prefs.lastNotified(ctx, key) == value) return
        send()
        Prefs.setLastNotified(ctx, key, value)
    }

    fun run(ctx: Context) {
        val s = Prefs.snapshot(ctx) ?: return
        val today = LocalDate.now().toString()
        if (s.today != today) return
        val v = Verdict.compute(s, Prefs.rhrOverride(ctx), Prefs.hrvOverride(ctx))
        // Nothing before 9:00: overnight data (resting HR, HRV, sleep) is often still syncing.
        // Alerts held back here aren't lost; they go out with the first refresh after 9.
        val now = LocalTime.now()
        if (now.hour < 9) return
        val rhrIn = Prefs.rhrOverride(ctx) != null || s.days.lastOrNull()?.takeIf { it.date == today }?.rhr != null

        // Morning summary: from 9:00, once today's resting HR is in (or 11:00 at the latest)
        if (Prefs.notifyMorning(ctx) && now.hour in 9..13 && v != null && (rhrIn || now.hour >= 11)) {
            once(ctx, "morning", today) {
                val key = s.workouts.filter { it.status != WorkoutStatus.DONE }.maxByOrNull { it.load }
                val session = key?.let { k -> k.name + (k.steps?.let { " · $it" } ?: "") } ?: "No session planned"
                val st = Streaks.compute(ctx)
                val streak = st?.let { " · streak ${it.dayStreak} d / ${it.weekStreak} wk" } ?: ""
                post(ctx, DAILY, 1, "${v.call.label}: ${v.headline}", "$session\n${v.reasons.take(3).joinToString(" · ") { it.second }}$streak", "HOME")
            }
        }
        if (!Prefs.notifyAlerts(ctx)) return

        // Your numbers vs intervals.icu (once each time the set of differences changes)
        val dev = Deviations.compute(ctx)
        if (dev.isNotEmpty()) once(ctx, "deviations", dev.joinToString("|").hashCode().toString()) {
            post(ctx, ALERTS, 6, "Your numbers differ from intervals.icu", dev.joinToString("\n") + "\nThe app uses yours. Update intervals.icu if it should match.", "HOME")
        }

        // Resting HR spike
        val rhr = s.days.mapNotNull { it.rhr }
        val last = Prefs.rhrOverride(ctx) ?: s.days.last().rhr
        if (last != null && rhr.size >= 10) {
            val base = rhr.dropLast(1).takeLast(42).average()
            if (last - base >= 5) once(ctx, "rhr", today) {
                post(ctx, ALERTS, 2, "Resting HR is up ${(last - base).roundToInt()} bpm", "Resting HR ${last.roundToInt()} vs your normal ${base.roundToInt()}. Consider an easy day and check sleep, hydration and illness.", "RECOVERY")
            }
        }
        // New CP high
        PowerModel.now(ctx)?.let { p ->
            val e = p.eftp ?: return@let
            val prevMax = s.runs.dropLast(1).mapNotNull { it.cp }.maxOrNull()
            if (prevMax != null && e >= prevMax + 2) once(ctx, "cp", e.roundToInt().toString()) {
                post(ctx, ALERTS, 3, "New eFTP high: ${e.roundToInt()} W", "Up from ${prevMax.roundToInt()} W (your set FTP is ${p.cp.roundToInt()} W)", "POWER")
            }
        }
        // Taper drifting off target (race within 7 days)
        s.races.firstOrNull { it.date >= today }?.let { r ->
            val rd = LocalDate.parse(r.date)
            if (java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), rd) in 1..7) {
                val series = (s.days + s.proj).distinctBy { it.date }.sortedBy { it.date }
                val tt = Taper.targets(series, rd, r.category, RaceDistance.of(r.distanceM, r.name), Prefs.raceHistory(ctx)?.second.orEmpty(), Prefs.taperPreset(ctx))
                val eve = series.firstOrNull { it.date == rd.minusDays(1).toString() }
                if (tt != null && eve != null && eve.atl > tt.fatHi) once(ctx, "taper", today) {
                    post(ctx, ALERTS, 4, "Taper off target for ${r.name.take(30)}", "Race-morning Fatigue projected ${eve.atl.roundToInt()}, above your ${tt.fatLo.roundToInt()}–${tt.fatHi.roundToInt()} range. Ease the last few days.", "RACE")
                }
            }
        }
        // Weekly streak result (sent once each Monday)
        if (LocalDate.now().dayOfWeek == java.time.DayOfWeek.MONDAY) {
            Streaks.compute(ctx)?.let { st ->
                val lastWeek = st.weeks.lastOrNull { it.complete } ?: return@let
                once(ctx, "week", lastWeek.monday.toString()) {
                    val txt = when (lastWeek.passed) {
                        true -> "Week passed: streak now ${st.weekStreak} week${if (st.weekStreak == 1) "" else "s"}."
                        false -> "Last week missed your load rules. The streak restarts this week."
                        null -> "Last week wasn't judged (race or taper)."
                    }
                    post(ctx, ALERTS, 5, "Load-balance streak", txt, "STREAKS")
                }
            }
        }
    }
}
