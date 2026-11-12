// Sahl PDF (TV edition of MJ PDF). License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.tv

import android.app.Activity
import android.view.KeyEvent
import com.github.barteksc.pdfviewer.PDFView

/**
 * TV remote in the reader:
 *  ◀ ▶  turn pages (follows the book's direction: in a right-to-left book ◀ goes forward, like a printed Arabic book)
 *  ▲ ▼  move up / down inside the page (or to the next / previous page when the page already fits)
 *  OK   show / hide the toolbars
 *  ⏩ ⏪ / channel + −  next / previous page
 * While the toolbars are open and a button has focus, the arrows move between buttons as usual.
 */
class TvRemote(
    private val activity: Activity,
    private val pdfView: PDFView,
    private val currentPage: () -> Int,
    private val toggleToolbars: () -> Unit,
) {
    fun handleKeyDown(keyCode: Int): Boolean {
        val focus = activity.currentFocus
        if (focus != null && focus !== pdfView) return false         // a toolbar button has focus: normal navigation
        val rtl = pdfView.isHorizontalReadingDirectionRtl()
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { turn(if (rtl) 1 else -1); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { turn(if (rtl) -1 else 1); true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { turn(1); true }
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { turn(-1); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { scroll(1); true }
            KeyEvent.KEYCODE_DPAD_UP -> { scroll(-1); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { toggleToolbars(); true }
            else -> false
        }
    }

    private fun turn(delta: Int) {
        val target = pdfView.getPageAfterRowStep(currentPage(), delta)
        if (target in 0 until pdfView.getPageCount()) pdfView.jumpTo(target, true)
    }

    /** Scrolls a third of the screen; in single-page (paged) mode this naturally moves to the next page at the edge. */
    private fun scroll(dir: Int) {
        val step = pdfView.height * 0.33f
        if (pdfView.isSwipeVertical() || pdfView.getZoom() > 1.05f) pdfView.moveRelativeTo(0f, -dir * step)
        else turn(dir)
        pdfView.loadPages()
    }
}
