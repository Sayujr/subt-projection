package dev.subt.trainingwidget

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Your run-window rules (Settings → Run windows). Temperatures in °C, times in minutes after midnight. */
data class RwSettings(
    val uvLimit: Double = 2.0,
    val hotWinter: Double = 15.0,
    val hotSummer: Double = 20.0,
    val cold: Double = 5.0,
    val humid: Double = 16.0,
    val bufferMin: Int = 30,
    /** true = daylight (sunrise − 30 min to sunset + 30 min); false = the custom hours below. */
    val auto: Boolean = true,
    val summerFrom: Int = 5 * 60,
    val summerTo: Int = 21 * 60,
    val winterFrom: Int = 7 * 60,
    val winterTo: Int = 17 * 60 + 30,
    val lat: Double? = null,
    val lon: Double? = null,
    val place: String = "",
)

/**
 * Best-time-to-run logic: forecast parsers (pure: JSON text in, hours out), combining sources, hour colours,
 * window scoring per run type, the plain-language summary and shift detection. No Android calls, so it is all tested.
 */
object Weather {
    enum class RunType(val label: String) { TT("TT"), SUBT("SubT"), LONG("Long run"), EASY("Easy run") }

    /** One source's view of one hour. [t] is epoch seconds at the start of the hour; wind in km/h. */
    data class Hour(
        val t: Long,
        val temp: Double? = null,
        val feels: Double? = null,
        val dew: Double? = null,
        val cloud: Double? = null,
        val uv: Double? = null,
        val rainMm: Double? = null,
        val rainPct: Double? = null,
        val wind: Double? = null,
        val gust: Double? = null,
        val precipType: String? = null,
    )

    /** Rainbow nowcast: rain rate (mm/h) for a stretch of time. */
    data class NowStep(val begin: Long, val end: Long, val rate: Double, val type: String?)

    data class Sun(val date: LocalDate, val rise: Long, val set: Long)

    /** Open-Meteo: one hour list per model id, plus sunrise / sunset per day. */
    class OmData(val models: Map<String, List<Hour>>, val sun: List<Sun>)

    val NAMES = mapOf("rainbow" to "Rainbow", "nws" to "NWS", "nbm" to "NBM", "hrrr" to "HRRR", "ecmwf" to "ECMWF", "gfs" to "GFS")
    val ORDER = listOf("rainbow", "nws", "nbm", "hrrr", "ecmwf", "gfs")
    private val OM_MODELS = mapOf("ecmwf_ifs025" to "ecmwf", "gfs_seamless" to "gfs", "ncep_hrrr_conus" to "hrrr", "ncep_nbm_conus" to "nbm")

    // ================================================================ parsing

    private fun JSONObject.num(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeUnless { it.isNaN() } else null
    private fun JSONArray.num(i: Int): Double? = if (i < length() && !isNull(i)) optDouble(i).takeUnless { it.isNaN() } else null

    fun hourFloor(t: Long): Long = t - Math.floorMod(t, 3600L)

    /** ISO instants (UTC "Z", offsets, or no zone = UTC) or epoch seconds / milliseconds → epoch seconds. */
    /**
     * Coordinates typed or pasted in any common form: "39.95, -75.20", "39.9532° N, 75.1972° W",
     * "39.9532N 75.1972W", "N 39.95 W 75.20", or degrees-minutes-seconds like 39°57'11"N 75°11'50"W.
     * Returns (lat, lon) or null when it isn't a valid position.
     */
    fun parseCoords(raw: String): Pair<Double, Double>? {
        val s = raw.trim().replace('′', '\'').replace('″', '"').replace("''", "\"")
        // Hemisphere letter either before each number ("N 39.95 W 75.20") or after it ("39.95° N")
        val prefix = s.firstOrNull()?.let { it in "NSEWnsew" } == true
        val part = if (prefix) Regex("""([NSEWnsew])\s*(-?\d+(?:\.\d+)?)\s*°?\s*(?:(\d+(?:\.\d+)?)\s*'\s*)?(?:(\d+(?:\.\d+)?)\s*"\s*)?()""")
            else Regex("""()(-?\d+(?:\.\d+)?)\s*°?\s*(?:(\d+(?:\.\d+)?)\s*'\s*)?(?:(\d+(?:\.\d+)?)\s*"\s*)?([NSEWnsew])?""")
        val found = part.findAll(s).filter { it.groupValues[2].isNotEmpty() }.toList()
        if (found.size != 2) return null
        // Anything left besides separators means it isn't just coordinates (e.g. a place name with numbers)
        val rest = part.replace(s, "").replace(Regex("[\\s,;/]"), "")
        if (rest.isNotEmpty()) return null
        fun value(m: MatchResult): Pair<Double, Char?> {
            fun n(i: Int) = m.groupValues[i].replace(',', '.').toDoubleOrNull() ?: 0.0
            val deg = n(2)
            val abs = Math.abs(deg) + n(3) / 60 + n(4) / 3600
            val hemi = (m.groupValues[1] + m.groupValues[5]).uppercase().firstOrNull()
            val neg = deg < 0 || hemi == 'S' || hemi == 'W'
            return (if (neg) -abs else abs) to hemi
        }
        var (a, ha) = value(found[0]); var (b, hb) = value(found[1])
        // Longitude written first (E/W before N/S): swap
        if ((ha == 'E' || ha == 'W') || (hb == 'N' || hb == 'S')) { val t = a; a = b; b = t }
        if (a !in -90.0..90.0 || b !in -180.0..180.0) return null
        return a to b
    }

    fun parseInstant(v: Any?): Long? {
        if (v == null || v == JSONObject.NULL) return null
        if (v is Number) { val x = v.toLong(); return if (x > 100_000_000_000L) x / 1000 else x }
        val s = v.toString().trim()
        if (s.isEmpty()) return null
        s.toLongOrNull()?.let { return if (it > 100_000_000_000L) it / 1000 else it }
        return runCatching { Instant.parse(s).epochSecond }
            .recoverCatching { OffsetDateTime.parse(s).toEpochSecond() }
            .recoverCatching { LocalDateTime.parse(s).toEpochSecond(ZoneOffset.UTC) }
            .getOrNull()
    }

    /** Rainbow's condition word → cloud cover %. Words that say nothing about cloud (Breezy…) give null. */
    fun condCloud(c: String?): Double? {
        val k = c?.lowercase()?.replace("_", "")?.replace(" ", "")?.replace("-", "") ?: return null
        return when (k) {
            "clear", "sunny" -> 5.0
            "mostlyclear", "mostlysunny" -> 25.0
            "partlycloudy" -> 50.0
            "mostlycloudy" -> 75.0
            "cloudy", "overcast", "drizzle", "rain", "lightrain", "heavyrain", "showers", "snow", "sleet", "freezingrain", "hail", "thunderstorm", "thunderstorms" -> 95.0
            else -> null
        }
    }

    fun isFrozen(type: String?): Boolean {
        val t = type?.lowercase() ?: return false
        return t.contains("snow") || t.contains("sleet") || t.contains("ice") || t.contains("hail") || t.contains("freezing")
    }

    private fun findHourly(o: JSONObject): JSONArray? {
        for (k in listOf("timelines", "hourly", "hours", "hourlyForecast", "forecast", "data")) {
            val v = o.opt(k)
            if (v is JSONArray && v.length() > 0 && v.opt(0) is JSONObject) return v
            if (v is JSONObject) findHourly(v)?.let { return it }
        }
        return null
    }

    /** Rainbow hourly forecast. Times are UTC ISO strings; wind is m/s (converted to km/h). */
    fun parseRainbow(json: String): List<Hour> {
        val root = runCatching { JSONObject(json) }.getOrNull()
        val arr: JSONArray = (if (root != null) findHourly(root) else runCatching { JSONArray(json) }.getOrNull()) ?: return emptyList()
        val out = ArrayList<Hour>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val tKey = listOf("startTimestamp", "startTimeIso", "time", "timestamp", "validTime", "startTime", "datetime", "dateTime", "date", "forecastTime").firstOrNull { o.has(it) } ?: continue
            val t = parseInstant(o.opt(tKey)) ?: continue
            val cond = if (o.has("condition") && !o.isNull("condition")) o.optString("condition") else null
            val type = if (o.has("precipitationType") && !o.isNull("precipitationType")) o.optString("precipitationType") else null
            out += Hour(
                t = hourFloor(t),
                temp = o.num("temperature"),
                feels = o.num("feelsLikeTemperature"),
                dew = o.num("temperatureDewPoint"),
                cloud = condCloud(cond),
                uv = o.num("uvIndex"),
                rainMm = o.num("precipitationAmount") ?: o.num("precipitationRate"),
                rainPct = o.num("precipitationChance"),
                wind = o.num("windSpeed")?.let { it * 3.6 },
                gust = o.num("windGust")?.let { it * 3.6 },
                precipType = type,
            )
        }
        return out.sortedBy { it.t }
    }

    /** Rainbow nowcast: `forecast[]` of {precipRate, precipType, timestampBegin, timestampEnd}. */
    fun parseNowcast(json: String): List<NowStep> {
        val a = runCatching { JSONObject(json).optJSONArray("forecast") }.getOrNull() ?: return emptyList()
        val out = ArrayList<NowStep>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val b = parseInstant(o.opt("timestampBegin")) ?: continue
            val e = parseInstant(o.opt("timestampEnd")) ?: (b + 60)
            val type = if (o.has("precipType") && !o.isNull("precipType")) o.optString("precipType").takeIf { it.isNotEmpty() } else null
            out += NowStep(b, max(e, b + 60), o.num("precipRate") ?: 0.0, type)
        }
        return out
    }

    private fun localToEpoch(s: String, off: ZoneOffset): Long =
        runCatching { LocalDateTime.parse(s).toEpochSecond(off) }.getOrDefault(-1L)

    private fun omCode(code: Double?): String? = when (code?.roundToInt()) {
        71, 72, 73, 74, 75, 77, 85, 86 -> "snow"
        56, 57, 66, 67 -> "freezing"
        else -> null
    }

    /** Open-Meteo forecast with several models (variables carry the model name as a suffix). Local times + utc_offset_seconds. */
    fun parseOpenMeteo(json: String): OmData? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val off = ZoneOffset.ofTotalSeconds(o.optInt("utc_offset_seconds", 0))
        val h = o.optJSONObject("hourly") ?: return null
        val times = h.optJSONArray("time") ?: return null
        val ts = LongArray(times.length()) { i -> localToEpoch(times.optString(i), off) }
        val models = linkedMapOf<String, List<Hour>>()
        for ((model, id) in OM_MODELS) {
            fun col(v: String): JSONArray? = h.optJSONArray("${v}_$model")
            val temp = col("temperature_2m")
            val feels = col("apparent_temperature")
            val dew = col("dew_point_2m")
            val cloud = col("cloud_cover")
            val rain = col("precipitation")
            val pct = col("precipitation_probability")
            val uv = col("uv_index")
            val wind = col("wind_speed_10m")
            val gust = col("wind_gusts_10m")
            val code = col("weather_code")
            val list = ArrayList<Hour>()
            for (i in ts.indices) {
                if (ts[i] < 0) continue
                val hr = Hour(
                    t = hourFloor(ts[i]),
                    temp = temp?.num(i), feels = feels?.num(i), dew = dew?.num(i), cloud = cloud?.num(i),
                    uv = uv?.num(i), rainMm = rain?.num(i), rainPct = pct?.num(i),
                    wind = wind?.num(i), gust = gust?.num(i), precipType = omCode(code?.num(i)),
                )
                if (hr.temp == null && hr.cloud == null && hr.rainMm == null) continue
                list += hr
            }
            if (list.any { it.temp != null || it.cloud != null }) models[id] = list
        }
        val suns = ArrayList<Sun>()
        val d = o.optJSONObject("daily")
        if (d != null) {
            val dates = d.optJSONArray("time")
            val riseKey = d.keys().asSequence().firstOrNull { it == "sunrise" || it.startsWith("sunrise_") }
            val setKey = d.keys().asSequence().firstOrNull { it == "sunset" || it.startsWith("sunset_") }
            val rise = riseKey?.let { d.optJSONArray(it) }
            val set = setKey?.let { d.optJSONArray(it) }
            if (dates != null && rise != null && set != null) {
                for (i in 0 until dates.length()) {
                    val date = runCatching { LocalDate.parse(dates.optString(i)) }.getOrNull() ?: continue
                    val r = localToEpoch(rise.optString(i), off); val s = localToEpoch(set.optString(i), off)
                    if (r >= 0 && s >= 0) suns += Sun(date, r, s)
                }
            }
        }
        return OmData(models, suns)
    }

    /** Open-Meteo air quality: hour start → US AQI. */
    fun parseAqi(json: String): Map<Long, Double> {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
        val off = ZoneOffset.ofTotalSeconds(o.optInt("utc_offset_seconds", 0))
        val h = o.optJSONObject("hourly") ?: return emptyMap()
        val times = h.optJSONArray("time") ?: return emptyMap()
        val v = h.optJSONArray("us_aqi") ?: return emptyMap()
        val out = HashMap<Long, Double>()
        for (i in 0 until times.length()) {
            val t = localToEpoch(times.optString(i), off)
            val a = v.num(i)
            if (t >= 0 && a != null) out[hourFloor(t)] = a
        }
        return out
    }

    /** NWS /points response → the forecastGridData URL. */
    fun parseNwsGridUrl(json: String): String? =
        runCatching { JSONObject(json).optJSONObject("properties")?.optString("forecastGridData") }.getOrNull()?.takeIf { it.isNotBlank() }

    /** "PT1H", "PT3H", "P1DT2H", "PT30M" → whole hours (at least 1). */
    fun isoHours(d: String): Int {
        val m = Regex("P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?)?").matchEntire(d.trim()) ?: return 1
        val days = m.groupValues[1].toIntOrNull() ?: 0
        val hours = m.groupValues[2].toIntOrNull() ?: 0
        val mins = m.groupValues[3].toIntOrNull() ?: 0
        return max(1, days * 24 + hours + if (mins >= 30) 1 else 0)
    }

    /** NWS gridpoint data: sky cover %, each value covering "ISO/PT#H" expanded into hours. */
    fun parseNws(json: String): List<Hour> {
        val vals = runCatching { JSONObject(json).optJSONObject("properties")?.optJSONObject("skyCover")?.optJSONArray("values") }.getOrNull()
            ?: return emptyList()
        val out = LinkedHashMap<Long, Hour>()
        for (i in 0 until vals.length()) {
            val o = vals.optJSONObject(i) ?: continue
            val cloud = o.num("value") ?: continue
            val parts = o.optString("validTime").split("/")
            val start = parseInstant(parts.getOrNull(0)) ?: continue
            val n = min(72, isoHours(parts.getOrNull(1) ?: "PT1H"))
            for (k in 0 until n) {
                val t = hourFloor(start) + k * 3600L
                out[t] = Hour(t = t, cloud = cloud)
            }
        }
        return out.values.sortedBy { it.t }
    }

    // ================================================================ combining

    /** One hour with every source merged (see the spec, "Combining sources"). */
    data class Combined(
        val t: Long,
        val temp: Double?,
        val feels: Double?,
        val dew: Double?,
        val cloud: Double?,
        val uv: Double,
        val rainMm: Double,
        val rainPct: Double,
        val wind: Double?,
        val gust: Double?,
        val aqi: Double?,
        val frozen: Boolean,
        /** At or below 0 °C within 12 h of rain. */
        val icy: Boolean = false,
        /** Sources agreeing with the median on cloudy-vs-not / wet-vs-dry (the weaker of the two), out of [total]. */
        val agree: Int = 0,
        val total: Int = 0,
        /** (source id, what it says) for the sources that disagree with the median. */
        val dissent: List<Pair<String, String>> = emptyList(),
    )

    fun median(v: List<Double>): Double? {
        if (v.isEmpty()) return null
        val s = v.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    /**
     * Median where each value carries a weight (a source's learned accuracy). Equal weights give the ordinary median;
     * otherwise the value where the running weight first reaches half the total (the midpoint when it lands exactly on half).
     */
    fun wMedian(v: List<Pair<Double, Double>>): Double? {
        if (v.isEmpty()) return null
        val w0 = v.first().second
        if (v.all { abs(it.second - w0) < 1e-9 }) return median(v.map { it.first })
        val s = v.sortedBy { it.first }
        val total = s.sumOf { it.second }
        var cum = 0.0
        for ((i, p) in s.withIndex()) {
            cum += p.second
            if (abs(cum - total / 2) < 1e-9 && i + 1 < s.size) return (p.first + s[i + 1].first) / 2
            if (cum > total / 2) return p.first
        }
        return s.last().first
    }

    /** Nowcast → per hour: mean of the 10-minute maxima (mm/h) and whether it is frozen. Hours with under 30 min of nowcast are left out. */
    fun nowcastHours(steps: List<NowStep>): Map<Long, Pair<Double, Boolean>> {
        if (steps.isEmpty()) return emptyMap()
        val rate = HashMap<Long, Double>()
        val frozen = HashSet<Long>()
        for (s in steps) {
            val b0 = Math.floorDiv(s.begin, 600L)
            val b1 = Math.floorDiv(max(s.end - 1, s.begin), 600L)
            for (b in b0..b1) {
                rate[b] = max(rate[b] ?: 0.0, s.rate)
                if (s.rate > 0 && isFrozen(s.type)) frozen += b
            }
        }
        val out = HashMap<Long, Pair<Double, Boolean>>()
        val hours = rate.keys.map { Math.floorDiv(it, 6L) }.toSet()
        for (h in hours) {
            val bs = (0 until 6).map { h * 6 + it }.filter { it in rate }
            if (bs.size < 3) continue
            out[h * 3600] = bs.sumOf { rate.getValue(it) } / bs.size to bs.any { it in frozen }
        }
        return out
    }

    /** [src]: source id → its hours. The Rainbow-only pick passes just "rainbow". */
    fun combine(
        src: Map<String, List<Hour>>, nowcast: List<NowStep> = emptyList(), aqi: Map<Long, Double> = emptyMap(),
        weights: Map<String, Double> = emptyMap(),
    ): List<Combined> {
        val byT = src.mapValues { (_, l) -> l.associateBy { hourFloor(it.t) } }
        val times = byT.values.flatMap { it.keys }.toSortedSet()
        val now = nowcastHours(nowcast)
        val out = ArrayList<Combined>()
        for (t in times) {
            val hs = byT.mapNotNull { (id, m) -> m[t]?.let { id to it } }
            if (hs.isEmpty()) continue
            fun med(f: (Hour) -> Double?) = median(hs.mapNotNull { f(it.second) })
            val temp = med { it.temp }
            fun medW(f: (Hour) -> Double?) = wMedian(hs.mapNotNull { (id, h) -> f(h)?.let { it to (weights[id] ?: 1.0) } })
            val cloudMed = medW { it.cloud }
            if (temp == null && cloudMed == null) continue
            val nc = now[t]
            val rainMed = medW { it.rainMm }
            val rain = nc?.first ?: rainMed ?: 0.0
            val pct = hs.mapNotNull { it.second.rainPct }.maxOrNull() ?: 0.0
            val uv = hs.mapNotNull { it.second.uv }.maxOrNull() ?: 0.0
            val frozen = hs.any { isFrozen(it.second.precipType) } || nc?.second == true

            // Agreement with the median
            val dissent = ArrayList<Pair<String, String>>()
            val cVotes = hs.mapNotNull { (id, h) -> h.cloud?.let { id to it } }
            val medCloudy = (cloudMed ?: 0.0) >= 60.0
            val cAgree = cVotes.count { (it.second >= 60.0) == medCloudy }
            for ((id, v) in cVotes) if ((v >= 60.0) != medCloudy) dissent += id to (if (v >= 60.0) "cloudy" else "clear")
            val wVotes = hs.mapNotNull { (id, h) -> h.rainMm?.let { id to it } }
            val medWet = rain > 0.25
            val wAgree = wVotes.count { (it.second > 0.25) == medWet }
            for ((id, v) in wVotes) if ((v > 0.25) != medWet) dissent += id to (if (v > 0.25) "rain" else "dry")
            val cRatio = if (cVotes.isEmpty()) 1.0 else cAgree.toDouble() / cVotes.size
            val wRatio = if (wVotes.isEmpty()) 1.0 else wAgree.toDouble() / wVotes.size
            val useCloud = cRatio <= wRatio
            val agree = if (useCloud) cAgree else wAgree
            val total = if (useCloud) cVotes.size else wVotes.size

            out += Combined(
                t = t, temp = temp, feels = med { it.feels }, dew = med { it.dew }, cloud = cloudMed,
                uv = uv, rainMm = rain, rainPct = pct, wind = med { it.wind }, gust = med { it.gust },
                aqi = aqi[t], frozen = frozen, agree = agree, total = total, dissent = dissent,
            )
        }
        // Ice: at or below 0 °C within 12 h after rain
        return out.mapIndexed { i, h ->
            val t0 = h.temp
            if (t0 != null && t0 <= 0.0 && out.take(i).any { it.t >= h.t - 12 * 3600 && it.rainMm > 0.1 }) h.copy(icy = true) else h
        }
    }

    // ================================================================ hour classification and colour

    enum class Tone(val argb: Int, val label: String) {
        BEST(0xFF7FB59A.toInt(), "best"),
        CHILL(0xFF9AA7B4.toInt(), "chill"),
        LO1(0xFFFBE0B5.toInt(), "lo1"),
        LO2(0xFFF8C07A.toInt(), "lo2"),
        OR(0xFFF59E42.toInt(), "or"),
        ORR(0xFFF7793F.toInt(), "orr"),
        RED(0xFFF05A4A.toInt(), "red"),
        DRED(0xFFD93A2B.toInt(), "dred"),
        DEEP(0xFFA3241C.toInt(), "deep"),
        NIGHT(0xFF2A303D.toInt(), "night"),
    }

    enum class Band { COLD, COOL, WARM, HOT }

    data class HourClass(val band: Band, val eff: Double, val humid: Boolean, val sunny: Boolean, val wet: Boolean, val cloudy: Boolean, val tone: Tone, val night: Boolean)

    /** Feels-like when it is below 5 °C or above 25 °C, the air temperature otherwise. */
    fun effTemp(temp: Double?, feels: Double?): Double? {
        if (feels != null && (feels < 5.0 || feels > 25.0)) return feels
        return temp ?: feels
    }

    fun hotLimit(s: RwSettings, month: Int) = if (month in 4..10) s.hotSummer else s.hotWinter

    fun classify(h: Combined, s: RwSettings, month: Int, night: Boolean): HourClass {
        val eff = effTemp(h.temp, h.feels) ?: 10.0
        val hot = hotLimit(s, month)
        val band = when {
            eff < s.cold -> Band.COLD
            eff >= hot -> Band.HOT
            eff < 14.0 -> Band.COOL
            else -> Band.WARM
        }
        val humid = (h.dew ?: -50.0) >= s.humid
        val cloud = h.cloud ?: 50.0
        val sunny = cloud < 60.0 && h.uv >= 2.0
        val wet = h.rainMm > 0.5
        val tone = if (night) Tone.NIGHT else when (band) {
            Band.HOT -> when { sunny && humid -> Tone.DEEP; sunny -> Tone.DRED; humid && wet -> Tone.RED; humid -> Tone.ORR; else -> Tone.OR }
            Band.WARM -> when { sunny -> Tone.ORR; humid -> Tone.OR; else -> Tone.LO2 }
            Band.COOL -> when { sunny && humid -> Tone.LO2; sunny -> Tone.LO1; else -> Tone.BEST }
            Band.COLD -> if (sunny) Tone.LO1 else Tone.CHILL
        }
        return HourClass(band, eff, humid, sunny, wet, cloud >= 60.0, tone, night)
    }

    fun dangerous(h: Combined) = h.frozen || h.icy || (h.aqi ?: 0.0) > 150.0

    /** Icons for an hour cell, most important first. 💨 only on TT days. */
    fun icons(h: Combined, c: HourClass, type: RunType, s: RwSettings): List<String> {
        val out = ArrayList<String>()
        if (h.frozen || h.icy) out += "🧊"
        if (c.wet) out += "💧"
        if ((h.aqi ?: 0.0) > 100.0) out += "😷"
        if (h.uv > s.uvLimit) out += "☀"
        if (type == RunType.TT && (h.gust ?: 0.0) > 30.0) out += "💨"
        if (c.cloudy) out += "☁"
        return out
    }

    // ================================================================ sun and time helpers

    fun dayOf(t: Long, zone: ZoneId): LocalDate = Instant.ofEpochSecond(t).atZone(zone).toLocalDate()
    fun sunFor(suns: List<Sun>, date: LocalDate): Sun? = suns.firstOrNull { it.date == date }
    fun atMinutes(date: LocalDate, minutes: Int, zone: ZoneId): Long =
        date.atStartOfDay().plusMinutes(minutes.toLong()).atZone(zone).toEpochSecond()

    /** Before sunrise or after sunset (judged at the middle of the hour); without sun times, before 06:00 or from 20:00. */
    fun isNight(t: Long, sun: Sun?, zone: ZoneId): Boolean {
        val mid = t + 1800
        if (sun != null) return mid < sun.rise || mid > sun.set
        val hr = Instant.ofEpochSecond(t).atZone(zone).hour
        return hr < 6 || hr >= 20
    }

    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    fun hhmm(t: Long, zone: ZoneId): String = HHMM.format(Instant.ofEpochSecond(t).atZone(zone))
    fun range(w: Window, zone: ZoneId) = "${hhmm(w.start, zone)}–${hhmm(w.end, zone)}"

    /** Running hours for a day: daylight ± 30 min, or your custom summer / winter hours. */
    /** Winter for the evening rule: November to March. */
    fun winter(date: LocalDate) = date.monthValue >= 11 || date.monthValue <= 3
    /** Summer for the early-morning rule (UV season): May to September. */
    fun uvSummer(date: LocalDate) = date.monthValue in 5..9

    /** The latest a run may finish at night when nothing else fits. */
    fun nightEnd(date: LocalDate, zone: ZoneId): Long = atMinutes(date, if (winter(date)) 20 * 60 else 22 * 60, zone)

    /**
     * Auto hours: daylight ± 30 min, plus
     * - winter evenings: a run may start as late as 18:30 although it's dark (finishing by 20:00);
     * - summer mornings: it may start from 05:00 before sunrise, to keep out of the UV.
     * [lenSec] is the run plus buffer, so the 18:30 latest start becomes an end limit.
     */
    fun allowedRange(date: LocalDate, sun: Sun?, s: RwSettings, zone: ZoneId, lenSec: Long = 0): Pair<Long, Long> {
        if (s.auto) {
            var lo = sun?.let { it.rise - 1800 } ?: atMinutes(date, 6 * 60 + 30, zone)
            var hi = sun?.let { it.set + 1800 } ?: atMinutes(date, 19 * 60 + 30, zone)
            if (winter(date)) hi = max(hi, min(atMinutes(date, 18 * 60 + 30, zone) + lenSec, atMinutes(date, 20 * 60, zone)))
            if (uvSummer(date)) lo = min(lo, atMinutes(date, 5 * 60, zone))
            return lo to hi
        }
        val summer = date.monthValue in 4..10
        val from = if (summer) s.summerFrom else s.winterFrom
        val to = if (summer) s.summerTo else s.winterTo
        return atMinutes(date, from, zone) to atMinutes(date, to, zone)
    }

    /** What the strip covers: allowed hours ± 2 h, or 05:00–21:00 when daylight isn't known. */
    fun stripRange(date: LocalDate, sun: Sun?, s: RwSettings, zone: ZoneId): Pair<Long, Long> {
        val day0 = atMinutes(date, 0, zone)
        val dayEnd = atMinutes(date, 24 * 60, zone)
        if (s.auto && sun == null) return atMinutes(date, 5 * 60, zone) to atMinutes(date, 21 * 60, zone)
        val (a, b) = allowedRange(date, sun, s, zone)
        val lo = max(day0, hourFloor(a - 2 * 3600))
        val hi = min(dayEnd, ceilTo(b + 2 * 3600, 3600L))
        return if (hi - lo >= 6 * 3600) lo to hi else atMinutes(date, 5 * 60, zone) to atMinutes(date, 21 * 60, zone)
    }

    // ================================================================ the planned run

    data class PlannedRun(val name: String, val minutes: Int, val type: RunType, val planned: Boolean, val done: Boolean = false)

    fun typeOf(w: Workout): RunType = when {
        w.status == WorkoutStatus.RACE || Streaks.isTtName(w.name) -> RunType.TT
        SubtWorkouts.isSubtWorkout(w.name) -> RunType.SUBT
        Zones.isLong(w.name, w.date, w.seconds) -> RunType.LONG
        else -> RunType.EASY
    }

    /** The planned (not done, not a race) run for [date]: the one [runOf] would use, with its event id for moving it. */
    fun plannedWorkout(week: List<Workout>, date: String): Workout? =
        week.filter { it.date == date && it.type.contains("Run", ignoreCase = true) && it.status == WorkoutStatus.PLANNED }.maxByOrNull { it.seconds }

    /** The day's run from the intervals.icu week: its planned duration, else distance at 5:30/km, else 60 min. */
    fun runOf(week: List<Workout>, date: String): PlannedRun {
        val runs = week.filter { it.date == date && it.type.contains("Run", ignoreCase = true) }
        val w = runs.filter { it.status != WorkoutStatus.DONE }.maxByOrNull { it.seconds }
            ?: runs.maxByOrNull { it.seconds }
            ?: return PlannedRun("No run planned", 60, RunType.EASY, planned = false)
        val minutes = when {
            w.seconds > 0 -> (w.seconds / 60.0).roundToInt().coerceAtLeast(10)
            w.distanceM > 0 -> (w.distanceM / 1000.0 * 5.5).roundToInt().coerceAtLeast(10)
            else -> 60
        }
        // Any run synced as done that day counts: the planned session is complete
        return PlannedRun(w.name, minutes, typeOf(w), planned = true, done = runs.any { it.status == WorkoutStatus.DONE })
    }

    // ================================================================ scoring

    /** Penalty for one hour for one run type (lower is better). */
    fun penalty(h: Combined, type: RunType, s: RwSettings, month: Int): Double {
        val eff = effTemp(h.temp, h.feels) ?: 10.0
        val dew = h.dew ?: 8.0
        val cloud = h.cloud ?: 50.0
        val lim = s.uvLimit
        val uv = h.uv
        var p = 0.0
        p += when (type) {
            RunType.TT -> if (uv > lim) (uv - lim) * 1.0 + 0.5 else uv * 0.1
            RunType.SUBT, RunType.EASY -> if (uv > lim) (uv - lim) * 4.0 + 2.0 else uv * 0.5
            RunType.LONG -> if (uv > lim - 1.0) (uv - (lim - 1.0)) * 5.0 + 2.0 else uv * 0.5
        }
        when (type) {
            RunType.TT -> {
                // coolest hours that stay at or above the cold setting, then dew point, wind and gusts
                p += if (eff < s.cold) (s.cold - eff) * 3.0 + 6.0 else (eff - s.cold) * 1.2
                p += dew * 0.4
                p += max(0.0, (h.wind ?: 0.0) - 20.0) * 0.15 + max(0.0, (h.gust ?: 0.0) - 30.0) * 0.15
            }
            RunType.SUBT -> {
                if (eff > 10.0) p += (eff - 10.0) * 2.0
                if (eff < s.cold) p += (s.cold - eff) * 1.0
                if (dew > 6.0) p += (dew - 6.0) * 0.9
                p += max(0.0, (h.gust ?: 0.0) - 40.0) * 0.05
            }
            RunType.LONG -> {
                // heat is welcome (heat training); cold is not
                if (eff < s.cold) p += (s.cold - eff) * 3.0 + 4.0
                p += (100.0 - cloud) * 0.015
            }
            RunType.EASY -> {
                if (eff < s.cold) p += (s.cold - eff) * 1.0
                p += (100.0 - cloud) * 0.01
            }
        }
        // Rain: light is free, 0.25–0.5 mild, over 0.5 strong
        p += when {
            h.rainMm > 0.5 -> 8.0 + (h.rainMm - 0.5) * 4.0
            h.rainMm > 0.25 -> 3.0
            h.rainPct > 20.0 -> (h.rainPct - 20.0) / 80.0
            else -> 0.0
        }
        if ((h.aqi ?: 0.0) > 100.0) p += ((h.aqi ?: 0.0) - 100.0) * 0.05
        if (dangerous(h)) p += 40.0
        return p
    }

    data class Window(
        val start: Long,
        val end: Long,
        /** Mean of the hour penalties plus half the worst hour (lower is better). */
        val score: Double,
        val night: Boolean,
        val dangerous: Boolean,
        val narrow: Boolean,
        val wet: Boolean,
        val frozen: Boolean,
        val agree: Int,
        val total: Int,
        val dissent: List<Pair<String, String>>,
        val minTemp: Double?,
        val maxTemp: Double?,
        val minFeels: Double?,
        val maxDew: Double?,
        val avgCloud: Double?,
        val maxUv: Double,
        val maxRain: Double,
        val maxRainPct: Double,
        val maxGust: Double?,
        val maxAqi: Double?,
        val icons: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
    )

    class Pick(val best: Window?, val risky: Boolean, val candidates: List<Window>)

    private fun build(st: Long, en: Long, byT: Map<Long, Combined>, pens: Map<Long, Double>, night: Boolean): Window? {
        var sum = 0.0; var sumW = 0.0; var worst = 0.0; var covered = 0L
        val used = ArrayList<Combined>()
        var t = hourFloor(st)
        while (t < en) {
            val ov = min(en, t + 3600) - max(st, t)
            val h = byT[t]
            val p = pens[t]
            if (h != null && p != null && ov > 0) {
                sum += p * ov; sumW += ov; covered += ov
                if (ov >= 900) { worst = max(worst, p); used += h }
            }
            t += 3600
        }
        if (sumW <= 0.0 || covered * 2 < (en - st) || used.isEmpty()) return null
        val weakest = used.filter { it.total > 0 }.minByOrNull { it.agree.toDouble() / it.total }
        return Window(
            start = st, end = en, score = sum / sumW + worst * 0.5, night = night,
            dangerous = used.any { dangerous(it) }, narrow = false,
            wet = used.any { it.rainMm > 0.5 }, frozen = used.any { it.frozen || it.icy },
            agree = weakest?.agree ?: 0, total = weakest?.total ?: 0,
            dissent = used.flatMap { it.dissent }.distinct(),
            minTemp = used.mapNotNull { it.temp }.minOrNull(), maxTemp = used.mapNotNull { it.temp }.maxOrNull(),
            minFeels = used.mapNotNull { it.feels ?: it.temp }.minOrNull(),
            maxDew = used.mapNotNull { it.dew }.maxOrNull(),
            avgCloud = used.mapNotNull { it.cloud }.takeIf { it.isNotEmpty() }?.average(),
            maxUv = used.maxOf { it.uv }, maxRain = used.maxOf { it.rainMm }, maxRainPct = used.maxOf { it.rainPct },
            maxGust = used.mapNotNull { it.gust }.maxOrNull(), maxAqi = used.mapNotNull { it.aqi }.maxOrNull(),
        )
    }

    private fun ceilTo(t: Long, step: Long) = Math.floorDiv(t + step - 1, step) * step

    /**
     * The best window for [run] on [date] from [hours] (already for that day). Candidates start every 30 minutes
     * inside the allowed hours; at night only if none fits in daylight. With [nowSec], windows that have already started are skipped.
     */
    fun pick(hours: List<Combined>, date: LocalDate, run: PlannedRun, suns: List<Sun>, s: RwSettings, nowSec: Long?, zone: ZoneId): Pick {
        val sun = sunFor(suns, date)
        val month = date.monthValue
        val byT = hours.associateBy { it.t }
        val pens = hours.associate { it.t to penalty(it, run.type, s, month) }
        val lenSec = (run.minutes + s.bufferMin) * 60L
        fun gen(a: Long, b: Long, night: Boolean): List<Window> {
            val out = ArrayList<Window>()
            var st = ceilTo(a, 1800L)
            while (st + lenSec <= b) {
                if (nowSec == null || st >= nowSec) build(st, st + lenSec, byT, pens, night)?.let { out += it }
                st += 1800
            }
            return out
        }
        val (lo, hi) = allowedRange(date, sun, s, zone, lenSec)
        var cands = gen(lo, hi, false)
        if (cands.isEmpty()) cands = gen(atMinutes(date, 5 * 60, zone), nightEnd(date, zone), true)
        val best = cands.minByOrNull { it.score } ?: return Pick(null, false, emptyList())
        val risky = cands.all { it.dangerous }
        val tol = 1.5
        val neighbours = cands.filter { abs(it.start - best.start) == 1800L }
        val narrow = !risky && !best.wet && neighbours.none { it.score <= best.score + tol && !it.dangerous && !it.wet }
        return Pick(decorate(best.copy(narrow = narrow), run, s, risky), risky, cands)
    }

    /** Icons and warning lines for the chosen window. */
    private fun decorate(w: Window, run: PlannedRun, s: RwSettings, risky: Boolean): Window {
        val icons = ArrayList<String>()
        val warn = ArrayList<String>()
        if (risky) { icons += "⛔"; warn += "⛔ Even the best window is risky: gym recommended." }
        if (w.narrow) { icons += "⚠"; warn += "⚠ Narrow: the good stretch barely fits your run." }
        if (w.night) { icons += "🌙"; warn += "🌙 Night run: take lights." }
        if (w.frozen) { icons += "🧊"; warn += "🧊 Ice or snow around: take care, or use the gym." }
        if (w.wet) { icons += "💧"; warn += "💧 Rain in the window (up to ${fmt1(w.maxRain)} mm/h): gym or outside." }
        if ((w.maxAqi ?: 0.0) > 100.0) { icons += "😷"; warn += "😷 Air quality up to ${(w.maxAqi ?: 0.0).roundToInt()} (US AQI): consider easing off." }
        if (w.maxUv > s.uvLimit) { icons += "🧢"; warn += "🧢 UV up to ${fmt1(w.maxUv)}, above your limit of ${fmt1(s.uvLimit)}: cap and sunscreen." }
        if ((w.minFeels ?: 99.0) < s.cold) { icons += "🧤"; warn += "🧤 Feels like ${(w.minFeels ?: 0.0).roundToInt()} °C at the coldest: gloves." }
        if (run.type == RunType.TT && (w.maxGust ?: 0.0) > 30.0) { icons += "💨"; warn += "💨 Gusts to ${(w.maxGust ?: 0.0).roundToInt()} km/h." }
        return w.copy(icons = icons, warnings = warn)
    }

    private fun fmt1(v: Double) = if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else String.format(java.util.Locale.US, "%.1f", v)

    fun overlap(a: Window, b: Window): Double {
        val ov = max(0L, min(a.end, b.end) - max(a.start, b.start)).toDouble()
        return ov / max(1L, min(a.end - a.start, b.end - b.start)).toDouble()
    }

    // ================================================================ the day plan and its summary

    data class Summary(
        val shape: String,
        val pick: String,
        val rainbow: String?,
        val agree: String?,
        val suggestion: String,
        val swap: String?,
        val warnings: List<String>,
        /** One line for cards and the Week list. */
        val short: String,
    ) {
        fun lines(): List<String> = listOfNotNull(shape, pick, rainbow, agree, listOfNotNull(suggestion, swap).joinToString(" "), warnings.takeIf { it.isNotEmpty() }?.joinToString("\n")).filter { it.isNotBlank() }
    }

    class DayPlan(
        val date: LocalDate,
        val run: PlannedRun,
        val window: Window?,
        /** Rainbow's own pick (null when Rainbow isn't connected). */
        val rainbow: Window?,
        val rainbowPresent: Boolean,
        /** Both picks are the same, or overlap by 80 % or more. */
        val same: Boolean,
        val risky: Boolean,
        /** Today's best window has already started or passed (shown for reference). */
        val passed: Boolean,
        val hours: List<Combined>,
        val rbHours: List<Combined>,
        val sun: Sun?,
        val lo: Long,
        val hi: Long,
        val summary: Summary,
        val noData: Boolean,
        /** Two shorter good windows, when no single window is acceptable (null otherwise). */
        val split: Split? = null,
    ) {
        fun withSwap(text: String?) = DayPlan(date, run, window, rainbow, rainbowPresent, same, risky, passed, hours, rbHours, sun, lo, hi, summary.copy(swap = text), noData, split)
    }

    fun plan(
        date: LocalDate, all: List<Combined>, rb: List<Combined>, suns: List<Sun>, run: PlannedRun,
        s: RwSettings, nowSec: Long?, zone: ZoneId,
    ): DayPlan {
        val dh = all.filter { dayOf(it.t, zone) == date }
        val rh = rb.filter { dayOf(it.t, zone) == date }
        val sun = sunFor(suns, date)
        val (lo, hi) = stripRange(date, sun, s, zone)
        if (dh.isEmpty()) {
            val sm = Summary("No forecast for this day yet.", "", null, null, "Tap Refresh now, or check your location and connection.", null, emptyList(), "No forecast")
            return DayPlan(date, run, null, null, rh.isNotEmpty(), false, false, false, emptyList(), emptyList(), sun, lo, hi, sm, noData = true)
        }
        var pk = pick(dh, date, run, suns, s, nowSec, zone)
        var passed = false
        if (pk.best == null && nowSec != null) {
            pk = pick(dh, date, run, suns, s, null, zone)
            passed = pk.best != null
        }
        val rpk = if (rh.isEmpty()) null else pick(rh, date, run, suns, s, if (passed) null else nowSec, zone)
        val best = pk.best
        val rbBest = rpk?.best
        val same = best != null && rbBest != null && overlap(best, rbBest) >= 0.8
        var sm = summarize(date, dh, rh, run, best, rbBest, same, rh.isNotEmpty(), pk.risky, passed, sun, s, zone)
        val split = if (passed) null else findSplit(dh, date, run, suns, s, nowSec, zone)
        if (split != null) {
            val label = splitLabel(split, zone)
            sm = sm.copy(suggestion = "$label: no single ${run.minutes + s.bufferMin}-minute window is good, but two shorter ones are.", short = label)
        }
        return DayPlan(date, run, best, rbBest, rh.isNotEmpty(), same, pk.risky, passed, dh, rh, sun, lo, hi, sm, noData = false, split = split)
    }

    private fun tempWord(v: Double, s: RwSettings, month: Int): String = when {
        v < s.cold -> "Cold"
        v < 14.0 -> "Cool"
        v < hotLimit(s, month) -> "Warm"
        else -> "Hot"
    }

    private fun skyWord(cloud: Double?): String = when {
        cloud == null -> "unsettled"
        cloud < 30 -> "clear"
        cloud < 60 -> "partly cloudy"
        else -> "cloudy"
    }

    private fun dissentText(dissent: List<Pair<String, String>>): String? {
        if (dissent.isEmpty()) return null
        val top = dissent.groupBy { it.first }.entries.sortedByDescending { it.value.size }.first()
        val claim = when (top.value.first().second) {
            "cloudy" -> "more cloud"
            "clear" -> "clear skies"
            "rain" -> "rain"
            else -> "no rain"
        }
        return "${NAMES[top.key] ?: top.key} dissents (expects $claim)"
    }

    private fun summarize(
        date: LocalDate, dh: List<Combined>, rh: List<Combined>, run: PlannedRun, best: Window?, rbBest: Window?,
        same: Boolean, rbPresent: Boolean, risky: Boolean, passed: Boolean, sun: Sun?, s: RwSettings, zone: ZoneId,
    ): Summary {
        val month = date.monthValue
        // The day's shape
        val day = dh.filter { !isNight(it.t, sun, zone) }.ifEmpty { dh }
        val temps = day.mapNotNull { it.temp }
        val shape = buildString {
            if (temps.isEmpty()) append("Forecast incomplete")
            else {
                append(tempWord(day.mapNotNull { effTemp(it.temp, it.feels) }.average(), s, month)).append(" and ")
                append(skyWord(day.mapNotNull { it.cloud }.takeIf { it.isNotEmpty() }?.average()))
                append(", ${temps.min().roundToInt()}–${temps.max().roundToInt()} °C")
            }
            val wet = day.filter { it.rainMm > 0.25 }
            if (wet.isEmpty()) append(", dry all day")
            else {
                val groups = ArrayList<MutableList<Combined>>()
                for (h in wet) { val g = groups.lastOrNull(); if (g != null && h.t - g.last().t <= 3600) g += h else groups += mutableListOf(h) }
                if (groups.size == 1) append(", rain ${hhmm(groups[0].first().t, zone)}–${hhmm(groups[0].last().t + 3600, zone)}") else append(", showers on and off")
            }
            val peak = day.maxByOrNull { it.uv }
            if (peak != null && peak.uv >= 3.0) append(", UV up to ${peak.uv.roundToInt()} around ${hhmm(peak.t, zone)}")
            append(".")
        }
        // The pick
        val pick = when {
            best == null -> "No ${run.minutes + s.bufferMin}-minute window fits your running hours."
            else -> {
                val reason = when (run.type) {
                    RunType.TT -> "the coolest stretch that stays above ${s.cold.roundToInt()} °C (${(best.minTemp ?: 0.0).roundToInt()}–${(best.maxTemp ?: 0.0).roundToInt()} °C)"
                    RunType.SUBT -> "the coolest, driest air (${(best.minTemp ?: 0.0).roundToInt()}–${(best.maxTemp ?: 0.0).roundToInt()} °C, dew point up to ${(best.maxDew ?: 0.0).roundToInt()})"
                    RunType.LONG -> "UV stays at ${fmt1(best.maxUv)} and it feels no colder than ${(best.minFeels ?: 0.0).roundToInt()} °C"
                    RunType.EASY -> "the lowest UV and the most cloud cover (UV up to ${fmt1(best.maxUv)})"
                }
                val lead = if (passed) "Today's best window has passed (it was ${range(best, zone)})" else "Best for your ${run.type.label} (${run.minutes} min + ${s.bufferMin} min buffer): ${range(best, zone)}"
                lead + (if (best.icons.isNotEmpty()) " " + best.icons.joinToString(" ") else "") + ". Why: $reason."
            }
        }
        // Rainbow's own pick, when it differs
        var rainbow: String? = null
        if (rbPresent && !same && rbBest != null && best != null) {
            val over = rh.filter { it.t < best.end && it.t + 3600 > best.start }
            val rbRain = over.maxOfOrNull { it.rainMm } ?: 0.0
            val rbCloud = over.mapNotNull { it.cloud }.takeIf { it.isNotEmpty() }?.average()
            val why = when {
                rbRain > 0.5 -> "Rainbow expects rain around ${range(best, zone)}"
                rbCloud != null && best.avgCloud != null && abs(rbCloud - (best.avgCloud ?: 0.0)) >= 25 ->
                    "Rainbow sees ${range(best, zone)} as ${if (rbCloud > (best.avgCloud ?: 0.0)) "cloudier" else "clearer"} than the other sources"
                else -> "Rainbow only looks at its own forecast, so it weighs temperature and rain a little differently"
            }
            rainbow = "Rainbow alone would pick ${range(rbBest, zone)}. $why."
        }
        // Agreement
        val agree = when {
            !rbPresent -> "Rainbow isn't connected: add your key in Settings → Run windows."
            best == null -> null
            same -> "✓ Sources agree (Rainbow's pick matches)" + (if (best.total >= 2) ": ${best.agree}/${best.total} agree" else "") + "."
            best.total >= 2 -> "${best.agree}/${best.total} sources agree" + (dissentText(best.dissent)?.let { "; $it" } ?: "") + "."
            else -> null
        }
        // One suggestion
        val suggestion = when {
            risky -> "Gym recommended: ice, snow or poor air make every window risky."
            best == null -> "Nothing fits: use the gym or shorten the run."
            passed -> "Today's window has passed."
            best.wet -> "Gym or outside: rain is likely in the best window. If you go out, start at ${hhmm(best.start, zone)}."
            best.narrow -> "Go at ${hhmm(best.start, zone)} sharp: the good stretch is short."
            else -> "Go at ${hhmm(best.start, zone)}."
        }
        // One line for cards
        val short = if (best == null) "No window" else {
            val mid = ((best.minTemp ?: 10.0) + (best.maxTemp ?: 10.0)) / 2
            tempWord(mid, s, month) + ", " + skyWord(best.avgCloud) +
                " · ${(best.minTemp ?: 0.0).roundToInt()}–${(best.maxTemp ?: 0.0).roundToInt()}°" + if (best.wet) " · rain" else " · dry"
        }
        return Summary(shape, pick, rainbow, agree, suggestion, null, best?.warnings.orEmpty(), short)
    }

    /** Text-only "swap with day Y": when a day's window is poor and another day in the list is clearly better. */
    fun swapHints(plans: List<DayPlan>, zone: ZoneId): Map<LocalDate, String> {
        val out = HashMap<LocalDate, String>()
        for (p in plans) {
            val w = p.window ?: continue
            if (!p.run.planned || p.run.done || p.passed) continue
            if (!(p.risky || w.wet || w.score >= 10.0)) continue
            val q = plans.filter { it.date != p.date && it.date.isAfter(p.date.minusDays(1)) && !it.risky }
                .mapNotNull { o -> o.window?.takeIf { !it.wet && it.score + 5.0 <= w.score }?.let { o to it } }
                .minByOrNull { it.second.score } ?: continue
            val day = q.first.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
            out[p.date] = "Or swap with $day, which has a much better window (${range(q.second, zone)}). This is only a suggestion: nothing is changed."
        }
        return out
    }

    // ================================================================ split runs

    const val SPLIT_MIN = 25
    const val SPLIT_BUFFER = 15

    /** [minA] minutes in [first], [minB] minutes in [second] (first starts earlier). */
    data class Split(val first: Window, val second: Window, val minA: Int, val minB: Int)

    fun splitLabel(sp: Split, zone: ZoneId) = "Split: ${sp.minA}′ at ${hhmm(sp.first.start, zone)} + ${sp.minB}′ at ${hhmm(sp.second.start, zone)}"

    /** A window that needs no compromise: dry, safe and within the UV limit. */
    fun acceptable(w: Window, s: RwSettings) = !w.dangerous && !w.wet && !w.frozen && w.maxUv <= s.uvLimit

    /**
     * When no single window of the run's length (plus buffer) is acceptable (wet over 0.5 mm/h, dangerous or UV over
     * the limit) but two shorter acceptable windows exist that together cover the planned duration (each at least
     * 25 min of running plus a 15 min buffer, at least an hour apart), returns the best such pair. Daylight only.
     */
    fun findSplit(hours: List<Combined>, date: LocalDate, run: PlannedRun, suns: List<Sun>, s: RwSettings, nowSec: Long?, zone: ZoneId): Split? {
        if (!run.planned || run.done || run.minutes < 2 * SPLIT_MIN || hours.isEmpty()) return null
        val sun = sunFor(suns, date)
        val byT = hours.associateBy { it.t }
        val pens = hours.associate { it.t to penalty(it, run.type, s, date.monthValue) }
        val (lo, hi) = allowedRange(date, sun, s, zone, 40 * 60L)
        fun gen(minutes: Int): List<Window> {
            val lenSec = minutes * 60L
            val out = ArrayList<Window>()
            var st = ceilTo(lo, 1800L)
            while (st + lenSec <= hi) {
                if (nowSec == null || st >= nowSec) build(st, st + lenSec, byT, pens, false)?.let { out += it }
                st += 1800
            }
            return out
        }
        if (gen(run.minutes + s.bufferMin).any { acceptable(it, s) }) return null
        fun r5(v: Double) = (Math.round(v / 5.0) * 5).toInt()
        val m = run.minutes
        val splits = listOf(0.5, 0.6, 0.4).map { f -> r5(m * f).coerceIn(SPLIT_MIN, m - SPLIT_MIN) }.distinct()
        var best: Split? = null
        var bestScore = Double.MAX_VALUE
        for (a in splits) {
            val b = m - a
            val wa = gen(a + SPLIT_BUFFER).filter { acceptable(it, s) }
            val wb = gen(b + SPLIT_BUFFER).filter { acceptable(it, s) }
            for (x in wa) for (y in wb) {
                val xFirst = x.end + 3600 <= y.start
                val yFirst = y.end + 3600 <= x.start
                if (!xFirst && !yFirst) continue
                val sc = x.score + y.score
                if (sc < bestScore) {
                    bestScore = sc
                    best = if (xFirst) Split(x, y, a, b) else Split(y, x, b, a)
                }
            }
        }
        return best
    }

    // ================================================================ swap with another day

    /** Move [a] to [aTo]; when [b] is set (the other day's run) it moves to [bTo], i.e. the two swap places. */
    data class SwapPlan(val a: Workout, val aTo: LocalDate, val b: Workout?, val bTo: LocalDate?)

    private fun dayLabel(d: LocalDate) = d.format(DateTimeFormatter.ofPattern("EEE d", java.util.Locale.getDefault()))

    fun swapText(p: SwapPlan): String {
        val from = LocalDate.parse(p.a.date)
        val head = "Move '${p.a.name}' from ${dayLabel(from)} to ${dayLabel(p.aTo)}"
        val b = p.b
        val bTo = p.bTo
        return if (b == null || bTo == null) "$head?" else "$head and '${b.name}' from ${dayLabel(LocalDate.parse(b.date))} to ${dayLabel(bTo)}?"
    }

    /**
     * Today's or tomorrow's planned run day is bad (⛔, or the best window is wet) and another day in the next 6 days,
     * with a planned run or none, has a clearly better window (score at least 5 lower, dry, not risky): returns what would
     * move. Days whose sessions have no intervals.icu event id are skipped, so a button never offers something it can't do.
     * [plans] start at [today]; [week] is the planned sessions (this week and the days after).
     */
    fun swapSuggestions(plans: List<DayPlan>, week: List<Workout>, today: LocalDate): Map<LocalDate, SwapPlan> {
        val out = HashMap<LocalDate, SwapPlan>()
        for (p in plans) {
            val offset = java.time.temporal.ChronoUnit.DAYS.between(today, p.date)
            if (offset !in 0..1) continue
            val w = p.window ?: continue
            if (!p.run.planned || p.run.done || p.passed || p.noData) continue
            if (!(p.risky || w.wet)) continue
            val mine = plannedWorkout(week, p.date.toString()) ?: continue
            if (mine.eventId == null) continue
            var bestPlan: SwapPlan? = null
            var bestScore = Double.MAX_VALUE
            for (q in plans) {
                val qo = java.time.temporal.ChronoUnit.DAYS.between(today, q.date)
                if (q.date == p.date || qo !in 0..6 || q.noData || q.passed || q.risky) continue
                val qw = q.window ?: continue
                if (qw.wet || qw.dangerous || qw.score + 5.0 > w.score) continue
                val other = if (q.run.planned) plannedWorkout(week, q.date.toString()) else null
                if (q.run.planned && (other == null || other.eventId == null)) continue   // a race or a session we can't move
                val sc = qw.score + if (other != null) 2.0 else 0.0
                if (sc < bestScore) { bestScore = sc; bestPlan = SwapPlan(mine, q.date, other, if (other != null) p.date else null) }
            }
            bestPlan?.let { out[p.date] = it }
        }
        return out
    }

    // ================================================================ shift detection

    /** What a window looked like when last checked: its times and which warnings applied. */
    data class Sig(val start: Long, val end: Long, val warns: Set<String>) {
        fun encode() = "$start|$end|${warns.sorted().joinToString(",")}"
        companion object {
            fun decode(s: String?): Sig? {
                val p = s?.split("|") ?: return null
                if (p.size < 3) return null
                return Sig(p[0].toLongOrNull() ?: return null, p[1].toLongOrNull() ?: return null, p[2].split(",").filter { it.isNotEmpty() }.toSet())
            }
        }
    }

    fun sigOf(p: DayPlan): Sig? {
        val w = p.window ?: return null
        val k = mutableSetOf<String>()
        if (w.wet) k += "rain"
        if (p.risky) k += "risky"
        if (w.narrow) k += "narrow"
        return Sig(w.start, w.end, k)
    }

    /** The notification text when the best window moved 30 minutes or more, or its warnings changed; null when nothing material changed. */
    fun shiftMessage(old: Sig?, new: Sig?, zone: ZoneId, any: Boolean = false): String? {
        if (old == null) return null
        if (new == null) return "Run window shifted: no window fits any more (was ${hhmm(old.start, zone)}–${hhmm(old.end, zone)})"
        val moved = if (any) (new.start != old.start || new.end != old.end) else abs(new.start - old.start) >= 1800
        if (!moved && new.warns == old.warns) return null
        val parts = ArrayList<String>()
        val added = new.warns - old.warns
        val removed = old.warns - new.warns
        if ("rain" in added) parts += "rain now expected"
        if ("rain" in removed) parts += "rain has cleared"
        if ("risky" in added) parts += "conditions now risky"
        if ("risky" in removed) parts += "risk has eased"
        if ("narrow" in added) parts += "window has narrowed"
        if ("narrow" in removed) parts += "window has widened"
        if (parts.isEmpty()) parts += "forecast changed"
        return "Run window shifted: ${parts.joinToString(", ")} → best is ${hhmm(new.start, zone)}–${hhmm(new.end, zone)} (was ${hhmm(old.start, zone)}–${hhmm(old.end, zone)})"
    }

    /** No checks from 22:00 until 05:00. */
    fun quiet(hour: Int) = hour >= 22 || hour < 5
}
