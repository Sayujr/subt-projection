package dev.subt.trainingwidget

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Ties the forecast cache, your settings and the intervals.icu week together into day plans. */
object RunWindows {
    /** Every source's cached forecast, parsed. */
    class WxData(
        val src: Map<String, List<Weather.Hour>>,
        val rbHours: List<Weather.Hour>,
        val now: List<Weather.NowStep>,
        val aqi: Map<Long, Double>,
        val suns: List<Weather.Sun>,
        val fetched: Map<String, Long>,
        /** Learned source weights from self-scoring (empty = equal). */
        val weights: Map<String, Double> = emptyMap(),
    ) {
        val all: List<Weather.Combined> by lazy { Weather.combine(src, now, aqi, weights) }
        val rb: List<Weather.Combined> by lazy { if (rbHours.isEmpty()) emptyList() else Weather.combine(mapOf("rainbow" to rbHours), now, aqi) }
        val lastFetch: Long get() = fetched.values.maxOrNull() ?: 0L
    }

    private val IDS = listOf("om", "nws", "rainbow", "nowcast", "aqi")
    @Volatile private var memo: Pair<String, WxData>? = null
    @Volatile private var busy = false
    @Volatile private var lastAttempt = 0L

    fun hasLocation(ctx: Context): Boolean = RwStore.load(ctx).let { it.lat != null && it.lon != null }

    /** The cached forecasts, parsed (and kept parsed until a source is refreshed); null when nothing is cached. */
    fun data(ctx: Context): WxData? {
        val caches = IDS.associateWith { RwStore.cache(ctx, it) }
        val weights = Scoring.weights(ctx)
        val key = caches.entries.joinToString("|") { "${it.key}:${it.value?.second}" } + "|w" + weights.toSortedMap().toString()
        memo?.let { if (it.first == key) return it.second }
        val src = LinkedHashMap<String, List<Weather.Hour>>()
        var suns: List<Weather.Sun> = emptyList()
        val fetched = HashMap<String, Long>()
        caches["om"]?.let { (body, t) ->
            Weather.parseOpenMeteo(body)?.let { om -> src.putAll(om.models); suns = om.sun; fetched["Forecast models"] = t }
        }
        caches["nws"]?.let { (body, t) -> Weather.parseNws(body).takeIf { it.isNotEmpty() }?.let { src["nws"] = it; fetched["NWS"] = t } }
        var rbHours: List<Weather.Hour> = emptyList()
        caches["rainbow"]?.let { (body, t) -> Weather.parseRainbow(body).takeIf { it.isNotEmpty() }?.let { rbHours = it; src["rainbow"] = it; fetched["Rainbow"] = t } }
        val now = caches["nowcast"]?.let { (body, t) -> fetched["Rainbow nowcast"] = t; Weather.parseNowcast(body) }.orEmpty()
        val aqi = caches["aqi"]?.let { (body, t) -> fetched["Air quality"] = t; Weather.parseAqi(body) }.orEmpty()
        if (src.isEmpty()) return null
        val d = WxData(src, rbHours, now, aqi, suns, fetched, weights)
        memo = key to d
        return d
    }

    /** "as of 14:05" when the newest data is older than 3 hours; null when fresh. */
    fun asOf(ctx: Context): String? {
        val d = data(ctx) ?: return null
        val age = System.currentTimeMillis() - d.lastFetch
        if (age <= 3 * 3600_000L) return null
        return "as of " + Weather.hhmm(d.lastFetch / 1000, ZoneId.systemDefault())
    }

    /**
     * Plans for [days] days from [from]. Today's plan skips windows already started (unless [useNow] is false);
     * the 7-day list also gets the text-only "swap" hints.
     */
    fun plans(ctx: Context, from: LocalDate, days: Int, useNow: Boolean = true): List<Weather.DayPlan> {
        val s = RwStore.load(ctx)
        if (s.lat == null || s.lon == null) return emptyList()
        val d = data(ctx) ?: return emptyList()
        val week = weekRuns(ctx)
        val zone = ZoneId.systemDefault()
        val nowSec = System.currentTimeMillis() / 1000
        val today = LocalDate.now()
        val list = (0 until days).map { i ->
            val date = from.plusDays(i.toLong())
            Weather.plan(date, d.all, d.rb, d.suns, Weather.runOf(week, date.toString()), s, if (useNow && date == today) nowSec else null, zone)
        }
        val hints = Weather.swapHints(list, zone)
        return list.map { it.withSwap(hints[it.date]) }
    }

    /** This week's sessions plus the planned ones over the next week (with their intervals.icu event ids). */
    fun weekRuns(ctx: Context): List<Workout> {
        val snap = Prefs.snapshot(ctx) ?: return emptyList()
        return snap.week + snap.ahead.filter { a -> snap.week.none { it.date == a.date && it.name == a.name && it.eventId == a.eventId } }
    }

    /**
     * The "Swap in intervals.icu" offer for [date] (today or tomorrow), or null. Judged over the 7 days from today
     * so it sees the same plans as the Week list.
     */
    fun swapFor(ctx: Context, date: LocalDate): Weather.SwapPlan? {
        if (Prefs.credentials(ctx) == null) return null
        val today = LocalDate.now()
        val plans = plans(ctx, today, 7)
        if (plans.isEmpty()) return null
        return Weather.swapSuggestions(plans, weekRuns(ctx), today)[date]
    }

    /** One day's plan (with swap hints computed over the 7 days from today when it falls in that range). */
    fun plan(ctx: Context, date: LocalDate): Weather.DayPlan? {
        val today = LocalDate.now()
        val offset = java.time.temporal.ChronoUnit.DAYS.between(today, date).toInt()
        if (offset in 0..6) return plans(ctx, today, 7).getOrNull(offset)
        return plans(ctx, date, 1).firstOrNull()
    }

    /** "Best run window 07:00–08:30 ⚠ · Cool, cloudy · 9–12° · dry" for the morning notification; null when no usable window today. */
    fun todayLine(ctx: Context): String? {
        val p = plan(ctx, LocalDate.now()) ?: return null
        val w = p.window ?: return null
        if (p.noData || p.passed) return null
        val zone = ZoneId.systemDefault()
        p.split?.let { return "Run window: " + Weather.splitLabel(it, zone) }
        return "Best run window ${Weather.range(w, zone)}" + (if (w.icons.isNotEmpty()) " " + w.icons.joinToString("") else "") + " · " + p.summary.short
    }

    // ---------------------------------------------------------------- refreshing

    /** Fetches every source; [notify] also checks whether today's or tomorrow's best window moved and says so. */
    fun refresh(ctx: Context, notify: Boolean): WeatherApi.Report {
        val rep = WeatherApi.fetchAll(ctx)
        if (rep.anyOk) {
            runCatching { data(ctx)?.let { Scoring.recordForecasts(ctx, it) } }
            runCatching { Scoring.observeTile(ctx, data(ctx)) }
            runCatching { checkShift(ctx, notify) }
            runCatching { Watch.evaluate(ctx) }
            runCatching { RwWidgets.renderAll(ctx) }
        }
        return rep
    }

    /** Compares today's and tomorrow's whole-day best window with the one last seen. */
    private fun checkShift(ctx: Context, notify: Boolean) {
        val zone = ZoneId.systemDefault()
        val nowSec = System.currentTimeMillis() / 1000
        for (p in plans(ctx, LocalDate.now(), 2, useNow = false)) {
            if (p.noData) continue
            val new = Weather.sigOf(p) ?: continue
            val key = "rw_sig_${p.date}"
            val old = Weather.Sig.decode(RwStore.getStr(ctx, key))
            RwStore.putStr(ctx, key, new.encode())
            if (!notify || new.end <= nowSec) continue
            if (!NotifySettings.isOn(ctx, NotifySettings.cat("rw_shift"))) continue
            val watching = p.date == LocalDate.now() && Watch.isOn(ctx)
            val msg = Weather.shiftMessage(old, new, zone, any = watching) ?: continue
            Notifier.windowShifted(ctx, msg, p.date)
        }
    }

    /** Refreshes when the cache is older than [maxAgeMin], outside quiet hours (22:00–05:00). */
    fun refreshIfDue(ctx: Context, maxAgeMin: Int) {
        if (!hasLocation(ctx) || Weather.quiet(LocalTime.now().hour)) return
        val age = System.currentTimeMillis() - (data(ctx)?.lastFetch ?: 0L)
        if (age > maxAgeMin * 60_000L) refresh(ctx, notify = true)
    }

    /** Background refresh for the screens; [done] runs on the main thread. Manual taps ([manual]) skip the retry throttle. */
    fun refreshAsync(ctx: Context, manual: Boolean, done: (WeatherApi.Report) -> Unit) {
        if (busy) return
        if (!manual && System.currentTimeMillis() - lastAttempt < 10 * 60_000L) return
        busy = true
        lastAttempt = System.currentTimeMillis()
        val app = ctx.applicationContext
        Thread {
            val r = runCatching { refresh(app, notify = false) }.getOrElse { WeatherApi.Report(emptyList(), listOf("Error")) }
            busy = false
            Handler(Looper.getMainLooper()).post { done(r) }
        }.start()
    }

    /** From a screen: fetch quietly when there is no data or it is over an hour old. */
    fun autoRefresh(ctx: Context, done: () -> Unit) {
        if (!hasLocation(ctx) || Weather.quiet(LocalTime.now().hour)) return
        val age = System.currentTimeMillis() - (data(ctx)?.lastFetch ?: 0L)
        if (age > 60 * 60_000L) refreshAsync(ctx, manual = false) { done() }
    }

    /** On a day with a planned run still ahead: check hourly from 05:00 until its window starts. */
    fun needsHourly(ctx: Context): Boolean {
        if (!hasLocation(ctx)) return false
        val hour = LocalTime.now().hour
        if (hour < 5 || hour >= 22) return false
        val p = plans(ctx, LocalDate.now(), 1, useNow = false).firstOrNull() ?: return false
        val w = p.window ?: return false
        return p.run.planned && !p.run.done && System.currentTimeMillis() / 1000 < w.start
    }
}
