package com.sahl.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageLayoutTest {
    private val pages = listOf(PageSize(100f, 200f), PageSize(200f, 100f), PageSize(100f, 100f))

    @Test fun laysOutPagesAtViewWidth() {
        val l = PageLayout(pages, 10f)
        l.layout(500f)
        assertEquals(10f, l.top(0), 0.01f)
        assertEquals(1000f, l.height(0), 0.01f)
        assertEquals(1020f, l.top(1), 0.01f)
        assertEquals(250f, l.height(1), 0.01f)
        assertEquals(1280f, l.top(2), 0.01f)
        assertEquals(1790f, l.totalHeight, 0.01f)
    }

    @Test fun findsPageAtCoordinate() {
        val l = PageLayout(pages, 10f).apply { layout(500f) }
        assertEquals(0, l.pageAt(0f))
        assertEquals(0, l.pageAt(1015f))
        assertEquals(1, l.pageAt(1020f))
        assertEquals(2, l.pageAt(99999f))
    }

    @Test fun visibleRangeSkipsGapAbove() {
        val l = PageLayout(pages, 10f).apply { layout(500f) }
        assertEquals(1..1, l.visible(1015f, 1200f))
        assertEquals(0..2, l.visible(0f, 1500f))
    }

    @Test fun degeneratePageGetsA4Ratio() {
        val l = PageLayout(listOf(PageSize(0f, 0f)), 0f).apply { layout(100f) }
        assertEquals(141.4f, l.height(0), 0.1f)
    }

    @Test fun horizontalLtrAndRtlOrder() {
        val ltr = PageLayout(pages, 10f).apply { layout(400f, 800f, FlowMode.HORIZONTAL_LTR, FitMode.PAGE) }
        assertEquals(true, ltr.left(0) < ltr.left(1) && ltr.left(1) < ltr.left(2))

        val rtl = PageLayout(pages, 10f).apply { layout(400f, 800f, FlowMode.HORIZONTAL_RTL, FitMode.PAGE) }
        assertEquals(true, rtl.left(2) < rtl.left(1) && rtl.left(1) < rtl.left(0))
        assertEquals(1, rtl.pageAt(rtl.left(1) + rtl.width(1) / 2f, 400f))
    }

    @Test fun marginCropDetectsContentBounds() {
        val w = 100; val h = 100
        val pixels = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        for (y in 20..79) for (x in 30..69) pixels[y * w + x] = 0xFF000000.toInt()
        val crop = MarginCrop.detect(pixels, w, h, padFraction = 0.0f)
        assertEquals(0.30f, crop.left, 0.02f)
        assertEquals(0.20f, crop.top, 0.02f)
        assertEquals(0.69f, crop.right, 0.02f)
        assertEquals(0.79f, crop.bottom, 0.02f)
    }

    @Test fun twoPageSpreadLtrAndRtl() {
        val ltr = PageLayout(pages, 10f).apply { layout(800f, 600f, FlowMode.HORIZONTAL_LTR, FitMode.PAGE, spread = true) }
        // Page 0 is cover (alone), pages 1 and 2 are paired [1 | 2]
        assertEquals(true, ltr.snapX(0) < ltr.snapX(1))
        assertEquals(ltr.snapX(1), ltr.snapX(2), 0.01f)
        assertEquals(true, ltr.left(1) < ltr.left(2))

        val rtl = PageLayout(pages, 10f).apply { layout(800f, 600f, FlowMode.HORIZONTAL_RTL, FitMode.PAGE, spread = true) }
        // In RTL, spread [2 | 1] comes before cover [0]
        assertEquals(true, rtl.snapX(1) < rtl.snapX(0))
        assertEquals(rtl.snapX(1), rtl.snapX(2), 0.01f)
        assertEquals(true, rtl.left(2) < rtl.left(1))
    }
}

class UpdatePickTest {
    private val codes = mapOf(
        "SahlPDF.apk" to 10050L,
        "SahlPDF-armeabi-v7a.apk" to 20050L,
        "SahlPDF-arm64-v8a.apk" to 30050L,
    )

    @Test fun prefersDeviceAbi() {
        assertEquals("SahlPDF-arm64-v8a.apk", UpdatePick.choose(codes, listOf("arm64-v8a", "armeabi-v7a"), 30040L))
    }

    @Test fun oldOneXInstallsUpdate() {
        // Sahl PDF 1.x armeabi-v7a builds were 2000 + run.
        assertEquals("SahlPDF-armeabi-v7a.apk", UpdatePick.choose(codes, listOf("armeabi-v7a"), 2044L))
    }

    @Test fun nothingWhenUpToDate() {
        assertNull(UpdatePick.choose(codes, listOf("arm64-v8a"), 30050L))
    }

    @Test fun fallsBackToUniversal() {
        assertEquals("SahlPDF.apk", UpdatePick.choose(codes, listOf("x86_64"), 10001L))
    }

    @Test fun parsesVersions() {
        assertEquals(listOf(1, 55), UpdatePick.version("1.55"))
        assertEquals(listOf(2, 0, 17), UpdatePick.version("2.0.17"))
        assertEquals(emptyList<Int>(), UpdatePick.version("bad"))
    }

    @Test fun picksVersionedApkNames() {
        val versioned = mapOf(
            "SahlPDF-v1.58.apk" to 10058L,
            "SahlPDF-armeabi-v7a-v1.58.apk" to 20058L,
            "SahlPDF-arm64-v8a-v1.58.apk" to 30058L,
        )
        assertEquals("SahlPDF-arm64-v8a-v1.58.apk", UpdatePick.choose(versioned, listOf("arm64-v8a"), 30040L))
        assertEquals("SahlPDF-v1.58.apk", UpdatePick.choose(versioned, listOf("x86_64"), 10001L))
    }
}
