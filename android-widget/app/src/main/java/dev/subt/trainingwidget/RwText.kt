package dev.subt.trainingwidget

import java.time.ZoneId
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Plain-language pieces of the v3 run-window screens and notifications: headline, sub lines, best-stretch line, two-run
 * lines, the notification texts, and the pure rules for "window changed" and "you missed the window". No Android calls.
 */
object RwText {
    // ---------------------------------------------------------------- sky

    /** ☀️⛅🌥️☁️🌦️🌧️🌙 by rain, cloud and night (the chart's icon row and the scrub card). */
    fun skyEmoji(cloud: Double?, rainMm: Double, night: Boolean): String {
        val c = cloud ?: 50.0
        if (rainMm > 0.5) return "🌧️"
        if (rainMm > 0.25) return "🌦️"
        if (night) return if (c >= 60) "☁️" else "🌙"
        return if (c >= 80) "☁️" else if (c >= 50) "🌥️" else if (c >= 25) "⛅" else "☀️"
    }

    fun skyLabel(cloud: Double?, rainMm: Double, night: Boolean): String {
        val c = cloud ?: 50.0
        return when {
            rainMm > 0.5 -> "Rain"
            rainMm > 0.25 -> "Light rain"
            c >= 80 -> "Cloudy"
            c >= 50 -> "Mostly cloudy"
            c >= 25 -> "Partly cloudy"
            night -> "Clear night"
            else -> "Clear"
        }
    }

    fun num1(v: Double): String = if (Math.abs(v - v.roundToInt()) < 0.05) v.roundToInt().toString() else String.format(Locale.US, "%.1f", v)

    // ---------------------------------------------------------------- windows

    fun range(w: Weather.Window, zone: ZoneId) = Weather.range(w, zone)

    /** "07:00–12:00", "07:00–12:00 and 17:00–18:30", "A, B and C". */
    fun windowsList(wins: List<Weather.Window>, zone: ZoneId): String {
        val r = wins.map { range(it, zone) }
        return when (r.size) {
            0 -> ""
            1 -> r[0]
            else -> r.dropLast(1).joinToString(", ") + " and " + r.last()
        }
    }

    /** The windows still to come (or open) now; all of them for another day. */
    fun remaining(p: Weather.DayPlan, nowSec: Long?): List<Weather.Window> =
        p.windows.filter { nowSec == null || it.end > nowSec }

    class Head(val text: String, val tight: Boolean, val none: Boolean)

    /** "Run any time A–B", amber "Tight: A–B ⚠", "A–B and C–D", or "No window fits". */
    fun headline(p: Weather.DayPlan, zone: ZoneId, nowSec: Long? = null): Head {
        val wins = remaining(p, nowSec)
        if (wins.isEmpty()) return Head("No window fits", tight = false, none = true)
        return when {
            wins.all { it.narrow } -> Head("Tight: ${range(wins.first(), zone)} ⚠", tight = true, none = false)
            wins.size == 1 -> Head("Run any time ${range(wins.first(), zone)}", tight = false, none = false)
            else -> Head(windowsList(wins.take(2), zone), tight = false, none = false)
        }
    }

    /** The windows after the first one, for "then UV above 2 until 18:30 · next 19:30–21:00". */
    fun nextText(p: Weather.DayPlan, zone: ZoneId, nowSec: Long? = null): String? {
        val wins = remaining(p, nowSec)
        if (wins.size < 2) return null
        return "next " + windowsList(wins.drop(1).take(2), zone)
    }

    private fun hoursIn(dh: List<Weather.Combined>, a: Long, b: Long) = dh.filter { it.t < b && it.t + 3600 > a }

    fun windSpeedWord(maxWind: Double?): String = when {
        maxWind == null -> "wind unknown"
        maxWind < 15.0 -> "light wind"
        maxWind < 25.0 -> "breezy"
        else -> "windy"
    }

    /** "Best for SubT 08:00–10:00 · dew 2°, 7 °C, light wind"; null without a best stretch. */
    fun bestLine(best: Weather.Window?, run: Weather.PlannedRun, dh: List<Weather.Combined>, zone: ZoneId): String? {
        if (best == null) return null
        val hs = hoursIn(dh, best.start, best.end)
        val dew = hs.mapNotNull { it.dew }.takeIf { it.isNotEmpty() }?.average()
        val temp = hs.mapNotNull { it.temp }.takeIf { it.isNotEmpty() }?.average()
        val wind = hs.mapNotNull { it.wind }.maxOrNull()
        val bits = ArrayList<String>()
        if (dew != null) bits += "dew ${dew.roundToInt()}°"
        if (temp != null) bits += "${temp.roundToInt()} °C"
        bits += windSpeedWord(wind)
        return "Best for ${run.type.label} ${range(best, zone)} · " + bits.joinToString(", ")
    }

    /** The summary box's "pick" line. */
    fun pickLine(wins: List<Weather.Window>, best: Weather.Window?, run: Weather.PlannedRun, dh: List<Weather.Combined>, passed: Boolean, zone: ZoneId): String {
        if (passed) return "Today's ${if (wins.size == 1) "window has" else "windows have"} passed (${windowsList(wins, zone)})."
        val head = when {
            wins.all { it.narrow } -> "Tight: only ${windowsList(wins, zone)}."
            wins.size == 1 -> "You can run any time ${range(wins[0], zone)}."
            else -> "Windows: ${windowsList(wins, zone)}."
        }
        return head + (bestLine(best, run, dh, zone)?.let { " $it." } ?: "")
    }

    /** The card's sub line: "60m Easy · ☁️ mostly cloudy · UV ≤ 1.6 · feels 3–6 °C 🧤 · 6/6 agree". */
    fun subLine(p: Weather.DayPlan, s: RwSettings, zone: ZoneId): String {
        val parts = ArrayList<String>()
        parts += if (p.run.planned) p.run.name else "No run planned"
        val w = p.window
        if (w != null) {
            val hs = hoursIn(p.hours, w.start, w.end)
            val cloud = hs.mapNotNull { it.cloud }.takeIf { it.isNotEmpty() }?.average()
            parts += RwText.skyEmoji(cloud, 0.0, false) + " " + skyLabel(cloud, 0.0, false).lowercase(Locale.US)
            parts += "UV ≤ " + num1(hs.maxOfOrNull { it.uv } ?: w.maxUv)
            val feels = hs.mapNotNull { it.feels ?: it.temp }
            val temps = hs.mapNotNull { it.temp }
            if (feels.isNotEmpty() && feels.min() < s.cold) parts += "feels ${feels.min().roundToInt()}–${feels.max().roundToInt()} °C 🧤"
            else if (temps.isNotEmpty()) parts += "${temps.min().roundToInt()}–${temps.max().roundToInt()} °C"
            if (w.total >= 2) parts += "${w.agree}/${w.total} agree"
        }
        return parts.joinToString(" · ")
    }

    // ---------------------------------------------------------------- two runs

    class SecondLine(val first: String, val prefix: String, val hi: String, val rest: String, val bad: Boolean)

    /** ① and ② lines for a two-run day; null on a one-run day. */
    fun twoRunLines(p: Weather.DayPlan, zone: ZoneId): SecondLine? {
        val sec = p.second ?: return null
        val main = p.window
        val single = p.windows.size == 1 && !(main?.narrow ?: true)
        val first = "① ${p.run.name} · " + if (single) "go any time, e.g. morning" else if (main != null) "${range(main, zone)}" else "no window"
        val prefix = "② ${sec.minutes}′ double · "
        val sw = p.secondWindow
        if (sw != null) {
            val within = main != null && sw.start >= main.start && sw.end <= main.end
            if (within) return SecondLine(first, prefix, "after 16:00 → ${range(sw, zone)} ✓", " still in the window", false)
            val dark = p.sun?.let { sw.end > it.set } == true
            val hs = hoursIn(p.hours, sw.start, sw.end)
            val cloud = hs.mapNotNull { it.cloud }.takeIf { it.isNotEmpty() }?.average()
            val bits = ArrayList<String>()
            if (dark) bits += "dark OK"
            bits += if (sw.wet) "rain" else "dry"
            if (cloud != null) bits += "☁️ ${cloud.roundToInt()}%"
            return SecondLine(first, prefix, range(sw, zone), " · " + bits.joinToString(", "), false)
        }
        val sixteen = Weather.atMinutes(p.date, 16 * 60, zone)
        val reasons = p.why.filter { it.key >= sixteen }.values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        return SecondLine(first, prefix, "no run window after 16:00", if (reasons != null) " ($reasons)" else "", true)
    }

    /** "45′ easy ride · 55–65% FTP · ≈ same load (≈24)". */
    fun bikeLine(b: Weather.BikeSwap): String = "${b.minutes}′ easy ride · 55–65% FTP · ≈ same load as the ${b.runMinutes}′ run (≈ ${b.load})"

    /** The text added to the ride event in intervals.icu. */
    fun bikeDescription(b: Weather.BikeSwap): String =
        "- ${b.minutes}m 55-65%\n\nAdded by Training Today (indoor ride instead of ${b.runName}: no run window after 16:00)"

    // ---------------------------------------------------------------- notification texts

    class Note(val title: String, val body: String)

    /** Evening-before ("Tomorrow you can run A–B") and morning ("Today: A–B · best for SubT C–D") texts. */
    fun dayNote(p: Weather.DayPlan, s: RwSettings, zone: ZoneId, today: Boolean, nowSec: Long? = null): Note {
        val day = if (today) "Today" else "Tomorrow"
        val wins = remaining(p, if (today) nowSec else null)
        val tail = ArrayList<String>()
        p.second?.let { sec ->
            val sw = p.secondWindow
            when {
                sw != null -> tail += "② ${sec.minutes}′ double ${Weather.hhmm(sw.start, zone)}–${Weather.hhmm(sw.end, zone)}"
                p.bikeSwap != null -> tail += "② no run window after 16:00: ${p.bikeSwap.minutes}′ indoor ride instead?"
                else -> Unit
            }
        }
        if (wins.isEmpty()) {
            return Note("$day: no run window fits", listOfNotNull(p.summary.suggestion.takeIf { it.isNotBlank() }) .plus(tail).joinToString("\n"))
        }
        val bestPart = bestLine(p.best, p.run, p.hours, zone)?.removePrefix("Best for ")?.let { " · best for " + it.substringBefore(" · ") }.orEmpty()
        if (wins.all { it.narrow }) {
            val next = if (wins.size > 1) "Next: ${windowsList(wins.drop(1).take(2), zone)} · options ›" else "Can't make it? Options ›"
            return Note("Tight ${day.lowercase(Locale.US)}: only ${range(wins.first(), zone)}", (listOf(next) + tail).joinToString("\n"))
        }
        val title = if (today) "Today: ${windowsList(wins.take(2), zone)}$bestPart"
        else "Tomorrow you can run ${windowsList(wins.take(2), zone)}"
        val body = (listOf(subLine(p, s, zone)) + (if (!today && bestPart.isNotEmpty()) listOf(bestPart.removePrefix(" · ").replaceFirstChar { it.uppercase() }) else emptyList()) + tail).joinToString("\n")
        return Note(title, body)
    }

    // ---------------------------------------------------------------- window changes (pure rules)

    /** A window as (start, end) in epoch seconds. */
    fun spans(wins: List<Weather.Window>): List<Pair<Long, Long>> = wins.map { it.start to it.end }

    fun encode(sp: List<Pair<Long, Long>>): String = sp.joinToString(",") { "${it.first}-${it.second}" }

    fun decode(s: String?): List<Pair<Long, Long>>? {
        if (s == null) return null
        if (s.isEmpty()) return emptyList()
        return runCatching { s.split(",").map { p -> p.split("-").let { it[0].toLong() to it[1].toLong() } } }.getOrNull()
    }

    private const val CHANGE_SEC = 1800L

    private fun rangeOf(a: Long, b: Long, zone: ZoneId) = "${Weather.hhmm(a, zone)}–${Weather.hhmm(b, zone)}"

    private fun spanList(sp: List<Pair<Long, Long>>, zone: ZoneId): String {
        val r = sp.map { rangeOf(it.first, it.second, zone) }
        return if (r.size <= 1) r.joinToString("") else r.dropLast(1).joinToString(", ") + " and " + r.last()
    }

    /**
     * Notify only when the window itself changes: it shrinks or grows by 30 minutes or more at either end, splits or merges,
     * closes altogether, or a new one opens. Returns the notification, or null when nothing material changed.
     */
    fun windowChange(old: List<Pair<Long, Long>>, new: List<Pair<Long, Long>>, zone: ZoneId): Note? {
        if (old.isEmpty() && new.isEmpty()) return null
        if (new.isEmpty()) return Note("Run window closed", "No run window fits any more (was ${spanList(old, zone)}).")
        if (old.isEmpty()) return Note("New run window: ${spanList(new, zone)}", "A window opened up.")
        val moved = old.size != new.size || old.indices.any { i ->
            Math.abs(old[i].first - new[i].first) >= CHANGE_SEC || Math.abs(old[i].second - new[i].second) >= CHANGE_SEC
        }
        if (!moved) return null
        val why = when {
            new.size > old.size -> "the window has split"
            new.size < old.size -> "a window has gone"
            new.first().second < old.first().second -> "it now ends earlier"
            new.first().second > old.first().second -> "it now lasts longer"
            new.first().first > old.first().first -> "it now starts later"
            else -> "it now starts earlier"
        }
        return Note("Window changed: now ${spanList(new, zone)}", "$why (was ${spanList(old, zone)})")
    }

    /** True while [nowSec] is inside an open window: no alerts then. */
    fun insideOpen(wins: List<Pair<Long, Long>>, nowSec: Long): Boolean = wins.any { it.first <= nowSec && nowSec < it.second }

    /**
     * The window that has just closed with the run still not done (closed within the last [graceSec]); null while you're
     * inside another open window, when the run is done, or when nothing closed.
     */
    fun justClosed(wins: List<Pair<Long, Long>>, nowSec: Long, planned: Boolean, done: Boolean, graceSec: Long = 3 * 3600L): Pair<Long, Long>? {
        if (!planned || done) return null
        if (insideOpen(wins, nowSec)) return null
        return wins.filter { it.second <= nowSec && nowSec - it.second <= graceSec }.maxByOrNull { it.second }
    }

    /** "You missed today's window (A–B)" with the next option today and tomorrow's first window. */
    fun missedNote(closed: Pair<Long, Long>, wins: List<Pair<Long, Long>>, tomorrow: List<Pair<Long, Long>>, nowSec: Long, zone: ZoneId): Note {
        val later = wins.filter { it.first > nowSec || it.second > nowSec }
        val bits = ArrayList<String>()
        if (later.isNotEmpty()) bits += "Next option: " + later.first().let { rangeOf(max(it.first, nowSec), it.second, zone) }
        if (tomorrow.isNotEmpty()) bits += (if (later.isEmpty()) "Tomorrow " else "or tomorrow ") + rangeOf(tomorrow.first().first, tomorrow.first().second, zone)
        if (bits.isEmpty()) bits += "No more windows today. Options ›"
        return Note("You missed today's window (${rangeOf(closed.first, closed.second, zone)})", bits.joinToString(" · "))
    }

    /** The window you're in with 45 minutes or less to go (and more than 5), or null. */
    fun leftIn(wins: List<Pair<Long, Long>>, nowSec: Long, leftSec: Long = 45 * 60L): Pair<Long, Long>? =
        wins.firstOrNull { it.first <= nowSec && it.second - nowSec in 300L..leftSec }
}
