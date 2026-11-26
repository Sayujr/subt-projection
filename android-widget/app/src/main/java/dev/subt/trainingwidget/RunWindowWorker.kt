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

/** Forecast check: every 3 hours, and hourly from 05:00 on a day with a planned run until its window starts. Nothing runs 22:00–05:00. */
class RunWindowWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        if (RunWindows.hasLocation(ctx)) {
            if (!Weather.quiet(LocalTime.now().hour)) {
                runCatching { RunWindows.refresh(ctx, notify = true) }
                runCatching { Notifier.runWindows(ctx) }
            }
            runCatching { RunWindowSchedule.chain(ctx) }
        }
        Result.success()
    }
}

object RunWindowSchedule {
    private const val PERIODIC = "run-window-3h"
    private const val HOURLY = "run-window-hourly"
    private const val K_NEXT = "rw_chain_next"

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
        if (!RunWindows.needsHourly(ctx)) return
        val now = System.currentTimeMillis()
        if (RwStore.getLong(ctx, K_NEXT) > now + 60_000L) return
        RwStore.putLong(ctx, K_NEXT, now + 60 * 60_000L)
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            HOURLY, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<RunWindowWorker>().setConstraints(net).setInitialDelay(60, TimeUnit.MINUTES).build(),
        )
    }

    fun cancel(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC)
        WorkManager.getInstance(ctx).cancelUniqueWork(HOURLY)
    }
}
