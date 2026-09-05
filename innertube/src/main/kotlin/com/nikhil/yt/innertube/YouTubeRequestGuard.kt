package com.nikhil.yt.innertube

import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

class YouTubeRequestBlockedException(message: String) : IllegalStateException(message)

/** Shared by account/browse requests and AUDIO; anonymous VIDEO keeps a separate instance. */
class YouTubeRequestGuard(
    private val minIntervalMs: Long = 250L,
    private val cooldownMs: Long = 10 * 60 * 1_000L,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private data class Cooldown(val until: Long, val reason: String)
    @Volatile private var cooldown: Cooldown? = null
    private val starts = Mutex()
    private val requests = Semaphore(2)
    private var lastStartedAt: Long? = null

    fun blockedExceptionOrNull(now: Long = nowMs()): YouTubeRequestBlockedException? {
        val state = cooldown ?: return null
        if (state.until <= now) return null
        return YouTubeRequestBlockedException(
            "YouTube requests cooling down: ${state.reason} (${((state.until - now) / 1_000L).coerceAtLeast(1)}s)",
        )
    }

    @Synchronized fun trip(reason: String) {
        val until = nowMs() + cooldownMs
        if (until > (cooldown?.until ?: 0L)) cooldown = Cooldown(until, reason.take(160))
    }

    @Synchronized fun clear() { cooldown = null }

    fun observeFailure(error: Throwable) {
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        // A rejected local attempt must not extend the original upstream cooldown.
        if (causes.any { it is YouTubeRequestBlockedException }) return
        val status = causes.filterIsInstance<ResponseException>().firstOrNull()?.response?.status?.value
        val text = causes.mapNotNull { it.message }.joinToString(" ")
        when (YouTubeFailureClassifier.classify(status, text = text)) {
            YouTubeFailureKind.RATE_LIMITED -> trip("HTTP 429")
            YouTubeFailureKind.BOT_CHECK -> trip("YouTube requested a bot check")
            else -> if (Regex("(^|\\D)429(\\D|$)").containsMatchIn(text)) trip("HTTP 429")
        }
    }

    suspend fun <T> execute(block: suspend () -> T): T = requests.withPermit {
        starts.withLock {
            blockedExceptionOrNull()?.let { throw it }
            val waitMs = lastStartedAt?.let { minIntervalMs - (nowMs() - it) } ?: 0L
            if (waitMs > 0) delay(waitMs)
            // Another in-flight response may have opened the breaker during pacing.
            blockedExceptionOrNull()?.let { throw it }
            lastStartedAt = nowMs()
        }
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            observeFailure(failure)
            throw failure
        }
    }

    companion object {
        val shared = YouTubeRequestGuard()
    }
}
