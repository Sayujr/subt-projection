package dev.subt.trainingwidget

import android.content.Context
import org.json.JSONObject

/**
 * Exports / imports your settings and entered numbers (not cached intervals.icu data, which
 * reloads by itself, and not your API key, which you paste again on a new phone).
 */
object Backup {
    private const val PREFS = "training_widget"
    /** Cached or per-device keys left out of a backup. */
    private val SKIP_EXACT = setOf(
        "api_key", "snapshot", "long_history", "load_index", "subt", "subt_reps", "race_history", "pbs", "tt_dates",
        "error", "refreshing_since", "history_fetched", "update_info", "update_checked", "update_dl_id", "update_dl_code", "seen_version",
    )
    private fun skip(k: String) = k in SKIP_EXACT || k.startsWith("notified_")

    fun export(ctx: Context): String {
        val o = JSONObject().put("app", "training-today").put("version", Updater.currentName(ctx)).put("created", java.time.LocalDateTime.now().toString())
        val v = JSONObject()
        for ((k, value) in ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all) {
            if (skip(k) || value == null) continue
            val type = when (value) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is String -> "s"; else -> continue }
            v.put(k, JSONObject().put("t", type).put("v", value))
        }
        return o.put("values", v).toString(2)
    }

    /** Returns how many settings were restored. */
    fun import(ctx: Context, json: String): Int {
        val o = JSONObject(json)
        require(o.optString("app") == "training-today") { "Not a Training Today backup file" }
        val v = o.getJSONObject("values")
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        var n = 0
        for (k in v.keys()) {
            if (skip(k)) continue
            val x = v.getJSONObject(k)
            when (x.getString("t")) {
                "b" -> e.putBoolean(k, x.getBoolean("v"))
                "i" -> e.putInt(k, x.getInt("v"))
                "l" -> e.putLong(k, x.getLong("v"))
                "f" -> e.putFloat(k, x.getDouble("v").toFloat())
                "s" -> e.putString(k, x.getString("v"))
                else -> continue
            }
            n++
        }
        e.apply()
        return n
    }
}
