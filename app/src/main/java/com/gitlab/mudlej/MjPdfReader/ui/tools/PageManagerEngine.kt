package com.gitlab.mudlej.MjPdfReader.ui.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Runs on IO. The private source snapshot is never modified. */
class PageManagerEngine(private val context: Context) {
    companion object { const val MAX_SOURCE_BYTES = 128L * 1024 * 1024 }

    suspend fun stage(uri: Uri, target: File): Int {
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot read PDF")
            input.use { source ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_SOURCE_BYTES) { "PDF exceeds 128 MiB" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            PDFBoxResourceLoader.init(context.applicationContext)
            val count = PDDocument.load(target, memory()).use {
                require(!it.isEncrypted) { "Protected PDF" }
                require(it.numberOfPages in 1..PageSelection.MAX_OUTPUT_PAGES)
                it.numberOfPages
            }
            // Validate preview support before opening the editor.
            ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { require(it.pageCount == count) }
            }
            return count
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }
    }

    fun thumbnail(source: File, index: Int): Bitmap {
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                renderer.openPage(index).use { page ->
                    val longest = maxOf(page.width, page.height).coerceAtLeast(1)
                    val width = (page.width.toLong() * 320 / longest).toInt().coerceAtLeast(1)
                    val height = (page.height.toLong() * 320 / longest).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        return bitmap
                    } catch (failure: Throwable) {
                        bitmap.recycle()
                        throw failure
                    }
                }
            }
        }
    }

    suspend fun create(sourceFile: File, plan: PagePlan, output: File) {
        require(plan.pages.isNotEmpty())
        PDFBoxResourceLoader.init(context.applicationContext)
        try {
            PDDocument.load(sourceFile, memory()).use { source ->
                require(!source.isEncrypted)
                PDDocument(memory()).use { destination ->
                    for (entry in plan.pages) {
                        currentCoroutineContext().ensureActive()
                        require(entry.sourceIndex in 0 until source.numberOfPages)
                        val page = source.getPage(entry.sourceIndex)
                        val imported = destination.importPage(page)
                        imported.resources = page.resources
                        imported.mediaBox = page.mediaBox
                        imported.cropBox = page.cropBox
                        imported.rotation = ((page.rotation % 360 + 360) % 360 + entry.rotation) % 360
                    }
                    destination.save(output)
                }
            }
            PDDocument.load(output, memory()).use { verified ->
                check(verified.numberOfPages == plan.pages.size)
            }
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        }
    }

    private fun memory() = MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)
}
