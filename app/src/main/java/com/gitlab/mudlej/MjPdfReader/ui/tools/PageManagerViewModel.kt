package com.gitlab.mudlej.MjPdfReader.ui.tools

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PageManagerState(
    val source: File? = null,
    val plan: PagePlan? = null,
    val busy: Boolean = false,
    val canUndo: Boolean = false,
    val dirty: Boolean = false,
    val message: Message? = null,
) {
    enum class Message { LOADED, SAVED, FAILED }
}

class PageManagerViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(PageManagerState())
    val state = mutable.asStateFlow()
    private val engine = PageManagerEngine(application)
    private val history = ArrayDeque<PagePlan>()
    private var sourceUri: Uri? = null
    private val files = java.util.Collections.synchronizedSet(mutableSetOf<File>())
    private val thumbnailLock = Mutex()
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(uri: Uri) {
        if (mutable.value.busy) return
        val previous = mutable.value
        mutable.value = previous.copy(busy = true, message = null)
        viewModelScope.launch {
            var file: File? = null
            try {
                val count = withContext(Dispatchers.IO) {
                    file = temporary("sahl-pages-source-")
                    engine.stage(uri, file!!)
                }
                sourceUri = uri
                history.clear()
                cache.evictAll()
                mutable.value = PageManagerState(source = file, plan = PagePlan.initial(count),
                    message = PageManagerState.Message.LOADED)
                previous.source?.let { old -> withContext(Dispatchers.IO) { old.delete(); files.remove(old) } }
            } catch (cancelled: CancellationException) {
                file?.delete()
                throw cancelled
            } catch (_: Exception) {
                file?.delete()
                mutable.value = previous.copy(busy = false, message = PageManagerState.Message.FAILED)
            }
        }
    }

    fun select(id: Long) = selection { it.toggle(id) }
    fun selectAll() = selection { it.selectAll() }
    fun selectNone() = selection { it.clearSelection() }
    private fun selection(change: (PagePlan) -> PagePlan) {
        val s = mutable.value
        if (!s.busy) s.plan?.let { mutable.value = s.copy(plan = change(it)) }
    }
    fun rotate() = edit { it.rotate() }
    fun move(delta: Int) = edit { it.move(delta) }
    fun duplicate() = edit { it.duplicate() }
    fun delete() = edit { it.delete() }
    private fun edit(change: (PagePlan) -> PagePlan) {
        val s = mutable.value
        val plan = s.plan ?: return
        if (s.busy || plan.selected.isEmpty()) return
        try {
            val next = change(plan)
            if (next.pages == plan.pages) return
            if (history.size == 20) history.removeFirst()
            history.addLast(plan)
            mutable.value = s.copy(plan = next, canUndo = true, dirty = true, message = null)
        } catch (_: IllegalArgumentException) {
            mutable.value = s.copy(message = PageManagerState.Message.FAILED)
        }
    }
    fun undo() {
        val s = mutable.value
        if (!s.busy && history.isNotEmpty()) {
            mutable.value = s.copy(plan = history.removeLast(), canUndo = history.isNotEmpty(),
                dirty = true, message = null)
        }
    }

    suspend fun thumbnail(source: File, page: Int, rotation: Int): Bitmap = withContext(Dispatchers.IO) {
        thumbnailLock.withLock {
            currentCoroutineContext().ensureActive()
            val key = source.name + ":" + page + ":" + rotation
            cache.get(key) ?: run {
                val original = engine.thumbnail(source, page)
                val bitmap = if (rotation == 0) original else {
                    try {
                        val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
                        Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
                    } finally { original.recycle() }
                }
                cache.put(key, bitmap)
                bitmap
            }
        }
    }

    fun export(uri: Uri) {
        val s = mutable.value
        val source = s.source ?: return
        val plan = s.plan ?: return
        if (s.busy || uri == sourceUri) return
        mutable.value = s.copy(busy = true, message = null)
        viewModelScope.launch {
            var result: File? = null
            var targetOpened = false
            try {
                withContext(Dispatchers.IO) {
                    result = temporary("sahl-pages-result-")
                    engine.create(source, plan, result!!)
                    currentCoroutineContext().ensureActive()
                    val resolver = getApplication<Application>().contentResolver
                    val stream = resolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot save")
                    targetOpened = true
                    stream.use { output ->
                        result!!.inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                }
                mutable.value = s.copy(dirty = false, message = PageManagerState.Message.SAVED)
            } catch (cancelled: CancellationException) {
                if (targetOpened) deleteOutput(uri)
                throw cancelled
            } catch (_: Exception) {
                // CreateDocument supplies a new output; never remove or write the input.
                deleteOutput(uri)
                mutable.value = s.copy(busy = false, message = PageManagerState.Message.FAILED)
            } finally {
                result?.let { it.delete(); files.remove(it) }
            }
        }
    }
    private fun deleteOutput(uri: Uri) {
        if (uri != sourceUri) runCatching {
            DocumentsContract.deleteDocument(getApplication<Application>().contentResolver, uri)
        }
    }
    private fun temporary(prefix: String): File =
        File.createTempFile(prefix, ".pdf", getApplication<Application>().cacheDir).also { files.add(it) }

    override fun onCleared() {
        synchronized(files) { files.forEach { it.delete() }; files.clear() }
        cache.evictAll()
    }
}
