package dev.subt.trainingwidget

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import java.time.LocalDate

/**
 * "Swap ② for an indoor bike": when a double has no run window after 16:00, an easy ride of about the same load can be
 * added to intervals.icu. Only on an explicit tap plus a confirmation; the planned run is never deleted or changed.
 */
object RwBike {
    const val START_MIN = 17 * 60

    private fun key(date: LocalDate) = "rw_bike_$date"

    /** "added" once the ride is in intervals.icu, "kept" after "Keep the run", else null. */
    fun state(ctx: Context, date: LocalDate): String? = RwStore.getStr(ctx, key(date))?.takeIf { it.isNotEmpty() }

    fun keep(ctx: Context, date: LocalDate) = RwStore.putStr(ctx, key(date), "kept")

    fun rideName() = "Easy ride (indoor, for double)"

    /** Asks first, then creates the ride event off the main thread. [onDone] runs on the main thread. */
    fun confirmAndAdd(a: Activity, date: LocalDate, b: Weather.BikeSwap, onDone: () -> Unit) {
        val day = date.format(java.time.format.DateTimeFormatter.ofPattern("EEE d", java.util.Locale.getDefault()))
        AlertDialog.Builder(a)
            .setTitle("Add an indoor ride?")
            .setMessage("Adds a ${b.minutes}′ easy ride (55–65% FTP, ≈ the same load as the ${b.runMinutes}′ run) to intervals.icu on $day at 17:00.\n\nThe planned run stays; nothing is deleted.")
            .setPositiveButton("Add to intervals.icu") { _, _ ->
                val creds = Prefs.credentials(a)
                if (creds == null) { Toast.makeText(a, "Connect intervals.icu first", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val app = a.applicationContext
                Thread {
                    val msg = try {
                        IcuApi(creds).createRideEvent(date.toString(), START_MIN, rideName(), b.minutes, RwText.bikeDescription(b))
                        RwStore.putStr(app, key(date), "added")
                        Refresh.now(app, replace = true)
                        "Added the ride to intervals.icu"
                    } catch (e: Exception) { "Couldn't add it: ${e.message ?: "network error"}" }
                    a.runOnUiThread { Toast.makeText(a, msg, Toast.LENGTH_LONG).show(); if (!a.isFinishing) onDone() }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
