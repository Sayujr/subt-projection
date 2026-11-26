package dev.subt.trainingwidget

import android.Manifest
import android.app.Activity
import android.content.Context
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

/** Small dialogs made of a message and a column of buttons (an AlertDialog can't show both a message and a list). */
object RwDialogs {
    class Option(val label: String, val action: () -> Unit)

    /** The dialog body as a view (also used by the screenshot test). [done] closes the dialog. */
    fun content(a: Activity, body: String, options: List<Option>, done: () -> Unit): View {
        val d = a.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).roundToInt()
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        col.addView(TextView(a).apply {
            text = body; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f); setTextColor(0xFFF2F4F8.toInt()); setLineSpacing(dp(2).toFloat(), 1f)
        })
        for ((i, o) in options.withIndex()) {
            col.addView(TextView(a).apply {
                text = o.label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); gravity = Gravity.CENTER
                setTextColor(if (i == 0) 0xFFF2F4F8.toInt() else 0xFF4EA1FF.toInt())
                setBackgroundResource(if (i == 0) R.drawable.chip_on else R.drawable.chip_off)
                setPadding(dp(12), dp(11), dp(12), dp(11))
                setOnClickListener { done(); o.action() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(if (i == 0) 14 else 8) })
        }
        return col
    }

    fun show(a: Activity, title: String, body: String, options: List<Option>, onCancel: () -> Unit = {}) {
        if (a.isFinishing) return
        var dlg: AlertDialog? = null
        val v = content(a, body, options) { dlg?.dismiss() }
        dlg = AlertDialog.Builder(a).setTitle(title).setView(v).setOnCancelListener { onCancel() }.create()
        dlg?.show()
    }

    /** "Dismiss for 1 day / 3 days / 1 week / until…": stores the date until which the check is skipped. */
    fun dismissOptions(a: Activity, after: () -> Unit = {}): List<Option> {
        fun until(days: Int) {
            LocState.setDismissUntil(a, LocRules.dismissUntil(LocalDate.now(), days)); after()
        }
        return listOf(
            Option("Dismiss for 1 day") { until(1) },
            Option("Dismiss for 3 days") { until(3) },
            Option("Dismiss for 1 week") { until(7) },
            Option("Dismiss until…") {
                val t = LocalDate.now().plusDays(1)
                val dlg = DatePickerDialog(a, { _, y, m, dd -> LocState.setDismissUntil(a, LocalDate.of(y, m + 1, dd)); after() }, t.year, t.monthValue - 1, t.dayOfMonth)
                dlg.datePicker.minDate = t.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                dlg.show()
            },
        )
    }
}

/**
 * Location-change check when the app opens. Only approximate location (ACCESS_COARSE_LOCATION) is ever asked for, and
 * only from the foreground, so Android's one-time permission fits: the app asks first (once a day, or when the time zone
 * changes), then reads one coarse fix. If you already allowed location, it checks silently and only speaks up if you moved.
 */
object LocPrompt {
    const val REQ = 8
    private const val PERM = Manifest.permission.ACCESS_COARSE_LOCATION
    @Volatile private var lastRun = 0L
    @Volatile private var redraw: (() -> Unit)? = null

    fun granted(a: Activity) = a.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED

    private fun saved(a: Activity): String = RwStore.load(a).place.ifBlank { "your saved location" }

    /** Call from onResume. At most once per 30 minutes, never in tests (Net.offline), never while dismissed. */
    fun onOpen(a: Activity, redrawHome: () -> Unit) {
        if (Net.offline || a.isFinishing) return
        if (Prefs.credentials(a) == null || !RunWindows.hasLocation(a)) return
        val now = System.currentTimeMillis()
        if (now - lastRun < 30 * 60_000L) return
        if (!LocState.due(a)) return
        lastRun = now
        redraw = redrawHome
        if (granted(a)) check(a) else askFirst(a)
    }

    private fun askFirst(a: Activity) {
        val zone = LocState.zoneChanged(a)
        val title = if (zone) "New time zone — change in location?" else "Check your location?"
        val body = "Run windows use ${saved(a)}. Tap Check to compare with where you are now — Android will ask for one-time permission."
        RwDialogs.show(a, title, body,
            listOf(RwDialogs.Option("Check now") { a.requestPermissions(arrayOf(PERM), REQ) }) + RwDialogs.dismissOptions(a))
    }

    /** From onRequestPermissionsResult. */
    fun onPermissionResult(a: Activity, grantResults: IntArray) {
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) check(a)
        else {
            LocState.markChecked(a)
            Toast.makeText(a, "No location permission: keeping ${saved(a)}", Toast.LENGTH_LONG).show()
        }
    }

    /** One coarse fix, compared with the saved place; the "Change in location?" dialog only when more than 25 km away. */
    fun check(a: Activity) {
        val s = RwStore.load(a)
        val lat = s.lat ?: return
        val lon = s.lon ?: return
        coarseFix(a) { loc ->
            if (a.isFinishing) return@coarseFix
            if (loc == null) {
                Toast.makeText(a, "Couldn't get your location this time", Toast.LENGTH_SHORT).show()
                return@coarseFix
            }
            val km = LocRules.distanceKm(lat, lon, loc.latitude, loc.longitude)
            if (!LocRules.moved(km)) {
                LocState.markChecked(a)
                Toast.makeText(a, "Still near ${saved(a)} ✓", Toast.LENGTH_SHORT).show()
                return@coarseFix
            }
            Thread {
                val name = placeName(a, loc.latitude, loc.longitude)
                a.runOnUiThread { if (!a.isFinishing) showMoved(a, LocRules.round2(loc.latitude), LocRules.round2(loc.longitude), name, km, s.place.ifBlank { "your saved location" }) }
            }.start()
        }
    }

    fun movedBody(near: String, km: Double, savedPlace: String) =
        "You seem to be near $near — ${km.roundToInt()} km from $savedPlace. Run windows use $savedPlace."

    private fun showMoved(a: Activity, lat: Double, lon: Double, name: String?, km: Double, savedPlace: String) {
        val near = name ?: LocRules.latLon(lat, lon)
        RwDialogs.show(a, "Change in location?", movedBody(near, km, savedPlace),
            listOf(
                RwDialogs.Option("Use current location") { useLocation(a, lat, lon, name ?: "Current location") },
                RwDialogs.Option("Keep $savedPlace") { LocState.markChecked(a) },
            ) + RwDialogs.dismissOptions(a))
    }

    /** Saves the new place, forgets what was cached for the old one and fetches the forecast again. */
    fun useLocation(a: Activity, lat: Double, lon: Double, place: String) {
        val ctx = a.applicationContext
        RwStore.setLocation(ctx, lat, lon, place)
        for (id in listOf("om", "nws", "rainbow", "nowcast", "aqi")) RwStore.drop(ctx, id)
        val today = LocalDate.now()
        for (i in 0..1) RwStore.putStr(ctx, "rw_sig_${today.plusDays(i.toLong())}", "")
        Scoring.clearForecasts(ctx)
        LocState.markChecked(ctx)
        RunWindowSchedule.ensure(ctx)
        Toast.makeText(a, "Run windows now use $place", Toast.LENGTH_LONG).show()
        RunWindows.refreshAsync(ctx, manual = true) { redraw?.invoke() }
    }

    data class Place(val lat: Double, val lon: Double, val label: String)

    /**
     * Forward search for anything typed: an address ("1600 Market St"), a neighbourhood ("downtown Denver"),
     * a ZIP / PIN / post code, or a town. Uses the phone's own Geocoder (Google's address search on most phones),
     * then Open-Meteo's place search as a fallback. Up to 5 distinct results. Blocking: call off the main thread.
     */
    @Suppress("DEPRECATION")
    fun search(ctx: Context, q: String): List<Place> {
        val out = ArrayList<Place>()
        runCatching {
            if (android.location.Geocoder.isPresent()) {
                android.location.Geocoder(ctx, Locale.getDefault()).getFromLocationName(q, 5).orEmpty().forEach { ad ->
                    if (!ad.hasLatitude() || !ad.hasLongitude()) return@forEach
                    val first = listOfNotNull(
                        ad.featureName?.takeIf { f -> f != ad.locality && f != ad.postalCode && f != ad.subThoroughfare },
                        listOfNotNull(ad.subThoroughfare, ad.thoroughfare).joinToString(" ").takeIf { it.isNotBlank() },
                        ad.subLocality,
                    ).distinct().joinToString(", ")
                    val label = listOf(first, ad.locality ?: ad.subAdminArea ?: "", listOfNotNull(ad.adminArea, ad.postalCode).joinToString(" "), ad.countryCode ?: "")
                        .filter { it.isNotBlank() }.distinct().joinToString(", ")
                    out += Place(ad.latitude, ad.longitude, label.ifBlank { q })
                }
            }
        }
        if (out.isEmpty()) runCatching { out += WeatherApi.geocodeAll(q) }
        // Drop near-duplicates (same spot within ~200 m)
        return out.fold(ArrayList<Place>()) { acc, p ->
            if (acc.none { Math.abs(it.lat - p.lat) < 0.002 && Math.abs(it.lon - p.lon) < 0.002 }) acc += p; acc
        }.take(5)
    }

    /** Reverse lookup through the phone's own Geocoder when it has one; null otherwise (the caller shows lat, lon). */
    @Suppress("DEPRECATION")
    fun placeName(a: Activity, lat: Double, lon: Double): String? {
        return try {
            if (!android.location.Geocoder.isPresent()) return null
            val ad = android.location.Geocoder(a, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull() ?: return null
            listOfNotNull(ad.locality ?: ad.subAdminArea, ad.adminArea).distinct().joinToString(", ").takeIf { it.isNotBlank() }
        } catch (e: Exception) { null }
    }

    /**
     * A coarse fix from the network provider (or passive), no GPS: the last known one when it is under 2 hours old,
     * else one fresh update with an 8 second limit, falling back to an older last-known fix. Delivered on the main thread.
     */
    @Suppress("MissingPermission", "DEPRECATION")
    fun coarseFix(a: Activity, cb: (Location?) -> Unit) {
        val lm = a.getSystemService(LocationManager::class.java)
        if (lm == null) { cb(null); return }
        var best: Location? = null
        for (p in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            val l = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            val b = best
            if (b == null || l.time > b.time) best = l
        }
        val known = best
        if (known != null && System.currentTimeMillis() - known.time < 2 * 3600_000L) { cb(known); return }
        val provider = LocationManager.NETWORK_PROVIDER
        if (!runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)) { cb(known); return }
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (finished) return
                finished = true; handler.removeCallbacksAndMessages(null); cb(location)
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        val started = runCatching { lm.requestSingleUpdate(provider, listener, Looper.getMainLooper()) }.isSuccess
        if (!started) { cb(known); return }
        handler.postDelayed({
            if (!finished) { finished = true; runCatching { lm.removeUpdates(listener) }; cb(known) }
        }, 8_000L)
    }
}
