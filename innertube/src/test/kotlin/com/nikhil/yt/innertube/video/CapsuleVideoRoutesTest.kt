package com.nikhil.yt.innertube.video

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

class CapsuleVideoRoutesTest {
    private val stream = CapsuleNewPipeResolved("abcdefghijk", "https://rr.googlevideo.com/video", null,
        "360p", 640, 360, 18, -1, Long.MAX_VALUE)

    @Test fun segmentedRoutesPrecedeTheProgressiveReelFallback() {
        val calls = mutableListOf<CapsuleVideoRoute>()
        val result = resolveVideoRoutes { route ->
            calls += route
            when (route) {
                CapsuleVideoRoute.VISIONOS -> throw IOException("player unavailable")
                CapsuleVideoRoute.ANDROID_REEL -> stream
                CapsuleVideoRoute.NEWPIPE -> throw IOException("parser unavailable")
            }
        }
        assertEquals(listOf(CapsuleVideoRoute.VISIONOS, CapsuleVideoRoute.NEWPIPE, CapsuleVideoRoute.ANDROID_REEL), calls)
        assertEquals("direct-android-reel", result.backend)
    }
    @Test fun recoveringVisionUsesDifferentEndpointBeforeRepeatingFailedBackend() {
        val calls = mutableListOf<CapsuleVideoRoute>()
        val result = resolveVideoRoutes("direct-visionos") { calls += it; stream }
        assertEquals(listOf(CapsuleVideoRoute.NEWPIPE), calls)
        assertEquals("newpipe", result.backend)
    }
    @Test fun brokenNewPipeParserDoesNotPreventAnIndependentRecovery() {
        val result = resolveVideoRoutes("newpipe") { route ->
            if (route == CapsuleVideoRoute.NEWPIPE) error("Broken parser") else stream
        }
        assertEquals("direct-visionos", result.backend)
    }
    @Test fun rateLimitStopsTheChainAndBotVerdictSurvivesLaterNetworkFailure() {
        var calls = 0
        val limited = runCatching { resolveVideoRoutes { calls++; throw IOException("HTTP 429") } }
        assertEquals(1, calls)
        assertEquals(CapsuleNewPipeFailure.RATE_LIMITED, CapsuleNewPipeExtractor.classify(limited.exceptionOrNull()!!))
        val blocked = runCatching { resolveVideoRoutes { route ->
            if (route == CapsuleVideoRoute.VISIONOS) throw IOException("Confirm you are not a bot")
            throw IOException("network down")
        } }
        assertEquals(CapsuleNewPipeFailure.BOT_BLOCKED, CapsuleNewPipeExtractor.classify(blocked.exceptionOrNull()!!))
    }
    @Test fun requestCancellationCannotAdvanceToAnotherRoute() {
        val id = UUID.randomUUID().toString()
        var calls = 0
        val result = runCatching { VideoExtractionRequests.withRequest(id, 5_000L) {
            resolveVideoRoutes { calls++; VideoExtractionRequests.cancel(id); throw IOException("cancelled") }
        } }
        assertTrue(result.isFailure)
        assertEquals(1, calls)
    }
    @Test fun nextClipStartsWithSuccessfulRouteWithoutRepeatingDeadEndpoints() {
        val health = CapsuleVideoRouteHealth()
        resolveVideoRoutes(health = health) { route ->
            if (route != CapsuleVideoRoute.NEWPIPE) throw IOException("endpoint down")
            stream
        }
        val calls = mutableListOf<CapsuleVideoRoute>()
        val next = resolveVideoRoutes(health = health) { calls += it; stream }
        assertEquals(listOf(CapsuleVideoRoute.NEWPIPE), calls)
        assertEquals("newpipe", next.backend)
    }
    @Test fun fallbackSuccessDoesNotStickAfterTheFastEndpointRecovers() {
        var now = 0L
        val health = CapsuleVideoRouteHealth { now }
        health.failure(CapsuleVideoRoute.VISIONOS)
        health.success(CapsuleVideoRoute.NEWPIPE)
        assertEquals(CapsuleVideoRoute.NEWPIPE, health.order(null).first())
        now = 46_000_000_000L
        assertEquals(CapsuleVideoRoute.VISIONOS, health.order(null).first())
        assertEquals(CapsuleVideoRoute.NEWPIPE, health.order("direct-visionos").first())
    }
    @Test fun missingFormatOnOneClipDoesNotCooldownTheFastRouteForOtherClips() {
        val health = CapsuleVideoRouteHealth()
        val first = resolveVideoRoutes(health = health) { if (it == CapsuleVideoRoute.VISIONOS) null else stream }
        assertEquals("newpipe", first.backend)
        val calls = mutableListOf<CapsuleVideoRoute>()
        val next = resolveVideoRoutes(health = health) { calls += it; stream }
        assertEquals(listOf(CapsuleVideoRoute.VISIONOS), calls)
        assertEquals("direct-visionos", next.backend)
    }
}
