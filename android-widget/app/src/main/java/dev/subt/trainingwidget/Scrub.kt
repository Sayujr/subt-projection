package dev.subt.trainingwidget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.widget.ImageView
import java.util.WeakHashMap

/** Points a chart drew (bitmap pixels) with what to show when you touch near them. */
object ChartHits {
    data class Hit(val x: Float, val y: Float, val label: String, val color: Int)
    private val byBitmap = WeakHashMap<Bitmap, List<Hit>>()
    private val byName = mutableMapOf<String, List<Hit>>()

    fun put(b: Bitmap, hits: List<Hit>, name: String? = null) {
        synchronized(this) { byBitmap[b] = hits; if (name != null) byName[name] = hits }
    }
    fun get(b: Bitmap): List<Hit>? = synchronized(this) { byBitmap[b] }
    fun named(name: String): List<Hit>? = synchronized(this) { byName[name] }
}

/**
 * Touch-and-drag on a chart: a line, ring and label for the nearest point, drawn on a copy of the
 * chart so the original is untouched. Lift your finger and the plain chart comes back.
 */
object Scrub {
    fun attach(iv: ImageView, original: Bitmap, hits: List<ChartHits.Hit>) {
        if (hits.isEmpty()) return
        val d = iv.resources.displayMetrics.density
        var lastI = -1
        iv.setOnTouchListener { v, e ->
            try {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        // view → bitmap coordinates (works for any scale type)
                        val inv = Matrix(); iv.imageMatrix.invert(inv)
                        val pt = floatArrayOf(e.x - iv.paddingLeft, e.y - iv.paddingTop); inv.mapPoints(pt)
                        val i = hits.indices.minByOrNull { kotlin.math.abs(hits[it].x - pt[0]) } ?: return@setOnTouchListener true
                        if (i != lastI) { lastI = i; iv.setImageBitmap(draw(original, hits[i], d)) }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { lastI = -1; iv.setImageBitmap(original); true }
                    else -> true
                }
            } catch (t: Throwable) { true }
        }
    }

    private fun draw(src: Bitmap, h: ChartHits.Hit, d: Float): Bitmap {
        val b = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(b)
        c.drawLine(h.x, 0f, h.x, b.height.toFloat(), Paint().apply { color = 0x88FFFFFF.toInt(); strokeWidth = 1.2f * d })
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFF161A22.toInt(); c.drawCircle(h.x, h.y, 5f * d, p)
        p.color = h.color; c.drawCircle(h.x, h.y, 3.6f * d, p)
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2F4F8.toInt(); textSize = 11f * d; isFakeBoldText = true }
        val tw = t.measureText(h.label)
        val pad = 6f * d
        val left = (h.x - tw / 2 - pad).coerceIn(0f, (b.width - tw - 2 * pad).coerceAtLeast(0f))
        // label above the point, or below it when the point is near the top
        val top = if (h.y > 30f * d) (h.y - 28f * d) else (h.y + 10f * d)
        val r = RectF(left, top, left + tw + 2 * pad, top + 18f * d)
        c.drawRoundRect(r, 6f * d, 6f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEE222733.toInt() })
        c.drawRoundRect(r, 6f * d, 6f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = h.color; style = Paint.Style.STROKE; strokeWidth = 1f * d })
        c.drawText(h.label, r.left + pad, r.top + 13f * d, t)
        return b
    }
}
