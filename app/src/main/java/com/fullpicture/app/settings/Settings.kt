package com.fullpicture.app.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Lightweight key-value store for user-configurable settings (currently just
 * the Claude API key). Backed by [SharedPreferences] for now; the file name
 * is namespaced so we can swap to EncryptedSharedPreferences later without
 * breaking call sites.
 *
 * TODO: migrate to androidx.security EncryptedSharedPreferences once we add
 *       the dependency / are willing to take the Tink hit.
 */
object Settings {

    private const val PREFS = "fullpicture.secure"
    private const val KEY_API_KEY = "claude_api_key"
    private const val KEY_AUTO_TRIGGER = "auto_trigger_enabled"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getApiKey(ctx: Context): String =
        prefs(ctx).getString(KEY_API_KEY, "").orEmpty()

    fun setApiKey(ctx: Context, key: String) {
        prefs(ctx).edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    fun hasApiKey(ctx: Context): Boolean = getApiKey(ctx).isNotBlank()

    fun isAutoTriggerEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AUTO_TRIGGER, true)

    fun setAutoTriggerEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_TRIGGER, enabled).apply()
    }
}

