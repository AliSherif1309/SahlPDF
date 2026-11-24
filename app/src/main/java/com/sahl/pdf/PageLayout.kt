package com.sahl.pdf

enum class FlowMode { VERTICAL, HORIZONTAL_LTR, HORIZONTAL_RTL }
enum class FitMode { WIDTH, PAGE }

/** Size of a PDF page in points. */
data class PageSize(val width: Float, val height: Float)

/** Normalized [0..1] rectangle, pure Kotlin for unit testing. */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

/**
 * Lays pages out in a column (vertical) or row (horizontal LTR / RTL) at zoom 1 ("base" units).
 * Pure math so it can be unit-tested without Android.
 */
class PageLayout(private val pages: List<PageSize>, private val gap: Float) {
    val count get() = pages.size
    var width = 0f; private set
    var height = 0f; private set
    var totalWidth = 0f; private set
    var totalHeight = 0f; private set
    var flow = FlowMode.VERTICAL; private set
    var fit = FitMode.WIDTH; private set

    private var lefts = FloatArray(0)
    private var tops = FloatArray(0)
    private var widths = FloatArray(0)
    private var heights = FloatArray(0)

    fun layout(viewW: Float, viewH: Float, flowMode: FlowMode = FlowMode.VERTICAL, fitMode: FitMode = FitMode.WIDTH) {
        width = viewW
        height = viewH
        flow = flowMode
        fit = fitMode
        val n = pages.size
        lefts = FloatArray(n)
        tops = FloatArray(n)
        widths = FloatArray(n)
        heights = FloatArray(n)
        if (n == 0 || viewW <= 0f || viewH <= 0f) {
            totalWidth = viewW
            totalHeight = viewH
            return
        }

        for (i in 0 until n) {
            val p = pages[i]
            val ratio = if (p.width > 0f && p.height > 0f) p.height / p.width else 1.414f
            if (fitMode == FitMode.PAGE) {
                val wByH = viewH / ratio
                if (wByH <= viewW) {
                    widths[i] = wByH
                    heights[i] = viewH
                } else {
                    widths[i] = viewW
                    heights[i] = viewW * ratio
                }
            } else {
                widths[i] = viewW
                heights[i] = viewW * ratio
            }
        }

        if (flowMode == FlowMode.VERTICAL) {
            var y = gap
            for (i in 0 until n) {
                lefts[i] = (viewW - widths[i]) / 2f
                tops[i] = y
                y += heights[i] + gap
            }
            totalWidth = viewW
            totalHeight = y
        } else {
            val order = if (flowMode == FlowMode.HORIZONTAL_RTL) (n - 1 downTo 0) else (0 until n)
            var x = gap
            var maxH = viewH
            for (i in order) {
                val slotW = maxOf(viewW, widths[i])
                lefts[i] = x + (slotW - widths[i]) / 2f
                tops[i] = maxOf(0f, (viewH - heights[i]) / 2f)
                x += slotW + gap
                if (heights[i] > maxH) maxH = heights[i]
            }
            totalWidth = x
            totalHeight = maxH
        }
    }

    /** Backwards-compatible helper for vertical fit-width layout. */
    fun layout(viewW: Float) = layout(viewW, viewW * 2f, FlowMode.VERTICAL, FitMode.WIDTH)

    fun left(i: Int) = lefts[i]
    fun top(i: Int) = tops[i]
    fun width(i: Int) = widths[i]
    fun height(i: Int) = heights[i]
    fun right(i: Int) = lefts[i] + widths[i]
    fun bottom(i: Int) = tops[i] + heights[i]

    /** Snap x-offset for page [i] in horizontal mode (centered in viewport). */
    fun snapX(i: Int): Float = (lefts[i] - (width - widths[i]) / 2f).coerceAtLeast(0f)

    /** Index of the page nearest to base coordinates ([x], [y]). */
    fun pageAt(x: Float, y: Float): Int {
        if (pages.isEmpty()) return 0
        if (flow == FlowMode.VERTICAL) {
            var lo = 0
            var hi = tops.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (tops[mid] <= y) lo = mid else hi = mid - 1
            }
            return lo
        }
        var best = 0
        var bestDist = Float.MAX_VALUE
        for (i in pages.indices) {
            val cx = lefts[i] + widths[i] / 2f
            val d = kotlin.math.abs(cx - x)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    fun pageAt(y: Float): Int = pageAt(width / 2f, y)

    /** Pages that intersect base viewport [x0..x1, y0..y1]. */
    fun visible(x0: Float, y0: Float, x1: Float, y1: Float): List<Int> {
        if (pages.isEmpty()) return emptyList()
        if (flow == FlowMode.VERTICAL) {
            var first = pageAt(0f, y0)
            if (bottom(first) < y0 && first < tops.size - 1) first++
            var last = pageAt(0f, y1)
            if (last < first) last = first
            return (first..last).toList()
        }
        val out = ArrayList<Int>()
        for (i in pages.indices) {
            if (right(i) >= x0 && left(i) <= x1) out += i
        }
        return out
    }

    fun visible(y0: Float, y1: Float): IntRange {
        val list = visible(0f, y0, width, y1)
        return if (list.isEmpty()) IntRange.EMPTY else list.first()..list.last()
    }
}

/**
 * Pure margin-crop detector: scans an ARGB_8888 pixel array to find the bounding box of
 * non-background pixels, returned in normalized [0f..1f] page coordinates.
 */
object MarginCrop {
    fun detect(pixels: IntArray, w: Int, h: Int, padFraction: Float = 0.02f): NormRect {
        if (w <= 0 || h <= 0 || pixels.size < w * h) return NormRect(0f, 0f, 1f, 1f)
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        val step = maxOf(1, minOf(w, h) / 200)
        var y = 0
        while (y < h) {
            val rowOff = y * w
            var x = 0
            while (x < w) {
                val c = pixels[rowOff + x]
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                if (r < 242 || g < 242 || b < 242) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                x += step
            }
            y += step
        }
        if (maxX <= minX || maxY <= minY) return NormRect(0f, 0f, 1f, 1f)
        val l = (minX.toFloat() / w - padFraction).coerceIn(0f, 0.45f)
        val t = (minY.toFloat() / h - padFraction).coerceIn(0f, 0.45f)
        val r = (maxX.toFloat() / w + padFraction).coerceIn(l + 0.1f, 1f)
        val b = (maxY.toFloat() / h + padFraction).coerceIn(t + 0.1f, 1f)
        return NormRect(l, t, r, b)
    }
}
