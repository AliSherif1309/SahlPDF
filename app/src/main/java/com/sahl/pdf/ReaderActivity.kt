package com.sahl.pdf

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.format.Formatter
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import io.legere.pdfiumandroid.PdfPasswordException
import java.io.FileOutputStream

class ReaderActivity : Activity(), PdfView.Listener {

    private val renderer = Renderer()
    private lateinit var pdfView: PdfView
    private lateinit var topBar: LinearLayout
    private lateinit var searchBar: LinearLayout
    private lateinit var annotateBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var bookmarkBtn: ImageView
    private lateinit var searchInput: EditText
    private lateinit var searchCount: TextView
    private lateinit var pageLabel: TextView
    private lateinit var pagePill: TextView
    private lateinit var selectionBar: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var progress: ProgressBar

    private var doc: PdfDoc? = null
    private lateinit var uri: Uri
    private var name = ""
    private var size = -1L
    private var key = ""
    private var barsShown = true
    private var searchOpen = false
    private var searchGen = 0
    private var searchHits: List<SearchHit> = emptyList()
    private var searchIdx = -1

    private val prefs by lazy { getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE) }
    private val hidePill = Runnable { pagePill.animate().alpha(0f).setDuration(250).start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SahlCore.catchCrashes(this)
        val target = documentUri(intent)
        if (target == null) { finish(); return }
        uri = target
        Library.keepAccess(this, uri, intent.flags)
        val (n, s) = Library.describe(this, uri)
        name = n; size = s
        key = Library.keyFor(uri, name, size)

        buildUi()
        applyPrefs()
        open(null)
    }

    override fun onResume() {
        super.onResume()
        if (::pdfView.isInitialized) applyPrefs()
    }

    private fun applyPrefs() {
        if (Ui.isTv(this) && !prefs.getBoolean(SettingsActivity.TV_DEFAULTS_DONE, false)) {
            prefs.edit()
                .putInt(SettingsActivity.FLOW_MODE, FlowMode.HORIZONTAL_LTR.ordinal)
                .putInt(SettingsActivity.FIT_MODE, FitMode.PAGE.ordinal)
                .putBoolean(SettingsActivity.TWO_PAGE, true)
                .putBoolean(SettingsActivity.TV_DEFAULTS_DONE, true)
                .apply()
        }
        val flows = FlowMode.entries
        pdfView.flowMode = flows[prefs.getInt(SettingsActivity.FLOW_MODE, 0).coerceIn(0, flows.lastIndex)]
        val fits = FitMode.entries
        pdfView.fitMode = fits[prefs.getInt(SettingsActivity.FIT_MODE, 0).coerceIn(0, fits.lastIndex)]
        pdfView.twoPage = prefs.getBoolean(SettingsActivity.TWO_PAGE, false)
        pdfView.cropMargins = prefs.getBoolean(SettingsActivity.CROP_MARGINS, false)
        val night = prefs.getBoolean(SettingsActivity.NIGHT, false)
        pdfView.night = night
        pdfView.paperBg = if (night) Color.rgb(12, 14, 13) else getColor(R.color.reader_bg)
        if (prefs.getBoolean(SettingsActivity.KEEP_SCREEN, true)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun documentUri(i: Intent): Uri? = when (i.action) {
        Intent.ACTION_SEND ->
            if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") (i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
        else -> i.data
    }

    // ---- UI ----------------------------------------------------------------------------

    private fun dp(v: Number) = Ui.dp(this, v)

    private fun icon(res: Int, desc: Int, click: (View) -> Unit) = ImageView(this).apply {
        setImageResource(res)
        contentDescription = getString(desc)
        setColorFilter(Color.WHITE)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        Ui.focusable(this, Color.TRANSPARENT, dp(22).toFloat(), Color.parseColor("#F5C84C"))
        setOnClickListener(click)
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
    }

    private fun smallTextBtn(label: String, click: (View) -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 18f
        gravity = Gravity.CENTER
        Ui.focusable(this, Color.TRANSPARENT, dp(18).toFloat(), Color.parseColor("#F5C84C"))
        setOnClickListener(click)
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
    }

    private fun buildUi() {
        val barColor = getColor(R.color.reader_bar)
        val root = FrameLayout(this)

        pdfView = PdfView(this, renderer).apply {
            listener = this@ReaderActivity
            isFocusable = true
            isFocusableInTouchMode = true
        }
        root.addView(pdfView, FrameLayout.LayoutParams(-1, -1))

        val topContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(barColor)
        }

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        topBar.addView(icon(R.drawable.ic_back, R.string.app_name) { finish() })
        titleView = TextView(this).apply {
            text = name.removeSuffix(".pdf").removeSuffix(".PDF")
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(4), 0, dp(4), 0)
        }
        topBar.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
        topBar.addView(icon(R.drawable.ic_sahl_search, R.string.search) { openSearch() })
        topBar.addView(icon(R.drawable.ic_annotate, R.string.annotate) { toggleAnnotateBar() })
        bookmarkBtn = icon(R.drawable.ic_bookmark, R.string.bookmark_add) { toggleCurrentBookmark() }
        topBar.addView(bookmarkBtn)
        topBar.addView(icon(R.drawable.ic_toc, R.string.contents) { showContents() })
        topBar.addView(icon(R.drawable.ic_night, R.string.night_pages) { toggleNight() })
        topBar.addView(icon(R.drawable.ic_sahl_more, R.string.settings_title) { showMenu(it) })
        topContainer.addView(topBar, LinearLayout.LayoutParams(-1, -2))

        annotateBar = buildAnnotateBar()
        topContainer.addView(annotateBar, LinearLayout.LayoutParams(-1, -2))

        searchBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(8), dp(6))
            visibility = View.GONE
        }
        searchInput = EditText(this).apply {
            hint = getString(R.string.search_hint)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#9AA3AB"))
            textSize = 15f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = Ui.rounded(Color.parseColor("#22FFFFFF"), dp(10).toFloat())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) { runSearch(); true } else false
            }
        }
        searchBar.addView(searchInput, LinearLayout.LayoutParams(0, -2, 1f))
        searchCount = TextView(this).apply {
            setTextColor(Color.parseColor("#D8E0DC"))
            textSize = 13f
            setPadding(dp(8), 0, dp(4), 0)
        }
        searchBar.addView(searchCount)
        searchBar.addView(smallTextBtn("▲") { stepSearch(-1) })
        searchBar.addView(smallTextBtn("▼") { stepSearch(1) })
        searchBar.addView(icon(R.drawable.ic_close, android.R.string.cancel) { closeSearch() })
        topContainer.addView(searchBar, LinearLayout.LayoutParams(-1, -2))

        root.addView(topContainer, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(barColor)
            setPadding(dp(16), dp(6), dp(8), dp(6))
        }
        pageLabel = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f
            minWidth = dp(72)
            Ui.focusable(this, Color.TRANSPARENT, dp(12).toFloat(), Color.parseColor("#F5C84C"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { askPage() }
        }
        bottomBar.addView(pageLabel)
        seek = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) { pdfView.goTo(p); updateLabel(p) }
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        bottomBar.addView(seek, LinearLayout.LayoutParams(0, dp(40), 1f))
        root.addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        pagePill = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f
            background = Ui.rounded(Color.parseColor("#CC0B110F"), dp(14).toFloat())
            setPadding(dp(12), dp(5), dp(12), dp(5))
            alpha = 0f
        }
        root.addView(pagePill, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(24)
        })

        val highlightColors = intArrayOf(
            0x66F5C84C,          // Gold
            0x552E9E6B,          // Emerald
            0x553B9DF2,          // Sky blue
            0x55E85D75,          // Rose
            0x559B72CF,          // Lavender
        )
        var activeColor = highlightColors[0]
        val colorDots = ArrayList<View>()

        selectionBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Ui.rounded(Color.parseColor("#EB0B110F"), dp(22).toFloat(), dp(1), Color.parseColor("#33FFFFFF"))
            setPadding(dp(10), dp(6), dp(10), dp(4))
            visibility = View.GONE
        }

        val dotsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(4))
        }
        fun refreshDots() {
            for (i in colorDots.indices) {
                val c = highlightColors[i] or 0xFF000000.toInt()
                val selected = highlightColors[i] == activeColor
                colorDots[i].background = Ui.rounded(c, dp(12).toFloat(), if (selected) dp(2) else 0, Color.WHITE)
            }
            pdfView.selectionColor = activeColor
        }
        for (c in highlightColors) {
            val dot = View(this).apply {
                setOnClickListener {
                    activeColor = c
                    refreshDots()
                }
            }
            colorDots += dot
            dotsRow.addView(dot, LinearLayout.LayoutParams(dp(22), dp(22)).apply { setMargins(dp(6), 0, dp(6), 0) })
        }
        refreshDots()
        selectionBar.addView(dotsRow)

        val actionsRow = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val btns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun selBtn(label: Int, click: () -> Unit) = TextView(this).apply {
            text = getString(label)
            setTextColor(Color.WHITE); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(6), dp(10), dp(6))
            Ui.focusable(this, Color.TRANSPARENT, dp(16).toFloat(), Color.parseColor("#F5C84C"))
            setOnClickListener { click() }
        }
        btns.addView(selBtn(R.string.copy) { copySelection() })
        btns.addView(selBtn(R.string.highlight) { saveSelection(withNote = false, color = activeColor, style = "highlight") })
        btns.addView(selBtn(R.string.underline) { saveSelection(withNote = false, color = activeColor, style = "underline") })
        btns.addView(selBtn(R.string.strikeout) { saveSelection(withNote = false, color = activeColor, style = "strikeout") })
        btns.addView(selBtn(R.string.squiggly) { saveSelection(withNote = false, color = activeColor, style = "squiggly") })
        btns.addView(selBtn(R.string.add_note) { saveSelection(withNote = true, color = activeColor, style = "highlight") })
        btns.addView(icon(R.drawable.ic_close, android.R.string.cancel) { pdfView.clearSelection() })
        actionsRow.addView(btns)
        selectionBar.addView(actionsRow)

        root.addView(selectionBar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(72)
        })

        progress = ProgressBar(this)
        root.addView(progress, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))

        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            val bottom = insets.systemWindowInsetBottom
            @Suppress("DEPRECATION")
            val left = insets.systemWindowInsetLeft
            @Suppress("DEPRECATION")
            val right = insets.systemWindowInsetRight
            topContainer.setPadding(left, top, right, 0)
            bottomBar.setPadding(dp(16) + left, dp(6), dp(8) + right, dp(6) + bottom)
            insets
        }
        setContentView(root)
        layoutBehindSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun layoutBehindSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    @Suppress("DEPRECATION")
    private fun setBars(show: Boolean) {
        if (show == barsShown) return
        barsShown = show
        val topParent = topBar.parent as View
        for (bar in listOf(topParent, bottomBar)) {
            if (show) {
                bar.visibility = View.VISIBLE
                bar.animate().alpha(1f).setDuration(150).start()
            } else {
                bar.animate().alpha(0f).setDuration(150).withEndAction { bar.visibility = View.GONE }.start()
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let { c ->
                if (show) c.show(WindowInsets.Type.systemBars())
                else {
                    c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    c.hide(WindowInsets.Type.systemBars())
                }
            }
        } else {
            val base = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            window.decorView.systemUiVisibility = if (show) base else base or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        if (!show) pdfView.requestFocus()
    }

    private fun updateLabel(p: Int) {
        val n = pdfView.pageCount
        pageLabel.text = getString(R.string.page_short, p + 1, n)
        pagePill.text = getString(R.string.page_short, p + 1, n)
        updateBookmarkIcon(p)
    }

    private fun updateBookmarkIcon(p: Int) {
        if (!::bookmarkBtn.isInitialized) return
        val marked = Library.isBookmarked(this, key, p)
        bookmarkBtn.setImageResource(if (marked) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark)
        if (marked) bookmarkBtn.clearColorFilter() else bookmarkBtn.setColorFilter(Color.WHITE)
    }

    // ---- document ----------------------------------------------------------------------

    private fun open(password: String?) {
        progress.visibility = View.VISIBLE
        renderer.call({ PdfDoc.open(this, uri, password) }) { r ->
            progress.visibility = View.GONE
            r.onSuccess { d -> onOpened(d) }
            r.onFailure { e ->
                android.util.Log.e("SahlPDF", "Failed to open $uri", e)
                if (e is PdfPasswordException) askPassword(password != null)
                else {
                    val msg = if (e is SecurityException || e is java.io.FileNotFoundException) R.string.file_gone else R.string.cannot_open
                    val detail = "${e.javaClass.simpleName}: ${e.message ?: ""}".trim()
                    fail(msg, detail)
                }
            }
        }
    }

    private fun onOpened(d: PdfDoc) {
        doc = d
        val saved = Library.get(this, key)
        val start = saved?.page?.coerceIn(0, d.pageCount - 1) ?: 0
        seek.max = d.pageCount - 1
        pdfView.setDocument(d, start)
        pdfView.setAnnotations(Library.annotations(this, key))
        pdfView.setInks(Library.inks(this, key))
        pdfView.setShapes(Library.shapes(this, key))
        updateLabel(start)
        Library.upsert(this, Book(key, uri.toString(), name, size, d.pageCount, start, System.currentTimeMillis()))
        val thumb = Library.thumb(this, key)
        if (!thumb.exists()) {
            val w = dp(96)
            renderer.call({
                val bmp = d.renderThumb(0, w)
                FileOutputStream(thumb).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            }) { }
        }
        pdfView.postDelayed({ if (!isFinishing && !searchOpen && pdfView.zoom == 1f) setBars(false) }, 1800)
    }

    private fun askPassword(wrong: Boolean) {
        val input = EditText(this).apply {
            hint = getString(R.string.password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle(R.string.password_title)
            .apply { if (wrong) setMessage(R.string.wrong_password) }
            .setView(box)
            .setPositiveButton(R.string.open) { _, _ -> open(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun fail(msg: Int, detail: String = "") {
        val text = if (detail.isBlank()) getString(msg) else "${getString(msg)}\n\n($detail)"
        AlertDialog.Builder(this)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    // ---- search ------------------------------------------------------------------------

    private fun openSearch() {
        searchOpen = true
        setBars(true)
        searchBar.visibility = View.VISIBLE
        searchInput.requestFocus()
        getSystemService(InputMethodManager::class.java)?.showSoftInput(searchInput, 0)
    }

    private fun closeSearch() {
        searchOpen = false
        searchGen++
        searchHits = emptyList()
        searchIdx = -1
        searchCount.text = ""
        searchBar.visibility = View.GONE
        pdfView.setSearchResults(emptyList(), -1)
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(searchInput.windowToken, 0)
        pdfView.requestFocus()
    }

    private fun runSearch() {
        val d = doc ?: return
        val q = searchInput.text.toString().trim()
        if (q.isEmpty()) return
        val gen = ++searchGen
        searchCount.text = "…"
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(searchInput.windowToken, 0)
        renderer.call({ d.search(q) { searchGen != gen || renderer.quit } }) { r ->
            if (gen != searchGen || isFinishing) return@call
            val hits = r.getOrDefault(emptyList())
            searchHits = hits
            if (hits.isEmpty()) {
                searchIdx = -1
                searchCount.text = getString(R.string.search_none)
                pdfView.setSearchResults(emptyList(), -1)
            } else {
                val cur = pdfView.page
                val firstOnOrAfter = hits.indexOfFirst { it.page >= cur }.let { if (it >= 0) it else 0 }
                searchIdx = firstOnOrAfter
                searchCount.text = getString(R.string.search_count, searchIdx + 1, hits.size)
                pdfView.setSearchResults(hits, searchIdx)
            }
        }
    }

    private fun stepSearch(delta: Int) {
        if (searchHits.isEmpty()) return
        searchIdx = (searchIdx + delta + searchHits.size) % searchHits.size
        searchCount.text = getString(R.string.search_count, searchIdx + 1, searchHits.size)
        pdfView.setSearchResults(searchHits, searchIdx)
    }

    // ---- bookmarks & actions -----------------------------------------------------------

    private fun toggleCurrentBookmark() {
        if (doc == null) return
        val p = pdfView.page
        val label = getString(R.string.page_of, p + 1, pdfView.pageCount)
        val added = Library.toggleBookmark(this, key, p, label)
        updateBookmarkIcon(p)
        Toast.makeText(this, if (added) R.string.bookmark_added else R.string.bookmark_removed, Toast.LENGTH_SHORT).show()
    }

    private fun showBookmarks() {
        val list = Library.bookmarks(this, key)
        if (list.isEmpty()) {
            Toast.makeText(this, R.string.no_bookmarks, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = list.map { getString(R.string.page_of, it.page + 1, pdfView.pageCount) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.bookmarks)
            .setItems(labels) { _, which -> pdfView.goTo(list[which].page); updateLabel(list[which].page) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toggleNight() {
        val on = !pdfView.night
        pdfView.night = on
        pdfView.paperBg = if (on) Color.rgb(12, 14, 13) else getColor(R.color.reader_bg)
        prefs.edit().putBoolean(SettingsActivity.NIGHT, on).apply()
    }

    private fun showContents() {
        val d = doc ?: return
        renderer.call({ d.toc() }) { r ->
            val items = r.getOrDefault(emptyList())
            if (items.isEmpty()) { Toast.makeText(this, R.string.no_contents, Toast.LENGTH_SHORT).show(); return@call }
            val labels = items.map { "\u2003".repeat(it.depth) + it.title + "  ·  " + (it.page + 1) }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(R.string.contents)
                .setItems(labels) { _, which -> pdfView.goTo(items[which].page); updateLabel(items[which].page) }
                .show()
        }
    }

    private fun askPage() {
        val n = pdfView.pageCount
        if (n == 0) return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.page_number_hint, n)
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle(R.string.go_to_page)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val p = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                pdfView.goTo((p - 1).coerceIn(0, n - 1))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseFlowMode() {
        val labels = arrayOf(getString(R.string.mode_vertical), getString(R.string.mode_ltr), getString(R.string.mode_rtl))
        val cur = prefs.getInt(SettingsActivity.FLOW_MODE, 0).coerceIn(0, 2)
        AlertDialog.Builder(this)
            .setTitle(R.string.reading_mode)
            .setSingleChoiceItems(labels, cur) { dlg, which ->
                prefs.edit().putInt(SettingsActivity.FLOW_MODE, which).apply()
                pdfView.flowMode = FlowMode.entries[which]
                dlg.dismiss()
            }.show()
    }

    private fun chooseFitMode() {
        val labels = arrayOf(getString(R.string.fit_width), getString(R.string.fit_page))
        val cur = prefs.getInt(SettingsActivity.FIT_MODE, 0).coerceIn(0, 1)
        AlertDialog.Builder(this)
            .setTitle(R.string.fit_mode)
            .setSingleChoiceItems(labels, cur) { dlg, which ->
                prefs.edit().putInt(SettingsActivity.FIT_MODE, which).apply()
                pdfView.fitMode = FitMode.entries[which]
                dlg.dismiss()
            }.show()
    }

    private fun toggleTwoPage() {
        val next = !pdfView.twoPage
        if (next && pdfView.flowMode == FlowMode.VERTICAL) {
            prefs.edit().putInt(SettingsActivity.FLOW_MODE, FlowMode.HORIZONTAL_LTR.ordinal).apply()
            pdfView.flowMode = FlowMode.HORIZONTAL_LTR
        }
        prefs.edit().putBoolean(SettingsActivity.TWO_PAGE, next).apply()
        pdfView.twoPage = next
    }

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, R.string.go_to_page)
        m.menu.add(0, 2, 0, R.string.bookmarks)
        m.menu.add(0, 11, 0, R.string.notes_title)
        m.menu.add(0, 3, 0, R.string.reading_mode)
        m.menu.add(0, 4, 0, R.string.fit_mode)
        m.menu.add(0, 10, 0, R.string.two_page).apply {
            isCheckable = true
            isChecked = pdfView.twoPage
        }
        m.menu.add(0, 5, 0, R.string.crop_margins).apply {
            isCheckable = true
            isChecked = pdfView.cropMargins
        }
        m.menu.add(0, 6, 0, R.string.doc_info)
        m.menu.add(0, 12, 0, R.string.save_annotated)
        if (uri.scheme == "content") {
            m.menu.add(0, 7, 0, R.string.share)
            m.menu.add(0, 8, 0, R.string.open_with)
        }
        m.menu.add(0, 9, 0, R.string.settings_title)
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> askPage()
                2 -> showBookmarks()
                3 -> chooseFlowMode()
                4 -> chooseFitMode()
                5 -> {
                    val next = !pdfView.cropMargins
                    prefs.edit().putBoolean(SettingsActivity.CROP_MARGINS, next).apply()
                    pdfView.cropMargins = next
                }
                6 -> showInfo()
                7 -> startSafely(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf")
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), getString(R.string.share)))
                8 -> startSafely(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), getString(R.string.open_with)))
                9 -> startActivity(Intent(this, SettingsActivity::class.java))
                10 -> toggleTwoPage()
                11 -> showAnnotations()
                12 -> startExportAnnotated()
            }
            true
        }
        m.show()
    }

    private fun startExportAnnotated() {
        val base = name.removeSuffix(".pdf").removeSuffix(".PDF")
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_TITLE, "$base-annotated.pdf")
        try { startActivityForResult(i, 44) } catch (_: Exception) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 44 || resultCode != RESULT_OK) return
        val outUri = data?.data ?: return
        val d = doc ?: return
        val anns = Library.annotations(this, key)
        val inks = Library.inks(this, key)
        val shapes = Library.shapes(this, key)
        Toast.makeText(this, R.string.saving_pdf, Toast.LENGTH_SHORT).show()
        progress.visibility = View.VISIBLE
        renderer.call({
            contentResolver.openOutputStream(outUri)?.use { os ->
                PdfExporter.export(d, anns, inks, shapes, os)
            } ?: error("Cannot write")
        }) { r ->
            progress.visibility = View.GONE
            r.onSuccess { Toast.makeText(this, R.string.saved_pdf, Toast.LENGTH_SHORT).show() }
            r.onFailure { Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun showTvMenu() {
        val items = listOf(
            R.string.contents to { showContents() },
            R.string.go_to_page to { askPage() },
            R.string.bookmarks to { showBookmarks() },
            R.string.notes_title to { showAnnotations() },
            R.string.search to { openSearch() },
            R.string.one_or_two_pages to { toggleTwoPage() },
            R.string.reading_mode to { chooseFlowMode() },
            R.string.fit_mode to { chooseFitMode() },
            R.string.crop_margins to {
                val next = !pdfView.cropMargins
                prefs.edit().putBoolean(SettingsActivity.CROP_MARGINS, next).apply()
                pdfView.cropMargins = next
            },
            R.string.night_pages to { toggleNight() },
            R.string.toolbars to { setBars(!barsShown) },
            R.string.settings_title to { startActivity(Intent(this, SettingsActivity::class.java)) },
        )
        val labels = items.map { getString(it.first) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.tv_menu)
            .setItems(labels) { _, which -> items[which].second() }
            .show()
    }

    private fun copyText(text: String) {
        getSystemService(android.content.ClipboardManager::class.java)
            .setPrimaryClip(android.content.ClipData.newPlainText("pdf", text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun copySelection() {
        val sel = pdfView.selection ?: return
        copyText(sel.text)
        pdfView.clearSelection()
    }

    private fun saveSelection(withNote: Boolean, color: Int = 0x66F5C84C, style: String = "highlight") {
        val sel = pdfView.selection ?: return
        if (!withNote) {
            Library.addAnnotation(this, key, sel.page, sel.rects, sel.text, "", color, style)
            pdfView.setAnnotations(Library.annotations(this, key))
            pdfView.clearSelection()
            return
        }
        showNoteEditor(R.string.add_note, sel.text, "") { note ->
            Library.addAnnotation(this, key, sel.page, sel.rects, sel.text, note, color, style)
            pdfView.setAnnotations(Library.annotations(this, key))
            pdfView.clearSelection()
        }
    }

    private fun showNoteEditor(titleRes: Int, quote: String, initialNote: String, onSave: (String) -> Unit) {
        val pal = Palette(this)
        val dlg = android.app.Dialog(this)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(pal.bg, dp(22).toFloat(), dp(1), pal.divider)
            setPadding(dp(20), dp(18), dp(20), dp(16))
        }
        card.addView(TextView(this).apply {
            text = getString(titleRes)
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(pal.text)
            setPadding(0, 0, 0, dp(10))
        })

        if (quote.isNotBlank()) {
            val quoteBox = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = Ui.rounded(pal.card, dp(14).toFloat())
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            quoteBox.addView(View(this).apply {
                setBackgroundColor(pal.gold)
            }, LinearLayout.LayoutParams(dp(3), -1).apply { marginEnd = dp(10) })
            quoteBox.addView(TextView(this).apply {
                text = quote.take(220)
                textSize = 13f
                setTextColor(pal.sub)
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(quoteBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }

        val input = EditText(this).apply {
            setText(initialNote)
            hint = getString(R.string.note_hint)
            setTextColor(pal.text)
            setHintTextColor(pal.sub)
            textSize = 15f
            gravity = Gravity.TOP or Gravity.START
            minLines = 5
            maxLines = 10
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = false
            background = Ui.rounded(pal.card, dp(16).toFloat(), dp(1), pal.divider)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        val fmtBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        fun fmtBtn(label: String, bold: Boolean = false, italic: Boolean = false, click: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(pal.text)
            typeface = when {
                bold -> Typeface.DEFAULT_BOLD
                italic -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                else -> Typeface.DEFAULT
            }
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            Ui.focusable(this, pal.card, dp(12).toFloat(), pal.gold)
            setOnClickListener { click() }
        }
        fmtBar.addView(fmtBtn("B", bold = true) { NoteFormat.wrap(input, "**", "**") },
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        fmtBar.addView(fmtBtn("I", italic = true) { NoteFormat.wrap(input, "*", "*") },
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        fmtBar.addView(fmtBtn("•") { NoteFormat.toggleLinePrefix(input, "- ") },
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        fmtBar.addView(fmtBtn("☑") { NoteFormat.toggleLinePrefix(input, "- [ ] ") })
        card.addView(fmtBar)
        card.addView(input, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        btnRow.addView(TextView(this).apply {
            text = getString(android.R.string.cancel)
            setTextColor(pal.sub); textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { dlg.dismiss() }
        })
        btnRow.addView(TextView(this).apply {
            text = getString(android.R.string.ok)
            setTextColor(Color.WHITE); textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(20), dp(10), dp(20), dp(10))
            Ui.focusable(this, pal.accent, dp(14).toFloat(), pal.gold)
            setOnClickListener {
                onSave(input.text.toString().trim())
                dlg.dismiss()
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        card.addView(btnRow)

        dlg.setContentView(card)
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), -2)
        dlg.show()
        input.requestFocus()
    }

    private fun showAnnotations() {
        val list = Library.annotations(this, key)
        if (list.isEmpty()) {
            Toast.makeText(this, R.string.no_notes, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = list.map { a ->
            val pStr = getString(R.string.page_short, a.page + 1, pdfView.pageCount)
            val body = a.note.ifBlank { a.text }.replace('\n', ' ').take(60)
            "$pStr  ·  $body"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.notes_title)
            .setItems(labels) { _, which ->
                val item = list[which]
                pdfView.goTo(item.page)
                updateLabel(item.page)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startSafely(i: Intent) {
        try { startActivity(i) } catch (_: Exception) { Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show() }
    }

    private fun showInfo() {
        val d = doc ?: return
        renderer.call({ d.title() }) { r ->
            val lines = buildList {
                r.getOrNull()?.let { add(getString(R.string.info_title) + ": " + it) }
                add(getString(R.string.info_file) + ": " + name)
                add(getString(R.string.info_pages) + ": " + d.pageCount)
                val bytes = this@ReaderActivity.size
                if (bytes > 0) add(getString(R.string.info_size) + ": " + Formatter.formatShortFileSize(this@ReaderActivity, bytes))
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.doc_info)
                .setMessage(lines.joinToString("\n"))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    // ---- PdfView.Listener --------------------------------------------------------------

    override fun onPageChanged(page: Int, count: Int) {
        updateLabel(page)
        if (!seekTracking()) seek.progress = page
        if (!barsShown) {
            pagePill.removeCallbacks(hidePill)
            pagePill.animate().cancel()
            pagePill.alpha = 1f
            pagePill.postDelayed(hidePill, 1200)
        }
    }

    private fun seekTracking() = seek.isPressed

    override fun onTap() {
        if (searchOpen) return
        setBars(!barsShown)
    }

    override fun onLinkTap(link: PageLink) {
        if (link.destPage != null) {
            pdfView.goTo(link.destPage)
            updateLabel(link.destPage)
        } else if (link.uri != null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.open_link)
                .setMessage(link.uri)
                .setPositiveButton(R.string.open) { _, _ -> startSafely(Intent(Intent.ACTION_VIEW, Uri.parse(link.uri))) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    override fun onSelectionChanged(selection: TextSelection?) {
        selectionBar.visibility = if (selection != null) View.VISIBLE else View.GONE
    }

    override fun onAnnotationTap(entry: Library.AnnotationEntry) {
        val pal = Palette(this)
        val dlg = android.app.Dialog(this)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(pal.bg, dp(22).toFloat(), dp(1), pal.divider)
            setPadding(dp(20), dp(18), dp(20), dp(14))
        }
        card.addView(TextView(this).apply {
            text = getString(R.string.page_of, entry.page + 1, pdfView.pageCount)
            textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(pal.text)
            setPadding(0, 0, 0, dp(10))
        })
        card.addView(TextView(this).apply {
            text = entry.text
            textSize = 13.5f; setTextColor(pal.sub)
            background = Ui.rounded(pal.card, dp(14).toFloat())
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        if (entry.note.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = NoteFormat.render(entry.note)
                textSize = 15f; setTextColor(pal.text)
                setPadding(dp(4), dp(2), dp(4), dp(12))
            })
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        fun btn(label: Int, color: Int, click: () -> Unit) = TextView(this).apply {
            text = getString(label); setTextColor(color); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(8), dp(12), dp(8)); setOnClickListener { click() }
        }
        row.addView(btn(R.string.delete, Color.parseColor("#E05A47")) {
            Library.removeAnnotation(this, key, entry.id)
            pdfView.setAnnotations(Library.annotations(this, key))
            dlg.dismiss()
        })
        row.addView(btn(R.string.copy, pal.sub) { copyText(entry.text); dlg.dismiss() })
        row.addView(btn(R.string.edit_note, pal.accent) {
            dlg.dismiss()
            showNoteEditor(R.string.edit_note, entry.text, entry.note) { updated ->
                Library.updateAnnotationNote(this, key, entry.id, updated)
                pdfView.setAnnotations(Library.annotations(this, key))
            }
        })
        card.addView(row)
        dlg.setContentView(card)
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), -2)
        dlg.show()
    }

    // ---- Xodo-style annotate bar (Pen, Marker, Eraser, Sticky note, Undo, Colors) ------

    private fun buildAnnotateBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(2), dp(8), dp(6))
            visibility = View.GONE
        }
        val toolsScroll = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val toolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val penColors = intArrayOf(
            0xFF0E6B4E.toInt(), // Sahl Emerald
            0xFFF5C84C.toInt(), // Sahl Gold
            0xFF2196F3.toInt(), // Blue
            0xFFE53935.toInt(), // Red
            0xFF111C18.toInt(), // Ink Black
        )
        val colorDots = ArrayList<View>()
        val toolBtns = LinkedHashMap<PdfView.ToolMode, TextView>()

        fun refreshAnnotateUi() {
            for ((m, tv) in toolBtns) {
                val active = pdfView.toolMode == m
                tv.background = Ui.rounded(if (active) Color.parseColor("#0E6B4E") else Color.parseColor("#22FFFFFF"), dp(14).toFloat())
                tv.setTextColor(Color.WHITE)
            }
            for (i in colorDots.indices) {
                val c = penColors[i]
                val sel = pdfView.penColor == c
                colorDots[i].background = Ui.rounded(c, dp(11).toFloat(), if (sel) dp(2) else 0, Color.WHITE)
            }
        }

        fun toolBtn(mode: PdfView.ToolMode, label: Int) = TextView(this).apply {
            text = getString(label)
            textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(11), dp(6), dp(11), dp(6))
            setOnClickListener {
                pdfView.toolMode = if (pdfView.toolMode == mode) PdfView.ToolMode.NONE else mode
                refreshAnnotateUi()
                if (pdfView.toolMode == PdfView.ToolMode.STICKY) {
                    Toast.makeText(this@ReaderActivity, R.string.tap_to_place_note, Toast.LENGTH_SHORT).show()
                }
            }
            toolBtns[mode] = this
        }

        val lp = { LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) } }
        toolsRow.addView(toolBtn(PdfView.ToolMode.PEN, R.string.pen), lp())
        toolsRow.addView(toolBtn(PdfView.ToolMode.MARKER, R.string.marker), lp())
        toolsRow.addView(toolBtn(PdfView.ToolMode.RECT, R.string.shape_rect), lp())
        toolsRow.addView(toolBtn(PdfView.ToolMode.CIRCLE, R.string.shape_circle), lp())
        toolsRow.addView(toolBtn(PdfView.ToolMode.ARROW, R.string.shape_arrow), lp())
        toolsRow.addView(toolBtn(PdfView.ToolMode.TEXT, R.string.free_text), lp())
        toolsRow.addView(toolBtn(PdfView.ToolMode.STICKY, R.string.sticky_note), lp())
        toolsRow.addView(toolBtn(PdfView.ToolMode.ERASER, R.string.eraser), lp())

        toolsRow.addView(TextView(this).apply {
            text = getString(R.string.undo)
            textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            background = Ui.rounded(Color.parseColor("#22FFFFFF"), dp(14).toFloat())
            setPadding(dp(11), dp(6), dp(11), dp(6))
            setOnClickListener { pdfView.setInks(Library.undoInk(this@ReaderActivity, key)) }
        }, lp())

        for (c in penColors) {
            val dot = View(this).apply {
                setOnClickListener {
                    pdfView.penColor = c
                    if (pdfView.toolMode == PdfView.ToolMode.NONE || pdfView.toolMode == PdfView.ToolMode.ERASER) {
                        pdfView.toolMode = PdfView.ToolMode.PEN
                    }
                    refreshAnnotateUi()
                }
            }
            colorDots += dot
            toolsRow.addView(dot, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(6) })
        }

        toolsRow.addView(TextView(this).apply {
            text = getString(R.string.done)
            textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#F5C84C"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { toggleAnnotateBar(forceClose = true) }
        })

        refreshAnnotateUi()
        toolsScroll.addView(toolsRow)
        bar.addView(toolsScroll)
        return bar
    }

    private fun toggleAnnotateBar(forceClose: Boolean = false) {
        val open = if (forceClose) false else annotateBar.visibility != View.VISIBLE
        if (open) {
            setBars(true)
            annotateBar.visibility = View.VISIBLE
            if (pdfView.toolMode == PdfView.ToolMode.NONE) pdfView.toolMode = PdfView.ToolMode.PEN
        } else {
            annotateBar.visibility = View.GONE
            pdfView.toolMode = PdfView.ToolMode.NONE
        }
    }

    override fun onInkAdded(page: Int, color: Int, width: Float, points: List<Float>) {
        Library.addInk(this, key, page, color, width, points)
        pdfView.setInks(Library.inks(this, key))
    }

    override fun onInkErased(id: String) {
        pdfView.setInks(Library.removeInk(this, key, id))
    }

    override fun onStickyTap(page: Int, nx: Float, ny: Float) {
        val rect = NormRect(nx, ny, (nx + 0.04f).coerceAtMost(1f), (ny + 0.04f).coerceAtMost(1f))
        showNoteEditor(R.string.sticky_note, "", "") { note ->
            if (note.isNotBlank()) {
                Library.addAnnotation(this, key, page, listOf(rect), getString(R.string.sticky_note), note, 0xFFF5C84C.toInt(), "sticky")
                pdfView.setAnnotations(Library.annotations(this, key))
            }
            toggleAnnotateBar(forceClose = true)
        }
    }

    override fun onShapeAdded(page: Int, kind: String, x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float) {
        Library.addShape(this, key, page, kind, x1, y1, x2, y2, color, width)
        pdfView.setShapes(Library.shapes(this, key))
    }

    override fun onShapeErased(id: String) {
        pdfView.setShapes(Library.removeShape(this, key, id))
    }

    override fun onTextTap(page: Int, nx: Float, ny: Float) {
        showNoteEditor(R.string.free_text, "", "") { text ->
            if (text.isNotBlank()) {
                Library.addShape(
                    this, key, page, "text",
                    nx, ny, (nx + 0.25f).coerceAtMost(1f), (ny + 0.06f).coerceAtMost(1f),
                    pdfView.penColor, pdfView.penWidth, text,
                )
                pdfView.setShapes(Library.shapes(this, key))
            }
            toggleAnnotateBar(forceClose = true)
        }
    }

    override fun onShapeTap(entry: Library.ShapeEntry) {
        if (entry.kind == "text") {
            showNoteEditor(R.string.free_text, "", entry.text) { updated ->
                if (updated.isBlank()) {
                    pdfView.setShapes(Library.removeShape(this, key, entry.id))
                } else {
                    pdfView.setShapes(Library.updateShapeText(this, key, entry.id, updated))
                }
            }
        }
    }

    // ---- keys (TV remote, keyboards) -----------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (doc == null) return super.onKeyDown(keyCode, event)
        val rtl = pdfView.flowMode == FlowMode.HORIZONTAL_RTL
        val step = pdfView.height * 0.3f
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> if (!barsShown) { pdfView.scrollByAnimated(step); return true }
            KeyEvent.KEYCODE_DPAD_UP -> if (!barsShown) { pdfView.scrollByAnimated(-step); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (!barsShown) { pdfView.goTo(pdfView.stepPage(dir = if (rtl) -1 else 1)); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (!barsShown) { pdfView.goTo(pdfView.stepPage(dir = if (rtl) 1 else -1)); return true }
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                pdfView.goTo(pdfView.stepPage(dir = 1)); return true
            }
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_CHANNEL_UP -> {
                pdfView.goTo(pdfView.stepPage(dir = -1)); return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (!barsShown) {
                if (Ui.isTv(this)) showTvMenu() else { setBars(true); topBar.getChildAt(0).requestFocus() }
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (Ui.isTv(this)) showTvMenu() else setBars(!barsShown)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onBackPressed() {
        if (::annotateBar.isInitialized && annotateBar.visibility == View.VISIBLE) { toggleAnnotateBar(forceClose = true); return }
        if (searchOpen) { closeSearch(); return }
        if (!barsShown && Ui.isTv(this)) { setBars(true); return }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // ---- lifecycle ---------------------------------------------------------------------

    override fun onPause() {
        super.onPause()
        val d = doc ?: return
        Library.setPage(this, key, pdfView.page, d.pageCount)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::pdfView.isInitialized) pdfView.release()
        val d = doc
        doc = null
        renderer.shutdown { d?.close() }
    }
}
