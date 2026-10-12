package com.nikhil.yt.innertube.video

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

class CapsuleVideoRoutesTest {
    private val stream = CapsuleNewPipeResolved("abcdefghijk", "https://rr.googlevideo.com/video", null,
        "360p", 640, 360, 18, -1, Long.MAX_VALUE)

    @Test fun androidReelCanSucceedWithoutInitializingOrCallingNewPipe() {
        val calls = mutableListOf<CapsuleVideoRoute>()
        val result = resolveVideoRoutes { route ->
            calls += route
            when (route) {
                CapsuleVideoRoute.VISIONOS -> throw IOException("player unavailable")
                CapsuleVideoRoute.ANDROID_REEL -> stream
                CapsuleVideoRoute.NEWPIPE -> error("NewPipe must not be called")
            }
        }
        assertEquals(listOf(CapsuleVideoRoute.VISIONOS, CapsuleVideoRoute.ANDROID_REEL), calls)
        assertEquals("direct-android-reel", result.backend)
    }
    @Test fun recoveringVisionUsesDifferentEndpointBeforeRepeatingFailedBackend() {
        val calls = mutableListOf<CapsuleVideoRoute>()
        val result = resolveVideoRoutes("direct-visionos") { calls += it; stream }
        assertEquals(listOf(CapsuleVideoRoute.ANDROID_REEL), calls)
        assertEquals("direct-android-reel", result.backend)
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
    @Test fun healthyRouteFailingStillFallsBackAndRecoveryDeprioritizesIt() {
        val health = CapsuleVideoRouteHealth()
        health.success(CapsuleVideoRoute.ANDROID_REEL)
        assertEquals(CapsuleVideoRoute.VISIONOS, health.order("direct-android-reel").first())
        val calls = mutableListOf<CapsuleVideoRoute>()
        val next = resolveVideoRoutes(health = health) { route ->
            calls += route
            if (route == CapsuleVideoRoute.ANDROID_REEL) throw IOException("endpoint down")
            stream
        }
        assertEquals(listOf(CapsuleVideoRoute.ANDROID_REEL, CapsuleVideoRoute.VISIONOS), calls)
        assertEquals("direct-visionos", next.backend)
    }
}
