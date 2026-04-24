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
import android.view.WindowManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Holds the active [MediaProjection] and exposes a one-shot
 * [captureOneFrame] suspend function.
 *
 * Skeleton implementation — production code should reuse the VirtualDisplay
 * across captures and handle rotation / DPI changes more carefully.
 */
object ScreenCaptureManager {

    private var projection: MediaProjection? = null

    fun isReady(): Boolean = projection != null

    /** Exposed so AudioCaptureManager can build an AudioPlaybackCaptureConfig. */
    fun projection(): MediaProjection? = projection

    fun attach(ctx: Context, mp: MediaProjection) {
        tearDown()
        projection = mp
    }

    fun tearDown() {
        projection?.stop()
        projection = null
    }

    suspend fun captureOneFrame(ctx: Context): Bitmap? {
        val mp = projection ?: return null

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val thread = HandlerThread("FullPictureCapture").apply { start() }
        val handler = Handler(thread.looper)

        var virtualDisplay: VirtualDisplay? = null

        return try {
            suspendCancellableCoroutine { cont ->
                virtualDisplay = mp.createVirtualDisplay(
                    "fullpicture-cap",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface, null, handler
                )
                reader.setOnImageAvailableListener({ r ->
                    val image: Image? = r.acquireLatestImage()
                    if (image != null) {
                        val bmp = imageToBitmap(image, width, height)
                        image.close()
                        if (cont.isActive) cont.resume(bmp)
                    }
                }, handler)

                cont.invokeOnCancellation {
                    runCatching { virtualDisplay?.release() }
                    runCatching { reader.close() }
                    thread.quitSafely()
                }
            }
        } finally {
            runCatching { virtualDisplay?.release() }
            runCatching { reader.close() }
            thread.quitSafely()
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

