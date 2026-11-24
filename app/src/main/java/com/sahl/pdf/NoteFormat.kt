package com.sahl.pdf

import android.graphics.Typeface
import android.text.Html
import android.text.Spannable
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

    /** Toggles Bold or Italic StyleSpan directly in [ed] (WYSIWYG, no Markdown syntax). */
    fun toggleStyle(ed: EditText, style: Int) {
        val text = ed.text ?: return
        val start = minOf(ed.selectionStart, ed.selectionEnd).coerceAtLeast(0)
        val end = maxOf(ed.selectionStart, ed.selectionEnd).coerceAtLeast(start)
        if (start == end) {
            // Apply to word or insert a styled space? Better: toggle on current word or whole line if empty
            val spans = text.getSpans(start, end, StyleSpan::class.java).filter { it.style == style }
            if (spans.isNotEmpty()) spans.forEach { text.removeSpan(it) }
            else text.setSpan(StyleSpan(style), start, end, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            return
        }
        val existing = text.getSpans(start, end, StyleSpan::class.java).filter { it.style == style }
        if (existing.isNotEmpty()) {
            existing.forEach { text.removeSpan(it) }
        } else {
            text.setSpan(StyleSpan(style), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Toggles a visual prefix like "• " or "☐ " on the current line in [ed]. */
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

    fun encode(spanned: Spanned): String {
        if (spanned.isBlank()) return ""
        return Html.toHtml(spanned, Html.TO_HTML_PARAGRAPH_LINES_INDIVIDUAL).trim()
    }

    fun decode(raw: String): CharSequence {
        if (raw.isBlank()) return ""
        if (raw.contains('<') && raw.contains('>')) {
            return Html.fromHtml(raw, Html.FROM_HTML_MODE_COMPACT).trimEnd()
        }
        // Fallback for any older plain/markdown notes
        return renderLegacy(raw)
    }

    private fun renderLegacy(raw: String): CharSequence {
        val sb = SpannableStringBuilder()
        for ((li, rawLine) in raw.split('\n').withIndex()) {
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
