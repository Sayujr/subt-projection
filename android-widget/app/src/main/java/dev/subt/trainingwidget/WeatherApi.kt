package dev.subt.trainingwidget

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.time.YearMonth
import java.util.Locale

/** Run-window settings and cached forecasts, kept in the app's normal Prefs file. */
object RwStore {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("training_widget", Context.MODE_PRIVATE)

    const val K_UV = "rw_uv"
    const val K_UV_FREE = "rw_uv_free"
    const val K_UV_CLOUD = "rw_uv_cloud"
    const val K_HOT_W = "rw_hot_w"
    const val K_HOT_S = "rw_hot_s"
    const val K_COLD = "rw_cold"
    const val K_HUMID = "rw_humid"
    const val K_BUFFER = "rw_buffer"
    const val K_AUTO = "rw_auto"
    const val K_S_FROM = "rw_s_from"
    const val K_S_TO = "rw_s_to"
    const val K_W_FROM = "rw_w_from"
    const val K_W_TO = "rw_w_to"

    /** Floats come back with noise (0.6 → 0.6000000238); settings have two decimals at most. */
    private fun r2(v: Double) = Math.round(v * 100.0) / 100.0

    fun load(ctx: Context): RwSettings {
        val p = sp(ctx)
        val d = RwSettings()
        return RwSettings(
            uvLimit = r2(p.getFloat(K_UV, d.uvLimit.toFloat()).toDouble()),
            uvFree = r2(p.getFloat(K_UV_FREE, d.uvFree.toFloat()).toDouble()),
            uvCloud = p.getFloat(K_UV_CLOUD, d.uvCloud.toFloat()).toDouble(),
            hotWinter = p.getFloat(K_HOT_W, d.hotWinter.toFloat()).toDouble(),
            hotSummer = p.getFloat(K_HOT_S, d.hotSummer.toFloat()).toDouble(),
            cold = p.getFloat(K_COLD, d.cold.toFloat()).toDouble(),
            humid = p.getFloat(K_HUMID, d.humid.toFloat()).toDouble(),
            bufferMin = p.getInt(K_BUFFER, d.bufferMin),
            auto = p.getBoolean(K_AUTO, d.auto),
            summerFrom = p.getInt(K_S_FROM, d.summerFrom),
            summerTo = p.getInt(K_S_TO, d.summerTo),
            winterFrom = p.getInt(K_W_FROM, d.winterFrom),
            winterTo = p.getInt(K_W_TO, d.winterTo),
            lat = p.getString("rw_lat", null)?.toDoubleOrNull(),
            lon = p.getString("rw_lon", null)?.toDoubleOrNull(),
            place = p.getString("rw_place", "") ?: "",
        )
    }

    /** Coordinates are kept to 2 decimals (about 1 km): enough for a forecast, no more precise than needed. */
    fun setLocation(ctx: Context, lat: Double, lon: Double, place: String) {
        sp(ctx).edit().putString("rw_lat", LocRules.round2(lat).toString()).putString("rw_lon", LocRules.round2(lon).toString()).putString("rw_place", place).apply()
        LocState.markZone(ctx)
    }

    fun putFloat(ctx: Context, k: String, v: Double) = sp(ctx).edit().putFloat(k, v.toFloat()).apply()
    fun putInt(ctx: Context, k: String, v: Int) = sp(ctx).edit().putInt(k, v).apply()
    fun putBool(ctx: Context, k: String, v: Boolean) = sp(ctx).edit().putBoolean(k, v).apply()
    fun getStr(ctx: Context, k: String): String? = sp(ctx).getString(k, null)
    fun putStr(ctx: Context, k: String, v: String) = sp(ctx).edit().putString(k, v).apply()
    fun getLong(ctx: Context, k: String): Long = sp(ctx).getLong(k, 0L)
    fun putLong(ctx: Context, k: String, v: Long) = sp(ctx).edit().putLong(k, v).apply()

    // ---- last good raw answer per source, with when it was fetched
    fun cache(ctx: Context, id: String): Pair<String, Long>? {
        val p = sp(ctx)
        val body = p.getString("rw_cache_$id", null) ?: return null
        return body to p.getLong("rw_cache_${id}_t", 0L)
    }

    fun drop(ctx: Context, id: String) = sp(ctx).edit().remove("rw_cache_$id").remove("rw_cache_${id}_t").apply()

    fun putCache(ctx: Context, id: String, body: String, at: Long = System.currentTimeMillis()) =
        sp(ctx).edit().putString("rw_cache_$id", body).putLong("rw_cache_${id}_t", at).apply()

    // ---- Rainbow calls this month (free plan: 10,000)
    private fun callsKey() = "rw_calls_" + YearMonth.now()
    fun callsThisMonth(ctx: Context): Int = sp(ctx).getInt(callsKey(), 0)
    fun addCall(ctx: Context) = sp(ctx).edit().putInt(callsKey(), callsThisMonth(ctx) + 1).apply()
}

/** Fetches every forecast source (each optional) and caches the last good answer. Never throws for a single source. */
object WeatherApi {
    private const val UA = "TrainingToday/1.0"
    const val RAINBOW_HOST = "api.rainbow.ai"

    class Resp(val code: Int, val body: String)
    class Report(val ok: List<String>, val failed: List<String>) {
        val anyOk get() = ok.isNotEmpty()
    }

    private fun http(url: String, headers: Map<String, String> = emptyMap()): Resp {
        val conn = Net.open(URL(url))
        try {
            conn.connectTimeout = 12_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", UA)
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Resp(code, body)
        } finally { conn.disconnect() }
    }

    private fun httpBytes(url: String, headers: Map<String, String>): Pair<Int, ByteArray> {
        val conn = Net.open(URL(url))
        try {
            conn.connectTimeout = 12_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", UA)
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            val code = conn.responseCode
            val body = if (code in 200..299) conn.inputStream.use { it.readBytes() } else ByteArray(0)
            return code to body
        } finally { conn.disconnect() }
    }

    private const val M15_FULL = "temperature_2m,apparent_temperature,precipitation,cloud_cover,uv_index,wind_speed_10m,wind_gusts_10m,dew_point_2m"
    /** What Open-Meteo's 15-minute set is known to carry (cloud cover and UV are not in it, so those are interpolated from the hourly forecast). */
    private const val M15_SAFE = "temperature_2m,apparent_temperature,precipitation,wind_speed_10m,wind_gusts_10m,dew_point_2m"
    private const val M15_MODELS = "ncep_hrrr_conus,ncep_nbm_conus"
    private const val K_M15_MODE = "rw_m15_mode"

    /** The Open-Meteo 15-minute URL: [vars] for [models] (null = Open-Meteo's own best match), today and tomorrow. */
    fun minutely15Url(lat: Double, lon: Double, vars: String, models: String?) =
        "https://api.open-meteo.com/v1/forecast?latitude=${f4(lat)}&longitude=${f4(lon)}&minutely_15=$vars" +
            (if (models != null) "&models=$models" else "") + "&timezone=auto&forecast_days=2&wind_speed_unit=kmh"

    /**
     * Fetches `minutely_15` with the US high-resolution models, falling back to fewer variables and then to the best match when
     * Open-Meteo refuses a variable (HTTP 400) or has no data for the place. Remembers which attempt worked. True when a usable
     * answer was cached; network errors propagate to the caller.
     */
    private fun fetchFine(ctx: Context, lat: Double, lon: Double): Boolean {
        val attempts = listOf(M15_FULL to M15_MODELS, M15_SAFE to M15_MODELS, M15_SAFE to null)
        val first = (RwStore.getStr(ctx, K_M15_MODE)?.toIntOrNull() ?: 0).coerceIn(0, attempts.lastIndex)
        for (i in (first..attempts.lastIndex) + (0 until first)) {
            val (vars, models) = attempts[i]
            val r = http(minutely15Url(lat, lon, vars, models))
            if (r.code != 200 || Weather.parseMinutely15(r.body).isEmpty()) continue
            RwStore.putCache(ctx, "om15", r.body)
            RwStore.putStr(ctx, K_M15_MODE, i.toString())
            return true
        }
        return false
    }

    /**
     * Rainbow's clouds tile for "now" at the position: asks for the latest snapshot id, fetches the zoom-7 tile holding
     * the position, decodes the WebP and reads the pixels around it (see [CloudTile] for the cloudy-or-not rule).
     * Two Rainbow calls. Null when anything fails; never throws.
     */
    fun cloudTileCloudy(ctx: Context, key: String, lat: Double, lon: Double): Boolean? = if (rainbowPaused(ctx)) null else try {
        val hdr = mapOf("Ocp-Apim-Subscription-Key" to key)
        RwStore.addCall(ctx)
        val snap = http("https://$RAINBOW_HOST/tiles/v1/snapshot?layer=clouds", hdr)
        val id = if (snap.code == 200) CloudTile.parseSnapshotId(snap.body) else null
        if (id == null) null else {
            val t = CloudTile.tileFor(lat, lon)
            RwStore.addCall(ctx)
            val (code, bytes) = httpBytes("https://$RAINBOW_HOST/tiles/v1/clouds/$id/${CloudTile.ZOOM}/${t.x}/${t.y}", hdr)
            val bmp = if (code == 200 && bytes.isNotEmpty()) android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) else null
            if (bmp == null) null else {
                val cx = (t.fx * bmp.width).toInt().coerceIn(0, bmp.width - 1)
                val cy = (t.fy * bmp.height).toInt().coerceIn(0, bmp.height - 1)
                val x0 = (cx - 2).coerceAtLeast(0); val y0 = (cy - 2).coerceAtLeast(0)
                val w = minOf(5, bmp.width - x0); val h = minOf(5, bmp.height - y0)
                val px = IntArray(w * h)
                bmp.getPixels(px, 0, w, x0, y0, w, h)
                CloudTile.cloudy(px)
            }
        }
    } catch (e: Exception) { null }

    private fun f4(v: Double) = String.format(Locale.US, "%.4f", v)

    private fun msg(e: Exception): String = (e.message ?: e.javaClass.simpleName).take(80)

    /** The Rainbow forecast URL (the key goes in a header, never in the URL). */
    /** Rainbow allows at most about 100 forecast hours (120+ is refused with HTTP 400), so ask for 96: four days hourly. */
    fun rainbowForecastUrl(lat: Double, lon: Double, days: Int = 7, hours: Int = 96) =
        "https://$RAINBOW_HOST/weather/v1/forecast/${f4(lon)}/${f4(lat)}?forecast_days=$days&forecast_hours=$hours"

    fun rainbowNowcastUrl(lat: Double, lon: Double) = "https://$RAINBOW_HOST/nowcast/v1/precip/${f4(lon)}/${f4(lat)}"

    private fun rainbowGet(ctx: Context, url: String, key: String): Resp {
        RwStore.addCall(ctx)
        val r = http(url, mapOf("Ocp-Apim-Subscription-Key" to key))
        if (r.code == 429) rainbowPause(ctx)
        return r
    }

    /**
     * When Rainbow's monthly allowance runs out (HTTP 429) the app stops calling it for a day, then tries once a day
     * until the allowance resets. The other sources carry on meanwhile.
     */
    private const val K_RB_PAUSE = "rw_rb_pause_until"
    fun rainbowPause(ctx: Context) = RwStore.putLong(ctx, K_RB_PAUSE, System.currentTimeMillis() + 24 * 3600_000L)
    fun rainbowPaused(ctx: Context): Boolean = System.currentTimeMillis() < RwStore.getLong(ctx, K_RB_PAUSE)

    fun fetchAll(ctx: Context): Report {
        val s = RwStore.load(ctx)
        val lat = s.lat; val lon = s.lon
        if (lat == null || lon == null) return Report(emptyList(), listOf("No location set"))
        val ok = ArrayList<String>(); val bad = ArrayList<String>()
        fun step(name: String, block: () -> Unit) {
            try { block(); ok += name } catch (e: Exception) { bad += "$name (${msg(e)})" }
        }

        step("Forecast models") {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=${f4(lat)}&longitude=${f4(lon)}" +
                "&hourly=temperature_2m,apparent_temperature,dew_point_2m,cloud_cover,precipitation,precipitation_probability,uv_index,wind_speed_10m,wind_gusts_10m,weather_code,is_day,visibility" +
                "&daily=sunrise,sunset&models=ecmwf_ifs025,gfs_seamless,ncep_hrrr_conus,ncep_nbm_conus&timezone=auto&forecast_days=7&wind_speed_unit=kmh"
            val r = http(url)
            if (r.code != 200) throw IOException("HTTP ${r.code}")
            val om = Weather.parseOpenMeteo(r.body)
            if (om == null || om.models.isEmpty()) throw IOException("no data")
            RwStore.putCache(ctx, "om", r.body)
        }
        // 15-minute detail for today and tomorrow: optional. Any failure drops it and everything carries on with the hourly forecast.
        try { if (!fetchFine(ctx, lat, lon)) RwStore.drop(ctx, "om15") } catch (e: Exception) { RwStore.drop(ctx, "om15") }
        step("Air quality") {
            val url = "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=${f4(lat)}&longitude=${f4(lon)}&hourly=us_aqi&timezone=auto&forecast_days=5"
            val r = http(url)
            if (r.code != 200) throw IOException("HTTP ${r.code}")
            if (Weather.parseAqi(r.body).isEmpty()) throw IOException("no data")
            RwStore.putCache(ctx, "aqi", r.body)
        }

        // NWS: US only. A 404 on /points means outside the US: remember it and skip from then on.
        val here = "${f4(lat)},${f4(lon)}"
        if (RwStore.getStr(ctx, "rw_nws_skip") != here) {
            try {
                var grid = if (RwStore.getStr(ctx, "rw_nws_grid_loc") == here) RwStore.getStr(ctx, "rw_nws_grid") else null
                var outside = false
                if (grid == null) {
                    val r = http("https://api.weather.gov/points/$here", mapOf("Accept" to "application/geo+json"))
                    if (r.code == 404) { RwStore.putStr(ctx, "rw_nws_skip", here); outside = true }
                    else {
                        if (r.code != 200) throw IOException("HTTP ${r.code}")
                        grid = Weather.parseNwsGridUrl(r.body) ?: throw IOException("no grid")
                        RwStore.putStr(ctx, "rw_nws_grid", grid); RwStore.putStr(ctx, "rw_nws_grid_loc", here)
                    }
                }
                if (!outside && grid != null) {
                    val r = http(grid, mapOf("Accept" to "application/geo+json"))
                    if (r.code != 200) throw IOException("HTTP ${r.code}")
                    if (Weather.parseNws(r.body).isEmpty()) throw IOException("no data")
                    RwStore.putCache(ctx, "nws", r.body)
                    ok += "NWS"
                }
            } catch (e: Exception) { bad += "NWS (${msg(e)})" }
        }

        // Rainbow: only with a key; both calls count towards the monthly total
        val key = SecureKey.load(ctx)
        if (key != null && rainbowPaused(ctx)) bad += "Rainbow (monthly limit used up: paused, retrying daily until it resets)"
        else if (key != null) {
            step("Rainbow") {
                val r = rainbowGet(ctx, rainbowForecastUrl(lat, lon), key)
                if (r.code == 401 || r.code == 403) throw IOException("key rejected, HTTP ${r.code}")
                if (r.code == 429) throw IOException("monthly limit used up: paused, retrying daily until it resets")
                if (r.code != 200) throw IOException("HTTP ${r.code}")
                if (Weather.parseRainbow(r.body).isEmpty()) throw IOException("no data")
                RwStore.putCache(ctx, "rainbow", r.body)
            }
            step("Rainbow nowcast") {
                val r = rainbowGet(ctx, rainbowNowcastUrl(lat, lon), key)
                if (r.code != 200) throw IOException("HTTP ${r.code}")
                if (runCatching { JSONObject(r.body).has("forecast") }.getOrDefault(false).not()) throw IOException("no data")
                RwStore.putCache(ctx, "nowcast", r.body)
            }
        }
        return Report(ok, bad)
    }

    /** Settings → "Test key": one small Rainbow call. Returns a sentence for the screen. */
    fun testKey(ctx: Context, key: String): String {
        val s = RwStore.load(ctx)
        val url = rainbowForecastUrl(s.lat ?: 40.0, s.lon ?: -105.0, days = 1, hours = 1)
        return try {
            val r = rainbowGet(ctx, url, key)
            when {
                r.code in 200..299 -> "✓ Rainbow accepted the key"
                r.code == 401 || r.code == 403 -> "Rainbow rejected the key (HTTP ${r.code})"
                r.code == 429 -> "Rainbow says the monthly limit is used up (HTTP 429)"
                else -> "Rainbow answered HTTP ${r.code}"
            }
        } catch (e: Exception) { "Couldn't reach Rainbow: ${msg(e)}" }
    }

    /** A place name → (lat, lon, label) through Open-Meteo's free geocoder; null when nothing matches. */
    /** Open-Meteo place search (towns and some post codes), up to 5 results. */
    fun geocodeAll(name: String): List<LocPrompt.Place> {
        val r = http("https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(name.trim(), "UTF-8")}&count=5&language=en&format=json")
        if (r.code != 200) throw IOException("HTTP ${r.code}")
        val a = JSONObject(r.body).optJSONArray("results") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val label = listOf(o.optString("name"), o.optString("admin1"), o.optString("country_code")).filter { it.isNotBlank() }.joinToString(", ")
            LocPrompt.Place(o.getDouble("latitude"), o.getDouble("longitude"), label)
        }
    }

    fun geocode(name: String): Triple<Double, Double, String>? {
        val r = http("https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(name.trim(), "UTF-8")}&count=1&language=en&format=json")
        if (r.code != 200) throw IOException("HTTP ${r.code}")
        val a = JSONObject(r.body).optJSONArray("results") ?: return null
        val o = a.optJSONObject(0) ?: return null
        val label = listOf(o.optString("name"), o.optString("admin1"), o.optString("country_code")).filter { it.isNotBlank() }.joinToString(", ")
        return Triple(o.getDouble("latitude"), o.getDouble("longitude"), label)
    }
}
