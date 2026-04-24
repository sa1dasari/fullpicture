package com.fullpicture.app.claude

import android.graphics.Bitmap
import android.util.Base64
import com.fullpicture.app.a11y.ScreenContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Sends (image + accessibility-derived text + optional audio note) to Claude
 * and parses a structured "missing context" JSON response.
 */
object ClaudeClient {

    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val MODEL = "claude-sonnet-4-5"
    private const val API_VERSION = "2023-06-01"

    // TODO: inject from BuildConfig / EncryptedSharedPreferences.
    private const val API_KEY = ""

    private val SYSTEM_PROMPT = """
        You are FullPicture, an assistant that looks at a social-media post a
        user is currently viewing (image of the screen, the on-screen text we
        scraped via Android Accessibility, and optionally an audio note) and
        explains what crucial context the creator left out.

        ALWAYS respond with a single JSON object, no prose, matching:
        {
          "claim": "<one-sentence summary of the post's main claim, or null>",
          "missing_context": ["<bullet>", ...],
          "opposing_views": ["<bullet>", ...],
          "sources": ["<url or citation>", ...],
          "confidence": "low" | "medium" | "high"
        }

        Be neutral. Prefer primary sources. If the post is benign / non-claim
        content (recipe, meme), return empty arrays and confidence "low".
    """.trimIndent()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    fun analyze(
        screenshot: Bitmap,
        screenContext: ScreenContext?,
        audioNote: String? = null,
    ): MissingContextAnalysis {
        if (API_KEY.isBlank()) return stub(screenContext, audioNote)

        val b64 = encodeToBase64Png(screenshot)
        val userText = buildUserPrompt(screenContext, audioNote)

        val body = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", 800)
            put("system", SYSTEM_PROMPT)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray()
                    .put(JSONObject().apply {
                        put("type", "image")
                        put("source", JSONObject().apply {
                            put("type", "base64")
                            put("media_type", "image/png")
                            put("data", b64)
                        })
                    })
                    .put(JSONObject().apply {
                        put("type", "text")
                        put("text", userText)
                    })
                )
            }))
        }

        val req = Request.Builder()
            .url(ENDPOINT)
            .header("x-api-key", API_KEY)
            .header("anthropic-version", API_VERSION)
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return runCatching {
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) errorAnalysis("Claude error ${resp.code}: $raw")
                else parseResponse(raw)
            }
        }.getOrElse { errorAnalysis("Request failed: ${it.message}") }
    }

    /** Back-compat shim for the original skeleton call site. */
    fun analyzeScreenshot(bitmap: Bitmap): String =
        analyze(bitmap, null, null).renderForPanel()

    // region helpers

    private fun buildUserPrompt(ctx: ScreenContext?, audioNote: String?): String = buildString {
        append("Here is the post the user is looking at right now.\n\n")
        if (ctx != null) {
            append("Accessibility-scraped context:\n")
            append(ctx.summarize()).append('\n')
        }
        if (!audioNote.isNullOrBlank()) {
            append("Audio: ").append(audioNote).append('\n')
        }
        append("\nReturn the JSON object as specified.")
    }

    private fun parseResponse(raw: String): MissingContextAnalysis {
        val arr = JSONObject(raw).optJSONArray("content") ?: return errorAnalysis(raw)
        val text = buildString {
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                if (p.optString("type") == "text") append(p.optString("text"))
            }
        }
        val firstBrace = text.indexOf('{')
        val lastBrace = text.lastIndexOf('}')
        if (firstBrace < 0 || lastBrace <= firstBrace) {
            return MissingContextAnalysis(null, emptyList(), emptyList(), emptyList(), null, text)
        }
        return runCatching {
            val o = JSONObject(text.substring(firstBrace, lastBrace + 1))
            MissingContextAnalysis(
                claim = o.optString("claim").ifBlank { null },
                missingContext = o.optJSONArray("missing_context").toStringList(),
                opposingViews = o.optJSONArray("opposing_views").toStringList(),
                sources = o.optJSONArray("sources").toStringList(),
                confidence = o.optString("confidence").ifBlank { null },
                rawText = text,
            )
        }.getOrElse { MissingContextAnalysis(null, emptyList(), emptyList(), emptyList(), null, text) }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
    }

    private fun errorAnalysis(msg: String) =
        MissingContextAnalysis(null, emptyList(), emptyList(), emptyList(), null, msg)

    private fun stub(ctx: ScreenContext?, audioNote: String?) = MissingContextAnalysis(
        claim = "[stub] No API key configured.",
        missingContext = listOfNotNull(
            "Captured screenshot ✓",
            ctx?.let { "A11y text: ${it.visibleTexts.size} lines, ${it.handles.size} handles, ${it.hashtags.size} hashtags" },
            audioNote?.let { "Audio: $it" },
        ),
        opposingViews = emptyList(),
        sources = emptyList(),
        confidence = "low",
        rawText = "stub response",
    )

    private fun encodeToBase64Png(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    // endregion
}
