package com.fullpicture.app.a11y

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service that:
 *   1. Walks the active window's view tree to extract on-screen text
 *      (captions, creator handle, hashtags, comment snippets).
 *   2. Detects when the user "pauses" scrolling — i.e., no scroll event for
 *      [PAUSE_DEBOUNCE_MS] — and emits an auto-analysis trigger.
 *
 * Snapshots are pushed to [ScreenContextRepository] so the bubble service can
 * consume the latest structured context whenever the user (or auto-trigger)
 * fires an analysis.
 */
class FullPictureAccessibilityService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var pendingPause: Runnable? = null
    private var lastPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenContextRepository.markServiceConnected(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (!SUPPORTED_PACKAGES.contains(pkg)) return
        lastPackage = pkg

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> scheduleScrollPauseTrigger(pkg)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> snapshotScreenText(pkg)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        ScreenContextRepository.markServiceConnected(false)
        super.onDestroy()
    }

    // region pause detection

    private fun scheduleScrollPauseTrigger(pkg: String) {
        pendingPause?.let { main.removeCallbacks(it) }
        val r = Runnable {
            // User stopped scrolling for a beat → take a fresh snapshot and
            // notify any listener (e.g., the bubble service) that wants to
            // auto-analyze.
            snapshotScreenText(pkg)
            ScreenContextRepository.publishAutoTrigger(pkg)
        }
        pendingPause = r
        main.postDelayed(r, PAUSE_DEBOUNCE_MS)
    }

    // endregion

    // region tree walk

    private fun snapshotScreenText(pkg: String) {
        val root: AccessibilityNodeInfo = rootInActiveWindow ?: return
        val texts = mutableListOf<String>()
        val handles = mutableListOf<String>()
        val hashtags = mutableListOf<String>()

        walk(root) { node ->
            val t = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
            if (t.isEmpty()) return@walk
            when {
                t.startsWith("@") && t.length < 40 -> handles += t
                t.startsWith("#") && t.length < 60 -> hashtags += t
                else -> texts += t
            }
        }

        ScreenContextRepository.update(
            ScreenContext(
                packageName = pkg,
                visibleTexts = texts.distinct().take(60),
                handles = handles.distinct().take(10),
                hashtags = hashtags.distinct().take(20),
                capturedAtMs = System.currentTimeMillis()
            )
        )
    }

    private fun walk(node: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        visit(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { walk(it, visit) }
        }
    }

    // endregion

    companion object {
        private const val PAUSE_DEBOUNCE_MS = 700L

        private val SUPPORTED_PACKAGES = setOf(
            "com.instagram.android",
            "com.zhiliaoapp.musically",      // TikTok (global)
            "com.ss.android.ugc.trill",      // TikTok (alt)
            "com.google.android.youtube",
            "com.twitter.android",
            "com.x.android",
        )
    }
}

