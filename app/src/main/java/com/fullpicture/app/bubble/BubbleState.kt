package com.fullpicture.app.bubble

/**
 * Visual states the floating bubble can be in.
 */
enum class BubbleState {
    /** Resting dot, awaiting user tap. */
    IDLE,

    /** Briefly hidden right before screen capture, so it's not in the screenshot. */
    HIDDEN_FOR_CAPTURE,

    /** Spinner / pulse while Claude streams its response. */
    THINKING,

    /** Expanded into a panel showing the analysis. */
    PANEL,
}

