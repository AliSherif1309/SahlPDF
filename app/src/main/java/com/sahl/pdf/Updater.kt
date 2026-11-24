package com.sahl.pdf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer Sahl PDF. Each release carries version.json with the
 * version code of every APK, so the right ABI build is chosen for this device.
 */
object Updater {
    private const val REPO = "AliSherif1309/SahlPDF"
    private const val PREFS = "sahl_updates"

    data class Release(val version: String, val code: Long, val apkUrl: String, val notes: String)

    private fun tr(c: Context, en: String, ar: String) = if (Ui.isArabic(c)) ar else en

    fun installedCode(c: Context): Long =
        try { c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode } catch (_: Exception) { BuildConfig.VERSION_CODE.toLong() }

    private fun get(url: String): String {
        val con = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 10000
            setRequestProperty("Accept", "application/vnd.github+json")
            instanceFollowRedirects = true
        }
        return con.inputStream.bufferedReader().use { it.readText() }
    }

    /** Blocking; call off the main thread. */
    fun fetchNewer(c: Context): Release? = try {
        val rel = JSONObject(get("https://api.github.com/repos/$REPO/releases/latest"))
        val assets = rel.getJSONArray("assets")
        val urls = HashMap<String, String>()
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            urls[a.getString("name")] = a.getString("browser_download_url")
        }
        val meta = JSONObject(get(urls["version.json"] ?: error("no version.json")))
        val apks = meta.getJSONObject("apks")
        val codes = HashMap<String, Long>()
        for (name in apks.keys()) if (urls.containsKey(name)) codes[name] = apks.getJSONObject(name).getLong("code")
        val pick = UpdatePick.choose(codes, Build.SUPPORTED_ABIS.toList(), installedCode(c))
        if (pick == null) null
        else Release(meta.optString("sahlpdf"), codes.getValue(pick), urls.getValue(pick), meta.optString("notes"))
    } catch (_: Exception) { null }

    /** At most once every 20 hours, for the home-screen banner. */
    fun dueForCheck(c: Context): Boolean {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - p.getLong("last", 0) < 20 * 3600_000L) return false
        p.edit().putLong("last", now).apply()
        return true
    }

    fun download(c: Context, r: Release) {
        try {
            c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.apkUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            android.widget.Toast.makeText(c, r.apkUrl, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    fun availableText(c: Context, r: Release) = tr(c, "New version available: ${r.version}", "إصدار جديد متاح: ${r.version}")
    fun upToDateText(c: Context) = tr(c, "You have the latest version (v${BuildConfig.VERSION_NAME})", "لديك آخر إصدار (v${BuildConfig.VERSION_NAME})")
    fun checkingText(c: Context) = tr(c, "Checking…", "جارٍ التحقق…")
    fun checkText(c: Context) = tr(c, "Check for updates", "التحقق من التحديثات")
    fun updateNowText(c: Context) = tr(c, "Update now", "حدّث الآن")
}
