package dev.subt.trainingwidget

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File

/** Keeps the last few crashes on the phone so they can be shared from Settings → Crash reports. */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "crashes.txt")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val f = file(app)
                val entry = buildString {
                    append("=== ").append(java.time.LocalDateTime.now().withNano(0)).append("  ·  app ").append(Updater.currentName(app))
                    append("  ·  Android ").append(Build.VERSION.RELEASE).append(" (").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(")\n")
                    append("thread ").append(t.name).append('\n')
                    append(android.util.Log.getStackTraceString(e)).append('\n')
                }
                // keep roughly the last 5 crashes
                val old = if (f.exists()) f.readText().split("=== ").filter { it.isNotBlank() }.takeLast(4).joinToString("") { "=== $it" } else ""
                f.writeText(old + entry)
            }
            previous?.uncaughtException(t, e)
        }
    }

    fun read(ctx: Context): String? = file(ctx).takeIf { it.exists() && it.length() > 0 }?.readText()
    fun count(ctx: Context): Int = read(ctx)?.split("=== ")?.count { it.isNotBlank() } ?: 0
    fun lastTime(ctx: Context): String? = read(ctx)?.split("=== ")?.lastOrNull { it.isNotBlank() }?.substringBefore("  ·")
    fun clear(ctx: Context) { file(ctx).delete() }
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
