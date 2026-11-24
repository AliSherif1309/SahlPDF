package com.sahl.pdf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** One document in the recent-files library. */
data class Book(
    val key: String,
    val uri: String,
    val name: String,
    val size: Long,
    val pages: Int,
    val page: Int,
    val opened: Long,
)

/** Recent files and reading positions, kept in private SharedPreferences. */
object Library {
    private const val PREFS = "sahl_pdf_library"
    private const val MAX = 80

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(c: Context): List<Book> {
        val raw = prefs(c).getString("books", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Book(
                    o.getString("key"), o.getString("uri"), o.optString("name"),
                    o.optLong("size"), o.optInt("pages"), o.optInt("page"), o.optLong("opened"),
                )
            }.sortedByDescending { it.opened }
        } catch (_: Exception) { emptyList() }
    }

    private fun save(c: Context, books: List<Book>) {
        val arr = JSONArray()
        books.sortedByDescending { it.opened }.take(MAX).forEach { b ->
            arr.put(JSONObject().apply {
                put("key", b.key); put("uri", b.uri); put("name", b.name); put("size", b.size)
                put("pages", b.pages); put("page", b.page); put("opened", b.opened)
            })
        }
        prefs(c).edit().putString("books", arr.toString()).apply()
    }

    fun get(c: Context, key: String) = all(c).firstOrNull { it.key == key }

    fun upsert(c: Context, book: Book) {
        val rest = all(c).filter { it.key != book.key }
        save(c, rest + book)
    }

    fun setPage(c: Context, key: String, page: Int, pages: Int) {
        val b = get(c, key) ?: return
        upsert(c, b.copy(page = page, pages = pages))
    }

    fun remove(c: Context, key: String) {
        save(c, all(c).filter { it.key != key })
        thumb(c, key).delete()
    }

    fun thumb(c: Context, key: String): File {
        val dir = File(c.filesDir, "thumbs").apply { mkdirs() }
        return File(dir, sha1(key) + ".jpg")
    }

    /** Stable identity for a document: name + size when known (same file via different apps), else the uri. */
    fun keyFor(uri: Uri, name: String, size: Long) = if (size > 0 && name.isNotBlank()) "$name|$size" else uri.toString()

    fun describe(c: Context, uri: Uri): Pair<String, Long> {
        var name = ""
        var size = -1L
        try {
            if (uri.scheme == "file") {
                val f = File(uri.path ?: "")
                name = f.name; size = f.length()
            } else {
                c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cur ->
                    if (cur.moveToFirst()) {
                        if (!cur.isNull(0)) name = cur.getString(0)
                        if (!cur.isNull(1)) size = cur.getLong(1)
                    }
                }
            }
        } catch (_: Exception) { }
        if (name.isBlank()) name = uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"
        return name to size
    }

    fun keepAccess(c: Context, uri: Uri, flags: Int) {
        if (flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0) return
        try { c.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
    }

    // ---- bookmarks ---------------------------------------------------------------------

    data class BookmarkEntry(val page: Int, val label: String, val added: Long)

    private fun bookmarkPrefs(c: Context) = c.getSharedPreferences("sahl_pdf_bookmarks", Context.MODE_PRIVATE)

    fun bookmarks(c: Context, docKey: String): List<BookmarkEntry> {
        val raw = bookmarkPrefs(c).getString(sha1(docKey), null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                BookmarkEntry(o.getInt("page"), o.optString("label"), o.optLong("added"))
            }.sortedBy { it.page }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveBookmarks(c: Context, docKey: String, list: List<BookmarkEntry>) {
        val arr = JSONArray()
        list.sortedBy { it.page }.forEach { b ->
            arr.put(JSONObject().apply {
                put("page", b.page); put("label", b.label); put("added", b.added)
            })
        }
        bookmarkPrefs(c).edit().putString(sha1(docKey), arr.toString()).apply()
    }

    fun isBookmarked(c: Context, docKey: String, page: Int): Boolean =
        bookmarks(c, docKey).any { it.page == page }

    fun toggleBookmark(c: Context, docKey: String, page: Int, label: String): Boolean {
        val current = bookmarks(c, docKey)
        return if (current.any { it.page == page }) {
            saveBookmarks(c, docKey, current.filter { it.page != page })
            false
        } else {
            saveBookmarks(c, docKey, current + BookmarkEntry(page, label, System.currentTimeMillis()))
            true
        }
    }

    fun removeBookmark(c: Context, docKey: String, page: Int) {
        saveBookmarks(c, docKey, bookmarks(c, docKey).filter { it.page != page })
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
