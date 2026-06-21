package com.daig0rian.mirakurun.tvinput

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.view.View

// Transparent overlay view displayed on top of the TIF video surface via onCreateOverlayView().
// Call showCaptions() from the main thread; it auto-clears after durationMs milliseconds.
internal class SubtitleOverlayView(ctx: Context) : View(ctx) {

    private var captionImages: Array<CaptionImage> = emptyArray()
    // Video logical size reported by ExoPlayer (used to scale images to view size)
    private var videoWidth = 1920
    private var videoHeight = 1080

    private val clearRunnable = Runnable { clearCaptions() }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setVideoSize(w: Int, h: Int) {
        if (w > 0 && h > 0) {
            videoWidth = w
            videoHeight = h
        }
    }

    fun showCaptions(images: Array<CaptionImage>) {
        removeCallbacks(clearRunnable)
        captionImages = images
        invalidate()
        if (images.isNotEmpty()) {
            val durationMs = images[0].durationMs.coerceIn(500, 10_000)
            postDelayed(clearRunnable, durationMs)
        }
    }

    fun clearCaptions() {
        removeCallbacks(clearRunnable)
        captionImages = emptyArray()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val images = captionImages
        if (images.isEmpty()) return

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return

        // Scale libaribcaption's rendered coordinates (based on video frame) to view size.
        val scaleX = viewW / videoWidth.toFloat()
        val scaleY = viewH / videoHeight.toFloat()

        for (img in images) {
            if (img.bitmap.isRecycled) continue
            canvas.save()
            canvas.translate(img.x * scaleX, img.y * scaleY)
            canvas.scale(scaleX, scaleY)
            canvas.drawBitmap(img.bitmap, 0f, 0f, null)
            canvas.restore()
        }
    }
}
