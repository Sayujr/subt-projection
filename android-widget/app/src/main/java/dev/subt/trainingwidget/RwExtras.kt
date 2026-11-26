package dev.subt.trainingwidget

import android.content.Context
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** How often the app offers to check whether you've moved (Settings → Run windows). */
enum class AskMode(val key: String, val label: String) {
    DAILY("daily", "Once a day"),
    ZONE_ONLY("zone", "Only when time zone changes"),
    NEVER("never", "Never");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: DAILY
    }
}

/** Pure rules for the "Change in location?" prompt (tested). */
object LocRules {
    const val MOVED_KM = 25.0

    /** Great-circle distance in km. */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** More than 25 km from the saved place. */
    fun moved(km: Double) = km > MOVED_KM

    /** Dismissed for [days] from [today]: the prompt is skipped until that date arrives. */
    fun dismissUntil(today: LocalDate, days: Int): LocalDate = today.plusDays(days.toLong())

    fun isDismissed(today: LocalDate, until: LocalDate?) = until != null && today.isBefore(until)

    /**
     * Whether to offer the check now. Never when the mode is Never or you dismissed it until a later date; a changed time
     * zone makes it due at once (modes Once a day and Only when time zone changes); otherwise once a day in Once a day mode.
     */
    fun isDue(now: LocalDate, lastCheck: LocalDate?, dismissUntil: LocalDate?, mode: AskMode, zoneChanged: Boolean): Boolean {
        if (mode == AskMode.NEVER) return false
        if (isDismissed(now, dismissUntil)) return false
        if (zoneChanged) return true
        return mode == AskMode.DAILY && lastCheck != now
    }

    /** Coordinates kept to 2 decimals (about 1 km): enough for a forecast, no more precise than we need. */
    fun round2(v: Double): Double = (v * 100).roundToInt() / 100.0

    /** "40.01, -105.27" for when no place name could be found. */
    fun latLon(lat: Double, lon: Double) = String.format(java.util.Locale.US, "%.2f, %.2f", lat, lon)

    /** Zone changed: a zone was recorded earlier and it differs from now. No recorded zone = nothing to compare with. */
    fun zoneChanged(stored: String?, now: String) = !stored.isNullOrEmpty() && stored != now
}

/** Stored state for the location prompt. */
object LocState {
    private const val K_DISMISS = "rw_loc_dismiss"      // epoch day
    private const val K_LAST = "rw_loc_last"            // epoch day of the last completed check
    private const val K_ZONE = "rw_loc_zone"
    private const val K_MODE = "rw_loc_mode"

    fun mode(ctx: Context) = AskMode.of(RwStore.getStr(ctx, K_MODE))
    fun setMode(ctx: Context, m: AskMode) = RwStore.putStr(ctx, K_MODE, m.key)

    private fun day(ctx: Context, k: String): LocalDate? = RwStore.getLong(ctx, k).takeIf { it > 0 }?.let { LocalDate.ofEpochDay(it) }
    fun dismissUntil(ctx: Context): LocalDate? = day(ctx, K_DISMISS)
    fun setDismissUntil(ctx: Context, d: LocalDate) = RwStore.putLong(ctx, K_DISMISS, d.toEpochDay())
    fun lastCheck(ctx: Context): LocalDate? = day(ctx, K_LAST)
    fun markChecked(ctx: Context) {
        RwStore.putLong(ctx, K_LAST, LocalDate.now().toEpochDay())
        markZone(ctx)
    }

    fun zoneNow(): String = ZoneId.systemDefault().id
    fun markZone(ctx: Context) = RwStore.putStr(ctx, K_ZONE, zoneNow())

    /** First time (e.g. an existing install) just records the current zone. */
    fun zoneChanged(ctx: Context): Boolean {
        val stored = RwStore.getStr(ctx, K_ZONE)
        if (stored == null) { markZone(ctx); return false }
        return LocRules.zoneChanged(stored, zoneNow())
    }

    fun due(ctx: Context): Boolean = LocRules.isDue(LocalDate.now(), lastCheck(ctx), dismissUntil(ctx), mode(ctx), zoneChanged(ctx))
}

/**
 * "Watch today closely": the forecast is checked every 30 minutes until the window starts (or 22:00), and any change of
 * the best window or its warnings is announced. On by your tap, or by itself for a narrow (⚠) window or sources that disagree.
 */
object Watch {
    private const val K_DATE = "rw_watch_date"
    private const val K_REASON = "rw_watch_reason"
    private const val K_OFF = "rw_watch_off"

    /** Agreement below this share of sources turns it on. */
    const val DISAGREE_BELOW = 0.70

    /** Pure rule: the reason to turn watching on by itself for a run day, or null. */
    fun autoReason(planned: Boolean, done: Boolean, passed: Boolean, narrow: Boolean, agree: Int, total: Int): String? {
        if (!planned || done || passed) return null
        if (narrow) return "narrow window ⚠"
        if (total >= 2 && agree.toDouble() / total < DISAGREE_BELOW) return "sources disagree ($agree/$total agree)"
        return null
    }

    fun autoReason(p: Weather.DayPlan): String? {
        val w = p.window ?: return null
        return autoReason(p.run.planned, p.run.done, p.passed, w.narrow, w.agree, w.total)
    }

    private fun today() = LocalDate.now().toString()

    fun isOn(ctx: Context) = RwStore.getStr(ctx, K_DATE) == today()
    fun reason(ctx: Context): String? = if (isOn(ctx)) RwStore.getStr(ctx, K_REASON) else null

    private fun on(ctx: Context, reason: String) {
        RwStore.putStr(ctx, K_DATE, today()); RwStore.putStr(ctx, K_REASON, reason)
    }

    private fun off(ctx: Context) { RwStore.putStr(ctx, K_DATE, "") }

    /** The button on the day plan: switches watching on or off for today. Turning it off by hand stops the automatic switch-on today. */
    fun toggle(ctx: Context): Boolean {
        return if (isOn(ctx)) {
            off(ctx); RwStore.putStr(ctx, K_OFF, today()); false
        } else {
            on(ctx, "you asked"); RwStore.putStr(ctx, K_OFF, ""); RunWindowSchedule.chainWatch(ctx, first = true); true
        }
    }

    /** After each refresh: stop once the window has started or it's 22:00; otherwise switch on by itself when the rule says so. */
    fun evaluate(ctx: Context) {
        if (!RunWindows.hasLocation(ctx)) return
        val nowSec = System.currentTimeMillis() / 1000
        val p = RunWindows.plan(ctx, LocalDate.now()) ?: return
        if (isOn(ctx)) {
            val w = p.window
            if (w == null || nowSec >= w.start || LocalTime.now().hour >= 22) off(ctx)
            return
        }
        if (RwStore.getStr(ctx, K_OFF) == today()) return
        if (Weather.quiet(LocalTime.now().hour)) return
        val reason = autoReason(p) ?: return
        on(ctx, reason)
    }

    /** True while there's reason to keep checking: on, before 22:00, and the window hasn't started. */
    fun active(ctx: Context): Boolean {
        if (!isOn(ctx) || LocalTime.now().hour >= 22) return false
        val w = RunWindows.plan(ctx, LocalDate.now())?.window ?: return false
        return System.currentTimeMillis() / 1000 < w.start
    }
}
