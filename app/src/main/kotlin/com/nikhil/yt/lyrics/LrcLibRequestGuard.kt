package com.nikhil.yt.lyrics

import com.nikhil.yt.utils.NetworkFailureKind
import com.nikhil.yt.utils.failureChain
import com.nikhil.yt.utils.httpFailureStatus
import com.nikhil.yt.utils.networkFailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.Normalizer
import java.util.Locale

/** One instance is owned by LrcLibLyricsProvider for all helpers and queue preloads. */
internal class LrcLibRequestGuard(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onTransientFailure: (String) -> Unit = {},
) {
    private data class Key(val title: String, val artist: String, val duration: Int)

    // Serialize admission with the request, so queued callers see a newly opened cooldown.
    private val mutex = Mutex()
    private val blockedUntil = LinkedHashMap<Key, Long>()
    private var providerBlockedUntil = 0L

    suspend fun <T : Any> request(
        title: String,
        artist: String,
        duration: Int,
        fetch: suspend () -> T?,
    ): T? = mutex.withLock {
        currentCoroutineContext().ensureActive()
        val now = nowMs()
        blockedUntil.entries.removeAll { it.value <= now }
        val key = Key(normalize(title), normalize(artist), duration)
        if (providerBlockedUntil > now || (blockedUntil[key] ?: 0L) > now) return@withLock null

        val result = try {
            fetch().also { currentCoroutineContext().ensureActive() }
        } catch (failure: Exception) {
            // This includes cancellation returned inside a provider Result or another wrapper.
            failure.failureChain().filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
            currentCoroutineContext().ensureActive()
            val networkFailure = failure.networkFailureKind()
            val overloaded = failure.httpFailureStatus() == 503
            if (!overloaded && networkFailure == null) throw failure

            val failedAt = nowMs()
            block(key, failedAt + TRANSIENT_TTL_MS)
            if (overloaded || networkFailure == NetworkFailureKind.TIMEOUT) {
                providerBlockedUntil = failedAt + PROVIDER_COOLDOWN_MS
            }
            // A suppressed repeat returns above and cannot log another stack trace.
            onTransientFailure(if (overloaded) "HTTP 503; LRCLIB cooling down" else "${networkFailure?.name}; LRCLIB cooling down")
            return@withLock null
        }
        if (result == null) block(key, nowMs() + UNAVAILABLE_TTL_MS)
        result
    }

    private fun block(key: Key, until: Long) {
        blockedUntil[key] = until
        while (blockedUntil.size > MAX_ENTRIES) {
            blockedUntil.remove(blockedUntil.keys.first())
        }
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFC)
            .trim().lowercase(Locale.ROOT).replace(WHITESPACE, " ")

    companion object {
        const val UNAVAILABLE_TTL_MS = 12 * 60 * 60 * 1_000L
        const val TRANSIENT_TTL_MS = 5 * 60 * 1_000L
        const val PROVIDER_COOLDOWN_MS = 90 * 1_000L
        private const val MAX_ENTRIES = 512
        private val WHITESPACE = Regex("[\\s\\p{Z}]+")
    }
}
