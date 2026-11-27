package com.sahl.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfPage
import io.legere.pdfiumandroid.PdfiumCore

/** A table-of-contents entry, flattened with its depth. */
data class TocItem(val title: String, val page: Int, val depth: Int)

/** One search match on [page] with normalized [0..1] highlight boxes. */
data class SearchHit(val page: Int, val rects: List<NormRect>)

/** A clickable link on a page with normalized [0..1] bounds. */
data class PageLink(val bounds: NormRect, val destPage: Int?, val uri: String?)

/** A selected text span on [page] with normalized [0..1] boxes. */
data class TextSelection(
    val page: Int,
    val startChar: Int,
    val endChar: Int,
    val text: String,
    val rects: List<NormRect>,
)

/**
 * An open PDF. Not thread-safe: every call must happen on the [Renderer] thread.
 */
class PdfDoc private constructor(
    private val pdf: PdfDocument,
    private val pfd: ParcelFileDescriptor,
    val sizes: List<PageSize>,
    private val tempFile: java.io.File? = null,
) {
    val pageCount get() = sizes.size

    private val openPages = object : LinkedHashMap<Int, PdfPage>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PdfPage>): Boolean {
            if (size <= 6) return false
            runCatching { eldest.value.close() }
            return true
        }
    }

    private fun page(i: Int): PdfPage = openPages.getOrPut(i) { pdf.openPage(i) }

    /** Renders page [i] scaled to [drawW]x[drawH], shifted by ([startX], [startY]), into [bmp]. */
    fun render(i: Int, bmp: Bitmap, startX: Int, startY: Int, drawW: Int, drawH: Int) {
        page(i).renderPageBitmap(
            bmp, startX, startY, drawW, drawH,
            renderAnnot = true, canvasColor = Color.WHITE, pageBackgroundColor = Color.WHITE,
        )
    }

    fun renderThumb(i: Int, width: Int): Bitmap {
        val s = sizes[i]
        val h = (width * (s.height / s.width)).toInt().coerceIn(1, width * 3)
        val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
        render(i, bmp, 0, 0, width, h)
        return bmp
    }

    fun detectCrop(i: Int): NormRect {
        val bmp = renderThumb(i, 160)
        return try {
            val w = bmp.width; val h = bmp.height
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            MarginCrop.detect(pixels, w, h)
        } finally {
            bmp.recycle()
        }
    }

    fun search(query: String, cancel: () -> Boolean): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val hits = ArrayList<SearchHit>()
        for (i in 0 until pageCount) {
            if (cancel()) break
            runCatching {
                val p = page(i)
                p.openTextPage().use { tp ->
                    tp.findStart(q, emptySet(), 0)?.use { fr ->
                        while (!cancel() && fr.findNext()) {
                            val start = fr.getSchResultIndex()
                            val count = fr.getSchCount()
                            val nRects = tp.textPageCountRects(start, count)
                            val rects = ArrayList<NormRect>(nRects)
                            for (r in 0 until nRects) {
                                val pr = tp.textPageGetRect(r) ?: continue
                                val dr = p.mapRectToDevice(0, 0, 10000, 10000, 0, pr)
                                val left = minOf(dr.left, dr.right) / 10000f
                                val top = minOf(dr.top, dr.bottom) / 10000f
                                val right = maxOf(dr.left, dr.right) / 10000f
                                val bottom = maxOf(dr.top, dr.bottom) / 10000f
                                if (right > left && bottom > top) rects += NormRect(left, top, right, bottom)
                            }
                            if (rects.isNotEmpty()) hits += SearchHit(i, rects)
                        }
                    }
                }
            }
        }
        return hits
    }

    fun links(i: Int): List<PageLink> {
        val out = ArrayList<PageLink>()
        runCatching {
            val p = page(i)
            for (l in p.getPageLinks()) {
                val dr = p.mapRectToDevice(0, 0, 10000, 10000, 0, l.bounds)
                val left = minOf(dr.left, dr.right) / 10000f
                val top = minOf(dr.top, dr.bottom) / 10000f
                val right = maxOf(dr.left, dr.right) / 10000f
                val bottom = maxOf(dr.top, dr.bottom) / 10000f
                if (right > left && bottom > top) {
                    val dest = l.destPageIdx?.takeIf { it in 0 until pageCount }
                    val uri = l.uri?.takeIf { it.isNotBlank() }
                    if (dest != null || uri != null) out += PageLink(NormRect(left, top, right, bottom), dest, uri)
                }
            }
        }
        return out
    }

    fun selectWordAt(pageIdx: Int, nx: Float, ny: Float): TextSelection? = runCatching {
        val p = page(pageIdx)
        val pt = p.mapDeviceCoordsToPage(0, 0, 10000, 10000, 0, (nx * 10000f).toInt(), (ny * 10000f).toInt())
        p.openTextPage().use { tp ->
            val total = tp.textPageCountChars()
            if (total <= 0) return null
            val idx = tp.textPageGetCharIndexAtPos(pt.x.toDouble(), pt.y.toDouble(), 16.0, 16.0)
            if (idx !in 0 until total) return null
            var start = idx
            var end = idx
            while (start > 0 && !tp.textPageGetUnicode(start - 1).isWhitespace()) start--
            while (end + 1 < total && !tp.textPageGetUnicode(end + 1).isWhitespace()) end++
            buildSelection(p, tp, pageIdx, start, end)
        }
    }.getOrNull()

    fun selectRange(pageIdx: Int, anchorChar: Int, nx: Float, ny: Float): TextSelection? = runCatching {
        val p = page(pageIdx)
        val pt = p.mapDeviceCoordsToPage(0, 0, 10000, 10000, 0, (nx * 10000f).toInt(), (ny * 10000f).toInt())
        p.openTextPage().use { tp ->
            val total = tp.textPageCountChars()
            if (total <= 0) return null
            val idx = tp.textPageGetCharIndexAtPos(pt.x.toDouble(), pt.y.toDouble(), 24.0, 24.0)
            if (idx !in 0 until total) return null
            val a = anchorChar.coerceIn(0, total - 1)
            buildSelection(p, tp, pageIdx, minOf(a, idx), maxOf(a, idx))
        }
    }.getOrNull()

    fun selectBetween(
        pageIdx: Int,
        startChar: Int,
        endChar: Int,
        nx: Float,
        ny: Float,
        moveStart: Boolean,
    ): TextSelection? = runCatching {
        val p = page(pageIdx)
        val pt = p.mapDeviceCoordsToPage(0, 0, 10000, 10000, 0, (nx * 10000f).toInt(), (ny * 10000f).toInt())
        p.openTextPage().use { tp ->
            val total = tp.textPageCountChars()
            if (total <= 0) return null
            val idx = tp.textPageGetCharIndexAtPos(pt.x.toDouble(), pt.y.toDouble(), 28.0, 28.0)
            if (idx !in 0 until total) return null
            val s = (if (moveStart) idx else startChar).coerceIn(0, total - 1)
            val e = (if (moveStart) endChar else idx).coerceIn(0, total - 1)
            buildSelection(p, tp, pageIdx, minOf(s, e), maxOf(s, e))
        }
    }.getOrNull()

    private fun buildSelection(
        p: PdfPage,
        tp: io.legere.pdfiumandroid.PdfTextPage,
        pageIdx: Int,
        start: Int,
        end: Int,
    ): TextSelection? {
        val count = end - start + 1
        val text = tp.textPageGetText(start, count)?.trim().orEmpty()
        if (text.isEmpty()) return null
        val nRects = tp.textPageCountRects(start, count)
        val rects = ArrayList<NormRect>(nRects)
        for (r in 0 until nRects) {
            val pr = tp.textPageGetRect(r) ?: continue
            val dr = p.mapRectToDevice(0, 0, 10000, 10000, 0, pr)
            val left = minOf(dr.left, dr.right) / 10000f
            val top = minOf(dr.top, dr.bottom) / 10000f
            val right = maxOf(dr.left, dr.right) / 10000f
            val bottom = maxOf(dr.top, dr.bottom) / 10000f
            if (right > left && bottom > top) rects += NormRect(left, top, right, bottom)
        }
        val merged = NoteFormat.mergeLineRects(rects)
        return if (merged.isEmpty()) null else TextSelection(pageIdx, start, end, text, merged)
    }

    fun textInRect(pageIdx: Int, bounds: NormRect): String = runCatching {
        val p = page(pageIdx)
        val tl = p.mapDeviceCoordsToPage(0, 0, 10000, 10000, 0, (bounds.left * 10000f).toInt(), (bounds.top * 10000f).toInt())
        val br = p.mapDeviceCoordsToPage(0, 0, 10000, 10000, 0, (bounds.right * 10000f).toInt(), (bounds.bottom * 10000f).toInt())
        p.openTextPage().use { tp ->
            val total = tp.textPageCountChars()
            if (total <= 0) return ""
            val c1 = tp.textPageGetCharIndexAtPos(tl.x.toDouble(), tl.y.toDouble(), 24.0, 24.0)
            val c2 = tp.textPageGetCharIndexAtPos(br.x.toDouble(), br.y.toDouble(), 24.0, 24.0)
            val c3 = tp.textPageGetCharIndexAtPos(br.x.toDouble(), tl.y.toDouble(), 24.0, 24.0)
            val c4 = tp.textPageGetCharIndexAtPos(tl.x.toDouble(), br.y.toDouble(), 24.0, 24.0)
            val valid = listOf(c1, c2, c3, c4).filter { it in 0 until total }
            if (valid.size >= 2) {
                val s = valid.min()
                val e = valid.max()
                tp.textPageGetText(s, (e - s + 1).coerceAtMost(240))?.trim().orEmpty()
            } else ""
        }
    }.getOrDefault("")

    fun toc(): List<TocItem> {
        val out = ArrayList<TocItem>()
        fun walk(list: List<PdfDocument.Bookmark>, depth: Int) {
            for (b in list) {
                val p = b.pageIdx.toInt()
                if (p in 0 until pageCount) out += TocItem(b.title?.trim().orEmpty().ifBlank { "…" }, p, depth)
                if (depth < 6) walk(b.children, depth + 1)
            }
        }
        runCatching { walk(pdf.getTableOfContents(), 0) }
        return out
    }

    fun title(): String? = runCatching { pdf.getDocumentMeta().title }.getOrNull()?.takeIf { it.isNotBlank() }

    fun close() {
        openPages.values.forEach { runCatching { it.close() } }
        openPages.clear()
        runCatching { pdf.close() }
        runCatching { pfd.close() }
        runCatching { tempFile?.delete() }
    }

    companion object {
        @Volatile private var core: PdfiumCore? = null

        private fun core(c: Context): PdfiumCore =
            core ?: synchronized(this) { core ?: PdfiumCore(c.applicationContext).also { core = it } }

        /** Opens [uri]; throws [io.legere.pdfiumandroid.PdfPasswordException] when a (correct) password is needed. */
        fun open(c: Context, uri: Uri, password: String?): PdfDoc {
            var temp: java.io.File? = null
            var pfd: ParcelFileDescriptor? = null
            var pdf: PdfDocument? = null
            try {
                val direct = c.contentResolver.openFileDescriptor(uri, "r")
                if (direct != null) {
                    try {
                        pfd = direct
                        pdf = core(c).newDocument(direct, password)
                    } catch (e: io.legere.pdfiumandroid.PdfPasswordException) {
                        throw e
                    } catch (_: Throwable) {
                        runCatching { direct.close() }
                        pfd = null
                    }
                }
                if (pdf == null) {
                    val tmp = java.io.File.createTempFile("sahl_", ".pdf", c.cacheDir)
                    temp = tmp
                    val inp = c.contentResolver.openInputStream(uri) ?: error("Cannot open stream")
                    inp.use { src -> tmp.outputStream().use { dst -> src.copyTo(dst) } }
                    val tpfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
                    pfd = tpfd
                    pdf = core(c).newDocument(tpfd, password)
                }
                val n = pdf.getPageCount()
                if (n <= 0) error("No pages")
                val sizes = ArrayList<PageSize>(n)
                for (i in 0 until n) {
                    pdf.openPage(i).use { p ->
                        sizes += PageSize(p.getPageWidthPoint().toFloat(), p.getPageHeightPoint().toFloat())
                    }
                }
                return PdfDoc(pdf, pfd!!, sizes, temp)
            } catch (e: Throwable) {
                runCatching { pdf?.close() }
                runCatching { pfd?.close() }
                runCatching { temp?.delete() }
                throw e
            }
        }
    }
}

/**
 * Single background thread that owns all PDFium work. Render requests are "latest wins":
 * each [submit] replaces whatever is still waiting, so only what is on screen gets rendered.
 */
class Renderer {
    class Job(val key: String, val work: () -> Bitmap?, val done: (Bitmap?) -> Unit)

    private val thread = HandlerThread("sahl-pdf", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val bg = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val waiting = LinkedHashMap<String, Job>()
    private var draining = false
    @Volatile private var busy: String? = null
    @Volatile var quit = false; private set

    /** Runs [block] on the PDF thread and delivers its result on the main thread. */
    fun <T> call(block: () -> T, result: (Result<T>) -> Unit) {
        bg.post {
            val r = runCatching { block() }
            main.post { if (!quit) result(r) }
        }
    }

    fun submit(jobs: List<Job>) {
        synchronized(lock) {
            waiting.clear()
            for (j in jobs) if (j.key != busy) waiting[j.key] = j
            if (!draining && waiting.isNotEmpty()) {
                draining = true
                bg.post(::drain)
            }
        }
    }

    private fun drain() {
        while (true) {
            val job = synchronized(lock) {
                val first = waiting.entries.firstOrNull()
                if (first == null || quit) { draining = false; null }
                else { waiting.remove(first.key); busy = first.key; first.value }
            } ?: return
            val bmp = try { job.work() } catch (_: OutOfMemoryError) { null } catch (_: Exception) { null }
            busy = null
            main.post { if (!quit) job.done(bmp) }
        }
    }

    /** Stops rendering, runs [cleanup] on the PDF thread, then ends the thread. */
    fun shutdown(cleanup: () -> Unit) {
        quit = true
        synchronized(lock) { waiting.clear() }
        bg.post {
            runCatching(cleanup)
            thread.quitSafely()
        }
    }
}
