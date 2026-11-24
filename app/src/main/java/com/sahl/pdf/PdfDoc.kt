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
