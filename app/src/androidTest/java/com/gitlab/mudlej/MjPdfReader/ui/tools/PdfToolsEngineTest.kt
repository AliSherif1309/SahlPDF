package com.gitlab.mudlej.MjPdfReader.ui.tools

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfToolsEngineTest {
    private lateinit var context: Context
    private lateinit var directory: File
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
        directory = File(context.cacheDir, "pdf-tools-test-" + System.nanoTime()).apply { mkdirs() }
    }
    @After fun cleanup() { directory.deleteRecursively() }
    private fun document(name: String, vararg labels: String): File {
        val file = File(directory, name)
        PDDocument().use { doc ->
            labels.forEach { label ->
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use {
                    it.beginText()
                    it.setFont(PDType1Font.HELVETICA, 12f)
                    it.newLineAtOffset(30f, 700f)
                    it.showText(label)
                    it.endText()
                }
            }
            doc.save(file)
        }
        return file
    }
    private fun text(file: File): String = PDDocument.load(file).use { PDFTextStripper().getText(it).trim() }

    @Test fun mergeKeepsSearchableTextAndOriginalBytes() = runBlocking {
        val a = document("a.pdf", "Alpha")
        val b = document("b.pdf", "Beta")
        val before = a.readBytes()
        val output = File(directory, "merged.pdf")
        PdfToolsEngine(context).create(PdfTool.MERGE, listOf(Uri.fromFile(b), Uri.fromFile(a)), "", output)
        PDDocument.load(output).use { assertEquals(2, it.numberOfPages) }
        assertTrue(text(output).indexOf("Beta") < text(output).indexOf("Alpha"))
        assertArrayEquals(before, a.readBytes())
    }
    @Test fun extractReordersAndDuplicatesActualPageContent() = runBlocking {
        val input = document("source.pdf", "Alpha", "Beta", "Gamma")
        val before = input.readBytes()
        val output = File(directory, "pages.pdf")
        PdfToolsEngine(context).create(PdfTool.PAGES, listOf(Uri.fromFile(input)), "3,1,3", output)
        PDDocument.load(output).use {
            assertEquals(3, it.numberOfPages)
            val stripper = PDFTextStripper()
            assertEquals(listOf("Gamma", "Alpha", "Gamma"), (1..3).map { page ->
                stripper.startPage = page
                stripper.endPage = page
                stripper.getText(it).trim()
            })
        }
        assertArrayEquals(before, input.readBytes())
    }
    @Test fun invalidRangeLeavesNoPartialOutput() = runBlocking {
        val source = document("source.pdf", "Alpha")
        val output = File(directory, "invalid.pdf")
        try {
            PdfToolsEngine(context).create(PdfTool.PAGES, listOf(Uri.fromFile(source)), "2", output)
            fail("Expected invalid range to fail")
        } catch (_: IllegalArgumentException) { }
        assertFalse(output.exists())
    }
    @Test fun protectedPdfDoesNotProduceOutput() = runBlocking {
        val source = document("protected.pdf", "Private")
        PDDocument.load(source).use {
            it.protect(StandardProtectionPolicy("owner", "reader", AccessPermission()))
            it.save(File(directory, "encrypted.pdf"))
        }
        val output = File(directory, "output.pdf")
        try {
            PdfToolsEngine(context).create(PdfTool.PAGES, listOf(Uri.fromFile(File(directory, "encrypted.pdf"))), "1", output)
            fail("Expected protected document to fail")
        } catch (_: java.io.IOException) { }
        assertFalse(output.exists())
    }
    @Test fun imagesProduceOneA4PagePerImage() = runBlocking {
        val image = File(directory, "image.png")
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        try { image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
        val output = File(directory, "images.pdf")
        PdfToolsEngine(context).create(PdfTool.IMAGES, listOf(Uri.fromFile(image), Uri.fromFile(image)), "", output)
        PDDocument.load(output).use {
            assertEquals(2, it.numberOfPages)
            assertEquals(842f, it.getPage(0).mediaBox.width, 0.1f)
            assertTrue(it.getPage(0).resources.xObjectNames.iterator().hasNext())
        }
    }
}
