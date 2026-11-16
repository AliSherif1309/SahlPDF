package com.gitlab.mudlej.MjPdfReader.ui.tools

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PdfToolsState(
    val tool: PdfTool = PdfTool.MERGE,
    val inputs: List<Uri> = emptyList(),
    val pages: String = "",
    val busy: Boolean = false,
    val output: File? = null,
    val message: Message? = null,
) {
    enum class Message { CREATED, SAVED, FAILED }
}

class PdfToolsViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(PdfToolsState())
    val state = mutable.asStateFlow()
    fun select(tool: PdfTool) {
        if (!mutable.value.busy && mutable.value.tool != tool) {
            mutable.value.output?.delete()
            mutable.value = PdfToolsState(tool = tool)
        }
    }
    fun add(uris: List<Uri>) {
        val s = mutable.value
        if (s.busy) return
        val inputs = if (s.tool == PdfTool.PAGES) uris.take(1) else (s.inputs + uris).distinct().take(50)
        invalidate(s.copy(inputs = inputs))
    }
    fun remove(index: Int) {
        val s = mutable.value
        if (!s.busy) invalidate(s.copy(inputs = s.inputs.filterIndexed { i, _ -> i != index }))
    }
    fun move(index: Int, delta: Int) {
        val s = mutable.value
        if (s.busy || index + delta !in s.inputs.indices) return
        val list = s.inputs.toMutableList()
        java.util.Collections.swap(list, index, index + delta)
        invalidate(s.copy(inputs = list))
    }
    fun pages(value: String) {
        if (!mutable.value.busy && value != mutable.value.pages) invalidate(mutable.value.copy(pages = value))
    }
    private fun invalidate(next: PdfToolsState) {
        mutable.value.output?.delete()
        mutable.value = next.copy(output = null, message = null)
    }
    fun create() {
        val s = mutable.value
        if (s.busy || s.inputs.isEmpty()) return
        mutable.value = s.copy(busy = true, message = null)
        viewModelScope.launch {
            var result: File? = null
            try {
                withContext(Dispatchers.IO) {
                    result = File.createTempFile("sahl-tools-", ".pdf", getApplication<Application>().cacheDir)
                    PdfToolsEngine(getApplication()).create(s.tool, s.inputs, s.pages, result!!)
                }
                s.output?.delete()
                mutable.value = s.copy(output = result, message = PdfToolsState.Message.CREATED)
            } catch (cancelled: CancellationException) {
                result?.delete()
                throw cancelled
            } catch (_: Exception) {
                result?.delete()
                mutable.value = s.copy(message = PdfToolsState.Message.FAILED)
            }
        }
    }
    fun export(uri: Uri) {
        val s = mutable.value
        val source = s.output ?: return
        if (s.busy || uri in s.inputs) return
        mutable.value = s.copy(busy = true, message = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val output = resolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot save")
                    output.use { target -> source.inputStream().use { it.copyTo(target) } }
                }
                mutable.value = s.copy(message = PdfToolsState.Message.SAVED)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Only the user-selected new output can be removed; source PDFs are read-only.
                if (uri !in s.inputs) runCatching { DocumentsContract.deleteDocument(getApplication<Application>().contentResolver, uri) }
                mutable.value = s.copy(message = PdfToolsState.Message.FAILED)
            }
        }
    }
    override fun onCleared() {
        mutable.value.output?.delete()
    }
}
