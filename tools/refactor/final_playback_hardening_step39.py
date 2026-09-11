from pathlib import Path


def read(path: str) -> str:
    return Path(path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected exactly one match in {path}, found {count}: {old[:120]!r}")
    write(path, text.replace(old, new, 1))


# 1) CDN rejection policy: 403/410 get one bounded same-URL retry, but 429 is
# already an explicit server throttle and must never retry the same signed URL.
policy = "app/src/main/kotlin/com/nikhil/yt/playback/CapsuleLoadErrorHandlingPolicy.kt"
replace_once(
    policy,
    """ * WEB_REMIX resolve. Keep exactly one immediate same-URL retry because captures show\n * that a transient 403 can succeed on the next open. A second rejection is treated as\n * fatal for this load so MusicService can invalidate the URL and do its bounded fresh\n * resolve instead of letting Media3 hammer the same rejected URL many times.\n""",
    """ * WEB_REMIX resolve. Keep exactly one immediate same-URL retry for 403/410 because\n * captures show that a transient rejection can succeed on the next open. A second\n * rejection is fatal for this load so MusicService can invalidate the URL and do its\n * bounded fresh resolve instead of letting Media3 hammer the same rejected URL.\n *\n * 429 is different: it is an explicit throttle signal, so the current CDN URL fails\n * immediately and MusicService's rate-limit circuit breaker gets control without a\n * redundant request.\n""",
)
replace_once(
    policy,
    """    if (cacheKey?.startsWith(CAPSULE_AUDIO_CACHE_PREFIX) != true) return null\n    if (httpStatusCode !in REJECTED_SIGNED_URL_STATUS_CODES) return null\n    return if (errorCount <= 1) 0L else C.TIME_UNSET\n""",
    """    if (cacheKey?.startsWith(CAPSULE_AUDIO_CACHE_PREFIX) != true) return null\n    if (httpStatusCode == 429) return C.TIME_UNSET\n    if (httpStatusCode !in REJECTED_SIGNED_URL_STATUS_CODES) return null\n    return if (errorCount <= 1) 0L else C.TIME_UNSET\n""",
)

test_policy = "app/src/test/kotlin/com/nikhil/yt/playback/CapsuleLoadErrorHandlingPolicyTest.kt"
replace_once(
    test_policy,
    """    @Test\n    fun rateLimitAndNonAudioLoadsKeepMedia3DefaultPolicy() {\n        assertNull(\n            audioCdnRejectedRetryDelayMs(\n                cacheKey = \"capsule:audio:track:251:1234\",\n                httpStatusCode = 429,\n                errorCount = 2,\n            ),\n        )\n        assertNull(\n            audioCdnRejectedRetryDelayMs(\n                cacheKey = \"capsule:video:track\",\n                httpStatusCode = 403,\n                errorCount = 2,\n            ),\n        )\n    }\n""",
    """    @Test\n    fun rateLimitFailsFastForAudioWithoutSameUrlRetry() {\n        assertEquals(\n            C.TIME_UNSET,\n            audioCdnRejectedRetryDelayMs(\n                cacheKey = \"capsule:audio:track:251:1234\",\n                httpStatusCode = 429,\n                errorCount = 1,\n            ),\n        )\n    }\n\n    @Test\n    fun nonAudioLoadsKeepMedia3DefaultPolicy() {\n        assertNull(\n            audioCdnRejectedRetryDelayMs(\n                cacheKey = \"capsule:video:track\",\n                httpStatusCode = 403,\n                errorCount = 2,\n            ),\n        )\n    }\n""",
)


# 2) Safe signed-URL fingerprinting. The full URL/signature never enters logs;
# a short SHA-256 prefix is enough to prove whether recovery reused or replaced it.
diag = "app/src/main/kotlin/com/nikhil/yt/playback/audio/AudioNetworkDiagnosticDataSource.kt"
replace_once(
    diag,
    """import java.net.SocketTimeoutException\n""",
    """import java.net.SocketTimeoutException\nimport java.security.MessageDigest\n""",
)
replace_once(
    diag,
    """internal fun Throwable.isExpectedAudioCdnInterruption(): Boolean {\n""",
    """internal fun audioCdnUrlFingerprint(rawUrl: String): String {\n    val digest = MessageDigest.getInstance(\"SHA-256\").digest(rawUrl.toByteArray(Charsets.UTF_8))\n    val alphabet = \"0123456789abcdef\"\n    val output = CharArray(12)\n    for (index in 0 until 6) {\n        val value = digest[index].toInt() and 0xff\n        output[index * 2] = alphabet[value ushr 4]\n        output[index * 2 + 1] = alphabet[value and 0x0f]\n    }\n    return String(output)\n}\n\ninternal fun Throwable.isExpectedAudioCdnInterruption(): Boolean {\n""",
)
replace_once(
    diag,
    """    private var mediaKey: String? = null\n    private var host: String? = null\n""",
    """    private var mediaKey: String? = null\n    private var host: String? = null\n    private var urlFingerprint: String? = null\n""",
)
replace_once(
    diag,
    """        mediaKey = dataSpec.key?.take(64)\n        host = dataSpec.uri.host?.take(96)\n\n        Timber.tag(TAG).i(\n            \"cdn-open-start id=%s host=%s position=%d length=%d\",\n            mediaKey ?: \"none\",\n            host ?: \"unknown\",\n            dataSpec.position,\n            dataSpec.length,\n        )\n""",
    """        mediaKey = dataSpec.key?.take(64)\n        host = dataSpec.uri.host?.take(96)\n        urlFingerprint = audioCdnUrlFingerprint(dataSpec.uri.toString())\n\n        Timber.tag(TAG).i(\n            \"cdn-open-start id=%s host=%s urlFp=%s position=%d length=%d\",\n            mediaKey ?: \"none\",\n            host ?: \"unknown\",\n            urlFingerprint ?: \"none\",\n            dataSpec.position,\n            dataSpec.length,\n        )\n""",
)
replace_once(
    diag,
    """                    \"cdn-open-ready id=%s host=%s openMs=%d resolvedLength=%d\",\n                    mediaKey ?: \"none\",\n                    host ?: \"unknown\",\n                    elapsedMs(startedAtNs, openCompletedAtNs),\n                    resolvedLength,\n""",
    """                    \"cdn-open-ready id=%s host=%s urlFp=%s openMs=%d resolvedLength=%d\",\n                    mediaKey ?: \"none\",\n                    host ?: \"unknown\",\n                    urlFingerprint ?: \"none\",\n                    elapsedMs(startedAtNs, openCompletedAtNs),\n                    resolvedLength,\n""",
)
replace_once(
    diag,
    """                    \"cdn-open-interrupted id=%s host=%s elapsedMs=%d\",\n                    mediaKey ?: \"none\",\n                    host ?: \"unknown\",\n                    elapsedMs(startedAtNs, now),\n""",
    """                    \"cdn-open-interrupted id=%s host=%s urlFp=%s elapsedMs=%d\",\n                    mediaKey ?: \"none\",\n                    host ?: \"unknown\",\n                    urlFingerprint ?: \"none\",\n                    elapsedMs(startedAtNs, now),\n""",
)
replace_once(
    diag,
    """                    \"cdn-open-failed id=%s host=%s elapsedMs=%d\",\n                    mediaKey ?: \"none\",\n                    host ?: \"unknown\",\n                    elapsedMs(startedAtNs, now),\n""",
    """                    \"cdn-open-failed id=%s host=%s urlFp=%s elapsedMs=%d\",\n                    mediaKey ?: \"none\",\n                    host ?: \"unknown\",\n                    urlFingerprint ?: \"none\",\n                    elapsedMs(startedAtNs, now),\n""",
)
replace_once(
    diag,
    """            mediaKey = null\n            host = null\n""",
    """            mediaKey = null\n            host = null\n            urlFingerprint = null\n""",
)

fingerprint_test = "app/src/test/kotlin/com/nikhil/yt/playback/audio/AudioNetworkDiagnosticDataSourceTest.kt"
if not Path(fingerprint_test).exists():
    write(
        fingerprint_test,
        """package com.nikhil.yt.playback.audio\n\nimport org.junit.Assert.assertEquals\nimport org.junit.Assert.assertFalse\nimport org.junit.Assert.assertNotEquals\nimport org.junit.Test\n\nclass AudioNetworkDiagnosticDataSourceTest {\n    @Test\n    fun urlFingerprintIsStableShortAndOpaque() {\n        val first = \"https://rr.example.googlevideo.com/videoplayback?expire=1&sig=secret-a\"\n        val second = \"https://rr.example.googlevideo.com/videoplayback?expire=2&sig=secret-b\"\n\n        val firstFingerprint = audioCdnUrlFingerprint(first)\n\n        assertEquals(12, firstFingerprint.length)\n        assertEquals(firstFingerprint, audioCdnUrlFingerprint(first))\n        assertNotEquals(firstFingerprint, audioCdnUrlFingerprint(second))\n        assertFalse(firstFingerprint.contains(\"secret\", ignoreCase = true))\n        assertFalse(firstFingerprint.contains(\"sig\", ignoreCase = true))\n    }\n}\n""",
    )


# 3) Offload safety. UI already refuses UNSUPPORTED/UNKNOWN at enable time.
# Add a runtime guard for route/format changes so an old enabled preference
# cannot stay visually enabled after the selected track becomes unsupported.
ext = "app/src/main/kotlin/com/nikhil/yt/extensions/ExoPlayerExtensions.kt"
replace_once(
    ext,
    """internal enum class CapsuleAudioOffloadAvailability {\n    SUPPORTED,\n    UNSUPPORTED,\n    UNKNOWN,\n}\n\n""",
    """internal enum class CapsuleAudioOffloadAvailability {\n    SUPPORTED,\n    UNSUPPORTED,\n    UNKNOWN,\n}\n\ninternal fun ExoPlayer.isAudioOffloadRequested(): Boolean =\n    trackSelectionParameters.audioOffloadPreferences.audioOffloadMode !=\n        TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED\n\n""",
)

service = "app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt"
replace_once(
    service,
    """import androidx.media3.common.Timeline\n""",
    """import androidx.media3.common.Timeline\nimport androidx.media3.common.Tracks\n""",
)
replace_once(
    service,
    """import com.nikhil.yt.extensions.setOffloadEnabled\n""",
    """import com.nikhil.yt.extensions.CapsuleAudioOffloadAvailability\nimport com.nikhil.yt.extensions.currentAudioOffloadAvailability\nimport com.nikhil.yt.extensions.isAudioOffloadRequested\nimport com.nikhil.yt.extensions.setOffloadEnabled\n""",
)
replace_once(
    service,
    """    override fun onPlayerError(error: PlaybackException) {\n""",
    """    override fun onTracksChanged(tracks: Tracks) {\n        if (tracks.groups.isEmpty() || !player.isAudioOffloadRequested()) return\n        if (player.currentAudioOffloadAvailability() != CapsuleAudioOffloadAvailability.UNSUPPORTED) return\n\n        // The route or selected format changed after the user enabled offload.\n        // Keep runtime state and persisted UI state honest: unsupported means OFF.\n        player.setOffloadEnabled(false)\n        scope.launch(Dispatchers.IO) {\n            dataStore.edit { preferences ->\n                if (preferences[AudioOffload] == true) {\n                    preferences[AudioOffload] = false\n                }\n            }\n        }\n        Timber.tag(\"AudioOffload\").i(\n            \"Disabled audio offload after current format/output became unsupported\",\n        )\n    }\n\n    override fun onPlayerError(error: PlaybackException) {\n""",
)


# 4) Harden the existing account login path. Only the exact HTTPS
# music.youtube.com destination may run config-extraction JavaScript or publish
# a YouTube Music cookie. This does not bypass supervised/age restrictions; it
# simply makes legitimate signed-in account state safer and less spoofable.
login_policy = "app/src/main/kotlin/com/nikhil/yt/ui/screens/YouTubeLoginUrlPolicy.kt"
if not Path(login_policy).exists():
    write(
        login_policy,
        """package com.nikhil.yt.ui.screens\n\nimport java.net.URI\n\ninternal fun isYouTubeMusicLoginPage(url: String?): Boolean {\n    val parsed =\n        runCatching { URI(url ?: return false) }\n            .getOrNull()\n            ?: return false\n\n    return parsed.scheme.equals(\"https\", ignoreCase = true) &&\n        parsed.host.equals(\"music.youtube.com\", ignoreCase = true)\n}\n""",
    )

login = "app/src/main/kotlin/com/nikhil/yt/ui/screens/LoginScreen.kt"
replace_once(
    login,
    """                    override fun onPageFinished(view: WebView, url: String?) {\n                        loadUrl(\"javascript:Android.onRetrieveVisitorData(window.yt.config_.VISITOR_DATA)\")\n                        loadUrl(\"javascript:Android.onRetrieveDataSyncId(window.yt.config_.DATASYNC_ID)\")\n                        loadUrl(\"javascript:void((function(){try{var c=window.ytcfg;if(c&&c.get){var t=c.get('PO_TOKEN');if(t){Android.onRetrievePoToken(t);return}}var s=document.querySelectorAll('script');for(var i=0;i<s.length;i++){var m=s[i].textContent.match(/\\\"PO_TOKEN\\\":\\\"([^\\\"]+)\\\"/);if(m){Android.onRetrievePoToken(m[1]);return}}}catch(e){}})())\")\n\n                        if (url?.startsWith(\"https://music.youtube.com\") == true) {\n                            val loginCookie = CookieManager.getInstance().getCookie(url).orEmpty()\n                            if (loginCookie.isBlank()) return\n""",
    """                    override fun onPageFinished(view: WebView, url: String?) {\n                        if (isYouTubeMusicLoginPage(url)) {\n                            loadUrl(\"javascript:Android.onRetrieveVisitorData(window.yt.config_.VISITOR_DATA)\")\n                            loadUrl(\"javascript:Android.onRetrieveDataSyncId(window.yt.config_.DATASYNC_ID)\")\n                            loadUrl(\"javascript:void((function(){try{var c=window.ytcfg;if(c&&c.get){var t=c.get('PO_TOKEN');if(t){Android.onRetrievePoToken(t);return}}var s=document.querySelectorAll('script');for(var i=0;i<s.length;i++){var m=s[i].textContent.match(/\\\"PO_TOKEN\\\":\\\"([^\\\"]+)\\\"/);if(m){Android.onRetrievePoToken(m[1]);return}}}catch(e){}})())\")\n\n                            val loginCookie = CookieManager.getInstance().getCookie(url).orEmpty()\n                            if (loginCookie.isBlank()) return\n                            CookieManager.getInstance().flush()\n""",
)

login_test = "app/src/test/kotlin/com/nikhil/yt/ui/screens/YouTubeLoginUrlPolicyTest.kt"
if not Path(login_test).exists():
    write(
        login_test,
        """package com.nikhil.yt.ui.screens\n\nimport org.junit.Assert.assertFalse\nimport org.junit.Assert.assertTrue\nimport org.junit.Test\n\nclass YouTubeLoginUrlPolicyTest {\n    @Test\n    fun acceptsOnlyExactHttpsMusicYouTubeHost() {\n        assertTrue(isYouTubeMusicLoginPage(\"https://music.youtube.com/\"))\n        assertTrue(isYouTubeMusicLoginPage(\"https://music.youtube.com/watch?v=test\"))\n\n        assertFalse(isYouTubeMusicLoginPage(null))\n        assertFalse(isYouTubeMusicLoginPage(\"http://music.youtube.com/\"))\n        assertFalse(isYouTubeMusicLoginPage(\"https://music.youtube.com.evil.example/\"))\n        assertFalse(isYouTubeMusicLoginPage(\"https://accounts.google.com/ServiceLogin\"))\n        assertFalse(isYouTubeMusicLoginPage(\"not a url\"))\n    }\n}\n""",
    )

print("Final playback hardening patch applied")
