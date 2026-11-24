package dev.subt.trainingwidget

import android.content.Context
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Keeps the day's "Coach notes" note in intervals.icu in step with the app. The only things written are
 * that one NOTE per day; it is always fetched fresh and merged, never replaced blindly.
 */
object CoachSync {
    /** Fetches [date]'s latest note, applies [change], saves it, and updates the cache. */
    private fun update(ctx: Context, api: IcuApi, date: String, change: (CoachNotes.Note?, String) -> CoachNotes.Note) {
        val day = LocalDate.parse(date)
        val existing = api.fetchCoachNotes(day, day)[date]
        val parsed = existing?.second?.takeIf { it.isNotBlank() }?.let { CoachNotes.parse(it) ?: throw IOException("A 'Coach notes' note already on $date isn't in the app's format, so it was left alone") }
        val text = CoachNotes.render(change(parsed, Coach.background(ctx)))
        api.saveCoachNote(date, existing?.first, text)
        Prefs.saveCoachNotes(ctx, Prefs.coachNotes(ctx) + (date to text))
    }

    /** Appends a question to [date]'s note. Throws on network / auth errors. */
    fun sendQuestion(ctx: Context, creds: Credentials, date: String, topic: String, question: String, context: String) {
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        update(ctx, IcuApi(creds), date) { n, about -> CoachNotes.withQuestion(n, about, time, topic, question, context) }
    }

    /** Why the ask / add actions can't run right now, or null when they can. */
    fun blocker(ctx: Context): String? = when {
        !Prefs.coachSync(ctx) -> "Coach notes are off. Turn on 'Keep coach notes in intervals.icu' in Settings → Coach."
        Prefs.credentials(ctx) == null -> "Connect intervals.icu in Settings first."
        else -> null
    }

    /** Last 3 days: cache the notes, and add a section for each finished run with targets that has none yet. */
    fun run(ctx: Context, creds: Credentials) {
        val api = IcuApi(creds)
        val today = LocalDate.now(); val from = today.minusDays(2)
        val fetched = api.fetchCoachNotes(from, today).toMutableMap()
        val cache = Prefs.coachNotes(ctx).filterKeys { it < from.toString() || it > today.toString() }.toMutableMap()
        fetched.forEach { (d, v) -> cache[d] = v.second }
        Prefs.saveCoachNotes(ctx, cache)
        val snap = Prefs.snapshot(ctx) ?: return
        val noted = Prefs.coachNoted(ctx).toMutableSet()
        val todo = snap.week.filter { it.status == WorkoutStatus.DONE && it.activityId != null && it.date >= from.toString() && it.date <= today.toString() && it.activityId !in noted }
        for (w in todo) {
            val id = w.activityId ?: continue
            if (!CoachNotes.eligible(w.type, true)) { noted += id; Prefs.saveCoachNoted(ctx, noted); continue }
            try {
                val raw = api.fetchWorkoutRaw(id)
                WorkoutDetail.save(ctx, id, raw)
                val ftp = Prefs.cpOverride(ctx) ?: snap.runFtp
                val det = WorkoutDetail.fromRaw(raw, ftp)
                if (CoachNotes.eligible(det.type, det.hasTargets)) {
                    val z = if (det.heart.any { it != null }) runCatching { Zones.compute(raw, Prefs.lt1(ctx).toDouble(), ftp) }.getOrNull() else null
                    val r = Coach.workout(det, z)
                    val section = CoachNotes.WorkoutNote(id, det.name, r.data, r.lines.map { it.text }, null)
                    val existing = fetched[w.date]
                    val parsed = existing?.second?.takeIf { it.isNotBlank() }?.let { CoachNotes.parse(it) }
                    if (existing != null && existing.second.isNotBlank() && parsed == null) { noted += id; Prefs.saveCoachNoted(ctx, noted); continue }
                    val text = CoachNotes.render(CoachNotes.withWorkout(parsed, Coach.background(ctx), section))
                    val eventId = api.saveCoachNote(w.date, existing?.first, text)
                    fetched[w.date] = eventId to text
                    Prefs.saveCoachNotes(ctx, Prefs.coachNotes(ctx) + (w.date to text))
                }
                noted += id; Prefs.saveCoachNoted(ctx, noted)
            } catch (e: AuthException) { throw e } catch (e: Exception) { /* try again next refresh */ }
        }
    }
}
