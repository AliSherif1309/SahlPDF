package com.gitlab.mudlej.MjPdfReader.scanner

import org.junit.Assert.*
import org.junit.Test

class ScanGeometryTest {
    @Test fun acceptsFullPageAndOrdersUnorderedCornersClockwise() {
        val full = ScanGeometry.fullPage()
        assertTrue(ScanGeometry.valid(full))
        assertEquals(full, ScanGeometry.ordered(listOf(full[2], full[0], full[3], full[1])))
    }
    @Test fun rejectsCrossedCollapsedOutOfBoundsAndNonFiniteCorners() {
        val full = ScanGeometry.fullPage()
        assertFalse(ScanGeometry.valid(listOf(full[0], full[2], full[1], full[3])))
        assertFalse(ScanGeometry.valid(List(4) { full[0] }))
        assertFalse(ScanGeometry.valid(full.toMutableList().apply { this[0] = ScanPoint(-0.1f, 0f) }))
        assertFalse(ScanGeometry.valid(full.toMutableList().apply { this[0] = ScanPoint(Float.NaN, 0f) }))
        assertFalse(ScanGeometry.valid(listOf(ScanPoint(.1f,.1f), ScanPoint(.11f,.1f),
            ScanPoint(.11f,.11f), ScanPoint(.1f,.11f))))
    }
    @Test fun boundsOutputWithoutUpscalingOrForcingPaperAspectRatio() {
        assertEquals(2338 to 3508, ScanGeometry.outputSize(ScanGeometry.fullPage(), 4000, 6000))
        assertEquals(800 to 600, ScanGeometry.outputSize(ScanGeometry.fullPage(), 800, 600))
        assertEquals(320 to 240, ScanGeometry.outputSize(ScanGeometry.fullPage(), 4000, 3000, 320))
    }
    @Test fun perspectiveUsesBothOppositeEdges() {
        val quad = listOf(ScanPoint(.1f,.1f), ScanPoint(.9f,.2f), ScanPoint(.8f,.9f), ScanPoint(.2f,.9f))
        assertTrue(ScanGeometry.valid(quad))
        val size = ScanGeometry.outputSize(quad, 1000, 1000)
        assertTrue(size.first in 690..730)
        assertTrue(size.second in 740..780)
    }
}
