package com.gitlab.mudlej.MjPdfReader.ui.tools

/** One-based ranges for people; zero-based indices for the PDF engine. Order and duplicates matter. */
object PageSelection {
    const val MAX_OUTPUT_PAGES = 1000

    fun parse(expression: String, pageCount: Int): List<Int> {
        require(pageCount > 0)
        require(expression.isNotBlank())
        val normalized = buildString {
            expression.forEach { c ->
                val digit = Character.digit(c, 10)
                append(if (digit >= 0) ('0'.code + digit).toChar() else if (c == '،') ',' else if (c == '–') '-' else c)
            }
        }
        val result = mutableListOf<Int>()
        for (part in normalized.split(',')) {
            val match = Regex("""\s*(\d+)\s*(?:-\s*(\d+)\s*)?""").matchEntire(part)
                ?: throw IllegalArgumentException("Invalid page range")
            val first = match.groupValues[1].toIntOrNull() ?: throw IllegalArgumentException()
            val last = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull()
                ?: if (match.groupValues[2].isEmpty()) first else throw IllegalArgumentException()
            require(first in 1..pageCount && last in 1..pageCount)
            val count = kotlin.math.abs(last.toLong() - first) + 1
            require(result.size + count <= MAX_OUTPUT_PAGES)
            val range = if (first <= last) first..last else first downTo last
            range.forEach { result.add(it - 1) }
        }
        return result
    }
}
