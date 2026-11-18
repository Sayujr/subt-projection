package dev.subt.trainingwidget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/** Feeds the scrolling list in the workouts-only widget. */
class WorkoutListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(private val ctx: Context) : RemoteViewsFactory {
        private var items: List<Workout> = emptyList()

        override fun onCreate() {}
        override fun onDataSetChanged() {
            items = WidgetRenderer.listItems(ctx)
        }
        override fun onDestroy() {}
        override fun getCount(): Int = items.size

        override fun getViewAt(position: Int): RemoteViews {
            val w = items.getOrNull(position) ?: return RemoteViews(ctx.packageName, R.layout.widget_workout_row)
            return WidgetRenderer.row(ctx, w).apply {
                setOnClickFillInIntent(R.id.row_root, Intent())
            }
        }

        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount(): Int = 1
        override fun getItemId(position: Int): Long = position.toLong()
        override fun hasStableIds(): Boolean = false
    }
}
