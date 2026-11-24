package com.sahl.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pure-Kotlin PDF tools for Batch 5:
 * - Organize pages (reorder, rotate, delete, save a copy)
 * - Split / extract page ranges
 * - Merge multiple PDFs
 * - Scan photos/images into a clean PDF with document filters
 */
object PdfTools {

    data class PageSpec(val pageIndex: Int, val rotationDeg: Int = 0)

    enum class ScanFilter { ORIGINAL, ENHANCED, BW }

    data class ScanPage(
        val filePath: String,
        val rotationDeg: Int = 0,
        val filter: ScanFilter = ScanFilter.ENHANCED,
    )

    /**
     * Parses a 1-based page range string such as "1-3, 5, 8-10" into 0-based page indices.
     * Preserves order, deduplicates, and ignores out-of-range or malformed tokens.
     */
    fun parsePageRanges(spec: String, pageCount: Int): List<Int> {
        if (pageCount <= 0 || spec.isBlank()) return emptyList()
        val seen = HashSet<Int>()
        val out = ArrayList<Int>()
        for (rawToken in spec.split(',', ';', ' ')) {
            val token = rawToken.trim()
            if (token.isEmpty()) continue
            val dash = token.indexOf('-')
            if (dash < 0) {
                val p = token.toIntOrNull() ?: continue
                if (p in 1..pageCount && seen.add(p - 1)) out += p - 1
            } else {
                val a = token.substring(0, dash).trim().toIntOrNull() ?: continue
                val b = token.substring(dash + 1).trim().toIntOrNull() ?: continue
                val step = if (a <= b) 1 else -1
                var cur = a
                while (true) {
                    if (cur in 1..pageCount && seen.add(cur - 1)) out += cur - 1
                    if (cur == b) break
                    cur += step
                }
            }
        }
        return out
    }

    /** Exports the selected [pages] (in order, with per-page rotation) from [doc] to [out]. */
    fun exportOrganized(doc: PdfDoc, pages: List<PageSpec>, out: OutputStream) {
        val pdf = PdfDocument()
        try {
            for ((idx, spec) in pages.withIndex()) {
                if (spec.pageIndex !in 0 until doc.pageCount) continue
                val sz = doc.sizes[spec.pageIndex]
                val baseW = max(1, sz.width.roundToInt())
                val baseH = max(1, sz.height.roundToInt())
                val scale = (1440f / max(baseW, baseH)).coerceIn(1f, 2.5f)
                val bw = max(1, (baseW * scale).roundToInt())
                val bh = max(1, (baseH * scale).roundToInt())
                val rawBmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                val bmp = try {
                    doc.render(spec.pageIndex, rawBmp, 0, 0, bw, bh)
                    val rot = ((spec.rotationDeg % 360) + 360) % 360
                    if (rot == 0) rawBmp else rotateBitmap(rawBmp, rot).also { rawBmp.recycle() }
                } catch (t: Throwable) {
                    rawBmp.recycle()
                    throw t
                }
                try {
                    val rot = ((spec.rotationDeg % 360) + 360) % 360
                    val swap = rot == 90 || rot == 270
                    val pw = if (swap) baseH else baseW
                    val ph = if (swap) baseW else baseH
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, idx + 1).create())
                    page.canvas.drawBitmap(bmp, null, RectF(0f, 0f, pw.toFloat(), ph.toFloat()), null)
                    pdf.finishPage(page)
                } finally {
                    bmp.recycle()
                }
            }
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }

    /** Merges all pages of [docs] in order into a single PDF written to [out]. */
    fun merge(docs: List<PdfDoc>, out: OutputStream) {
        val pdf = PdfDocument()
        var pageNum = 0
        try {
            for (doc in docs) {
                for (i in 0 until doc.pageCount) {
                    val sz = doc.sizes[i]
                    val pw = max(1, sz.width.roundToInt())
                    val ph = max(1, sz.height.roundToInt())
                    val scale = (1440f / max(pw, ph)).coerceIn(1f, 2.5f)
                    val bw = max(1, (pw * scale).roundToInt())
                    val bh = max(1, (ph * scale).roundToInt())
                    val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    try {
                        doc.render(i, bmp, 0, 0, bw, bh)
                        pageNum++
                        val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, pageNum).create())
                        page.canvas.drawBitmap(bmp, null, RectF(0f, 0f, pw.toFloat(), ph.toFloat()), null)
                        pdf.finishPage(page)
                    } finally {
                        bmp.recycle()
                    }
                }
            }
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }

    /** Converts [pages] (scanned photos/images) into an A4-proportioned PDF written to [out]. */
    fun imagesToPdf(pages: List<ScanPage>, out: OutputStream) {
        val pdf = PdfDocument()
        try {
            for ((idx, sp) in pages.withIndex()) {
                val src = decodeSampledBitmap(sp.filePath, 2048) ?: continue
                val processed = try {
                    applyFilterAndRotate(src, sp.rotationDeg, sp.filter)
                } finally {
                    src.recycle()
                }
                try {
                    val landscape = processed.width > processed.height
                    val pw = if (landscape) 842 else 595
                    val ph = if (landscape) 595 else 842
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, idx + 1).create())
                    page.canvas.drawColor(Color.WHITE)
                    val scale = minOf(pw.toFloat() / processed.width, ph.toFloat() / processed.height)
                    val dw = processed.width * scale
                    val dh = processed.height * scale
                    val left = (pw - dw) / 2f
                    val top = (ph - dh) / 2f
                    page.canvas.drawBitmap(processed, null, RectF(left, top, left + dw, top + dh), Paint(Paint.FILTER_BITMAP_FLAG))
                    pdf.finishPage(page)
                } finally {
                    processed.recycle()
                }
            }
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }

    fun decodeSampledBitmap(path: String, maxDim: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var sample = 1
        while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) sample *= 2
        val decOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(path, decOpts) ?: return null
        val exifRot = try {
            when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: Exception) { 0 }
        if (exifRot == 0) return bmp
        return rotateBitmap(bmp, exifRot).also { bmp.recycle() }
    }

    fun applyFilterAndRotate(src: Bitmap, rotationDeg: Int, filter: ScanFilter): Bitmap {
        val rot = ((rotationDeg % 360) + 360) % 360
        val rotated = if (rot == 0) src else rotateBitmap(src, rot)
        if (filter == ScanFilter.ORIGINAL) {
            return if (rotated === src) src.copy(Bitmap.Config.ARGB_8888, false) else rotated
        }
        val out = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val cm = ColorMatrix()
        when (filter) {
            ScanFilter.ENHANCED -> {
                // Boost contrast and lift whites so paper looks bright & text is crisp
                val c = 1.38f
                val t = (-0.5f * c + 0.5f) * 255f + 16f
                cm.set(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f,
                ))
            }
            ScanFilter.BW -> {
                val gray = ColorMatrix().apply { setSaturation(0f) }
                val c = 1.85f
                val t = (-0.5f * c + 0.5f) * 255f + 22f
                val contrast = ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f,
                ))
                cm.setConcat(contrast, gray)
            }
            ScanFilter.ORIGINAL -> Unit
        }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) }
        canvas.drawBitmap(rotated, 0f, 0f, paint)
        if (rotated !== src) rotated.recycle()
        return out
    }

    private fun rotateBitmap(bmp: Bitmap, deg: Int): Bitmap {
        val m = Matrix().apply { postRotate(deg.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
}
