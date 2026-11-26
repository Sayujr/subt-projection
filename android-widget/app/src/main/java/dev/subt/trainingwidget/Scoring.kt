package dev.subt.trainingwidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Pixel heuristic for Rainbow's "clouds" map tile (pure, so it is tested on made-up pixels).
 *
 * The tile is a WebP overlay. We don't have its legend, so this is an approximation: where the overlay is
 * translucent, its opacity follows cloud density (transparent = clear sky, near-opaque = thick cloud). If the tile
 * comes back opaque, brightness is used instead (dark = clear, bright = cloudy). The 5 x 5 pixels around the
 * location are averaged and counted as "cloudy" at [CLOUDY_AT] or more. The forecast side says cloudy at 60 % cover;
 * overlays tend to draw thin cloud lighter than its cover, so the cut-off here is a little lower.
 */
object CloudTile {
    const val CLOUDY_AT = 0.5
    const val ZOOM = 7

    /** One pixel (ARGB) → cloud density 0..1. */
    fun fraction(argb: Int): Double {
        val a = (argb ushr 24) and 0xFF
        if (a < 250) return a / 255.0
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
    }

    /** Mean density of the sampled pixels, or null when there are none. */
    fun density(px: IntArray): Double? = if (px.isEmpty()) null else px.map { fraction(it) }.average()

    /** True when the sampled pixels look cloudy, false when clear, null when nothing was sampled. */
    fun cloudy(px: IntArray): Boolean? = density(px)?.let { it >= CLOUDY_AT }

    /** Slippy-map tile (x, y) at [zoom] holding the position, and where in that tile it falls (0..1 across and down). */
    class TileRef(val x: Int, val y: Int, val fx: Double, val fy: Double)

    fun tileFor(lat: Double, lon: Double, zoom: Int = ZOOM): TileRef {
        val n = 2.0.pow(zoom)
        val xf = ((lon + 180.0) / 360.0 * n).coerceIn(0.0, n - 1e-9)
        val la = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        val yf = ((1.0 - ln(tan(la) + 1.0 / cos(la)) / PI) / 2.0 * n).coerceIn(0.0, n - 1e-9)
        val x = floor(xf).toInt(); val y = floor(yf).toInt()
        return TileRef(x, y, xf - x, yf - y)
    }

    /** The snapshot id from the /tiles/v1/snapshot answer: a bare number, or a JSON object / array holding one. */
    fun parseSnapshotId(body: String): String? {
        val t = body.trim().trim('"')
        if (t.isNotEmpty() && t.all { it.isDigit() }) return t
        fun num(v: Any?): String? = when (v) {
            is Number -> v.toLong().toString()
            is String -> v.trim().takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } }
            else -> null
        }
        runCatching {
            val o = JSONObject(t)
            for (k in listOf("snapshot", "snapshotId", "snapshot_id", "id", "timestamp", "time")) num(o.opt(k))?.let { return it }
            val arr = o.optJSONArray("snapshots")
            if (arr != null && arr.length() > 0) num(arr.opt(arr.length() - 1))?.let { return it }
            Unit
        }
        runCatching {
            val a = JSONArray(t)
            if (a.length() > 0) num(a.opt(0))?.let { return it }
            Unit
        }
        return null
    }
}

/**
 * Self-scoring: keeps about 60 days of each source's hourly forecast (cloud % and rain), compares them with what
 * actually happened (Rainbow's cloud tile "now" and your "outside now" taps) and turns each source's hit rate over the
 * last 30 days into a weight for the combined forecast. Pure logic first (tested), then the file and the network.
 */
object Scoring {
    /** The app's own combined forecast is archived as a pseudo-source with this id. */
    const val ALL = "all"
    const val KEEP_DAYS = 60
    const val WINDOW_DAYS = 30
    const val MIN_OBS = 10
    private const val WET_MM = 0.25

    /** [cloud] % (or -1) and [rain] in hundredths of mm/h (or -1) as one source forecast for one hour. */
    data class Call(val cloud: Int, val rain: Int)

    /** What was seen in [hour] (epoch hours): cloudy / wet as 1, 0 or -1 (unknown); [src] is "tile" or "user". */
    data class Obs(val hour: Long, val cloudy: Int, val wet: Int, val src: String, val at: Long)

    class Archive(
        val fc: MutableMap<Long, MutableMap<String, Call>> = HashMap(),
        val obs: MutableList<Obs> = ArrayList(),
    )

    data class Acc(val correct: Int, val total: Int) {
        val pct: Int? get() = if (total == 0) null else (correct * 100.0 / total).roundToInt()
        val rate: Double get() = if (total == 0) 0.0 else correct.toDouble() / total
        operator fun plus(o: Acc) = Acc(correct + o.correct, total + o.total)
    }

    // ---------------------------------------------------------------- pure

    fun hourOf(t: Long): Long = Math.floorDiv(t, 3600L)

    /** Stores the forecast for hours from 2 h ahead to 2 days ahead; hours already closer than 2 h keep the earlier call. */
    fun record(a: Archive, src: Map<String, List<Weather.Hour>>, all: List<Weather.Combined>, nowSec: Long) {
        val lo = nowSec + 2 * 3600
        val hi = nowSec + 48 * 3600
        fun put(id: String, t: Long, cloud: Double?, rain: Double?) {
            if (t < lo || t > hi) return
            if (cloud == null && rain == null) return
            a.fc.getOrPut(hourOf(t)) { HashMap() }[id] = Call(cloud?.roundToInt() ?: -1, rain?.let { (it * 100).roundToInt() } ?: -1)
        }
        for ((id, hours) in src) for (h in hours) put(id, h.t, h.cloud, h.rainMm)
        for (h in all) put(ALL, h.t, h.cloud, h.rainMm)
    }

    fun addObs(a: Archive, o: Obs) {
        a.obs.removeAll { it.hour == o.hour && it.src == o.src }
        a.obs += o
    }

    fun prune(a: Archive, nowSec: Long) {
        val min = hourOf(nowSec) - KEEP_DAYS * 24L
        a.fc.keys.removeAll { it < min }
        a.obs.removeAll { it.hour < min }
    }

    /** Cloudy-or-not calls that matched, for one source, since [sinceHour]. Cloudy means 60 % cover or more. */
    fun cloudAcc(a: Archive, src: String, sinceHour: Long): Acc {
        var ok = 0; var n = 0
        for (o in a.obs) {
            if (o.hour < sinceHour || o.cloudy < 0) continue
            val c = a.fc[o.hour]?.get(src)?.cloud?.takeIf { it >= 0 } ?: continue
            n++
            if ((c >= 60) == (o.cloudy == 1)) ok++
        }
        return Acc(ok, n)
    }

    /** Wet-or-dry calls that matched (wet = more than 0.25 mm/h); only your taps say anything about rain. */
    fun wetAcc(a: Archive, src: String, sinceHour: Long): Acc {
        var ok = 0; var n = 0
        for (o in a.obs) {
            if (o.hour < sinceHour || o.wet < 0) continue
            val r = a.fc[o.hour]?.get(src)?.rain?.takeIf { it >= 0 } ?: continue
            n++
            if ((r > WET_MM * 100) == (o.wet == 1)) ok++
        }
        return Acc(ok, n)
    }

    /** Cloud and wet calls together, for every real source in the archive (the pseudo-source is left out). */
    fun pooled(a: Archive, sinceHour: Long): Map<String, Acc> {
        val ids = a.fc.values.flatMap { it.keys }.toSet() - ALL
        return ids.associateWith { cloudAcc(a, it, sinceHour) + wetAcc(a, it, sinceHour) }
    }

    /**
     * Source weights from their hit rates: rate squared (floor 0.1), scaled to average 1. Equal weights (an empty map)
     * until at least two sources have [minObs] or more observations; sources with fewer get the average weight.
     */
    fun weights(pooled: Map<String, Acc>, minObs: Int = MIN_OBS): Map<String, Double> {
        val scored = pooled.filter { it.key != ALL && it.value.total >= minObs }
        if (scored.size < 2) return emptyMap()
        val raw = HashMap<String, Double>()
        for ((id, acc) in scored) raw[id] = (acc.rate * acc.rate).coerceAtLeast(0.1)
        val mean = raw.values.average()
        for (id in pooled.keys) if (id != ALL && id !in raw) raw[id] = mean
        val scale = raw.values.average()
        return raw.mapValues { it.value / scale }
    }

    /** The source with the best cloud hit rate (at least [minObs] checks), ties to the one with more checks. */
    fun best(a: Archive, sinceHour: Long, minObs: Int = 5): String? =
        Weather.ORDER.map { it to cloudAcc(a, it, sinceHour) }.filter { it.second.total >= minObs }
            .maxWithOrNull(compareBy<Pair<String, Acc>>({ it.second.rate }, { it.second.total }))?.first

    /** "Cloud calls right 82% (last 30 days) · best: NWS", or a progress note until there is enough to say. */
    fun line(a: Archive, nowSec: Long): String? {
        val since = hourOf(nowSec) - WINDOW_DAYS * 24L
        val acc = cloudAcc(a, ALL, since)
        if (acc.total < 3) {
            val n = a.obs.count { it.hour >= since }
            return if (n == 0) null else "Checking the forecast against what happened: $n observation${if (n == 1) "" else "s"} so far"
        }
        val b = best(a, since)?.let { " · best: ${Weather.NAMES[it] ?: it}" } ?: ""
        return "Cloud calls right ${acc.pct}% (last $WINDOW_DAYS days)$b"
    }

    // ---------------------------------------------------------------- JSON

    fun encode(a: Archive): String {
        val fc = JSONObject()
        for ((h, m) in a.fc) {
            val o = JSONObject()
            for ((id, c) in m) o.put(id, JSONArray().put(c.cloud).put(c.rain))
            fc.put(h.toString(), o)
        }
        val obs = JSONArray()
        for (o in a.obs) obs.put(JSONArray().put(o.hour).put(o.cloudy).put(o.wet).put(o.src).put(o.at))
        return JSONObject().put("fc", fc).put("obs", obs).toString()
    }

    fun decode(json: String?): Archive {
        val a = Archive()
        if (json.isNullOrBlank()) return a
        runCatching {
            val o = JSONObject(json)
            val fc = o.optJSONObject("fc")
            if (fc != null) for (k in fc.keys()) {
                val h = k.toLongOrNull() ?: continue
                val m = fc.optJSONObject(k) ?: continue
                val calls = HashMap<String, Call>()
                for (id in m.keys()) { val c = m.optJSONArray(id) ?: continue; calls[id] = Call(c.optInt(0, -1), c.optInt(1, -1)) }
                a.fc[h] = calls
            }
            val obs = o.optJSONArray("obs")
            if (obs != null) for (i in 0 until obs.length()) {
                val r = obs.optJSONArray(i) ?: continue
                a.obs += Obs(r.optLong(0), r.optInt(1, -1), r.optInt(2, -1), r.optString(3, "user"), r.optLong(4))
            }
            Unit
        }
        return a
    }

    // ---------------------------------------------------------------- file

    private class Cached(val path: String, val stamp: Long, val len: Long, val archive: Archive)
    @Volatile private var cached: Cached? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "rw_scoring.json")

    @Synchronized fun load(ctx: Context): Archive {
        val f = file(ctx)
        val c = cached
        if (c != null && c.path == f.absolutePath && c.stamp == f.lastModified() && c.len == f.length()) return c.archive
        val a = if (f.exists()) decode(runCatching { f.readText() }.getOrNull()) else Archive()
        cached = Cached(f.absolutePath, f.lastModified(), f.length(), a)
        return a
    }

    @Synchronized fun save(ctx: Context, a: Archive) {
        val f = file(ctx)
        runCatching {
            val tmp = File(f.path + ".tmp")
            tmp.writeText(encode(a))
            if (!tmp.renameTo(f)) { f.writeText(encode(a)); tmp.delete() }
            cached = Cached(f.absolutePath, f.lastModified(), f.length(), a)
        }
    }

    /** Source weights for the combined forecast (empty = equal weights). */
    fun weights(ctx: Context): Map<String, Double> {
        val a = load(ctx)
        return weights(pooled(a, hourOf(System.currentTimeMillis() / 1000) - WINDOW_DAYS * 24L))
    }

    fun clearForecasts(ctx: Context) {
        val a = load(ctx)
        a.fc.clear()
        save(ctx, a)
    }

    /** Archives what every source (and the combined forecast) says for the next two days. Called after each refresh. */
    fun recordForecasts(ctx: Context, d: RunWindows.WxData) {
        val a = load(ctx)
        val now = System.currentTimeMillis() / 1000
        record(a, d.src, d.all, now)
        prune(a, now)
        save(ctx, a)
    }

    /** Your tap: [kind] is "cloudy", "sunny" or "rain". Rain implies cloud; "cloudy" and "sunny" mean it isn't raining. */
    fun recordUser(ctx: Context, kind: String) {
        val now = System.currentTimeMillis() / 1000
        val cloudy = if (kind == "sunny") 0 else 1
        val wet = if (kind == "rain") 1 else 0
        val a = load(ctx)
        addObs(a, Obs(hourOf(now), cloudy, wet, "user", now))
        save(ctx, a)
    }

    private fun recordTile(ctx: Context, cloudy: Boolean) {
        val now = System.currentTimeMillis() / 1000
        val a = load(ctx)
        addObs(a, Obs(hourOf(now), if (cloudy) 1 else 0, -1, "tile", now))
        save(ctx, a)
    }

    /** "Cloud calls right 82% …" for the day plan. */
    fun accuracyLine(ctx: Context): String? = line(load(ctx), System.currentTimeMillis() / 1000)

    /** Each source's cloud hit rate over the last 30 days (for the sources table). */
    fun cloudAccBySource(ctx: Context): Map<String, Acc> {
        val a = load(ctx)
        val since = hourOf(System.currentTimeMillis() / 1000) - WINDOW_DAYS * 24L
        return Weather.ORDER.associateWith { cloudAcc(a, it, since) }
    }

    /**
     * Once per refresh in daylight (and not within 50 minutes of the last one): fetches Rainbow's clouds tile for "now",
     * reads the pixels at the location and records cloudy or not. Needs a Rainbow key; costs 2 Rainbow calls.
     */
    fun observeTile(ctx: Context, d: RunWindows.WxData?) {
        val key = SecureKey.load(ctx) ?: return
        val s = RwStore.load(ctx)
        val lat = s.lat ?: return; val lon = s.lon ?: return
        val nowSec = System.currentTimeMillis() / 1000
        val zone = ZoneId.systemDefault()
        val sun = d?.suns?.let { Weather.sunFor(it, Weather.dayOf(nowSec, zone)) }
        val daylight = if (sun != null) nowSec in (sun.rise + 1800)..(sun.set - 1800) else Instant.ofEpochSecond(nowSec).atZone(zone).hour in 8..17
        if (!daylight) return
        if (System.currentTimeMillis() - RwStore.getLong(ctx, "rw_tile_last") < 50 * 60_000L) return
        RwStore.putLong(ctx, "rw_tile_last", System.currentTimeMillis())
        val cloudy = WeatherApi.cloudTileCloudy(ctx, key, lat, lon) ?: return
        recordTile(ctx, cloudy)
    }
}
