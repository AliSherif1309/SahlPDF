package com.gitlab.mudlej.MjPdfReader.scanner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.gitlab.mudlej.MjPdfReader.R
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScannerWorkflowTest {
    @get:Rule val cameraPermission = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    private lateinit var context: Context
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("sahl-scanner", 0)
        val store = ScanStore(context)
        runCatching { store.delete(store.load()) }
        prefs.edit().clear().commit()
    }
    @After fun cleanup() {
        val store = ScanStore(context)
        runCatching { store.delete(store.load()) }
        context.getSharedPreferences("sahl-scanner", 0).edit().clear().commit()
    }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 45000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue("Timed out waiting for scanner state", condition())
    }
    private fun fixture(): File {
        val file = File(context.cacheDir, "scanner-workflow-" + System.nanoTime() + ".png")
        val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.DKGRAY)
            val paint = Paint().apply { color = Color.WHITE }
            canvas.drawRect(80f, 60f, 520f, 740f, paint)
            paint.color = Color.BLUE; paint.textSize = 36f
            canvas.drawText("REPORT", 160f, 300f, paint)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
        return file
    }
    @Test fun importEditingRecreationCloseAndReopenRetainDraftAndExportPdf() {
        val input = fixture()
        val original = input.readBytes()
        val output = File(context.cacheDir, "scanner-workflow.pdf")
        try {
            ActivityScenario.launch<ScannerActivity>(Intent(context, ScannerActivity::class.java)).use { scenario ->
                lateinit var model: ScannerViewModel
                scenario.onActivity { model = ViewModelProvider(it)[ScannerViewModel::class.java] }
                waitFor { !model.state.value.busy }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity {
                    assertTrue(it.findViewById<android.view.View>(R.id.scanner_empty).isShown)
                    assertFalse(it.findViewById<android.view.View>(R.id.scanner_crop).isShown)
                    assertFalse(it.findViewById<android.view.View>(R.id.scanner_save).isShown)
                    assertTrue(it.findViewById<android.view.View>(R.id.scanner_capture).isEnabled)
                    assertTrue(it.findViewById<android.view.View>(R.id.scanner_import).isEnabled)
                    model.import(Uri.fromFile(input))
                }
                waitFor { !model.state.value.busy }
                assertEquals(1, model.state.value.draft!!.pages.size)
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity {
                    assertFalse(it.findViewById<android.view.View>(R.id.scanner_empty).isShown)
                    assertTrue(it.findViewById<android.view.View>(R.id.scanner_crop).isShown)
                    assertTrue(it.findViewById<android.view.View>(R.id.scanner_save).isShown)
                }
                scenario.onActivity { model.rotate(); model.filter(ScanFilter.GRAY) }
                scenario.recreate()
                scenario.onActivity {
                    val restored = ViewModelProvider(it)[ScannerViewModel::class.java]
                    assertSame(model, restored)
                    assertEquals(90, restored.state.value.draft!!.pages.single().rotation)
                    model.export(Uri.fromFile(output))
                }
                waitFor { !model.state.value.busy }
                assertEquals(ScannerState.Message.SAVED, model.state.value.message)
                PDFBoxResourceLoader.init(context)
                PDDocument.load(output).use { assertEquals(1, it.numberOfPages) }
                assertArrayEquals(original, input.readBytes())
            }
            ActivityScenario.launch<ScannerActivity>(Intent(context, ScannerActivity::class.java)).use { scenario ->
                lateinit var reopened: ScannerViewModel
                scenario.onActivity { reopened = ViewModelProvider(it)[ScannerViewModel::class.java] }
                waitFor { !reopened.state.value.busy }
                assertEquals(90, reopened.state.value.draft!!.pages.single().rotation)
                assertEquals(ScanFilter.GRAY, reopened.state.value.draft!!.pages.single().filter)
            }
        } finally { input.delete(); output.delete() }
    }
    @Test fun embeddedCameraCaptureReturnsAReviewablePageToScanner() {
        ActivityScenario.launch<ScannerActivity>(Intent(context, ScannerActivity::class.java)).use { scenario ->
            lateinit var model: ScannerViewModel
            scenario.onActivity { model = ViewModelProvider(it)[ScannerViewModel::class.java] }
            waitFor { !model.state.value.busy }
            scenario.onActivity { it.findViewById<android.view.View>(R.id.scanner_capture).performClick() }
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val shutter = requireNotNull(device.wait(Until.findObject(By.res(context.packageName, "scanner_shutter")), 45000)) {
                "Embedded camera did not open"
            }
            waitFor { shutter.isEnabled }
            shutter.click()
            waitFor { !model.state.value.busy && model.state.value.draft?.pages?.size == 1 }
            val draft = model.state.value.draft!!
            val file = ScanStore(context).original(draft, draft.pages.single())
            assertTrue(file.length() > 100)
            assertTrue(ScanGeometry.valid(draft.pages.single().corners))
        }
    }
}
