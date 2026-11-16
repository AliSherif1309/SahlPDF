package com.gitlab.mudlej.MjPdfReader.ui.tools

import android.os.Bundle
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.ui.ColorUtil
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class PageManagerActivity : AppCompatActivity() {
    private val model: PageManagerViewModel by viewModels()
    private lateinit var adapter: PageManagerAdapter
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var counter: TextView
    private lateinit var choose: MaterialButton
    private lateinit var save: MaterialButton
    private lateinit var undo: MaterialButton
    private lateinit var all: MaterialButton
    private lateinit var none: MaterialButton
    private lateinit var rotate: MaterialButton
    private lateinit var earlier: MaterialButton
    private lateinit var later: MaterialButton
    private lateinit var duplicate: MaterialButton
    private lateinit var delete: MaterialButton
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::load)
    }
    private val exporter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let(model::export)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Explicit insets protect controls on Android 15/16, including landscape cutouts.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        root.addView(MaterialToolbar(this).apply {
            setTitle(R.string.page_manager_title)
            setNavigationIcon(R.drawable.ic_nav_back)
            setNavigationContentDescription(R.string.pdf_tools_back)
            setNavigationOnClickListener { requestExit() }
        })
        fun action(row: LinearLayout, label: Int, idValue: Int, callback: () -> Unit): MaterialButton =
            MaterialButton(this).apply {
                id = idValue
                setText(label)
                minHeight = dp(48)
                setOnClickListener { callback() }
                row.addView(this)
            }
        fun row(): LinearLayout {
            val row = LinearLayout(this).apply { setPadding(dp(12), 0, dp(12), 0) }
            root.addView(HorizontalScrollView(this).apply {
                isFillViewport = true
                addView(row)
            })
            return row
        }
        val documents = row()
        choose = action(documents, R.string.page_manager_open, R.id.page_manager_open) {
            confirmDiscard { picker.launch(arrayOf("application/pdf")) }
        }
        undo = action(documents, R.string.page_manager_undo, R.id.page_manager_undo) { model.undo() }
        val selection = documents
        all = action(selection, R.string.page_manager_all, R.id.page_manager_all) { model.selectAll() }
        none = action(selection, R.string.page_manager_none, R.id.page_manager_none) { model.selectNone() }
        val actions = row()
        earlier = action(actions, R.string.page_manager_earlier, R.id.page_manager_earlier) { model.move(-1) }
        later = action(actions, R.string.page_manager_later, R.id.page_manager_later) { model.move(1) }
        rotate = action(actions, R.string.page_manager_rotate, R.id.page_manager_rotate) { model.rotate() }
        duplicate = action(actions, R.string.page_manager_duplicate, R.id.page_manager_duplicate) { model.duplicate() }
        delete = action(actions, R.string.page_manager_delete, R.id.page_manager_delete) {
            val count = model.state.value.plan?.selected?.size ?: 0
            MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.page_manager_delete_confirm, count))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.page_manager_delete) { _, _ -> model.delete() }
                .show()
        }
        counter = TextView(this).apply {
            id = R.id.page_manager_counter
            setPadding(dp(16), dp(4), dp(16), dp(4))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(counter)
        adapter = PageManagerAdapter(model, lifecycleScope)
        root.addView(RecyclerView(this).apply {
            id = R.id.page_manager_grid
            layoutManager = GridLayoutManager(this@PageManagerActivity,
                (resources.configuration.screenWidthDp / 170).coerceIn(1, 4))
            adapter = this@PageManagerActivity.adapter
            itemAnimator = null
            setPadding(dp(8), 0, dp(8), 0)
            clipToPadding = false
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        progress = ProgressBar(this)
        root.addView(progress)
        status = TextView(this).apply {
            id = R.id.page_manager_status
            setPadding(dp(16), dp(4), dp(16), dp(4))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(status)
        save = MaterialButton(this).apply {
            id = R.id.page_manager_save
            setText(R.string.pdf_tools_save)
            setOnClickListener { exporter.launch("SahlPDF-pages-" + System.currentTimeMillis() + ".pdf") }
        }
        root.addView(save)
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        ColorUtil.colorize(this, window, supportActionBar)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = requestExit()
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    val plan = state.plan
                    val count = plan?.pages?.size ?: 0
                    val selected = plan?.selected?.size ?: 0
                    val ready = !state.busy && plan != null
                    choose.isEnabled = !state.busy
                    undo.isEnabled = !state.busy && state.canUndo
                    all.isEnabled = ready && selected < count
                    none.isEnabled = ready && selected > 0
                    listOf(earlier, later, rotate).forEach { it.isEnabled = ready && selected > 0 }
                    duplicate.isEnabled = ready && selected > 0 && count + selected <= PageSelection.MAX_OUTPUT_PAGES
                    delete.isEnabled = ready && selected in 1 until count
                    save.isEnabled = ready
                    progress.visibility = if (state.busy) View.VISIBLE else View.GONE
                    counter.text = getString(R.string.page_manager_count, count, selected)
                    status.maxLines = if (plan == null) 5 else 2
                    status.ellipsize = android.text.TextUtils.TruncateAt.END
                    status.text = getString(when {
                        state.busy -> R.string.pdf_tools_working
                        state.message == PageManagerState.Message.FAILED -> R.string.page_manager_failed
                        state.message == PageManagerState.Message.SAVED -> R.string.pdf_tools_saved
                        plan == null -> R.string.page_manager_intro
                        else -> R.string.page_manager_help
                    })
                    adapter.submit(state)
                }
            }
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun requestExit() = confirmDiscard { finish() }
    private fun confirmDiscard(proceed: () -> Unit) {
        val state = model.state.value
        if (!state.dirty && !state.busy) {
            proceed()
        } else {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.page_manager_discard)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.page_manager_leave) { _, _ -> proceed() }
                .show()
        }
    }
    override fun onDestroy() {
        adapter.close()
        super.onDestroy()
    }
}
