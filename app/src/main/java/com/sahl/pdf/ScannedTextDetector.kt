package com.sahl.pdf

import kotlin.math.max

/**
 * Pure-Kotlin visual line & word detector for scanned PDF pages (no text layer).
 * Works offline on any script (Arabic, Latin, etc.) so users can select and
 * highlight scanned pages with draggable handles just like real text.
 */
object ScannedTextDetector {

    fun detectWords(pixels: IntArray, w: Int, h: Int): List<NormRect> {
        if (w <= 4 || h <= 4 || pixels.size < w * h) return emptyList()

        val dark = BooleanArray(w * h)
        val rowInk = IntArray(h)
        for (y in 0 until h) {
            val rowOff = y * w
            var cnt = 0
            for (x in 0 until w) {
                val c = pixels[rowOff + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val luma = (r * 77 + g * 150 + b * 29) shr 8
                if (luma < 195) {
                    dark[rowOff + x] = true
                    cnt++
                }
            }
            rowInk[y] = cnt
        }

        val minRowInk = max(2, w / 250)
        val maxLineGap = max(2, h / 180)
        val lines = ArrayList<IntRange>()
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
            if (end - start + 1 >= 4) lines += start..end
        }

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
     * Builds smooth, Xodo-style line-snapped highlight bands between ([x1],[y1]) and ([x2],[y2])
     * using visually detected [words]. Always returns at least one clean band even on faint scans.
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

        // Group words into horizontal lines
        data class Line(var left: Float, var top: Float, var right: Float, var bottom: Float)
        val lines = ArrayList<Line>()
        for (w in words.sortedBy { it.top }) {
            val prev = lines.lastOrNull()
            if (prev != null && w.top <= prev.bottom + (prev.bottom - prev.top) * 0.25f) {
                prev.left = minOf(prev.left, w.left)
                prev.top = minOf(prev.top, w.top)
                prev.right = maxOf(prev.right, w.right)
                prev.bottom = maxOf(prev.bottom, w.bottom)
            } else {
                lines += Line(w.left, w.top, w.right, w.bottom)
            }
        }

        val hit = lines.filter { ln ->
            val pad = (ln.bottom - ln.top) * 0.35f
            ln.bottom + pad >= topY && ln.top - pad <= botY
        }

        if (hit.isEmpty()) {
            val l = minOf(x1, x2).coerceIn(0f, 1f)
            val r = maxOf(x1, x2).coerceIn(0f, 1f)
            val hPad = 0.011f
            val t = (topY - hPad).coerceIn(0f, 1f)
            val b = (botY + hPad).coerceIn(0f, 1f)
            return if (r - l >= 0.005f) listOf(NormRect(l, t, r, b)) else emptyList()
        }

        if (hit.size == 1) {
            val ln = hit[0]
            val l = minOf(x1, x2).coerceIn(ln.left, ln.right)
            val r = maxOf(x1, x2).coerceIn(ln.left, ln.right)
            return if (r - l >= 0.005f) listOf(NormRect(l, ln.top, r, ln.bottom))
            else listOf(NormRect(ln.left, ln.top, ln.right, ln.bottom))
        }

        val isRtl = rtl || sx > 0.55f
        val out = ArrayList<NormRect>(hit.size)
        for ((idx, ln) in hit.withIndex()) {
            val (l, r) = when (idx) {
                0 -> if (isRtl) ln.left to sx.coerceIn(ln.left + 0.01f, ln.right)
                else sx.coerceIn(ln.left, ln.right - 0.01f) to ln.right
                hit.lastIndex -> if (isRtl) ex.coerceIn(ln.left, ln.right - 0.01f) to ln.right
                else ln.left to ex.coerceIn(ln.left + 0.01f, ln.right)
                else -> ln.left to ln.right
            }
            if (r > l) out += NormRect(l, ln.top, r, ln.bottom)
        }
        return out
    }
}
