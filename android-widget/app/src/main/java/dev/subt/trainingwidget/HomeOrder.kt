package dev.subt.trainingwidget

import android.content.Context

/** The order of the cards on the Today screen, set in Settings → Home screen. */
object HomeOrder {
    /** key → name shown in Settings. */
    val CARDS = linkedMapOf(
        "verdict" to "Today's call",
        "today" to "Today (workouts, Fitness chart)",
        "power" to "Today's power targets",
        "window" to "Best run window",
        "coach" to "Coach replied",
        "deviations" to "Your numbers vs intervals.icu",
        "recent" to "Recent workouts",
        "checklist" to "This week's checklist",
        "streak" to "Streak",
    )
    val DEFAULT = listOf("verdict", "today", "power", "window", "coach", "deviations", "recent", "checklist", "streak")
    private const val K = "home_order"

    /** Saved order; cards added in later versions are appended in their default place, unknown keys dropped. */
    fun load(ctx: Context): List<String> {
        val saved = ctx.getSharedPreferences("training_widget", Context.MODE_PRIVATE).getString(K, null)
            ?.split(",")?.map { it.trim() }?.filter { it in CARDS }?.distinct() ?: return DEFAULT
        return merge(saved)
    }

    fun merge(saved: List<String>): List<String> {
        val out = saved.toMutableList()
        for ((i, k) in DEFAULT.withIndex()) if (k !in out) out.add(minOf(i, out.size), k)
        return out
    }

    fun save(ctx: Context, order: List<String>) =
        ctx.getSharedPreferences("training_widget", Context.MODE_PRIVATE).edit().putString(K, order.joinToString(",")).apply()

    fun move(order: List<String>, key: String, by: Int): List<String> {
        val i = order.indexOf(key); val j = i + by
        if (i < 0 || j !in order.indices) return order
        return order.toMutableList().apply { removeAt(i); add(j, key) }
    }
}
