from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"missing patch anchor in {path}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# 1) CDN 403/410 handling: observed GVS token propagation can need a short wait.
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/CapsuleLoadErrorHandlingPolicy.kt",
    '''/**
 * Audio CDN URLs are signed and can occasionally be rejected even after a successful
 * WEB_REMIX resolve. Keep exactly one immediate same-URL retry for 403/410 because
 * captures show that a transient rejection can succeed on the next open. A second
 * rejection is fatal for this load so MusicService can invalidate the URL and do its
 * bounded fresh resolve instead of letting Media3 hammer the same rejected URL.
 *
 * 429 is different: it is an explicit throttle signal, so the current CDN URL fails
 * immediately and MusicService's rate-limit circuit breaker gets control without a
 * redundant request.
 *
 * null means: use Media3's normal policy for this load.
 */''',
    '''/**
 * Audio CDN URLs are signed/tokenized and a freshly resolved GVS URL can be rejected
 * transiently before the same URL becomes usable. Device captures show both one-shot
 * 403s and a double-403 that succeeds roughly one second later without a fresh resolve.
 * Use a tiny, bounded propagation backoff instead of immediately spending another
 * /player request and rotating client identity.
 *
 * 403 gets two delayed same-URL retries (250 ms, then 1 s). 410 remains tighter because
 * it more often represents a genuinely gone URL: one 250 ms retry, then fresh resolve.
 * 429 is an explicit throttle signal and never retries the same URL.
 *
 * null means: use Media3's normal policy for this load.
 */''',
)
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/CapsuleLoadErrorHandlingPolicy.kt",
    '''    if (httpStatusCode == 429) return C.TIME_UNSET
    if (httpStatusCode !in REJECTED_SIGNED_URL_STATUS_CODES) return null
    return if (errorCount <= 1) 0L else C.TIME_UNSET''',
    '''    if (httpStatusCode == 429) return C.TIME_UNSET
    if (httpStatusCode !in REJECTED_SIGNED_URL_STATUS_CODES) return null

    return when (httpStatusCode) {
        403 ->
            when (errorCount) {
                1 -> 250L
                2 -> 1_000L
                else -> C.TIME_UNSET
            }
        410 -> if (errorCount <= 1) 250L else C.TIME_UNSET
        else -> null
    }''',
)

Path("app/src/test/kotlin/com/nikhil/yt/playback/CapsuleLoadErrorHandlingPolicyTest.kt").write_text(
    '''package com.nikhil.yt.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CapsuleLoadErrorHandlingPolicyTest {
    @Test
    fun firstAudio403WaitsBeforeSameUrlRetry() {
        assertEquals(
            250L,
            audioCdnRejectedRetryDelayMs(
                cacheKey = "capsule:audio:track:251:1234",
                httpStatusCode = 403,
                errorCount = 1,
            ),
        )
    }

    @Test
    fun secondAudio403GetsOneLongerPropagationRetry() {
        assertEquals(
            1_000L,
            audioCdnRejectedRetryDelayMs(
                cacheKey = "capsule:audio:track:251:1234",
                httpStatusCode = 403,
                errorCount = 2,
            ),
        )
    }

    @Test
    fun thirdAudio403StopsSameUrlRetries() {
        assertEquals(
            C.TIME_UNSET,
            audioCdnRejectedRetryDelayMs(
                cacheKey = "capsule:audio:track:251:1234",
                httpStatusCode = 403,
                errorCount = 3,
            ),
        )
    }

    @Test
    fun rejected410GetsOnlyOneShortRetry() {
        assertEquals(
            250L,
            audioCdnRejectedRetryDelayMs(
                cacheKey = "capsule:audio:track:251:1234",
                httpStatusCode = 410,
                errorCount = 1,
            ),
        )
        assertEquals(
            C.TIME_UNSET,
            audioCdnRejectedRetryDelayMs(
                cacheKey = "capsule:audio:track:251:1234",
                httpStatusCode = 410,
                errorCount = 2,
            ),
        )
    }

    @Test
    fun rateLimitFailsFastForAudioWithoutSameUrlRetry() {
        assertEquals(
            C.TIME_UNSET,
            audioCdnRejectedRetryDelayMs(
                cacheKey = "capsule:audio:track:251:1234",
                httpStatusCode = 429,
                errorCount = 1,
            ),
        )
    }

    @Test
    fun nonAudioLoadsKeepMedia3DefaultPolicy() {
        assertNull(
            audioCdnRejectedRetryDelayMs(
                cacheKey = "capsule:video:track",
                httpStatusCode = 403,
                errorCount = 2,
            ),
        )
    }
}
''',
    encoding="utf-8",
)

# 2) Bound foreground identity churn and pace the rare fallback path.
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/audio/CapsuleAudioFallbackPolicy.kt",
    '''    private val supportedProfiles = AudioClientOrder.supportedProfiles.toSet()
    private const val MAX_FOREGROUND_ATTEMPTS = 6''',
    '''    private val supportedProfiles = AudioClientOrder.supportedProfiles.toSet()
    private const val MAX_FOREGROUND_ATTEMPTS = 3
    private const val NORMAL_FALLBACK_DELAY_MS = 350L
    private const val BOT_FALLBACK_DELAY_MS = 1_000L''',
)
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/audio/CapsuleAudioFallbackPolicy.kt",
    '''    fun canFallbackAfter(kind: YouTubeFailureKind): Boolean =
        when (kind) {''',
    '''    fun fallbackDelayMs(kind: YouTubeFailureKind): Long =
        when (kind) {
            YouTubeFailureKind.BOT_CHECK -> BOT_FALLBACK_DELAY_MS
            YouTubeFailureKind.FORBIDDEN,
            YouTubeFailureKind.LOGIN_REQUIRED,
            YouTubeFailureKind.AGE_RESTRICTED,
            YouTubeFailureKind.UNPLAYABLE,
            YouTubeFailureKind.NONE,
            -> NORMAL_FALLBACK_DELAY_MS
            else -> 0L
        }

    fun canFallbackAfter(kind: YouTubeFailureKind): Boolean =
        when (kind) {''',
)

fallback_test = Path("app/src/test/kotlin/com/nikhil/yt/playback/audio/CapsuleAudioFallbackPolicyTest.kt")
text = fallback_test.read_text(encoding="utf-8")
text = text.replace(
    '''            listOf(
                "WEB_REMIX",
                "VISIONOS_0_1",
                "WEB_EMBEDDED_PLAYER",
                "TVHTML5_SIMPLY",
            ),''',
    '''            listOf(
                "WEB_REMIX",
                "VISIONOS_0_1",
                "WEB_EMBEDDED_PLAYER",
            ),''',
    1,
)
text = text.replace('''        assertEquals(custom.dropLast(1), plan)''', '''        assertEquals(custom.take(3), plan)''', 1)
text = text.replace('''    fun authenticatedManualOrderCanReachAllSixMaintainedProfiles() {''', '''    fun authenticatedManualOrderIsCappedAtThreeForegroundProfiles() {''', 1)
text = text.replace('''        assertEquals(custom, plan)''', '''        assertEquals(custom.take(3), plan)''', 1)
anchor = '''    @Test
    fun networkAndRateFailuresDoNotRotateIdentity() {
'''
if anchor not in text:
    raise SystemExit("fallback policy test anchor missing")
text = text.replace(
    anchor,
    '''    @Test
    fun fallbackPacingIsDeterministicAndRateLimitsNeverRetry() {
        assertEquals(350L, CapsuleAudioFallbackPolicy.fallbackDelayMs(YouTubeFailureKind.NONE))
        assertEquals(350L, CapsuleAudioFallbackPolicy.fallbackDelayMs(YouTubeFailureKind.UNPLAYABLE))
        assertEquals(1_000L, CapsuleAudioFallbackPolicy.fallbackDelayMs(YouTubeFailureKind.BOT_CHECK))
        assertEquals(0L, CapsuleAudioFallbackPolicy.fallbackDelayMs(YouTubeFailureKind.RATE_LIMITED))
    }

''' + anchor,
    1,
)
fallback_test.write_text(text, encoding="utf-8")

# 3) Apply the pacing in the outer Capsule-owned fallback loop.
player = "app/src/main/kotlin/com/nikhil/yt/playback/audio/CapsuleInnerTubeXPlayer.kt"
replace_once(
    player,
    '''import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive''',
    '''import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive''',
)
replace_once(
    player,
    '''                    botSignalAlreadySeen = true
                    onlyPostBotProfile = crossFamily
                    lastFailure = failure
                    continue''',
    '''                    botSignalAlreadySeen = true
                    onlyPostBotProfile = crossFamily
                    lastFailure = failure
                    delay(CapsuleAudioFallbackPolicy.fallbackDelayMs(kind))
                    continue''',
)
replace_once(
    player,
    '''                Timber.tag(TAG).w(
                    "AUDIO client-local failure id=%s profile=%s kind=%s; trying bounded fallback",
                    videoId,
                    profileId,
                    kind,
                )
                lastFailure = failure''',
    '''                val fallbackDelayMs = CapsuleAudioFallbackPolicy.fallbackDelayMs(kind)
                Timber.tag(TAG).w(
                    "AUDIO client-local failure id=%s profile=%s kind=%s; fallbackDelayMs=%d",
                    videoId,
                    profileId,
                    kind,
                    fallbackDelayMs,
                )
                lastFailure = failure
                if (fallbackDelayMs > 0L) delay(fallbackDelayMs)''',
)

# 4) Point the validated remote config store at the source it actually trusts.
replace_once(
    player,
    '''"https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"''',
    '''"https://raw.githubusercontent.com/MetrolistGroup/faraday/master/registry/player_configs.json"''',
)

# 5) Identical loudness state can arrive through two upstream flows. Do not re-apply/log it.
music = "app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt"
replace_once(
    music,
    '''            (fresh?.takeIf { it.preferredValue != null } ?: stored) to normalizeAudio
        }.collectLatest(scope) { (loudness, normalizeAudio) ->''',
    '''            (fresh?.takeIf { it.preferredValue != null } ?: stored) to normalizeAudio
        }.distinctUntilChanged().collectLatest(scope) { (loudness, normalizeAudio) ->''',
)

print("step41 playback hardening patch applied")
