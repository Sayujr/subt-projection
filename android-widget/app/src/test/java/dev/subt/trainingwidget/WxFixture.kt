package dev.subt.trainingwidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Made-up forecasts in the field shapes the spec describes (Rainbow hourly + nowcast, Open-Meteo with four models and
 * sunrise / sunset, NWS sky cover, air quality) for tests and screenshots. No real data, no real key.
 */
object WxFixture {
    val zone: ZoneId = ZoneId.systemDefault()

    class P(val temp: Double, val cloud: Double, val uv: Double, val rain: Double, val dew: Double, val wind: Double)

    /** Lets a test reshape the forecast (day offset, hour, the normal profile) before it is written out. */
    @Volatile var tweak: ((Int, Int, P) -> P)? = null

    fun profile(day: Int, hour: Int): P {
        val p = baseProfile(day, hour)
        return tweak?.invoke(day, hour, p) ?: p
    }

    /** Seven different days: cool and grey, hot and humid, a wet afternoon, steady drizzle, … */
    private fun baseProfile(day: Int, hour: Int): P {
        val k = day % 7
        val heat = max(0.0, sin(PI * (hour - 6) / 14.0))
        val base = listOf(9.0, 16.0, 11.0, 7.0, 13.0, 17.0, 10.0)[k]
        val temp = base + 8 * heat
        val cloud = (listOf(85.0, 40.0, 70.0, 95.0, 30.0, 55.0, 80.0)[k] - 25 * heat).coerceIn(5.0, 100.0)
        val uv = heat * listOf(3.5, 7.0, 2.0, 0.8, 6.0, 4.0, 2.5)[k]
        val rain = when {
            k == 2 && hour in 14..17 -> 2.0
            k == 3 && hour in 6..22 -> 0.6
            else -> 0.0
        }
        val dew = temp - if (k == 1) 5.0 else 9.0
        return P(temp, cloud, uv, rain, dew, 10.0 + 6 * heat)
    }

    private fun hourEpoch(day: Int, hour: Int): Long =
        LocalDate.now(zone).plusDays(day.toLong()).atStartOfDay().plusHours(hour.toLong()).atZone(zone).toEpochSecond()

    private fun localStr(day: Int, hour: Int): String =
        LocalDate.now(zone).plusDays(day.toLong()).atStartOfDay().plusHours(hour.toLong()).toString()

    private fun cond(p: P) = when {
        p.rain > 0.5 -> "Rain"
        p.cloud < 15 -> "Clear"
        p.cloud < 35 -> "MostlyClear"
        p.cloud < 60 -> "PartlyCloudy"
        p.cloud < 85 -> "MostlyCloudy"
        else -> "Cloudy"
    }

    fun rainbowJson(): String {
        val a = JSONArray()
        for (day in 0 until 7) for (hour in 0 until 24) {
            val p = profile(day, hour)
            a.put(JSONObject()
                .put("time", Instant.ofEpochSecond(hourEpoch(day, hour)).toString())
                .put("temperature", p.temp + 1).put("feelsLikeTemperature", p.temp + 0.5).put("temperatureDewPoint", p.dew)
                .put("uvIndex", p.uv).put("precipitationChance", if (p.rain > 0) 70 else 5).put("precipitationAmount", p.rain)
                .put("precipitationRate", p.rain).put("precipitationType", if (p.rain > 0) "rain" else "none")
                .put("windSpeed", p.wind / 3.6).put("windGust", p.wind * 1.5 / 3.6).put("visibility", 10000).put("humidity", 60)
                .put("condition", cond(p)))
        }
        return JSONObject().put("hourly", a).toString()
    }

    fun omJson(): String {
        val times = JSONArray()
        for (day in 0 until 7) for (hour in 0 until 24) times.put(localStr(day, hour))
        val hourly = JSONObject().put("time", times)
        val models = listOf("ecmwf_ifs025" to 0.0, "gfs_seamless" to 8.0, "ncep_hrrr_conus" to 3.0, "ncep_nbm_conus" to -6.0)
        for ((m, dc) in models) {
            val short = m == "ncep_hrrr_conus" || m == "ncep_nbm_conus"
            fun col(name: String, f: (P) -> Double) {
                val arr = JSONArray()
                for (day in 0 until 7) for (hour in 0 until 24) {
                    if (short && day >= 2) arr.put(JSONObject.NULL) else arr.put(f(profile(day, hour)))
                }
                hourly.put("${name}_$m", arr)
            }
            col("temperature_2m") { it.temp }
            col("apparent_temperature") { it.temp - 1 }
            col("dew_point_2m") { it.dew }
            col("cloud_cover") { (it.cloud + dc).coerceIn(0.0, 100.0) }
            col("precipitation") { it.rain }
            col("precipitation_probability") { if (it.rain > 0) 60.0 else 5.0 }
            col("uv_index") { it.uv * 0.9 }
            col("wind_speed_10m") { it.wind }
            col("wind_gusts_10m") { it.wind * 1.6 }
            col("weather_code") { if (it.rain > 0.5) 61.0 else 3.0 }
        }
        val dates = JSONArray(); val rise = JSONArray(); val set = JSONArray()
        for (day in 0 until 7) {
            val d = LocalDate.now(zone).plusDays(day.toLong())
            dates.put(d.toString()); rise.put("${d}T06:40"); set.put("${d}T19:50")
        }
        val off = zone.rules.getOffset(Instant.now()).totalSeconds
        return JSONObject().put("utc_offset_seconds", off).put("hourly", hourly)
            .put("daily", JSONObject().put("time", dates).put("sunrise", rise).put("sunset", set)).toString()
    }

    fun nwsGridJson(): String {
        val vals = JSONArray()
        for (day in 0 until 7) for (hour in 0 until 24 step 3) {
            vals.put(JSONObject().put("validTime", Instant.ofEpochSecond(hourEpoch(day, hour)).toString() + "/PT3H").put("value", profile(day, hour).cloud))
        }
        return JSONObject().put("properties", JSONObject().put("skyCover", JSONObject().put("values", vals))).toString()
    }

    /** Per-minute rain rates for the next 4 hours, as the Rainbow nowcast returns them. */
    fun nowcastJson(nowSec: Long = System.currentTimeMillis() / 1000): String {
        val a = JSONArray()
        val start = nowSec - nowSec % 60
        for (i in 0 until 240) {
            val b = start + i * 60L
            val day = ((b - hourEpoch(0, 0)) / 86400).toInt().coerceIn(0, 6)
            val hour = (((b - hourEpoch(day, 0)) / 3600).toInt()).coerceIn(0, 23)
            a.put(JSONObject().put("precipRate", profile(day, hour).rain).put("precipType", "rain").put("timestampBegin", b).put("timestampEnd", b + 60))
        }
        return JSONObject().put("forecast", a).put("summary", JSONObject().put("intensity", "none")).toString()
    }

    fun aqiJson(): String {
        val times = JSONArray(); val v = JSONArray()
        for (day in 0 until 5) for (hour in 0 until 24) { times.put(localStr(day, hour)); v.put(if (day == 1 && hour in 12..16) 120.0 else 40.0) }
        val off = zone.rules.getOffset(Instant.now()).totalSeconds
        return JSONObject().put("utc_offset_seconds", off).put("hourly", JSONObject().put("time", times).put("us_aqi", v)).toString()
    }

    /** A location plus every source cached (as if just fetched), so the Today card, day plan and Week list render offline. */
    fun seed(ctx: Context) {
        RwStore.setLocation(ctx, 40.015, -105.27, "Boulder, CO, US")
        RwStore.putCache(ctx, "om", omJson())
        RwStore.putCache(ctx, "nws", nwsGridJson())
        RwStore.putCache(ctx, "rainbow", rainbowJson())
        RwStore.putCache(ctx, "nowcast", nowcastJson())
        RwStore.putCache(ctx, "aqi", aqiJson())
    }
}
