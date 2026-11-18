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
        // 120 days minimum so the Race screen can show the 3-month build-up
        (WidgetRenderer.allIds(ctx).map { period(ctx, it) } + period(ctx, WidgetRenderer.APP_ID) + 120).max()

    fun subt(ctx: Context): SubtData? = sp(ctx).getString("subt", null)?.let { SubtData.fromJson(it) }

    fun saveSubt(ctx: Context, d: SubtData) = sp(ctx).edit().putString("subt", d.toJson()).apply()

    fun error(ctx: Context): String? = sp(ctx).getString("error", null)

    fun setError(ctx: Context, msg: String) = sp(ctx).edit().putString("error", msg).apply()

    /** "Refreshing" auto-expires after a minute so a killed worker can't leave it stuck. */
    fun isRefreshing(ctx: Context): Boolean =
        System.currentTimeMillis() - sp(ctx).getLong("refreshing_since", 0L) < 60_000

    fun setRefreshing(ctx: Context, on: Boolean) =
        sp(ctx).edit().putLong("refreshing_since", if (on) System.currentTimeMillis() else 0L).apply()

    // ---- race taper target: allowed Fitness drop from the 3-month peak, percent (low..high)

    fun taperDrop(ctx: Context): Pair<Int, Int> {
        val v = sp(ctx).getString("taper_drop", "5-10") ?: "5-10"
        val p = v.split("-").mapNotNull { it.toIntOrNull() }
        return if (p.size == 2) p[0] to p[1] else 5 to 10
    }

    fun setTaperDrop(ctx: Context, lo: Int, hi: Int) = sp(ctx).edit().putString("taper_drop", "$lo-$hi").apply()

    // ---- race taper: Fatigue target (race morning) and Fitness guardrail (max % below 3-month peak)

    fun fatigueTarget(ctx: Context): Double? = sp(ctx).getFloat("fatigue_target", 0f).takeIf { it > 0 }?.toDouble()
    fun setFatigueTarget(ctx: Context, v: Double?) = sp(ctx).edit().putFloat("fatigue_target", (v ?: 0.0).toFloat()).apply()
    fun fitnessGuardrail(ctx: Context): Int = sp(ctx).getInt("fitness_guardrail", 15)
    fun setFitnessGuardrail(ctx: Context, pct: Int) = sp(ctx).edit().putInt("fitness_guardrail", pct).apply()

    // ---- marathon / weight planning

    /** User-entered weight overrides the intervals.icu value. */
    fun weightOverride(ctx: Context): Double? = sp(ctx).getFloat("weight_kg", 0f).takeIf { it > 0 }?.toDouble()
    fun setWeightOverride(ctx: Context, kg: Double?) = sp(ctx).edit().putFloat("weight_kg", (kg ?: 0.0).toFloat()).apply()
    fun goalWeight(ctx: Context): Double? = sp(ctx).getFloat("goal_kg", 0f).takeIf { it > 0 }?.toDouble()
    fun setGoalWeight(ctx: Context, kg: Double) = sp(ctx).edit().putFloat("goal_kg", kg.toFloat()).apply()
    fun weightPlan(ctx: Context): Int = sp(ctx).getInt("weight_plan", 3) // Stable
    fun setWeightPlan(ctx: Context, i: Int) = sp(ctx).edit().putInt("weight_plan", i).apply()
    fun marathonDate(ctx: Context): String? = sp(ctx).getString("marathon_date", null)
    fun setMarathonDate(ctx: Context, d: String) = sp(ctx).edit().putString("marathon_date", d).apply()
    fun marathonTarget(ctx: Context): Int = sp(ctx).getInt("marathon_target", SubtModel.SUB3.toInt())
    fun setMarathonTarget(ctx: Context, sec: Int) = sp(ctx).edit().putInt("marathon_target", sec).apply()

    fun subtReps(ctx: Context): Pair<Long, List<SubtRep>>? = sp(ctx).getString("subt_reps", null)?.let { SubtRep.listFromJson(it) }
    fun saveSubtReps(ctx: Context, reps: List<SubtRep>) =
        sp(ctx).edit().putString("subt_reps", SubtRep.listToJson(reps, System.currentTimeMillis())).apply()

    // ---- per-widget settings

    fun style(ctx: Context, id: Int): GraphStyle =
        runCatching { GraphStyle.valueOf(sp(ctx).getString("style_$id", null) ?: "AUTO") }.getOrDefault(GraphStyle.AUTO)

    fun period(ctx: Context, id: Int): Int = sp(ctx).getInt("period_$id", Periods.DEFAULT)

    fun saveWidget(ctx: Context, id: Int, style: GraphStyle, period: Int) =
        sp(ctx).edit().putString("style_$id", style.name).putInt("period_$id", period).apply()

    fun deleteWidget(ctx: Context, id: Int) = sp(ctx).edit().remove("style_$id").remove("period_$id").apply()
}
