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
    @Test fun coldVideoPrefersBoundedDirectVisionAndRemembersSuccessfulRoute() {
        val health = CapsuleVideoRouteHealth()
        assertEquals(CapsuleVideoRoute.VISIONOS, health.order(null).first())
        health.success(CapsuleVideoRoute.VISIONOS)
        assertEquals(CapsuleVideoRoute.VISIONOS, health.order(null).first())
    }

    @Test fun restoredHealthyRouteSkipsPreviouslyDeadColdRoutes() {
        val health = CapsuleVideoRouteHealth()
        health.restorePreferredRoute("newpipe")
        assertEquals(CapsuleVideoRoute.NEWPIPE, health.order(null).first())
        val tried = mutableListOf<CapsuleVideoRoute>()
        val selected = resolveVideoRoutes(health = health) { route ->
            tried += route
            stream
        }
        assertEquals(listOf(CapsuleVideoRoute.NEWPIPE), tried)
        assertEquals("newpipe", selected.backend)

        // Never trust an unknown saved name or revive a currently blocked route.
        val fresh = CapsuleVideoRouteHealth()
        fresh.restorePreferredRoute("untrusted-route")
        assertEquals(CapsuleVideoRoute.VISIONOS, fresh.order(null).first())
        health.failure(CapsuleVideoRoute.NEWPIPE, CapsuleNewPipeFailure.BOT_BLOCKED)
        assertFalse(CapsuleVideoRoute.NEWPIPE in health.candidates(null))
    }

    @Test fun coldOrderedRoutesTryMaintainedExtractorBeforeOldPipePipeAdapter() {
        val ordered = CapsuleVideoRouteHealth().order(null)
        assertEquals(CapsuleVideoRoute.VISIONOS, ordered[0])
        assertEquals(CapsuleVideoRoute.ANDROID_VR, ordered[1])
        assertTrue(ordered.indexOf(CapsuleVideoRoute.NEWPIPE) <
            ordered.indexOf(CapsuleVideoRoute.PIPEPIPE))
        assertEquals(CapsuleVideoRoute.ANDROID_REEL, ordered.last())
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
    @Test fun lastWorkingRouteStaysPreferredEvenAfterEarlierFailuresExpire() {
        var now = 0L
        val health = CapsuleVideoRouteHealth { now }
        health.failure(CapsuleVideoRoute.ANDROID_VR)
        health.success(CapsuleVideoRoute.NEWPIPE)
        assertEquals(CapsuleVideoRoute.NEWPIPE, health.order(null).first())
        now = 46_000_000_000L
        // Temporary 45s route failure does not discard a backend that worked.
        assertEquals(CapsuleVideoRoute.NEWPIPE, health.order(null).first())
        assertEquals(CapsuleVideoRoute.NEWPIPE, health.order("direct-android-vr").first())
    }
    @Test fun missingFormatOnOneClipDoesNotCooldownTheFastRouteForOtherClips() {
        val health = CapsuleVideoRouteHealth()
        val first = resolveVideoRoutes(health = health) { if (it == CapsuleVideoRoute.VISIONOS) null else stream }
        assertEquals("direct-android-vr", first.backend)
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
        assertEquals(CapsuleVideoRoute.VISIONOS, health.order(null).first())
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
        val failures = mutableListOf<CapsuleNewPipeFailure>()
        val stream = resolveVideoRoutes(onFailure = { _, failure -> failures += CapsuleNewPipeExtractor.classify(failure) }) {
            routes += it
            if (it == CapsuleVideoRoute.PIPEPIPE) throw VerifyError("bad Android class")
            if (it == CapsuleVideoRoute.NEWPIPE) this.stream else null
        }
        assertEquals("newpipe", stream.backend)
        assertTrue(routes.contains(CapsuleVideoRoute.PIPEPIPE))
        assertEquals(listOf(CapsuleNewPipeFailure.EXTRACTOR), failures)
    }

    @Test fun directBotFailureStillAllowsOtherBackendsAndNoFormatDoesNotPenalizeNextClip() {
        val health = CapsuleVideoRouteHealth()
        val result = resolveVideoRoutes(health = health) {
            when (it) {
                CapsuleVideoRoute.PIPEPIPE,
                CapsuleVideoRoute.ANDROID_VR -> throw CapsuleDirectVideoException(CapsuleNewPipeFailure.BOT_BLOCKED, "PLAYER_LOGIN_REQUIRED:BOT_CHECK")
                CapsuleVideoRoute.VISIONOS -> throw CapsuleDirectVideoException(CapsuleNewPipeFailure.EXTRACTOR, "NO_COMPATIBLE_STREAM", penalizeRoute = false)
                else -> stream
            }
        }
        assertEquals("newpipe", result.backend)
        assertEquals(CapsuleVideoRoute.NEWPIPE, health.candidates(null).first())
    }

    @Test fun recentBotBlockedRoutesAreSkippedWhenAnotherBackendJustWorked() {
        var now = 0L
        val health = CapsuleVideoRouteHealth { now }
        val first = resolveVideoRoutes(health = health) { route ->
            when (route) {
                CapsuleVideoRoute.PIPEPIPE, CapsuleVideoRoute.ANDROID_VR ->
                    throw CapsuleDirectVideoException(
                        CapsuleNewPipeFailure.BOT_BLOCKED, "PLAYER_LOGIN_REQUIRED:BOT_CHECK")
                CapsuleVideoRoute.VISIONOS -> stream
                else -> error("Should use first healthy direct route")
            }
        }
        assertEquals("direct-visionos", first.backend)
        val attempted = mutableListOf<CapsuleVideoRoute>()
        val next = resolveVideoRoutes(health = health) { route ->
            attempted += route
            stream
        }
        assertEquals("direct-visionos", next.backend)
        assertEquals(listOf(CapsuleVideoRoute.VISIONOS), attempted)
        now = java.util.concurrent.TimeUnit.MINUTES.toNanos(11)
        assertEquals(0, health.pausedBotCount())
    }

    @Test fun anAllBotVerdictIncludesRecentConfirmedBlocksWithoutReprobes() {
        val health = CapsuleVideoRouteHealth()
        health.failure(CapsuleVideoRoute.PIPEPIPE, CapsuleNewPipeFailure.BOT_BLOCKED)
        health.failure(CapsuleVideoRoute.ANDROID_VR, CapsuleNewPipeFailure.BOT_BLOCKED)
        resolveVideoRoutes(health = health) { route ->
            when (route) {
                CapsuleVideoRoute.PIPEPIPE, CapsuleVideoRoute.ANDROID_VR ->
                    throw CapsuleDirectVideoException(
                        CapsuleNewPipeFailure.BOT_BLOCKED, "PLAYER_LOGIN_REQUIRED:BOT_CHECK")
                CapsuleVideoRoute.VISIONOS -> stream
                else -> error("should have succeeded")
            }
        }
        val attempts = mutableListOf<CapsuleVideoRoute>()
        val e = runCatching {
            resolveVideoRoutes(health = health) { route ->
                attempts += route
                throw IOException("Confirm you're not a bot")
            }
        }.exceptionOrNull()!!
        assertEquals(CapsuleNewPipeFailure.BOT_BLOCKED, CapsuleNewPipeExtractor.classify(e))
        assertEquals(listOf(CapsuleVideoRoute.VISIONOS, CapsuleVideoRoute.NEWPIPE,
            CapsuleVideoRoute.ANDROID_REEL), attempts)
    }

    @Test fun repeatedVideoTapsDoNotProbeANetworkFailedRouteDuringCooldown() {
        var now = 0L
        val health = CapsuleVideoRouteHealth { now }
        CapsuleVideoRoute.entries.forEach { health.failure(it, CapsuleNewPipeFailure.NETWORK) }
        var calls = 0
        repeat(4) {
            val error = runCatching { resolveVideoRoutes(health = health) { calls++; stream } }.exceptionOrNull()!!
            assertEquals(CapsuleNewPipeFailure.NETWORK, CapsuleNewPipeExtractor.classify(error))
        }
        assertEquals(0, calls)
        now = java.util.concurrent.TimeUnit.SECONDS.toNanos(6)
        assertEquals("direct-visionos", resolveVideoRoutes(health = health) { calls++; stream }.backend)
        assertEquals(1, calls)
    }

    @Test fun twoDifferentBackendsFailingDnsStopTheExtractorCascadeWithoutPoisoningRoutes() {
        val health = CapsuleVideoRouteHealth()
        val attempts = mutableListOf<CapsuleVideoRoute>()
        val thrown = runCatching {
            resolveVideoRoutes(health = health) { route ->
                attempts += route
                throw java.net.UnknownHostException(
                    if (route == CapsuleVideoRoute.VISIONOS) "www.youtube.com"
                    else "youtubei.googleapis.com"
                )
            }
        }.exceptionOrNull()!!
        assertEquals(CapsuleNewPipeFailure.NETWORK, CapsuleNewPipeExtractor.classify(thrown))
        assertEquals(2, attempts.size)
        assertEquals(CapsuleVideoRoute.VISIONOS, health.candidates(null).first())
    }

    @Test fun oneBackendDnsFailureMustNotPreventAnotherBackendFromSucceeding() {
        val health = CapsuleVideoRouteHealth()
        val attempts = mutableListOf<CapsuleVideoRoute>()
        val result = resolveVideoRoutes(health = health) { route ->
            attempts += route
            if (attempts.size == 1) throw java.net.UnknownHostException("www.youtube.com")
            stream
        }
        assertEquals(2, attempts.size)
        assertEquals("direct-android-vr", result.backend)
    }

    @Test fun anInvalidResponseDoesNotMarkTheRouteHealthyOrBlockItsReserve() {
        val health = CapsuleVideoRouteHealth()
        val failed = mutableListOf<CapsuleVideoRoute>()
        val result = resolveVideoRoutes(health = health, onFailure = { route, _ -> failed += route }) { route ->
            if (route == CapsuleVideoRoute.VISIONOS) stream.copy(videoUrl = "https://172.16.0.1/private") else stream
        }
        assertEquals(listOf(CapsuleVideoRoute.VISIONOS), failed)
        assertEquals("direct-android-vr", result.backend)
        assertFalse(CapsuleVideoRoute.VISIONOS in health.candidates(null))
    }

    @Test fun wrongTrackExpiredUrlAndBrokenDashEachAdvanceToTheReserve() {
        val rejected = listOf(
            stream.copy(videoId = "otherclip12"), stream.copy(expiresAtMs = 0),
            stream.copy(delivery = CapsuleVideoDelivery.DASH, dashManifest = "broken"),
            stream.copy(userAgent = "browser\r\nAuthorization: secret"),
        )
        for (bad in rejected) {
            val calls = mutableListOf<CapsuleVideoRoute>()
            val result = resolveVideoRoutes(expectedVideoId = stream.videoId) { route ->
                calls += route
                if (calls.size == 1) bad else stream
            }
            assertEquals(2, calls.size)
            assertEquals(stream.videoUrl, result.videoUrl)
            assertEquals("direct-visionos", result.backend)
        }
    }

}
