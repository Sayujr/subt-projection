package dev.subt.trainingwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

class TrainingWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { WidgetRenderer.render(ctx, mgr, it) }
        Refresh.schedulePeriodic(ctx)
        val snap = Prefs.snapshot(ctx)
        val stale = snap == null || System.currentTimeMillis() - snap.fetchedAt > 15 * 60_000 ||
            snap.today != java.time.LocalDate.now().toString()
        if (stale && Prefs.credentials(ctx) != null) Refresh.now(ctx, replace = false)
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle) {
        WidgetRenderer.render(ctx, mgr, id) // re-draw charts for the new size
    }

    override fun onEnabled(ctx: Context) = Refresh.schedulePeriodic(ctx)

    override fun onDisabled(ctx: Context) = Refresh.cancel(ctx)

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
