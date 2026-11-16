package com.gitlab.mudlej.MjPdfReader.ui.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.R
import com.google.android.material.button.MaterialButton
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageManagerTest {
    private lateinit var context: Context
    private lateinit var directory: File
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
        directory = File(context.cacheDir, "page-manager-test-" + System.nanoTime()).apply { mkdirs() }
    }
    @After fun cleanup() { directory.deleteRecursively() }
    private fun fixture(): File {
        val file = File(directory, "source.pdf")
        PDDocument().use { doc ->
            listOf("Alpha", "Beta", "Gamma").forEachIndexed { index, label ->
                val page = PDPage(PDRectangle(400f, 600f))
                page.cropBox = PDRectangle(10f, 20f, 360f, 550f)
                page.rotation = if (index == 1) 90 else 0
                doc.addPage(page)
                PDPageContentStream(doc, page).use {
                    it.beginText()
                    it.setFont(PDType1Font.HELVETICA, 14f)
                    it.newLineAtOffset(50f, 400f)
                    it.showText(label)
                    it.endText()
                }
            }
            doc.save(file)
        }
        return file
    }
    @Test fun outputKeepsOrderSearchableTextCropRotationAndOriginalBytes() = runBlocking {
        val input = fixture()
        val original = input.readBytes()
        val snapshot = File(directory, "snapshot.pdf")
        val engine = PageManagerEngine(context)
        assertEquals(3, engine.stage(Uri.fromFile(input), snapshot))
        // Gamma, Alpha, Gamma: duplicated identity, with different relative rotations.
        val plan = PagePlan(listOf(PlannedPage(0, 2, 90), PlannedPage(1, 0), PlannedPage(2, 2, 180)))
        val output = File(directory, "result.pdf")
        engine.create(snapshot, plan, output)
        PDDocument.load(output).use { doc ->
            assertEquals(3, doc.numberOfPages)
            val stripper = PDFTextStripper()
            assertEquals(listOf("Gamma", "Alpha", "Gamma"), (1..3).map { n ->
                stripper.startPage = n; stripper.endPage = n; stripper.getText(doc).trim()
            })
            assertEquals(listOf(90, 0, 180), (0..2).map { doc.getPage(it).rotation })
            assertEquals(360f, doc.getPage(0).cropBox.width, 0.1f)
            assertEquals(10f, doc.getPage(0).cropBox.lowerLeftX, 0.1f)
        }
        assertArrayEquals(original, input.readBytes())
        assertArrayEquals(original, snapshot.readBytes())
    }
    @Test fun relativeRotationAddsToOriginalOrientation() = runBlocking {
        val input = fixture()
        val output = File(directory, "rotation.pdf")
        PageManagerEngine(context).create(input, PagePlan(listOf(PlannedPage(0, 1, 270))), output)
        PDDocument.load(output).use { assertEquals(0, it.getPage(0).rotation) }
    }
    @Test fun invalidPlanDeletesPartialOutput() = runBlocking {
        val input = fixture()
        val output = File(directory, "invalid.pdf").apply { writeText("old partial") }
        try {
            PageManagerEngine(context).create(input, PagePlan(listOf(PlannedPage(0, 9))), output)
            fail("Expected bad page to fail")
        } catch (_: IllegalArgumentException) { }
        assertFalse(output.exists())
    }
    @Test fun protectedInputIsRejectedAndSnapshotRemoved() = runBlocking {
        val input = fixture()
        val encrypted = File(directory, "protected.pdf")
        PDDocument.load(input).use {
            // Empty user password still counts as encrypted and must be rejected.
            it.protect(StandardProtectionPolicy("owner", "", AccessPermission()))
            it.save(encrypted)
        }
        val snapshot = File(directory, "private.pdf")
        try {
            PageManagerEngine(context).stage(Uri.fromFile(encrypted), snapshot)
            fail("Expected protected input rejection")
        } catch (_: IllegalArgumentException) { }
        assertFalse(snapshot.exists())
    }
    @Test fun thumbnailsAreBoundedAndRenderSourcePage() = runBlocking {
        val input = fixture()
        val bitmap = PageManagerEngine(context).thumbnail(input, 0)
        try {
            assertTrue(bitmap.width > 0 && bitmap.height > 0)
            assertTrue(maxOf(bitmap.width, bitmap.height) <= 320)
            var nonWhite = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) != android.graphics.Color.WHITE) nonWhite++
            }
            assertTrue(nonWhite > 0)
        } finally { bitmap.recycle() }
    }
    @Test fun editorActionsAndSelectionSurviveActivityRecreation() {
        val input = fixture()
        val original = input.readBytes()
        val saved = File(directory, "saved-copy.pdf")
        ActivityScenario.launch<PageManagerActivity>(Intent(context, PageManagerActivity::class.java)).use { scenario ->
            lateinit var model: PageManagerViewModel
            scenario.onActivity {
                model = ViewModelProvider(it)[PageManagerViewModel::class.java]
                model.load(Uri.fromFile(input))
            }
            val deadline = SystemClock.uptimeMillis() + 30000
            while (model.state.value.busy && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            assertNotNull(model.state.value.plan)
            scenario.onActivity { model.select(1) }
            // render() is collected asynchronously; invoke the UI action after the state has rendered.
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                activity.findViewById<MaterialButton>(R.id.page_manager_rotate).performClick()
                assertEquals(90, model.state.value.plan!!.pages[1].rotation)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val restored = ViewModelProvider(activity)[PageManagerViewModel::class.java]
                assertSame(model, restored)
                assertEquals(setOf(1L), restored.state.value.plan!!.selected)
                assertEquals(90, restored.state.value.plan!!.pages[1].rotation)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                activity.findViewById<MaterialButton>(R.id.page_manager_duplicate).performClick()
                assertEquals(listOf(0, 1, 1, 2), model.state.value.plan!!.pages.map { it.sourceIndex })
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                activity.findViewById<MaterialButton>(R.id.page_manager_undo).performClick()
                assertEquals(listOf(0, 1, 2), model.state.value.plan!!.pages.map { it.sourceIndex })
                assertEquals(90, model.state.value.plan!!.pages[1].rotation)
                model.export(Uri.fromFile(saved))
            }
            val exportDeadline = SystemClock.uptimeMillis() + 30000
            while (model.state.value.busy && SystemClock.uptimeMillis() < exportDeadline) SystemClock.sleep(50)
            assertEquals(PageManagerState.Message.SAVED, model.state.value.message)
            PDDocument.load(saved).use { doc ->
                assertEquals(3, doc.numberOfPages)
                assertEquals(180, doc.getPage(1).rotation)
            }
            assertArrayEquals(original, input.readBytes())
        }
    }
}
