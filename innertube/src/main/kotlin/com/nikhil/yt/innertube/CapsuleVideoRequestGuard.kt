/*
 * Capsule MUSIC
 *
 * Conservative request guard for the optional VIDEO path.
 *
 * This does not try to impersonate or hide Capsule as an official YouTube
 * application. Its purpose is the opposite: make VIDEO polite and fail closed.
 *
 * v2 changes:
 *  - API and STREAM traffic are gated separately (stream probes used to bypass
 *    the gate entirely, which was the main source of request bursts).
 *  - A token bucket puts a real ceiling on sustained traffic instead of only
 *    enforcing a minimum gap.
 *  - Failures are classified instead of pattern-matched into one boolean, so a
 *    routine googlevideo 403 no longer kills the whole VIDEO path for 10 min.
 *  - Backoff escalates (10 min -> 30 min -> 2 h -> 6 h) and can be persisted
 *    across process death through [onStateChanged] / [restore].
 *
 * GPL-3.0
 */

package com.nikhil.yt.innertube

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

object CapsuleVideoRequestGuard {

    // ---------------------------------------------------------------------
    // Tuning
    // ---------------------------------------------------------------------

    /**
     * Which endpoint family a request belongs to.
     *
     * API      -> youtubei (search / player / browse). Expensive, closely
     *             watched, and the only surface where a 403 really means
     *             "stop".
     * STREAM   -> googlevideo range probes. Cheap, noisy, and a 403 there is
     *             usually just a bad format or a stale signature.
     */
    enum class Surface { API, STREAM }

    enum class FailureKind {
        /** Nothing suspicious. Retrying another client/format is fine. */
        NONE,

        /** HTTP 429 / "too many requests". Always trips the breaker. */
        RATE_LIMITED,

        /** Anti-bot interstitial or captcha. Always trips the breaker. */
        BOT_CHECK,

        /** HTTP 403. Trips on API, tolerated (counted) on STREAM. */
        FORBIDDEN,

        /** Timeout, IO error, 5xx. Retry is reasonable. */
        TRANSIENT,

        /** Video is gone / private / region-locked / login-only. Do not retry. */
        PERMANENT,
    }

    /** Minimum spacing between two youtubei requests. */
    private const val API_MIN_GAP_MS = 900L
    private const val API_JITTER_MS = 600L

    /** Minimum spacing between two googlevideo range probes. */
    private const val STREAM_MIN_GAP_MS = 150L
    private const val STREAM_JITTER_MS = 120L

    /**
     * Sustained ceiling. The bucket holds [BUCKET_CAPACITY] tokens and refills
     * one token every [REFILL_INTERVAL_MS], i.e. ~180 API requests per hour
     * with room for a short burst when the user actually switches to VIDEO.
     *
     * A stream probe costs less than an API call because it is a 1 KiB range
     * request against a CDN rather than an InnerTube call.
     */
    private const val BUCKET_CAPACITY = 20.0
    private const val REFILL_INTERVAL_MS = 20_000.0
    private const val API_COST = 1.0
    private const val STREAM_COST = 0.34

    /** How long a caller may be parked waiting for a token before we give up. */
    private const val MAX_QUEUE_WAIT_MS = 2_500L

    /** Escalating circuit-breaker steps. */
    private val BACKOFF_STEPS_MS =
        longArrayOf(
            10 * 60 * 1000L,
            30 * 60 * 1000L,
            2 * 60 * 60 * 1000L,
            6 * 60 * 60 * 1000L,
        )

    /** Clean running time after which the escalation level decays back down. */
    private const val ESCALATION_DECAY_MS = 6 * 60 * 60 * 1000L

    /**
     * A single googlevideo 403 is normal. This many in a row, across different
     * formats and clients, is not.
     */
    private const val STREAM_FORBIDDEN_STREAK_LIMIT = 6

    // ---------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------

    /**
     * Serialisable view of the breaker, so the app can persist it (DataStore,
     * Room, SharedPreferences) and survive process death.
     */
    data class Snapshot(
        val blockedUntilMs: Long,
        val escalationLevel: Int,
        val lastTripAtMs: Long,
        val reason: String?,
    )

    /**
     * Called whenever the breaker state changes. Wire this to persistent
     * storage from Application.onCreate:
     *
     *     CapsuleVideoRequestGuard.restore(loadedSnapshot)
     *     CapsuleVideoRequestGuard.onStateChanged = { scope.launch { save(it) } }
     *
     * Must not block: it runs on the caller's dispatcher.
     */
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

    // ---------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------

    fun snapshot(): Snapshot =
        Snapshot(
            blockedUntilMs = blockedUntilMs,
            escalationLevel = escalationLevel,
            lastTripAtMs = lastTripAtMs,
            reason = blockReason,
        )

    /**
     * Restores a previously persisted breaker state. Safe to call once at
     * startup; ignores snapshots whose block window already elapsed.
     */
    fun restore(snapshot: Snapshot?) {
        snapshot ?: return

        val now = System.currentTimeMillis()

        // Guard against a clock jump / restored backup from the future.
        val sane = snapshot.blockedUntilMs <= now + BACKOFF_STEPS_MS.last()

        blockedUntilMs = if (sane) snapshot.blockedUntilMs else 0L
        lastTripAtMs = min(snapshot.lastTripAtMs, now)
        escalationLevel =
            snapshot.escalationLevel.coerceIn(0, BACKOFF_STEPS_MS.size - 1)
        blockReason = snapshot.reason

        decayEscalationIfClean(now)
    }

    // ---------------------------------------------------------------------
    // Gate
    // ---------------------------------------------------------------------

    /** Kept for source compatibility with v1 call sites. */
    suspend fun beforeMetadataRequest() = acquire(Surface.API)

    /** Gate for googlevideo range probes. v1 did not gate these at all. */
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
            if (waitMs > 0L) {
                delay(waitMs)
            }

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

    // ---------------------------------------------------------------------
    // Failure handling
    // ---------------------------------------------------------------------

    /**
     * Classifies a throwable chain or a raw message without changing any state.
     * Exposed so callers can decide whether a playabilityStatus reason is worth
     * retrying on another client.
     */
    fun classify(text: String?): FailureKind {
        val message = " ${text.orEmpty().lowercase()} "
        if (message.isBlank()) return FailureKind.NONE

        val rateLimited =
            " 429 " in message ||
                "http 429" in message ||
                "response code 429" in message ||
                "too many requests" in message ||
                "rate limit" in message ||
                "quota exceeded" in message

        if (rateLimited) return FailureKind.RATE_LIMITED

        val botCheck =
            "not a bot" in message ||
                "bot detection" in message ||
                "unusual traffic" in message ||
                "captcha" in message ||
                "recaptcha" in message ||
                "confirm you're not a bot" in message ||
                "confirm you’re not a bot" in message ||
                "sign in to confirm" in message

        if (botCheck) return FailureKind.BOT_CHECK

        val permanent =
            "video unavailable" in message ||
                "is not available" in message ||
                "no longer available" in message ||
                "private video" in message ||
                "has been removed" in message ||
                "removed by the uploader" in message ||
                "terms of service" in message ||
                "not available in your country" in message ||
                "age" in message && "verif" in message

        if (permanent) return FailureKind.PERMANENT

        val forbidden =
            " 403 " in message ||
                "http 403" in message ||
                "response code 403" in message ||
                "forbidden" in message

        if (forbidden) return FailureKind.FORBIDDEN

        val transient =
            "timeout" in message ||
                "timed out" in message ||
                "connection reset" in message ||
                "unexpected end of stream" in message ||
                "unable to resolve host" in message ||
                " 500 " in message ||
                " 502 " in message ||
                " 503 " in message ||
                " 504 " in message

        if (transient) return FailureKind.TRANSIENT

        return FailureKind.NONE
    }

    /** Classifies without touching state, so callers can decide when to trip. */
    fun classify(throwable: Throwable): FailureKind = classify(flatten(throwable))

    /**
     * Opens the breaker explicitly, after the caller has exhausted its own
     * fallbacks. Use this instead of tripping on the first suspicious response
     * when a retry on another client is still plausible.
     */
    fun noteBlockedAfterAllAttempts(reason: String) {
        trip(reason)
    }

    private fun flatten(throwable: Throwable): String =
        generateSequence(throwable as Throwable?) { it?.cause }
            .take(8)
            .mapNotNull { it?.message }
            .joinToString(" ")

    /**
     * Reports a failure from the youtubei surface.
     *
     * Returns true when the VIDEO circuit breaker was opened, matching the v1
     * contract so existing call sites keep working.
     */
    fun noteFailure(throwable: Throwable): Boolean =
        noteApiFailure(throwable) != FailureKind.NONE &&
            isBlocked()

    fun noteApiFailure(throwable: Throwable): FailureKind {
        val kind = classify(flatten(throwable))

        when (kind) {
            FailureKind.RATE_LIMITED,
            FailureKind.BOT_CHECK,
            FailureKind.FORBIDDEN,
            -> trip(kind.name.lowercase().replace('_', ' '))

            else -> Unit
        }

        return kind
    }

    /**
     * Reports the status code of a googlevideo range probe.
     *
     * 429 trips immediately. 403 only trips once it repeats
     * [STREAM_FORBIDDEN_STREAK_LIMIT] times in a row, because an isolated 403
     * there normally means a stale signature or a format the chosen client is
     * not allowed to read, not that we are being throttled.
     */
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

    /**
     * v1 name. Kept so old call sites compile, but it now only trips for codes
     * that genuinely warrant it on the API surface.
     */
    fun noteHttpStatus(code: Int): Boolean {
        if (code != 403 && code != 429) return false

        trip("http $code")
        return true
    }

    /** Resets the stream failure streak after a fully successful resolve. */
    fun noteSuccess() {
        streamForbiddenStreak = 0
    }

    // ---------------------------------------------------------------------
    // Breaker
    // ---------------------------------------------------------------------

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

        val step = BACKOFF_STEPS_MS[escalationLevel.coerceIn(0, BACKOFF_STEPS_MS.lastIndex)]

        blockedUntilMs = max(blockedUntilMs, now + step)
        blockReason = reason
        lastTripAtMs = now
        escalationLevel = min(escalationLevel + 1, BACKOFF_STEPS_MS.lastIndex)

        // Drain the bucket: whatever we were doing, we were doing too much.
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

    /** Test / debug helper. Never call this to work around a live block. */
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
