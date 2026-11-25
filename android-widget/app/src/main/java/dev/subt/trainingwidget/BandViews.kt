package dev.subt.trainingwidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

/** Heart-rate band table (stacked bar + rows), shared by the Week screen and the workout detail screen. */
object BandViews {
    const val GREEN = 0xFF3DD68C.toInt()
    const val GREY = 0xFF6B7386.toInt()
    const val ORANGE = 0xFFFF9F5A.toInt()
    const val RED = 0xFFFF7A7A.toInt()
    private const val TEXT = 0xFFF2F4F8.toInt()
    private const val DIM = 0xFF9AA3B2.toInt()
    private const val FAINT = 0xFF6B7386.toInt()

    fun bar(ctx: Context, sec: List<Double>, widthPx: Int): Bitmap {
        fun s(i: Int) = sec.getOrElse(i) { 0.0 }
        return strip(ctx, listOf(s(0) + s(1), s(2), s(3) + s(4), s(5) + s(6)), listOf(GREEN, GREY, ORANGE, RED), widthPx)
    }

    /** A rounded bar split into parts (any weights) with the given colours. */
    fun strip(ctx: Context, parts: List<Double>, cols: List<Int>, widthPx: Int): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val h = (10 * d).roundToInt()
        val w = widthPx.coerceAtLeast(40)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = h / 2f
        p.color = 0xFF3A4150.toInt()
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, p)
        val sum = parts.sum()
        if (sum > 0) {
            c.save()
            val clip = android.graphics.Path().apply { addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, android.graphics.Path.Direction.CW) }
            c.clipPath(clip)
            var x = 0f
            for (i in parts.indices) {
                val x1 = if (i == parts.size - 1) w.toFloat() else x + (parts[i] / sum * w).toFloat()
                p.color = cols[i]
                c.drawRect(x, 0f, x1, h.toFloat(), p)
                x = x1
            }
            c.restore()
        }
        return bmp
    }

    fun stripView(ctx: Context, parts: List<Double>, cols: List<Int>, widthPx: Int): ImageView {
        val iv = ImageView(ctx).apply { setImageBitmap(strip(ctx, parts, cols, widthPx)); scaleType = ImageView.ScaleType.FIT_XY }
        val d = ctx.resources.displayMetrics.density
        iv.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (10 * d).roundToInt()).apply { topMargin = (8 * d).roundToInt() }
        return iv
    }

    /** One table row: label, time, share (0–100, null = hidden), distance (null = hidden). */
    fun statRow(ctx: Context, label: String, sec: Double, pct: Double?, m: Double?, emphasised: Boolean = false, decimals: Int = 1, pctColor: Int? = null): LinearLayout {
        val d = ctx.resources.displayMetrics.density
        val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, (3 * d).roundToInt(), 0, (3 * d).roundToInt()) }
        val c = if (emphasised) TEXT else 0xFFD3D8E2.toInt()
        val sp = if (emphasised) 15f else 14f
        line.addView(cell(ctx, label, sp, c, 0, emphasised, Gravity.START))
        line.addView(cell(ctx, if (sec > 0) SubtModel.time(sec) else "–", sp, c, 66, emphasised, Gravity.END))
        if (pct != null) line.addView(cell(ctx, if (sec > 0) "${pct.roundToInt()}%" else "–", sp, pctColor ?: c, 48, emphasised, Gravity.END))
        if (m != null) line.addView(cell(ctx, if (m > 0) String.format(Locale.US, "%.${decimals}f km", m / 1000) else "–", sp, c, 66, emphasised, Gravity.END))
        return line
    }

    /** Small heading with a tappable "ⓘ" that shows or hides a short note underneath. */
    fun heading(ctx: Context, parent: LinearLayout, title: String, note: String?, color: Int = DIM, size: Float = 11f, topDp: Int = 0) {
        val d = ctx.resources.displayMetrics.density
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, (topDp * d).roundToInt(), 0, 0) }
        row.addView(TextView(ctx).apply { text = title; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color); letterSpacing = 0.08f })
        parent.addView(row)
        if (note == null) return
        val tv = TextView(ctx).apply {
            text = note; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setTextColor(DIM); setPadding(0, (4 * d).roundToInt(), 0, (2 * d).roundToInt())
            visibility = View.GONE
        }
        row.addView(TextView(ctx).apply {
            text = "ⓘ"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(FAINT); setPadding((10 * d).roundToInt(), (2 * d).roundToInt(), (10 * d).roundToInt(), (2 * d).roundToInt())
            contentDescription = "Explain $title"
            setOnClickListener { tv.visibility = if (tv.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        })
        parent.addView(tv)
    }

    private fun cell(ctx: Context, t: String, sp: Float, color: Int, widthDp: Int, bold: Boolean, gravity: Int): TextView =
        TextView(ctx).apply {
            text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color); this.gravity = gravity
            maxLines = 1
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = if (widthDp > 0) LinearLayout.LayoutParams((widthDp * ctx.resources.displayMetrics.density).roundToInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

    /** Stacked bar, then rows grouped under small headings, then the overlap note. */
    fun build(ctx: Context, sec: List<Double>, m: List<Double>, widthPx: Int, decimals: Int = 1): LinearLayout {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).roundToInt()
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val iv = ImageView(ctx).apply { setImageBitmap(bar(ctx, sec, widthPx)); scaleType = ImageView.ScaleType.FIT_XY }
        box.addView(iv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply { topMargin = dp(8) })
        val rows = Zones.bandRows(sec, m)
        var group = ""
        for (r in rows) {
            if (r.group != group) {
                group = r.group
                val color = when (group) { "EASY" -> GREEN; "IN BETWEEN" -> GREY; "SUBT BAND" -> ORANGE; else -> RED }
                box.addView(TextView(ctx).apply {
                    text = group; setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f); setTextColor(color); letterSpacing = 0.08f
                    setPadding(0, dp(10), 0, dp(1))
                })
            }
            val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(3), 0, dp(3)) }
            val c = if (r.emphasised) TEXT else 0xFFD3D8E2.toInt()
            val sp = if (r.emphasised) 15f else 14f
            line.addView(cell(ctx, r.label, sp, c, 0, r.emphasised, Gravity.START))
            line.addView(cell(ctx, if (r.sec > 0) SubtModel.time(r.sec) else "–", sp, c, 66, r.emphasised, Gravity.END))
            line.addView(cell(ctx, if (r.sec > 0) "${r.pct.roundToInt()}%" else "–", sp, c, 48, r.emphasised, Gravity.END))
            line.addView(cell(ctx, if (r.m > 0) String.format(Locale.US, "%.${decimals}f km", r.m / 1000) else "–", sp, c, 66, r.emphasised, Gravity.END))
            box.addView(line)
        }
        return box
    }
}
