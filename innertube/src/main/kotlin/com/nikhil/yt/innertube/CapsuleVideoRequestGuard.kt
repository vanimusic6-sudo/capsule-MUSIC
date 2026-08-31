/*
 * Capsule MUSIC
 *
 * Conservative request guard for the optional VIDEO path.
 *
 * This does not try to impersonate or hide Capsule as an official YouTube
 * application. Its purpose is the opposite: make VIDEO polite and fail closed.
 *
 * v3 hardening:
 *  - HTTP status and playabilityStatus are classified before localized text;
 *  - generic "sign in" is no longer treated as a bot-check;
 *  - Ktor ResponseException status is read directly when available;
 *  - existing token bucket / escalating breaker behaviour is preserved.
 *
 * GPL-3.0
 */

package com.nikhil.yt.innertube

import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

object CapsuleVideoRequestGuard {
    enum class Surface { API, STREAM }

    enum class FailureKind {
        NONE,
        RATE_LIMITED,
        BOT_CHECK,
        FORBIDDEN,
        TRANSIENT,
        PERMANENT,
    }

    private const val API_MIN_GAP_MS = 900L
    private const val API_JITTER_MS = 600L
    private const val STREAM_MIN_GAP_MS = 150L
    private const val STREAM_JITTER_MS = 120L

    private const val BUCKET_CAPACITY = 20.0
    private const val REFILL_INTERVAL_MS = 20_000.0
    private const val API_COST = 1.0
    private const val STREAM_COST = 0.34
    private const val MAX_QUEUE_WAIT_MS = 2_500L

    private val BACKOFF_STEPS_MS =
        longArrayOf(
            10 * 60 * 1000L,
            30 * 60 * 1000L,
            2 * 60 * 60 * 1000L,
            6 * 60 * 60 * 1000L,
        )

    private const val ESCALATION_DECAY_MS = 6 * 60 * 60 * 1000L
    private const val STREAM_FORBIDDEN_STREAK_LIMIT = 6

    data class Snapshot(
        val blockedUntilMs: Long,
        val escalationLevel: Int,
        val lastTripAtMs: Long,
        val reason: String?,
    )

    @Volatile
    var onStateChanged: ((Snapshot) -> Unit)? = null

    private val gate = Mutex()

    @Volatile
    private var tokens: Double = BUCKET_CAPACITY

    @Volatile
    private var lastRefillAtMs: Long = System.currentTimeMillis()

    @Volatile
    private var lastApiRequestAtMs = 0L

    @Volatile
    private var lastStreamRequestAtMs = 0L

    @Volatile
    private var blockedUntilMs = 0L

    @Volatile
    private var escalationLevel = 0

    @Volatile
    private var lastTripAtMs = 0L

    @Volatile
    private var blockReason: String? = null

    @Volatile
    private var streamForbiddenStreak = 0

    class RequestBlockedException(
        message: String,
        cause: Throwable? = null,
    ) : IllegalStateException(message, cause)

    fun snapshot(): Snapshot =
        Snapshot(
            blockedUntilMs = blockedUntilMs,
            escalationLevel = escalationLevel,
            lastTripAtMs = lastTripAtMs,
            reason = blockReason,
        )

    fun restore(snapshot: Snapshot?) {
        snapshot ?: return

        val now = System.currentTimeMillis()
        val sane = snapshot.blockedUntilMs <= now + BACKOFF_STEPS_MS.last()

        blockedUntilMs = if (sane) snapshot.blockedUntilMs else 0L
        lastTripAtMs = min(snapshot.lastTripAtMs, now)
        escalationLevel =
            snapshot.escalationLevel.coerceIn(0, BACKOFF_STEPS_MS.size - 1)
        blockReason = snapshot.reason

        decayEscalationIfClean(now)
    }

    suspend fun beforeMetadataRequest() = acquire(Surface.API)

    suspend fun beforeStreamProbe() = acquire(Surface.STREAM)

    suspend fun acquire(surface: Surface) {
        gate.withLock {
            var now = System.currentTimeMillis()
            decayEscalationIfClean(now)

            val remaining = blockedUntilMs - now
            if (remaining > 0L) {
                throw RequestBlockedException(
                    "YouTube VIDEO requests paused after a ${blockReason ?: "block"} " +
                        "response (${max(1L, remaining / 1000L)}s remaining)",
                )
            }

            if (blockedUntilMs != 0L) {
                blockedUntilMs = 0L
                blockReason = null
                publish()
            }

            refill(now)

            val cost = if (surface == Surface.API) API_COST else STREAM_COST
            if (tokens < cost) {
                val waitForToken = ((cost - tokens) * REFILL_INTERVAL_MS).toLong()
                if (waitForToken > MAX_QUEUE_WAIT_MS) {
                    throw RequestBlockedException(
                        "VIDEO request quota exhausted, retry in " +
                            "${max(1L, waitForToken / 1000L)}s",
                    )
                }
                delay(waitForToken)
                now = System.currentTimeMillis()
                refill(now)
            }

            tokens = max(0.0, tokens - cost)

            val lastAt =
                if (surface == Surface.API) lastApiRequestAtMs else lastStreamRequestAtMs

            val gap =
                if (surface == Surface.API) {
                    API_MIN_GAP_MS + Random.nextLong(API_JITTER_MS + 1)
                } else {
                    STREAM_MIN_GAP_MS + Random.nextLong(STREAM_JITTER_MS + 1)
                }

            val waitMs = gap - (now - lastAt)
            if (waitMs > 0L) delay(waitMs)

            val stamp = System.currentTimeMillis()
            if (surface == Surface.API) {
                lastApiRequestAtMs = stamp
            } else {
                lastStreamRequestAtMs = stamp
            }
        }
    }

    private fun refill(now: Long) {
        val elapsed = now - lastRefillAtMs
        if (elapsed <= 0L) return

        tokens = min(BUCKET_CAPACITY, tokens + elapsed / REFILL_INTERVAL_MS)
        lastRefillAtMs = now
    }

    fun classify(text: String?): FailureKind =
        mapFailureKind(
            YouTubeFailureClassifier.classify(
                httpStatusCode = null,
                playabilityStatus = null,
                text = text,
            ),
        )

    fun classify(
        httpStatusCode: Int? = null,
        playabilityStatus: String? = null,
        text: String? = null,
    ): FailureKind =
        mapFailureKind(
            YouTubeFailureClassifier.classify(
                httpStatusCode = httpStatusCode,
                playabilityStatus = playabilityStatus,
                text = text,
            ),
        )

    fun classify(throwable: Throwable): FailureKind {
        val responseCode =
            generateSequence(throwable as Throwable?) { it?.cause }
                .take(8)
                .filterIsInstance<ResponseException>()
                .firstOrNull()
                ?.response
                ?.status
                ?.value

        return classify(
            httpStatusCode = responseCode,
            text = flatten(throwable),
        )
    }

    fun noteBlockedAfterAllAttempts(reason: String) {
        trip(reason)
    }

    private fun flatten(throwable: Throwable): String =
        generateSequence(throwable as Throwable?) { it?.cause }
            .take(8)
            .mapNotNull { it?.message }
            .joinToString(" ")

    fun noteFailure(throwable: Throwable): Boolean =
        noteApiFailure(throwable) != FailureKind.NONE && isBlocked()

    fun noteApiFailure(throwable: Throwable): FailureKind {
        val kind = classify(throwable)

        when (kind) {
            FailureKind.RATE_LIMITED,
            FailureKind.BOT_CHECK,
            FailureKind.FORBIDDEN,
            -> trip(kind.name.lowercase().replace('_', ' '))

            else -> Unit
        }

        return kind
    }

    fun noteStreamStatus(code: Int): FailureKind {
        if (code in 200..399 || code == 416) {
            streamForbiddenStreak = 0
            return FailureKind.NONE
        }

        if (code == 429) {
            trip("http 429 (stream)")
            return FailureKind.RATE_LIMITED
        }

        if (code == 403 || code == 401) {
            streamForbiddenStreak += 1
            if (streamForbiddenStreak >= STREAM_FORBIDDEN_STREAK_LIMIT) {
                streamForbiddenStreak = 0
                trip("repeated stream 403")
                return FailureKind.FORBIDDEN
            }
            return FailureKind.NONE
        }

        return FailureKind.TRANSIENT
    }

    fun noteHttpStatus(code: Int): Boolean {
        if (code != 403 && code != 429) return false

        trip("http $code")
        return true
    }

    fun noteSuccess() {
        streamForbiddenStreak = 0
    }

    fun isBlocked(): Boolean {
        val until = blockedUntilMs
        if (until <= 0L) return false

        if (until <= System.currentTimeMillis()) {
            blockedUntilMs = 0L
            blockReason = null
            publish()
            return false
        }

        return true
    }

    fun remainingBackoffMs(): Long =
        (blockedUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)

    fun blockReason(): String? = blockReason

    private fun trip(reason: String) {
        val now = System.currentTimeMillis()
        decayEscalationIfClean(now)

        val step =
            BACKOFF_STEPS_MS[
                escalationLevel.coerceIn(0, BACKOFF_STEPS_MS.lastIndex)
            ]

        blockedUntilMs = max(blockedUntilMs, now + step)
        blockReason = reason
        lastTripAtMs = now
        escalationLevel = min(escalationLevel + 1, BACKOFF_STEPS_MS.lastIndex)

        tokens = 0.0
        lastRefillAtMs = now

        publish()
    }

    private fun decayEscalationIfClean(now: Long) {
        if (escalationLevel == 0) return
        if (lastTripAtMs == 0L) return
        if (now - lastTripAtMs < ESCALATION_DECAY_MS) return

        escalationLevel = 0
        lastTripAtMs = 0L
    }

    private fun publish() {
        onStateChanged?.invoke(snapshot())
    }

    private fun mapFailureKind(kind: YouTubeFailureKind): FailureKind =
        when (kind) {
            YouTubeFailureKind.NONE -> FailureKind.NONE
            YouTubeFailureKind.RATE_LIMITED -> FailureKind.RATE_LIMITED
            YouTubeFailureKind.BOT_CHECK -> FailureKind.BOT_CHECK
            YouTubeFailureKind.FORBIDDEN -> FailureKind.FORBIDDEN
            YouTubeFailureKind.TRANSIENT -> FailureKind.TRANSIENT
            YouTubeFailureKind.LOGIN_REQUIRED,
            YouTubeFailureKind.AGE_RESTRICTED,
            YouTubeFailureKind.UNPLAYABLE,
            YouTubeFailureKind.PERMANENT,
            -> FailureKind.PERMANENT
        }

    fun resetForTests() {
        blockedUntilMs = 0L
        blockReason = null
        escalationLevel = 0
        lastTripAtMs = 0L
        streamForbiddenStreak = 0
        tokens = BUCKET_CAPACITY
        lastRefillAtMs = System.currentTimeMillis()
        lastApiRequestAtMs = 0L
        lastStreamRequestAtMs = 0L
    }
}
