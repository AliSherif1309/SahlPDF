package com.sahl.pdf

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {

    /** One row in either list. */
    private data class Row(val uri: Uri, val name: String, val sub: String, val key: String?, val book: Book?)

    private lateinit var pal: Palette
    private lateinit var list: ListView
    private lateinit var empty: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var accessButton: TextView
    private lateinit var tabRecent: TextView
    private lateinit var tabDevice: TextView
    private lateinit var updateChip: TextView
    private var deviceTab = false
    private var rows: List<Row> = emptyList()
    private val io = Executors.newFixedThreadPool(2)
    private val thumbs = LruCache<String, Bitmap>(8 * 1024 * 1024)
    private var release: Updater.Release? = null

    private fun dp(v: Number) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SahlCore.catchCrashes(this)
        pal = Palette(this)
        deviceTab = savedInstanceState?.getBoolean("device") ?: false
        buildUi()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putBoolean("device", deviceTab)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (Updater.dueForCheck(this)) Thread {
            val r = Updater.fetchNewer(this)
            runOnUiThread { if (r != null && !isFinishing) { release = r; updateChip.visibility = View.VISIBLE } }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }

    // ---- UI ----------------------------------------------------------------------------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.bg)
            fitsSystemWindows = true
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(8), dp(8))
        }
        header.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(pal.text)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        updateChip = TextView(this).apply {
            text = getString(R.string.update_chip)
            setTextColor(Color.BLACK)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(6), dp(14), dp(6))
            Ui.focusable(this, Color.parseColor("#F5C84C"), dp(16).toFloat(), pal.text)
            visibility = View.GONE
            setOnClickListener { release?.let { showUpdate(it) } }
        }
        header.addView(updateChip)
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_sahl_settings)
            setColorFilter(pal.text)
            contentDescription = getString(R.string.settings_title)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            Ui.focusable(this, 0, dp(24).toFloat(), pal.gold)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header)

        val open = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14))
            Ui.focusable(this, pal.accent, dp(18).toFloat(), pal.gold)
            setOnClickListener { pickFile() }
        }
        open.addView(ImageView(this).apply { setImageResource(R.drawable.ic_open); setColorFilter(Color.WHITE) },
            LinearLayout.LayoutParams(dp(22), dp(22)))
        open.addView(TextView(this).apply {
            text = getString(R.string.open_pdf)
            setTextColor(Color.WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), 0, dp(10), 0)
        })
        root.addView(open, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(8), dp(16), dp(12)) })

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        tabRecent = tab(R.string.tab_recent) { selectTab(false) }
        tabDevice = tab(R.string.tab_device) { selectTab(true) }
        val sendBtn = tab(R.string.send_from_phone) { showWifiTransfer() }.apply {
            Ui.focusable(this, pal.card, dp(18).toFloat(), pal.gold)
            setTextColor(pal.accent)
            typeface = Typeface.DEFAULT_BOLD
        }
        tabs.addView(tabRecent)
        tabs.addView(tabDevice, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        tabs.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        tabs.addView(sendBtn)
        root.addView(tabs)

        val body = android.widget.FrameLayout(this)
        list = ListView(this).apply {
            divider = null
            selector = Ui.rounded(0, dp(16).toFloat(), dp(2), pal.gold)
            setPadding(dp(8), 0, dp(8), dp(16))
            clipToPadding = false
            adapter = RowAdapter()
            setOnItemClickListener { _, _, pos, _ -> openRow(rows[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> rowMenu(rows[pos]); true }
        }
        body.addView(list, android.widget.FrameLayout.LayoutParams(-1, -1))

        empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        empty.addView(ImageView(this).apply { setImageResource(R.drawable.ic_sahl_pdf); alpha = 0.7f },
            LinearLayout.LayoutParams(dp(56), dp(56)))
        emptyText = TextView(this).apply {
            setTextColor(pal.sub); textSize = 15f; gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
        }
        empty.addView(emptyText)
        accessButton = TextView(this).apply {
            text = getString(R.string.allow_access)
            setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(20), dp(10), dp(20), dp(10))
            Ui.focusable(this, pal.accent, dp(18).toFloat(), pal.gold)
            setOnClickListener { requestAccess() }
        }
        empty.addView(accessButton)
        body.addView(empty, android.widget.FrameLayout.LayoutParams(-1, -1))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
        styleTabs()
    }

    private fun tab(label: Int, click: () -> Unit) = TextView(this).apply {
        text = getString(label)
        textSize = 14f
        setPadding(dp(16), dp(8), dp(16), dp(8))
        setOnClickListener { click() }
    }

    private fun styleTabs() {
        for ((t, on) in listOf(tabRecent to !deviceTab, tabDevice to deviceTab)) {
            Ui.focusable(t, if (on) pal.accent else pal.card, dp(18).toFloat(), pal.gold)
            t.setTextColor(if (on) Color.WHITE else pal.text)
            t.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    private fun selectTab(device: Boolean) {
        if (device == deviceTab) return
        deviceTab = device
        styleTabs()
        refresh()
    }

    // ---- data --------------------------------------------------------------------------

    private fun refresh() {
        if (!deviceTab) {
            rows = Library.all(this).map { b ->
                val parts = ArrayList<String>()
                if (b.pages > 0) parts += getString(R.string.page_of, b.page + 1, b.pages)
                parts += DateUtils.getRelativeTimeSpanString(b.opened, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                Row(Uri.parse(b.uri), b.name, parts.joinToString(" · "), b.key, b)
            }
            show(R.string.empty_recent, needAccess = false)
            return
        }
        if (!hasAccess()) {
            rows = emptyList()
            show(R.string.device_access_body, needAccess = true)
            return
        }
        io.execute {
            val found = queryDevicePdfs()
            runOnUiThread {
                if (isFinishing || !deviceTab) return@runOnUiThread
                rows = found
                show(R.string.empty_device, needAccess = false)
            }
        }
    }

    private fun show(emptyMsg: Int, needAccess: Boolean) {
        (list.adapter as BaseAdapter).notifyDataSetChanged()
        val isEmpty = rows.isEmpty()
        empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        list.visibility = if (isEmpty) View.GONE else View.VISIBLE
        emptyText.text = getString(emptyMsg)
        accessButton.visibility = if (needAccess) View.VISIBLE else View.GONE
    }

    private fun queryDevicePdfs(): List<Row> {
        val out = ArrayList<Row>()
        val base = MediaStore.Files.getContentUri("external")
        val cols = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
        try {
            contentResolver.query(base, cols, "${MediaStore.Files.FileColumns.MIME_TYPE}=?", arrayOf("application/pdf"),
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use { c ->
                while (c.moveToNext() && out.size < 2000) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: continue
                    val size = c.getLong(2)
                    val date = c.getLong(3) * 1000L
                    val sub = Formatter.formatShortFileSize(this, size) + " · " +
                        DateUtils.formatDateTime(this, date, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
                    out += Row(ContentUris.withAppendedId(base, id), name, sub, Library.keyFor(Uri.EMPTY, name, size), null)
                }
            }
        } catch (_: Exception) { }
        return out
    }

    private fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT < 30) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_PERM)
            return
        }
        val tries = listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        )
        for (i in tries) {
            try { startActivity(i); return } catch (_: Exception) { }
        }
        AlertDialog.Builder(this).setMessage(R.string.tv_access_hint).setPositiveButton(android.R.string.ok, null).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) refresh()
    }

    // ---- actions -----------------------------------------------------------------------

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf")
        try { startActivityForResult(i, REQ_PICK) } catch (_: Exception) {
            Toast.makeText(this, R.string.no_picker, Toast.LENGTH_LONG).show()
            selectTab(true)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Library.keepAccess(this, uri, data.flags or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        openUri(uri)
    }

    private fun openRow(r: Row) = openUri(r.uri)

    private fun openUri(uri: Uri) {
        startActivity(Intent(this, ReaderActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun rowMenu(r: Row) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (r.book != null) {
            labels += getString(R.string.remove_recent); actions += { Library.remove(this, r.book.key); refresh() }
        }
        if (r.uri.scheme == "content") {
            labels += getString(R.string.share); actions += {
                start(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf")
                    .putExtra(Intent.EXTRA_STREAM, r.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), getString(R.string.share)))
            }
            labels += getString(R.string.open_with); actions += {
                start(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(r.uri, "application/pdf")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), getString(R.string.open_with)))
            }
        }
        if (labels.isEmpty()) return
        AlertDialog.Builder(this).setTitle(r.name).setItems(labels.toTypedArray()) { _, w -> actions[w]() }.show()
    }

    private fun start(i: Intent) {
        try { startActivity(i) } catch (_: Exception) { Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show() }
    }

    private fun showUpdate(r: Updater.Release) {
        AlertDialog.Builder(this)
            .setTitle(Updater.availableText(this, r))
            .setMessage(r.notes.ifBlank { Updater.updateNowText(this) })
            .setPositiveButton(Updater.updateNowText(this)) { _, _ -> Updater.download(this, r) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showWifiTransfer() {
        var received = 0
        lateinit var statusView: TextView
        val transfer = WifiTransfer(this) { _, _ ->
            received++
            runOnUiThread {
                if (!isFinishing) {
                    statusView.text = getString(R.string.received_count, received)
                    refresh()
                }
            }
        }
        if (!transfer.start()) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
            return
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(8))
        }
        box.addView(TextView(this).apply {
            text = getString(R.string.send_from_phone_desc)
            textSize = 14f
            setTextColor(pal.sub)
        })
        box.addView(TextView(this).apply {
            text = transfer.url
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(pal.accent)
            setTextIsSelectable(true)
            setPadding(0, dp(10), 0, dp(4))
        })
        box.addView(TextView(this).apply {
            text = transfer.code
            textSize = 32f
            typeface = Typeface.MONOSPACE
            setTextColor(pal.gold)
            setPadding(0, dp(4), 0, dp(8))
        })
        statusView = TextView(this).apply {
            text = getString(R.string.received_count, 0)
            textSize = 14f
            setTextColor(pal.text)
        }
        box.addView(statusView)

        AlertDialog.Builder(this)
            .setTitle(R.string.send_from_phone)
            .setView(box)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { transfer.stop() }
            .show()
    }

    // ---- adapter -----------------------------------------------------------------------

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: makeRow()
            val r = rows[position]
            val thumb = row.getChildAt(0) as ImageView
            val texts = row.getChildAt(1) as LinearLayout
            (texts.getChildAt(0) as TextView).text = r.name.removeSuffix(".pdf").removeSuffix(".PDF")
            (texts.getChildAt(1) as TextView).text = r.sub
            bindThumb(thumb, r.key?.takeIf { r.book != null })
            return row
        }

        private fun makeRow() = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = Ui.rounded(pal.card, dp(8).toFloat())
                clipToOutline = true
            }, LinearLayout.LayoutParams(dp(46), dp(62)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, dp(4), 0)
                addView(TextView(context).apply {
                    setTextColor(pal.text); textSize = 15.5f; typeface = Typeface.DEFAULT_BOLD
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(TextView(context).apply { setTextColor(pal.sub); textSize = 13f; maxLines = 1 })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun bindThumb(v: ImageView, key: String?) {
        v.tag = key
        val cached = key?.let { thumbs.get(it) }
        if (cached != null) { v.setImageBitmap(cached); v.setPadding(0, 0, 0, 0); return }
        v.setImageResource(R.drawable.ic_sahl_pdf)
        v.setPadding(dp(10), dp(14), dp(10), dp(14))
        if (key == null) return
        io.execute {
            val f = Library.thumb(this, key)
            val bmp = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
            if (bmp != null) runOnUiThread {
                thumbs.put(key, bmp)
                if (v.tag == key) { v.setImageBitmap(bmp); v.setPadding(0, 0, 0, 0) }
            }
        }
    }

    companion object {
        private const val REQ_PICK = 11
        private const val REQ_PERM = 12
    }
}
