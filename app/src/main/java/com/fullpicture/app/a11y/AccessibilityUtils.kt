package com.fullpicture.app.a11y

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils

/**
 * Helpers for inspecting/deep-linking into Android's Accessibility settings
 * for our specific service.
 */
object AccessibilityUtils {

    fun isServiceEnabled(ctx: Context): Boolean {
        // Prefer the in-process flag set by the service itself when it's bound.
        if (ScreenContextRepository.isServiceConnected()) return true

        // Fallback: inspect the system setting string. This works even if our
        // process was just started and the service hasn't bound yet.
        val expected = ComponentName(ctx, FullPictureAccessibilityService::class.java)
            .flattenToString()
        val enabled = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        for (component in splitter) {
            if (component.equals(expected, ignoreCase = true)) return true
        }
        return false
    }
}

