package dev.subt.trainingwidget

import android.content.Context
import kotlin.math.abs
import kotlin.math.roundToInt

/** Where the numbers you entered differ from intervals.icu / measured values. The app uses yours. */
object Deviations {
    fun compute(ctx: Context): List<String> {
        val out = mutableListOf<String>()
        val s = Prefs.snapshot(ctx)
        Prefs.weightOverride(ctx)?.let { w ->
            s?.weight?.let { i -> if (abs(w - i) >= 1.0) out += "Weight ${"%.1f".format(w)} kg (yours) vs ${"%.1f".format(i)} kg in intervals.icu" }
        }
        Prefs.cpOverride(ctx)?.let { cp ->
            s?.runFtp?.let { f -> if (abs(cp - f) >= 5) out += "CP ${cp.roundToInt()} W (yours) vs FTP ${f.roundToInt()} W in intervals.icu" }
            s?.runs?.lastOrNull { it.cp != null }?.cp?.let { e -> if (abs(cp - e) >= 5) out += "CP ${cp.roundToInt()} W (yours) vs eFTP ${e.roundToInt()} W estimated by intervals.icu" }
        }
        Prefs.strydCp(ctx)?.let { sc ->
            val ftp = s?.runFtp; val eftp = s?.runs?.lastOrNull { it.cp != null }?.cp
            val parts = listOfNotNull(ftp?.takeIf { abs(sc - it) >= 5 }?.let { "FTP ${it.roundToInt()}" }, eftp?.takeIf { abs(sc - it) >= 5 }?.let { "eFTP ${it.roundToInt()}" })
            if (parts.isNotEmpty()) out += "Stryd CP ${sc.roundToInt()} W vs intervals.icu ${parts.joinToString(" · ")} W"
        }
        val todayDay = s?.days?.lastOrNull()?.takeIf { it.date == java.time.LocalDate.now().toString() }
        Prefs.rhrOverride(ctx)?.let { r ->
            val i = todayDay?.rhr
            if (i == null) out += "Resting HR ${r.roundToInt()} (yours) isn't in intervals.icu for today"
            else if (abs(r - i) >= 3) out += "Resting HR ${r.roundToInt()} (yours) vs ${i.roundToInt()} in intervals.icu today"
        }
        Prefs.hrvOverride(ctx)?.let { h ->
            val i = todayDay?.hrv
            if (i == null) out += "HRV ${h.roundToInt()} ms (yours) isn't in intervals.icu for today"
            else if (abs(h - i) >= 5) out += "HRV ${h.roundToInt()} ms (yours) vs ${i.roundToInt()} ms in intervals.icu today"
        }
        Prefs.subtOverride(ctx)?.let { p ->
            Prefs.subt(ctx)?.months?.lastOrNull { !it.outlier }?.pace?.let { m ->
                if (abs(p - m) * 60 >= 5) out += "SubT ${Charts.paceText(p)}/km (yours) vs ${Charts.paceText(m)}/km latest monthly median"
            }
        }
        val auto = Prefs.pbs(ctx)
        for ((key, label, fetched) in listOf(Triple("5k", "5K", auto?.fiveK), Triple("10k", "10K", auto?.tenK), Triple("hm", "Half", auto?.half))) {
            val mine = Prefs.manualPb(ctx, key) ?: continue
            val f = fetched?.seconds
            if (f == null) out += "$label PB ${SubtModel.time(mine)} (yours) isn't in intervals.icu's bests for the last 12 months"
            else if (abs(mine - f) >= 5) out += "$label PB ${SubtModel.time(mine)} (yours) vs ${SubtModel.time(f)} in intervals.icu"
        }
        return out
    }
}
