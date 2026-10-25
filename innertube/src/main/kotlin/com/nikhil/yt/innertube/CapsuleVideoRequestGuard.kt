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
 *  - paced metadata admission / explicit bot and rate-limit breaker are preserved;
 *  - continuous CDN media delivery has no local finite request quota.
 *
 * GPL-3.0
 */

package com.nikhil.yt.innertube

import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
    private const val MAX_QUEUE_WAIT_MS = 2_500L

    private val BACKOFF_STEPS_MS =
        longArrayOf(
            10 * 60 * 1000L,
            30 * 60 * 1000L,
            2 * 60 * 60 * 1000L,
            6 * 60 * 60 * 1000L,
        )

    private const val ESCALATION_DECAY_MS = 6 * 60 * 60 * 1000L

    data class Snapshot(
        val blockedUntilMs: Long,
        val escalationLevel: Int,
        val lastTripAtMs: Long,
        val reason: String?,
    )

    @Volatile
    var onStateChanged: ((Snapshot) -> Unit)? = null

    private val gate = Mutex()

    private var clock: () -> Long = System::currentTimeMillis

    @Volatile
    private var lastApiRequestAtMs = 0L

    @Volatile
    private var blockedUntilMs = 0L

    @Volatile
    private var escalationLevel = 0

    @Volatile
    private var lastTripAtMs = 0L

    @Volatile
    private var blockReason: String? = null

    @Volatile private var resetGeneration = 0L
    fun resetEpoch(): Long = resetGeneration

    /** Explicit developer action; persists through the same bridge as a trip. */
    @Synchronized
    fun resetCooldown() {
        resetGeneration++
        blockedUntilMs = 0L
        blockReason = null
        escalationLevel = 0
        lastTripAtMs = 0L
        lastApiRequestAtMs = 0L
        publish()
    }


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
        // Old policy incorrectly promoted ordinary signed-link/client 403s to
        // a global ban. Migrate only those reasons; real 429/bot verdicts stay.
        if (snapshot.reason?.lowercase() in setOf("repeated stream 403", "http 403", "forbidden")) {
            blockedUntilMs = 0L
            escalationLevel = 0
            lastTripAtMs = 0L
            blockReason = null
            return
        }

        val now = clock()
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

    /**
     * Only metadata/search is paced. A valid HLS clip continuously requests
     * playlists, audio/video segments and initialization data; those reads must
     * not spend a finite bucket shared with the next clip's extraction.
     * Both surfaces still obey real server bot/rate-limit cooldowns.
     */
    suspend fun acquire(surface: Surface) {
        var previousApiAt = 0L
        val scheduledAt = gate.withLock {
            val now = clock()
            decayEscalationIfClean(now)
            checkServerCooldown(now)
            if (surface == Surface.STREAM) return@withLock now

            previousApiAt = lastApiRequestAtMs
            val gap = API_MIN_GAP_MS + Random.nextLong(API_JITTER_MS + 1)
            val slot = max(now, lastApiRequestAtMs + gap)
            if (slot - now > MAX_QUEUE_WAIT_MS) {
                throw RequestBlockedException(
                    "VIDEO metadata queue is full, retry in ${max(1L, (slot - now) / 1000L)}s",
                )
            }
            lastApiRequestAtMs = slot
            slot
        }
        try {
            val waitMs = scheduledAt - clock()
            if (waitMs > 0L) delay(waitMs)
            checkServerCooldown(clock())
        } catch (cancelled: CancellationException) {
            // An obsolete queued resolve must not reserve the next user's slot.
            if (surface == Surface.API) withContext(NonCancellable) {
                gate.withLock {
                    if (lastApiRequestAtMs == scheduledAt) lastApiRequestAtMs = previousApiAt
                }
            }
            throw cancelled
        }
    }

    @Synchronized
    private fun checkServerCooldown(now: Long) {
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
            generateSequence(throwable) { it.cause }
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

    @Synchronized
    fun noteBlockedAfterAllAttempts(reason: String, epoch: Long = resetGeneration) {
        // A reply from before a manual reset cannot reinstate the old ban.
        if (epoch == resetGeneration) trip(reason)
    }

    private fun flatten(throwable: Throwable): String =
        generateSequence(throwable) { it.cause }
            .take(8)
            .mapNotNull { it.message }
            .joinToString(" ")

    fun noteFailure(throwable: Throwable): Boolean =
        noteApiFailure(throwable) != FailureKind.NONE && isBlocked()

    fun noteApiFailure(throwable: Throwable, epoch: Long = resetGeneration): FailureKind {
        val kind = classify(throwable)

        when (kind) {
            FailureKind.RATE_LIMITED,
            FailureKind.BOT_CHECK,
            -> noteBlockedAfterAllAttempts(kind.name.lowercase().replace('_', ' '), epoch)

            else -> Unit
        }

        return kind
    }

    @Synchronized
    fun noteStreamStatus(code: Int, epoch: Long = resetGeneration): FailureKind {
        if (code in 200..399 || code == 416) {
            return FailureKind.NONE
        }

        if (code == 429) {
            noteBlockedAfterAllAttempts("http 429 (stream)", epoch)
            return FailureKind.RATE_LIMITED
        }

        if (code == 403 || code == 401) {
            // A CDN refusal can mean an expired signature, unsupported route
            // or missing credentials. It is not evidence of a global bot ban.
            // The VIDEO session already invalidates and changes backend.
            return FailureKind.FORBIDDEN
        }

        return FailureKind.TRANSIENT
    }

    fun noteHttpStatus(code: Int): Boolean {
        if (code != 429) return false

        trip("http $code")
        return true
    }

    /**
     * A successfully rendered VIDEO frame is stronger evidence than simply
     * receiving an extractor URL. Forget stale escalation after proven playback,
     * but NEVER lift a server restriction that is still active: an offline/cached
     * frame is not permission to bypass a live bot or rate-limit cooldown.
     */
    @Synchronized fun noteVideoRenderedSuccessfully() {
        val now = clock()
        if (blockedUntilMs > now) return
        if (escalationLevel == 0 && lastTripAtMs == 0L && blockedUntilMs == 0L) return
        escalationLevel = 0
        lastTripAtMs = 0L
        blockedUntilMs = 0L
        blockReason = null
        publish()
    }

    fun noteSuccess() = Unit

    @Synchronized
    fun isBlocked(): Boolean {
        val until = blockedUntilMs
        if (until <= 0L) return false

        if (until <= clock()) {
            blockedUntilMs = 0L
            blockReason = null
            publish()
            return false
        }

        return true
    }

    fun remainingBackoffMs(): Long =
        (blockedUntilMs - clock()).coerceAtLeast(0L)

    fun blockReason(): String? = blockReason

    @Synchronized
    private fun trip(reason: String) {
        val now = clock()
        // Concurrent audio/video segment failures are one incident. Escalate
        // only after the previous cooldown has elapsed and a new attempt fails.
        if (blockedUntilMs > now) return
        decayEscalationIfClean(now)

        val step =
            BACKOFF_STEPS_MS[
                escalationLevel.coerceIn(0, BACKOFF_STEPS_MS.lastIndex)
            ]

        blockedUntilMs = max(blockedUntilMs, now + step)
        blockReason = reason
        lastTripAtMs = now
        escalationLevel = min(escalationLevel + 1, BACKOFF_STEPS_MS.lastIndex)

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

    fun resetForTests(now: () -> Long = System::currentTimeMillis) {
        clock = now
        resetGeneration = 0L
        blockedUntilMs = 0L
        blockReason = null
        escalationLevel = 0
        lastTripAtMs = 0L
        lastApiRequestAtMs = 0L
    }
}
