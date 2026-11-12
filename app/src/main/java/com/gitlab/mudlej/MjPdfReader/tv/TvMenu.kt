// Sahl PDF (TV edition of MJ PDF). License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.tv

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import com.github.barteksc.pdfviewer.PDFView
import com.gitlab.mudlej.MjPdfReader.data.PageFitPolicy
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeGridSize
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeSortOrder
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeTab
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeViewMode
import com.gitlab.mudlej.MjPdfReader.ui.reader.actions.ConfigurableActionResolver
import java.util.Locale

/**
 * One-time TV settings: an open book (two pages side by side, whole page visible), page-by-page turning,
 * no toolbars until you ask for them, and no touch-screen tips.
 */
object TvDefaults {
    private const val KEY = "sahlTvDefaultsV1"

    fun apply(ctx: Context) {
        if (!ctx.isTv()) return
        applyLibrary(ctx)
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        if (sp.getBoolean(KEY, false)) return
        val pref = Preferences(sp)
        pref.setDualPageMode(true)
        pref.setPageFitPolicy(PageFitPolicy.BOTH)
        pref.setSinglePageMode(true)
        pref.setAutoFullScreen(true)
        pref.setShowExitFullscreenTip(false)
        sp.edit().putBoolean(KEY, true).apply()
    }

    /** Library on the TV: open on Recent (it starts with "Continue Reading"), big covers in a grid, newest first. */
    private const val KEY2 = "sahlTvDefaultsV2"

    fun applyLibrary(ctx: Context) {
        if (!ctx.isTv()) return
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        if (sp.getBoolean(KEY2, false)) return
        val pref = Preferences(sp)
        pref.setHomeTab(HomeTab.RECENT)
        pref.setHomeViewMode(HomeViewMode.GRID)
        pref.setHomeGridSize(HomeGridSize.LARGE)
        pref.setHomeSort(HomeSortOrder.LAST_OPENED)
        sp.edit().putBoolean(KEY2, true).apply()
    }
}

/**
 * The TV menu (press OK while reading): everything useful with a remote, in one simple list.
 * Touch-only tools (signatures, drawing, screenshots, rotate, print) are left out on purpose.
 */
object TvMenu {
    fun show(a: Activity, pdfView: PDFView, h: ConfigurableActionResolver.Handlers, dualOn: Boolean, toolbarsShown: Boolean, onClosed: () -> Unit) {
        val ar = Locale.getDefault().language == "ar"
        fun t(en: String, x: String) = if (ar) x else en
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += t("☰  Table of contents", "☰  الفهرس") to h.tableOfContents
        items += t("#  Go to page", "#  الانتقال إلى صفحة") to h.goToPage
        items += t("🔖  Bookmark this page (on / off)", "🔖  علامة لهذه الصفحة (إضافة / إزالة)") to h.toggleBookmark
        items += t("📑  My bookmarks", "📑  علاماتي") to h.userBookmarks
        items += t("🔍  Search in the book", "🔍  البحث في الكتاب") to h.search
        items += (if (dualOn) t("▭  One page at a time", "▭  صفحة واحدة") else t("▭▭  Two pages side by side", "▭▭  صفحتان متجاورتان")) to h.toggleDualPage
        items += t("🌓  Dark / light page", "🌓  صفحة داكنة / فاتحة") to h.switchTheme
        items += t("＋  Zoom in", "＋  تكبير") to { pdfView.zoomWithAnimation((pdfView.zoom * 1.4f).coerceAtMost(8f)) }
        items += t("－  Zoom out", "－  تصغير") to { pdfView.zoomWithAnimation((pdfView.zoom / 1.4f).coerceAtLeast(1f)) }
        items += t("⤢  Fit the whole page", "⤢  ملاءمة الصفحة كاملة") to { pdfView.resetZoomWithAnimation() }
        items += t("✂  Hide / show margins", "✂  إخفاء / إظهار الهوامش") to h.toggleCropMargins
        items += t("⇄  Reading direction (right-to-left books)", "⇄  اتجاه القراءة (كتب من اليمين لليسار)") to h.readingDirection
        items += t("📖  Read as text (e-book mode)", "📖  القراءة كنص (وضع الكتاب الإلكتروني)") to h.textMode
        items += t("↶  Back to where I was", "↶  الرجوع إلى الموضع السابق") to h.navigateBack
        items += (if (toolbarsShown) t("▢  Hide the toolbars", "▢  إخفاء الأشرطة") else t("▤  Show the toolbars", "▤  إظهار الأشرطة")) to h.toggleFullscreen
        items += t("⚙  Settings", "⚙  الإعدادات") to h.settings
        items += t("ⓘ  About", "ⓘ  حول") to h.about
        AlertDialog.Builder(a)
            .setTitle("Sahl PDF")
            .setItems(items.map { it.first }.toTypedArray()) { _, w -> items[w].second() }
            .setOnDismissListener { onClosed() }
            .show()
    }
}
