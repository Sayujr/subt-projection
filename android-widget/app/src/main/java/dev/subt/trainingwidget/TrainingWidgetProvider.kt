package dev.subt.trainingwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.time.LocalDate

/** Shared behaviour for all three widgets: they read the same cached snapshot and share one refresh job. */
abstract class BaseWidgetProvider : AppWidgetProvider() {

    abstract fun render(ctx: Context, mgr: AppWidgetManager, id: Int)

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(ctx, mgr, it) }
        Refresh.schedulePeriodic(ctx)
        val snap = Prefs.snapshot(ctx)
        val stale = snap == null || System.currentTimeMillis() - snap.fetchedAt > 15 * 60_000 ||
            snap.today != LocalDate.now().toString()
        if ((stale && Prefs.credentials(ctx) != null) || Prefs.subt(ctx) == null) Refresh.now(ctx, replace = false)
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle) {
        render(ctx, mgr, id) // re-draw charts / pick layout for the new size
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        ids.forEach { Prefs.deleteWidget(ctx, it) }
    }

    override fun onEnabled(ctx: Context) = Refresh.schedulePeriodic(ctx)

    override fun onDisabled(ctx: Context) {
        if (WidgetRenderer.allIds(ctx).isEmpty()) Refresh.cancel(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            Prefs.setRefreshing(ctx, true)
            WidgetRenderer.renderAll(ctx)
            Refresh.now(ctx, replace = true)
            return
        }
        super.onReceive(ctx, intent)
    }

    companion object {
        const val ACTION_REFRESH = "dev.subt.trainingwidget.REFRESH"
    }
}

/** Workouts + Fitness / Fatigue / Form tiles. */
class TrainingWidgetProvider : BaseWidgetProvider() {
    override fun render(ctx: Context, mgr: AppWidgetManager, id: Int) = WidgetRenderer.renderFull(ctx, mgr, id)
}

/** One combined chart of all three metrics with today's numbers. */
class TrendWidgetProvider : BaseWidgetProvider() {
    override fun render(ctx: Context, mgr: AppWidgetManager, id: Int) = WidgetRenderer.renderTrend(ctx, mgr, id)
}

/** Today's workouts as a one-row scrolling strip, with the graphs underneath. */
class CompactWidgetProvider : BaseWidgetProvider() {
    override fun render(ctx: Context, mgr: AppWidgetManager, id: Int) = WidgetRenderer.renderCompact(ctx, mgr, id)

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        super.onUpdate(ctx, mgr, ids)
        mgr.notifyAppWidgetViewDataChanged(ids, R.id.workout_list)
    }
}

/** Compact, scrollable list of today's workouts. */
class WorkoutsWidgetProvider : BaseWidgetProvider() {
    override fun render(ctx: Context, mgr: AppWidgetManager, id: Int) = WidgetRenderer.renderWorkouts(ctx, mgr, id)

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        super.onUpdate(ctx, mgr, ids)
        mgr.notifyAppWidgetViewDataChanged(ids, R.id.workout_list)
    }
}

/** This week's load per day, with what's still planned. */
class WeekWidgetProvider : BaseWidgetProvider() {
    override fun render(ctx: Context, mgr: AppWidgetManager, id: Int) = ExtraWidgets.renderWeek(ctx, mgr, id)
}

/** Countdown to the next race, with projected race-day Fitness / Form. */
class RaceWidgetProvider : BaseWidgetProvider() {
    override fun render(ctx: Context, mgr: AppWidgetManager, id: Int) = ExtraWidgets.renderRace(ctx, mgr, id)
}

/** HRV, resting HR and sleep trends. */
class RecoveryWidgetProvider : BaseWidgetProvider() {
    override fun render(ctx: Context, mgr: AppWidgetManager, id: Int) = ExtraWidgets.renderRecovery(ctx, mgr, id)
}

/** Monthly SubT pace from the projection page's data. */
class SubtWidgetProvider : BaseWidgetProvider() {
    override fun render(ctx: Context, mgr: AppWidgetManager, id: Int) = ExtraWidgets.renderSubt(ctx, mgr, id)
}
