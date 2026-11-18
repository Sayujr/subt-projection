package dev.subt.trainingwidget

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("training_widget", Context.MODE_PRIVATE)

    fun credentials(ctx: Context): Credentials? {
        val p = sp(ctx)
        val key = p.getString("api_key", null)?.takeIf { it.isNotBlank() } ?: return null
        val ath = p.getString("athlete_id", null)?.takeIf { it.isNotBlank() } ?: "0"
        return Credentials(ath, key)
    }

    fun saveCredentials(ctx: Context, c: Credentials) =
        sp(ctx).edit().putString("athlete_id", c.athleteId).putString("api_key", c.apiKey).apply()

    fun snapshot(ctx: Context): Snapshot? = sp(ctx).getString("snapshot", null)?.let { Snapshot.fromJson(it) }

    fun saveSnapshot(ctx: Context, s: Snapshot) =
        sp(ctx).edit().putString("snapshot", s.toJson()).remove("error").apply()

    fun error(ctx: Context): String? = sp(ctx).getString("error", null)

    fun setError(ctx: Context, msg: String) = sp(ctx).edit().putString("error", msg).apply()

    /** "Refreshing" auto-expires after a minute so a killed worker can't leave it stuck. */
    fun isRefreshing(ctx: Context): Boolean =
        System.currentTimeMillis() - sp(ctx).getLong("refreshing_since", 0L) < 60_000

    fun setRefreshing(ctx: Context, on: Boolean) =
        sp(ctx).edit().putLong("refreshing_since", if (on) System.currentTimeMillis() else 0L).apply()
}
