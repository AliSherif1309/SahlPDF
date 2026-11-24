package com.sahl.pdf

/** Pure rule for picking the right APK from a release (unit-tested). */
object UpdatePick {
    val knownAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

    fun preferredNames(deviceAbis: List<String>): List<String> =
        (deviceAbis.filter { it in knownAbis }.map { "SahlPDF-$it.apk" } + "SahlPDF.apk").distinct()

    /** Returns the asset name to download, or null when nothing newer fits this device. */
    fun choose(apkCodes: Map<String, Long>, deviceAbis: List<String>, installedCode: Long): String? {
        for (abi in deviceAbis.filter { it in knownAbis }) {
            val prefix = "SahlPDF-$abi"
            val match = apkCodes.keys.firstOrNull { it == "$prefix.apk" || (it.startsWith("$prefix-v") && it.endsWith(".apk")) }
            if (match != null) {
                return if (apkCodes.getValue(match) > installedCode) match else null
            }
        }
        val uni = apkCodes.keys.firstOrNull { it == "SahlPDF.apk" || (it.startsWith("SahlPDF-v") && it.endsWith(".apk")) }
            ?: return null
        return if (apkCodes.getValue(uni) > installedCode) uni else null
    }

    /** "1.55" or "2.0.17" -> [1, 55] / [2, 0, 17]; anything unparsable -> empty. */
    fun version(v: String): List<Int> =
        if (Regex("""\d{1,9}\.\d{1,9}(?:\.\d{1,9})?""").matches(v)) v.split('.').map { it.toInt() } else emptyList()
}
