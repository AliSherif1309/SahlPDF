package com.sahl.pdf

import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Exports a flattened copy of [doc] with all Sahl PDF annotations (highlights, markups,
 * sticky pins, freehand ink, shapes, and free text) baked onto each page.
 * Works on any PDF, including scanned or non-editable documents.
 */
object PdfExporter {
    fun export(
        doc: PdfDoc,
        annotations: List<Library.AnnotationEntry>,
        inks: List<Library.InkStroke>,
        shapes: List<Library.ShapeEntry>,
        out: OutputStream,
    ) {
        val pdf = PdfDocument()
        val draw = AnnotationDraw(2f)
        val fullCrop = NormRect(0f, 0f, 1f, 1f)
        try {
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
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
                    val dst = RectF(0f, 0f, pw.toFloat(), ph.toFloat())
                    page.canvas.drawBitmap(bmp, null, dst, null)
                    draw.drawPageOverlays(page.canvas, i, dst, fullCrop, annotations, inks, shapes)
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
}
