package com.fullpicture.app.bubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.fullpicture.app.R
import com.fullpicture.app.a11y.ScreenContextRepository
import com.fullpicture.app.capture.AudioCaptureManager
import com.fullpicture.app.capture.ProjectionRequestActivity
import com.fullpicture.app.capture.ScreenCaptureManager
import com.fullpicture.app.claude.ClaudeClient
import com.fullpicture.app.claude.MissingContextAnalysis
import com.fullpicture.app.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that owns the floating bubble + panel windows and
 * orchestrates the tap → capture → analyze → display flow.
 */
class BubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: View
    private var panelView: View? = null

    private lateinit var bubbleParams: WindowManager.LayoutParams

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var inFlight: Job? = null

    private var state: BubbleState = BubbleState.IDLE

    // ---- result cache -------------------------------------------------------
    // Keyed by (perceptual hash of screenshot, a11y summary).
    // Lets repeated taps on the same content skip the Claude round-trip.
    private var cachedHash: Long = 0L
    private var cachedA11yKey: String = ""
    private var cachedAnalysis: MissingContextAnalysis? = null
    // -------------------------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        addBubble()
        ProjectionResultBus.register(this::onProjectionResult)
        // Auto-trigger: when the accessibility service detects the user paused
        // scrolling on a supported feed, kick off an analysis automatically.
        ScreenContextRepository.setAutoTriggerListener {
            main().post {
                if (Settings.isAutoTriggerEnabled(this) && state == BubbleState.IDLE) onBubbleTap()
            }
        }
    }

    private fun main() = android.os.Handler(mainLooper)

    override fun onDestroy() {
        ProjectionResultBus.unregister()
        ScreenContextRepository.setAutoTriggerListener(null)
        scope.cancel()
        runCatching { panelView?.let { windowManager.removeView(it) } }
        runCatching { windowManager.removeView(bubbleView) }
        ScreenCaptureManager.tearDown()
        super.onDestroy()
    }

    // region overlay setup

    private fun addBubble() {
        bubbleView = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null)

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 400
        }

        attachBubbleTouchHandler()
        windowManager.addView(bubbleView, bubbleParams)
    }

    private fun attachBubbleTouchHandler() {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        val touchSlop = 16

        bubbleView.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt()
                    val dy = (ev.rawY - downY).toInt()
                    if (!dragging && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        bubbleParams.x = startX + dx
                        bubbleParams.y = startY + dy
                        windowManager.updateViewLayout(bubbleView, bubbleParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) onBubbleTap()
                    true
                }
                else -> false
            }
        }
    }

    // endregion

    // region tap flow

    private fun onBubbleTap() {
        if (state != BubbleState.IDLE) return
        animateTapFeedback()
        ensureProjectionThenCapture()
    }

    private fun animateTapFeedback() {
        bubbleView.animate()
            .scaleX(0.8f).scaleY(0.8f).setDuration(80)
            .withEndAction {
                bubbleView.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            .start()
    }

    private fun ensureProjectionThenCapture() {
        if (ScreenCaptureManager.isReady()) {
            captureAndAnalyze()
        } else {
            // We can't pop the system MediaProjection consent dialog from a
            // background service on Android 10+ (BAL is silently blocked), so
            // tell the user to re-arm it from MainActivity.
            android.widget.Toast.makeText(
                this,
                R.string.need_capture_consent,
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun onProjectionResult(success: Boolean) {
        if (success) captureAndAnalyze()
    }

    private fun captureAndAnalyze() {
        inFlight?.cancel()
        inFlight = scope.launch {
            try {
                // 1. Hide bubble so it doesn't appear in screenshot.
                setState(BubbleState.HIDDEN_FOR_CAPTURE)
                delay(120) // let the window manager actually remove it from the frame

                // 2. Grab a single frame.
                val bitmap: Bitmap = withContext(Dispatchers.IO) {
                    ScreenCaptureManager.captureOneFrame(this@BubbleService)
                } ?: run {
                    setState(BubbleState.IDLE)
                    return@launch
                }

                // 3. Pull the latest accessibility snapshot.
                val ctx = ScreenContextRepository.current()
                val a11yKey = ctx?.summarize().orEmpty()

                // 4. Check the perceptual-hash cache.
                //    If the screen looks identical to the last capture
                //    (dHash Hamming distance ≤ 10 AND same a11y text),
                //    skip Claude and show the previous result immediately.
                val newHash = withContext(Dispatchers.Default) { dHash(bitmap) }
                val cached = cachedAnalysis
                if (cached != null
                    && hammingDistance(newHash, cachedHash) <= 10
                    && a11yKey == cachedA11yKey
                ) {
                    Log.d(TAG, "cache hit (hamming=${hammingDistance(newHash, cachedHash)}), reusing result")
                    showPanel(cached)
                    return@launch
                }

                // 5. Bubble reappears in "thinking" state while we call Claude.
                setState(BubbleState.THINKING)

                // 6. Optionally capture a short audio clip.
                val audioBytes = withContext(Dispatchers.IO) {
                    val mp = ScreenCaptureManager.projection() ?: return@withContext null
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        AudioCaptureManager.captureClip(this@BubbleService, mp, durationMs = 3_000)
                    else null
                }
                val audioNote = audioBytes?.let { "captured ${it.size} bytes of PCM16 mono @16kHz" }

                // 7. Send everything to Claude and parse structured analysis.
                val analysis = withContext(Dispatchers.IO) {
                    ClaudeClient.analyze(this@BubbleService, bitmap, ctx, audioNote)
                }

                // 8. Store result in cache.
                cachedHash = newHash
                cachedA11yKey = a11yKey
                cachedAnalysis = analysis

                // 9. Morph into panel showing analysis.
                showPanel(analysis)
            } catch (t: Throwable) {
                Log.e(TAG, "tap flow failed", t)
                setState(BubbleState.IDLE)
            }
        }
    }

    // endregion

    // region state / views

    private fun setState(newState: BubbleState) {
        state = newState
        val dot = bubbleView.findViewById<ImageView>(R.id.bubbleDot)
        val spinner = bubbleView.findViewById<ProgressBar>(R.id.bubbleSpinner)
        when (newState) {
            BubbleState.IDLE -> {
                bubbleView.visibility = View.VISIBLE
                dot.setBackgroundResource(R.drawable.bubble_idle)
                spinner.visibility = View.GONE
            }
            BubbleState.HIDDEN_FOR_CAPTURE -> {
                bubbleView.visibility = View.INVISIBLE
            }
            BubbleState.THINKING -> {
                bubbleView.visibility = View.VISIBLE
                dot.setBackgroundResource(R.drawable.bubble_thinking)
                spinner.visibility = View.VISIBLE
            }
            BubbleState.PANEL -> {
                bubbleView.visibility = View.INVISIBLE
            }
        }
    }

    private fun showPanel(analysis: MissingContextAnalysis) {
        setState(BubbleState.PANEL)
        val panel = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)
        val text = analysis.renderForPanel()
        panel.findViewById<TextView>(R.id.panelText).text = text
        panel.findViewById<ImageButton>(R.id.closePanelBtn).setOnClickListener { collapsePanel() }
        panel.findViewById<ImageButton>(R.id.copyPanelBtn).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("FullPicture", text))
            android.widget.Toast.makeText(this, R.string.copied, android.widget.Toast.LENGTH_SHORT).show()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleParams.x
            y = bubbleParams.y
        }
        attachPanelDragHandler(panel, params)
        windowManager.addView(panel, params)
        panelView = panel
    }

    private fun attachPanelDragHandler(panel: View, params: WindowManager.LayoutParams) {
        // The whole panel header (everything except the buttons) acts as a drag bar.
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        panel.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = params.x; startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (ev.rawX - downX).toInt()
                    params.y = startY + (ev.rawY - downY).toInt()
                    runCatching { windowManager.updateViewLayout(panel, params) }
                    true
                }
                else -> false
            }
        }
    }

    private fun collapsePanel() {
        panelView?.let { runCatching { windowManager.removeView(it) } }
        panelView = null
        // Keep the cache intact — if the user taps again on the same content
        // right after closing the panel they'll get the instant cached result.
        // The cache is only invalidated when the screenshot changes.
        setState(BubbleState.IDLE)
    }

    // endregion

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun startForegroundWithNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_bubble),
                NotificationManager.IMPORTANCE_MIN
            )
            nm.createNotificationChannel(ch)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_bubble_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notification)
    }

    // region perceptual hash

    /**
     * Difference hash (dHash): scale bitmap to 9×8 greyscale, then for each
     * of the 8 rows compare the 8 adjacent column pairs → 64 bits packed into
     * a Long. Fast, allocation-light, and robust to minor rendering jitter.
     */
    private fun dHash(src: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(src, 9, 8, true)
        var hash = 0L
        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val left  = small.getPixel(col,     row)
                val right = small.getPixel(col + 1, row)
                val luma: (Int) -> Int = { px ->
                    // fast integer luma: (2*R + 5*G + B) / 8
                    (Color.red(px) * 2 + Color.green(px) * 5 + Color.blue(px)) ushr 3
                }
                if (luma(left) > luma(right)) hash = hash or (1L shl (row * 8 + col))
            }
        }
        if (small !== src) small.recycle()
        return hash
    }

    /** Number of differing bits between two 64-bit hashes (population count of XOR). */
    private fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    // endregion

    companion object {
        private const val TAG = "BubbleService"
        private const val CHANNEL_ID = "fullpicture.bubble"
        private const val NOTIF_ID = 1001
    }
}

