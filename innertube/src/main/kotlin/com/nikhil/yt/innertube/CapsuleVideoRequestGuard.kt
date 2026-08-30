/*
 * Capsule MUSIC
 *
 * Conservative request guard for the optional VIDEO path.
 *
 * This does not try to impersonate or hide Capsule as an official YouTube
 * application. Its purpose is the opposite: make VIDEO polite and fail closed.
 *
 * GPL-3.0
 */

package com.nikhil.yt.innertube

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max

object CapsuleVideoRequestGuard {
    /*
     * Only metadata/search/player calls use this gate. Actual media streaming
     * is not throttled here.
     *
     * 700 ms prevents rapid track-skipping from turning into a burst of
     * YouTube Music search/player requests while remaining responsive enough
     * for an explicit AUDIO -> VIDEO switch.
     */
    private const val MIN_METADATA_REQUEST_GAP_MS = 700L
    private const val RATE_LIMIT_BACKOFF_MS = 10 * 60 * 1000L

    private val requestMutex = Mutex()

    @Volatile
    private var lastMetadataRequestAtMs = 0L

    @Volatile
    private var blockedUntilMs = 0L

    @Volatile
    private var blockReason: String? = null

    class RequestBlockedException(
        message: String,
        cause: Throwable? = null,
    ) : IllegalStateException(message, cause)

    suspend fun beforeMetadataRequest() {
        requestMutex.withLock {
            val now = System.currentTimeMillis()
            val remaining = blockedUntilMs - now

            if (remaining > 0L) {
                throw RequestBlockedException(
                    "YouTube VIDEO requests temporarily paused after a 403/429/bot response " +
                        "(${max(1L, remaining / 1000L)}s remaining)",
                )
            }

            if (blockedUntilMs != 0L) {
                blockedUntilMs = 0L
                blockReason = null
            }

            val waitMs =
                MIN_METADATA_REQUEST_GAP_MS -
                    (now - lastMetadataRequestAtMs)

            if (waitMs > 0L) {
                delay(waitMs)
            }

            lastMetadataRequestAtMs = System.currentTimeMillis()
        }
    }

    /**
     * Returns true when the failure looks like rate limiting / anti-bot and a
     * VIDEO-only circuit breaker was opened.
     */
    fun noteFailure(throwable: Throwable): Boolean {
        val message =
            generateSequence(throwable as Throwable?) { it?.cause }
                .mapNotNull { it?.message }
                .joinToString(" ")
                .lowercase()

        val blocked =
            " 429 " in " $message " ||
                "http 429" in message ||
                "response code 429" in message ||
                "too many requests" in message ||
                " 403 " in " $message " ||
                "http 403" in message ||
                "response code 403" in message ||
                "not a bot" in message ||
                "bot detection" in message ||
                "confirm you're not a bot" in message ||
                "confirm you’re not a bot" in message

        if (blocked) {
            openRateLimitBackoff(message.ifBlank { "YouTube VIDEO request blocked" })
        }

        return blocked
    }

    fun noteHttpStatus(code: Int): Boolean {
        if (code != 403 && code != 429) return false

        openRateLimitBackoff("HTTP $code")
        return true
    }

    fun isBlocked(): Boolean {
        val until = blockedUntilMs
        if (until <= 0L) return false

        if (until <= System.currentTimeMillis()) {
            blockedUntilMs = 0L
            blockReason = null
            return false
        }

        return true
    }

    fun remainingBackoffMs(): Long =
        (blockedUntilMs - System.currentTimeMillis())
            .coerceAtLeast(0L)

    private fun openRateLimitBackoff(reason: String) {
        blockedUntilMs =
            max(
                blockedUntilMs,
                System.currentTimeMillis() + RATE_LIMIT_BACKOFF_MS,
            )
        blockReason = reason
    }
}
