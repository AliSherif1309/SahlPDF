package com.gitlab.mudlej.MjPdfReader.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.gitlab.mudlej.MjPdfReader.R
import com.google.android.material.color.MaterialColors

class ScanCropView(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private var points = ScanGeometry.fullPage()
    private var selected = -1
    private val bounds = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val accent = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, Color.CYAN)
    var onCornersChanged: ((List<ScanPoint>) -> Unit)? = null
    init {
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.scanner_crop_accessibility)
    }
    fun image(value: Bitmap?) {
        if (bitmap !== value) bitmap?.recycle()
        bitmap = value
        invalidate()
    }
    fun corners(value: List<ScanPoint>) { points = value.toList(); invalidate() }
    fun release() { image(null) }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(28, 28, 28))
        val image = bitmap ?: return
        val scale = minOf((width - 32 * resources.displayMetrics.density).coerceAtLeast(1f) / image.width,
            (height - 32 * resources.displayMetrics.density).coerceAtLeast(1f) / image.height)
        val w = image.width * scale; val h = image.height * scale
        bounds.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        canvas.drawBitmap(image, null, bounds, paint)
        val path = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(bounds, Path.Direction.CW)
            moveTo(px(points[0]), py(points[0]))
            points.drop(1).forEach { lineTo(px(it), py(it)) }
            close()
        }
        paint.color = Color.argb(145, 0, 0, 0)
        canvas.drawPath(path, paint)
        val outline = Path().apply {
            moveTo(px(points[0]), py(points[0]))
            points.drop(1).forEach { lineTo(px(it), py(it)) }
            close()
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3 * resources.displayMetrics.density
        paint.color = accent
        canvas.drawPath(outline, paint)
        paint.style = Paint.Style.FILL
        points.forEachIndexed { index, point ->
            paint.color = Color.WHITE
            canvas.drawCircle(px(point), py(point), 10 * resources.displayMetrics.density, paint)
            paint.color = Color.BLACK
            paint.textSize = 12 * resources.displayMetrics.scaledDensity
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(context.getString(R.string.scanner_corner_number, index + 1), px(point), py(point) + paint.textSize / 3, paint)
        }
        if (selected >= 0) {
            val radius = minOf(52 * resources.displayMetrics.density, width / 5f, height / 5f)
            val cx = if (points[selected].x < 0.5f) width - radius - 12 else radius + 12
            val cy = radius + 12
            val zoom = 3f
            canvas.save()
            canvas.clipPath(Path().apply { addCircle(cx, cy, radius, Path.Direction.CW) })
            canvas.drawColor(Color.LTGRAY)
            canvas.translate(cx - points[selected].x * image.width * zoom, cy - points[selected].y * image.height * zoom)
            canvas.scale(zoom, zoom)
            paint.color = Color.WHITE
            canvas.drawBitmap(image, 0f, 0f, paint)
            canvas.restore()
            paint.color = accent
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2 * resources.displayMetrics.density
            canvas.drawCircle(cx, cy, radius, paint)
            canvas.drawLine(cx - 10, cy, cx + 10, cy, paint)
            canvas.drawLine(cx, cy - 10, cx, cy + 10, paint)
            paint.style = Paint.Style.FILL
        }
    }
    private fun px(point: ScanPoint) = bounds.left + point.x * bounds.width()
    private fun py(point: ScanPoint) = bounds.top + point.y * bounds.height()
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || bitmap == null || bounds.isEmpty) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                selected = points.indices.minBy { kotlin.math.hypot(px(points[it]) - event.x, py(points[it]) - event.y) }
                if (kotlin.math.hypot(px(points[selected]) - event.x, py(points[selected]) - event.y) > 48 * resources.displayMetrics.density) {
                    selected = -1; return false
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (selected < 0) return false
                val next = points.toMutableList()
                next[selected] = ScanPoint(((event.x - bounds.left) / bounds.width()).coerceIn(0f, 1f),
                    ((event.y - bounds.top) / bounds.height()).coerceIn(0f, 1f))
                if (ScanGeometry.valid(next)) { points = next; invalidate() }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (selected < 0) return false
                selected = -1
                invalidate()
                onCornersChanged?.invoke(points.toList())
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                selected = -1
                onCornersChanged?.invoke(points.toList())
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
