package com.gitlab.mudlej.MjPdfReader.scanner

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class ScanPdfWriter(private val context: Context) {
    suspend fun create(draft: ScanDraft, output: File) {
        require(draft.pages.size in 1..ScanStore.MAX_PAGES)
        PDFBoxResourceLoader.init(context.applicationContext)
        val store = ScanStore(context)
        val processor = ScanImageProcessor()
        try {
            PDDocument(MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)).use { pdf ->
                for (entry in draft.pages) {
                    currentCoroutineContext().ensureActive()
                    val bitmap = processor.process(store.original(draft, entry), entry)
                    try {
                        val size = if (bitmap.width > bitmap.height) PDRectangle(842f, 595f) else PDRectangle.A4
                        val page = PDPage(size)
                        pdf.addPage(page)
                        val image = JPEGFactory.createFromImage(pdf, bitmap, 0.94f)
                        val scale = minOf((size.width - 24f) / bitmap.width, (size.height - 24f) / bitmap.height)
                        val width = bitmap.width * scale
                        val height = bitmap.height * scale
                        PDPageContentStream(pdf, page).use {
                            it.drawImage(image, (size.width - width) / 2f, (size.height - height) / 2f, width, height)
                        }
                    } finally { bitmap.recycle() }
                }
                pdf.save(output)
            }
            PDDocument.load(output, MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)).use {
                check(it.numberOfPages == draft.pages.size)
            }
        } catch (failure: Throwable) { output.delete(); throw failure }
    }
}
