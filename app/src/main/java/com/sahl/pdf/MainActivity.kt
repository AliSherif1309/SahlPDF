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
    private data class Row(
        val uri: Uri,
        val name: String,
        var sub: String,
        val key: String?,
        val book: Book?,
        val size: Long = 0L,
        val dateMs: Long = 0L,
    )

    private lateinit var pal: Palette
    private lateinit var list: ListView
    private lateinit var empty: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var accessButton: TextView
    private lateinit var tabRecent: TextView
    private lateinit var tabDevice: TextView
    private lateinit var updateChip: TextView
    private lateinit var searchBox: android.widget.EditText
    private lateinit var bulkBar: LinearLayout
    private lateinit var bulkCount: TextView
    private lateinit var bulkMergeBtn: TextView
    private lateinit var bulkShareBtn: TextView
    private lateinit var bulkRemoveBtn: TextView
    private lateinit var bulkSelectAllBtn: TextView
    private var deviceTab = false
    private var allRows: List<Row> = emptyList()
    private var rows: List<Row> = emptyList()
    private var filterQuery = ""
    private var multiSelect = false
    private val selectedUris = LinkedHashSet<String>()
    private var cachedDeviceRows: List<Row>? = null
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
        root.addView(open, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(8), dp(16), dp(8)) })

        val toolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), dp(12))
        }
        fun toolCard(labelRes: Int, click: () -> Unit) = TextView(this).apply {
            text = getString(labelRes)
            setTextColor(pal.text)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(11), dp(12), dp(11))
            Ui.focusable(this, pal.card, dp(16).toFloat(), pal.gold)
            setOnClickListener { click() }
        }
        if (!Ui.isTv(this)) {
            toolsRow.addView(toolCard(R.string.scan_to_pdf) { startActivity(Intent(this, ScanActivity::class.java)) },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        }
        toolsRow.addView(toolCard(R.string.merge_pdfs) { startActivity(Intent(this, MergeActivity::class.java)) },
            LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(toolsRow)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        tabRecent = tab(R.string.tab_recent) { selectTab(false) }
        tabDevice = tab(R.string.tab_device) { selectTab(true) }
        tabs.addView(tabRecent)
        tabs.addView(tabDevice, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        tabs.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        if (Ui.isTv(this)) {
            val sendBtn = tab(R.string.send_from_phone) { showWifiTransfer() }.apply {
                Ui.focusable(this, pal.card, dp(18).toFloat(), pal.gold)
                setTextColor(pal.accent)
                typeface = Typeface.DEFAULT_BOLD
            }
            tabs.addView(sendBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        }
        val selectBtn = tab(R.string.select_mode) { toggleMultiSelect(!multiSelect) }.apply {
            Ui.focusable(this, pal.card, dp(18).toFloat(), pal.gold)
            setTextColor(pal.text)
        }
        tabs.addView(selectBtn)
        root.addView(tabs)

        val searchWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(pal.card, dp(16).toFloat(), dp(1), pal.divider)
            setPadding(dp(14), dp(4), dp(8), dp(4))
        }
        searchBox = android.widget.EditText(this).apply {
            hint = getString(R.string.search_files_hint)
            setTextColor(pal.text)
            setHintTextColor(pal.sub)
            textSize = 14.5f
            isSingleLine = true
            background = null
            setPadding(0, dp(6), dp(6), dp(6))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    filterQuery = s?.toString()?.trim() ?: ""
                    applyFilter()
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        searchWrap.addView(searchBox, LinearLayout.LayoutParams(0, -2, 1f))
        searchWrap.addView(TextView(this).apply {
            text = "✕"
            textSize = 15f
            setTextColor(pal.sub)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { searchBox.setText("") }
        })
        root.addView(searchWrap, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), 0, dp(16), dp(8)) })

        val body = android.widget.FrameLayout(this)
        list = ListView(this).apply {
            divider = null
            selector = Ui.rounded(0, dp(16).toFloat(), dp(2), pal.gold)
            setPadding(dp(8), 0, dp(8), dp(16))
            clipToPadding = false
            adapter = RowAdapter()
            setOnItemClickListener { _, _, pos, _ ->
                val r = rows[pos]
                if (multiSelect) toggleRowSelection(r) else openRow(r)
            }
            setOnItemLongClickListener { _, _, pos, _ ->
                val r = rows[pos]
                if (!multiSelect) toggleMultiSelect(true)
                toggleRowSelection(r)
                true
            }
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

        bulkBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.card)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            visibility = View.GONE
        }
        fun bulkBtn(labelRes: Int, bg: Int, fg: Int, click: () -> Unit) = TextView(this).apply {
            text = getString(labelRes)
            setTextColor(fg); textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            Ui.focusable(this, bg, dp(14).toFloat(), pal.gold)
            setOnClickListener { click() }
        }
        val bulkTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        bulkCount = TextView(this).apply {
            setTextColor(pal.text); textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        bulkSelectAllBtn = bulkBtn(R.string.select_all, pal.bg, pal.text) { toggleSelectAllRows() }
        bulkTop.addView(bulkCount, LinearLayout.LayoutParams(0, -2, 1f))
        bulkTop.addView(bulkSelectAllBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        bulkTop.addView(bulkBtn(R.string.done, pal.bg, pal.sub) { toggleMultiSelect(false) })
        bulkBar.addView(bulkTop)

        val bulkActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bulkMergeBtn = bulkBtn(R.string.merge_short, pal.accent, Color.WHITE) { mergeSelected() }
        bulkShareBtn = bulkBtn(R.string.share, pal.bg, pal.text) { shareSelected() }
        bulkRemoveBtn = bulkBtn(R.string.remove_recent, Color.parseColor("#B83B3B"), Color.WHITE) { removeSelected() }
        bulkActions.addView(bulkMergeBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        bulkActions.addView(bulkShareBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        bulkActions.addView(bulkRemoveBtn, LinearLayout.LayoutParams(0, -2, 1.3f))
        bulkBar.addView(bulkActions)
        root.addView(bulkBar)

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
            allRows = Library.all(this).map { b ->
                val parts = ArrayList<String>()
                if (b.pages > 0) parts += getString(R.string.page_of, b.page + 1, b.pages)
                parts += DateUtils.getRelativeTimeSpanString(b.opened, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                Row(Uri.parse(b.uri), b.name, parts.joinToString(" · "), b.key, b)
            }
            applyFilter()
            if (cachedDeviceRows == null && hasAccess()) {
                io.execute {
                    val found = queryDevicePdfs()
                    runOnUiThread { if (!isFinishing) cachedDeviceRows = found }
                }
            }
            return
        }
        if (!hasAccess()) {
            allRows = emptyList()
            rows = emptyList()
            show(R.string.device_access_body, needAccess = true)
            return
        }
        cachedDeviceRows?.let { cached ->
            allRows = cached
            applyFilter()
        }
        io.execute {
            val found = queryDevicePdfs()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                cachedDeviceRows = found
                if (!deviceTab) return@runOnUiThread
                allRows = found
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        rows = if (filterQuery.isEmpty()) allRows
        else allRows.filter { it.name.contains(filterQuery, ignoreCase = true) }
        val emptyRes = if (filterQuery.isNotEmpty()) R.string.search_files_empty
        else if (deviceTab) R.string.empty_device else R.string.empty_recent
        show(emptyRes, needAccess = deviceTab && !hasAccess())
    }

    private fun toggleMultiSelect(on: Boolean) {
        multiSelect = on
        if (!on) selectedUris.clear()
        updateBulkBar()
        (list.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun toggleRowSelection(r: Row) {
        val k = r.uri.toString()
        if (!selectedUris.add(k)) selectedUris.remove(k)
        if (selectedUris.isEmpty()) multiSelect = false
        updateBulkBar()
        (list.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun selectedRows(): List<Row> {
        val byUri = allRows.associateBy { it.uri.toString() }
        return selectedUris.mapNotNull { byUri[it] }
    }

    private fun toggleSelectAllRows() {
        val allVisibleSelected = rows.isNotEmpty() && rows.all { selectedUris.contains(it.uri.toString()) }
        if (allVisibleSelected) {
            for (r in rows) selectedUris.remove(r.uri.toString())
        } else {
            for (r in rows) selectedUris.add(r.uri.toString())
        }
        updateBulkBar()
        (list.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun updateBulkBar() {
        bulkBar.visibility = if (multiSelect) View.VISIBLE else View.GONE
        if (!multiSelect) return
        val sel = selectedRows()
        bulkCount.text = getString(R.string.selected_count, sel.size)
        val allSel = rows.isNotEmpty() && rows.all { selectedUris.contains(it.uri.toString()) }
        bulkSelectAllBtn.text = getString(if (allSel) R.string.deselect_all else R.string.select_all)
        bulkMergeBtn.visibility = if (sel.size >= 2) View.VISIBLE else View.GONE
        bulkShareBtn.visibility = if (sel.any { it.uri.scheme == "content" }) View.VISIBLE else View.GONE
        bulkRemoveBtn.visibility = if (!deviceTab && sel.any { it.book != null }) View.VISIBLE else View.GONE
    }

    private fun mergeSelected() {
        val uris = ArrayList<Uri>(selectedRows().map { it.uri })
        if (uris.size < 2) return
        toggleMultiSelect(false)
        startActivity(Intent(this, MergeActivity::class.java).putParcelableArrayListExtra(MergeActivity.EXTRA_URIS, uris))
    }

    private fun shareSelected() {
        val uris = ArrayList<Uri>(selectedRows().map { it.uri }.filter { it.scheme == "content" })
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("application/pdf").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(Intent.createChooser(intent, getString(R.string.share)))
    }

    private fun removeSelected() {
        for (r in selectedRows()) {
            r.book?.let { Library.remove(this, it.key) }
        }
        toggleMultiSelect(false)
        refresh()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (multiSelect) { toggleMultiSelect(false); return }
        @Suppress("DEPRECATION") super.onBackPressed()
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
                    out += Row(ContentUris.withAppendedId(base, id), name, "", Library.keyFor(Uri.EMPTY, name, size), null, size, date)
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
            val check = row.getChildAt(2) as TextView
            (texts.getChildAt(0) as TextView).text = r.name.removeSuffix(".pdf").removeSuffix(".PDF")
            val subText = r.sub.ifEmpty {
                if (r.size > 0L || r.dateMs > 0L) {
                    (Formatter.formatShortFileSize(this@MainActivity, r.size) + " · " +
                        DateUtils.formatDateTime(this@MainActivity, r.dateMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)).also { r.sub = it }
                } else ""
            }
            (texts.getChildAt(1) as TextView).text = subText
            bindThumb(thumb, r.key?.takeIf { r.book != null })
            val isSel = multiSelect && selectedUris.contains(r.uri.toString())
            check.visibility = if (multiSelect) View.VISIBLE else View.GONE
            check.text = if (isSel) "✓" else ""
            check.background = Ui.rounded(if (isSel) pal.accent else pal.card, dp(12).toFloat(), dp(1), if (isSel) pal.accent else pal.divider)
            row.background = if (isSel) Ui.rounded(pal.card, dp(14).toFloat(), dp(1), pal.gold) else null
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
                setPadding(dp(14), 0, dp(8), 0)
                addView(TextView(context).apply {
                    setTextColor(pal.text); textSize = 15.5f; typeface = Typeface.DEFAULT_BOLD
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(TextView(context).apply { setTextColor(pal.sub); textSize = 13f; maxLines = 1 })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(context).apply {
                setTextColor(Color.WHITE); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                visibility = View.GONE
            }, LinearLayout.LayoutParams(dp(24), dp(24)))
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
