package dev.subt.trainingwidget

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Ties the forecast cache, your settings and the intervals.icu week together into day plans. */
object RunWindows {
    enum class Lead { TODAY, TOMORROW }

    /**
     * Which day the Today run-window card leads with: tomorrow from 20:00, once today's run is done, or when no window
     * remains today (the best one has started or passed, or none fits and there's no split). Otherwise today.
     */
    fun leadDay(hour: Int, passed: Boolean, done: Boolean, hasWindow: Boolean, hasSplit: Boolean = false): Lead =
        // Today stays first until 20:00 unless today's run is done: a passed or missing window
        // still shows today (with the next best time), never jumps to tomorrow early
        if (hour >= 20 || done) Lead.TOMORROW else Lead.TODAY

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
     * Plans for [days] days from [from]. Today's plan skips windows already started (unless [useNow] is false).
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
            val run = Weather.runOf(week, date.toString())
            Weather.plan(date, d.all, d.rb, d.suns, run, s, if (useNow && date == today) nowSec else null, zone, second = if (run.planned && !run.done) Weather.secondOf(week, date.toString()) else null)
        }
        return list
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

    /** "Today: 07:00–14:00 · best for SubT 08:00–10:00" (plus the ② line) for the morning notification; null when there is no usable window today. */
    fun todayLine(ctx: Context): String? {
        val p = plan(ctx, LocalDate.now()) ?: return null
        if (p.noData || p.passed || p.run.done) return null
        val zone = ZoneId.systemDefault()
        p.split?.let { return "Run window: " + Weather.splitLabel(it, zone) }
        if (p.windows.isEmpty()) return null
        val n = RwText.dayNote(p, RwStore.load(ctx), zone, today = true, nowSec = System.currentTimeMillis() / 1000)
        return (listOf(n.title) + n.body.lines().filter { it.startsWith("②") }).joinToString("\n")
    }

    // ---------------------------------------------------------------- refreshing

    /** Fetches every source; [notify] also checks whether today's or tomorrow's best window moved and says so. */
    fun refresh(ctx: Context, notify: Boolean): WeatherApi.Report {
        val rep = WeatherApi.fetchAll(ctx)
        if (rep.anyOk) {
            runCatching { data(ctx)?.let { Scoring.recordForecasts(ctx, it) } }
            runCatching { Scoring.observeTile(ctx, data(ctx)) }
            runCatching { checkWindows(ctx, notify) }
            if (notify) runCatching { Notifier.swapPrompts(ctx) }
            runCatching { Watch.evaluate(ctx) }
            runCatching { RwWidgets.renderAll(ctx) }
        }
        return rep
    }

    /**
     * Compares today's and tomorrow's windows with the ones last seen. Alerts only when the window itself changes (see
     * [RwText.windowChange]), never while you are inside an open window, plus one "You missed today's window" when the
     * last window has closed with the run not done, and the optional "45 min left".
     */
    private fun checkWindows(ctx: Context, notify: Boolean) {
        val zone = ZoneId.systemDefault()
        val nowSec = System.currentTimeMillis() / 1000
        val today = LocalDate.now()
        val plans = plans(ctx, today, 2, useNow = true)
        for (p in plans) {
            if (p.noData) continue
            val spans = RwText.spans(p.windows)
            val key = "rw_wins_${p.date}"
            val old = RwText.decode(RwStore.getStr(ctx, key))
            RwStore.putStr(ctx, key, RwText.encode(spans))
            if (!notify || old == null || p.run.done || !p.run.planned) continue
            if (p.date == today && RwText.insideOpen(spans, nowSec)) continue
            if (!NotifySettings.isOn(ctx, NotifySettings.cat("rw_shift"))) continue
            RwText.windowChange(old, spans, zone)?.let { Notifier.windowShifted(ctx, it.body, p.date, it.title) }
        }
        val p0 = plans.firstOrNull() ?: return
        if (!notify || p0.noData || !p0.run.planned || p0.run.done) return
        val spans = RwText.spans(p0.windows)
        if (NotifySettings.isOn(ctx, NotifySettings.cat("rw_missed"))) {
            val closed = RwText.justClosed(spans, nowSec, p0.run.planned, p0.run.done)
            if (closed != null) {
                val tomorrow = plans.getOrNull(1)?.takeIf { !it.noData }?.let { RwText.spans(it.windows) }.orEmpty()
                val note = RwText.missedNote(closed, spans, tomorrow, nowSec, zone)
                Notifier.windowMissed(ctx, note, today, "$today|${closed.second}")
            }
        }
        if (NotifySettings.isOn(ctx, NotifySettings.cat("rw_left"))) {
            RwText.leftIn(spans, nowSec)?.let { w ->
                Notifier.windowLeft(ctx, "Run ${p0.run.name}: the window closes at ${Weather.hhmm(w.second, zone)}.", today, "$today|${w.second}")
            }
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
        val last = p.windows.maxOfOrNull { it.end } ?: p.window?.end ?: return false
        // until the last window has closed (plus a check for the "missed" notice)
        return p.run.planned && !p.run.done && System.currentTimeMillis() / 1000 < last + 90 * 60
    }
}
