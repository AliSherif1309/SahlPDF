package com.gitlab.mudlej.MjPdfReader.update

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    @Test fun comparesSemanticVersionsAndRequiresAnAndroidUpgrade() {
        assertTrue(UpdatePolicy.newer("1.10.0", 3040, "1.9.9", 3039))
        assertTrue(UpdatePolicy.newer("1.5.0", 3040, "1.5.0", 3039))
        assertFalse(UpdatePolicy.newer("1.4.9", 9999, "1.5.0", 3039))
        assertFalse(UpdatePolicy.newer("1.6.0", 1040, "1.5.0", 3039))
        assertFalse(UpdatePolicy.newer("1.6.0", 3039, "1.5.0", 3039))
    }
    @Test fun rejectsMalformedVersions() {
        listOf("1.2", "v1.2.3", "1.2.3-beta", "1.2.3.4", "-1.2.3", "999999999999.0.0").forEach {
            assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.version(it) }
        }
    }
    @Test fun abiPreferenceFallsBackToUniversalWithoutDuplicates() {
        assertEquals(listOf("SahlPDF-arm64-v8a.apk", "SahlPDF-armeabi-v7a.apk", "SahlPDF.apk"),
            UpdatePolicy.preferredNames(listOf("arm64-v8a", "armeabi-v7a", "arm64-v8a")))
        assertEquals(listOf("SahlPDF-x86_64.apk", "SahlPDF-x86.apk", "SahlPDF.apk"),
            UpdatePolicy.preferredNames(listOf("x86_64", "x86")))
        assertEquals(listOf("SahlPDF.apk"), UpdatePolicy.preferredNames(listOf("unknown")))
    }
    @Test fun onlyImmutableReleaseAssetsAreAllowed() {
        assertEquals("https://github.com/AliSherif1309/SahlPDF/releases/download/build-40/SahlPDF.apk",
            UpdatePolicy.assetUrl("build-40", "SahlPDF.apk"))
        listOf("latest", "../build-40", "build-40/evil").forEach {
            assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.assetUrl(it, "SahlPDF.apk") }
        }
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.assetUrl("build-40", "../other.apk") }
    }
    @Test fun redirectsCannotUseHttpCredentialsOrAnUntrustedHost() {
        assertTrue(UpdatePolicy.trustedHttps("https://release-assets.githubusercontent.com/example?token=x"))
        listOf("http://github.com/test", "https://github.com.evil.test/file", "file:///tmp/app.apk",
            "https://user@github.com/file", "https://github.com:444/file", "https://evil.test/github.com").forEach {
            assertFalse(it, UpdatePolicy.trustedHttps(it))
        }
    }
    @Test fun checksumMatchesExactFilenameAndRejectsDuplicates() {
        val sum = "a".repeat(64)
        assertEquals(sum, UpdatePolicy.checksum("$sum  SahlPDF.apk\nbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb *SahlPDF-arm64-v8a.apk\n", "SahlPDF.apk"))
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.checksum("$sum  SahlPDF.apk.bak", "SahlPDF.apk") }
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.checksum("$sum SahlPDF.apk\n$sum SahlPDF.apk", "SahlPDF.apk") }
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.checksum("bad SahlPDF.apk", "SahlPDF.apk") }
    }
}
