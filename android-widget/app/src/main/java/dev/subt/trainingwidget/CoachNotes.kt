package dev.subt.trainingwidget

/**
 * The "Coach notes" note kept in the athlete's intervals.icu calendar, one per day: parsing, rendering and
 * merging. Pure text logic (no network, no views). A separate coach fills in the "(pending)" markers; the app
 * must never lose text the coach wrote, so every update parses the latest note and only adds to it.
 */
object CoachNotes {
    const val NAME = "Coach notes"
    const val PENDING = "(pending)"
    private const val HEADER = "TRAINING TODAY COACH NOTES v1"
    private const val INTRO = "(Written by the Training Today app. A coach fills in the lines marked (pending). Keep the === lines unchanged.)"
    private const val QUESTIONS = "=== QUESTIONS ==="
    private const val MAX_CONTEXT = 400

    /** [recap] is null while pending. */
    data class WorkoutNote(val activityId: String, val name: String, val data: String, val draft: List<String>, val recap: String?)
    /** [answer] is null while pending. */
    data class Qa(val n: Int, val time: String, val topic: String, val question: String, val context: String, val answer: String?)
    data class Note(val about: String, val workouts: List<WorkoutNote>, val questions: List<Qa>)

    /** Only runs that had planned targets get a note section. */
    fun eligible(type: String, hasTargets: Boolean): Boolean = hasTargets && type.contains("run", ignoreCase = true)

    private val workoutHead = Regex("^=== WORKOUT (\\S+) \\| (.*) ===$")
    private val questionHead = Regex("^Q(\\d+) \\| ([^|]*?) \\| (.*)$")
    private val answerHead = Regex("^A\\d+$")

    private fun oneLine(s: String) = s.trim().replace(Regex("\\s*[\\r\\n]+\\s*"), " ")

    fun parse(description: String): Note? {
        val lines = description.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        if (lines.firstOrNull()?.trim() != HEADER) return null
        val heads = lines.indices.filter { lines[it].startsWith("=== ") }
        val pre = lines.subList(1, heads.firstOrNull() ?: lines.size)
        val aboutAt = pre.indexOfFirst { it.trim() == "ABOUT ME" }
        val about = if (aboutAt < 0) "" else pre.subList(aboutAt + 1, pre.size).joinToString("\n").trim()
        val workouts = mutableListOf<WorkoutNote>()
        val questions = mutableListOf<Qa>()
        for ((k, h) in heads.withIndex()) {
            val body = lines.subList(h + 1, heads.getOrNull(k + 1) ?: lines.size)
            val m = workoutHead.matchEntire(lines[h].trimEnd())
            if (m != null) {
                val d = body.indexOfFirst { it.trim() == "DATA" }
                val dr = body.indexOfFirst { it.trim() == "DRAFT" }.let { i -> if (i < 0) -1 else i }
                val r = body.indexOfFirst { it.trim() == "RECAP" }
                if (d < 0 || dr < d || r < dr) continue
                val data = body.subList(d + 1, dr).joinToString("\n").trim()
                val draft = body.subList(dr + 1, r).map { it.trim() }.filter { it.isNotEmpty() }.map { it.removePrefix("- ").trim() }
                val recap = body.subList(r + 1, body.size).joinToString("\n").trim()
                workouts += WorkoutNote(m.groupValues[1], m.groupValues[2], data, draft, recap.takeIf { it.isNotEmpty() && it != PENDING })
            } else if (lines[h].trim() == QUESTIONS) {
                val qs = body.indices.filter { questionHead.matches(body[it]) }
                for ((j, qi) in qs.withIndex()) {
                    val block = body.subList(qi, qs.getOrNull(j + 1) ?: body.size)
                    val qm = questionHead.matchEntire(block[0])!!
                    val c = block.indexOfFirst { it.startsWith("CONTEXT:") }
                    val a = block.indexOfFirst { answerHead.matches(it.trim()) }
                    val qEnd = if (c >= 0) c else if (a >= 0) a else block.size
                    val question = block.subList(1, qEnd).joinToString(" ") { it.trim() }.trim()
                    val context = if (c >= 0) block[c].removePrefix("CONTEXT:").trim() else ""
                    val answer = if (a >= 0) block.subList(a + 1, block.size).joinToString("\n").trim() else ""
                    questions += Qa(qm.groupValues[1].toInt(), qm.groupValues[2].trim(), qm.groupValues[3].trim(), question, context, answer.takeIf { it.isNotEmpty() && it != PENDING })
                }
            }
        }
        return Note(about, workouts, questions)
    }

    fun render(note: Note): String {
        val sb = StringBuilder()
        sb.append(HEADER).append('\n').append(INTRO).append("\n\n")
        sb.append("ABOUT ME\n").append(note.about.trim())
        for (w in note.workouts) {
            sb.append("\n\n=== WORKOUT ").append(w.activityId).append(" | ").append(oneLine(w.name)).append(" ===\n")
            sb.append("DATA\n").append(w.data.trim()).append("\nDRAFT\n")
            for (l in w.draft) sb.append("- ").append(oneLine(l)).append('\n')
            sb.append("RECAP\n").append(w.recap?.trim()?.takeIf { it.isNotEmpty() } ?: PENDING)
        }
        if (note.questions.isNotEmpty()) {
            sb.append("\n\n").append(QUESTIONS)
            for ((i, q) in note.questions.withIndex()) {
                sb.append(if (i == 0) "\n" else "\n\n")
                sb.append("Q${q.n} | ${oneLine(q.time)} | ${oneLine(q.topic)}\n")
                sb.append(oneLine(q.question)).append('\n')
                sb.append("CONTEXT: ").append(oneLine(q.context)).append('\n')
                sb.append("A${q.n}\n").append(q.answer?.trim()?.takeIf { it.isNotEmpty() } ?: PENDING)
            }
        }
        return sb.toString()
    }

    /** Adds [w] unless that activity already has a section (an existing section, recap included, is never touched). */
    fun withWorkout(note: Note?, about: String, w: WorkoutNote): Note {
        val base = note ?: Note(about, emptyList(), emptyList())
        val workouts = if (base.workouts.any { it.activityId == w.activityId }) base.workouts else base.workouts + w
        return base.copy(about = about, workouts = workouts)
    }

    fun withQuestion(note: Note?, about: String, time: String, topic: String, question: String, context: String): Note {
        val base = note ?: Note(about, emptyList(), emptyList())
        val n = (base.questions.maxOfOrNull { it.n } ?: 0) + 1
        return base.copy(about = about, questions = base.questions + Qa(n, oneLine(time), oneLine(topic), oneLine(question), oneLine(context).take(MAX_CONTEXT), null))
    }
}
