package com.gitlab.mudlej.MjPdfReader.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

data class AppUpdate(
    val tag: String,
    val version: String,
    val code: Long,
    val apkName: String,
    val size: Long,
    val sha256: String,
    val notes: String,
) {
    val apkUrl: String get() = UpdatePolicy.assetUrl(tag, apkName)
    val cacheName: String get() = "$tag-$apkName"
}

/** All calls are on Dispatchers.IO. Only public, stable, numbered Sahl PDF releases. */
class UpdateRepository(
    private val context: Context,
    private val textSource: (suspend (String, Int) -> String)? = null,
) {
    private val preferences get() = context.getSharedPreferences("sahl-updates", Context.MODE_PRIVATE)
    private fun installed(): PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    @Suppress("DEPRECATION")
    private fun code(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    fun cached(): AppUpdate? = runCatching {
        decode(JSONObject(preferences.getString("candidate", null) ?: return null))
            .takeIf { update -> installed().let { UpdatePolicy.newer(update.version, update.code, it.versionName.orEmpty(), code(it)) } }
    }.getOrNull()
    fun due(now: Long = System.currentTimeMillis()): Boolean {
        val last = preferences.getLong("last-check", 0)
        return last == 0L || now < last || now - last >= 24 * 60 * 60 * 1000L
    }

    suspend fun check(): AppUpdate? {
        preferences.edit().putLong("last-check", System.currentTimeMillis()).apply()
        val release = JSONObject(text(UpdatePolicy.LATEST_API, 512 * 1024))
        require(!release.optBoolean("draft") && !release.optBoolean("prerelease"))
        val tag = release.getString("tag_name")
        require(Regex("""build-\d+""").matches(tag))
        val assets = release.getJSONArray("assets")
        fun asset(name: String): JSONObject? {
            val found = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("name") == name }
            require(found.size <= 1)
            return found.singleOrNull()?.also {
                require(it.getString("browser_download_url") == UpdatePolicy.assetUrl(tag, name))
            }
        }
        require(asset("version.json") != null)
        val metadata = JSONObject(text(UpdatePolicy.assetUrl(tag, "version.json"), 64 * 1024))
        val version = metadata.getString("sahlpdf")
        val current = installed()
        val currentVersion = current.versionName.orEmpty()
        // Older releases need no per-APK metadata; they can never update this app.
        if (!UpdatePolicy.versionAtLeast(version, currentVersion)) return remember(null)
        val apkMetadata = metadata.optJSONObject("apks") ?: throw IOException("Missing APK metadata")
        val chosen = UpdatePolicy.preferredNames(Build.SUPPORTED_ABIS.toList()).firstNotNullOfOrNull { name ->
            val details = apkMetadata.optJSONObject(name) ?: return@firstNotNullOfOrNull null
            val entry = asset(name) ?: return@firstNotNullOfOrNull null
            val nextCode = details.getLong("code")
            if (!UpdatePolicy.newer(version, nextCode, currentVersion, code(current))) return@firstNotNullOfOrNull null
            val size = entry.getLong("size")
            require(size in 1..UpdatePolicy.MAX_APK_BYTES)
            Triple(name, nextCode, size)
        } ?: return remember(null)
        require(asset("SHA256SUMS") != null)
        val sum = UpdatePolicy.checksum(text(UpdatePolicy.assetUrl(tag, "SHA256SUMS"), 64 * 1024), chosen.first)
        return remember(AppUpdate(tag, version, chosen.second, chosen.first, chosen.third, sum,
            release.optString("body").take(4000)))
    }

    private fun remember(update: AppUpdate?): AppUpdate? {
        preferences.edit().putLong("last-check", System.currentTimeMillis())
            .putString("candidate", update?.let { encode(it).toString() }).apply()
        return update
    }
    private fun encode(update: AppUpdate) = JSONObject().apply {
        put("tag", update.tag); put("version", update.version); put("code", update.code)
        put("name", update.apkName); put("size", update.size); put("sha", update.sha256); put("notes", update.notes)
    }
    private fun decode(json: JSONObject): AppUpdate = AppUpdate(
        json.getString("tag"), json.getString("version"), json.getLong("code"),
        json.getString("name"), json.getLong("size"), json.getString("sha"), json.optString("notes")
    ).also {
        UpdatePolicy.assetUrl(it.tag, it.apkName)
        require(it.size in 1..UpdatePolicy.MAX_APK_BYTES && Regex("[a-f0-9]{64}").matches(it.sha256))
    }

    suspend fun download(update: AppUpdate, progress: (Int) -> Unit): File {
        val dir = File(context.filesDir, "updates").apply { check(mkdirs() || isDirectory) }
        val ready = File(dir, update.cacheName)
        if (ready.exists()) {
            try { verify(ready, update); progress(100); return ready }
            catch (_: Exception) { ready.delete() }
        }
        val partial = File.createTempFile("download-", ".part", dir)
        try {
            partial.outputStream().use { output -> transfer(update.apkUrl, update.size, output) { received ->
                progress((received * 100 / update.size).toInt().coerceIn(0, 100))
            } }
            verify(partial, update)
            check(partial.renameTo(ready))
            dir.listFiles()?.filter { it != ready && it.lastModified() < System.currentTimeMillis() - 24 * 60 * 60 * 1000L }
                ?.forEach { it.delete() }
            return ready
        } finally { partial.delete() }
    }

    @Suppress("DEPRECATION")
    fun verify(file: File, update: AppUpdate) {
        require(file.length() == update.size) { "Incomplete APK" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        require(actual == update.sha256) { "Checksum mismatch" }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IOException("Invalid APK")
        val current = context.packageManager.getPackageInfo(context.packageName, flags)
        require(archive.packageName == context.packageName && archive.versionName == update.version)
        require(code(archive) == update.code && UpdatePolicy.newer(update.version, code(archive), current.versionName.orEmpty(), code(current)))
        fun signers(info: PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return signatures.orEmpty().map {
                MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b.toInt() and 255) }
            }.toSet()
        }
        val trusted = signers(current)
        require(trusted.isNotEmpty() && signers(archive) == trusted) { "Signing key mismatch" }
    }

    private suspend fun text(url: String, limit: Int): String {
        textSource?.let { return it(url, limit) }
        val output = ByteArrayOutputStream()
        transfer(url, limit.toLong(), output)
        return output.toString("UTF-8")
    }

    private suspend fun transfer(initialUrl: String, limit: Long, output: OutputStream, progress: (Long) -> Unit = {}) {
        var next = initialUrl
        repeat(6) {
            currentCoroutineContext().ensureActive()
            require(UpdatePolicy.trustedHttps(next))
            val connection = URI(next).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.setRequestProperty("Accept", if (initialUrl == UpdatePolicy.LATEST_API) "application/vnd.github+json" else "*/*")
            connection.setRequestProperty("User-Agent", "SahlPDF-Updater")
            try {
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: throw IOException("Missing redirect")
                    next = URI(next).resolve(location).toString()
                } else {
                    if (status != 200) throw IOException("Update server returned $status")
                    require((connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L) <= limit)
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= limit)
                            output.write(buffer, 0, count)
                            progress(total)
                        }
                    }
                    return
                }
            } finally { connection.disconnect() }
        }
        throw IOException("Too many redirects")
    }
}
