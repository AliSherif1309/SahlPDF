package com.gitlab.mudlej.MjPdfReader.scanner

@androidx.annotation.Keep
enum class ScanFilter { NATURAL, GRAY, MONO }
@androidx.annotation.Keep
data class ScanPage(
    val id: String,
    val original: String,
    val corners: List<ScanPoint>,
    val rotation: Int = 0,
    val filter: ScanFilter = ScanFilter.NATURAL,
)
@androidx.annotation.Keep
data class ScanDraft(val id: String, val pages: List<ScanPage> = emptyList(), val selected: String? = null) {
    fun selectedPage(): ScanPage? = pages.firstOrNull { it.id == selected } ?: pages.firstOrNull()
}
data class ScanAnalysis(val corners: List<ScanPoint>, val detected: Boolean, val mayBeBlurred: Boolean)
