package com.fullpicture.app.capture

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import com.fullpicture.app.bubble.ProjectionResultBus

/**
 * Transparent activity used solely to request MediaProjection consent.
 * The result is forwarded to [ScreenCaptureManager] / [ScreenCaptureService]
 * and published on [ProjectionResultBus] so the bubble can resume its flow.
 */
class ProjectionRequestActivity : Activity() {

    private lateinit var pm: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(pm.createScreenCaptureIntent(), REQ)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            // Hand the consent token to the foreground capture service.
            val svc = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc)
            } else {
                startService(svc)
            }
            ProjectionResultBus.publish(true)
        } else {
            ProjectionResultBus.publish(false)
        }
        finish()
    }

    companion object {
        private const val REQ = 4242
    }
}

