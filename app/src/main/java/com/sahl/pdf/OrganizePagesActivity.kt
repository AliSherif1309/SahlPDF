package com.sahl.pdf

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.InputType
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Visual page organizer & PDF tools:
 * - Multi-select & Select All
 * - Reorder, rotate 90°, delete, save a copy
 * - Extract selected/range OR Split whole PDF into a folder
 * - Export pages as JPEG images
 * - Compress PDF
 */
class OrganizePagesActivity : Activity() {

    private lateinit var pal: Palette
    private lateinit var grid: GridView
    private lateinit var progress: ProgressBar
    private lateinit var titleView: TextView
    private lateinit var selectAllBtn: TextView
    private val io = Executors.newFixedThreadPool(2)
    private val thumbs = LruCache<Int, Bitmap>(16 * 1024 * 1024)

    private var doc: PdfDoc? = null
    private lateinit var uri: Uri
    private var docName = "document"
    private var password: String? = null
    private val pages = ArrayList<PdfTools.PageSpec>()
    private val selected = LinkedHashSet<Int>()

    private var pendingExportPages: List<PdfTools.PageSpec>? = null
    private var pendingCompress: Pair<Int, Int>? = null // maxSidePx to jpegQuality
    private var pendingTreeMode: TreeMode = TreeMode.SPLIT_WHOLE
    private var pendingSplitChunk = 1

    private enum class TreeMode { SPLIT_WHOLE, EXPORT_IMAGES }

    private fun dp(v: Number) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SahlCore.catchCrashes(this)
        pal = Palette(this)
        uri = intent.data ?: run { finish(); return }
        docName = intent.getStringExtra(EXTRA_NAME) ?: "document"
        password = intent.getStringExtra(EXTRA_PASSWORD)
        buildUi()
        loadDoc()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
        doc?.close()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.bg)
            fitsSystemWindows = true
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(12), dp(6))
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(pal.text)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            Ui.focusable(this, 0, dp(22).toFloat(), pal.gold)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))

        titleView = TextView(this).apply {
            text = getString(R.string.organize_pages)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(pal.text)
            setPadding(dp(4), 0, dp(8), 0)
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))

        header.addView(actionPill(getString(R.string.save_copy), pal.accent, Color.WHITE) {
            startSave(pages.toList(), "${baseName()}-organized.pdf")
        })
        root.addView(header)

        val toolsStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), dp(8))
        }
        selectAllBtn = actionPill(getString(R.string.select_all), pal.card, pal.text) { toggleSelectAll() }
        toolsStrip.addView(selectAllBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        toolsStrip.addView(actionPill(getString(R.string.split_extract), pal.card, pal.text) { showSplitDialog() },
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        toolsStrip.addView(actionPill(getString(R.string.more_tools), pal.card, pal.text) { showMoreTools() })
        root.addView(toolsStrip)

        val body = FrameLayout(this)
        grid = GridView(this).apply {
            numColumns = if (resources.configuration.screenWidthDp >= 600) 4 else 3
            horizontalSpacing = dp(10)
            verticalSpacing = dp(10)
            setPadding(dp(12), dp(4), dp(12), dp(12))
            clipToPadding = false
            adapter = PageAdapter()
            setOnItemClickListener { _, _, pos, _ ->
                if (!selected.add(pos)) selected.remove(pos)
                updateHeader()
                (adapter as BaseAdapter).notifyDataSetChanged()
            }
        }
        body.addView(grid, FrameLayout.LayoutParams(-1, -1))
        progress = ProgressBar(this)
        body.addView(progress, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(pal.card)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        bar.addView(actionPill("◀", pal.bg, pal.text) { moveSelected(-1) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        bar.addView(actionPill("▶", pal.bg, pal.text) { moveSelected(1) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        bar.addView(actionPill("↻ 90°", pal.bg, pal.text) { rotateSelected() },
            LinearLayout.LayoutParams(0, -2, 1.2f).apply { marginEnd = dp(6) })
        bar.addView(actionPill(getString(R.string.delete), Color.parseColor("#B83B3B"), Color.WHITE) { deleteSelected() },
            LinearLayout.LayoutParams(0, -2, 1.2f))
        root.addView(bar)

        setContentView(root)
    }

    private fun baseName() = docName.removeSuffix(".pdf").removeSuffix(".PDF")

    private fun actionPill(label: String, bg: Int, fg: Int, click: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(fg)
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(11), dp(8), dp(11), dp(8))
        Ui.focusable(this, bg, dp(14).toFloat(), pal.gold)
        setOnClickListener { click() }
    }

    private fun loadDoc() {
        io.execute {
            val opened = try { PdfDoc.open(this, uri, password) } catch (_: Throwable) { null }
            runOnUiThread {
                if (isFinishing) { opened?.close(); return@runOnUiThread }
                progress.visibility = View.GONE
                if (opened == null) {
                    Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
                    finish()
                    return@runOnUiThread
                }
                doc = opened
                pages.clear()
                for (i in 0 until opened.pageCount) pages += PdfTools.PageSpec(i, 0)
                selected.clear()
                if (pages.isNotEmpty()) selected += 0
                updateHeader()
                (grid.adapter as BaseAdapter).notifyDataSetChanged()
            }
        }
    }

    private fun updateHeader() {
        titleView.text = if (selected.isEmpty()) "${getString(R.string.organize_pages)} (${pages.size})"
        else "${getString(R.string.organize_pages)} · ${selected.size}/${pages.size}"
        val allSelected = pages.isNotEmpty() && selected.size == pages.size
        selectAllBtn.text = getString(if (allSelected) R.string.deselect_all else R.string.select_all)
    }

    private fun toggleSelectAll() {
        if (pages.isEmpty()) return
        if (selected.size == pages.size) selected.clear()
        else {
            selected.clear()
            for (i in pages.indices) selected += i
        }
        updateHeader()
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun moveSelected(delta: Int) {
        if (selected.isEmpty()) return
        val sorted = if (delta < 0) selected.sorted() else selected.sortedDescending()
        val nextSel = LinkedHashSet<Int>()
        for (pos in sorted) {
            val target = pos + delta
            if (target !in pages.indices || (target in selected && target !in nextSel)) {
                nextSel += pos
                continue
            }
            val item = pages.removeAt(pos)
            pages.add(target, item)
            nextSel += target
        }
        selected.clear()
        selected += nextSel
        updateHeader()
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun rotateSelected() {
        if (selected.isEmpty()) return
        for (pos in selected) {
            if (pos in pages.indices) {
                val cur = pages[pos]
                pages[pos] = cur.copy(rotationDeg = (cur.rotationDeg + 90) % 360)
            }
        }
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun deleteSelected() {
        if (selected.isEmpty()) return
        if (pages.size - selected.size < 1) {
            Toast.makeText(this, R.string.keep_one_page, Toast.LENGTH_SHORT).show()
            return
        }
        for (pos in selected.sortedDescending()) {
            if (pos in pages.indices) pages.removeAt(pos)
        }
        selected.clear()
        if (pages.isNotEmpty()) selected += 0
        updateHeader()
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun showSplitDialog() {
        val opts = arrayOf(
            getString(R.string.split_whole_pdf),
            getString(R.string.split_every_n),
            getString(R.string.extract_range),
            getString(R.string.extract_selected),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.split_extract)
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> {
                        pendingTreeMode = TreeMode.SPLIT_WHOLE
                        pendingSplitChunk = 1
                        pickOutputFolder()
                    }
                    1 -> askSplitChunkSize()
                    2 -> askExtractRange()
                    3 -> {
                        val specs = selected.sorted().mapNotNull { pages.getOrNull(it) }
                        if (specs.isEmpty()) Toast.makeText(this, R.string.select_pages_first, Toast.LENGTH_SHORT).show()
                        else startSave(specs, "${baseName()}-extracted.pdf")
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askSplitChunkSize() {
        val input = EditText(this).apply {
            hint = getString(R.string.split_every_n_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("2")
            setTextColor(pal.text)
            setHintTextColor(pal.sub)
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle(R.string.split_every_n)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val n = (input.text.toString().toIntOrNull() ?: 1).coerceAtLeast(1)
                pendingTreeMode = TreeMode.SPLIT_WHOLE
                pendingSplitChunk = n
                pickOutputFolder()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askExtractRange() {
        val d = doc ?: return
        val input = EditText(this).apply {
            hint = getString(R.string.split_hint, d.pageCount)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(pal.text)
            setHintTextColor(pal.sub)
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle(R.string.extract_range)
            .setView(box)
            .setPositiveButton(R.string.save_copy) { _, _ ->
                val indices = PdfTools.parsePageRanges(input.text.toString(), d.pageCount)
                if (indices.isEmpty()) {
                    Toast.makeText(this, R.string.invalid_range, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                startSave(indices.map { PdfTools.PageSpec(it, 0) }, "${baseName()}-split.pdf")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showMoreTools() {
        val opts = arrayOf(
            getString(R.string.compress_pdf),
            getString(R.string.export_images),
            getString(R.string.reverse_order),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.more_tools)
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> askCompressLevel()
                    1 -> {
                        pendingTreeMode = TreeMode.EXPORT_IMAGES
                        pickOutputFolder()
                    }
                    2 -> {
                        pages.reverse()
                        selected.clear()
                        updateHeader()
                        (grid.adapter as BaseAdapter).notifyDataSetChanged()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askCompressLevel() {
        val levels = arrayOf(
            getString(R.string.compress_high),
            getString(R.string.compress_medium),
            getString(R.string.compress_low),
        )
        val presets = arrayOf(900 to 50, 1200 to 68, 1600 to 82)
        AlertDialog.Builder(this)
            .setTitle(R.string.compress_pdf)
            .setItems(levels) { _, which ->
                pendingCompress = presets[which]
                pendingExportPages = null
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/pdf")
                    .putExtra(Intent.EXTRA_TITLE, "${baseName()}-compressed.pdf")
                try { startActivityForResult(i, REQ_SAVE) } catch (_: Exception) {
                    Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickOutputFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        try { startActivityForResult(i, REQ_TREE) } catch (_: Exception) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startSave(specs: List<PdfTools.PageSpec>, suggestedName: String) {
        if (specs.isEmpty()) return
        pendingExportPages = specs
        pendingCompress = null
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_TITLE, suggestedName)
        try { startActivityForResult(i, REQ_SAVE) } catch (_: Exception) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        val d = doc ?: return

        if (requestCode == REQ_SAVE) {
            val outUri = data.data ?: return
            val comp = pendingCompress
            val specs = pendingExportPages
            progress.visibility = View.VISIBLE
            Toast.makeText(this, R.string.saving_pdf, Toast.LENGTH_SHORT).show()
            io.execute {
                val ok = try {
                    contentResolver.openOutputStream(outUri)?.use { os ->
                        if (comp != null) PdfTools.compressPdf(d, comp.first, comp.second, os)
                        else if (specs != null) PdfTools.exportOrganized(d, specs, os)
                    } ?: error("Cannot write")
                    true
                } catch (_: Throwable) { false }
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    progress.visibility = View.GONE
                    if (ok) {
                        AlertDialog.Builder(this)
                            .setMessage(R.string.saved_pdf)
                            .setPositiveButton(R.string.open) { _, _ ->
                                startActivity(Intent(this, ReaderActivity::class.java).setData(outUri))
                                finish()
                            }
                            .setNegativeButton(android.R.string.ok, null)
                            .show()
                    } else {
                        Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else if (requestCode == REQ_TREE) {
            val treeUri = data.data ?: return
            val docTreeUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
            val snapshot = pages.toList()
            val selSnapshot = selected.sorted().mapNotNull { snapshot.getOrNull(it) }.ifEmpty { snapshot }
            val mode = pendingTreeMode
            val chunk = pendingSplitChunk.coerceAtLeast(1)
            progress.visibility = View.VISIBLE
            Toast.makeText(this, R.string.saving_pdf, Toast.LENGTH_SHORT).show()
            io.execute {
                var count = 0
                val ok = try {
                    if (mode == TreeMode.SPLIT_WHOLE) {
                        val chunks = snapshot.chunked(chunk)
                        for ((idx, part) in chunks.withIndex()) {
                            val fileName = "${baseName()}_part_${idx + 1}.pdf"
                            val created = DocumentsContract.createDocument(contentResolver, docTreeUri, "application/pdf", fileName)
                                ?: continue
                            contentResolver.openOutputStream(created)?.use { os ->
                                PdfTools.exportOrganized(d, part, os)
                            }
                            count++
                        }
                    } else {
                        for ((idx, spec) in selSnapshot.withIndex()) {
                            val fileName = "${baseName()}_page_${spec.pageIndex + 1}.jpg"
                            val created = DocumentsContract.createDocument(contentResolver, docTreeUri, "image/jpeg", fileName)
                                ?: continue
                            contentResolver.openOutputStream(created)?.use { os ->
                                PdfTools.exportPageAsJpeg(d, spec.pageIndex, spec.rotationDeg, os)
                            }
                            count = idx + 1
                        }
                    }
                    count > 0
                } catch (_: Throwable) { false }
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    progress.visibility = View.GONE
                    val msg = if (ok) getString(R.string.exported_count, count) else getString(R.string.cannot_open)
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private inner class PageAdapter : BaseAdapter() {
        override fun getCount() = pages.size
        override fun getItem(position: Int) = pages[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = (convertView as? LinearLayout) ?: LinearLayout(this@OrganizePagesActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
                addView(ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(Color.WHITE)
                }, LinearLayout.LayoutParams(-1, dp(126)))
                addView(TextView(context).apply {
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(4), 0, 0)
                })
            }
            val spec = pages[position]
            val isSel = selected.contains(position)
            cell.background = Ui.rounded(pal.card, dp(12).toFloat(), if (isSel) dp(2) else dp(1), if (isSel) pal.gold else pal.divider)
            val img = cell.getChildAt(0) as ImageView
            val lbl = cell.getChildAt(1) as TextView
            lbl.setTextColor(if (isSel) pal.gold else pal.text)
            val prefix = if (isSel) "✓ " else ""
            lbl.text = prefix + if (spec.rotationDeg == 0) "${spec.pageIndex + 1}" else "${spec.pageIndex + 1} · ${spec.rotationDeg}°"

            img.tag = position
            val baseBmp = thumbs.get(spec.pageIndex)
            if (baseBmp != null) {
                if (spec.rotationDeg == 0) img.setImageBitmap(baseBmp)
                else {
                    val m = Matrix().apply { postRotate(spec.rotationDeg.toFloat()) }
                    img.setImageBitmap(Bitmap.createBitmap(baseBmp, 0, 0, baseBmp.width, baseBmp.height, m, true))
                }
            } else {
                img.setImageBitmap(null)
                val d = doc
                if (d != null) {
                    io.execute {
                        val bmp = try { d.renderThumb(spec.pageIndex, dp(180)) } catch (_: Throwable) { null }
                        if (bmp != null) {
                            thumbs.put(spec.pageIndex, bmp)
                            runOnUiThread {
                                if (img.tag == position && !isFinishing) notifyDataSetChanged()
                            }
                        }
                    }
                }
            }
            return cell
        }
    }

    companion object {
        const val EXTRA_NAME = "name"
        const val EXTRA_PASSWORD = "password"
        private const val REQ_SAVE = 51
        private const val REQ_TREE = 52
    }
}
