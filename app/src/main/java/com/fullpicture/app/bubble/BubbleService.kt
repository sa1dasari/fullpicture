package com.fullpicture.app.bubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
            main().post { if (state == BubbleState.IDLE) onBubbleTap() }
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
            // Launch transparent activity to ask the user for projection consent.
            val i = Intent(this, ProjectionRequestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
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

                // 3. Bubble reappears in "thinking" state.
                setState(BubbleState.THINKING)

                // 4. Pull the latest accessibility snapshot + (optionally) a
                //    short audio clip from the same MediaProjection session.
                val ctx = ScreenContextRepository.current()
                val audioBytes = withContext(Dispatchers.IO) {
                    val mp = ScreenCaptureManager.projection() ?: return@withContext null
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        AudioCaptureManager.captureClip(mp, durationMs = 3_000)
                    else null
                }
                val audioNote = audioBytes?.let { "captured ${it.size} bytes of PCM16 mono @16kHz" }

                // 5. Send everything to Claude and parse structured analysis.
                val analysis = withContext(Dispatchers.IO) {
                    ClaudeClient.analyze(bitmap, ctx, audioNote)
                }

                // 6. Morph into panel showing analysis.
                showPanel(analysis.renderForPanel())
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

    private fun showPanel(text: String) {
        setState(BubbleState.PANEL)
        val panel = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)
        panel.findViewById<TextView>(R.id.panelText).text = text
        panel.findViewById<ImageButton>(R.id.closePanelBtn).setOnClickListener { collapsePanel() }

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
        windowManager.addView(panel, params)
        panelView = panel
    }

    private fun collapsePanel() {
        panelView?.let { runCatching { windowManager.removeView(it) } }
        panelView = null
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

    companion object {
        private const val TAG = "BubbleService"
        private const val CHANNEL_ID = "fullpicture.bubble"
        private const val NOTIF_ID = 1001
    }
}

