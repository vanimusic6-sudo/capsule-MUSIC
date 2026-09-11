from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def replace_once(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected exactly one match in {path}, got {count}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# Step44 correctly stopped retrying an already-rejected CDN generation, but its
# 250 ms clamp made the higher-level recovery burn through fresh /player resolves
# far too quickly when a whole Web PoToken session was unhealthy. Preserve the
# normal exponential retry budget and allow only two fresh signed-URL generations.
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt",
    '''internal const val SIGNED_URL_REFRESH_DELAY_MS = 250L

internal fun signedUrlRefreshDelayMs(
    httpStatusCode: Int?,
    budgetDelayMs: Long,
): Long =
    if (httpStatusCode in setOf(403, 410)) {
        minOf(budgetDelayMs, SIGNED_URL_REFRESH_DELAY_MS)
    } else {
        budgetDelayMs
    }
''',
    '''internal const val SIGNED_URL_SESSION_REFRESH_THRESHOLD_MS = 3_000L
internal const val SIGNED_URL_MAX_FRESH_RESOLVE_DELAY_MS = 3_000L

internal fun signedUrlRefreshDelayMs(
    httpStatusCode: Int?,
    budgetDelayMs: Long,
): Long = budgetDelayMs

internal fun shouldRefreshStreamSessionAfterSignedUrlRejection(
    httpStatusCode: Int?,
    budgetDelayMs: Long,
): Boolean =
    httpStatusCode in setOf(403, 410) &&
        budgetDelayMs >= SIGNED_URL_SESSION_REFRESH_THRESHOLD_MS

internal fun shouldRetryRejectedSignedUrl(
    httpStatusCode: Int?,
    budgetDelayMs: Long,
): Boolean =
    httpStatusCode !in setOf(403, 410) ||
        budgetDelayMs <= SIGNED_URL_MAX_FRESH_RESOLVE_DELAY_MS
''',
)

replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt",
    '''            val retryDelay = playbackRecoveryCoordinator.nextRetryDelayMs(currentMediaId)
            if (retryDelay == null) {
                handleTerminalPlaybackError()
                return
            }
            // A rejected/expired URL does not mean the selected client is broken.
            // Refresh with that same explicit client, within the shared retry budget.
            CapsuleAudioEngine.clearTrackClientFailures(currentMediaId)
            audioResolveCoordinator.cancelMedia(currentMediaId) {
                playbackUrlCache.remove(currentMediaId)
            }
            scheduleStreamRefreshRetry(
                mediaId = currentMediaId,
                // The step43 capture had a healthy Faraday config (the refresh returned 304)
                // and the fresh /player generation succeeded. Refresh the rejected URL, not
                // unrelated cipher configuration, on every rare CDN rejection.
                refreshCipherConfig = false,
                retryReason = "http=$httpStatusCode code=${error.errorCode}",
                retryDelayMs = signedUrlRefreshDelayMs(httpStatusCode, retryDelay),
            )
            return
''',
    '''            val retryDelay = playbackRecoveryCoordinator.nextRetryDelayMs(currentMediaId)
            if (
                retryDelay == null ||
                !shouldRetryRejectedSignedUrl(httpStatusCode, retryDelay)
            ) {
                handleTerminalPlaybackError()
                return
            }
            // A rejected/expired URL does not mean the selected client is broken.
            // Keep the same client and identity, but do not burn through fresh
            // generations in a 250 ms loop. After a second signed-URL rejection,
            // refresh the same visitor-bound streaming session once before the
            // final bounded fresh resolve.
            CapsuleAudioEngine.clearTrackClientFailures(currentMediaId)
            audioResolveCoordinator.cancelMedia(currentMediaId) {
                playbackUrlCache.remove(currentMediaId)
            }
            scheduleStreamRefreshRetry(
                mediaId = currentMediaId,
                refreshCipherConfig =
                    shouldRefreshStreamSessionAfterSignedUrlRejection(
                        httpStatusCode = httpStatusCode,
                        budgetDelayMs = retryDelay,
                    ),
                retryReason = "http=$httpStatusCode code=${error.errorCode}",
                retryDelayMs = signedUrlRefreshDelayMs(httpStatusCode, retryDelay),
            )
            return
''',
)

# Force-recreate only the existing visitor-bound BotGuard session. This is not
# visitor rotation: the same visitorData/cookies/client policy are preserved.
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/audio/potoken/PoTokenGenerator.kt",
    '''    suspend fun close() {
        clear()
    }
''',
    '''    suspend fun refreshSameVisitorSession(visitorData: String): Boolean {
        val normalizedVisitorData = visitorData.trim()
        if (normalizedVisitorData.isBlank() || !webViewSupported || brokenWebView) return false

        return try {
            withTimeout(OVERALL_TIMEOUT_MS) {
                prepareSession(
                    visitorData = normalizedVisitorData,
                    forceRecreate = true,
                )
            }
            Timber.tag(TAG).i("Web PoToken session refreshed after repeated stream rejection")
            true
        } catch (timeout: TimeoutCancellationException) {
            Timber.tag(TAG).w("Web PoToken session refresh timed out")
            clear()
            false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (badWebView: BadWebViewException) {
            Timber.tag(TAG).w(badWebView, "System WebView cannot refresh BotGuard")
            brokenWebView = true
            clear()
            false
        } catch (error: Exception) {
            Timber.tag(TAG).w(error, "Web PoToken session refresh failed")
            clear()
            false
        }
    }

    suspend fun close() {
        clear()
    }
''',
)

replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/audio/CapsuleInnerTubeXPlayer.kt",
    '''    suspend fun refreshAfterStreamRejection(): Boolean =
        resolveMutex.withLock {
            CapsulePlaybackSafety.blockedExceptionOrNull()?.let { throw it }
            bundle().cipherService.refreshAfterStreamRejection()
        }
''',
    '''    suspend fun refreshAfterStreamRejection(): Boolean =
        resolveMutex.withLock {
            CapsulePlaybackSafety.blockedExceptionOrNull()?.let { throw it }
            val extractionBundle = bundle()
            val configChanged = extractionBundle.cipherService.refreshAfterStreamRejection()
            val auth = extractionBundle.key.auth
            val hasConfiguredPoTokens =
                auth.poTokenPlayer?.trim().orEmpty().isNotBlank() &&
                    auth.poTokenGvs?.trim().orEmpty().isNotBlank()
            val visitorData =
                auth.visitorData
                    ?.trim()
                    ?.takeIf { it.isNotBlank() && it != "null" }
            val tokenSessionRefreshed =
                if (!hasConfiguredPoTokens && visitorData != null) {
                    poTokenGenerator.refreshSameVisitorSession(visitorData)
                } else {
                    false
                }
            if (tokenSessionRefreshed) {
                Timber.tag(TAG).i("Refreshed same-visitor Web PoToken session after repeated CDN rejection")
            }
            configChanged || tokenSessionRefreshed
        }
''',
)

replace_once(
    "app/src/test/kotlin/com/nikhil/yt/playback/CapsuleLoadErrorHandlingPolicyTest.kt",
    '''import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
''',
    '''import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
''',
)

replace_once(
    "app/src/test/kotlin/com/nikhil/yt/playback/CapsuleLoadErrorHandlingPolicyTest.kt",
    '''    @Test
    fun rejectedSignedUrlUsesShortFreshResolveDelayButKeepsBudgetBound() {
        assertEquals(250L, signedUrlRefreshDelayMs(httpStatusCode = 403, budgetDelayMs = 1_500L))
        assertEquals(250L, signedUrlRefreshDelayMs(httpStatusCode = 410, budgetDelayMs = 3_000L))
        assertEquals(1_500L, signedUrlRefreshDelayMs(httpStatusCode = 500, budgetDelayMs = 1_500L))
    }
''',
    '''    @Test
    fun rejectedSignedUrlPreservesSharedRecoveryBackoff() {
        assertEquals(1_500L, signedUrlRefreshDelayMs(httpStatusCode = 403, budgetDelayMs = 1_500L))
        assertEquals(3_000L, signedUrlRefreshDelayMs(httpStatusCode = 410, budgetDelayMs = 3_000L))
        assertEquals(1_500L, signedUrlRefreshDelayMs(httpStatusCode = 500, budgetDelayMs = 1_500L))
    }

    @Test
    fun repeatedSignedUrlRejectionRefreshesSessionOnlyOnSecondFreshAttempt() {
        assertFalse(
            shouldRefreshStreamSessionAfterSignedUrlRejection(
                httpStatusCode = 403,
                budgetDelayMs = 1_500L,
            ),
        )
        assertTrue(
            shouldRefreshStreamSessionAfterSignedUrlRejection(
                httpStatusCode = 403,
                budgetDelayMs = 3_000L,
            ),
        )
        assertFalse(
            shouldRefreshStreamSessionAfterSignedUrlRejection(
                httpStatusCode = 500,
                budgetDelayMs = 3_000L,
            ),
        )
    }

    @Test
    fun signedUrlFreshResolveLoopStopsBeforeThirdRecoverySlot() {
        assertTrue(shouldRetryRejectedSignedUrl(httpStatusCode = 403, budgetDelayMs = 1_500L))
        assertTrue(shouldRetryRejectedSignedUrl(httpStatusCode = 403, budgetDelayMs = 3_000L))
        assertFalse(shouldRetryRejectedSignedUrl(httpStatusCode = 403, budgetDelayMs = 6_000L))
        assertTrue(shouldRetryRejectedSignedUrl(httpStatusCode = 500, budgetDelayMs = 6_000L))
    }
''',
)

print("step45 patch applied")
