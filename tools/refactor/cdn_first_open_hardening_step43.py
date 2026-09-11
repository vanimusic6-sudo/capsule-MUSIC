from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def replace_once(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise SystemExit(f"expected exactly one match in {path}, got {text.count(old)}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# 1) Timestamp resolved PlaybackData at the exact moment the extraction result is published.
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/audio/CapsuleAudioEngine.kt",
    """        /** Required GVS request headers returned by InnerTubeX. */\n        val streamHeaders: Map<String, String> = emptyMap(),\n    )\n""",
    """        /** Required GVS request headers returned by InnerTubeX. */\n        val streamHeaders: Map<String, String> = emptyMap(),\n        /** Monotonic creation time used only for first-open settling/diagnostics. */\n        val resolvedAtElapsedMs: Long = 0L,\n    )\n""",
)
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/audio/CapsuleAudioEngine.kt",
    """                    streamClient = resolved.streamClient,\n                    streamHeaders = resolved.streamHeaders,\n                )\n""",
    """                    streamClient = resolved.streamClient,\n                    streamHeaders = resolved.streamHeaders,\n                    resolvedAtElapsedMs = android.os.SystemClock.elapsedRealtime(),\n                )\n""",
)

# 2) Keep background prefetch out of the most fragile part of the current track's first CDN open.
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/PlaybackStabilityGate.kt",
    "internal const val PREFETCH_RESOLVE_STABILITY_DELAY_MS = 800L",
    "internal const val PREFETCH_RESOLVE_STABILITY_DELAY_MS = 1_500L",
)
replace_once(
    "app/src/test/kotlin/com/nikhil/yt/playback/PlaybackStabilityGateTest.kt",
    "// Track 1 starts as PREFETCH, so it would normally wait 800 ms.",
    "// Track 1 starts as PREFETCH, so it would normally wait the longer background window.",
)
replace_once(
    "app/src/test/kotlin/com/nikhil/yt/playback/PlaybackStabilityGateTest.kt",
    """        assertTrue(requested.isEmpty())\n        advanceTimeBy(649)\n        runCurrent()\n        assertTrue(requested.isEmpty())\n        advanceTimeBy(1)\n        runCurrent()\n        assertEquals(listOf(24, 25), requested.sorted())\n""",
    """        assertTrue(requested.isEmpty())\n        // The loop already advanced 150 ms after the final selection.\n        advanceTimeBy(PREFETCH_RESOLVE_STABILITY_DELAY_MS - 151)\n        runCurrent()\n        assertTrue(requested.isEmpty())\n        advanceTimeBy(1)\n        runCurrent()\n        assertEquals(listOf(24, 25), requested.sorted())\n""",
)

# 3) Safe first-open context: no URL/token values are ever logged.
context_path = ROOT / "app/src/main/kotlin/com/nikhil/yt/playback/audio/AudioCdnOpenContext.kt"
context_path.write_text(
    '''package com.nikhil.yt.playback.audio\n\ninternal enum class AudioCdnOpenSource {\n    CACHED,\n    JOINED_INFLIGHT,\n    ON_DEMAND,\n}\n\ninternal data class AudioCdnOpenContext(\n    val mediaId: String,\n    val resolvedAtElapsedMs: Long,\n    val source: AudioCdnOpenSource,\n    val streamClient: String?,\n)\n\ninternal const val AUDIO_CDN_INITIAL_SETTLE_MS = 250L\n\n/**\n * Freshly-issued tokenized GVS URLs in field captures sometimes reject the very first open\n * and accept the unchanged retry shortly afterwards. Do not blindly sleep for every track:\n * only finish the small 250 ms age window when the URL was resolved immediately before use.\n */\ninternal fun audioCdnInitialSettleDelayMs(\n    nowElapsedMs: Long,\n    resolvedAtElapsedMs: Long,\n): Long {\n    if (resolvedAtElapsedMs <= 0L || nowElapsedMs < resolvedAtElapsedMs) return 0L\n    val ageMs = nowElapsedMs - resolvedAtElapsedMs\n    return (AUDIO_CDN_INITIAL_SETTLE_MS - ageMs).coerceAtLeast(0L)\n}\n''',
    encoding="utf-8",
)

# 4) Wire diagnostic for the remaining 403s: protocol + route host + coalescing only.
#    No query, cookie, PoToken, signature, IP or raw URL leaves the process.
wire_path = ROOT / "app/src/main/kotlin/com/nikhil/yt/playback/audio/AudioCdnConnectionDiagnosticInterceptor.kt"
wire_path.write_text(
    '''package com.nikhil.yt.playback.audio\n\nimport com.nikhil.yt.utils.GlobalLog\nimport okhttp3.Interceptor\nimport okhttp3.Response\nimport timber.log.Timber\nimport java.io.IOException\n\ninternal fun audioCdnCrossHostCoalesced(\n    requestHost: String,\n    routeHost: String?,\n    protocol: String?,\n): Boolean =\n    routeHost != null &&\n        protocol.equals("h2", ignoreCase = true) &&\n        !requestHost.equals(routeHost, ignoreCase = true)\n\n/** Debug-only connection metadata around googlevideo requests. */\ninternal class AudioCdnConnectionDiagnosticInterceptor : Interceptor {\n    override fun intercept(chain: Interceptor.Chain): Response {\n        val request = chain.request()\n        val requestHost = request.url.host\n        val connection = chain.connection()\n        val routeHost = connection?.route()?.address?.url?.host\n        val protocol = connection?.protocol()?.toString()\n        val coalesced = audioCdnCrossHostCoalesced(requestHost, routeHost, protocol)\n        val connectionId = connection?.let(System::identityHashCode) ?: -1\n\n        return try {\n            chain.proceed(request).also { response ->\n                if (GlobalLog.isEnabled) {\n                    Timber.tag("AudioCDN").d(\n                        "cdn-wire host=%s routeHost=%s protocol=%s coalesced=%s conn=%d status=%d",\n                        requestHost,\n                        routeHost ?: "unknown",\n                        protocol ?: "unknown",\n                        coalesced,\n                        connectionId,\n                        response.code,\n                    )\n                }\n            }\n        } catch (failure: IOException) {\n            if (GlobalLog.isEnabled) {\n                Timber.tag("AudioCDN").d(\n                    "cdn-wire-iofail host=%s routeHost=%s protocol=%s coalesced=%s conn=%d type=%s",\n                    requestHost,\n                    routeHost ?: "unknown",\n                    protocol ?: "unknown",\n                    coalesced,\n                    connectionId,\n                    failure::class.java.simpleName,\n                )\n            }\n            throw failure\n        }\n    }\n}\n''',
    encoding="utf-8",
)

# 5) MusicService: carry source/age to the physical network boundary and finish the small
#    fresh-URL settle window there. Also attach the network-interceptor diagnostics.
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt",
    """import com.nikhil.yt.playback.audio.AudioNetworkDiagnosticDataSource\nimport com.nikhil.yt.playback.audio.CapsuleAudioRequestInterceptor\n""",
    """import com.nikhil.yt.playback.audio.AudioNetworkDiagnosticDataSource\nimport com.nikhil.yt.playback.audio.AudioCdnConnectionDiagnosticInterceptor\nimport com.nikhil.yt.playback.audio.AudioCdnOpenContext\nimport com.nikhil.yt.playback.audio.AudioCdnOpenSource\nimport com.nikhil.yt.playback.audio.audioCdnInitialSettleDelayMs\nimport com.nikhil.yt.playback.audio.CapsuleAudioRequestInterceptor\n""",
)
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt",
    """import com.nikhil.yt.utils.StreamClientUtils\nimport com.nikhil.yt.utils.SyncUtils\n""",
    """import com.nikhil.yt.utils.StreamClientUtils\nimport com.nikhil.yt.utils.SyncUtils\nimport com.nikhil.yt.utils.GlobalLog\n""",
)
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt",
    """    private fun awaitAudioNetworkOpenPermit(dataSpec: androidx.media3.datasource.DataSpec) {\n        val mediaId =\n            dataSpec.key\n                ?.let(AudioCacheIdentity::mediaId)\n                ?.trim()\n                ?.takeIf { it.isNotBlank() }\n                ?: return\n\n        try {\n            runBlocking {\n                audioResolveStability.awaitNetworkOpenStable {\n                    withContext(Dispatchers.Main.immediate) {\n                        mediaId == player.currentMediaItem?.mediaId ||\n                            mediaId in upcomingAudioIds()\n                    }\n                }\n            }\n        } catch (cancelled: kotlinx.coroutines.CancellationException) {\n            throw InterruptedIOException(\"Stale AUDIO CDN open suppressed before request\").apply {\n                initCause(cancelled)\n            }\n        } catch (interrupted: InterruptedException) {\n            throw InterruptedIOException(\"AUDIO CDN open interrupted before request\").apply {\n                initCause(interrupted)\n            }\n        }\n    }\n""",
    """    private fun awaitAudioNetworkOpenPermit(dataSpec: androidx.media3.datasource.DataSpec) {\n        val mediaId =\n            dataSpec.key\n                ?.let(AudioCacheIdentity::mediaId)\n                ?.trim()\n                ?.takeIf { it.isNotBlank() }\n                ?: return\n\n        try {\n            runBlocking {\n                suspend fun isRelevant(): Boolean =\n                    withContext(Dispatchers.Main.immediate) {\n                        mediaId == player.currentMediaItem?.mediaId ||\n                            mediaId in upcomingAudioIds()\n                    }\n\n                audioResolveStability.awaitNetworkOpenStable(::isRelevant)\n\n                val openContext = dataSpec.customData as? AudioCdnOpenContext\n                val nowElapsedMs = android.os.SystemClock.elapsedRealtime()\n                val ageMs =\n                    openContext\n                        ?.resolvedAtElapsedMs\n                        ?.takeIf { it > 0L && nowElapsedMs >= it }\n                        ?.let { nowElapsedMs - it }\n                        ?: -1L\n                val settleMs =\n                    openContext?.let { context ->\n                        audioCdnInitialSettleDelayMs(\n                            nowElapsedMs = nowElapsedMs,\n                            resolvedAtElapsedMs = context.resolvedAtElapsedMs,\n                        )\n                    } ?: 0L\n\n                if (GlobalLog.isEnabled && openContext != null) {\n                    val queryNames = runCatching { dataSpec.uri.queryParameterNames }.getOrDefault(emptySet())\n                    val headerNames = dataSpec.httpRequestHeaders.keys\n                    Timber.tag(\"AudioCDN\").d(\n                        \"cdn-open-gate id=%s source=%s ageMs=%d settleMs=%d client=%s pot=%s n=%s sig=%s expire=%s ua=%s origin=%s referer=%s\",\n                        mediaId,\n                        openContext.source,\n                        ageMs,\n                        settleMs,\n                        openContext.streamClient ?: \"unknown\",\n                        \"pot\" in queryNames,\n                        \"n\" in queryNames,\n                        \"sig\" in queryNames || \"signature\" in queryNames || \"lsig\" in queryNames,\n                        \"expire\" in queryNames,\n                        headerNames.any { it.equals(\"User-Agent\", ignoreCase = true) },\n                        headerNames.any { it.equals(\"Origin\", ignoreCase = true) },\n                        headerNames.any { it.equals(\"Referer\", ignoreCase = true) },\n                    )\n                }\n\n                if (settleMs > 0L) {\n                    delay(settleMs)\n                    if (!isRelevant()) {\n                        throw kotlinx.coroutines.CancellationException(\"Track changed during AUDIO CDN settle window\")\n                    }\n                }\n            }\n        } catch (cancelled: kotlinx.coroutines.CancellationException) {\n            throw InterruptedIOException(\"Stale AUDIO CDN open suppressed before request\").apply {\n                initCause(cancelled)\n            }\n        } catch (interrupted: InterruptedException) {\n            throw InterruptedIOException(\"AUDIO CDN open interrupted before request\").apply {\n                initCause(interrupted)\n            }\n        }\n    }\n""",
)
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt",
    """                .retryOnConnectionFailure(true)\n                .addInterceptor(CapsuleAudioRequestInterceptor(guardStreams = true))\n                .build()\n""",
    """                .retryOnConnectionFailure(true)\n                .addInterceptor(CapsuleAudioRequestInterceptor(guardStreams = true))\n                .addNetworkInterceptor(AudioCdnConnectionDiagnosticInterceptor())\n                .build()\n""",
)
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt",
    """                playbackUrlCache.get(mediaId)?.let { cached ->\n                    songMetadataRecoveryCoordinator.schedule(mediaId, cached)\n                    return@ResolvingDataSource resolvedAudioDataSpec(dataSpec, cached, contract)\n                }\n""",
    """                playbackUrlCache.get(mediaId)?.let { cached ->\n                    songMetadataRecoveryCoordinator.schedule(mediaId, cached)\n                    return@ResolvingDataSource resolvedAudioDataSpec(\n                        dataSpec = dataSpec,\n                        playback = cached,\n                        contract = contract,\n                        source = AudioCdnOpenSource.CACHED,\n                    )\n                }\n""",
)
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt",
    """                songMetadataRecoveryCoordinator.schedule(mediaId, playbackData)\n                return@ResolvingDataSource resolvedAudioDataSpec(dataSpec, playbackData, contract)\n            }\n        }\n    }\n\n    private fun resolvedAudioDataSpec(\n        dataSpec: androidx.media3.datasource.DataSpec,\n        playback: CapsuleAudioEngine.PlaybackData,\n        contract: AudioStreamContract,\n    ): androidx.media3.datasource.DataSpec {\n        val key = AudioCacheIdentity.key(requireNotNull(dataSpec.key), playback)\n        contract.bind(key)\n        AudioCacheIdentity.setLength(playerCache, key, playback.format.contentLength)\n        return dataSpec.buildUpon()\n            .setKey(key)\n            .setUri(playback.streamUrl.toUri())\n            .setHttpRequestHeaders(dataSpec.httpRequestHeaders + playback.streamHeaders)\n            .build()\n    }\n""",
    """                songMetadataRecoveryCoordinator.schedule(mediaId, playbackData)\n                return@ResolvingDataSource resolvedAudioDataSpec(\n                    dataSpec = dataSpec,\n                    playback = playbackData,\n                    contract = contract,\n                    source =\n                        if (alreadyRunning) AudioCdnOpenSource.JOINED_INFLIGHT\n                        else AudioCdnOpenSource.ON_DEMAND,\n                )\n            }\n        }\n    }\n\n    private fun resolvedAudioDataSpec(\n        dataSpec: androidx.media3.datasource.DataSpec,\n        playback: CapsuleAudioEngine.PlaybackData,\n        contract: AudioStreamContract,\n        source: AudioCdnOpenSource,\n    ): androidx.media3.datasource.DataSpec {\n        val mediaId = requireNotNull(dataSpec.key)\n        val key = AudioCacheIdentity.key(mediaId, playback)\n        contract.bind(key)\n        AudioCacheIdentity.setLength(playerCache, key, playback.format.contentLength)\n        return dataSpec.buildUpon()\n            .setKey(key)\n            .setUri(playback.streamUrl.toUri())\n            .setCustomData(\n                AudioCdnOpenContext(\n                    mediaId = mediaId,\n                    resolvedAtElapsedMs = playback.resolvedAtElapsedMs,\n                    source = source,\n                    streamClient = playback.streamClient,\n                ),\n            )\n            .setHttpRequestHeaders(dataSpec.httpRequestHeaders + playback.streamHeaders)\n            .build()\n    }\n""",
)

# 6) Log the actual Media3 retry decision so the field test can prove 250/1000 ms is active.
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/CapsuleLoadErrorHandlingPolicy.kt",
    """import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy\nimport androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy\n""",
    """import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy\nimport androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy\nimport com.nikhil.yt.utils.GlobalLog\nimport timber.log.Timber\n""",
)
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/CapsuleLoadErrorHandlingPolicy.kt",
    """        val capsuleDecision =\n            audioCdnRejectedRetryDelayMs(\n                cacheKey =\n                    audioCdnRetryCacheKey(\n                        outerCacheKey = loadErrorInfo.loadEventInfo.dataSpec.key,\n                        resolvedFailureCacheKey = httpFailure?.dataSpec?.key,\n                    ),\n                httpStatusCode = httpFailure?.responseCode,\n                errorCount = loadErrorInfo.errorCount,\n            )\n        return capsuleDecision ?: super.getRetryDelayMsFor(loadErrorInfo)\n""",
    """        val resolvedKey =\n            audioCdnRetryCacheKey(\n                outerCacheKey = loadErrorInfo.loadEventInfo.dataSpec.key,\n                resolvedFailureCacheKey = httpFailure?.dataSpec?.key,\n            )\n        val capsuleDecision =\n            audioCdnRejectedRetryDelayMs(\n                cacheKey = resolvedKey,\n                httpStatusCode = httpFailure?.responseCode,\n                errorCount = loadErrorInfo.errorCount,\n            )\n        if (GlobalLog.isEnabled && httpFailure != null && capsuleDecision != null) {\n            Timber.tag(\"AudioCDN\").d(\n                \"cdn-retry-policy status=%d errorCount=%d delayMs=%d stop=%s id=%s\",\n                httpFailure.responseCode,\n                loadErrorInfo.errorCount,\n                if (capsuleDecision == C.TIME_UNSET) -1L else capsuleDecision,\n                capsuleDecision == C.TIME_UNSET,\n                resolvedKey?.take(64) ?: \"none\",\n            )\n        }\n        return capsuleDecision ?: super.getRetryDelayMsFor(loadErrorInfo)\n""",
)

# 7) Unit coverage for the deterministic parts of the new first-open policy.
test_path = ROOT / "app/src/test/kotlin/com/nikhil/yt/playback/audio/AudioCdnOpenContextTest.kt"
test_path.write_text(
    '''package com.nikhil.yt.playback.audio\n\nimport org.junit.Assert.assertEquals\nimport org.junit.Assert.assertFalse\nimport org.junit.Assert.assertTrue\nimport org.junit.Test\n\nclass AudioCdnOpenContextTest {\n    @Test\n    fun freshResolvedUrlFinishesSmallSettleWindow() {\n        assertEquals(250L, audioCdnInitialSettleDelayMs(nowElapsedMs = 1_000L, resolvedAtElapsedMs = 1_000L))\n        assertEquals(150L, audioCdnInitialSettleDelayMs(nowElapsedMs = 1_100L, resolvedAtElapsedMs = 1_000L))\n        assertEquals(0L, audioCdnInitialSettleDelayMs(nowElapsedMs = 1_250L, resolvedAtElapsedMs = 1_000L))\n        assertEquals(0L, audioCdnInitialSettleDelayMs(nowElapsedMs = 5_000L, resolvedAtElapsedMs = 0L))\n    }\n\n    @Test\n    fun onlyHttp2CrossHostRouteCountsAsCoalesced() {\n        assertTrue(audioCdnCrossHostCoalesced("rr2.googlevideo.com", "rr1.googlevideo.com", "h2"))\n        assertFalse(audioCdnCrossHostCoalesced("rr2.googlevideo.com", "rr2.googlevideo.com", "h2"))\n        assertFalse(audioCdnCrossHostCoalesced("rr2.googlevideo.com", "rr1.googlevideo.com", "http/1.1"))\n        assertFalse(audioCdnCrossHostCoalesced("rr2.googlevideo.com", null, "h2"))\n    }\n}\n''',
    encoding="utf-8",
)

print("step43 CDN first-open hardening applied")
