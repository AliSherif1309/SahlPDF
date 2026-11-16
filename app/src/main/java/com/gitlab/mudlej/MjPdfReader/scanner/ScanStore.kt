package com.gitlab.mudlej.MjPdfReader.scanner

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.google.gson.Gson
import java.io.File
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class ScanStore(private val context: Context) {
    companion object {
        const val MAX_PAGES = 50
        const val MAX_IMAGE_BYTES = 32L * 1024 * 1024
        const val MAX_DRAFT_BYTES = 512L * 1024 * 1024
        fun validId(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    }
    private val gson = Gson()
    private val preferences get() = context.getSharedPreferences("sahl-scanner", Context.MODE_PRIVATE)
    fun directory(id: String): File {
        require(validId(id))
        return File(File(context.filesDir, "scans"), id).apply { check(mkdirs() || isDirectory) }
    }
    fun load(): ScanDraft {
        val id = preferences.getString("active", null) ?: return fresh()
        require(validId(id))
        val draft = AtomicFile(File(directory(id), "draft.json")).openRead().use {
            require(it.available() <= 512 * 1024)
            gson.fromJson(it.bufferedReader(Charsets.UTF_8), ScanDraft::class.java)
        }
        validate(draft)
        require(draft.id == id)
        return draft
    }
    fun fresh(discardPrevious: Boolean = false): ScanDraft {
        val previous = preferences.getString("active", null)
        val draft = ScanDraft(UUID.randomUUID().toString())
        save(draft)
        check(preferences.edit().putString("active", draft.id).commit())
        if (discardPrevious && previous != null && validId(previous) && previous != draft.id) delete(ScanDraft(previous))
        return draft
    }
    fun save(draft: ScanDraft) {
        validate(draft)
        val atomic = AtomicFile(File(directory(draft.id), "draft.json"))
        val stream = atomic.startWrite()
        try {
            stream.write(gson.toJson(draft).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
    }
    private fun validate(draft: ScanDraft) {
        require(validId(draft.id) && draft.pages.size <= MAX_PAGES)
        require(draft.pages.map { it.id }.distinct().size == draft.pages.size)
        draft.pages.forEach {
            require(validId(it.id) && ScanGeometry.valid(it.corners) && it.rotation in listOf(0, 90, 180, 270))
            require(it.filter in ScanFilter.entries)
            val file = original(draft, it)
            require(file.isFile && file.length() in 1..MAX_IMAGE_BYTES)
        }
    }
    fun original(draft: ScanDraft, page: ScanPage): File {
        require(Regex("""(?:capture|import)-[a-f0-9-]{36}\.(?:jpg|img)""").matches(page.original))
        val folder = directory(draft.id).canonicalFile
        return File(folder, page.original).canonicalFile.also { require(it.parentFile == folder) }
    }
    fun captureFile(draft: ScanDraft, replace: Boolean = false): File {
        require(draft.pages.size < MAX_PAGES || replace)
        require(bytes(draft) < MAX_DRAFT_BYTES - MAX_IMAGE_BYTES)
        return File(directory(draft.id), "capture-" + UUID.randomUUID() + ".jpg")
    }
    suspend fun import(draft: ScanDraft, uri: Uri): File {
        require(draft.pages.size < MAX_PAGES)
        val budget = minOf(MAX_IMAGE_BYTES, MAX_DRAFT_BYTES - bytes(draft))
        require(budget > 0)
        val file = File(directory(draft.id), "import-" + UUID.randomUUID() + ".img")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open image")
            input.use { source ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var size = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = source.read(buffer)
                        if (n < 0) break
                        size += n
                        require(size <= budget)
                        output.write(buffer, 0, n)
                    }
                    require(size > 0)
                }
            }
            return file
        } catch (failure: Throwable) { file.delete(); throw failure }
    }
    fun bytes(draft: ScanDraft): Long = directory(draft.id).listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0
    fun delete(draft: ScanDraft) {
        val target = directory(draft.id).canonicalFile
        val root = File(context.filesDir, "scans").canonicalFile
        require(target.parentFile == root && target != root)
        target.deleteRecursively()
    }
}
