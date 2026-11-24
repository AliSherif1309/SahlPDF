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
 * PDF view supporting vertical continuous scroll, horizontal LTR/RTL page-by-page,
 * fit-width / fit-page, margin cropping, pinch / double-tap zoom, sharp detail tiles,
 * night pages, and search highlights.
 */
@SuppressLint("ViewConstructor")
class PdfView(context: Context, private val renderer: Renderer) : View(context) {

    enum class ToolMode { NONE, PEN, MARKER, ERASER, STICKY }

    interface Listener {
        fun onPageChanged(page: Int, count: Int)
        fun onTap()
        fun onLinkTap(link: PageLink)
        fun onSelectionChanged(selection: TextSelection?)
        fun onAnnotationTap(entry: Library.AnnotationEntry)
        fun onInkAdded(page: Int, color: Int, width: Float, points: List<Float>)
        fun onInkErased(id: String)
        fun onStickyTap(page: Int, nx: Float, ny: Float)
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

    var flowMode = FlowMode.VERTICAL
        set(v) {
            if (field == v) return
            field = v
            relayoutKeepingPage()
        }

    var fitMode = FitMode.WIDTH
        set(v) {
            if (field == v) return
            field = v
            relayoutKeepingPage()
        }

    var twoPage = false
        set(v) {
            if (field == v) return
            field = v
            relayoutKeepingPage()
        }

    var cropMargins = false
        set(v) {
            if (field == v) return
            field = v
            cache.evictAll()
            details.clear()
            relayoutKeepingPage()
        }

    private val crops = HashMap<Int, NormRect>()
    private val cropPending = HashSet<Int>()

    private var searchHits: List<SearchHit> = emptyList()
    private var activeHitIndex = -1
    private var annotations: List<Library.AnnotationEntry> = emptyList()
    private var inks: List<Library.InkStroke> = emptyList()
    var selection: TextSelection? = null; private set
    var toolMode = ToolMode.NONE
    var penColor: Int = 0xFF0E6B4E.toInt()
    var penWidth: Float = 0.004f
    private var activeInkPage = -1
    private val activeInkPts = ArrayList<Float>()
    private val linksCache = HashMap<Int, List<PageLink>>()
    private val linksPending = HashSet<Int>()

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
    private val hitPaint = Paint().apply { color = 0x55F5C84C }
    private val activeHitPaint = Paint().apply { color = 0x99F5C84C.toInt() }
    private val annPaint = Paint()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val inkPath = android.graphics.Path()
    private val noteDotPaint = Paint().apply { color = 0xFFF5C84C.toInt() }
    private val selPaint = Paint().apply { color = 0x66F5C84C }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0E6B4E.toInt() }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.5f * dp
    }
    private var handleStartX = -1f
    private var handleStartY = -1f
    private var handleEndX = -1f
    private var handleEndY = -1f
    private var dragHandle = 0 // 0 = none, 1 = start, 2 = end
    var selectionColor: Int
        get() = selPaint.color
        set(v) { selPaint.color = v; invalidate() }
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
        crops.clear(); cropPending.clear()
        linksCache.clear(); linksPending.clear()
        searchHits = emptyList(); activeHitIndex = -1
        selection = null
        cache.evictAll(); details.clear()
        zoom = 1f; sx = 0f; sy = 0f
        pendingPage = startPage.coerceIn(0, d.pageCount - 1)
        pendingFraction = 0f
        currentPage = -1
        buildLayout()
        if (width > 0 && height > 0) relayout()
        invalidate()
    }

    fun setAnnotations(list: List<Library.AnnotationEntry>) {
        annotations = list
        invalidate()
    }

    fun setInks(list: List<Library.InkStroke>) {
        inks = list
        invalidate()
    }

    fun clearSelection() {
        if (selection == null) return
        selection = null
        listener?.onSelectionChanged(null)
        invalidate()
    }

    val pageCount get() = doc?.pageCount ?: 0
    val page get() = max(currentPage, 0)

    fun setSearchResults(hits: List<SearchHit>, active: Int) {
        searchHits = hits
        activeHitIndex = active
        if (active in hits.indices) focusHit(hits[active])
        invalidate()
    }

    fun focusHit(hit: SearchHit) {
        val l = layout ?: return
        val p = hit.page.coerceIn(0, l.count - 1)
        if (width <= 0 || height <= 0) { pendingPage = p; pendingFraction = 0f; return }
        stopMotion()
        val r = hit.rects.firstOrNull()
        val crop = cropFor(p)
        val nx = if (r != null) ((r.left + r.right) / 2f - crop.left) / max(0.05f, crop.width) else 0.5f
        val ny = if (r != null) ((r.top + r.bottom) / 2f - crop.top) / max(0.05f, crop.height) else 0.5f
        if (flowMode == FlowMode.VERTICAL) {
            val targetY = (l.top(p) + ny.coerceIn(0f, 1f) * l.height(p)) * zoom - height * 0.4f
            sy = targetY
            sx = (l.left(p) + nx.coerceIn(0f, 1f) * l.width(p)) * zoom - width * 0.5f
        } else {
            sx = if (zoom <= 1.01f) l.snapX(p) * zoom
            else (l.left(p) + nx.coerceIn(0f, 1f) * l.width(p)) * zoom - width * 0.5f
            sy = (l.top(p) + ny.coerceIn(0f, 1f) * l.height(p)) * zoom - height * 0.4f
        }
        clamp(); notifyPage(); invalidate()
    }

    fun goTo(page: Int) {
        val l = layout ?: return
        val p = page.coerceIn(0, l.count - 1)
        if (width <= 0 || height <= 0) { pendingPage = p; pendingFraction = 0f; return }
        stopMotion()
        if (flowMode == FlowMode.VERTICAL) {
            sy = l.top(p) * zoom - 6 * dp
        } else {
            sx = l.snapX(p) * zoom
            sy = max(0f, l.top(p) * zoom - (height - l.height(p) * zoom) / 2f)
        }
        clamp(); notifyPage(); invalidate()
    }

    fun stepPage(cur: Int = page, dir: Int): Int {
        val n = pageCount
        if (n <= 0 || dir == 0) return cur.coerceIn(0, max(0, n - 1))
        val next = if (!twoPage || flowMode == FlowMode.VERTICAL) {
            cur + dir
        } else if (dir > 0) {
            when {
                cur <= 0 -> 1
                cur % 2 == 1 -> cur + 2
                else -> cur + 1
            }
        } else {
            when {
                cur <= 2 -> 0
                cur % 2 == 1 -> cur - 2
                else -> cur - 3
            }
        }
        return next.coerceIn(0, n - 1)
    }

    fun scrollByAnimated(dy: Float) {
        stopMotion()
        val target = (sy + dy).coerceIn(0f, maxY())
        scroller.startScroll(sx.roundToInt(), sy.roundToInt(), 0, (target - sy).roundToInt(), 220)
        postInvalidateOnAnimation()
    }

    // ---- layout ------------------------------------------------------------------------

    private fun cropFor(i: Int): NormRect =
        if (cropMargins) crops[i] ?: NormRect(0f, 0f, 1f, 1f) else NormRect(0f, 0f, 1f, 1f)

    private fun buildLayout() {
        val d = doc ?: return
        val effective = d.sizes.mapIndexed { i, s ->
            val c = cropFor(i)
            PageSize(s.width * c.width, s.height * c.height)
        }
        layout = PageLayout(effective, 6 * dp)
    }

    private fun relayoutKeepingPage() {
        val l = layout ?: return
        if (pendingPage < 0 && l.count > 0) {
            pendingPage = page
            pendingFraction = 0f
        }
        buildLayout()
        if (width > 0 && height > 0) relayout()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (layout != null && oldw > 0 && pendingPage < 0) {
            val l = layout!!
            val p = page
            pendingPage = p
            pendingFraction = if (flowMode == FlowMode.VERTICAL) {
                ((sy / zoom) - l.top(p)) / max(1f, l.height(p))
            } else 0f
        }
        relayout()
    }

    private fun relayout() {
        val l = layout ?: return
        if (width <= 0 || height <= 0) return
        l.layout(width.toFloat(), height.toFloat(), flowMode, fitMode, twoPage)
        val newLow = min(width, 1280)
        if (newLow != lowW) { cache.evictAll(); lowW = newLow }
        details.clear()
        if (pendingPage >= 0) {
            val p = pendingPage.coerceIn(0, l.count - 1)
            if (flowMode == FlowMode.VERTICAL) {
                sx = max(0f, (l.totalWidth * zoom - width) / 2f)
                sy = (l.top(p) + pendingFraction * l.height(p)) * zoom - if (pendingFraction == 0f) 6 * dp else 0f
            } else {
                sx = l.snapX(p) * zoom
                sy = 0f
            }
            pendingPage = -1
        }
        clamp(); notifyPage(); invalidate()
    }

    private fun maxX() = max(0f, (layout?.totalWidth ?: 0f) * zoom - width)
    private fun maxY() = max(0f, (layout?.totalHeight ?: 0f) * zoom - height)
    private fun clamp() { sx = sx.coerceIn(0f, maxX()); sy = sy.coerceIn(0f, maxY()) }

    private fun notifyPage() {
        val l = layout ?: return
        val p = if (flowMode == FlowMode.VERTICAL) {
            if (sy >= maxY() - 1f && maxY() > 0f) l.count - 1 else l.pageAt(width / 2f, (sy + height * 0.4f) / zoom)
        } else {
            l.pageAt((sx + width * 0.5f) / zoom, (sy + height * 0.5f) / zoom)
        }
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
        val visiblePages = l.visible(sx / zoom, sy / zoom, (sx + width) / zoom, (sy + height) / zoom)
        for (i in visiblePages) {
            val left = l.left(i) * zoom - sx
            val top = l.top(i) * zoom - sy
            val pw = l.width(i) * zoom
            val ph = l.height(i) * zoom
            dst.set(left, top, left + pw, top + ph)
            canvas.drawRect(dst.left + 2 * dp, dst.top + 2 * dp, dst.right + 2 * dp, dst.bottom + 2 * dp, shadow)
            val low = cache.get(i)
            if (low != null) canvas.drawBitmap(low, null, dst, pagePaint) else canvas.drawRect(dst, placeholder)
            val d = details[i]
            if (d != null && d.zoom * l.width(i) > lowW * 1.05f) {
                val r = RectF(dst.left + d.rect.left * zoom, dst.top + d.rect.top * zoom,
                    dst.left + d.rect.right * zoom, dst.top + d.rect.bottom * zoom)
                canvas.drawBitmap(d.bmp, null, r, pagePaint)
            }
            drawHighlights(canvas, i, dst)
        }
        requestRenders(visiblePages)
    }

    private fun drawNormRects(
        canvas: Canvas,
        pageIdx: Int,
        pageDst: RectF,
        rects: List<NormRect>,
        paint: Paint,
        dot: Boolean = false,
        style: String = "highlight",
    ) {
        val crop = cropFor(pageIdx)
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
                    linePaint.color = paint.color or 0xFF000000.toInt()
                    linePaint.strokeWidth = 2.2f * dp
                    canvas.drawLine(l, b, r, b, linePaint)
                }
                "strikeout" -> {
                    linePaint.color = paint.color or 0xFF000000.toInt()
                    linePaint.strokeWidth = 2.2f * dp
                    val mid = (t + b) / 2f
                    canvas.drawLine(l, mid, r, mid, linePaint)
                }
                "squiggly" -> {
                    linePaint.color = paint.color or 0xFF000000.toInt()
                    linePaint.strokeWidth = 1.8f * dp
                    inkPath.reset()
                    inkPath.moveTo(l, b)
                    val step = 5f * dp
                    var x = l
                    var up = true
                    while (x < r) {
                        val nx = min(r, x + step)
                        inkPath.lineTo(nx, if (up) b - 2.5f * dp else b + 1f * dp)
                        x = nx; up = !up
                    }
                    canvas.drawPath(inkPath, linePaint)
                }
                "sticky" -> {
                    val sz = 18f * dp
                    annPaint.color = paint.color or 0xFF000000.toInt()
                    canvas.drawRoundRect(l, t, l + sz, t + sz, 4f * dp, 4f * dp, annPaint)
                    linePaint.color = Color.WHITE; linePaint.strokeWidth = 1.5f * dp
                    canvas.drawLine(l + 4f * dp, t + 6f * dp, l + sz - 4f * dp, t + 6f * dp, linePaint)
                    canvas.drawLine(l + 4f * dp, t + 10f * dp, l + sz - 4f * dp, t + 10f * dp, linePaint)
                    canvas.drawLine(l + 4f * dp, t + 14f * dp, l + sz - 7f * dp, t + 14f * dp, linePaint)
                }
                else -> canvas.drawRect(l, t, r, b, paint)
            }
            if (dot && idx == 0 && style != "sticky") canvas.drawCircle(r, t, 4 * dp, noteDotPaint)
        }
    }

    private fun drawInkStroke(canvas: Canvas, pageIdx: Int, pageDst: RectF, color: Int, normW: Float, pts: List<Float>) {
        if (pts.size < 2) return
        val crop = cropFor(pageIdx)
        val cw = max(0.05f, crop.width)
        val ch = max(0.05f, crop.height)
        val fullW = pageDst.width() / cw
        inkPaint.color = color
        inkPaint.strokeWidth = max(1.5f, normW * fullW)
        inkPath.reset()
        for (k in 0 until pts.size - 1 step 2) {
            val sx = pageDst.left + ((pts[k] - crop.left) / cw) * pageDst.width()
            val sy = pageDst.top + ((pts[k + 1] - crop.top) / ch) * pageDst.height()
            if (k == 0) inkPath.moveTo(sx, sy) else inkPath.lineTo(sx, sy)
        }
        if (pts.size == 2) canvas.drawPoint(
            pageDst.left + ((pts[0] - crop.left) / cw) * pageDst.width(),
            pageDst.top + ((pts[1] - crop.top) / ch) * pageDst.height(),
            inkPaint,
        ) else canvas.drawPath(inkPath, inkPaint)
    }

    private fun drawHighlights(canvas: Canvas, pageIdx: Int, pageDst: RectF) {
        for (a in annotations) {
            if (a.page == pageIdx) {
                annPaint.color = a.color
                drawNormRects(canvas, pageIdx, pageDst, a.rects, annPaint, dot = a.note.isNotBlank(), style = a.style)
            }
        }
        for (s in inks) {
            if (s.page == pageIdx) drawInkStroke(canvas, pageIdx, pageDst, s.color, s.width, s.points)
        }
        if (activeInkPage == pageIdx && activeInkPts.isNotEmpty()) {
            val c = if (toolMode == ToolMode.MARKER) (penColor and 0x00FFFFFF) or 0x55000000 else penColor
            val w = if (toolMode == ToolMode.MARKER) penWidth * 3.2f else penWidth
            drawInkStroke(canvas, pageIdx, pageDst, c, w, activeInkPts)
        }
        for (idx in searchHits.indices) {
            val hit = searchHits[idx]
            if (hit.page == pageIdx) {
                drawNormRects(canvas, pageIdx, pageDst, hit.rects, if (idx == activeHitIndex) activeHitPaint else hitPaint)
            }
        }
        val sel = selection
        if (sel != null && sel.page == pageIdx && sel.rects.isNotEmpty()) {
            drawNormRects(canvas, pageIdx, pageDst, sel.rects, selPaint)
            val crop = cropFor(pageIdx)
            val cw = max(0.05f, crop.width)
            val ch = max(0.05f, crop.height)
            val first = sel.rects.first()
            val last = sel.rects.last()
            val sx0 = pageDst.left + ((first.left - crop.left) / cw).coerceIn(0f, 1f) * pageDst.width()
            val sy0 = pageDst.top + ((first.bottom - crop.top) / ch).coerceIn(0f, 1f) * pageDst.height()
            val sx1 = pageDst.left + ((last.right - crop.left) / cw).coerceIn(0f, 1f) * pageDst.width()
            val sy1 = pageDst.top + ((last.bottom - crop.top) / ch).coerceIn(0f, 1f) * pageDst.height()
            val r = 9f * dp
            handleStartX = sx0; handleStartY = sy0 + r
            handleEndX = sx1; handleEndY = sy1 + r
            canvas.drawCircle(handleStartX, handleStartY, r, handleFill)
            canvas.drawCircle(handleStartX, handleStartY, r, handleStroke)
            canvas.drawCircle(handleEndX, handleEndY, r, handleFill)
            canvas.drawCircle(handleEndX, handleEndY, r, handleStroke)
        } else if (sel == null) {
            handleStartX = -1f; handleEndX = -1f
        }
    }

    private fun settled() = scroller.isFinished && !scaling && zoomAnim?.isRunning != true

    private fun requestRenders(visiblePages: List<Int>) {
        val d = doc ?: return
        val l = layout ?: return
        if (cropMargins) {
            for (i in visiblePages) {
                if (!crops.containsKey(i) && cropPending.add(i)) {
                    renderer.call({ d.detectCrop(i) }) { r ->
                        cropPending.remove(i)
                        r.onSuccess { c ->
                            if (doc === d && cropMargins) {
                                crops[i] = c
                                cache.remove(i)
                                details.remove(i)
                                relayoutKeepingPage()
                            }
                        }
                    }
                }
            }
        }

        val jobs = ArrayList<Renderer.Job>()
        val w = lowW
        for (i in visiblePages) if (cache.get(i) == null) jobs += lowJob(d, l, i, w)

        if (!settled()) {
            // keep low-res preloads only while moving
        } else {
            for (i in visiblePages) {
                val needDetail = l.width(i) * zoom > w * 1.05f
                if (!needDetail) continue
                val want = visibleRect(l, i) ?: continue
                val have = details[i]
                if (have != null && have.zoom == zoom && covers(have.rect, want)) continue
                jobs += detailJob(d, l, i, expand(l, i, want))
            }
            details.keys.retainAll { it in visiblePages }
        }
        if (visiblePages.isNotEmpty()) {
            val first = visiblePages.min()
            val last = visiblePages.max()
            for (i in listOf(last + 1, first - 1, last + 2)) {
                if (i in 0 until l.count && cache.get(i) == null) jobs += lowJob(d, l, i, w)
            }
        }
        renderer.submit(jobs)
    }

    private fun lowJob(d: PdfDoc, l: PageLayout, i: Int, w: Int): Renderer.Job {
        val crop = cropFor(i)
        var bw = min(w, l.width(i).roundToInt().coerceAtLeast(1))
        var bh = (bw * l.height(i) / max(1f, l.width(i))).roundToInt()
        if (bh > 4096) { bw = (bw * 4096f / bh).roundToInt(); bh = 4096 }
        val ww = max(1, bw); val hh = max(1, bh)
        val fullW = max(1, (ww / crop.width).roundToInt())
        val fullH = max(1, (hh / crop.height).roundToInt())
        val ox = -(crop.left * fullW).roundToInt()
        val oy = -(crop.top * fullH).roundToInt()
        return Renderer.Job("low:$i:$w:${crop.left}:${crop.top}", work = {
            Bitmap.createBitmap(ww, hh, Bitmap.Config.ARGB_8888).also { d.render(i, it, ox, oy, fullW, fullH) }
        }, done = { bmp ->
            if (bmp != null && doc === d && lowW == w) { cache.put(i, bmp); invalidate() }
            else if (bmp == null) cache.trimToSize(cache.size() / 2)
        })
    }

    private fun detailJob(d: PdfDoc, l: PageLayout, i: Int, r: RectF): Renderer.Job {
        val z = zoom
        val crop = cropFor(i)
        val pw = (l.width(i) * z).roundToInt()
        val ph = (l.height(i) * z).roundToInt()
        val fullW = max(1, (pw / crop.width).roundToInt())
        val fullH = max(1, (ph / crop.height).roundToInt())
        val cropOffX = (crop.left * fullW).roundToInt()
        val cropOffY = (crop.top * fullH).roundToInt()
        val left = (r.left * z).roundToInt()
        val top = (r.top * z).roundToInt()
        val bw = max(1, (r.width() * z).roundToInt())
        val bh = max(1, (r.height() * z).roundToInt())
        val rect = RectF(left / z, top / z, (left + bw) / z, (top + bh) / z)
        return Renderer.Job("detail:$i:$z:${rect.left.toInt()}:${rect.top.toInt()}", work = {
            Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also {
                d.render(i, it, -(cropOffX + left), -(cropOffY + top), fullW, fullH)
            }
        }, done = { bmp ->
            if (bmp != null && doc === d && zoom == z) { details[i] = Detail(z, rect, bmp); invalidate() }
        })
    }

    /** Visible part of page [i] in page-local base units, or null. */
    private fun visibleRect(l: PageLayout, i: Int): RectF? {
        val r = RectF(sx / zoom - l.left(i), sy / zoom - l.top(i),
            (sx + width) / zoom - l.left(i), (sy + height) / zoom - l.top(i))
        return if (r.intersect(0f, 0f, l.width(i), l.height(i))) r else null
    }

    private fun expand(l: PageLayout, i: Int, r: RectF): RectF {
        val mx = r.width() * 0.1f
        val my = r.height() * 0.1f
        return RectF(max(0f, r.left - mx), max(0f, r.top - my), min(l.width(i), r.right + mx), min(l.height(i), r.bottom + my))
    }

    private fun covers(have: RectF, want: RectF): Boolean {
        val e = 1.5f / zoom
        return have.left <= want.left + e && have.top <= want.top + e &&
            have.right >= want.right - e && have.bottom >= want.bottom - e
    }

    // ---- gestures ----------------------------------------------------------------------

    private var scaling = false
    private var selectingDrag = false
    private var selectAnchorChar = -1
    private var selectPage = -1

    /** Maps screen (x, y) to (pageIndex, normX, normY) in uncropped [0..1] page coordinates. */
    private fun hitTestPage(x: Float, y: Float): Triple<Int, Float, Float>? {
        val l = layout ?: return null
        val bx = (x + sx) / zoom
        val by = (y + sy) / zoom
        val vis = l.visible(bx, by, bx, by)
        for (i in vis) {
            if (bx >= l.left(i) && bx <= l.right(i) && by >= l.top(i) && by <= l.bottom(i)) {
                val fx = ((bx - l.left(i)) / max(1f, l.width(i))).coerceIn(0f, 1f)
                val fy = ((by - l.top(i)) / max(1f, l.height(i))).coerceIn(0f, 1f)
                val crop = cropFor(i)
                val nx = (crop.left + fx * crop.width).coerceIn(0f, 1f)
                val ny = (crop.top + fy * crop.height).coerceIn(0f, 1f)
                return Triple(i, nx, ny)
            }
        }
        return null
    }

    private fun hitNorm(nx: Float, ny: Float, r: NormRect, pad: Float = 0.012f): Boolean =
        nx >= r.left - pad && nx <= r.right + pad && ny >= r.top - pad && ny <= r.bottom + pad

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { scaling = true; selectingDrag = false; stopMotion(); return true }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomTo(zoom * detector.scaleFactor, detector.focusX, detector.focusY); return true
        }
        override fun onScaleEnd(detector: ScaleGestureDetector) { scaling = false; invalidate() }
    }).apply { isQuickScaleEnabled = false }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { stopMotion(); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaling || selectingDrag) return true
            sx += dx; sy += dy; clamp(); notifyPage(); invalidate(); return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scaling || selectingDrag) return true
            if (flowMode != FlowMode.VERTICAL && zoom <= 1.01f) {
                val l = layout ?: return true
                val cur = l.pageAt((sx + width * 0.5f) / zoom, (sy + height * 0.5f) / zoom)
                val dir = when {
                    vx < -350f -> if (flowMode == FlowMode.HORIZONTAL_RTL) -1 else 1
                    vx > 350f -> if (flowMode == FlowMode.HORIZONTAL_RTL) 1 else -1
                    else -> 0
                }
                val next = stepPage(cur, dir).coerceIn(0, l.count - 1)
                snapTo(next)
                return true
            }
            scroller.fling(sx.roundToInt(), sy.roundToInt(), -vx.roundToInt(), -vy.roundToInt(),
                0, maxX().roundToInt(), 0, maxY().roundToInt())
            postInvalidateOnAnimation(); return true
        }
        override fun onLongPress(e: MotionEvent) {
            if (scaling) return
            val d = doc ?: return
            val hit = hitTestPage(e.x, e.y) ?: return
            val (p, nx, ny) = hit
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            renderer.call({ d.selectWordAt(p, nx, ny) }) { r ->
                val sel = r.getOrNull() ?: return@call
                if (doc !== d) return@call
                selection = sel
                selectAnchorChar = sel.startChar
                selectPage = p
                selectingDrag = true
                listener?.onSelectionChanged(sel)
                invalidate()
            }
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (selection != null) {
                clearSelection()
                return true
            }
            val hit = hitTestPage(e.x, e.y)
            if (hit != null) {
                val (p, nx, ny) = hit
                val ann = annotations.firstOrNull { a -> a.page == p && a.rects.any { hitNorm(nx, ny, it) } }
                if (ann != null) {
                    listener?.onAnnotationTap(ann)
                    return true
                }
                if (toolMode == ToolMode.STICKY) {
                    listener?.onStickyTap(p, nx, ny)
                    return true
                }
                val d = doc
                val cached = linksCache[p]
                if (cached != null) {
                    val link = cached.firstOrNull { hitNorm(nx, ny, it.bounds) }
                    if (link != null) { listener?.onLinkTap(link); return true }
                } else if (d != null) {
                    renderer.call({ d.links(p) }) { r ->
                        val links = r.getOrDefault(emptyList())
                        if (doc === d) {
                            linksCache[p] = links
                            val link = links.firstOrNull { hitNorm(nx, ny, it.bounds) }
                            if (link != null) listener?.onLinkTap(link)
                            else listener?.onTap()
                        }
                    }
                    return true
                }
            }
            listener?.onTap()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            animateZoom(if (zoom < 1.6f) 2.5f else 1f, e.x, e.y); return true
        }
    })

    private fun snapTo(targetPage: Int) {
        val l = layout ?: return
        val tx = (l.snapX(targetPage) * zoom).coerceIn(0f, maxX())
        val dx = (tx - sx).roundToInt()
        scroller.startScroll(sx.roundToInt(), sy.roundToInt(), dx, 0, 220)
        postInvalidateOnAnimation()
    }

    private fun nearHandle(x: Float, y: Float, hx: Float, hy: Float): Boolean {
        if (hx < 0f) return false
        val dx = x - hx; val dy = y - hy
        val r = 32f * dp
        return dx * dx + dy * dy <= r * r
    }

    private fun eraseNear(page: Int, nx: Float, ny: Float) {
        val hit = inks.firstOrNull { s ->
            if (s.page != page) return@firstOrNull false
            for (k in 0 until s.points.size - 1 step 2) {
                val dx = s.points[k] - nx
                val dy = s.points[k + 1] - ny
                if (dx * dx + dy * dy <= 0.03f * 0.03f) return@firstOrNull true
            }
            false
        } ?: return
        listener?.onInkErased(hit.id)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (toolMode == ToolMode.PEN || toolMode == ToolMode.MARKER) {
            if (event.pointerCount == 1) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val hit = hitTestPage(event.x, event.y)
                        if (hit != null) {
                            stopMotion()
                            activeInkPage = hit.first
                            activeInkPts.clear()
                            activeInkPts += hit.second; activeInkPts += hit.third
                            invalidate()
                            return true
                        }
                    }
                    MotionEvent.ACTION_MOVE -> if (activeInkPage >= 0) {
                        val hit = hitTestPage(event.x, event.y)
                        if (hit != null && hit.first == activeInkPage) {
                            activeInkPts += hit.second; activeInkPts += hit.third
                            invalidate()
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (activeInkPage >= 0) {
                        val p = activeInkPage
                        val pts = activeInkPts.toList()
                        activeInkPage = -1
                        activeInkPts.clear()
                        val c = if (toolMode == ToolMode.MARKER) (penColor and 0x00FFFFFF) or 0x55000000 else penColor
                        val w = if (toolMode == ToolMode.MARKER) penWidth * 3.2f else penWidth
                        listener?.onInkAdded(p, c, w, pts)
                        invalidate()
                        return true
                    }
                }
            } else {
                activeInkPage = -1
                activeInkPts.clear()
            }
        } else if (toolMode == ToolMode.ERASER && event.pointerCount == 1) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                hitTestPage(event.x, event.y)?.let { (p, nx, ny) -> eraseNear(p, nx, ny) }
                return true
            }
        }

        scale.onTouchEvent(event)
        val sel = selection
        if (event.actionMasked == MotionEvent.ACTION_DOWN && sel != null) {
            dragHandle = when {
                nearHandle(event.x, event.y, handleStartX, handleStartY) -> 1
                nearHandle(event.x, event.y, handleEndX, handleEndY) -> 2
                else -> 0
            }
            if (dragHandle != 0) {
                stopMotion()
                return true
            }
        }
        if ((dragHandle != 0 || selectingDrag) && event.actionMasked == MotionEvent.ACTION_MOVE) {
            val d = doc
            val curSel = selection
            val hit = hitTestPage(event.x, event.y)
            if (d != null && curSel != null && hit != null && hit.first == curSel.page) {
                val (p, nx, ny) = hit
                val callBlock: () -> TextSelection? = if (dragHandle != 0) {
                    { d.selectBetween(p, curSel.startChar, curSel.endChar, nx, ny, moveStart = dragHandle == 1) }
                } else {
                    { d.selectRange(p, selectAnchorChar, nx, ny) }
                }
                renderer.call(callBlock) { r ->
                    val updated = r.getOrNull() ?: return@call
                    if (doc === d && selection != null) {
                        selection = updated
                        listener?.onSelectionChanged(updated)
                        invalidate()
                    }
                }
            }
            return true
        }
        val handled = gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            selectingDrag = false
            if (dragHandle != 0) {
                dragHandle = 0
                invalidate()
                return true
            }
            if (!handled && flowMode != FlowMode.VERTICAL && zoom <= 1.01f && scroller.isFinished && !scaling) {
                val l = layout
                if (l != null) snapTo(l.pageAt((sx + width * 0.5f) / zoom, (sy + height * 0.5f) / zoom))
            }
            invalidate()
        }
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
