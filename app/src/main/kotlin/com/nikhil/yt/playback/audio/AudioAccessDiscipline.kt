/* Capsule MUSIC — a single monotonic access-stop policy for AUDIO. GPL-3.0. */
package com.nikhil.yt.playback.audio

import java.io.IOException

internal enum class AudioAccessStopReason { TRACK_REFUSED, SERVER_COOLDOWN, ACCESS_DENIED }

internal class AudioAccessStopException(val reason: AudioAccessStopReason, val remainingMs: Long = 0L) :
    IOException("AUDIO access stopped ($reason, ${remainingMs / 1000L}s)")

internal fun Throwable.audioAccessStopOrNull(): AudioAccessStopException? =
    generateSequence(this as Throwable?) { it.cause }.take(12)
        .filterIsInstance<AudioAccessStopException>().firstOrNull()

/**
 * A 403 is a server verdict, not a transport timeout. Stop the item; repeated
 * wire refusals also pause NEW online audio work for this process. No probes,
 * no identity changes, no URL mutation and no timers that send requests.
 * Cached bytes remain usable. A user tap or network/account change cannot
 * erase the short refusal window. This is deliberately not an IP-ban detector.
 */
internal class AudioAccessDiscipline(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val trackQuietMs: Long = 300_000L,
    private val burstWindowMs: Long = 60_000L,
    private val globalQuietMs: Long = 120_000L,
    private val burstLimit: Int = 3,
) {
    init { require(trackQuietMs > 0 && burstWindowMs > 0 && globalQuietMs > 0 && burstLimit > 0) }
    private val tracks = LinkedHashMap<String, Long>(16, .75f, true)
    private val refusals = ArrayDeque<Long>()
    private var globalUntil = 0L

    @Synchronized fun recordTrack(id: String) {
        val now = nowMs()
        if ((tracks[id] ?: Long.MIN_VALUE) > now) return
        tracks[id] = now + trackQuietMs
        while (tracks.size > 256) tracks.remove(tracks.keys.first())
    }

    /** Exactly once per actual HTTP response, never per Media3 callback. */
    @Synchronized fun recordWireRefusal() {
        val now = nowMs()
        while (refusals.isNotEmpty() && now - refusals.first() >= burstWindowMs) refusals.removeFirst()
        if (now < globalUntil) return
        refusals.addLast(now)
        if (refusals.size >= burstLimit) {
            globalUntil = now + globalQuietMs
            refusals.clear()
        }
    }

    @Synchronized fun recordChallenge() {
        globalUntil = maxOf(globalUntil, nowMs() + globalQuietMs)
        refusals.clear()
    }

    /** RFC Retry-After supports delta seconds and an HTTP date. Keep the
     * existing ten-minute minimum; never shorten an explicit longer delay. */
    @Synchronized fun recordRateLimit(retryAfter: String?, wallMs: Long = System.currentTimeMillis()) {
        val delayMs = retryAfter?.trim()?.let { header ->
            header.toLongOrNull()?.coerceIn(0L, Long.MAX_VALUE / 1000L)?.times(1000L)
                ?: runCatching {
                    java.time.ZonedDateTime.parse(header, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli().let { (it - wallMs).coerceAtLeast(0L) }
                }.getOrNull()
        } ?: 0L
        val now = nowMs()
        val duration = maxOf(600_000L, delayMs)
        val until = if (now > Long.MAX_VALUE - duration) Long.MAX_VALUE else now + duration
        globalUntil = maxOf(globalUntil, until)
        refusals.clear()
    }

    @Synchronized fun globalRemainingMs(): Long = (globalUntil - nowMs()).coerceAtLeast(0L)

    @Synchronized fun stopFor(id: String? = null): AudioAccessStopException? {
        val now = nowMs()
        val global = (globalUntil - now).coerceAtLeast(0L)
        if (global > 0L) return AudioAccessStopException(AudioAccessStopReason.SERVER_COOLDOWN, global)
        if (id == null) return null
        val until = tracks[id] ?: return null
        if (until <= now) { tracks.remove(id); return null }
        return AudioAccessStopException(AudioAccessStopReason.TRACK_REFUSED, until - now)
    }
}

internal object AudioAccessGate {
    val discipline = AudioAccessDiscipline()
    fun check(id: String? = null) { discipline.stopFor(id)?.let { throw it } }
}

internal fun audioAccessStopFrom(error: Throwable): AudioAccessStopException? = error.audioAccessStopOrNull()
