package dev.subt.trainingwidget

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/**
 * Checks the GitHub release for a newer build (version.json published with each APK), downloads
 * it with Android's download manager and hands it to the installer. Shows "What's new" once after
 * an update. Nothing is installed without your tap and Android's own install prompt.
 */
object Updater {
    private const val BASE = "https://github.com/Sayujr/subt-projection/releases/download/android-widget-latest/"
    const val RELEASE_PAGE = "https://github.com/Sayujr/subt-projection/releases/tag/android-widget-latest"

    data class Latest(val code: Int, val name: String, val notes: List<String>)

    fun currentCode(ctx: Context): Long = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }.getOrDefault(0L)

    fun currentName(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"

    private fun parse(s: String): Latest? = runCatching {
        val o = JSONObject(s)
        val n = o.optJSONArray("notes") ?: JSONArray()
        Latest(o.getInt("code"), o.optString("name", "1.${o.getInt("code")}"), (0 until n.length()).map { n.getString(it) })
    }.getOrNull()

    fun latest(ctx: Context): Latest? = Prefs.updateInfo(ctx)?.let { parse(it) }

    /** A newer build than the one installed, if the last check found one. */
    fun available(ctx: Context): Latest? = latest(ctx)?.takeIf { it.code > currentCode(ctx) }

    /** Fetches version.json (at most every 6 h unless [force]). Network: call off the main thread. */
    fun check(ctx: Context, force: Boolean = false) {
        if (!force && System.currentTimeMillis() - Prefs.updateCheckedAt(ctx) < 6 * 3600_000L) return
        val conn = Net.open(URL(BASE + "version.json"))
        try {
            conn.connectTimeout = 15_000; conn.readTimeout = 20_000
            conn.instanceFollowRedirects = true
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                if (parse(body) != null) Prefs.saveUpdateInfo(ctx, body)
            }
            Prefs.setUpdateCheckedAt(ctx, System.currentTimeMillis())
        } finally { conn.disconnect() }
    }

    // ---- download + install

    fun startDownload(ctx: Context, l: Latest): Long {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val req = DownloadManager.Request(Uri.parse(BASE + "training-widget.apk"))
            .setTitle("Training Today ${l.name}")
            .setDescription("Update download")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, "training-widget-${l.code}.apk")
        val id = dm.enqueue(req)
        Prefs.setUpdateDownload(ctx, id, l.code)
        return id
    }

    /** Download state for the update card: (status, percent) or null if none for [code]. */
    fun progress(ctx: Context, code: Int): Pair<Int, Int>? {
        val (id, forCode) = Prefs.updateDownload(ctx) ?: return null
        if (forCode != code) return null
        val dm = ctx.getSystemService(DownloadManager::class.java)
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return null
            val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return st to (if (total > 0) (done * 100 / total).toInt() else 0)
        }
        return null
    }

    /** Opens Android's installer for the downloaded APK (asks once to allow installs from this app). */
    fun install(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val (id, _) = Prefs.updateDownload(ctx) ?: return false
        val uri = ctx.getSystemService(DownloadManager::class.java).getUriForDownloadedFile(id) ?: return false
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    // ---- what's new

    fun whatsNew(ctx: Context): List<String> = runCatching {
        ctx.assets.open("whatsnew.txt").bufferedReader().readLines().map { it.trim() }.filter { it.startsWith("- ") }.map { it.drop(2) }
    }.getOrDefault(emptyList())

    /** True once after an update (not on the very first install). */
    fun takeWhatsNew(ctx: Context): Boolean {
        val cur = currentCode(ctx)
        val seen = Prefs.seenVersion(ctx)
        Prefs.setSeenVersion(ctx, cur)
        return seen in 1 until cur
    }
}
