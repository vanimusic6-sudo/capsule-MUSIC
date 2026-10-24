package com.nikhil.yt.playback.audio.potoken

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Narrow boundary for the Android minter; also permits deterministic lifecycle tests. */
internal interface PoTokenMinter {
    val isDead: Boolean
    val isExpired: Boolean
    suspend fun generatePoToken(identifier: String): String
    fun close()
}

/**
 * One visitor-bound session. Initialization, minting and failure cleanup have one owner.
 * Failed visitors get at most three attempts per process, spaced by 30 s / 120 s.
 * There is no timer or polling; another real request may use the next attempt.
 */
internal class PoTokenSession(
    private val create: suspend () -> PoTokenMinter,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val timeoutMs: Long = 8_000L,
) {
    private data class Prepared(val visitor: String, val minter: PoTokenMinter, val token: String)
    private val lock = Mutex()
    @Volatile private var prepared: Prepared? = null
    @Volatile private var failedVisitor: String? = null
    @Volatile private var failures = 0
    @Volatile private var retryAtMs = 0L
    @Volatile private var unsupported = false

    fun isReady(visitor: String): Boolean = prepared?.let {
        it.visitor == visitor && !it.minter.isDead && !it.minter.isExpired
    } == true

    fun mayPrepare(visitor: String): Boolean = !unsupported && visitor.isNotBlank() && visitor != "null" &&
        (isReady(visitor) || failedVisitor != visitor || (failures < 3 && nowMs() >= retryAtMs))

    suspend fun prewarm(visitor: String): Boolean = access(visitor) { true } == true

    suspend fun obtain(videoId: String, visitor: String): PoTokenResult? = access(visitor) { session ->
        PoTokenResult.fromBotGuard(session.minter.generatePoToken(videoId), session.token)
    }

    private suspend fun <T> access(visitor: String, use: suspend (Prepared) -> T): T? {
        if (visitor.isBlank() || visitor == "null") return null
        return lock.withLock {
            if (unsupported) return@withLock null
            if (failedVisitor != visitor) {
                failedVisitor = visitor
                failures = 0
                retryAtMs = 0L
            }
            if (!isReady(visitor) && (failures >= 3 || nowMs() < retryAtMs)) return@withLock null
            try {
                val result = withTimeoutOrNull(timeoutMs) {
                    if (!isReady(visitor)) {
                        discard()
                        val fresh = create()
                        try {
                            val token = fresh.generatePoToken(visitor)
                            prepared = Prepared(visitor, fresh, token)
                        } catch (failure: Throwable) {
                            withContext(NonCancellable) { fresh.close() }
                            throw failure
                        }
                    }
                    use(requireNotNull(prepared))
                }
                if (result == null) {
                    failed()
                } else {
                    failures = 0
                    retryAtMs = 0L
                }
                result
            } catch (cancelled: CancellationException) {
                // Do not swallow a caller's timeout/cancellation, or clear a successor session.
                withContext(NonCancellable) { discard() }
                throw cancelled
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (failure is BadWebViewException) unsupported = true
                failed()
                null
            }
        }
    }

    private fun failed() {
        discard()
        failures++
        retryAtMs = nowMs() + if (failures == 1) 30_000L else 120_000L
    }

    private fun discard() {
        val previous = prepared
        prepared = null
        previous?.minter?.close()
    }

    suspend fun close() = withContext(NonCancellable) { lock.withLock { discard() } }
}
