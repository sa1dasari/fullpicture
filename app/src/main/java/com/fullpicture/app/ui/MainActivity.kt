package com.fullpicture.app.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.fullpicture.app.R
import com.fullpicture.app.a11y.AccessibilityUtils
import com.fullpicture.app.bubble.BubbleService
import com.fullpicture.app.capture.ScreenCaptureService
import com.fullpicture.app.settings.Settings

/**
 * Entry-point activity. Walks the user through:
 *  1. Granting SYSTEM_ALERT_WINDOW (overlay) permission.
 *  2. Enabling the FullPicture accessibility service.
 *  3. Pasting in a Claude API key (stored locally).
 *  4. Starting the floating bubble service.
 *
 * MediaProjection consent is requested on-demand the first time the bubble
 * is tapped (see [com.fullpicture.app.capture.ProjectionRequestActivity]).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var apiKeyInput: EditText
    private lateinit var autoTriggerCheck: CheckBox

    // Request MediaProjection consent here, while the activity is in the
    // foreground — pop-ups initiated from BubbleService are blocked by
    // Android's background-activity-launch restrictions.
    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                // Hand the consent token to the capture FGS, then launch the bubble.
                val captureSvc = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, captureSvc)
                ContextCompat.startForegroundService(this, Intent(this, BubbleService::class.java))
                Toast.makeText(this, R.string.bubble_running, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.capture_denied, Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.statusText)
        apiKeyInput = findViewById(R.id.apiKeyInput)
        autoTriggerCheck = findViewById(R.id.autoTriggerCheck)
        val grantBtn = findViewById<Button>(R.id.grantOverlayBtn)
        val grantA11yBtn = findViewById<Button>(R.id.grantA11yBtn)
        val saveKeyBtn = findViewById<Button>(R.id.saveApiKeyBtn)
        val startBtn = findViewById<Button>(R.id.startBubbleBtn)
        val stopBtn = findViewById<Button>(R.id.stopBubbleBtn)

        grantBtn.setOnClickListener {
            startActivity(
                Intent(
                    AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        grantA11yBtn.setOnClickListener {
            // No way to deep-link to a specific service prior to Android 14;
            // we send the user to the Accessibility list and they pick
            // "FullPicture".
            startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        saveKeyBtn.setOnClickListener {
            val key = apiKeyInput.text?.toString().orEmpty().trim()
            Settings.setApiKey(this, key)
            Toast.makeText(
                this,
                if (key.isBlank()) R.string.api_key_cleared else R.string.api_key_saved,
                Toast.LENGTH_SHORT
            ).show()
            refreshStatus()
        }

        autoTriggerCheck.setOnCheckedChangeListener { _, isChecked ->
            Settings.setAutoTriggerEnabled(this, isChecked)
        }

        startBtn.setOnClickListener {
            if (!AndroidSettings.canDrawOverlays(this)) {
                Toast.makeText(this, R.string.status_overlay_missing, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Pop the system "Allow screen capture?" dialog from THIS activity,
            // then onResult() spins up the capture + bubble services.
            val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(pm.createScreenCaptureIntent())
        }

        stopBtn.setOnClickListener {
            stopService(Intent(this, BubbleService::class.java))
            stopService(Intent(this, ScreenCaptureService::class.java))
            refreshStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        // Pre-fill key field (masked input keeps it from being visible).
        if (apiKeyInput.text.isNullOrEmpty()) {
            apiKeyInput.setText(Settings.getApiKey(this))
        }
        autoTriggerCheck.isChecked = Settings.isAutoTriggerEnabled(this)
        refreshStatus()
    }

    private fun refreshStatus() {
        val overlay = AndroidSettings.canDrawOverlays(this)
        val a11y = AccessibilityUtils.isServiceEnabled(this)
        val key = Settings.hasApiKey(this)
        status.text = buildString {
            append(getString(if (overlay) R.string.status_overlay_ok else R.string.status_overlay_missing))
            append('\n')
            append(getString(if (a11y) R.string.status_a11y_ok else R.string.status_a11y_missing))
            append('\n')
            append(getString(if (key) R.string.status_key_ok else R.string.status_key_missing))
        }
    }
}
