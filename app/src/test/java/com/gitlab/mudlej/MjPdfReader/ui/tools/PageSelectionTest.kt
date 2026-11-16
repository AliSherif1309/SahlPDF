package com.gitlab.mudlej.MjPdfReader.ui.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PageSelectionTest {
    @Test fun preservesReorderedAndRepeatedPages() {
        assertEquals(listOf(0, 1, 2, 6, 4, 3, 0), PageSelection.parse("1-3, 7, 5-4, 1", 7))
    }
    @Test fun supportsArabicAndPersianDigitsAndComma() {
        assertEquals(listOf(0, 1, 2, 4), PageSelection.parse("١–٣، ۵", 5))
    }
    @Test fun acceptsWhitespace() {
        assertEquals(listOf(2, 1, 0), PageSelection.parse(" 3 - 1 ", 3))
    }
    @Test fun rejectsMalformedAndOutsideDocumentPages() {
        listOf("", " ", "0", "-1", "4", "1,,2", "1,", ",1", "1-2-3", "a", "999999999999999999999").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { PageSelection.parse(it, 3) }
        }
    }
    @Test fun boundsExpansionBeforeAllocating() {
        assertThrows(IllegalArgumentException::class.java) { PageSelection.parse("1-2147483647", Int.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { PageSelection.parse("1-1000,1", 1000) }
        assertEquals(1000, PageSelection.parse("1000-1", 1000).size)
    }
    @Test fun rejectsEmptySource() {
        assertThrows(IllegalArgumentException::class.java) { PageSelection.parse("1", 0) }
    }
}
