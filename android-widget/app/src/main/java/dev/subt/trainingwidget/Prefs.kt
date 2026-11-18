package dev.subt.trainingwidget

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("training_widget", Context.MODE_PRIVATE)

    // ---- account

    fun credentials(ctx: Context): Credentials? {
        val p = sp(ctx)
        val key = p.getString("api_key", null)?.takeIf { it.isNotBlank() } ?: return null
        val ath = p.getString("athlete_id", null)?.takeIf { it.isNotBlank() } ?: "0"
        return Credentials(ath, key)
    }

    fun saveCredentials(ctx: Context, c: Credentials) =
        sp(ctx).edit().putString("athlete_id", c.athleteId).putString("api_key", c.apiKey).apply()

    // ---- cached data

    fun snapshot(ctx: Context): Snapshot? = sp(ctx).getString("snapshot", null)?.let { Snapshot.fromJson(it) }

    fun saveSnapshot(ctx: Context, s: Snapshot, historyDays: Int) =
        sp(ctx).edit().putString("snapshot", s.toJson()).putInt("history_fetched", historyDays).remove("error").apply()

    /** How many days of history the cached snapshot was fetched with. */
    fun historyFetched(ctx: Context): Int = sp(ctx).getInt("history_fetched", 0)

    /** Days of history needed to draw every widget's chosen period. */
    fun historyNeeded(ctx: Context): Int =
        (WidgetRenderer.allIds(ctx).map { period(ctx, it) } + Periods.DEFAULT).max()

    fun subt(ctx: Context): SubtData? = sp(ctx).getString("subt", null)?.let { SubtData.fromJson(it) }

    fun saveSubt(ctx: Context, d: SubtData) = sp(ctx).edit().putString("subt", d.toJson()).apply()

    fun error(ctx: Context): String? = sp(ctx).getString("error", null)

    fun setError(ctx: Context, msg: String) = sp(ctx).edit().putString("error", msg).apply()

    /** "Refreshing" auto-expires after a minute so a killed worker can't leave it stuck. */
    fun isRefreshing(ctx: Context): Boolean =
        System.currentTimeMillis() - sp(ctx).getLong("refreshing_since", 0L) < 60_000

    fun setRefreshing(ctx: Context, on: Boolean) =
        sp(ctx).edit().putLong("refreshing_since", if (on) System.currentTimeMillis() else 0L).apply()

    // ---- per-widget settings

    fun style(ctx: Context, id: Int): GraphStyle =
        runCatching { GraphStyle.valueOf(sp(ctx).getString("style_$id", null) ?: "AUTO") }.getOrDefault(GraphStyle.AUTO)

    fun period(ctx: Context, id: Int): Int = sp(ctx).getInt("period_$id", Periods.DEFAULT)

    fun saveWidget(ctx: Context, id: Int, style: GraphStyle, period: Int) =
        sp(ctx).edit().putString("style_$id", style.name).putInt("period_$id", period).apply()

    fun deleteWidget(ctx: Context, id: Int) = sp(ctx).edit().remove("style_$id").remove("period_$id").apply()
}
