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
    private lateinit var bottomBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var pageLabel: TextView
    private lateinit var pagePill: TextView
    private lateinit var seek: SeekBar
    private lateinit var progress: ProgressBar

    private var doc: PdfDoc? = null
    private lateinit var uri: Uri
    private var name = ""
    private var size = -1L
    private var key = ""
    private var barsShown = true
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
        if (prefs.getBoolean(SettingsActivity.KEEP_SCREEN, true)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        open(null)
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
        setPadding(dp(12), dp(12), dp(12), dp(12))
        Ui.focusable(this, Color.TRANSPARENT, dp(24).toFloat(), Color.parseColor("#F5C84C"))
        setOnClickListener(click)
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
    }

    private fun buildUi() {
        val barColor = getColor(R.color.reader_bar)
        val root = FrameLayout(this)

        pdfView = PdfView(this, renderer).apply {
            listener = this@ReaderActivity
            isFocusable = true
            isFocusableInTouchMode = true
            night = prefs.getBoolean(SettingsActivity.NIGHT, false)
            paperBg = if (night) Color.rgb(12, 14, 13) else getColor(R.color.reader_bg)
        }
        root.addView(pdfView, FrameLayout.LayoutParams(-1, -1))

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(barColor)
            setPadding(dp(4), 0, dp(4), 0)
        }
        topBar.addView(icon(R.drawable.ic_back, R.string.app_name) { finish() })
        titleView = TextView(this).apply {
            text = name.removeSuffix(".pdf").removeSuffix(".PDF")
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(4), 0, dp(4), 0)
        }
        topBar.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
        topBar.addView(icon(R.drawable.ic_toc, R.string.contents) { showContents() })
        topBar.addView(icon(R.drawable.ic_night, R.string.night_pages) { toggleNight() })
        topBar.addView(icon(R.drawable.ic_sahl_more, R.string.settings_title) { showMenu(it) })
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

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
            topBar.setPadding(dp(4) + left, top, dp(4) + right, 0)
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
        for (bar in listOf(topBar, bottomBar)) {
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
    }

    // ---- document ----------------------------------------------------------------------

    private fun open(password: String?) {
        progress.visibility = View.VISIBLE
        renderer.call({ PdfDoc.open(this, uri, password) }) { r ->
            progress.visibility = View.GONE
            r.onSuccess { d -> onOpened(d) }
            r.onFailure { e ->
                if (e is PdfPasswordException) askPassword(password != null)
                else fail(if (e is SecurityException || e is java.io.FileNotFoundException) R.string.file_gone else R.string.cannot_open)
            }
        }
    }

    private fun onOpened(d: PdfDoc) {
        doc = d
        val saved = Library.get(this, key)
        val start = saved?.page?.coerceIn(0, d.pageCount - 1) ?: 0
        seek.max = d.pageCount - 1
        pdfView.setDocument(d, start)
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
        pdfView.postDelayed({ if (!isFinishing && pdfView.zoom == 1f) setBars(false) }, 1800)
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

    private fun fail(msg: Int) {
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    // ---- actions -----------------------------------------------------------------------

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

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, R.string.go_to_page)
        m.menu.add(0, 2, 0, R.string.doc_info)
        if (uri.scheme == "content") {
            m.menu.add(0, 3, 0, R.string.share)
            m.menu.add(0, 4, 0, R.string.open_with)
        }
        m.menu.add(0, 5, 0, R.string.settings_title)
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> askPage()
                2 -> showInfo()
                3 -> startSafely(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf")
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), getString(R.string.share)))
                4 -> startSafely(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), getString(R.string.open_with)))
                5 -> startActivity(Intent(this, SettingsActivity::class.java))
            }
            true
        }
        m.show()
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

    override fun onTap() = setBars(!barsShown)

    // ---- keys (TV remote, keyboards) -----------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (doc == null) return super.onKeyDown(keyCode, event)
        val step = pdfView.height * 0.3f
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> if (!barsShown) { pdfView.scrollByAnimated(step); return true }
            KeyEvent.KEYCODE_DPAD_UP -> if (!barsShown) { pdfView.scrollByAnimated(-step); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (!barsShown) { pdfView.goTo(pdfView.page + 1); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (!barsShown) { pdfView.goTo(pdfView.page - 1); return true }
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                pdfView.goTo(pdfView.page + 1); return true
            }
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_CHANNEL_UP -> {
                pdfView.goTo(pdfView.page - 1); return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (!barsShown) { setBars(true); topBar.getChildAt(0).requestFocus(); return true }
            KeyEvent.KEYCODE_MENU -> { setBars(!barsShown); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onBackPressed() {
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
