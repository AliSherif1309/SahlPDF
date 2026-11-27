package com.sahl.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddedAnnotationsTest {

    @Test
    fun toNormRect_unrotatedAndRotated() {
        // Page 100x200, annot at top-right (80..90, 160..180)
        val r0 = EmbeddedAnnotations.toNormRect(80f, 160f, 90f, 180f, 0f, 0f, 100f, 200f, 0)
        assertEquals(0.8f, r0.left, 1e-4f)
        assertEquals(0.1f, r0.top, 1e-4f)
        assertEquals(0.9f, r0.right, 1e-4f)
        assertEquals(0.2f, r0.bottom, 1e-4f)

        // 90° clockwise: (uLeft=0.8..0.9, uTop=0.1..0.2) -> left=1-0.2=0.8, right=1-0.1=0.9, top=0.8, bottom=0.9
        val r90 = EmbeddedAnnotations.toNormRect(80f, 160f, 90f, 180f, 0f, 0f, 100f, 200f, 90)
        assertEquals(0.8f, r90.left, 1e-4f)
        assertEquals(0.8f, r90.top, 1e-4f)
        assertEquals(0.9f, r90.right, 1e-4f)
        assertEquals(0.9f, r90.bottom, 1e-4f)
    }
}
