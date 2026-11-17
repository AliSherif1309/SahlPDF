package com.gitlab.mudlej.MjPdfReader.scanner

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.ui.ColorUtil
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class ScannerActivity : AppCompatActivity() {
    private val model: ScannerViewModel by viewModels()
    private lateinit var empty: LinearLayout
    private lateinit var editor: LinearLayout
    private lateinit var crop: ScanCropView
    private lateinit var adapter: ScanStripAdapter
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var counter: TextView
    private lateinit var capture: MaterialButton
    private lateinit var gallery: MaterialButton
    private lateinit var fresh: MaterialButton
    private lateinit var undo: MaterialButton
    private lateinit var save: MaterialButton
    private val pageActions = mutableListOf<MaterialButton>()
    private val filters = mutableListOf<MaterialButton>()
    private var sourceKey: String? = null
    private var sourceJob: Job? = null
    private var previousDraftId: String? = null
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::import) }
    private val camera = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.captureReturned(it.resultCode == RESULT_OK)
    }
    private val exporter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) {
        uri -> uri?.let(model::export)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        root.addView(MaterialToolbar(this).apply {
            setTitle(R.string.scanner_title)
            setNavigationIcon(R.drawable.ic_nav_back)
            setNavigationContentDescription(R.string.pdf_tools_back)
            setNavigationOnClickListener { model.closeDraft { finish() } }
        })
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(android.widget.ScrollView(this).apply { isFillViewport = true; addView(content) },
            LinearLayout.LayoutParams(-1, 0, 1f))
        fun row(parent: LinearLayout = content): LinearLayout {
            val inner = LinearLayout(this).apply { setPadding(dp(8), 0, dp(8), 0) }
            parent.addView(HorizontalScrollView(this).apply { addView(inner) })
            return inner
        }
        fun button(row: LinearLayout, label: Int, idValue: Int = View.NO_ID, callback: () -> Unit): MaterialButton =
            MaterialButton(this).apply { id = idValue; setText(label); setOnClickListener { callback() }; row.addView(this) }
        empty = LinearLayout(this).apply {
            id = R.id.scanner_empty
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
            addView(TextView(this@ScannerActivity).apply {
                setText(R.string.scanner_empty_title)
                textSize = 24f
                setPadding(0, 0, 0, dp(12))
            })
            addView(TextView(this@ScannerActivity).apply {
                setText(R.string.scanner_empty_help)
                textSize = 16f
            })
        }
        content.addView(empty)
        val intake = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        content.addView(intake)
        capture = button(intake, R.string.scanner_capture, R.id.scanner_capture) { capture(false) }
        gallery = button(intake, R.string.scanner_import, R.id.scanner_import) { picker.launch(arrayOf("image/*")) }
        listOf(capture, gallery).forEach { it.layoutParams = LinearLayout.LayoutParams(-1, -2) }
        val draftActions = row()
        fresh = button(draftActions, R.string.scanner_new, R.id.scanner_new) {
            MaterialAlertDialogBuilder(this).setMessage(R.string.scanner_new_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.scanner_new) { _, _ -> model.newDraft() }.show()
        }
        undo = button(draftActions, R.string.page_manager_undo, R.id.scanner_undo) { model.undo() }
        editor = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(editor)
        adapter = ScanStripAdapter(model, lifecycleScope)
        editor.addView(RecyclerView(this).apply {
            id = R.id.scanner_pages
            layoutManager = LinearLayoutManager(this@ScannerActivity, RecyclerView.HORIZONTAL, false)
            adapter = this@ScannerActivity.adapter
            itemAnimator = null
        }, LinearLayout.LayoutParams(-1, dp(112)))
        counter = TextView(this).apply { setPadding(dp(16), 0, dp(16), 0) }
        editor.addView(counter)
        crop = ScanCropView(this).apply {
            id = R.id.scanner_crop
            onCornersChanged = model::corners
        }
        val configuration = resources.configuration
        val cropHeight = if (configuration.screenWidthDp > configuration.screenHeightDp)
            (configuration.screenHeightDp - 200).coerceAtLeast(160)
        else (configuration.screenWidthDp * 1.1f).toInt().coerceIn(280, 440)
        editor.addView(crop, LinearLayout.LayoutParams(-1, dp(cropHeight)))
        val actions = row(editor)
        pageActions += button(actions, R.string.scanner_preview, R.id.scanner_preview) { preview() }
        pageActions += button(actions, R.string.scanner_retake) { capture(true) }
        pageActions += button(actions, R.string.scanner_full_page) { model.corners(ScanGeometry.fullPage()) }
        pageActions += button(actions, R.string.page_manager_rotate) { model.rotate() }
        pageActions += button(actions, R.string.page_manager_earlier) { model.move(-1) }
        pageActions += button(actions, R.string.page_manager_later) { model.move(1) }
        pageActions += button(actions, R.string.page_manager_delete) {
            MaterialAlertDialogBuilder(this).setMessage(R.string.scanner_delete_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.page_manager_delete) { _, _ -> model.delete() }.show()
        }
        val filterRow = row(editor)
        val labels = listOf(R.string.scanner_natural, R.string.scanner_gray, R.string.scanner_mono)
        ScanFilter.entries.forEachIndexed { index, filter ->
            filters += button(filterRow, labels[index]) { model.filter(filter); render(model.state.value) }.apply { isCheckable = true }
        }
        status = TextView(this).apply {
            id = R.id.scanner_status
            setPadding(dp(16), dp(2), dp(16), dp(2))
            // Keep operation errors and guidance readable, including with large fonts.
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(status)
        progress = ProgressBar(this)
        root.addView(progress)
        save = MaterialButton(this).apply {
            id = R.id.scanner_save
            setText(R.string.scanner_save)
            setOnClickListener { exporter.launch("SahlScan-" + System.currentTimeMillis() + ".pdf") }
        }
        root.addView(save)
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { model.closeDraft { finish() } }
        })
        ColorUtil.colorize(this, window, supportActionBar)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect { render(it) } }
        }
    }
    private fun capture(retake: Boolean) {
        model.beginCapture(retake) { file ->
            camera.launch(Intent(this, ScanCameraActivity::class.java).putExtra(ScanCameraActivity.DESTINATION, file.absolutePath))
        }
    }
    private fun render(state: ScannerState) {
        val draft = state.draft
        val page = draft?.selectedPage()
        empty.visibility = if (page == null && !state.busy) View.VISIBLE else View.GONE
        editor.visibility = if (page != null) View.VISIBLE else View.GONE
        fresh.visibility = if (page != null || state.message == ScannerState.Message.FAILED) View.VISIBLE else View.GONE
        undo.visibility = if (state.canUndo) View.VISIBLE else View.GONE
        save.visibility = if (page != null) View.VISIBLE else View.GONE
        status.visibility = if (page == null && !state.busy && state.message == null) View.GONE else View.VISIBLE
        capture.isEnabled = !state.busy && draft != null && draft.pages.size < ScanStore.MAX_PAGES
        gallery.isEnabled = capture.isEnabled
        fresh.isEnabled = !state.busy
        undo.isEnabled = !state.busy && state.canUndo
        save.isEnabled = !state.busy && page != null
        pageActions.forEach { it.isEnabled = !state.busy && page != null }
        filters.forEachIndexed { index, button ->
            button.isEnabled = !state.busy && page != null
            button.isChecked = page?.filter?.ordinal == index
        }
        crop.isEnabled = !state.busy && page != null
        progress.visibility = if (state.busy) View.VISIBLE else View.GONE
        status.setText(when {
            state.busy -> R.string.pdf_tools_working
            state.message == ScannerState.Message.FAILED -> R.string.scanner_failed
            state.message == ScannerState.Message.SAVED -> R.string.scanner_saved
            state.message == ScannerState.Message.BLUR -> R.string.scanner_blur
            page == null -> R.string.scanner_intro
            else -> R.string.scanner_review
        })
        val count = draft?.pages?.size ?: 0
        counter.text = getString(R.string.scanner_count, if (page == null) 0 else draft!!.pages.indexOf(page) + 1, count)
        adapter.submit(state)
        if (page != null && draft != null) {
            crop.corners(page.corners)
            val key = draft.id + ":" + page.original
            if (key != sourceKey) {
                sourceKey = key; sourceJob?.cancel(); crop.image(null)
                sourceJob = lifecycleScope.launch {
                    try { crop.image(model.sourcePreview(draft, page)) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { status.setText(R.string.scanner_failed) }
                }
            }
        } else {
            sourceKey = null; sourceJob?.cancel(); crop.image(null)
        }
        if (previousDraftId != draft?.id) previousDraftId = draft?.id
    }
    private fun preview() {
        val state = model.state.value
        val draft = state.draft ?: return
        val page = draft.selectedPage() ?: return
        lifecycleScope.launch {
            try {
                val bitmap = model.processedPreview(draft, page)
                val image = ImageView(this@ScannerActivity).apply {
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(bitmap)
                    contentDescription = getString(R.string.scanner_preview)
                }
                val dialog = MaterialAlertDialogBuilder(this@ScannerActivity)
                    .setTitle(R.string.scanner_preview).setView(image)
                    .setPositiveButton(android.R.string.ok, null).create()
                dialog.setOnDismissListener { image.setImageDrawable(null); bitmap.recycle() }
                dialog.show()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status.setText(R.string.scanner_failed) }
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onDestroy() {
        sourceJob?.cancel()
        crop.release()
        adapter.close()
        super.onDestroy()
    }
}
