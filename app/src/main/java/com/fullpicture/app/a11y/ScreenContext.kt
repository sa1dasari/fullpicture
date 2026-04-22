package com.fullpicture.app.a11y

/**
 * Structured snapshot of what's currently on screen, harvested via
 * [FullPictureAccessibilityService]. Far more reliable than OCR.
 */
data class ScreenContext(
    val packageName: String,
    val visibleTexts: List<String>,
    val handles: List<String>,
    val hashtags: List<String>,
    val capturedAtMs: Long,
) {
    fun summarize(): String = buildString {
        append("App: ").append(packageName).append('\n')
        if (handles.isNotEmpty()) append("Handles: ").append(handles.joinToString()).append('\n')
        if (hashtags.isNotEmpty()) append("Hashtags: ").append(hashtags.joinToString()).append('\n')
        if (visibleTexts.isNotEmpty()) {
            append("On-screen text:\n")
            visibleTexts.forEach { append("  • ").append(it).append('\n') }
        }
    }
}

