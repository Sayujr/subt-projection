package dev.subt.trainingwidget

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("training_widget", Context.MODE_PRIVATE)

    // ---- app updates
    fun updateInfo(ctx: Context): String? = sp(ctx).getString("update_info", null)
    fun saveUpdateInfo(ctx: Context, json: String) = sp(ctx).edit().putString("update_info", json).apply()
    fun updateCheckedAt(ctx: Context): Long = sp(ctx).getLong("update_checked", 0L)
    fun setUpdateCheckedAt(ctx: Context, t: Long) = sp(ctx).edit().putLong("update_checked", t).apply()
    fun updateDownload(ctx: Context): Pair<Long, Int>? = sp(ctx).getLong("update_dl_id", -1L).takeIf { it >= 0 }?.let { it to sp(ctx).getInt("update_dl_code", 0) }
    fun setUpdateDownload(ctx: Context, id: Long, code: Int) = sp(ctx).edit().putLong("update_dl_id", id).putInt("update_dl_code", code).apply()
    fun seenVersion(ctx: Context): Long = sp(ctx).getLong("seen_version", 0L)
    fun setSeenVersion(ctx: Context, v: Long) = sp(ctx).edit().putLong("seen_version", v).apply()

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
        (WidgetRenderer.allIds(ctx).map { period(ctx, it) } + period(ctx, WidgetRenderer.APP_ID) + 365).max()

    fun subt(ctx: Context): SubtData? = sp(ctx).getString("subt", null)?.let { SubtData.fromJson(it) }

    fun saveSubt(ctx: Context, d: SubtData) = sp(ctx).edit().putString("subt", d.toJson()).apply()
    /** The projection page's data.json (the original SubT rule), kept for months before the switch. */
    fun subtPage(ctx: Context): SubtData? = sp(ctx).getString("subt_page", null)?.let { SubtData.fromJson(it) }
    fun saveSubtPage(ctx: Context, d: SubtData) = sp(ctx).edit().putString("subt_page", d.toJson()).apply()

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

    // ---- race history + taper preset (0 strict, 1 standard, 2 relaxed)

    fun raceHistory(ctx: Context): Pair<Long, List<RaceResult>>? = sp(ctx).getString("race_history", null)?.let { RaceResult.listFromJson(it) }
    fun saveRaceHistory(ctx: Context, l: List<RaceResult>) = sp(ctx).edit().putString("race_history", RaceResult.listToJson(l, System.currentTimeMillis())).apply()
    fun taperPreset(ctx: Context): Int = sp(ctx).getInt("taper_preset", 1)
    fun setTaperPreset(ctx: Context, i: Int) = sp(ctx).edit().putInt("taper_preset", i).apply()

    // ---- load-balance streak rules
    /** Fitness must stay within this % of the reference peak (outside tapers). */
    fun fitnessBand(ctx: Context): Int = sp(ctx).getInt("streak_fit_band", 10)
    fun setFitnessBand(ctx: Context, v: Int) = sp(ctx).edit().putInt("streak_fit_band", v).apply()
    /** Fatigue must be at least this fraction of Fitness on a build day (×100). */
    fun fatigueMin(ctx: Context): Int = sp(ctx).getInt("streak_fat_min", 95)
    fun setFatigueMin(ctx: Context, v: Int) = sp(ctx).edit().putInt("streak_fat_min", v).apply()
    /** Days per week that must meet every rule. */
    fun greyDays(ctx: Context): Int = sp(ctx).getInt("streak_grey_days", 4)
    fun setGreyDays(ctx: Context, v: Int) = sp(ctx).edit().putInt("streak_grey_days", v).apply()

    // ---- power model
    /** Marathon power as % of CP (Stryd-style default 88). */
    fun fmPct(ctx: Context): Int = sp(ctx).getInt("fm_pct_cp", 88)
    fun setFmPct(ctx: Context, v: Int) = sp(ctx).edit().putInt("fm_pct_cp", v).apply()

    // ---- notifications
    fun notifyMorning(ctx: Context): Boolean = sp(ctx).getBoolean("notify_morning", true)
    fun setNotifyMorning(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("notify_morning", on).apply()
    fun notifyWorkout(ctx: Context): Boolean = sp(ctx).getBoolean("notify_workout", true)
    fun setNotifyWorkout(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("notify_workout", on).apply()
    fun notifyAlerts(ctx: Context): Boolean = sp(ctx).getBoolean("notify_alerts", true)
    fun setNotifyAlerts(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("notify_alerts", on).apply()
    /** Remembers which notification was last sent, so each goes out at most once per day / value. */
    fun lastNotified(ctx: Context, key: String): String? = sp(ctx).getString("notified_$key", null)
    fun setLastNotified(ctx: Context, key: String, v: String) = sp(ctx).edit().putString("notified_$key", v).apply()

    // ---- multi-year Fitness history for the streak calendar (refreshed daily)
    fun longHistory(ctx: Context): Pair<Long, List<Day>>? = sp(ctx).getString("long_history", null)?.let {
        runCatching {
            val o = org.json.JSONObject(it); val a = o.getJSONArray("d")
            o.getLong("t") to (0 until a.length()).map { i -> val x = a.getJSONArray(i); Day(x.getString(0), x.getDouble(1), x.getDouble(2)) }
        }.getOrNull()
    }
    fun saveLongHistory(ctx: Context, days: List<Day>) {
        val a = org.json.JSONArray(); days.forEach { a.put(org.json.JSONArray().put(it.date).put(it.ctl).put(it.atl)) }
        sp(ctx).edit().putString("long_history", org.json.JSONObject().put("t", System.currentTimeMillis()).put("d", a).toString()).apply()
    }

    /** Daily actual and planned load (for plan-adherence streak rules). */
    fun loadIndex(ctx: Context): Pair<Map<String, Int>, Map<String, Int>>? = sp(ctx).getString("load_index", null)?.let {
        runCatching {
            val o = org.json.JSONObject(it)
            fun m(k: String) = o.getJSONObject(k).let { j -> j.keys().asSequence().associateWith { key -> j.getInt(key) } }
            m("a") to m("p")
        }.getOrNull()
    }
    fun saveLoadIndex(ctx: Context, actual: Map<String, Int>, planned: Map<String, Int>) =
        sp(ctx).edit().putString("load_index", org.json.JSONObject().put("a", org.json.JSONObject(actual as Map<*, *>)).put("p", org.json.JSONObject(planned as Map<*, *>)).toString()).apply()
    /** Plan-adherence tolerance in % (0 = off): a week on plan within this counts as passed. */
    fun planTolerance(ctx: Context): Int = sp(ctx).getInt("plan_tolerance", 15)
    fun setPlanTolerance(ctx: Context, v: Int) = sp(ctx).edit().putInt("plan_tolerance", v).apply()

    fun ttDates(ctx: Context): Pair<Long, List<String>>? = sp(ctx).getString("tt_dates", null)?.let {
        runCatching { val o = org.json.JSONObject(it); val a = o.getJSONArray("d"); o.getLong("t") to (0 until a.length()).map { i -> a.getString(i) } }.getOrNull()
    }
    fun saveTtDates(ctx: Context, d: List<String>) =
        sp(ctx).edit().putString("tt_dates", org.json.JSONObject().put("t", System.currentTimeMillis()).put("d", org.json.JSONArray(d)).toString()).apply()

    // ---- numbers you enter in Settings (override intervals.icu / measured values in this app only)
    fun cpOverride(ctx: Context): Double? = sp(ctx).getFloat("cp_override", 0f).takeIf { it > 0 }?.toDouble()
    fun setCpOverride(ctx: Context, w: Double?) = sp(ctx).edit().putFloat("cp_override", (w ?: 0.0).toFloat()).apply()
    /** Today's resting HR / HRV you entered (used as today's reading). Stored with the date they apply to. */
    fun rhrOverride(ctx: Context): Double? = todayValue(ctx, "rhr_override")
    fun setRhrOverride(ctx: Context, v: Double?) = setTodayValue(ctx, "rhr_override", v)
    fun hrvOverride(ctx: Context): Double? = todayValue(ctx, "hrv_override")
    fun setHrvOverride(ctx: Context, v: Double?) = setTodayValue(ctx, "hrv_override", v)
    private fun todayValue(ctx: Context, k: String): Double? {
        val p = sp(ctx)
        if (p.getString("${k}_date", null) != java.time.LocalDate.now().toString()) return null
        return p.getFloat(k, 0f).takeIf { it > 0 }?.toDouble()
    }
    private fun setTodayValue(ctx: Context, k: String, v: Double?) =
        sp(ctx).edit().putFloat(k, (v ?: 0.0).toFloat()).putString("${k}_date", java.time.LocalDate.now().toString()).apply()

    /** SubT pace, min/km. */
    fun subtOverride(ctx: Context): Double? = sp(ctx).getFloat("subt_override", 0f).takeIf { it > 0 }?.toDouble()
    fun setSubtOverride(ctx: Context, p: Double?) = sp(ctx).edit().putFloat("subt_override", (p ?: 0.0).toFloat()).apply()

    // ---- PBs and race gap

    fun pbs(ctx: Context): Pbs? = sp(ctx).getString("pbs", null)?.let { Pbs.fromJson(it) }
    fun savePbs(ctx: Context, p: Pbs) = sp(ctx).edit().putString("pbs", p.toJson()).apply()

    /** PBs you typed in (seconds); 0 = none. They win when faster than what intervals.icu has. */
    fun manualPb(ctx: Context, key: String): Double? = sp(ctx).getFloat("pb_manual_$key", 0f).takeIf { it > 0 }?.toDouble()
    fun setManualPb(ctx: Context, key: String, sec: Double?) = sp(ctx).edit().putFloat("pb_manual_$key", (sec ?: 0.0).toFloat()).apply()

    fun effectivePbs(ctx: Context): Pbs {
        val f = pbs(ctx)
        // A PB you entered is treated as accurate and always wins
        fun pick(fetched: Pb?, key: String): Pb? = manualPb(ctx, key)?.let { Pb(it, null, "entered by you") } ?: fetched
        return Pbs(f?.fetchedAt ?: 0L, pick(f?.fiveK, "5k"), pick(f?.tenK, "10k"), pick(f?.half, "hm"))
    }
    /** true = calibrate the race gap from your 5K PB; false = the projection page's 11 %. */
    fun gapFromPb(ctx: Context): Boolean = sp(ctx).getBoolean("gap_from_pb", true)
    fun setGapFromPb(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("gap_from_pb", on).apply()

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

    /** Runs already read for SubT laps (so runs without any aren't fetched again). */
    fun subtScanned(ctx: Context): Set<String> = sp(ctx).getStringSet("subt_scanned_v2", emptySet()) ?: emptySet()
    fun saveSubtScanned(ctx: Context, ids: Set<String>) = sp(ctx).edit().putStringSet("subt_scanned_v2", HashSet(ids)).apply()
    fun subtReps(ctx: Context): Pair<Long, List<SubtRep>>? = sp(ctx).getString("subt_reps", null)?.let { SubtRep.listFromJson(it) }
    fun saveSubtReps(ctx: Context, reps: List<SubtRep>) =
        sp(ctx).edit().putString("subt_reps", SubtRep.listToJson(reps, System.currentTimeMillis())).apply()

    // ---- time in target

    fun zones(ctx: Context): List<Zones.Run> = sp(ctx).getString("zones_v7", null)?.let { Zones.fromJson(it) }.orEmpty()
    fun saveZones(ctx: Context, list: List<Zones.Run>) = sp(ctx).edit().putString("zones_v7", Zones.toJson(list)).apply()
    fun lt1(ctx: Context): Int = sp(ctx).getInt("lt1_hr", 153)
    fun setLt1(ctx: Context, v: Int) = sp(ctx).edit().putInt("lt1_hr", v).apply()

    // ---- coach: tone of the hand-off prompt (0 direct, 1 encouraging, 2 analytical)
    fun coachTone(ctx: Context): Int = sp(ctx).getInt("coach_tone", 0).coerceIn(0, 2)
    fun setCoachTone(ctx: Context, v: Int) = sp(ctx).edit().putInt("coach_tone", v.coerceIn(0, 2)).apply()

    // ---- coach notes (one "Coach notes" note per day in intervals.icu)
    /** Cached note descriptions by date (yyyy-MM-dd), so screens render offline. */
    fun coachNotes(ctx: Context): Map<String, String> = sp(ctx).getString("coach_notes", null)?.let {
        runCatching { val o = org.json.JSONObject(it); o.keys().asSequence().associateWith { k -> o.getString(k) } }.getOrNull()
    }.orEmpty()
    fun saveCoachNotes(ctx: Context, m: Map<String, String>) =
        sp(ctx).edit().putString("coach_notes", org.json.JSONObject(m as Map<*, *>).toString()).apply()
    fun coachSync(ctx: Context): Boolean = sp(ctx).getBoolean("coach_sync", true)
    fun setCoachSync(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("coach_sync", on).apply()
    /** Activity ids already handled (a section written, or skipped as not eligible). */
    fun coachNoted(ctx: Context): Set<String> = sp(ctx).getStringSet("coach_noted", emptySet()) ?: emptySet()
    fun saveCoachNoted(ctx: Context, ids: Set<String>) = sp(ctx).edit().putStringSet("coach_noted", HashSet(ids)).apply()
    /** Replies the "Coach replied" card has already shown: "<date>|W|<activityId>" / "<date>|Q|<n>". */
    fun coachSeen(ctx: Context): Set<String> = sp(ctx).getStringSet("coach_seen", emptySet()) ?: emptySet()
    fun saveCoachSeen(ctx: Context, keys: Set<String>) = sp(ctx).edit().putStringSet("coach_seen", HashSet(keys)).apply()

    // ---- per-widget settings

    fun style(ctx: Context, id: Int): GraphStyle =
        runCatching { GraphStyle.valueOf(sp(ctx).getString("style_$id", null) ?: "AUTO") }.getOrDefault(GraphStyle.AUTO)

    fun period(ctx: Context, id: Int): Int = sp(ctx).getInt("period_$id", Periods.DEFAULT)

    fun saveWidget(ctx: Context, id: Int, style: GraphStyle, period: Int) =
        sp(ctx).edit().putString("style_$id", style.name).putInt("period_$id", period).apply()

    fun deleteWidget(ctx: Context, id: Int) = sp(ctx).edit().remove("style_$id").remove("period_$id").apply()
}
