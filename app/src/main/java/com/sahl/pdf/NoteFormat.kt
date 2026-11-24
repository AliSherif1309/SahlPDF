package com.sahl.pdf

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.widget.EditText
import kotlin.math.abs

object NoteFormat {

    /**
     * Merges adjacent/overlapping highlight boxes on the same line so Arabic & Latin
     * selections render as smooth continuous bars instead of choppy per-glyph fragments.
     */
    fun mergeLineRects(rects: List<NormRect>): List<NormRect> {
        if (rects.size <= 1) return rects
        val sorted = rects.sortedWith(compareBy<NormRect> { it.top }.thenBy { it.left })
        val out = ArrayList<NormRect>(sorted.size)
        var cur = sorted[0]
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            val sameLine = abs(cur.top - next.top) <= maxOf(cur.height, next.height) * 0.45f &&
                abs(cur.bottom - next.bottom) <= maxOf(cur.height, next.height) * 0.45f
            val closeHoriz = next.left <= cur.right + maxOf(cur.height, next.height) * 0.6f
            if (sameLine && closeHoriz) {
                cur = NormRect(
                    minOf(cur.left, next.left),
                    minOf(cur.top, next.top),
                    maxOf(cur.right, next.right),
                    maxOf(cur.bottom, next.bottom),
                )
            } else {
                out += cur
                cur = next
            }
        }
        out += cur
        return out
    }

    /** Wraps the current selection (or inserts markers at the cursor) in [ed]. */
    fun wrap(ed: EditText, prefix: String, suffix: String) {
        val text = ed.text ?: return
        val start = minOf(ed.selectionStart, ed.selectionEnd).coerceAtLeast(0)
        val end = maxOf(ed.selectionStart, ed.selectionEnd).coerceAtLeast(start)
        text.insert(end, suffix)
        text.insert(start, prefix)
        if (start == end) ed.setSelection(start + prefix.length)
        else ed.setSelection(start, end + prefix.length + suffix.length)
    }

    /** Prefixes the current line in [ed] with [linePrefix] (e.g. "- " or "- [ ] "). */
    fun toggleLinePrefix(ed: EditText, linePrefix: String) {
        val text = ed.text ?: return
        val pos = ed.selectionStart.coerceAtLeast(0)
        val lineStart = text.lastIndexOf('\n', (pos - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val existing = text.subSequence(lineStart, minOf(text.length, lineStart + linePrefix.length)).toString()
        if (existing == linePrefix) {
            text.delete(lineStart, lineStart + linePrefix.length)
        } else {
            text.insert(lineStart, linePrefix)
        }
    }

    /** Renders **bold**, *italic*, - bullets, and - [ ] / - [x] checklists into a Spanned. */
    fun render(raw: String): CharSequence {
        if (raw.isBlank()) return ""
        val sb = SpannableStringBuilder()
        val lines = raw.split('\n')
        for ((li, rawLine) in lines.withIndex()) {
            if (li > 0) sb.append('\n')
            val line = when {
                rawLine.startsWith("- [x] ") || rawLine.startsWith("- [X] ") -> "☑ " + rawLine.drop(6)
                rawLine.startsWith("- [ ] ") -> "☐ " + rawLine.drop(6)
                rawLine.startsWith("- ") -> "• " + rawLine.drop(2)
                else -> rawLine
            }
            var i = 0
            while (i < line.length) {
                if (line.startsWith("**", i)) {
                    val close = line.indexOf("**", i + 2)
                    if (close > i + 2) {
                        val s = sb.length
                        sb.append(line.substring(i + 2, close))
                        sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = close + 2
                        continue
                    }
                }
                if (line[i] == '*') {
                    val close = line.indexOf('*', i + 1)
                    if (close > i + 1) {
                        val s = sb.length
                        sb.append(line.substring(i + 1, close))
                        sb.setSpan(StyleSpan(Typeface.ITALIC), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = close + 1
                        continue
                    }
                }
                sb.append(line[i])
                i++
            }
        }
        return sb
    }
}
