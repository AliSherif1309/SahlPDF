package com.sahl.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannedTextDetectorTest {

    @Test
    fun detectWords_findsLinesAndWords() {
        val w = 200
        val h = 200
        val pixels = IntArray(w * h) { 0xFFFFFFFF.toInt() }

        fun fillRect(x0: Int, y0: Int, x1: Int, y1: Int) {
            for (y in y0 until y1) {
                for (x in x0 until x1) pixels[y * w + x] = 0xFF111111.toInt()
            }
        }

        // Line 1: two words separated by a space
        fillRect(20, 40, 60, 48)
        fillRect(80, 40, 140, 48)
        // Line 2: one word
        fillRect(30, 80, 110, 88)

        val words = ScannedTextDetector.detectWords(pixels, w, h)
        assertEquals(3, words.size)
        assertTrue(words[0].left < words[1].left)
        assertTrue(words[2].top > words[0].bottom)

        val hit = ScannedTextDetector.nearestWordIndex(words, 0.5f, 0.22f)
        assertEquals(1, hit)

        val bands = ScannedTextDetector.detectLineBands(words, 0.7f, 0.22f, 0.3f, 0.42f, rtl = true)
        assertEquals(2, bands.size)
        assertTrue(bands[0].top < bands[1].top)
    }

    @Test
    fun detectWords_ignoresTableBordersAndCapsHighlightToTextLines() {
        val w = 300
        val h = 300
        val pixels = IntArray(w * h) { 0xFFFFFFFF.toInt() }

        fun fillRect(x0: Int, y0: Int, x1: Int, y1: Int) {
            for (y in y0 until y1) {
                for (x in x0 until x1) pixels[y * w + x] = 0xFF111111.toInt()
            }
        }

        // Outer table box: vertical borders at x=20..21 and x=280..281, horizontal borders at y=30..31 and y=210..211
        fillRect(20, 30, 22, 212)
        fillRect(280, 30, 282, 212)
        fillRect(20, 30, 282, 32)
        fillRect(20, 210, 282, 212)

        // Two short text lines inside the box with lots of whitespace below
        fillRect(50, 60, 160, 68)
        fillRect(50, 90, 170, 98)

        val words = ScannedTextDetector.detectWords(pixels, w, h)
        assertEquals(2, words.size)

        val bands = ScannedTextDetector.detectLineBands(words, 0.17f, 0.21f, 0.56f, 0.32f, rtl = false)
        assertEquals(2, bands.size)
        // Each band must stay tightly around its 8px text line, never spanning the 180px box
        assertTrue(bands[0].height < 0.05f)
        assertTrue(bands[1].height < 0.05f)
    }
}
