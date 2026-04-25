package com.fullpicture.app.capture

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Captures a short PCM clip of the audio currently being played by other apps
 * (Instagram reel, TikTok, YouTube short) using Android 10+
 * [AudioPlaybackCaptureConfiguration].
 *
 * Note: only apps whose `allowAudioPlaybackCapture` flag is true will be
 * captured — this includes Instagram/TikTok/YouTube by default for their
 * media usages. System audio / DRM audio is still excluded.
 */
@RequiresApi(Build.VERSION_CODES.Q)
object AudioCaptureManager {

    private const val SAMPLE_RATE = 16_000
    private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    @SuppressLint("MissingPermission")
    suspend fun captureClip(
        context: Context,
        projection: MediaProjection,
        durationMs: Long = 4_000
    ): ByteArray? =
        withContext(Dispatchers.IO) {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(ENCODING)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL)
                .build()

            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
                .coerceAtLeast(4096)

            // Pass an attribution-bearing context so AppOps can register
            // RECORD_AUDIO_OUTPUT against our package/attributionTag instead of
            // logging "Attribution not found ... pkg=...(null)" from system_server.
            val attributedCtx = context.applicationContext
            val builder = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 2)
                .setAudioPlaybackCaptureConfig(config)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setContext(attributedCtx)
            }
            val record = builder.build()

            return@withContext runCatching {
                record.startRecording()
                val out = ByteArrayOutputStream()
                val buf = ByteArray(minBuf)
                val end = System.currentTimeMillis() + durationMs
                while (System.currentTimeMillis() < end) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) out.write(buf, 0, n)
                }
                out.toByteArray()
            }.also {
                runCatching { record.stop() }
                runCatching { record.release() }
            }.getOrNull()
        }
}

