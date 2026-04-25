package com.fullpicture.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Holds the active [MediaProjection] plus a single long-lived [VirtualDisplay]
 * and [ImageReader] that get reused across captures.
 *
 * Android 14 (API 34) only allows one createVirtualDisplay() call per
 * MediaProjection token, so we must NOT recreate it on every capture — doing
 * so throws SecurityException ("Don't take multiple captures by invoking
 * MediaProjection#createVirtualDisplay multiple times on the same instance").
 */
object ScreenCaptureManager {

    private const val TAG = "ScreenCaptureManager"

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var callbackThread: HandlerThread? = null
    private var callbackHandler: Handler? = null

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var readerThread: HandlerThread? = null
    private var readerHandler: Handler? = null
    private var displayWidth: Int = 0
    private var displayHeight: Int = 0

    fun isReady(): Boolean = projection != null && virtualDisplay != null

    /** Exposed so AudioCaptureManager can build an AudioPlaybackCaptureConfig. */
    fun projection(): MediaProjection? = projection

    fun attach(ctx: Context, mp: MediaProjection) {
        tearDown()

        // Android 14 (API 34) requires a callback to be registered before
        // createVirtualDisplay() / createAudioRecord() can be invoked on the
        // MediaProjection instance. Register one on a dedicated handler thread.
        val cbThread = HandlerThread("FullPictureProjectionCb").apply { start() }
        val cbHandler = Handler(cbThread.looper)
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                // System or user revoked the projection - clean up.
                tearDown()
            }
        }
        mp.registerCallback(cb, cbHandler)
        callbackThread = cbThread
        callbackHandler = cbHandler
        projectionCallback = cb
        projection = mp

        // Create the VirtualDisplay + ImageReader ONCE and reuse for every
        // captureOneFrame() call.
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val rThread = HandlerThread("FullPictureCapture").apply { start() }
        val rHandler = Handler(rThread.looper)
        // maxImages=2 lets the producer keep one in flight while we drain.
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val vd = mp.createVirtualDisplay(
            "fullpicture-cap",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, rHandler
        )
        // NOTE: intentionally do NOT install a drain listener here. The
        // BufferQueue will simply drop frames once maxImages is reached, and
        // the most recent frame stays acquirable via acquireLatestImage().
        // A previous version drained continuously, which caused
        // captureOneFrame() to hang on static screens (no new frame would
        // ever fire the one-shot listener).

        imageReader = reader
        virtualDisplay = vd
        readerThread = rThread
        readerHandler = rHandler
        displayWidth = width
        displayHeight = height
    }

    fun tearDown() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { readerThread?.quitSafely() }
        virtualDisplay = null
        imageReader = null
        readerHandler = null
        readerThread = null

        val mp = projection
        val cb = projectionCallback
        if (mp != null && cb != null) {
            runCatching { mp.unregisterCallback(cb) }
        }
        runCatching { mp?.stop() }
        projection = null
        projectionCallback = null
        callbackHandler = null
        runCatching { callbackThread?.quitSafely() }
        callbackThread = null
    }

    suspend fun captureOneFrame(ctx: Context): Bitmap? {
        val reader = imageReader ?: run {
            Log.w(TAG, "captureOneFrame: imageReader is null (projection not attached?)")
            return null
        }
        val handler = readerHandler ?: return null
        val width = displayWidth
        val height = displayHeight
        if (width == 0 || height == 0) return null

        // Fast path: a frame is already buffered in the BufferQueue.
        runCatching { reader.acquireLatestImage() }.getOrNull()?.let { image ->
            val bmp = runCatching { imageToBitmap(image, width, height) }.getOrNull()
            image.close()
            if (bmp != null) return bmp
        }

        // Slow path: wait (with timeout) for the next frame the producer
        // pushes. The bubble being hidden right before this call usually
        // forces a new frame within a few ms.
        return try {
            suspendCancellableCoroutine { cont ->
                var delivered = false
                val timeoutRunnable = Runnable {
                    if (!delivered && cont.isActive) {
                        delivered = true
                        Log.w(TAG, "captureOneFrame: timed out waiting for frame")
                        runCatching { reader.setOnImageAvailableListener(null, handler) }
                        cont.resume(null)
                    }
                }
                handler.postDelayed(timeoutRunnable, 1500L)

                reader.setOnImageAvailableListener({ r ->
                    if (delivered) {
                        runCatching { r.acquireLatestImage()?.close() }
                        return@setOnImageAvailableListener
                    }
                    val image: Image? = r.acquireLatestImage()
                    if (image != null) {
                        val bmp = runCatching { imageToBitmap(image, width, height) }.getOrNull()
                        image.close()
                        if (bmp != null && cont.isActive) {
                            delivered = true
                            handler.removeCallbacks(timeoutRunnable)
                            runCatching { r.setOnImageAvailableListener(null, handler) }
                            cont.resume(bmp)
                        }
                    }
                }, handler)

                cont.invokeOnCancellation {
                    handler.removeCallbacks(timeoutRunnable)
                    runCatching { reader.setOnImageAvailableListener(null, handler) }
                }
            }
        } finally {
            // Leave the reader with no listener so the BufferQueue just drops
            // surplus frames between captures.
            runCatching { reader.setOnImageAvailableListener(null, handler) }
        }
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val bmp = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        bmp.copyPixelsFromBuffer(buffer)
        return if (rowPadding == 0) bmp
        else Bitmap.createBitmap(bmp, 0, 0, width, height)
    }
}
