package com.sahl.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannedTextDetectorTest {

    @Test
    fun detectWords_findsLinesAndWords() {
        val w = 200
        val h = 100
        val pixels = IntArray(w * h) { 0xFFFFFFFF.toInt() }

        fun fillRect(x0: Int, y0: Int, x1: Int, y1: Int) {
            for (y in y0 until y1) {
                for (x in x0 until x1) pixels[y * w + x] = 0xFF111111.toInt()
            }
        }

        // Line 1: two words separated by a space
        fillRect(20, 20, 60, 32)
        fillRect(80, 20, 140, 32)
        // Line 2: one word
        fillRect(30, 55, 110, 67)

        val words = ScannedTextDetector.detectWords(pixels, w, h)
        assertEquals(3, words.size)
        assertTrue(words[0].left < words[1].left)
        assertTrue(words[2].top > words[0].bottom)

        val hit = ScannedTextDetector.nearestWordIndex(words, 0.5f, 0.25f)
        assertEquals(1, hit)

        val bands = ScannedTextDetector.detectLineBands(words, 0.7f, 0.25f, 0.3f, 0.60f, rtl = true)
        assertEquals(2, bands.size)
        assertTrue(bands[0].top < bands[1].top)
    }
}
