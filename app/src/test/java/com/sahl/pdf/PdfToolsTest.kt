package com.sahl.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfToolsTest {

    @Test
    fun parsePageRanges_basicAndReversed() {
        assertEquals(listOf(0, 1, 2, 4, 6, 7), PdfTools.parsePageRanges("1-3, 5, 7-8", 10))
        assertEquals(listOf(4, 3, 2), PdfTools.parsePageRanges("5-3", 10))
    }

    @Test
    fun parsePageRanges_dedupAndBounds() {
        assertEquals(listOf(0, 1, 9), PdfTools.parsePageRanges("0, 1, 1-2, 2, 10, 11, abc", 10))
        assertEquals(emptyList<Int>(), PdfTools.parsePageRanges("", 10))
        assertEquals(emptyList<Int>(), PdfTools.parsePageRanges("1-3", 0))
    }
}
