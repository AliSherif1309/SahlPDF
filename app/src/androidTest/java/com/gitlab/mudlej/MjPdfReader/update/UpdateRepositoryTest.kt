package com.gitlab.mudlej.MjPdfReader.update

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateRepositoryTest {
    private lateinit var context: Context
    private lateinit var directory: File
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        directory = File(context.filesDir, "updates").apply { mkdirs() }
        context.getSharedPreferences("sahl-updates", 0).edit().clear().commit()
    }
    @After fun cleanup() {
        directory.listFiles()?.filter { it.name.startsWith("fixture-") }?.forEach { it.delete() }
        context.getSharedPreferences("sahl-updates", 0).edit().clear().commit()
    }
    @Suppress("DEPRECATION")
    private fun currentCode(): Long = context.packageManager.getPackageInfo(context.packageName, 0).let {
        if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
    }
    private fun currentVersion() = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    private fun release(tag: String = "build-999") = JSONObject().apply {
        put("tag_name", tag); put("draft", false); put("prerelease", false); put("body", "A new release")
        put("assets", JSONArray().apply {
            listOf("version.json", "SHA256SUMS", "SahlPDF.apk").forEach { name ->
                put(JSONObject().put("name", name).put("size", 123)
                    .put("browser_download_url", UpdatePolicy.assetUrl(tag, name)))
            }
        })
    }
    private fun metadata(version: String = "9.0.0", code: Long = currentCode() + 1) =
        JSONObject().put("sahlpdf", version).put("apks",
            JSONObject().put("SahlPDF.apk", JSONObject().put("code", code)))

    @Test fun discoveryCachesCompatibleReleaseAndSuppressesRepeatedAutomaticChecks() = runBlocking {
        val calls = mutableListOf<String>()
        val repository = UpdateRepository(context) { url, _ ->
            calls.add(url)
            when {
                url == UpdatePolicy.LATEST_API -> release().toString()
                url.endsWith("/version.json") -> metadata().toString()
                url.endsWith("/SHA256SUMS") -> "a".repeat(64) + "  SahlPDF.apk\n"
                else -> error("Unexpected URL")
            }
        }
        assertTrue(repository.due())
        val update = repository.check()!!
        assertEquals(currentCode() + 1, update.code)
        assertEquals("SahlPDF.apk", update.apkName)
        assertEquals(3, calls.size)
        assertEquals(update, repository.cached())
        assertFalse(repository.due())
    }
    @Test fun rejectsWrongRepositoryAssetUrlsAndPrereleases() = runBlocking {
        val bad = release().apply {
            getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://evil.test/version.json")
        }
        for (json in listOf(bad, release().put("prerelease", true))) {
            try {
                UpdateRepository(context) { _, _ -> json.toString() }.check()
                fail("Expected release rejection")
            } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun anOlderReleaseOrNonIncreasingApkCodeIsNotAnUpdate() = runBlocking {
        for (meta in listOf(metadata("0.0.1", currentCode() + 10000), metadata(currentVersion(), currentCode()))) {
            val repository = UpdateRepository(context) { url, _ ->
                if (url == UpdatePolicy.LATEST_API) release().toString() else meta.toString()
            }
            assertNull(repository.check())
            assertNull(repository.cached())
        }
    }
    @Test fun installerIntentUsesNarrowFileProviderAndReadGrant() {
        val file = File(directory, "fixture-update.apk").apply { writeText("synthetic apk bytes") }
        val intent = UpdateInstaller.intent(context, file)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(UpdateInstaller.APK_TYPE, intent.type)
        assertEquals("content", intent.data!!.scheme)
        assertEquals(context.packageName + ".fileprovider", intent.data!!.authority)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        context.contentResolver.openInputStream(intent.data!!)!!.use {
            assertEquals("synthetic apk bytes", it.bufferedReader().readText())
        }
        val outside = File(context.cacheDir, "fixture-outside.apk").apply { writeText("not shared") }
        try {
            assertThrows(IllegalArgumentException::class.java) { UpdateInstaller.intent(context, outside) }
        } finally { outside.delete() }
    }
    @Test fun corruptDownloadAndExistingApkCannotBeInstalledAsAnUpgrade() {
        val file = File(directory, "fixture-invalid.apk").apply { writeText("not an apk") }
        val candidate = AppUpdate("build-999", "9.0.0", currentCode() + 1,
            "SahlPDF.apk", file.length(), "a".repeat(64), "")
        assertThrows(IllegalArgumentException::class.java) { UpdateRepository(context).verify(file, candidate) }
        val installed = File(context.applicationInfo.sourceDir)
        val digest = MessageDigest.getInstance("SHA-256")
        installed.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        val existing = AppUpdate("build-999", currentVersion(), currentCode(), "SahlPDF.apk", installed.length(), hash, "")
        assertThrows(IllegalArgumentException::class.java) { UpdateRepository(context).verify(installed, existing) }
    }
}
