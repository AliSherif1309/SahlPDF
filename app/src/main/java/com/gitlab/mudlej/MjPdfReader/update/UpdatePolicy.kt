package com.gitlab.mudlej.MjPdfReader.update

import java.net.URI
import java.util.Locale

/** Pure rules: compare actual APK codes (ABI builds have different codes). */
object UpdatePolicy {
    const val REPOSITORY = "AliSherif1309/SahlPDF"
    const val LATEST_API = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    const val MAX_APK_BYTES = 256L * 1024 * 1024
    fun version(value: String): List<Long> {
        require(Regex("""\d{1,9}\.\d{1,9}\.\d{1,9}""").matches(value))
        return value.split('.').map { it.toLong() }
    }
    fun versionAtLeast(candidate: String, installed: String): Boolean {
        val a = version(candidate); val b = version(installed)
        for (i in a.indices) if (a[i] != b[i]) return a[i] > b[i]
        return true
    }
    fun newer(version: String, code: Long, installedVersion: String, installedCode: Long): Boolean =
        code > installedCode && versionAtLeast(version, installedVersion)

    fun assetUrl(tag: String, name: String): String {
        require(Regex("""build-\d+""").matches(tag))
        require(name in listOf("version.json", "SHA256SUMS", "SahlPDF.apk",
            "SahlPDF-arm64-v8a.apk", "SahlPDF-armeabi-v7a.apk", "SahlPDF-x86.apk", "SahlPDF-x86_64.apk"))
        return "https://github.com/$REPOSITORY/releases/download/$tag/$name"
    }
    fun trustedHttps(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) &&
            uri.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com",
                "objects.githubusercontent.com", "github-releases.githubusercontent.com")
    }.getOrDefault(false)

    fun preferredNames(abis: List<String>): List<String> =
        (abis.filter { it in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }
            .map { "SahlPDF-$it.apk" } + "SahlPDF.apk").distinct()

    fun checksum(text: String, name: String): String {
        val matches = text.lineSequence().mapNotNull { line ->
            Regex("""^([a-fA-F0-9]{64})[ \t]+\*?(.+?)\s*$""").matchEntire(line.trim())
        }.filter { it.groupValues[2] == name }.toList()
        require(matches.size == 1) { "Missing or ambiguous checksum" }
        return matches.single().groupValues[1].lowercase(Locale.ROOT)
    }
}
