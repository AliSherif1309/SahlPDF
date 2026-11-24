package com.sahl.pdf

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Shared overlay renderer for markups, sticky notes, freehand ink, and shapes/text.
 * Used by both [PdfView] (live display) and [PdfExporter] (flattened PDF export).
 */
class AnnotationDraw(private val unit: Float) {
    private val annPaint = Paint()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val noteDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF5C84C.toInt() }
    private val path = Path()

    fun drawNormRects(
        canvas: Canvas,
        pageDst: RectF,
        crop: NormRect,
        rects: List<NormRect>,
        color: Int,
        dot: Boolean = false,
        style: String = "highlight",
    ) {
        val cw = max(0.05f, crop.width)
        val ch = max(0.05f, crop.height)
        for (idx in rects.indices) {
            val nr = rects[idx]
            val rl = ((nr.left - crop.left) / cw).coerceIn(0f, 1f)
            val rt = ((nr.top - crop.top) / ch).coerceIn(0f, 1f)
            val rr = ((nr.right - crop.left) / cw).coerceIn(0f, 1f)
            val rb = ((nr.bottom - crop.top) / ch).coerceIn(0f, 1f)
            if (rr <= rl || rb <= rt) continue
            val l = pageDst.left + rl * pageDst.width()
            val t = pageDst.top + rt * pageDst.height()
            val r = pageDst.left + rr * pageDst.width()
            val b = pageDst.top + rb * pageDst.height()
            when (style) {
                "underline" -> {
                    linePaint.color = color or 0xFF000000.toInt()
                    linePaint.strokeWidth = 2.2f * unit
                    canvas.drawLine(l, b, r, b, linePaint)
                }
                "strikeout" -> {
                    linePaint.color = color or 0xFF000000.toInt()
                    linePaint.strokeWidth = 2.2f * unit
                    val mid = (t + b) / 2f
                    canvas.drawLine(l, mid, r, mid, linePaint)
                }
                "squiggly" -> {
                    linePaint.color = color or 0xFF000000.toInt()
                    linePaint.strokeWidth = 1.8f * unit
                    path.reset()
                    path.moveTo(l, b)
                    val step = 5f * unit
                    var x = l
                    var up = true
                    while (x < r) {
                        val nx = min(r, x + step)
                        path.lineTo(nx, if (up) b - 2.5f * unit else b + 1f * unit)
                        x = nx; up = !up
                    }
                    canvas.drawPath(path, linePaint)
                }
                "sticky" -> {
                    val sz = 18f * unit
                    annPaint.color = color or 0xFF000000.toInt()
                    canvas.drawRoundRect(l, t, l + sz, t + sz, 4f * unit, 4f * unit, annPaint)
                    linePaint.color = Color.WHITE; linePaint.strokeWidth = 1.5f * unit
                    canvas.drawLine(l + 4f * unit, t + 6f * unit, l + sz - 4f * unit, t + 6f * unit, linePaint)
                    canvas.drawLine(l + 4f * unit, t + 10f * unit, l + sz - 4f * unit, t + 10f * unit, linePaint)
                    canvas.drawLine(l + 4f * unit, t + 14f * unit, l + sz - 7f * unit, t + 14f * unit, linePaint)
                }
                else -> {
                    annPaint.color = color
                    canvas.drawRect(l, t, r, b, annPaint)
                }
            }
            if (dot && idx == 0 && style != "sticky") canvas.drawCircle(r, t, 4 * unit, noteDotPaint)
        }
    }

    fun drawInk(canvas: Canvas, pageDst: RectF, crop: NormRect, color: Int, normW: Float, pts: List<Float>) {
        if (pts.size < 2) return
        val cw = max(0.05f, crop.width)
        val ch = max(0.05f, crop.height)
        val fullW = pageDst.width() / cw
        inkPaint.color = color
        inkPaint.strokeWidth = max(1.5f, normW * fullW)
        path.reset()
        for (k in 0 until pts.size - 1 step 2) {
            val sx = pageDst.left + ((pts[k] - crop.left) / cw) * pageDst.width()
            val sy = pageDst.top + ((pts[k + 1] - crop.top) / ch) * pageDst.height()
            if (k == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
        }
        if (pts.size == 2) canvas.drawPoint(
            pageDst.left + ((pts[0] - crop.left) / cw) * pageDst.width(),
            pageDst.top + ((pts[1] - crop.top) / ch) * pageDst.height(),
            inkPaint,
        ) else canvas.drawPath(path, inkPaint)
    }

    fun drawShape(
        canvas: Canvas,
        pageDst: RectF,
        crop: NormRect,
        kind: String,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        color: Int,
        normW: Float,
        text: String = "",
    ) {
        val cw = max(0.05f, crop.width)
        val ch = max(0.05f, crop.height)
        val fullW = pageDst.width() / cw
        val sx1 = pageDst.left + ((x1 - crop.left) / cw) * pageDst.width()
        val sy1 = pageDst.top + ((y1 - crop.top) / ch) * pageDst.height()
        val sx2 = pageDst.left + ((x2 - crop.left) / cw) * pageDst.width()
        val sy2 = pageDst.top + ((y2 - crop.top) / ch) * pageDst.height()
        linePaint.color = color or 0xFF000000.toInt()
        linePaint.strokeWidth = max(1.5f, normW * fullW)
        when (kind) {
            "rect" -> canvas.drawRect(min(sx1, sx2), min(sy1, sy2), max(sx1, sx2), max(sy1, sy2), linePaint)
            "circle" -> canvas.drawOval(min(sx1, sx2), min(sy1, sy2), max(sx1, sx2), max(sy1, sy2), linePaint)
            "arrow" -> {
                canvas.drawLine(sx1, sy1, sx2, sy2, linePaint)
                val angle = atan2((sy2 - sy1).toDouble(), (sx2 - sx1).toDouble())
                val head = max(10f * unit, linePaint.strokeWidth * 3.5f)
                val a1 = angle - Math.PI / 6
                val a2 = angle + Math.PI / 6
                canvas.drawLine(sx2, sy2, (sx2 - head * cos(a1)).toFloat(), (sy2 - head * sin(a1)).toFloat(), linePaint)
                canvas.drawLine(sx2, sy2, (sx2 - head * cos(a2)).toFloat(), (sy2 - head * sin(a2)).toFloat(), linePaint)
            }
            "text" -> {
                textPaint.color = color or 0xFF000000.toInt()
                textPaint.textSize = max(12f, fullW * 0.028f)
                val lines = text.split('\n')
                val lh = textPaint.fontSpacing
                for ((li, ln) in lines.withIndex()) {
                    canvas.drawText(ln, sx1, sy1 + li * lh, textPaint)
                }
            }
        }
    }

    fun drawPageOverlays(
        canvas: Canvas,
        pageIdx: Int,
        pageDst: RectF,
        crop: NormRect,
        annotations: List<Library.AnnotationEntry>,
        inks: List<Library.InkStroke>,
        shapes: List<Library.ShapeEntry>,
    ) {
        for (a in annotations) {
            if (a.page == pageIdx) drawNormRects(canvas, pageDst, crop, a.rects, a.color, dot = a.note.isNotBlank(), style = a.style)
        }
        for (s in inks) {
            if (s.page == pageIdx) drawInk(canvas, pageDst, crop, s.color, s.width, s.points)
        }
        for (sh in shapes) {
            if (sh.page == pageIdx) drawShape(canvas, pageDst, crop, sh.kind, sh.x1, sh.y1, sh.x2, sh.y2, sh.color, sh.width, sh.text)
        }
    }
}
