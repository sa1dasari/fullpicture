package com.fullpicture.app.a11y

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Tiny thread-safe holder shared between the accessibility service (producer)
 * and the bubble/capture services (consumers). Keeps things decoupled without
 * pulling in a DI framework for a skeleton.
 */
object ScreenContextRepository {

    private val latest = AtomicReference<ScreenContext?>(null)
    private val connected = AtomicBoolean(false)
    private var autoTriggerListener: ((String) -> Unit)? = null

    fun update(ctx: ScreenContext) { latest.set(ctx) }
    fun current(): ScreenContext? = latest.get()

    fun markServiceConnected(c: Boolean) { connected.set(c) }
    fun isServiceConnected(): Boolean = connected.get()

    /** Bubble service registers here to react to auto-pause triggers. */
    fun setAutoTriggerListener(l: ((String) -> Unit)?) { autoTriggerListener = l }
    fun publishAutoTrigger(pkg: String) { autoTriggerListener?.invoke(pkg) }
}

