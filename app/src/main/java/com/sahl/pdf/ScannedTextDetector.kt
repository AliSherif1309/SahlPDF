package com.sahl.pdf

import kotlin.math.abs
import kotlin.math.max

/**
 * Pure-Kotlin visual line & word detector for scanned PDF pages (no text layer).
 * Strips vertical/horizontal table borders before segmenting lines and words so
 * highlights snap tightly to text on forms, tables, and books (Arabic & Latin).
 */
object ScannedTextDetector {

    fun detectWords(pixels: IntArray, w: Int, h: Int): List<NormRect> {
        if (w <= 4 || h <= 4 || pixels.size < w * h) return emptyList()

        val dark = BooleanArray(w * h)
        for (y in 0 until h) {
            val rowOff = y * w
            for (x in 0 until w) {
                val c = pixels[rowOff + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val luma = (r * 77 + g * 150 + b * 29) shr 8
                if (luma < 195) dark[rowOff + x] = true
            }
        }

        // 1. Strip long continuous vertical table/box borders
        val minVertLine = max(14, (h * 0.045f).toInt())
        for (x in 0 until w) {
            var y = 0
            while (y < h) {
                if (!dark[y * w + x]) {
                    y++
                    continue
                }
                val start = y
                while (y < h && dark[y * w + x]) y++
                if (y - start >= minVertLine) {
                    for (cy in start until y) dark[cy * w + x] = false
                }
            }
        }

        // 2. Strip long continuous horizontal table/divider lines
        val minHorizLine = max(36, (w * 0.20f).toInt())
        for (y in 0 until h) {
            val rowOff = y * w
            var x = 0
            while (x < w) {
                if (!dark[rowOff + x]) {
                    x++
                    continue
                }
                val start = x
                while (x < w && dark[rowOff + x]) x++
                if (x - start >= minHorizLine) {
                    for (cx in start until x) dark[rowOff + cx] = false
                }
            }
        }

        // 3. Row projection on cleaned ink map
        val rowInk = IntArray(h)
        for (y in 0 until h) {
            val rowOff = y * w
            var cnt = 0
            for (x in 0 until w) if (dark[rowOff + x]) cnt++
            rowInk[y] = cnt
        }

        val minRowInk = max(2, w / 250)
        val maxLineGap = max(2, h / 220)
        val maxLineH = max(16, (h * 0.048f).toInt())
        val rawLines = ArrayList<IntRange>()
        var y = 0
        while (y < h) {
            if (rowInk[y] < minRowInk) {
                y++
                continue
            }
            val start = y
            var end = y
            var gap = 0
            y++
            while (y < h) {
                if (rowInk[y] >= minRowInk) {
                    end = y
                    gap = 0
                } else {
                    gap++
                    if (gap > maxLineGap) break
                }
                y++
            }
            if (end - start + 1 >= 4) rawLines += start..end
        }

        // Split any unusually tall region (e.g. touching descenders/graphics) at its lowest-ink valleys
        val lines = ArrayList<IntRange>(rawLines.size)
        for (rng in rawLines) {
            splitTallLine(rng, rowInk, maxLineH, lines)
        }

        // 4. Horizontal word projection within each text line
        val out = ArrayList<NormRect>()
        for (line in lines) {
            val lh = line.last - line.first + 1
            val maxWordGap = max(3, (lh * 0.36f).toInt())
            val colInk = BooleanArray(w)
            for (x in 0 until w) {
                var hit = false
                for (ly in line.first..line.last) {
                    if (dark[ly * w + x]) {
                        hit = true
                        break
                    }
                }
                colInk[x] = hit
            }
            var x = 0
            while (x < w) {
                if (!colInk[x]) {
                    x++
                    continue
                }
                val startX = x
                var endX = x
                var gap = 0
                x++
                while (x < w) {
                    if (colInk[x]) {
                        endX = x
                        gap = 0
                    } else {
                        gap++
                        if (gap > maxWordGap) break
                    }
                    x++
                }
                if (endX - startX + 1 >= 3) {
                    out += NormRect(
                        (startX.toFloat() / w).coerceIn(0f, 1f),
                        (line.first.toFloat() / h).coerceIn(0f, 1f),
                        ((endX + 1).toFloat() / w).coerceIn(0f, 1f),
                        ((line.last + 1).toFloat() / h).coerceIn(0f, 1f),
                    )
                }
            }
        }
        return out
    }

    private fun splitTallLine(rng: IntRange, rowInk: IntArray, maxLineH: Int, out: MutableList<IntRange>) {
        if (rng.last - rng.first + 1 <= maxLineH) {
            out += rng
            return
        }
        val midStart = rng.first + (rng.last - rng.first) / 4
        val midEnd = rng.last - (rng.last - rng.first) / 4
        var splitY = -1
        var bestInk = Int.MAX_VALUE
        for (yy in midStart..midEnd) {
            if (rowInk[yy] < bestInk) {
                bestInk = rowInk[yy]
                splitY = yy
            }
        }
        if (splitY <= rng.first + 3 || splitY >= rng.last - 3) {
            out += rng
            return
        }
        splitTallLine(rng.first until splitY, rowInk, maxLineH, out)
        splitTallLine((splitY + 1)..rng.last, rowInk, maxLineH, out)
    }

    fun nearestWordIndex(words: List<NormRect>, nx: Float, ny: Float): Int {
        if (words.isEmpty()) return -1
        for (i in words.indices) {
            val r = words[i]
            if (nx >= r.left - 0.008f && nx <= r.right + 0.008f && ny >= r.top - 0.008f && ny <= r.bottom + 0.008f) {
                return i
            }
        }
        var best = -1
        var bestDist = Float.MAX_VALUE
        for (i in words.indices) {
            val r = words[i]
            val dx = when {
                nx < r.left -> r.left - nx
                nx > r.right -> nx - r.right
                else -> 0f
            }
            val dy = when {
                ny < r.top -> r.top - ny
                ny > r.bottom -> ny - r.bottom
                else -> 0f
            }
            val d = dx * dx + (dy * 3f) * (dy * 3f)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return if (bestDist <= 0.04f) best else -1
    }

    /**
     * Builds tight, Xodo-style per-line highlight bands between ([x1],[y1]) and ([x2],[y2])
     * using visually detected [words]. Trims tightly to word ink and falls back to a compact
     * single-line strip if dragged over empty space or handwriting.
     */
    fun detectLineBands(
        words: List<NormRect>,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        rtl: Boolean = false,
    ): List<NormRect> {
        val topY = minOf(y1, y2)
        val botY = maxOf(y1, y2)
        val sx = if (y1 <= y2) x1 else x2
        val ex = if (y1 <= y2) x2 else x1
        val minX = minOf(x1, x2)
        val maxX = maxOf(x1, x2)

        // Filter out abnormally tall non-text blobs and group remaining words into lines
        val textWords = words.filter { (it.bottom - it.top) <= 0.055f }
        data class Line(var left: Float, var top: Float, var right: Float, var bottom: Float, val items: MutableList<NormRect>)
        val lines = ArrayList<Line>()
        for (w in textWords.sortedBy { it.top }) {
            val prev = lines.lastOrNull()
            if (prev != null && w.top <= prev.bottom + (prev.bottom - prev.top) * 0.2f &&
                abs(w.top - prev.top) <= 0.035f
            ) {
                prev.left = minOf(prev.left, w.left)
                prev.top = minOf(prev.top, w.top)
                prev.right = maxOf(prev.right, w.right)
                prev.bottom = maxOf(prev.bottom, w.bottom)
                prev.items += w
            } else {
                lines += Line(w.left, w.top, w.right, w.bottom, mutableListOf(w))
            }
        }

        val hit = lines.filter { ln ->
            val pad = (ln.bottom - ln.top) * 0.25f
            val vertHit = ln.bottom + pad >= topY && ln.top - pad <= botY
            val horizHit = ln.right >= minX - 0.02f && ln.left <= maxX + 0.02f
            vertHit && horizHit
        }

        if (hit.isEmpty()) {
            val l = minX.coerceIn(0f, 1f)
            val r = maxX.coerceIn(0f, 1f)
            val midY = (y1 + y2) * 0.5f
            val t = (midY - 0.011f).coerceIn(0f, 1f)
            val b = (midY + 0.011f).coerceIn(0f, 1f)
            return if (r - l >= 0.005f) listOf(NormRect(l, t, r, b)) else emptyList()
        }

        if (hit.size == 1) {
            val ln = hit[0]
            val matched = ln.items.filter { it.right >= minX - 0.006f && it.left <= maxX + 0.006f }
            if (matched.isNotEmpty()) {
                val l = maxOf(minX - 0.004f, matched.minOf { it.left })
                val r = minOf(maxX + 0.004f, matched.maxOf { it.right })
                val t = matched.minOf { it.top }
                val b = matched.maxOf { it.bottom }
                if (r > l) return listOf(NormRect(l.coerceIn(0f, 1f), t, r.coerceIn(0f, 1f), b))
            }
            val l = minX.coerceIn(ln.left, ln.right)
            val r = maxX.coerceIn(ln.left, ln.right)
            return if (r - l >= 0.005f) listOf(NormRect(l, ln.top, r, ln.bottom))
            else listOf(NormRect(ln.left, ln.top, ln.right, ln.bottom))
        }

        val isRtl = rtl || sx > 0.55f
        val out = ArrayList<NormRect>(hit.size)
        for ((idx, ln) in hit.withIndex()) {
            val (boundL, boundR) = when (idx) {
                0 -> if (isRtl) ln.left to sx.coerceIn(ln.left + 0.01f, ln.right)
                else sx.coerceIn(ln.left, ln.right - 0.01f) to ln.right
                hit.lastIndex -> if (isRtl) ex.coerceIn(ln.left, ln.right - 0.01f) to ln.right
                else ln.left to ex.coerceIn(ln.left + 0.01f, ln.right)
                else -> ln.left to ln.right
            }
            val matched = ln.items.filter { it.right >= boundL - 0.006f && it.left <= boundR + 0.006f }
            if (matched.isNotEmpty()) {
                val l = maxOf(boundL - 0.004f, matched.minOf { it.left }).coerceIn(0f, 1f)
                val r = minOf(boundR + 0.004f, matched.maxOf { it.right }).coerceIn(0f, 1f)
                val t = matched.minOf { it.top }
                val b = matched.maxOf { it.bottom }
                if (r > l) out += NormRect(l, t, r, b)
            }
        }
        return out
    }
}
