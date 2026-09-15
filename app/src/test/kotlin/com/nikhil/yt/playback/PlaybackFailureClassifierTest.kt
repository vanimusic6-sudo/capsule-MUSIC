package com.nikhil.yt.playback

import android.app.Application
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.metrolist.innertubex.extraction.StreamAttemptDiagnostic
import com.metrolist.innertubex.extraction.StreamDiagnostics
import com.metrolist.innertubex.extraction.StreamResolveException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class PlaybackFailureClassifierTest {
    private fun wrapped(cause: Throwable) = PlaybackException(
        "2000", IOException("Media3 source failed", cause), PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    )

    private fun classify(cause: Throwable, authenticated: Boolean = false) =
        PlaybackFailureClassifier.classify(wrapped(cause), authenticated)

    @Test fun typedAgeRestrictionSurvivesGenericMedia3Wrapper() {
        val cause = StreamResolveException(StreamResolveException.Reason.AGE_RESTRICTED, "No playable stream")
        assertEquals(PlaybackFailureKind.AUTH_REQUIRED, classify(cause))
        assertEquals(PlaybackFailureKind.AGE_RESTRICTED, classify(cause, authenticated = true))
    }

    @Test fun machineReadableLoginOutcomeSurvivesGenericExtractorMessage() {
        val cause = diagnosed("playability:LOGIN_REQUIRED")
        assertEquals(PlaybackFailureKind.AUTH_REQUIRED, classify(cause))
        assertEquals(PlaybackFailureKind.ACCESS_RESTRICTED, classify(cause, authenticated = true))
    }

    @Test fun ageVerificationStatusesAreRecognized() {
        for (status in listOf("AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED", "CONTENT_CHECK_REQUIRED")) {
            assertEquals(PlaybackFailureKind.AUTH_REQUIRED, classify(diagnosed("playability:$status")))
        }
    }

    @Test fun explicitAgeAndLoginReasonsAreRecognizedWithoutInventingRestriction() {
        assertEquals(PlaybackFailureKind.AUTH_REQUIRED, classify(IllegalStateException("Sign in to confirm your age")))
        assertEquals(PlaybackFailureKind.AUTH_REQUIRED, classify(IllegalStateException("Please sign in")))
        assertEquals(PlaybackFailureKind.GENERIC, classify(IllegalStateException("Video unavailable")))
        assertEquals(PlaybackFailureKind.GENERIC, classify(diagnosed("selection:LOGIN_REQUIRED")))
        assertEquals(PlaybackFailureKind.GENERIC, classify(diagnosed("po_token_unavailable")))
    }

    @Test fun botChallengeKeepsExistingBotErrorInsteadOfLoginPrompt() {
        val cause = StreamResolveException(
            StreamResolveException.Reason.NO_PLAYABLE_STREAM,
            "Sign in to confirm you're not a bot",
            diagnostics = diagnosed("playability:LOGIN_REQUIRED").diagnostics,
        )
        assertEquals(PlaybackFailureKind.BOT_CHECK, classify(cause))
    }

    @Test fun dnsConnectOfflineAndSocketFailuresAreNetworkErrorsInsideWrappers() {
        for (failure in listOf(
            UnknownHostException("DNS failed"), ConnectException("Connection refused"),
            NoRouteToHostException("Network is unreachable"), SocketException("Connection reset"),
            SocketTimeoutException("Read timeout"),
            ConnectTimeoutException("Connect timed out"),
            HttpRequestTimeoutException("https://youtube.test", 100),
        )) {
            assertEquals(failure.javaClass.name, PlaybackFailureKind.NETWORK, classify(failure))
        }
    }

    @Test fun media3NetworkCodesAreRecognizedEvenWhenOuterCodeIs2000() {
        for (code in listOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        )) {
            assertEquals(PlaybackFailureKind.NETWORK, classify(PlaybackException("offline", null, code)))
        }
    }

    @Test fun extractorRequestTimeoutCauseIsRecognized() = runTest {
        val failure = try {
            withTimeout(10) { delay(100) }
            error("expected timeout")
        } catch (timeout: CancellationException) { Exception("Player request exceeded its budget", timeout) }
        assertEquals(PlaybackFailureKind.NETWORK, classify(failure))
    }

    @Test fun unknownPlaybackExceptionAndPlainIoKeepGenericFallback() {
        assertEquals(PlaybackFailureKind.GENERIC, classify(IllegalArgumentException("decoder bug")))
        assertEquals(PlaybackFailureKind.GENERIC, classify(IOException("Disk failure")))
        // InnerTubeX also uses NETWORK for HTTP request errors. The enum alone is insufficient.
        assertEquals(PlaybackFailureKind.GENERIC, classify(StreamResolveException(
            StreamResolveException.Reason.NETWORK, "API error", IOException("unknown request failure"),
        )))
    }

    @Test fun cdn403And410AreNeitherAgeNorNetworkErrors() {
        for (status in listOf(403, 410)) {
            val failure = HttpDataSource.InvalidResponseCodeException(
                status, "CDN rejected signed URL", null, emptyMap(),
                DataSpec.Builder().setUri("https://test.googlevideo.com/videoplayback").build(), byteArrayOf(),
            )
            assertEquals(PlaybackFailureKind.GENERIC, classify(failure))
        }
    }

    @Test fun ktorHttpApiFailureIsNotNetworkOrAgeRestriction() = runTest {
        for (status in listOf(HttpStatusCode.Forbidden, HttpStatusCode.ServiceUnavailable)) {
            val client = HttpClient(MockEngine { respond("API failure", status) }) { expectSuccess = true }
            try {
                val failure = try {
                    client.get("https://youtube.test/player")
                    error("expected HTTP failure")
                } catch (failure: Exception) { failure }
                assertEquals(PlaybackFailureKind.GENERIC, classify(failure))
            } finally { client.close() }
        }
    }

    private fun diagnosed(outcome: String) = StreamResolveException(
        StreamResolveException.Reason.NO_PLAYABLE_STREAM,
        "No playable stream found for this track.",
        diagnostics = StreamDiagnostics(
            attempts = listOf(StreamAttemptDiagnostic("VISIONOS", null, "test", outcome)),
            usedAuthenticatedWatchPage = false,
        ),
    )
}
