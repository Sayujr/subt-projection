package dev.subt.trainingwidget

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * The Coach sheet: the instant read-out, three suggested questions, and "Talk it through", which hands a
 * prepared prompt to the Claude app through Android's share sheet. Nothing is sent unless that is tapped.
 */
object CoachSheet {
    private const val TEXT = 0xFFF2F4F8.toInt()
    private const val DIM = 0xFF9AA3B2.toInt()
    private const val FAINT = 0xFF6B7386.toInt()
    private const val GOOD = 0xFF3DD68C.toInt()
    private const val WATCH = 0xFFFFC53D.toInt()
    private const val ACT = 0xFFFF7A7A.toInt()
    private const val LINK = 0xFF4EA1FF.toInt()
    private const val PANEL = 0xFF1B202B.toInt()
    private const val FIELD = 0xFF262C3A.toInt()
    const val CLAUDE_PACKAGE = "com.anthropic.claude"

    private fun levelColor(l: Coach.Level) = when (l) { Coach.Level.GOOD -> GOOD; Coach.Level.WATCH -> WATCH; Coach.Level.ACT -> ACT; Coach.Level.INFO -> DIM }

    /** The sheet's view plus what's needed to read it back (used by the dialog and by the screenshot test). */
    class Content(val view: View, val input: EditText, val readout: Coach.Readout)

    private fun px(a: Context, v: Int) = (v * a.resources.displayMetrics.density).roundToInt()

    /** The question to send: what was typed, else the first suggestion. */
    fun question(c: Content): String = c.input.text.toString().trim().ifEmpty { c.readout.questions.firstOrNull().orEmpty() }

    /** The sheet's content, without a dialog around it. [onSent] runs after "Talk it through" or "Copy". */
    fun content(a: Activity, r: Coach.Readout, onSent: (Boolean) -> Unit = {}): Content {
        fun dp(v: Int) = px(a, v)
        val input = EditText(a).apply {
            hint = "Your question (optional)"
            setHintTextColor(FAINT); setTextColor(TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2; maxLines = 5; gravity = Gravity.TOP or Gravity.START
            background = GradientDrawable().apply { setColor(FIELD); cornerRadius = dp(10).toFloat() }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(14)) }
        col.addView(TextView(a).apply {
            text = "Coach · ${r.title}"; setTextColor(TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        for (line in r.lines) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(9), 0, 0) }
            row.addView(TextView(a).apply { text = "●"; setTextColor(levelColor(line.level)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f); setPadding(0, dp(4), dp(9), 0) })
            row.addView(TextView(a).apply {
                text = line.text; setTextColor(TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setLineSpacing(dp(2).toFloat(), 1f)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            col.addView(row)
        }
        col.addView(TextView(a).apply {
            text = "ASK"; setTextColor(DIM); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); letterSpacing = 0.08f; setPadding(0, dp(18), 0, dp(2))
        })
        for (q in r.questions) {
            col.addView(TextView(a).apply {
                text = q; setTextColor(LINK); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(7), 0, dp(7))
                setOnClickListener { input.setText(q); input.setSelection(input.text.length) }
            })
        }
        col.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        val content = Content(ScrollView(a).apply { isVerticalScrollBarEnabled = false; addView(col); setBackgroundColor(PANEL) }, input, r)

        fun button(t: String, filled: Boolean, onClick: () -> Unit) = TextView(a).apply {
            text = t; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (filled) 0xFF0F1218.toInt() else LINK)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                if (filled) setColor(LINK) else { setColor(Color.TRANSPARENT); setStroke(dp(1), LINK) }
            }
            setPadding(dp(12), dp(11), dp(12), dp(11))
            setOnClickListener { onClick() }
        }
        val buttons = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0) }
        buttons.addView(button("Talk it through", true) { talk(a, Coach.prompt(a, r, question(content))); onSent(true) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f).apply { rightMargin = dp(10) })
        buttons.addView(button("Copy", false) { copy(a, Coach.prompt(a, r, question(content))); onSent(false) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(buttons)
        col.addView(TextView(a).apply {
            text = "Nothing is sent until you tap Talk it through."; setTextColor(FAINT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f); setPadding(0, dp(8), 0, 0)
        })
        return content
    }

    /** Hands [text] to the Claude app through the share sheet; the chooser if the app isn't installed. */
    fun talk(a: Activity, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        val installed = runCatching { a.packageManager.getPackageInfo(CLAUDE_PACKAGE, 0); true }.getOrDefault(false)
        val ok = installed && runCatching { a.startActivity(Intent(send).setPackage(CLAUDE_PACKAGE)) }.isSuccess
        if (!ok) runCatching { a.startActivity(Intent.createChooser(send, "Talk it through with…")) }
            .onFailure { Toast.makeText(a, "No app to share with.", Toast.LENGTH_SHORT).show() }
    }

    fun copy(a: Activity, text: String) {
        runCatching { (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Coach prompt", text)) }
        Toast.makeText(a, "Copied", Toast.LENGTH_SHORT).show()
    }

    /** Opens the sheet, or says there isn't enough data. */
    fun show(a: Activity, r: Coach.Readout?) {
        if (r == null) { Toast.makeText(a, "Not enough data yet.", Toast.LENGTH_SHORT).show(); return }
        lateinit var dialog: AlertDialog
        val c = content(a, r) { dialog.dismiss() }
        dialog = AlertDialog.Builder(a).setView(c.view).create()
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply { setColor(PANEL); cornerRadius = px(a, 16).toFloat() })
        dialog.show()
    }

    // ---------------------------------------------------------------- entry points

    /** The small right-aligned "Coach ›" text link. */
    fun link(a: Activity, readout: () -> Coach.Readout?): TextView = TextView(a).apply {
        text = "Coach ›"; setTextColor(LINK); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f); gravity = Gravity.END
        setPadding(px(a, 12), px(a, 8), 0, px(a, 4))
        setOnClickListener { show(a, readout()) }
    }

    /** Long-press anywhere on [v] opens the sheet. */
    fun longPress(a: Activity, v: View, readout: () -> Coach.Readout?) {
        v.setOnLongClickListener { show(a, readout()); true }
    }

    /** Link as the last row inside [card], plus long-press on the card. */
    fun attach(a: Activity, card: LinearLayout, readout: () -> Coach.Readout?) {
        card.addView(link(a, readout), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        longPress(a, card, readout)
    }

    /** A slim row with the link directly below a card (for cards built elsewhere), plus long-press on [card]. */
    fun attachBelow(a: Activity, parent: LinearLayout, card: View, readout: () -> Coach.Readout?) {
        longPress(a, card, readout)
        parent.addView(link(a, readout).apply { setPadding(px(a, 12), px(a, 2), px(a, 8), px(a, 4)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}
