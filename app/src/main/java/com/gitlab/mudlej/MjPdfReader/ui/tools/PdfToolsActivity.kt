package com.gitlab.mudlej.MjPdfReader.ui.tools

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.ui.ColorUtil
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class PdfToolsActivity : AppCompatActivity() {
    private val model: PdfToolsViewModel by viewModels()
    private lateinit var list: LinearLayout
    private lateinit var description: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var pageLayout: TextInputLayout
    private lateinit var pageInput: TextInputEditText
    private lateinit var add: MaterialButton
    private lateinit var create: MaterialButton
    private lateinit var save: MaterialButton
    private val modes = mutableListOf<MaterialButton>()
    private val names = mutableMapOf<Uri, String>()
    private var shownInputs: List<Uri>? = null
    private var shownBusy: Boolean? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        model.add(uris)
    }
    private val exporter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let(model::export)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = MaterialToolbar(this).apply {
            setTitle(R.string.pdf_tools_title)
            setNavigationIcon(R.drawable.ic_nav_back)
            setNavigationContentDescription(R.string.pdf_tools_back)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(TextView(this).apply { setText(R.string.pdf_tools_intro) })
        content.addView(MaterialButton(this).apply {
            setText(R.string.page_manager_title)
            setOnClickListener {
                startActivity(android.content.Intent(this@PdfToolsActivity, PageManagerActivity::class.java))
            }
        })
        val titles = listOf(R.string.pdf_tools_merge, R.string.pdf_tools_pages, R.string.pdf_tools_images)
        PdfTool.entries.forEachIndexed { index, tool ->
            val button = MaterialButton(this).apply {
                setText(titles[index])
                isCheckable = true
                setOnClickListener { model.select(tool) }
            }
            modes.add(button)
            content.addView(button)
        }
        description = TextView(this)
        content.addView(description)
        add = MaterialButton(this).apply {
            setText(R.string.pdf_tools_add)
            setOnClickListener {
                picker.launch(arrayOf(if (model.state.value.tool == PdfTool.IMAGES) "image/*" else "application/pdf"))
            }
        }
        content.addView(add)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)
        pageInput = TextInputEditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine()
            doAfterTextChanged { model.pages(it.toString()) }
        }
        pageLayout = TextInputLayout(this).apply {
            hint = getString(R.string.pdf_tools_range)
            helperText = getString(R.string.pdf_tools_range_help)
            addView(pageInput)
        }
        content.addView(pageLayout)
        create = MaterialButton(this).apply {
            setText(R.string.pdf_tools_create)
            setOnClickListener { model.create() }
        }
        content.addView(create)
        progress = ProgressBar(this)
        content.addView(progress)
        status = TextView(this).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(status)
        save = MaterialButton(this).apply {
            setText(R.string.pdf_tools_save)
            setOnClickListener { exporter.launch("SahlPDF-" + System.currentTimeMillis() + ".pdf") }
        }
        content.addView(save)
        setContentView(root)
        ColorUtil.colorize(this, window, supportActionBar)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect { render(it) } }
        }
    }

    private fun render(state: PdfToolsState) {
        modes.forEachIndexed { i, button ->
            button.isChecked = i == state.tool.ordinal
            button.isEnabled = !state.busy
        }
        description.setText(when (state.tool) {
            PdfTool.MERGE -> R.string.pdf_tools_merge_help
            PdfTool.PAGES -> R.string.pdf_tools_pages_help
            PdfTool.IMAGES -> R.string.pdf_tools_images_help
        })
        pageLayout.visibility = if (state.tool == PdfTool.PAGES) View.VISIBLE else View.GONE
        pageInput.isEnabled = !state.busy
        if (pageInput.text.toString() != state.pages) pageInput.setText(state.pages)
        add.isEnabled = !state.busy
        create.isEnabled = !state.busy && when (state.tool) {
            PdfTool.MERGE -> state.inputs.size >= 2
            PdfTool.PAGES -> state.inputs.size == 1 && state.pages.isNotBlank()
            PdfTool.IMAGES -> state.inputs.isNotEmpty()
        }
        progress.visibility = if (state.busy) View.VISIBLE else View.GONE
        save.visibility = if (state.output != null) View.VISIBLE else View.GONE
        save.isEnabled = !state.busy
        status.text = when {
            state.busy -> getString(R.string.pdf_tools_working)
            state.message == PdfToolsState.Message.CREATED -> getString(R.string.pdf_tools_created)
            state.message == PdfToolsState.Message.SAVED -> getString(R.string.pdf_tools_saved)
            state.message == PdfToolsState.Message.FAILED -> getString(R.string.pdf_tools_failed)
            else -> ""
        }
        if (shownInputs == state.inputs && shownBusy == state.busy) return
        shownInputs = state.inputs
        shownBusy = state.busy
        list.removeAllViews()
        state.inputs.forEachIndexed { index, uri ->
            list.addView(TextView(this).apply {
                text = getString(R.string.pdf_tools_file, index + 1, names.getOrPut(uri) { displayName(uri) })
            })
            val row = LinearLayout(this)
            fun action(label: Int, enabled: Boolean, callback: () -> Unit) {
                row.addView(MaterialButton(this).apply {
                    setText(label)
                    isEnabled = enabled && !state.busy
                    setOnClickListener { callback() }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            action(R.string.pdf_tools_up, index > 0) { model.move(index, -1) }
            action(R.string.pdf_tools_down, index < state.inputs.lastIndex) { model.move(index, 1) }
            action(R.string.pdf_tools_remove, true) { model.remove(index) }
            list.addView(row)
        }
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()
}
