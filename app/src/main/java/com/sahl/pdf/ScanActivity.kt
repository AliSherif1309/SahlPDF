package com.sahl.pdf

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Scan to PDF: capture photos via Camera or import from Gallery, apply document filters,
 * rotate/reorder pages, and save as a clean multi-page PDF.
 */
class ScanActivity : Activity() {

    private lateinit var pal: Palette
    private lateinit var grid: GridView
    private lateinit var emptyText: TextView
    private lateinit var progress: ProgressBar
    private val io = Executors.newFixedThreadPool(2)
    private val pages = ArrayList<PdfTools.ScanPage>()
    private val previews = HashMap<String, Bitmap>()
    private var selected = 0
    private var pendingPhotoFile: File? = null

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
        previews.values.forEach { it.recycle() }
        previews.clear()
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
            text = getString(R.string.scan_to_pdf)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(pal.text)
            setPadding(dp(4), 0, dp(8), 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))

        header.addView(pill(getString(R.string.save_pdf), pal.accent, Color.WHITE) { startSave() })
        root.addView(header)

        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), dp(8))
        }
        addRow.addView(pill(getString(R.string.take_photo), pal.card, pal.text) { takePhoto() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        addRow.addView(pill(getString(R.string.from_gallery), pal.card, pal.text) { pickImages() },
            LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(addRow)

        val body = FrameLayout(this)
        grid = GridView(this).apply {
            numColumns = if (resources.configuration.screenWidthDp >= 600) 3 else 2
            horizontalSpacing = dp(10)
            verticalSpacing = dp(10)
            setPadding(dp(12), dp(4), dp(12), dp(12))
            clipToPadding = false
            adapter = ScanAdapter()
            setOnItemClickListener { _, _, pos, _ ->
                selected = pos
                (adapter as BaseAdapter).notifyDataSetChanged()
            }
        }
        body.addView(grid, FrameLayout.LayoutParams(-1, -1))

        emptyText = TextView(this).apply {
            text = getString(R.string.scan_empty)
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
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        bar.addView(pill("◀", pal.bg, pal.text) { move(-1) },
            LinearLayout.LayoutParams(0, -2, 0.8f).apply { marginEnd = dp(4) })
        bar.addView(pill("▶", pal.bg, pal.text) { move(1) },
            LinearLayout.LayoutParams(0, -2, 0.8f).apply { marginEnd = dp(4) })
        bar.addView(pill("↻ 90°", pal.bg, pal.text) { rotate() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        bar.addView(pill(getString(R.string.filter_btn), pal.bg, pal.text) { cycleFilter() },
            LinearLayout.LayoutParams(0, -2, 1.1f).apply { marginEnd = dp(4) })
        bar.addView(pill(getString(R.string.delete), Color.parseColor("#B83B3B"), Color.WHITE) { delete() },
            LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bar)

        setContentView(root)
    }

    private fun pill(label: String, bg: Int, fg: Int, click: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(fg)
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(9), dp(10), dp(9))
        Ui.focusable(this, bg, dp(14).toFloat(), pal.gold)
        setOnClickListener { click() }
    }

    private fun refresh() {
        emptyText.visibility = if (pages.isEmpty()) View.VISIBLE else View.GONE
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun move(delta: Int) {
        if (selected !in pages.indices) return
        val next = selected + delta
        if (next !in pages.indices) return
        val p = pages.removeAt(selected)
        pages.add(next, p)
        selected = next
        refresh()
    }

    private fun rotate() {
        if (selected !in pages.indices) return
        val cur = pages[selected]
        pages[selected] = cur.copy(rotationDeg = (cur.rotationDeg + 90) % 360)
        refresh()
    }

    private fun cycleFilter() {
        if (selected !in pages.indices) return
        val cur = pages[selected]
        val filters = PdfTools.ScanFilter.entries
        val next = filters[(cur.filter.ordinal + 1) % filters.size]
        pages[selected] = cur.copy(filter = next)
        refresh()
    }

    private fun delete() {
        if (selected !in pages.indices) return
        pages.removeAt(selected)
        if (selected >= pages.size) selected = pages.lastIndex
        refresh()
    }

    private fun scanDir(): File = File(cacheDir, "scans").apply { mkdirs() }

    private fun takePhoto() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), REQ_CAM_PERM)
            return
        }
        val file = File(scanDir(), "scan_${System.currentTimeMillis()}.jpg")
        pendingPhotoFile = file
        startActivityForResult(
            Intent(this, CameraScanActivity::class.java).putExtra(CameraScanActivity.EXTRA_OUTPUT_PATH, file.path),
            REQ_CAMERA,
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAM_PERM && grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            takePhoto()
        } else if (requestCode == REQ_CAM_PERM) {
            Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun pickImages() {
        val i = Intent(Intent.ACTION_GET_CONTENT)
            .setType("image/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try { startActivityForResult(i, REQ_GALLERY) } catch (_: Exception) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startSave() {
        if (pages.isEmpty()) {
            Toast.makeText(this, R.string.scan_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_TITLE, "Scan-$stamp.pdf")
        try { startActivityForResult(i, REQ_SAVE) } catch (_: Exception) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_CAMERA -> {
                val f = pendingPhotoFile ?: return
                if (!f.exists() || f.length() == 0L) {
                    @Suppress("DEPRECATION")
                    val bmp = data?.extras?.get("data") as? Bitmap
                    if (bmp != null) {
                        try {
                            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                        } catch (_: Throwable) { }
                    }
                }
                if (f.exists() && f.length() > 0L) {
                    pages += PdfTools.ScanPage(f.path, 0, PdfTools.ScanFilter.ENHANCED)
                    selected = pages.lastIndex
                    refresh()
                }
            }
            REQ_GALLERY -> {
                if (data == null) return
                val uris = ArrayList<Uri>()
                data.clipData?.let { cd -> for (i in 0 until cd.itemCount) uris += cd.getItemAt(i).uri }
                if (uris.isEmpty()) data.data?.let { uris += it }
                if (uris.isEmpty()) return
                progress.visibility = View.VISIBLE
                io.execute {
                    val added = ArrayList<PdfTools.ScanPage>()
                    for (u in uris) {
                        val dst = File(scanDir(), "imp_${System.currentTimeMillis()}_${added.size}.jpg")
                        val copied = try {
                            contentResolver.openInputStream(u)?.use { ins ->
                                FileOutputStream(dst).use { outs -> ins.copyTo(outs) }
                            }
                            dst.exists() && dst.length() > 0L
                        } catch (_: Throwable) { false }
                        if (copied) added += PdfTools.ScanPage(dst.path, 0, PdfTools.ScanFilter.ENHANCED)
                    }
                    runOnUiThread {
                        if (isFinishing) return@runOnUiThread
                        progress.visibility = View.GONE
                        pages += added
                        if (pages.isNotEmpty()) selected = pages.lastIndex
                        refresh()
                    }
                }
            }
            REQ_SAVE -> {
                val outUri = data?.data ?: return
                val snapshot = pages.toList()
                progress.visibility = View.VISIBLE
                Toast.makeText(this, R.string.saving_pdf, Toast.LENGTH_SHORT).show()
                io.execute {
                    val ok = try {
                        contentResolver.openOutputStream(outUri)?.use { os ->
                            PdfTools.imagesToPdf(snapshot, os)
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
        }
    }

    private inner class ScanAdapter : BaseAdapter() {
        override fun getCount() = pages.size
        override fun getItem(position: Int) = pages[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = (convertView as? LinearLayout) ?: LinearLayout(this@ScanActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
                addView(ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(Color.WHITE)
                }, LinearLayout.LayoutParams(-1, dp(180)))
                addView(TextView(context).apply {
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(4), 0, 0)
                })
            }
            val sp = pages[position]
            val sel = position == selected
            cell.background = Ui.rounded(pal.card, dp(12).toFloat(), if (sel) dp(2) else dp(1), if (sel) pal.gold else pal.divider)
            val img = cell.getChildAt(0) as ImageView
            val lbl = cell.getChildAt(1) as TextView
            lbl.setTextColor(if (sel) pal.gold else pal.text)
            val filterLabel = when (sp.filter) {
                PdfTools.ScanFilter.ORIGINAL -> getString(R.string.filter_original)
                PdfTools.ScanFilter.ENHANCED -> getString(R.string.filter_enhanced)
                PdfTools.ScanFilter.BW -> getString(R.string.filter_bw)
            }
            lbl.text = "${position + 1} · $filterLabel" + if (sp.rotationDeg != 0) " · ${sp.rotationDeg}°" else ""

            val cacheKey = "${sp.filePath}:${sp.rotationDeg}:${sp.filter}"
            img.tag = cacheKey
            val cached = previews[cacheKey]
            if (cached != null) {
                img.setImageBitmap(cached)
            } else {
                img.setImageBitmap(null)
                io.execute {
                    val src = PdfTools.decodeSampledBitmap(sp.filePath, dp(360))
                    val bmp = src?.let { s ->
                        try { PdfTools.applyFilterAndRotate(s, sp.rotationDeg, sp.filter) } finally { s.recycle() }
                    }
                    if (bmp != null) {
                        runOnUiThread {
                            if (isFinishing) { bmp.recycle(); return@runOnUiThread }
                            previews[cacheKey] = bmp
                            if (img.tag == cacheKey) img.setImageBitmap(bmp)
                        }
                    }
                }
            }
            return cell
        }
    }

    companion object {
        private const val REQ_CAMERA = 71
        private const val REQ_GALLERY = 72
        private const val REQ_SAVE = 73
        private const val REQ_CAM_PERM = 74
    }
}
