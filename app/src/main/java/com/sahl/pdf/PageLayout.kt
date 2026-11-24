package com.sahl.pdf

/** Size of a PDF page in points. */
data class PageSize(val width: Float, val height: Float)

/**
 * Lays pages out in one vertical column, each page as wide as the view ("base" units, zoom 1).
 * Pure math so it can be unit-tested without Android.
 */
class PageLayout(private val pages: List<PageSize>, private val gap: Float) {
    val count get() = pages.size
    var width = 0f; private set
    var totalHeight = 0f; private set
    private var tops = FloatArray(0)
    private var heights = FloatArray(0)

    fun layout(viewWidth: Float) {
        width = viewWidth
        tops = FloatArray(pages.size)
        heights = FloatArray(pages.size)
        var y = gap
        for (i in pages.indices) {
            val p = pages[i]
            val ratio = if (p.width > 0f && p.height > 0f) p.height / p.width else 1.414f
            tops[i] = y
            heights[i] = viewWidth * ratio
            y += heights[i] + gap
        }
        totalHeight = y
    }

    fun top(i: Int) = tops[i]
    fun height(i: Int) = heights[i]
    fun bottom(i: Int) = tops[i] + heights[i]

    /** Index of the page that contains (or is just above) base coordinate [y]. */
    fun pageAt(y: Float): Int {
        if (tops.isEmpty()) return 0
        var lo = 0
        var hi = tops.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (tops[mid] <= y) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Pages that intersect base range [y0, y1]. */
    fun visible(y0: Float, y1: Float): IntRange {
        if (tops.isEmpty()) return IntRange.EMPTY
        var first = pageAt(y0)
        if (bottom(first) < y0 && first < tops.size - 1) first++
        var last = pageAt(y1)
        if (last < first) last = first
        return first..last
    }
}
