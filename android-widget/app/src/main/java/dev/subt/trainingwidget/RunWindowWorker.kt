package dev.subt.trainingwidget

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Forecast check: every 3 hours, hourly from 05:00 on a day with a planned run until its windows have closed, and one more
 * check 1h30 before the next window closes (for the heads-up). Nothing runs 22:00–05:00.
 */
class RunWindowWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        if (RunWindows.hasLocation(ctx)) {
            if (!Weather.quiet(LocalTime.now().hour)) {
                runCatching { RunWindows.refresh(ctx, notify = true) }
                runCatching { Notifier.runWindows(ctx) }
                runCatching { RunWindows.headsUp(ctx) }        // from the cached forecast, so it still goes out when the refresh failed
            }
            runCatching { RunWindowSchedule.chain(ctx) }
            runCatching { RunWindowSchedule.chainHeadsUp(ctx) }
            runCatching { RunWindowSchedule.chainWatch(ctx, first = false) }
        }
        Result.success()
    }
}

object RunWindowSchedule {
    private const val PERIODIC = "run-window-3h"
    private const val HOURLY = "run-window-hourly"
    private const val WATCH = "run-window-watch"
    private const val HEADSUP = "run-window-headsup"
    private const val K_HEADS_AT = "rw_heads_at"
    private const val K_NEXT = "rw_chain_next"
    private const val K_WATCH_NEXT = "rw_watch_next"

    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** The 3-hourly job is on while a location is set. */
    fun ensure(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        if (!RunWindows.hasLocation(ctx)) { wm.cancelUniqueWork(PERIODIC); return }
        val req = PeriodicWorkRequestBuilder<RunWindowWorker>(3, TimeUnit.HOURS).setConstraints(net).build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** On a run day, queue the next hourly check (once: a check already waiting is left alone). */
    fun chain(ctx: Context) {
        if (Watch.active(ctx)) return        // watching closely already checks every 30 minutes
        if (!RunWindows.needsHourly(ctx)) return
        val now = System.currentTimeMillis()
        if (RwStore.getLong(ctx, K_NEXT) > now + 60_000L) return
        RwStore.putLong(ctx, K_NEXT, now + 60 * 60_000L)
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            HOURLY, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<RunWindowWorker>().setConstraints(net).setInitialDelay(60, TimeUnit.MINUTES).build(),
        )
    }

    /**
     * One check at 1h30 before the next window of a run day closes, so the heads-up lands on time instead of at the next hourly
     * check. Needs no network (the heads-up is judged from the cached forecast); a check already waiting for that time is left alone.
     */
    fun chainHeadsUp(ctx: Context) {
        val at = RunWindows.nextHeadsUpAt(ctx) ?: return
        val now = System.currentTimeMillis()
        val atMs = at * 1000L
        val queued = RwStore.getLong(ctx, K_HEADS_AT)
        if (queued > now && Math.abs(queued - atMs) < 2 * 60_000L) return
        RwStore.putLong(ctx, K_HEADS_AT, atMs)
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            HEADSUP, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<RunWindowWorker>().setInitialDelay((atMs - now).coerceAtLeast(30_000L), TimeUnit.MILLISECONDS).build(),
        )
    }

    /**
     * "Watch today closely": while it's on, before 22:00 and before the window starts, queue the next check in 30 minutes
     * (one at a time). [first] = just switched on: a check already waiting is replaced so the first one comes in 30 minutes.
     */
    fun chainWatch(ctx: Context, first: Boolean) {
        if (!Watch.active(ctx)) return
        val now = System.currentTimeMillis()
        if (!first && RwStore.getLong(ctx, K_WATCH_NEXT) > now + 60_000L) return
        RwStore.putLong(ctx, K_WATCH_NEXT, now + 30 * 60_000L)
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            WATCH, if (first) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<RunWindowWorker>().setConstraints(net).setInitialDelay(30, TimeUnit.MINUTES).build(),
        )
    }

    fun cancel(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC)
        WorkManager.getInstance(ctx).cancelUniqueWork(HOURLY)
        WorkManager.getInstance(ctx).cancelUniqueWork(WATCH)
        WorkManager.getInstance(ctx).cancelUniqueWork(HEADSUP)
    }
}
