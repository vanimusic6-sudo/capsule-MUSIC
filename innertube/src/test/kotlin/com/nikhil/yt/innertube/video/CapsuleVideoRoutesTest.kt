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
                else -> throw IOException("parser unavailable")
            }
        }
        assertEquals(listOf(CapsuleVideoRoute.ANDROID_VR, CapsuleVideoRoute.VISIONOS, CapsuleVideoRoute.PIPEPIPE, CapsuleVideoRoute.NEWPIPE, CapsuleVideoRoute.ANDROID_REEL), calls)
        assertEquals("direct-android-reel", result.backend)
    }
    @Test fun recoveringVisionUsesDifferentEndpointBeforeRepeatingFailedBackend() {
        val calls = mutableListOf<CapsuleVideoRoute>()
        val result = resolveVideoRoutes("direct-visionos") { calls += it; stream }
        assertEquals(listOf(CapsuleVideoRoute.ANDROID_VR), calls)
        assertEquals("direct-android-vr", result.backend)
    }
    @Test fun brokenNewPipeParserDoesNotPreventAnIndependentRecovery() {
        val result = resolveVideoRoutes("newpipe") { route ->
            if (route == CapsuleVideoRoute.NEWPIPE) error("Broken parser") else stream
        }
        assertEquals("direct-android-vr", result.backend)
    }
    @Test fun rateLimitStopsTheChainButOneBotVerdictDoesNotBanEveryRoute() {
        var calls = 0
        val limited = runCatching { resolveVideoRoutes { calls++; throw IOException("HTTP 429") } }
        assertEquals(1, calls)
        assertEquals(CapsuleNewPipeFailure.RATE_LIMITED, CapsuleNewPipeExtractor.classify(limited.exceptionOrNull()!!))
        val blocked = runCatching { resolveVideoRoutes { route ->
            if (route == CapsuleVideoRoute.VISIONOS) throw IOException("Confirm you are not a bot")
            throw IOException("network down")
        } }
        assertEquals(CapsuleNewPipeFailure.NETWORK, CapsuleNewPipeExtractor.classify(blocked.exceptionOrNull()!!))
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
        health.failure(CapsuleVideoRoute.ANDROID_VR)
        health.success(CapsuleVideoRoute.NEWPIPE)
        assertEquals(CapsuleVideoRoute.VISIONOS, health.order(null).first())
        now = 46_000_000_000L
        assertEquals(CapsuleVideoRoute.ANDROID_VR, health.order(null).first())
        assertEquals(CapsuleVideoRoute.VISIONOS, health.order("direct-android-vr").first())
    }
    @Test fun missingFormatOnOneClipDoesNotCooldownTheFastRouteForOtherClips() {
        val health = CapsuleVideoRouteHealth()
        val first = resolveVideoRoutes(health = health) { if (it == CapsuleVideoRoute.ANDROID_VR) null else stream }
        assertEquals("direct-visionos", first.backend)
        val calls = mutableListOf<CapsuleVideoRoute>()
        val next = resolveVideoRoutes(health = health) { calls += it; stream }
        assertEquals(listOf(CapsuleVideoRoute.ANDROID_VR), calls)
        assertEquals("direct-android-vr", next.backend)
    }

    @Test fun cdnPlaybackFailurePreventsRepeatingTheBrokenRouteOnTheNextClip() {
        var now = 0L
        val health = CapsuleVideoRouteHealth { now }
        val recovered = resolveVideoRoutes("direct-android-vr", health) { stream }
        assertEquals("direct-visionos", recovered.backend)
        assertEquals(CapsuleVideoRoute.VISIONOS, health.order(null).first())
        now = 46_000_000_000L
        assertEquals(CapsuleVideoRoute.ANDROID_VR, health.order(null).first())
    }
    @Test fun globalBotCooldownRequiresEveryRouteToConfirmTheBlock() {
        val failure = runCatching { resolveVideoRoutes { throw IOException("Confirm you are not a bot") } }.exceptionOrNull()!!
        assertEquals(CapsuleNewPipeFailure.BOT_BLOCKED, CapsuleNewPipeExtractor.classify(failure))
        val mixed = runCatching { resolveVideoRoutes {
            if (it == CapsuleVideoRoute.NEWPIPE) throw IOException("Confirm you are not a bot")
            null
        } }.exceptionOrNull()!!
        assertEquals(CapsuleNewPipeFailure.EXTRACTOR, CapsuleNewPipeExtractor.classify(mixed))
    }

    @Test fun incompatibleParserDoesNotKillRemainingFallbacks() {
        val routes = mutableListOf<CapsuleVideoRoute>()
        val stream = resolveVideoRoutes {
            routes += it
            if (it == CapsuleVideoRoute.PIPEPIPE) throw VerifyError("bad Android class")
            if (it == CapsuleVideoRoute.NEWPIPE) this.stream else null
        }
        assertEquals("newpipe", stream.backend)
        assertTrue(routes.contains(CapsuleVideoRoute.PIPEPIPE))
    }

    @Test fun directBotFailureStillAllowsOtherBackendsAndNoFormatDoesNotPenalizeNextClip() {
        val health = CapsuleVideoRouteHealth()
        val result = resolveVideoRoutes(health = health) {
            when (it) {
                CapsuleVideoRoute.ANDROID_VR -> throw CapsuleDirectVideoException(CapsuleNewPipeFailure.BOT_BLOCKED, "PLAYER_LOGIN_REQUIRED:BOT_CHECK")
                CapsuleVideoRoute.VISIONOS -> throw CapsuleDirectVideoException(CapsuleNewPipeFailure.EXTRACTOR, "NO_COMPATIBLE_STREAM", penalizeRoute = false)
                else -> stream
            }
        }
        assertEquals("pipepipe", result.backend)
        assertEquals(CapsuleVideoRoute.VISIONOS, health.order(null).first())
    }

}
