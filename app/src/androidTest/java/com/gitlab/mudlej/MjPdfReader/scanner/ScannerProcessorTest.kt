package com.gitlab.mudlej.MjPdfReader.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScannerProcessorTest {
    private lateinit var context: Context
    private lateinit var store: ScanStore
    private lateinit var draft: ScanDraft
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        store = ScanStore(context)
        context.getSharedPreferences("sahl-scanner", 0).edit().clear().commit()
        draft = store.fresh()
    }
    @After fun cleanup() {
        store.delete(draft)
        context.getSharedPreferences("sahl-scanner", 0).edit().clear().commit()
    }
    private fun fixture(perspective: Boolean = false): File {
        val file = File(store.directory(draft.id), "import-" + UUID.randomUUID() + ".img")
        val bitmap = Bitmap.createBitmap(1000, 1200, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.drawColor(Color.rgb(35, 45, 50))
            paint.color = Color.WHITE
            val polygon = Path().apply {
                moveTo(150f, 100f); lineTo(if (perspective) 850f else 800f, if (perspective) 160f else 100f)
                lineTo(800f, 1100f); lineTo(if (perspective) 200f else 150f, 1100f); close()
            }
            canvas.drawPath(polygon, paint)
            paint.color = Color.rgb(185, 80, 80)
            paint.textSize = 55f
            canvas.drawText("SERVICE REPORT", 230f, 350f, paint)
            paint.color = Color.rgb(205, 205, 205)
            paint.strokeWidth = 4f
            canvas.drawLine(240f, 550f, 700f, 550f, paint)
            paint.color = Color.rgb(70, 110, 180)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 5f
            canvas.drawOval(450f, 700f, 650f, 820f, paint)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        return file
    }
    @Test fun detectsPerspectivePageInsteadOfUsingImageBorders() {
        val file = fixture(true)
        val found = ScanImageProcessor().analyze(file)
        assertTrue(found.detected)
        assertTrue(ScanGeometry.valid(found.corners))
        assertTrue(found.corners[0].x in .10f.. .21f)
        assertTrue(found.corners[0].y in .04f.. .15f)
        assertTrue(found.corners[2].x in .74f.. .87f)
        assertTrue(found.corners[2].y in .85f.. .98f)
    }
    @Test fun naturalProcessingPreservesFaintMarksAndColorWithoutChangingOriginal() {
        val file = fixture()
        val before = file.readBytes()
        val page = ScanPage(UUID.randomUUID().toString(), file.name,
            listOf(ScanPoint(.15f,.08333f), ScanPoint(.8f,.08333f), ScanPoint(.8f,.91667f), ScanPoint(.15f,.91667f)))
        val output = ScanImageProcessor().process(file, page)
        try {
            val faint = output.getPixel((output.width * .45).toInt(), (output.height * .45).toInt())
            assertTrue(Color.red(faint) in 160..235)
            assertTrue(kotlin.math.abs(Color.red(faint) - Color.green(faint)) <= 3)
            var colored = false
            for (y in output.height / 6 until output.height / 3 step 5) {
                for (x in output.width / 8 until output.width * 7 / 8 step 5) {
                    val pixel = output.getPixel(x, y)
                    if (Color.red(pixel) > Color.blue(pixel) + 30) colored = true
                }
            }
            assertTrue(colored)
        } finally { output.recycle() }
        assertArrayEquals(before, file.readBytes())
    }
    @Test fun grayscaleAndMonoAreReversibleOptionsAndRotationIsApplied() {
        val file = fixture()
        val original = file.readBytes()
        val page = ScanPage(UUID.randomUUID().toString(), file.name, ScanGeometry.fullPage(), 90, ScanFilter.GRAY)
        val gray = ScanImageProcessor().process(file, page, 600)
        try {
            assertTrue(gray.width > gray.height)
            val p = gray.getPixel(gray.width / 3, gray.height / 3)
            assertEquals(Color.red(p), Color.green(p))
            assertEquals(Color.green(p), Color.blue(p))
        } finally { gray.recycle() }
        val mono = ScanImageProcessor().process(file, page.copy(rotation = 0, filter = ScanFilter.MONO), 600)
        try {
            for (y in 0 until mono.height step 10) for (x in 0 until mono.width step 10) {
                assertTrue(Color.red(mono.getPixel(x, y)) in listOf(0, 255))
            }
        } finally { mono.recycle() }
        assertArrayEquals(original, file.readBytes())
    }
    @Test fun jpegExifOrientationIsAppliedBeforeCrop() {
        val file = File(store.directory(draft.id), "capture-" + UUID.randomUUID() + ".jpg")
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) } }
        finally { bitmap.recycle() }
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val decoded = ScanImageProcessor().decode(file, 1600)
        try { assertEquals(80, decoded.width); assertEquals(120, decoded.height) }
        finally { decoded.recycle() }
    }
    @Test fun draftReopensWithCropFiltersAndOriginalBytes() {
        val file = fixture()
        val original = file.readBytes()
        val page = ScanPage(UUID.randomUUID().toString(), file.name, ScanGeometry.fullPage(), 180, ScanFilter.GRAY)
        draft = draft.copy(pages = listOf(page), selected = page.id)
        store.save(draft)
        assertEquals(draft, ScanStore(context).load())
        assertArrayEquals(original, store.original(draft, page).readBytes())
    }
    @Test fun scannedPdfHasOrderedImagesAndCorrectPageOrientation() = runBlocking {
        val file = fixture()
        val a = ScanPage(UUID.randomUUID().toString(), file.name, ScanGeometry.fullPage())
        val b = a.copy(id = UUID.randomUUID().toString(), rotation = 90)
        draft = draft.copy(pages = listOf(a,b), selected = b.id)
        store.save(draft)
        val output = File(context.cacheDir, "scanner-test-" + System.nanoTime() + ".pdf")
        try {
            ScanPdfWriter(context).create(draft, output)
            PDFBoxResourceLoader.init(context)
            PDDocument.load(output).use {
                assertEquals(2, it.numberOfPages)
                assertTrue(it.getPage(0).mediaBox.height > it.getPage(0).mediaBox.width)
                assertTrue(it.getPage(1).mediaBox.width > it.getPage(1).mediaBox.height)
                assertTrue(it.getPage(0).resources.xObjectNames.iterator().hasNext())
            }
            val reader = com.shockwave.pdfium.PdfiumCore(context)
            val document = reader.newDocument(output.readBytes())
            try {
                assertEquals(2, reader.getPageCount(document))
                reader.openPage(document, 0)
                assertTrue(reader.getPageWidthPoint(document, 0) > 0)
            } finally { reader.closeDocument(document) }
        } finally { output.delete() }
    }
    @Test fun draftRejectsTraversalAndMalformedCrop() {
        val file = fixture()
        val page = ScanPage(UUID.randomUUID().toString(), "../outside.img", ScanGeometry.fullPage())
        assertThrows(IllegalArgumentException::class.java) { store.original(draft, page) }
        val bad = page.copy(original = file.name, corners = List(4) { ScanPoint(0f,0f) })
        assertThrows(IllegalArgumentException::class.java) { store.save(draft.copy(pages = listOf(bad))) }
    }
}
