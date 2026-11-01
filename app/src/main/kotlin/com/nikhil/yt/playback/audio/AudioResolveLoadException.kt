@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.playback.audio

import androidx.media3.common.PlaybackException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException
import java.net.SocketTimeoutException

/** The synchronous Media3 source boundary must throw IO, never PlaybackException. */
internal class AudioResolveLoadException(cause: Throwable) :
    IOException("Audio stream resolution failed", cause)

internal fun audioResolveLoadFailure(failure: Throwable): IOException = when (failure) {
    is TimeoutCancellationException -> SocketTimeoutException("Audio stream resolution timed out").apply {
        initCause(failure)
    }
    is CancellationException -> cancelledAudioLoad(failure)
    is IOException -> failure
    // Preserve deliberate playback-safety errors in the cause chain for the
    // service's bot/rate-limit/auth guards; Loader must not call them unexpected.
    is PlaybackException -> AudioResolveLoadException(failure)
    else -> AudioResolveLoadException(failure)
}
