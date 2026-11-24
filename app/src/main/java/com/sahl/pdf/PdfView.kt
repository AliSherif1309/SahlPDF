package com.sahl.pdf

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Continuous vertical PDF view with pinch / double-tap zoom and fling.
 * Each visible page is drawn from a whole-page bitmap; when zoomed in, a sharp
 * "detail" bitmap of just the visible part is rendered on top once movement settles.
 */
@SuppressLint("ViewConstructor")
class PdfView(context: Context, private val renderer: Renderer) : View(context) {

    interface Listener {
        fun onPageChanged(page: Int, count: Int)
        fun onTap()
    }

    var listener: Listener? = null

    private var doc: PdfDoc? = null
    private var layout: PageLayout? = null
    private val dp = resources.displayMetrics.density

    var zoom = 1f; private set
    private var sx = 0f
    private var sy = 0f
    private var lowW = 0
    private var currentPage = -1
    private var pendingPage = -1
    private var pendingFraction = 0f

    private val scroller = OverScroller(context)
    private var zoomAnim: ValueAnimator? = null

    private val cache = object : LruCache<Int, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    private class Detail(val zoom: Float, val rect: RectF, val bmp: Bitmap)
    private val details = HashMap<Int, Detail>()

    private val pagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val placeholder = Paint().apply { color = Color.WHITE }
    private val shadow = Paint().apply { color = 0x22000000 }
    private val dst = RectF()

    var night = false
        set(v) {
            field = v
            pagePaint.colorFilter = if (v) ColorMatrixColorFilter(INVERT) else null
            placeholder.color = if (v) Color.rgb(28, 28, 28) else Color.WHITE
            invalidate()
        }

    var paperBg = Color.rgb(0x3A, 0x3F, 0x3C)
        set(v) { field = v; invalidate() }

    // ---- document -----------------------------------------------------------------------

    fun setDocument(d: PdfDoc, startPage: Int) {
        doc = d
        layout = PageLayout(d.sizes, 6 * dp)
        cache.evictAll(); details.clear()
        zoom = 1f; sx = 0f; sy = 0f
        pendingPage = startPage.coerceIn(0, d.pageCount - 1)
        pendingFraction = 0f
        currentPage = -1
        if (width > 0) relayout()
        invalidate()
    }

    val pageCount get() = doc?.pageCount ?: 0
    val page get() = max(currentPage, 0)

    fun goTo(page: Int) {
        val l = layout ?: return
        if (width == 0) { pendingPage = page; pendingFraction = 0f; return }
        stopMotion()
        sy = l.top(page.coerceIn(0, l.count - 1)) * zoom - 6 * dp
        clamp(); notifyPage(); invalidate()
    }

    fun scrollByAnimated(dy: Float) {
        stopMotion()
        val target = (sy + dy).coerceIn(0f, maxY())
        scroller.startScroll(sx.roundToInt(), sy.roundToInt(), 0, (target - sy).roundToInt(), 220)
        postInvalidateOnAnimation()
    }

    // ---- layout ------------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (layout != null && oldw > 0 && pendingPage < 0) {
            val l = layout!!
            val p = page
            pendingPage = p
            pendingFraction = ((sy / zoom) - l.top(p)) / max(1f, l.height(p))
        }
        relayout()
    }

    private fun relayout() {
        val l = layout ?: return
        if (width <= 0) return
        l.layout(width.toFloat())
        val newLow = min(width, 1280)
        if (newLow != lowW) { cache.evictAll(); lowW = newLow }
        details.clear()
        if (pendingPage >= 0) {
            val p = pendingPage.coerceIn(0, l.count - 1)
            sy = (l.top(p) + pendingFraction * l.height(p)) * zoom - if (pendingFraction == 0f) 6 * dp else 0f
            pendingPage = -1
        }
        clamp(); notifyPage(); invalidate()
    }

    private fun maxX() = max(0f, (layout?.width ?: 0f) * zoom - width)
    private fun maxY() = max(0f, (layout?.totalHeight ?: 0f) * zoom - height)
    private fun clamp() { sx = sx.coerceIn(0f, maxX()); sy = sy.coerceIn(0f, maxY()) }

    private fun notifyPage() {
        val l = layout ?: return
        val p = if (sy >= maxY() - 1f && maxY() > 0f) l.count - 1 else l.pageAt((sy + height * 0.4f) / zoom)
        if (p != currentPage) {
            currentPage = p
            listener?.onPageChanged(p, l.count)
        }
    }

    // ---- drawing -----------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(paperBg)
        val l = layout ?: return
        if (l.width <= 0f) return
        val range = l.visible(sy / zoom, (sy + height) / zoom)
        val pageW = l.width * zoom
        for (i in range) {
            val top = l.top(i) * zoom - sy
            dst.set(-sx, top, -sx + pageW, top + l.height(i) * zoom)
            canvas.drawRect(dst.left + 2 * dp, dst.top + 2 * dp, dst.right + 2 * dp, dst.bottom + 2 * dp, shadow)
            val low = cache.get(i)
            if (low != null) canvas.drawBitmap(low, null, dst, pagePaint) else canvas.drawRect(dst, placeholder)
            val d = details[i]
            if (d != null && d.zoom * l.width > lowW * 1.05f) {
                val r = RectF(dst.left + d.rect.left * zoom, dst.top + d.rect.top * zoom,
                    dst.left + d.rect.right * zoom, dst.top + d.rect.bottom * zoom)
                canvas.drawBitmap(d.bmp, null, r, pagePaint)
            }
        }
        requestRenders(range)
    }

    private fun settled() = scroller.isFinished && !scaling && zoomAnim?.isRunning != true

    private fun requestRenders(range: IntRange) {
        val d = doc ?: return
        val l = layout ?: return
        val jobs = ArrayList<Renderer.Job>()
        val w = lowW
        for (i in range) if (cache.get(i) == null) jobs += lowJob(d, l, i, w)

        val needDetail = l.width * zoom > w * 1.05f
        if (!needDetail) {
            if (details.isNotEmpty()) details.clear()
        } else if (settled()) {
            for (i in range) {
                val want = visibleRect(l, i) ?: continue
                val have = details[i]
                if (have != null && have.zoom == zoom && covers(have.rect, want)) continue
                jobs += detailJob(d, l, i, expand(l, i, want))
            }
            details.keys.retainAll { it in range }
        }
        if (!range.isEmpty()) {
            for (i in listOf(range.last + 1, range.first - 1, range.last + 2)) {
                if (i in 0 until l.count && cache.get(i) == null) jobs += lowJob(d, l, i, w)
            }
        }
        renderer.submit(jobs)
    }

    private fun lowJob(d: PdfDoc, l: PageLayout, i: Int, w: Int): Renderer.Job {
        var bw = w
        var bh = (w * l.height(i) / l.width).roundToInt()
        if (bh > 4096) { bw = (bw * 4096f / bh).roundToInt(); bh = 4096 }
        val ww = max(1, bw); val hh = max(1, bh)
        return Renderer.Job("low:$i:$w", work = {
            Bitmap.createBitmap(ww, hh, Bitmap.Config.ARGB_8888).also { d.render(i, it, 0, 0, ww, hh) }
        }, done = { bmp ->
            if (bmp != null && doc === d && lowW == w) { cache.put(i, bmp); invalidate() }
            else if (bmp == null) cache.trimToSize(cache.size() / 2)
        })
    }

    private fun detailJob(d: PdfDoc, l: PageLayout, i: Int, r: RectF): Renderer.Job {
        val z = zoom
        val pw = (l.width * z).roundToInt()
        val ph = (l.height(i) * z).roundToInt()
        val left = (r.left * z).roundToInt()
        val top = (r.top * z).roundToInt()
        val bw = max(1, (r.width() * z).roundToInt())
        val bh = max(1, (r.height() * z).roundToInt())
        val rect = RectF(left / z, top / z, (left + bw) / z, (top + bh) / z)
        return Renderer.Job("detail:$i:$z:${rect.left.toInt()}:${rect.top.toInt()}", work = {
            Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also { d.render(i, it, -left, -top, pw, ph) }
        }, done = { bmp ->
            if (bmp != null && doc === d && zoom == z) { details[i] = Detail(z, rect, bmp); invalidate() }
        })
    }

    private fun covers(have: RectF, want: RectF): Boolean {
        val e = 1.5f / zoom
        return have.left <= want.left + e && have.top <= want.top + e &&
            have.right >= want.right - e && have.bottom >= want.bottom - e
    }

    /** Visible part of page [i] in page-local base units, or null. */
    private fun visibleRect(l: PageLayout, i: Int): RectF? {
        val r = RectF(sx / zoom, sy / zoom - l.top(i), (sx + width) / zoom, (sy + height) / zoom - l.top(i))
        return if (r.intersect(0f, 0f, l.width, l.height(i))) r else null
    }

    private fun expand(l: PageLayout, i: Int, r: RectF): RectF {
        val mx = r.width() * 0.1f
        val my = r.height() * 0.1f
        return RectF(max(0f, r.left - mx), max(0f, r.top - my), min(l.width, r.right + mx), min(l.height(i), r.bottom + my))
    }

    // ---- gestures ----------------------------------------------------------------------

    private var scaling = false

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { scaling = true; stopMotion(); return true }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomTo(zoom * detector.scaleFactor, detector.focusX, detector.focusY); return true
        }
        override fun onScaleEnd(detector: ScaleGestureDetector) { scaling = false; invalidate() }
    }).apply { isQuickScaleEnabled = false }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { stopMotion(); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaling) return true
            sx += dx; sy += dy; clamp(); notifyPage(); invalidate(); return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scaling) return true
            scroller.fling(sx.roundToInt(), sy.roundToInt(), -vx.roundToInt(), -vy.roundToInt(),
                0, maxX().roundToInt(), 0, maxY().roundToInt())
            postInvalidateOnAnimation(); return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { listener?.onTap(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            animateZoom(if (zoom < 1.6f) 2.5f else 1f, e.x, e.y); return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scale.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) invalidate()
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            sx = scroller.currX.toFloat(); sy = scroller.currY.toFloat()
            clamp(); notifyPage()
            postInvalidateOnAnimation()
            if (scroller.isFinished) invalidate()
        }
    }

    private fun stopMotion() {
        if (!scroller.isFinished) scroller.forceFinished(true)
        zoomAnim?.cancel()
    }

    fun zoomTo(target: Float, fx: Float, fy: Float) {
        val nz = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (nz == zoom) return
        val k = nz / zoom
        sx = (sx + fx) * k - fx
        sy = (sy + fy) * k - fy
        zoom = nz
        clamp(); notifyPage(); invalidate()
    }

    fun animateZoom(target: Float, fx: Float = width / 2f, fy: Float = height / 2f) {
        stopMotion()
        zoomAnim = ValueAnimator.ofFloat(zoom, target).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { zoomTo(it.animatedValue as Float, fx, fy) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { post { invalidate() } }
            })
            start()
        }
    }

    fun release() {
        doc = null
        cache.evictAll(); details.clear()
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 6f
        private val INVERT = ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ))

        private fun cacheBytes(): Int =
            (Runtime.getRuntime().maxMemory() / 5).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
