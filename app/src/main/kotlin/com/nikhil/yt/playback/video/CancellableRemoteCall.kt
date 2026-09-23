package com.nikhil.yt.playback.video

import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor

/** Cancellation returns immediately and sends a separate IPC command to stop the remote HTTP call. */
internal suspend fun <T> cancellableRemoteCall(
    executor: Executor,
    cancellationExecutor: Executor,
    call: () -> T,
    cancel: () -> Unit,
): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation {
        cancellationExecutor.execute { runCatching(cancel) }
    }
    executor.execute {
        if (continuation.isActive) {
            continuation.resumeWith(runCatching(call))
        }
    }
}
