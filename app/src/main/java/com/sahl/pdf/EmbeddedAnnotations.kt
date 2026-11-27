package com.sahl.pdf

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A native ISO 32000 annotation embedded inside the PDF file (e.g. from Xodo, Adobe Acrobat, etc.).
 */
data class EmbeddedAnnotation(
    val page: Int,
    val indexOnPage: Int,
    val subtype: String,
    val contents: String,
    val author: String,
    val bounds: NormRect,
    val snippet: String = "",
)

/**
 * Reads, updates, and deletes native embedded PDF annotations using Apache PDFBox-Android.
 */
object EmbeddedAnnotations {
    private val inited = AtomicBoolean(false)

    private fun ensureInit(c: Context) {
        if (inited.compareAndSet(false, true)) {
            PDFBoxResourceLoader.init(c.applicationContext)
        }
    }

    /** Converts a PDF bottom-left-origin rect to normalized [0..1] top-left-origin coordinates, honoring page rotation. */
    fun toNormRect(
        llx: Float,
        lly: Float,
        urx: Float,
        ury: Float,
        boxLlx: Float,
        boxLly: Float,
        boxUrx: Float,
        boxUry: Float,
        rotationDeg: Int,
    ): NormRect {
        val bw = (boxUrx - boxLlx).coerceAtLeast(1f)
        val bh = (boxUry - boxLly).coerceAtLeast(1f)
        val uLeft = ((llx - boxLlx) / bw).coerceIn(0f, 1f)
        val uRight = ((urx - boxLlx) / bw).coerceIn(0f, 1f)
        val uTop = ((boxUry - ury) / bh).coerceIn(0f, 1f)
        val uBottom = ((boxUry - lly) / bh).coerceIn(0f, 1f)
        val (l, t, r, b) = when (((rotationDeg % 360) + 360) % 360) {
            90 -> floatArrayOf(1f - uBottom, uLeft, 1f - uTop, uRight)
            180 -> floatArrayOf(1f - uRight, 1f - uBottom, 1f - uLeft, 1f - uTop)
            270 -> floatArrayOf(uTop, 1f - uRight, uBottom, 1f - uLeft)
            else -> floatArrayOf(uLeft, uTop, uRight, uBottom)
        }
        return NormRect(
            minOf(l, r).coerceIn(0f, 1f),
            minOf(t, b).coerceIn(0f, 1f),
            maxOf(l, r).coerceIn(0f, 1f),
            maxOf(t, b).coerceIn(0f, 1f),
        )
    }

    /** Loads all interactive/commented native PDF annotations from [uri]. */
    fun load(c: Context, uri: Uri, password: String? = null): List<EmbeddedAnnotation> {
        ensureInit(c)
        val out = ArrayList<EmbeddedAnnotation>()
        val stream = try { c.contentResolver.openInputStream(uri) } catch (_: Throwable) { null } ?: return out
        stream.use { ins ->
            val doc = try {
                if (password.isNullOrEmpty()) PDDocument.load(ins, MemoryUsageSetting.setupTempFileOnly())
                else PDDocument.load(ins, password, MemoryUsageSetting.setupTempFileOnly())
            } catch (_: Throwable) {
                return out
            }
            doc.use { pd ->
                for (pageIdx in 0 until pd.numberOfPages) {
                    val page = pd.getPage(pageIdx)
                    val box = page.cropBox ?: page.mediaBox ?: continue
                    val rot = page.rotation
                    val annots = try { page.annotations } catch (_: Throwable) { emptyList() }
                    for ((idx, a) in annots.withIndex()) {
                        val sub = a.subtype ?: continue
                        if (sub == "Link" || sub == "Widget" || sub == "Popup") continue
                        val contents = a.contents?.trim().orEmpty()
                        val author = (a as? PDAnnotationMarkup)?.titlePopup?.trim().orEmpty()
                        val rect = a.rectangle ?: continue
                        val norm = toNormRect(
                            rect.lowerLeftX, rect.lowerLeftY, rect.upperRightX, rect.upperRightY,
                            box.lowerLeftX, box.lowerLeftY, box.upperRightX, box.upperRightY,
                            rot,
                        )
                        out += EmbeddedAnnotation(pageIdx, idx, sub, contents, author, norm)
                    }
                }
            }
        }
        return out
    }

    /** Updates [newContents] of the annotation at ([pageIdx], [annotIndex]) and writes the modified PDF to [out]. */
    fun updateContents(
        c: Context,
        srcUri: Uri,
        password: String?,
        pageIdx: Int,
        annotIndex: Int,
        newContents: String,
        out: OutputStream,
    ): Boolean {
        ensureInit(c)
        val ins = c.contentResolver.openInputStream(srcUri) ?: return false
        ins.use { stream ->
            val pd = if (password.isNullOrEmpty()) PDDocument.load(stream, MemoryUsageSetting.setupTempFileOnly())
            else PDDocument.load(stream, password, MemoryUsageSetting.setupTempFileOnly())
            pd.use { doc ->
                if (pageIdx !in 0 until doc.numberOfPages) return false
                val annots = doc.getPage(pageIdx).annotations
                if (annotIndex !in annots.indices) return false
                annots[annotIndex].contents = newContents
                doc.save(out)
                return true
            }
        }
    }

    /** Deletes the annotation at ([pageIdx], [annotIndex]) and writes the modified PDF to [out]. */
    fun deleteAnnotation(
        c: Context,
        srcUri: Uri,
        password: String?,
        pageIdx: Int,
        annotIndex: Int,
        out: OutputStream,
    ): Boolean {
        ensureInit(c)
        val ins = c.contentResolver.openInputStream(srcUri) ?: return false
        ins.use { stream ->
            val pd = if (password.isNullOrEmpty()) PDDocument.load(stream, MemoryUsageSetting.setupTempFileOnly())
            else PDDocument.load(stream, password, MemoryUsageSetting.setupTempFileOnly())
            pd.use { doc ->
                if (pageIdx !in 0 until doc.numberOfPages) return false
                val page = doc.getPage(pageIdx)
                val annots = page.annotations
                if (annotIndex !in annots.indices) return false
                val removed = annots.removeAt(annotIndex)
                // Also remove linked Popup if present
                val popup = (removed as? PDAnnotationMarkup)?.popup
                if (popup != null) annots.remove(popup)
                page.annotations = annots
                doc.save(out)
                return true
            }
        }
    }
}
