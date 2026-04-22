package com.fullpicture.app.bubble

/**
 * Tiny in-process bus the [com.fullpicture.app.capture.ProjectionRequestActivity]
 * uses to deliver MediaProjection consent results back to [BubbleService]
 * without requiring it to be bound.
 */
object ProjectionResultBus {
    private var listener: ((Boolean) -> Unit)? = null

    fun register(l: (Boolean) -> Unit) { listener = l }
    fun unregister() { listener = null }
    fun publish(success: Boolean) { listener?.invoke(success) }
}

