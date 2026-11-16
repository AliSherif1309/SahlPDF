package com.gitlab.mudlej.MjPdfReader.scanner

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ScannerState(
    val draft: ScanDraft? = null,
    val busy: Boolean = true,
    val message: Message? = null,
    val canUndo: Boolean = false,
) {
    enum class Message { FAILED, SAVED, REVIEW, BLUR }
}
class ScannerViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val store = ScanStore(application)
    private val lock = Mutex()
    private val mutable = MutableStateFlow(ScannerState())
    val state = mutable.asStateFlow()
    private val undo = ArrayDeque<ScanDraft>()
    private val thumbnails = object : android.util.LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    init {
        viewModelScope.launch {
            try {
                val draft = withContext(Dispatchers.IO) { lock.withLock { store.load() } }
                mutable.value = ScannerState(draft = draft, busy = false)
                consumeCapture()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = ScannerState(busy = false, message = ScannerState.Message.FAILED) }
        }
    }
    fun select(id: String) {
        val s = mutable.value
        if (!s.busy && s.draft?.pages?.any { it.id == id } == true) {
            mutable.value = s.copy(draft = s.draft.copy(selected = id), message = null)
            persist()
        }
    }
    private fun persist() {
        viewModelScope.launch(Dispatchers.IO) {
            try { lock.withLock { if (!mutable.value.busy) mutable.value.draft?.let { store.save(it) } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = mutable.value.copy(message = ScannerState.Message.FAILED) }
        }
    }
    private fun edit(change: (ScanDraft, ScanPage) -> ScanDraft) {
        val s = mutable.value
        val draft = s.draft ?: return
        val page = draft.selectedPage() ?: return
        if (s.busy) return
        val next = change(draft, page)
        if (next == draft) return
        if (undo.size == 20) undo.removeFirst()
        undo.addLast(draft)
        mutable.value = s.copy(draft = next, canUndo = true, message = null)
        persist()
    }
    fun corners(points: List<ScanPoint>) {
        if (ScanGeometry.valid(points)) edit { draft, page ->
            draft.copy(pages = draft.pages.map { if (it.id == page.id) it.copy(corners = points) else it })
        }
    }
    fun filter(value: ScanFilter) = edit { draft, page ->
        draft.copy(pages = draft.pages.map { if (it.id == page.id) it.copy(filter = value) else it })
    }
    fun rotate() = edit { draft, page ->
        draft.copy(pages = draft.pages.map { if (it.id == page.id) it.copy(rotation = (it.rotation + 90) % 360) else it })
    }
    fun move(delta: Int) = edit { draft, page ->
        val pages = draft.pages.toMutableList()
        val from = pages.indexOfFirst { it.id == page.id }
        val to = from + delta
        if (to in pages.indices) java.util.Collections.swap(pages, from, to)
        draft.copy(pages = pages)
    }
    fun delete() = edit { draft, page ->
        val pages = draft.pages.filterNot { it.id == page.id }
        draft.copy(pages = pages, selected = pages.firstOrNull()?.id)
    }
    fun undo() {
        if (!mutable.value.busy && undo.isNotEmpty()) {
            mutable.value = mutable.value.copy(draft = undo.removeLast(), canUndo = undo.isNotEmpty(), message = null)
            persist()
        }
    }
    fun newDraft() {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true)
        viewModelScope.launch {
            try {
                val draft = withContext(Dispatchers.IO) { lock.withLock {
                    store.fresh(discardPrevious = true)
                } }
                undo.clear()
                thumbnails.evictAll()
                mutable.value = ScannerState(draft, false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = mutable.value.copy(busy = false, message = ScannerState.Message.FAILED) }
        }
    }
    fun beginCapture(retake: Boolean, launch: (File) -> Unit) {
        val s = mutable.value
        val draft = s.draft ?: return
        if (s.busy) return
        mutable.value = s.copy(busy = true)
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { lock.withLock { store.captureFile(draft, retake) } }
                saved["capture"] = file.name
                saved["retake"] = if (retake) draft.selectedPage()?.id else null
                mutable.value = s
                launch(file)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = s.copy(message = ScannerState.Message.FAILED) }
        }
    }
    fun captureReturned(success: Boolean) {
        saved["capture_success"] = success
        consumeCapture()
    }
    private fun consumeCapture() {
        if (mutable.value.busy) return
        val draft = mutable.value.draft ?: return
        val success = saved.get<Boolean>("capture_success") ?: return
        val name = saved.get<String>("capture") ?: return
        val retake = saved.get<String>("retake")
        saved.remove<Boolean>("capture_success"); saved.remove<String>("capture"); saved.remove<String>("retake")
        require(Regex("""capture-[a-f0-9-]{36}\.jpg""").matches(name))
        val file = File(store.directory(draft.id), name)
        if (!success) {
            viewModelScope.launch(Dispatchers.IO) { file.delete() }
        } else add(null, file, retake)
    }
    fun import(uri: Uri) = add(uri, null, null)
    private fun add(uri: Uri?, captured: File?, retake: String?) {
        val s = mutable.value
        val draft = s.draft ?: return
        if (s.busy) return
        mutable.value = s.copy(busy = true, message = null)
        viewModelScope.launch {
            var source = captured
            var committed = false
            try {
                val pair = withContext(Dispatchers.IO) { lock.withLock {
                    require(draft.pages.size < ScanStore.MAX_PAGES || retake != null)
                    if (source == null) source = store.import(draft, uri!!)
                    require(source!!.length() in 1..ScanStore.MAX_IMAGE_BYTES && store.bytes(draft) <= ScanStore.MAX_DRAFT_BYTES)
                    val analysis = ScanImageProcessor().analyze(source!!)
                    val page = ScanPage(retake ?: UUID.randomUUID().toString(), source!!.name, analysis.corners)
                    val pages = if (retake != null) draft.pages.map { if (it.id == retake) page else it } else draft.pages + page
                    val next = draft.copy(pages = pages, selected = page.id)
                    store.save(next)
                    committed = true
                    next to analysis
                } }
                if (undo.size == 20) undo.removeFirst()
                undo.addLast(draft)
                mutable.value = ScannerState(pair.first, false,
                    if (pair.second.mayBeBlurred) ScannerState.Message.BLUR else ScannerState.Message.REVIEW, true)
            } catch (cancelled: CancellationException) {
                if (!committed) source?.delete()
                throw cancelled
            } catch (_: Exception) {
                if (!committed) source?.delete()
                mutable.value = s.copy(message = ScannerState.Message.FAILED)
            }
        }
    }
    suspend fun thumbnail(draft: ScanDraft, page: ScanPage): Bitmap = withContext(Dispatchers.IO) {
        lock.withLock {
            val key = draft.id + ":" + page.toString()
            thumbnails.get(key) ?: ScanImageProcessor().process(store.original(draft, page), page, 320)
                .also { thumbnails.put(key, it) }
        }
    }
    suspend fun sourcePreview(draft: ScanDraft, page: ScanPage): Bitmap = withContext(Dispatchers.IO) {
        lock.withLock { ScanImageProcessor().decode(store.original(draft, page), 1600) }
    }
    suspend fun processedPreview(draft: ScanDraft, page: ScanPage): Bitmap = withContext(Dispatchers.IO) {
        lock.withLock { ScanImageProcessor().process(store.original(draft, page), page, 1600) }
    }
    fun closeDraft(close: () -> Unit) {
        val current = mutable.value
        if (current.busy || current.draft == null) { close(); return }
        mutable.value = current.copy(busy = true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { lock.withLock { store.save(current.draft) } }
                mutable.value = current
                close()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = current.copy(message = ScannerState.Message.FAILED) }
        }
    }
    fun export(uri: Uri) {
        val s = mutable.value
        val draft = s.draft ?: return
        if (s.busy || draft.pages.isEmpty()) return
        mutable.value = s.copy(busy = true, message = null)
        viewModelScope.launch {
            var temporary: File? = null
            try {
                withContext(Dispatchers.IO) { lock.withLock {
                    store.save(draft)
                    temporary = File.createTempFile("sahl-scan-", ".pdf", getApplication<Application>().cacheDir)
                    ScanPdfWriter(getApplication()).create(draft, temporary!!)
                    val output = getApplication<Application>().contentResolver.openOutputStream(uri, "wt") ?: error("Cannot export")
                    output.use { target -> temporary!!.inputStream().use { input ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            target.write(buffer, 0, count)
                        }
                    } }
                } }
                mutable.value = s.copy(message = ScannerState.Message.SAVED)
            } catch (cancelled: CancellationException) { removePartial(uri); throw cancelled }
            catch (_: Exception) {
                removePartial(uri)
                mutable.value = s.copy(message = ScannerState.Message.FAILED)
            } finally { withContext(Dispatchers.IO + NonCancellable) { temporary?.delete() } }
        }
    }
    private suspend fun removePartial(uri: Uri) = withContext(Dispatchers.IO + NonCancellable) {
        runCatching { DocumentsContract.deleteDocument(getApplication<Application>().contentResolver, uri) }
        Unit
    }
}
