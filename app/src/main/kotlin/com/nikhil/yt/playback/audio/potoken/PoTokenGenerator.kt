package com.nikhil.yt.playback.audio.potoken

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber

/**
 * Reuses one BotGuard session per visitorData value. The session is bounded:
 * if Android's WebView renderer is missing, dead or slow, token generation
 * returns null and the playback selector can use a non-token profile.
 *
 * Capsule prewarms the visitor-bound part of this session during application
 * startup. State is shared by all instances so startup and InnerTubeX playback
 * can never accidentally create separate BotGuard/WebView sessions.
 */
class PoTokenGenerator(context: Context) {
    private val applicationContext = context.applicationContext
    private val webViewSupported by lazy { runCatching { CookieManager.getInstance() }.isSuccess }

    /**
     * Prepare only the reusable visitor-bound BotGuard session. This is safe to
     * call concurrently with playback: [lock] makes startup and the first
     * resolve share the same initialization instead of creating two WebViews.
     */
    suspend fun prewarm(visitorData: String): Boolean {
        val normalizedVisitorData = visitorData.trim()
        if (normalizedVisitorData.isBlank() || !webViewSupported || brokenWebView) return false

        return try {
            withTimeout(OVERALL_TIMEOUT_MS) {
                prepareSession(
                    visitorData = normalizedVisitorData,
                    forceRecreate = false,
                )
            }
            Timber.tag(TAG).i("Web PoToken session prewarmed")
            true
        } catch (timeout: TimeoutCancellationException) {
            Timber.tag(TAG).w("Web PoToken prewarm timed out; playback may use a non-token client")
            clear()
            false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (badWebView: BadWebViewException) {
            Timber.tag(TAG).w(badWebView, "System WebView cannot run BotGuard")
            brokenWebView = true
            clear()
            false
        } catch (error: Exception) {
            Timber.tag(TAG).w(error, "Web PoToken prewarm failed; playback will retry on demand")
            clear()
            false
        }
    }

    suspend fun getWebClientPoToken(
        videoId: String,
        visitorData: String,
    ): PoTokenResult? {
        if (!webViewSupported || brokenWebView) return null

        return try {
            withTimeout(OVERALL_TIMEOUT_MS) {
                obtain(videoId, visitorData, forceRecreate = false)
            }
        } catch (timeout: TimeoutCancellationException) {
            Timber.tag(TAG).w("Web PoToken timed out; continuing with non-token clients")
            clear()
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (badWebView: BadWebViewException) {
            Timber.tag(TAG).w(badWebView, "System WebView cannot run BotGuard")
            brokenWebView = true
            clear()
            null
        } catch (error: Exception) {
            Timber.tag(TAG).w(error, "Web PoToken failed; continuing with non-token clients")
            clear()
            null
        }
    }

    suspend fun close() {
        clear()
    }

    private suspend fun clear() {
        lock.withLock {
            val old = generator
            generator = null
            streamingToken = null
            sessionId = null
            if (old != null) {
                withContext(Dispatchers.Main) { old.close() }
            }
        }
    }

    private suspend fun prepareSession(
        visitorData: String,
        forceRecreate: Boolean,
    ): PreparedSession =
        lock.withLock {
            val old = generator
            val recreateReason =
                when {
                    forceRecreate -> "forced_after_generation_failure"
                    old == null -> "missing_session"
                    old.isDead -> "dead_webview"
                    old.isExpired -> "expired_session"
                    sessionId != visitorData -> "visitor_changed"
                    streamingToken == null -> "missing_streaming_token"
                    else -> null
                }
            val shouldRecreate = recreateReason != null

            if (shouldRecreate) {
                Timber.tag(TAG).i("Recreating Web PoToken session reason=%s", recreateReason)
                if (old != null) withContext(Dispatchers.Main) { old.close() }
                generator = null
                streamingToken = null
                sessionId = null

                val fresh = PoTokenWebView.create(applicationContext)
                val freshStreamingToken = fresh.generatePoToken(visitorData)
                generator = fresh
                streamingToken = freshStreamingToken
                sessionId = visitorData
            }

            PreparedSession(
                generator = requireNotNull(generator),
                streamingToken = requireNotNull(streamingToken),
                recreated = shouldRecreate,
            )
        }

    private suspend fun obtain(
        videoId: String,
        visitorData: String,
        forceRecreate: Boolean,
    ): PoTokenResult {
        val prepared =
            prepareSession(
                visitorData = visitorData,
                forceRecreate = forceRecreate,
            )

        val playerToken =
            try {
                prepared.generator.generatePoToken(videoId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (prepared.recreated) throw error
                return obtain(videoId, visitorData, forceRecreate = true)
            }

        return PoTokenResult(
            playerRequestPoToken = prepared.streamingToken,
            streamingDataPoToken = playerToken,
        )
    }

    private data class PreparedSession(
        val generator: PoTokenWebView,
        val streamingToken: String,
        val recreated: Boolean,
    )

    private companion object {
        const val TAG = "CapsulePoToken"
        // One global session is intentional: the application prewarmer and the
        // InnerTubeX TokenProvider must reuse the exact same WebView/minter.
        val lock = Mutex()

        @Volatile
        var brokenWebView = false

        var sessionId: String? = null
        var streamingToken: String? = null
        var generator: PoTokenWebView? = null

        // Cold WebView + BotGuard commonly takes a few seconds. Do not let a
        // damaged renderer stall AUDIO when a token-free profile is available.
        const val OVERALL_TIMEOUT_MS = 8_000L
    }
}
