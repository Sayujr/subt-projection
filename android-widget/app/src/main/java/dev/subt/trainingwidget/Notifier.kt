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

/** 9:00 recovery check, 16:00 workout reminder, and a few alerts; each goes out at most once per day (or per new value). */
object Notifier {
    private const val DAILY = "daily"
    private const val ALERTS = "alerts"

    private fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(DAILY, "Workout and recovery", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(ALERTS, "Training alerts", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun allowed(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun post(ctx: Context, channel: String, id: Int, title: String, text: String, tab: String) {
        if (!allowed(ctx)) return
        channels(ctx)
        val target = when {
            tab == "SETTINGS" -> Intent(ctx, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_OPEN, "update")
            tab.startsWith("SWAP:") -> tab.removePrefix("SWAP:").split(":").let { parts ->
                SwapActivity.intent(ctx, runCatching { LocalDate.parse(parts[0]) }.getOrNull(), parts.getOrNull(1)?.toLongOrNull(),
                    runCatching { Swaps.Trigger.valueOf(parts.getOrNull(2).orEmpty()) }.getOrDefault(Swaps.Trigger.MANUAL), parts.getOrNull(3)?.replace("_", " "))
            }
            tab.startsWith("RW:") -> Intent(ctx, RunWindowActivity::class.java).putExtra(RunWindowActivity.EXTRA_DATE, tab.removePrefix("RW:"))
            else -> Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, tab)
        }
        val pi = PendingIntent.getActivity(
            ctx, id, target
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
        if (!NotifySettings.isOn(ctx, NotifySettings.cat("update"))) return
        val l = Updater.available(ctx) ?: return
        once(ctx, "update", l.code.toString()) {
            post(ctx, ALERTS, 40, "Training Today ${l.name} is ready",
                (l.notes.take(3).joinToString("\n") { "• $it" }).ifEmpty { "Tap to install it from Settings." }, "SETTINGS")
        }
    }

    private fun once(ctx: Context, key: String, value: String, send: () -> Unit) {
        if (Prefs.lastNotified(ctx, key) == value) return
        send()
        Prefs.setLastNotified(ctx, key, value)
    }

    /** Tomorrow's windows, in the evening (default 20:00): "Tomorrow you can run A–B"; once per day. Needs a location, not intervals.icu. */
    fun runWindows(ctx: Context) {
        val N = NotifySettings
        val c = N.cat("rw_tomorrow")
        if (!N.isOn(ctx, c) || !N.inWindow(LocalTime.now(), N.time(ctx, c), 3)) return
        val tomorrow = LocalDate.now().plusDays(1)
        val p = RunWindows.plan(ctx, tomorrow) ?: return
        if (p.noData) return
        val zone = java.time.ZoneId.systemDefault()
        val title: String
        val body: String
        if (p.split != null && p.windows.isEmpty()) {
            title = "Tomorrow's run: " + Weather.splitLabel(p.split, zone); body = p.summary.short
        } else {
            val n = RwText.dayNote(p, RwStore.load(ctx), zone, today = false)
            title = n.title; body = n.body
        }
        once(ctx, "rw_tomorrow", LocalDate.now().toString()) { post(ctx, DAILY, 20, title, body, "RW:$tomorrow") }
    }

    /** One "You missed today's window (A–B)" per closed window while the run isn't done. */
    fun windowMissed(ctx: Context, note: RwText.Note, date: LocalDate, key: String) {
        once(ctx, "rw_missed", key) { post(ctx, ALERTS, 24, note.title, note.body, "RW:$date") }
    }

    /** "1h30 left in today's window (ends 14:30)": once per window (a window that only moved a little counts as the same one). */
    fun windowLeft(ctx: Context, note: RwText.Note, date: LocalDate, window: Pair<Long, Long>) {
        if (RwText.headsUpSeen(Prefs.lastNotified(ctx, "rw_left"), date, window)) return
        Prefs.setLastNotified(ctx, "rw_left", RwText.headsUpKey(date, window))
        post(ctx, ALERTS, 25, note.title, note.body, "RW:$date")
    }

    /**
     * "Missed X? Options to fit it in" (once per missed session) and "No good time to run tomorrow" (once per session: the
     * evening before, or the morning of). Opens the swap page; never changes anything by itself.
     */
    fun swapPrompts(ctx: Context) {
        val N = NotifySettings
        val now = LocalTime.now()
        if (Weather.quiet(now.hour) || (now.hour * 60 + now.minute) < N.alertsFrom(ctx) - 4 * 60) return
        val today = LocalDate.now()
        for (cand in SwapFlow.candidates(ctx)) {
            val id = cand.workout.eventId ?: continue
            val name = cand.workout.name
            when (cand.trigger) {
                Swaps.Trigger.MISSED_YESTERDAY, Swaps.Trigger.MISSED_PASSED, Swaps.Trigger.MISSED_LATE -> {
                    if (!N.isOn(ctx, N.cat("swap_missed"))) continue
                    once(ctx, "swap_$id", "missed") {
                        post(ctx, ALERTS, 22, "Missed $name? Options to fit it in", "See 1–3 ways to fit it back into your week. Nothing changes until you pick one.", "SWAP:${cand.date}:$id:${cand.trigger.name}")
                    }
                }
                Swaps.Trigger.AT_RISK -> {
                    if (!N.isOn(ctx, N.cat("swap_risk"))) continue
                    val tomorrow = cand.date == today.plusDays(1) && N.inWindow(now, N.time(ctx, N.cat("rw_tomorrow")), 3)
                    val sameDay = cand.date == today && now.hour in 5..11
                    if (!tomorrow && !sameDay) continue
                    val day = if (cand.date == today) "today" else "tomorrow"
                    val why = (cand.why ?: "poor conditions").replace(" ", "_")
                    once(ctx, "swap_$id", "risk") {
                        post(ctx, ALERTS, 23, "No good time to run $day", "$name. Fit it in another day?", "SWAP:${cand.date}:$id:${cand.trigger.name}:$why")
                    }
                }
                else -> Unit
            }
        }
    }

    /** "Run window shifted: …" (always on by default; opens that day's plan). */
    fun windowShifted(ctx: Context, text: String, date: LocalDate, title: String = "Run window shifted") {
        post(ctx, ALERTS, 21, title, text, "RW:$date")
    }

    fun run(ctx: Context) {
        val s = Prefs.snapshot(ctx) ?: return
        val today = LocalDate.now().toString()
        if (s.today != today) return
        val N = NotifySettings
        val now = LocalTime.now()
        fun on(key: String) = N.isOn(ctx, N.cat(key))
        fun at(key: String) = N.time(ctx, N.cat(key))
        // Recovery-type notifications pause from 2 days before a race through race day
        val pausedFor = if (N.pauseNearRace(ctx)) N.pausingRace(LocalDate.now(), s.races) else null
        val v = Verdict.compute(s, Prefs.rhrOverride(ctx), Prefs.hrvOverride(ctx))
        val rhrIn = Prefs.rhrOverride(ctx) != null || s.days.lastOrNull()?.takeIf { it.date == today }?.rhr != null

        // Today's workout (default 04:00)
        if (on("workout") && N.inWindow(now, at("workout"), 8)) {
            val todo = s.workouts.filter { it.status != WorkoutStatus.DONE }
            if (todo.isNotEmpty()) once(ctx, "workout_am", today) {
                val k = todo.maxByOrNull { it.load }!!
                val more = if (todo.size > 1) " (+${todo.size - 1} more)" else ""
                val window = if (on("rw_today")) runCatching { RunWindows.todayLine(ctx) }.getOrNull() else null
                post(ctx, DAILY, 8, "Today: ${k.name}$more", listOfNotNull(k.steps, WidgetRenderer.meta(k).takeIf { it.isNotBlank() }, window).joinToString("\n").ifEmpty { "Planned for today" }, "HOME")
            }
        }
        // Workout not done yet (off by default)
        if (on("reminder") && N.inWindow(now, at("reminder"), 5)) {
            val waiting = s.workouts.filter { it.status != WorkoutStatus.DONE }
            if (waiting.isNotEmpty() && s.workouts.none { it.status == WorkoutStatus.DONE && it.type == waiting.first().type }) once(ctx, "workout", today) {
                val k = waiting.maxByOrNull { it.load }!!
                post(ctx, DAILY, 7, "Workout waiting: ${k.name}", listOfNotNull(k.steps, WidgetRenderer.meta(k).takeIf { it.isNotBlank() }).joinToString("\n").ifEmpty { "Planned for today" }, "HOME")
            }
        }
        // Recovery check (default 09:00): waits up to 2 h for today's resting HR
        if (on("recovery") && pausedFor == null && v != null && N.inWindow(now, at("recovery"), 5) &&
            (rhrIn || !N.inWindow(now, at("recovery"), 2))) {
            once(ctx, "morning", today) {
                post(ctx, DAILY, 1, "${v.call.label}: ${v.headline}", v.reasons.joinToString("\n") { "• ${it.second}" }, "HOME")
            }
        }

        // Alerts: not before the "alerts from" time
        if ((now.hour * 60 + now.minute) < N.alertsFrom(ctx)) return

        // Your numbers vs intervals.icu (once each time the set of differences changes)
        val dev = Deviations.compute(ctx)
        if (on("deviations") && dev.isNotEmpty()) once(ctx, "deviations", dev.joinToString("|").hashCode().toString()) {
            post(ctx, ALERTS, 6, "Your numbers differ from intervals.icu", dev.joinToString("\n") + "\nThe app uses yours. Update intervals.icu if it should match.", "HOME")
        }

        // Resting HR spike
        val rhr = s.days.mapNotNull { it.rhr }
        val last = Prefs.rhrOverride(ctx) ?: s.days.last().rhr
        if (on("rhr") && pausedFor == null && last != null && rhr.size >= 10) {
            val base = rhr.dropLast(1).takeLast(42).average()
            if (last - base >= 5) once(ctx, "rhr", today) {
                post(ctx, ALERTS, 2, "Resting HR is up ${(last - base).roundToInt()} bpm", "Resting HR ${last.roundToInt()} vs your normal ${base.roundToInt()}. Consider an easy day and check sleep, hydration and illness.", "RECOVERY")
            }
        }
        // New CP high
        if (on("eftp")) PowerModel.now(ctx)?.let { p ->
            val e = p.eftp ?: return@let
            val prevMax = s.runs.dropLast(1).mapNotNull { it.cp }.maxOrNull()
            if (prevMax != null && e >= prevMax + 2) once(ctx, "cp", e.roundToInt().toString()) {
                post(ctx, ALERTS, 3, "New eFTP high: ${e.roundToInt()} W", "Up from ${prevMax.roundToInt()} W (your set FTP is ${p.cp.roundToInt()} W)", "POWER")
            }
        }
        // Taper drifting off target (race within 7 days)
        if (on("taper")) s.races.firstOrNull { it.date >= today }?.let { r ->
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
        if (on("streak") && LocalDate.now().dayOfWeek == java.time.DayOfWeek.MONDAY) {
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
