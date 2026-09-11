from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def replace_once(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected exactly one match in {path}, got {count}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# 1) A CDN 403/410 rejects the current signed URL generation. Step43 field data showed
#    three retries of one generation all failed, while one fresh /player resolve for the
#    same mediaId/itag succeeded immediately. Do not keep hammering the rejected URL.
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/CapsuleLoadErrorHandlingPolicy.kt",
    '''/**
 * Audio CDN URLs are signed/tokenized and can be rejected transiently even though
 * an unchanged retry succeeds. Device captures show both one-shot 403s and a
 * double-403 that succeeds later without a fresh /player resolve.
 *
 * 403 gets two delayed same-URL retries (250 ms, then 1 s). 410 remains tighter because
 * it more often represents a genuinely gone URL: one 250 ms retry, then fresh resolve.
 * 429 is an explicit throttle signal and never retries the same URL.
 *
 * null means: use Media3's normal policy for this load.
 */
internal fun audioCdnRejectedRetryDelayMs(
    cacheKey: String?,
    httpStatusCode: Int?,
    errorCount: Int,
): Long? {
    if (cacheKey?.startsWith(CAPSULE_AUDIO_CACHE_PREFIX) != true) return null
    if (httpStatusCode == 429) return C.TIME_UNSET
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
    }
}
''',
    '''/**
 * A 403/410 from googlevideo rejects the current signed URL generation.
 *
 * Step43 device captures finally separated this from transient transport noise: one
 * generation failed at ages ~1.3 s, ~1.8 s and ~3.4 s with complete PoToken/n/signature
 * and request headers, while a fresh /player generation for the same mediaId/itag opened
 * successfully. Retrying the rejected generation only creates more 403s and delays recovery.
 *
 * Fail the current load immediately so MusicService can invalidate that one PlaybackData
 * generation and perform its bounded fresh resolve. 429 also never retries the same URL.
 * null means: keep Media3's normal policy for unrelated loads/statuses.
 */
internal fun audioCdnRejectedRetryDelayMs(
    cacheKey: String?,
    httpStatusCode: Int?,
    errorCount: Int,
): Long? {
    if (cacheKey?.startsWith(CAPSULE_AUDIO_CACHE_PREFIX) != true) return null
    if (httpStatusCode == 429) return C.TIME_UNSET
    if (httpStatusCode in REJECTED_SIGNED_URL_STATUS_CODES) return C.TIME_UNSET
    return null
}
''',
)

# 2) Preserve the shared bounded recovery budget, but do not add the generic 1.5 s delay
#    after a signed URL has already been rejected. A fresh generation is the recovery.
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt",
    "internal const val MIN_NORMALIZATION_GAIN_DB = -12.0\n",
    '''internal const val MIN_NORMALIZATION_GAIN_DB = -12.0
internal const val SIGNED_URL_REFRESH_DELAY_MS = 250L

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
)
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt",
    '''            scheduleStreamRefreshRetry(
                mediaId = currentMediaId,
                refreshCipherConfig = httpStatusCode in setOf(403, 410),
                retryReason = "http=$httpStatusCode code=${error.errorCode}",
                retryDelayMs = retryDelay,
            )
''',
    '''            scheduleStreamRefreshRetry(
                mediaId = currentMediaId,
                // The step43 capture had a healthy Faraday config (the refresh returned 304)
                // and the fresh /player generation succeeded. Refresh the rejected URL, not
                // unrelated cipher configuration, on every rare CDN rejection.
                refreshCipherConfig = false,
                retryReason = "http=$httpStatusCode code=${error.errorCode}",
                retryDelayMs = signedUrlRefreshDelayMs(httpStatusCode, retryDelay),
            )
''',
)

# 3) Update policy tests to encode generation refresh instead of same-URL retries.
test_path = ROOT / "app/src/test/kotlin/com/nikhil/yt/playback/CapsuleLoadErrorHandlingPolicyTest.kt"
test = test_path.read_text(encoding="utf-8")
test = test.replace("fun firstAudio403WaitsBeforeSameUrlRetry()", "fun firstAudio403RejectsCurrentSignedUrlGeneration()")
test = test.replace("fun secondAudio403GetsOneLongerPropagationRetry()", "fun repeatedAudio403StillRejectsCurrentSignedUrlGeneration()")
test = test.replace("fun thirdAudio403StopsSameUrlRetries()", "fun laterAudio403StillStopsSameUrlGeneration()")
test = test.replace(
    '''            250L,
            audioCdnRejectedRetryDelayMs(
                cacheKey = "capsule:audio:track:251:1234",
                httpStatusCode = 403,
                errorCount = 1,
            ),
''',
    '''            C.TIME_UNSET,
            audioCdnRejectedRetryDelayMs(
                cacheKey = "capsule:audio:track:251:1234",
                httpStatusCode = 403,
                errorCount = 1,
            ),
''',
    1,
)
test = test.replace(
    '''            1_000L,
            audioCdnRejectedRetryDelayMs(
                cacheKey = "capsule:audio:track:251:1234",
                httpStatusCode = 403,
                errorCount = 2,
            ),
''',
    '''            C.TIME_UNSET,
            audioCdnRejectedRetryDelayMs(
                cacheKey = "capsule:audio:track:251:1234",
                httpStatusCode = 403,
                errorCount = 2,
            ),
''',
    1,
)
test = test.replace(
    '''            250L,
            audioCdnRejectedRetryDelayMs(
                cacheKey = "capsule:audio:track:251:1234",
                httpStatusCode = 410,
                errorCount = 1,
            ),
''',
    '''            C.TIME_UNSET,
            audioCdnRejectedRetryDelayMs(
                cacheKey = "capsule:audio:track:251:1234",
                httpStatusCode = 410,
                errorCount = 1,
            ),
''',
    1,
)
needle = '''    @Test
    fun rateLimitFailsFastForAudioWithoutSameUrlRetry() {
'''
if test.count(needle) != 1:
    raise SystemExit("could not find insertion point for refresh-delay test")
insert = '''    @Test
    fun rejectedSignedUrlUsesShortFreshResolveDelayButKeepsBudgetBound() {
        assertEquals(250L, signedUrlRefreshDelayMs(httpStatusCode = 403, budgetDelayMs = 1_500L))
        assertEquals(250L, signedUrlRefreshDelayMs(httpStatusCode = 410, budgetDelayMs = 3_000L))
        assertEquals(1_500L, signedUrlRefreshDelayMs(httpStatusCode = 500, budgetDelayMs = 1_500L))
    }

'''
test = test.replace(needle, insert + needle, 1)
test_path.write_text(test, encoding="utf-8")

print("step44 rejected signed URL generation refresh applied")
