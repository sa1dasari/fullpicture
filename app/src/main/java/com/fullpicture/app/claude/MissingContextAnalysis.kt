package com.fullpicture.app.claude

/**
 * Structured "missing context" analysis returned from the LLM.
 * Mirrors the JSON schema we ask Claude/Gemini to emit.
 */
data class MissingContextAnalysis(
    val claim: String?,
    val missingContext: List<String>,
    val opposingViews: List<String>,
    val sources: List<String>,
    val confidence: String?,    // "low" | "medium" | "high"
    val rawText: String,        // fallback / debug
) {
    fun renderForPanel(): String = buildString {
        if (!claim.isNullOrBlank()) {
            append("Claim:\n").append(claim).append("\n\n")
        }
        if (missingContext.isNotEmpty()) {
            append("What's missing:\n")
            missingContext.forEach { append("• ").append(it).append('\n') }
            append('\n')
        }
        if (opposingViews.isNotEmpty()) {
            append("Other perspectives:\n")
            opposingViews.forEach { append("• ").append(it).append('\n') }
            append('\n')
        }
        if (sources.isNotEmpty()) {
            append("Sources:\n")
            sources.forEach { append("• ").append(it).append('\n') }
        }
        if (isEmpty()) append(rawText)
        if (!confidence.isNullOrBlank()) append("\nConfidence: ").append(confidence)
    }
}

