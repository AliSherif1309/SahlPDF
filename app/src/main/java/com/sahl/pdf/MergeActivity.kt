package com.sahl.pdf

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Merge multiple PDFs into a single document in user-chosen order.
 */
class MergeActivity : Activity() {

    private data class Item(val uri: Uri, val name: String, val pages: Int)

    private lateinit var pal: Palette
    private lateinit var list: ListView
    private lateinit var emptyText: TextView
    private lateinit var progress: ProgressBar
    private val io = Executors.newFixedThreadPool(2)
    private val items = ArrayList<Item>()
    private var selected = 0

    private fun dp(v: Number) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SahlCore.catchCrashes(this)
        pal = Palette(this)
        buildUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
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

        header.addView(TextView(this).apply {
            text = getString(R.string.merge_pdfs)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(pal.text)
            setPadding(dp(4), 0, dp(8), 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))

        header.addView(pill("+ " + getString(R.string.add_pdfs), pal.card, pal.text) { pickPdfs() },
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        header.addView(pill(getString(R.string.merge_save), pal.accent, Color.WHITE) { startSave() })
        root.addView(header)

        val body = FrameLayout(this)
        list = ListView(this).apply {
            divider = null
            setPadding(dp(12), dp(8), dp(12), dp(12))
            clipToPadding = false
            adapter = ItemAdapter()
            setOnItemClickListener { _, _, pos, _ ->
                selected = pos
                (adapter as BaseAdapter).notifyDataSetChanged()
            }
        }
        body.addView(list, FrameLayout.LayoutParams(-1, -1))

        emptyText = TextView(this).apply {
            text = getString(R.string.merge_empty)
            textSize = 15f
            setTextColor(pal.sub)
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        body.addView(emptyText, FrameLayout.LayoutParams(-1, -1))

        progress = ProgressBar(this).apply { visibility = View.GONE }
        body.addView(progress, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(pal.card)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        bar.addView(pill("▲", pal.bg, pal.text) { move(-1) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        bar.addView(pill("▼", pal.bg, pal.text) { move(1) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        bar.addView(pill(getString(R.string.delete), Color.parseColor("#B83B3B"), Color.WHITE) { remove() },
            LinearLayout.LayoutParams(0, -2, 1.2f))
        root.addView(bar)

        setContentView(root)
    }

    private fun pill(label: String, bg: Int, fg: Int, click: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(fg)
        textSize = 13.5f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(9), dp(12), dp(9))
        Ui.focusable(this, bg, dp(14).toFloat(), pal.gold)
        setOnClickListener { click() }
    }

    private fun refresh() {
        emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        (list.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun move(delta: Int) {
        if (selected !in items.indices) return
        val next = selected + delta
        if (next !in items.indices) return
        val it = items.removeAt(selected)
        items.add(next, it)
        selected = next
        refresh()
    }

    private fun remove() {
        if (selected !in items.indices) return
        items.removeAt(selected)
        if (selected >= items.size) selected = items.lastIndex
        refresh()
    }

    private fun pickPdfs() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try { startActivityForResult(i, REQ_PICK) } catch (_: Exception) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startSave() {
        if (items.size < 2) {
            Toast.makeText(this, R.string.merge_need_two, Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_TITLE, "Merged.pdf")
        try { startActivityForResult(i, REQ_SAVE) } catch (_: Exception) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == REQ_PICK) {
            val uris = ArrayList<Uri>()
            data.clipData?.let { cd -> for (i in 0 until cd.itemCount) uris += cd.getItemAt(i).uri }
            if (uris.isEmpty()) data.data?.let { uris += it }
            if (uris.isEmpty()) return
            progress.visibility = View.VISIBLE
            io.execute {
                val added = ArrayList<Item>()
                for (u in uris) {
                    val d = try { PdfDoc.open(this, u, null) } catch (_: Throwable) { null }
                    if (d != null) {
                        val n = queryName(u)
                        added += Item(u, n, d.pageCount)
                        d.close()
                    }
                }
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    progress.visibility = View.GONE
                    items += added
                    if (added.isEmpty()) Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }
        } else if (requestCode == REQ_SAVE) {
            val outUri = data.data ?: return
            val snapshot = items.toList()
            progress.visibility = View.VISIBLE
            Toast.makeText(this, R.string.saving_pdf, Toast.LENGTH_SHORT).show()
            io.execute {
                val docs = ArrayList<PdfDoc>()
                val ok = try {
                    for (it in snapshot) docs += PdfDoc.open(this, it.uri, null)
                    contentResolver.openOutputStream(outUri)?.use { os ->
                        PdfTools.merge(docs, os)
                    } ?: error("Cannot write")
                    true
                } catch (_: Throwable) { false } finally {
                    docs.forEach { try { it.close() } catch (_: Throwable) {} }
                }
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
    }

    private fun queryName(u: Uri): String {
        try {
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0) ?: "document.pdf"
            }
        } catch (_: Exception) { }
        return u.lastPathSegment ?: "document.pdf"
    }

    private inner class ItemAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@MergeActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(TextView(context).apply { textSize = 15f; typeface = Typeface.DEFAULT_BOLD })
                addView(TextView(context).apply { textSize = 12.5f })
            }
            val it = items[position]
            val sel = position == selected
            row.background = Ui.rounded(pal.card, dp(14).toFloat(), if (sel) dp(2) else dp(1), if (sel) pal.gold else pal.divider)
            (row.getChildAt(0) as TextView).apply {
                setTextColor(pal.text)
                text = "${position + 1}. ${it.name}"
            }
            (row.getChildAt(1) as TextView).apply {
                setTextColor(pal.sub)
                text = getString(R.string.page_count_label, it.pages)
            }
            return row
        }
    }

    companion object {
        private const val REQ_PICK = 61
        private const val REQ_SAVE = 62
    }
}
