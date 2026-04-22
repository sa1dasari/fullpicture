package com.fullpicture.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.fullpicture.app.R
import com.fullpicture.app.bubble.BubbleService

/**
 * Entry-point activity. Walks the user through:
 *  1. Granting SYSTEM_ALERT_WINDOW (overlay) permission.
 *  2. Starting the floating bubble service.
 *
 * MediaProjection consent is requested on-demand the first time the bubble
 * is tapped (see [com.fullpicture.app.capture.ProjectionRequestActivity]).
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val status = findViewById<TextView>(R.id.statusText)
        val grantBtn = findViewById<Button>(R.id.grantOverlayBtn)
        val grantA11yBtn = findViewById<Button>(R.id.grantA11yBtn)
        val startBtn = findViewById<Button>(R.id.startBubbleBtn)
        val stopBtn = findViewById<Button>(R.id.stopBubbleBtn)

        grantBtn.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        grantA11yBtn.setOnClickListener {
            // No way to deep-link to a specific service prior to Android 14;
            // we send the user to the Accessibility list and they pick
            // "FullPicture".
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        startBtn.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                status.text = "Grant overlay permission first."
                return@setOnClickListener
            }
            val intent = Intent(this, BubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            status.text = "Bubble running. Tap it on any screen."
        }

        stopBtn.setOnClickListener {
            stopService(Intent(this, BubbleService::class.java))
            status.text = getString(R.string.status_idle)
        }
    }
}

