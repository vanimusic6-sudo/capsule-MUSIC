/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



 package com.nikhil.yt.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.nikhil.yt.utils.reportException
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import coil3.imageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.delay
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future
import kotlin.math.roundToInt

/**
 * Resolves artwork for the media session, the notification and whatever the OEM builds on top of
 * them — HONOR's island, the lock screen, Android Auto.
 *
 * Every failure path used to end in `createBitmap(64, 64)`, which is a *blank, fully transparent*
 * bitmap. That is the worst possible answer: the future succeeds, so nothing downstream can tell
 * that anything went wrong, and the system dutifully draws a transparent square where the artwork
 * should be. Nobody sees artwork, nobody sees a placeholder either, and no log says why.
 *
 * A failed load now fails the future. Media3 then simply publishes no artwork, which lets the
 * system fall back to the app icon — a visible, honest answer — and the failure is reportable.
 */
class CoilBitmapLoader(
    private val context: Context,
    private val scope: CoroutineScope,
) : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        scope.future(Dispatchers.IO) {
            require(data.isNotEmpty()) { "Empty image data" }
            BitmapFactory.decodeByteArray(data, 0, data.size)
                ?: throw IllegalStateException("Could not decode image data")
        }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        scope.future(Dispatchers.IO) {
            val density = context.resources.displayMetrics.density
            val maxIconSizePx = (density * 128f).roundToInt().coerceIn(128, 512)
            var lastFailure: Throwable? = null

            for (attempt in 0 until ATTEMPTS) {
                if (attempt > 0) {
                    // A track change and its first artwork request arrive together, before the image
                    // is in Coil's cache, so the first attempt is often a cold network fetch. The
                    // old budget gave that 750ms in total and then gave up; this backs off far
                    // enough to survive a slow start without ever becoming unbounded.
                    delay(RETRY_DELAY_MS * (1L shl (attempt - 1)))
                }

                val result =
                    runCatching {
                        val request =
                            ImageRequest.Builder(context)
                                .data(uri)
                                .allowHardware(false)
                                .size(maxIconSizePx, maxIconSizePx)
                                .build()
                        context.imageLoader.execute(request)
                    }.getOrElse { throwable ->
                        lastFailure = throwable
                        continue
                    }

                when (result) {
                    is SuccessResult ->
                        runCatching { result.image.toBitmap().fitTo(maxIconSizePx) }
                            .onSuccess { bitmap -> return@future bitmap }
                            .onFailure { throwable -> lastFailure = throwable }

                    is ErrorResult -> lastFailure = result.throwable
                }
            }

            val failure =
                lastFailure ?: IllegalStateException("No artwork was produced for $uri")
            reportException(failure as? Exception ?: IllegalStateException(failure))
            throw failure
        }

    /**
     * Scales down to the icon size, never up.
     *
     * Returns an ARGB_8888 copy because the session hands this bitmap across processes, and a
     * hardware or otherwise non-standard configuration cannot be parcelled.
     */
    private fun Bitmap.fitTo(maxSizePx: Int): Bitmap {
        require(width > 0 && height > 0) { "Decoded artwork has no size" }

        val fitted =
            if (width <= maxSizePx && height <= maxSizePx) {
                this
            } else {
                val scale = minOf(maxSizePx.toFloat() / width, maxSizePx.toFloat() / height)
                Bitmap.createScaledBitmap(
                    this,
                    (width * scale).roundToInt().coerceAtLeast(1),
                    (height * scale).roundToInt().coerceAtLeast(1),
                    true,
                )
            }

        return fitted.copy(Bitmap.Config.ARGB_8888, false)
    }

    private companion object {
        const val ATTEMPTS = 4
        const val RETRY_DELAY_MS = 300L
    }
}
