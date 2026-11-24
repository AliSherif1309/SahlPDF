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
 * Visual page organizer: reorder, rotate, delete, split/extract ranges, and save a new PDF copy.
 */
class OrganizePagesActivity : Activity() {

    private lateinit var pal: Palette
    private lateinit var grid: GridView
    private lateinit var progress: ProgressBar
    private lateinit var titleView: TextView
    private val io = Executors.newFixedThreadPool(2)
    private val thumbs = LruCache<Int, Bitmap>(16 * 1024 * 1024)

    private var doc: PdfDoc? = null
    private lateinit var uri: Uri
    private var docName = "document"
    private var password: String? = null
    private val pages = ArrayList<PdfTools.PageSpec>()
    private var selectedPos = 0
    private var pendingExportPages: List<PdfTools.PageSpec>? = null

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
            setPadding(dp(8), dp(10), dp(12), dp(8))
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

        header.addView(actionPill(getString(R.string.split_extract), pal.card, pal.text) { askSplitRange() },
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        header.addView(actionPill(getString(R.string.save_copy), pal.accent, Color.WHITE) {
            startSave(pages.toList(), "${docName.removeSuffix(".pdf").removeSuffix(".PDF")}-organized.pdf")
        })
        root.addView(header)

        val body = FrameLayout(this)
        grid = GridView(this).apply {
            numColumns = if (resources.configuration.screenWidthDp >= 600) 4 else 3
            horizontalSpacing = dp(10)
            verticalSpacing = dp(10)
            setPadding(dp(12), dp(8), dp(12), dp(12))
            clipToPadding = false
            adapter = PageAdapter()
            setOnItemClickListener { _, _, pos, _ ->
                selectedPos = pos
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

    private fun actionPill(label: String, bg: Int, fg: Int, click: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(fg)
        textSize = 13.5f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(9), dp(12), dp(9))
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
                selectedPos = 0
                updateTitle()
                (grid.adapter as BaseAdapter).notifyDataSetChanged()
            }
        }
    }

    private fun updateTitle() {
        titleView.text = "${getString(R.string.organize_pages)} (${pages.size})"
    }

    private fun moveSelected(delta: Int) {
        if (selectedPos !in pages.indices) return
        val next = selectedPos + delta
        if (next !in pages.indices) return
        val item = pages.removeAt(selectedPos)
        pages.add(next, item)
        selectedPos = next
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun rotateSelected() {
        if (selectedPos !in pages.indices) return
        val cur = pages[selectedPos]
        pages[selectedPos] = cur.copy(rotationDeg = (cur.rotationDeg + 90) % 360)
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun deleteSelected() {
        if (pages.size <= 1) {
            Toast.makeText(this, R.string.keep_one_page, Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedPos !in pages.indices) return
        pages.removeAt(selectedPos)
        if (selectedPos >= pages.size) selectedPos = pages.lastIndex
        updateTitle()
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun askSplitRange() {
        val d = doc ?: return
        val input = EditText(this).apply {
            hint = getString(R.string.split_hint, d.pageCount)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(pal.text)
            setHintTextColor(pal.sub)
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle(R.string.split_extract)
            .setView(box)
            .setPositiveButton(R.string.save_copy) { _, _ ->
                val indices = PdfTools.parsePageRanges(input.text.toString(), d.pageCount)
                if (indices.isEmpty()) {
                    Toast.makeText(this, R.string.invalid_range, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val specs = indices.map { PdfTools.PageSpec(it, 0) }
                startSave(specs, "${docName.removeSuffix(".pdf").removeSuffix(".PDF")}-split.pdf")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startSave(specs: List<PdfTools.PageSpec>, suggestedName: String) {
        if (specs.isEmpty()) return
        pendingExportPages = specs
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
        if (requestCode != REQ_SAVE || resultCode != RESULT_OK) return
        val outUri = data?.data ?: return
        val d = doc ?: return
        val specs = pendingExportPages ?: return
        progress.visibility = View.VISIBLE
        Toast.makeText(this, R.string.saving_pdf, Toast.LENGTH_SHORT).show()
        io.execute {
            val ok = try {
                contentResolver.openOutputStream(outUri)?.use { os ->
                    PdfTools.exportOrganized(d, specs, os)
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
                }, LinearLayout.LayoutParams(-1, dp(130)))
                addView(TextView(context).apply {
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(4), 0, 0)
                })
            }
            val spec = pages[position]
            val selected = position == selectedPos
            cell.background = Ui.rounded(pal.card, dp(12).toFloat(), if (selected) dp(2) else dp(1), if (selected) pal.gold else pal.divider)
            val img = cell.getChildAt(0) as ImageView
            val lbl = cell.getChildAt(1) as TextView
            lbl.setTextColor(if (selected) pal.gold else pal.text)
            lbl.text = if (spec.rotationDeg == 0) "${spec.pageIndex + 1}" else "${spec.pageIndex + 1} · ${spec.rotationDeg}°"

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
    }
}
