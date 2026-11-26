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
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        const val FORCE = "force"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val creds = Prefs.credentials(ctx)
        if (creds != null) {
            try {
                val history = Prefs.historyNeeded(ctx)
                Prefs.saveSnapshot(ctx, IcuApi(creds).fetchSnapshot(historyDays = history.toLong()), history)
            } catch (e: AuthException) {
                Prefs.setError(ctx, "check API key")
            } catch (e: Exception) {
                Prefs.setError(ctx, "offline")
            }
        }
        if (creds != null && Prefs.coachSync(ctx)) runCatching { CoachSync.run(ctx, creds) }
        // PBs change rarely: refresh daily (or on a manual refresh)
        if (creds != null) {
            val pb = Prefs.pbs(ctx)
            if (pb == null || System.currentTimeMillis() - pb.fetchedAt > 24 * 3600_000L || inputData.getBoolean(FORCE, false)) {
                runCatching { IcuApi(creds).fetchPbs() }.onSuccess { Prefs.savePbs(ctx, it) }
            }
        }
        // SubT: the original rule (projection page data) before the switch month, your SubT workouts' reps after
        val force = inputData.getBoolean(FORCE, false)
        val page = Prefs.subtPage(ctx)
        if (page == null || System.currentTimeMillis() - page.fetchedAt > 6 * 3600_000L || force) {
            runCatching { SubtApi.fetch() }.onSuccess { Prefs.saveSubtPage(ctx, it) }
        }
        val reps = Prefs.subtReps(ctx)
        if (creds != null && (reps == null || System.currentTimeMillis() - reps.first > 6 * 3600_000L || force || Prefs.subtScanned(ctx).isEmpty())) {
            runCatching { IcuApi(creds).fetchSubtReps(cached = reps?.second.orEmpty(), scanned = Prefs.subtScanned(ctx)) }
                .onSuccess { Prefs.saveSubtReps(ctx, it.reps); Prefs.saveSubtScanned(ctx, it.scanned) }
        }
        SubtWorkouts.merge(Prefs.subtPage(ctx), Prefs.subtReps(ctx)?.second.orEmpty())?.let { Prefs.saveSubt(ctx, it) }
        runCatching { Updater.check(ctx, force = inputData.getBoolean(FORCE, false)) }
        Prefs.setRefreshing(ctx, false)
        WidgetRenderer.renderAll(ctx)
        // Run windows: refresh the forecast when it's over an hour old (not 22:00-05:00), then the evening notice
        runCatching { RunWindows.refreshIfDue(ctx, 60) }
        runCatching { Notifier.run(ctx) }
        runCatching { Notifier.runWindows(ctx) }
        runCatching { Notifier.swapPrompts(ctx) }
        runCatching { Notifier.updateReady(ctx) }
        Result.success()
    }
}

object Refresh {
    private const val PERIODIC = "training-widget-periodic"
    private const val NOW = "training-widget-now"

    fun schedulePeriodic(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<RefreshWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun now(ctx: Context, replace: Boolean) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            NOW,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RefreshWorker>()
                .setInputData(workDataOf(RefreshWorker.FORCE to replace))
                .build(),
        )
    }

    fun cancel(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC)
        WorkManager.getInstance(ctx).cancelUniqueWork(NOW)
    }
}
