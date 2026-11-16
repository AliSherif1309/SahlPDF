package com.gitlab.mudlej.MjPdfReader.ui.tools

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException

enum class PdfTool { MERGE, PAGES, IMAGES }

/** All entry points are invoked on Dispatchers.IO. Inputs are only ever opened for reading. */
class PdfToolsEngine(private val context: Context) {
    suspend fun create(tool: PdfTool, inputs: List<Uri>, pages: String, output: File) {
        PDFBoxResourceLoader.init(context.applicationContext)
        require(inputs.isNotEmpty() && inputs.size <= 50)
        val memory = MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)
        try {
            PDDocument(memory).use { destination ->
                when (tool) {
                    PdfTool.MERGE -> {
                        require(inputs.size >= 2)
                        val merger = PDFMergerUtility()
                        for (uri in inputs) {
                            currentCoroutineContext().ensureActive()
                            load(uri).use { source ->
                                require(!source.isEncrypted)
                                require(destination.numberOfPages.toLong() + source.numberOfPages <= PageSelection.MAX_OUTPUT_PAGES)
                                merger.appendDocument(destination, source)
                            }
                        }
                    }
                    PdfTool.PAGES -> {
                        require(inputs.size == 1)
                        load(inputs.single()).use { source ->
                            require(!source.isEncrypted)
                            for (index in PageSelection.parse(pages, source.numberOfPages)) {
                                currentCoroutineContext().ensureActive()
                                val page = source.getPage(index)
                                // Import clones page content; materialize inherited resources before the source closes.
                                destination.importPage(page).resources = page.resources
                            }
                            require(destination.numberOfPages > 0)
                            destination.save(output)
                        }
                        verify(output, destination.numberOfPages)
                        return
                    }
                    PdfTool.IMAGES -> {
                        for (uri in inputs) {
                            currentCoroutineContext().ensureActive()
                            addImage(destination, uri)
                        }
                    }
                }
                require(destination.numberOfPages > 0)
                destination.save(output)
                verify(output, destination.numberOfPages)
            }
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        }
    }

    private fun load(uri: Uri): PDDocument {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open input")
        return input.use { PDDocument.load(it, MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)) }
    }

    private fun verify(output: File, expectedPages: Int) {
        check(output.length() > 0)
        PDDocument.load(output, MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)).use {
            check(it.numberOfPages == expectedPages)
        }
    }

    private fun addImage(document: PDDocument, uri: Uri) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val original = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("Unsupported image")
        var bitmap = original
        try {
            val orientation = resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            }
            if (!matrix.isIdentity) bitmap = android.graphics.Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
            val size = if (bitmap.width > bitmap.height) PDRectangle(842f, 595f) else PDRectangle.A4
            val page = PDPage(size)
            document.addPage(page)
            val image = JPEGFactory.createFromImage(document, bitmap, 0.9f)
            val scale = minOf((size.width - 48f) / bitmap.width, (size.height - 48f) / bitmap.height)
            val width = bitmap.width * scale
            val height = bitmap.height * scale
            PDPageContentStream(document, page).use {
                it.drawImage(image, (size.width - width) / 2f, (size.height - height) / 2f, width, height)
            }
        } finally {
            if (bitmap !== original) bitmap.recycle()
            original.recycle()
        }
    }
}
