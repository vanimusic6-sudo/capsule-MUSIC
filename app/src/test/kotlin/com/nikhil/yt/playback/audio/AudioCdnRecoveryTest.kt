@file:Suppress("UnsafeOptInUsageError")

package com.nikhil.yt.playback.audio

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import com.nikhil.yt.playback.CapsuleLoadErrorHandlingPolicy
import com.nikhil.yt.utils.NetworkFailureKind
import com.nikhil.yt.utils.isTransientClosedTlsHandshake
import com.nikhil.yt.utils.networkFailureKind
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import javax.net.ssl.SSLHandshakeException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioCdnRecoveryTest {
    private val host = "rr1---sn-test.googlevideo.com"
    private val spec = DataSpec.Builder().setUri("https://$host/videoplayback")
        .setKey("capsule:audio:song:251:1024").setLength(1024).build()

    private class Upstream : DataSource {
        var openFailure: Throwable? = null
        var readFailure: IOException? = null
        var readResult = C.RESULT_END_OF_INPUT
        var actualUri: Uri? = null
        val opens = mutableListOf<DataSpec>()
        var closes = 0
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long {
            opens += dataSpec
            openFailure?.let { throw it }
            return dataSpec.length
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            readFailure?.let { throw it }
            return readResult
        }
        override fun getUri(): Uri? = actualUri ?: opens.lastOrNull()?.uri
        override fun close() { closes += 1 }
    }

    private fun retryDelay(failure: IOException, count: Int = 1): Long =
        CapsuleLoadErrorHandlingPolicy().getRetryDelayMsFor(
            LoadErrorInfo(
                // Real load events retain the outer media-id key, not the CDN cache key.
                LoadEventInfo(1L, DataSpec.Builder().setUri("song").setKey("song").build(), 0L),
                MediaLoadData(C.DATA_TYPE_MEDIA), failure, count,
            ),
        )

    @Test
    fun peerClosedTlsHandshakeRetriesTheSameStreamWithoutCondemningMultipleCdnGroups() {
        // Actual production trace: HttpDataSourceException -> IOException ->
        // ExecutionException -> SSLHandshakeException("connection closed").
        val wrapped = IOException(
            "OkHttp failed",
            ExecutionException(SSLHandshakeException("connection closed")),
        )
        val health = AudioCdnHostHealth(now = { 0L })
        val upstream = Upstream().apply { openFailure = wrapped }
        val source = AudioCdnHostHealthDataSource(upstream, health)
        repeat(4) {
            assertSame(wrapped, assertThrows(IOException::class.java) { source.open(spec) })
            source.close()
        }
        // A route-wide failed TLS handshake cannot prove the entire host group is dead.
        repeat(2) { assertFalse(health.shouldSkipHost(host)) }
        assertTrue(wrapped.isTransientClosedTlsHandshake())
        assertNull(wrapped.audioCdnRefreshRequiredOrNull())
        assertFalse(wrapped.requiresFreshAudioUrlFor("song"))
        assertEquals(NetworkFailureKind.CONNECTION, wrapped.networkFailureKind())
        val playbackFailure = PlaybackException(
            "Source error",
            wrapped,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        )
        assertEquals(NetworkFailureKind.CONNECTION, playbackFailure.networkFailureKind())
        assertNotEquals(C.TIME_UNSET, retryDelay(wrapped))
    }

    @Test
    fun certificateValidationFailureMustNotBeRetriedAsTransientPeerClosure() {
        val certificateError = SSLHandshakeException("Trust anchor for certification path not found")
        assertFalse(certificateError.isTransientClosedTlsHandshake())
        assertNull(certificateError.networkFailureKind())
    }

    @Test
    fun cancellationsNeverMakeAHostCold() {
        listOf(
            IOException("Canceled"),
            IOException("Media3 wrapper", InterruptedIOException()),
            IOException("wrapper", CancellationException("selection changed")),
            CancellationException("stale before network open"),
        ).forEach { cancellation ->
            val health = AudioCdnHostHealth(now = { 0L })
            val upstream = Upstream().apply { openFailure = cancellation }
            val source = AudioCdnHostHealthDataSource(upstream, health)
            repeat(4) {
                assertSame(cancellation, runCatching { source.open(spec) }.exceptionOrNull())
                source.close()
            }
            // A cold group allows one probe, so checking only once would miss this regression.
            repeat(2) { assertFalse(health.shouldSkipHost(host)) }
        }
    }

    @Test
    fun onlyRealNoByteOpenTimeoutMakesCdnGroupSuspect() {
        val health = AudioCdnHostHealth(now = { 0L })
        val upstream = Upstream().apply {
            openFailure = IOException(
                "upstream wrapper",
                java.util.concurrent.ExecutionException(SocketTimeoutException("Read timed out")),
            )
        }
        val source = AudioCdnHostHealthDataSource(upstream, health)
        assertThrows(AudioCdnRefreshRequiredException::class.java) { source.open(spec) }
        source.close()
        assertTrue(health.isUnresponsiveHost(host))
        assertFalse(health.isUnresponsiveHost("rr1---sn-other.googlevideo.com"))

        // Any real body bytes from the same group restore its original budget.
        upstream.openFailure = null
        upstream.readResult = 4
        source.open(spec)
        assertEquals(4, source.read(ByteArray(4), 0, 4))
        source.close()
        assertFalse(health.isUnresponsiveHost(host))
    }

    @Test
    fun cancellationAndMidstreamTimeoutDoNotMarkAnUnresponsiveOpen() {
        val health = AudioCdnHostHealth(now = { 0L })
        val upstream = Upstream().apply { openFailure = IOException("Canceled") }
        val source = AudioCdnHostHealthDataSource(upstream, health)
        assertThrows(IOException::class.java) { source.open(spec) }
        source.close()
        assertFalse(health.isUnresponsiveHost(host))

        upstream.openFailure = null
        upstream.readFailure = SocketTimeoutException("midstream read timed out")
        source.open(spec)
        assertThrows(AudioCdnRefreshRequiredException::class.java) {
            source.read(ByteArray(4), 0, 4)
        }
        source.close()
        assertFalse(health.isUnresponsiveHost(host))
    }

    @Test
    fun twoTimeoutsAndOneCancellationAreOnlyTwoHostFailures() {
        val health = AudioCdnHostHealth(now = { 0L })
        val upstream = Upstream().apply { openFailure = IOException("wrapped", SocketTimeoutException()) }
        val source = AudioCdnHostHealthDataSource(upstream, health)
        repeat(2) {
            assertThrows(AudioCdnRefreshRequiredException::class.java) { source.open(spec) }
            source.close()
        }
        upstream.openFailure = IOException("Canceled")
        assertThrows(IOException::class.java) { source.open(spec) }
        repeat(2) { assertFalse(health.shouldSkipHost(host)) }
    }

    @Test
    fun aColdHostStopsTheLoadBeforeAnyNetworkOpen() {
        val health = AudioCdnHostHealth(now = { 0L })
        repeat(3) { health.recordFailure(host) }
        assertFalse(health.shouldSkipHost(host)) // consume the permitted probe
        val upstream = Upstream()
        val source = AudioCdnHostHealthDataSource(upstream, health)
        val failure = assertThrows(AudioCdnRefreshRequiredException::class.java) { source.open(spec) }
        assertEquals(AudioCdnRefreshReason.HOST_COOLDOWN, failure.refreshReason)
        assertTrue(upstream.opens.isEmpty())
        assertEquals(C.TIME_UNSET, retryDelay(IOException("cache wrapper", failure)))
        assertTrue(failure.requiresFreshAudioUrlFor("song"))
        assertFalse(failure.requiresFreshAudioUrlFor("different-song"))
        assertFalse(failure.requiresFreshAudioUrlFor(null))
    }

    @Test
    fun aCdnTimeoutEscapesTheChunkAndResolverLayersWithoutRetryingTheOldUrl() {
        val upstream = Upstream().apply { openFailure = SocketTimeoutException("TLS handshake") }
        val health = AudioCdnHostHealth(now = { 0L })
        var url = spec.uri
        val source = ResolvingDataSource(
            AudioChunkedDataSource(AudioCdnHostHealthDataSource(upstream, health)),
        ) { outer -> outer.buildUpon().setUri(url).setKey(spec.key).setLength(1024).build() }
        val outer = DataSpec.Builder().setUri("song").setKey("song").build()
        val failure = assertThrows(IOException::class.java) { source.open(outer) }
        assertEquals(1, upstream.opens.size)
        assertEquals(C.TIME_UNSET, retryDelay(failure))
        val playerFailure = PlaybackException("source failed", failure, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertTrue(playerFailure.requiresFreshAudioUrlFor("song"))
        assertEquals(AudioCdnRefreshReason.OPEN_FAILURE, failure.audioCdnRefreshRequiredOrNull()?.refreshReason)

        source.close()
        // Service recovery replaces the cached URL, while keeping the encoded-audio cache identity.
        url = Uri.parse("https://rr2---sn-other.googlevideo.com/videoplayback")
        upstream.openFailure = null
        assertEquals(1024L, source.open(outer))
        assertEquals(listOf(spec.uri, url), upstream.opens.map { it.uri })
        assertEquals(listOf(spec.key, spec.key), upstream.opens.map { it.key })
        source.close()
    }

    @Test
    fun onlyNoByteTimeoutsAtCdnOpenSkipSameUrlReconnect() {
        val timeout = IOException(
            "OkHttp wrapper",
            java.util.concurrent.ExecutionException(
                SocketTimeoutException("TLS handshake read timed out"),
            ),
        )
        val open = AudioCdnRefreshRequiredException(
            "song",
            AudioCdnRefreshReason.OPEN_FAILURE,
            timeout,
        )
        assertTrue(open.isUnresponsiveCdnOpenFor("song"))
        assertFalse(open.isUnresponsiveCdnOpenFor("other-song"))
        assertFalse(open.isUnresponsiveCdnOpenFor(null))
        assertFalse(
            AudioCdnRefreshRequiredException(
                "song", AudioCdnRefreshReason.READ_FAILURE, timeout,
            ).isUnresponsiveCdnOpenFor("song"),
        )
        assertFalse(
            AudioCdnRefreshRequiredException(
                "song", AudioCdnRefreshReason.HOST_COOLDOWN, timeout,
            ).isUnresponsiveCdnOpenFor("song"),
        )
        assertFalse(
            AudioCdnRefreshRequiredException(
                "song", AudioCdnRefreshReason.OPEN_FAILURE,
                IOException("Canceled"),
            ).isUnresponsiveCdnOpenFor("song"),
        )
        assertFalse(
            AudioCdnRefreshRequiredException(
                "song", AudioCdnRefreshReason.OPEN_FAILURE,
                IOException("a short-lived reset", java.net.SocketException("reset")),
            ).isUnresponsiveCdnOpenFor("song"),
        )
    }

    @Test
    fun aStalledBodyCanRefreshWithoutTurningCancellationIntoAnError() {
        val upstream = Upstream().apply { readFailure = SocketTimeoutException("body stalled") }
        val source = AudioCdnHostHealthDataSource(upstream, AudioCdnHostHealth())
        source.open(spec)
        val failure = assertThrows(AudioCdnRefreshRequiredException::class.java) { source.read(ByteArray(8), 0, 8) }
        assertEquals(AudioCdnRefreshReason.READ_FAILURE, failure.refreshReason)
        assertEquals(C.TIME_UNSET, retryDelay(failure))
        val cancelled = IOException("Canceled")
        upstream.readFailure = cancelled
        assertSame(cancelled, assertThrows(IOException::class.java) { source.read(ByteArray(8), 0, 8) })
        source.close()
    }

    @Test
    fun openingHeadersWithoutAudioBytesDoesNotResetADeadHostsFailureHistory() {
        val health = AudioCdnHostHealth(now = { 0L })
        repeat(2) { health.recordFailure(host) }
        val source = AudioCdnHostHealthDataSource(Upstream(), health)
        source.open(spec) // server returned 206, but has not actually delivered any audio
        source.close()
        health.recordFailure(host)
        assertFalse(health.shouldSkipHost(host)) // one permitted probe
        assertTrue(health.shouldSkipHost(host)) // still cold: open alone did not clear failures
    }

    @Test
    fun firstSuccessfulBodyBytesValidateTheServerThatActuallyServedAudio() {
        val health = AudioCdnHostHealth(now = { 0L })
        repeat(2) { health.recordFailure(host) }
        val upstream = Upstream().apply { readResult = 8 }
        val source = AudioCdnHostHealthDataSource(upstream, health)
        source.open(spec)
        assertEquals(8, source.read(ByteArray(8), 0, 8))
        source.close()
        // Two failures after an actual byte read must NOT amount to four cumulative failures.
        repeat(2) { health.recordFailure(host) }
        assertFalse(health.shouldSkipHost(host))
        assertFalse(health.shouldSkipHost(host))
    }

    @Test
    fun redirectedAudioCreditsTheActualServerInsteadOfTheOriginalSignedLinkHost() {
        val destination = "rr3---sn-other.googlevideo.com"
        val health = AudioCdnHostHealth(now = { 0L })
        repeat(2) { health.recordFailure(host); health.recordFailure(destination) }
        val upstream = Upstream().apply {
            readResult = 8
            actualUri = Uri.parse("https://$destination/videoplayback")
        }
        val source = AudioCdnHostHealthDataSource(upstream, health)
        source.open(spec)
        assertEquals(8, source.read(ByteArray(8), 0, 8))
        source.close()

        health.recordFailure(host) // still its third failure: only the destination served bytes
        assertFalse(health.shouldSkipHost(host)) // first cold probe
        assertTrue(health.shouldSkipHost(host))
        health.recordFailure(destination)
        health.recordFailure(destination)
        assertFalse(health.shouldSkipHost(destination))
        assertFalse(health.shouldSkipHost(destination))
    }

    @Test
    fun httpRefusalsAreLeftForTheExistingChunkAndRateLimitPolicies() {
        listOf(403, 410, 429).forEach { code ->
            val refusal = InvalidResponseCodeException(code, "refused", null, emptyMap(), spec, byteArrayOf())
            val upstream = Upstream().apply { openFailure = refusal }
            val health = AudioCdnHostHealth(now = { 0L })
            val source = AudioCdnHostHealthDataSource(upstream, health)
            assertSame(refusal, assertThrows(IOException::class.java) { source.open(spec) })
            assertFalse(refusal.isAudioCdnTransportFailure())
            source.close()
            // A repeatedly refused signed link must never mark its whole CDN group dead.
            // This used to happen for 403/410 after the chunk layer repeated position=0 three
            // times, suppressing a perfectly usable host for the next unrelated track.
            repeat(3) { runCatching { source.open(spec) }; source.close() }
            repeat(2) { assertFalse("HTTP $code condemned a healthy CDN group", health.shouldSkipHost(host)) }
        }
    }

    @Test
    fun programmerErrorsDoNotQuarantineTheNetwork() {
        val failure = IllegalStateException("already opened")
        val upstream = Upstream().apply { openFailure = failure }
        val health = AudioCdnHostHealth(now = { 0L })
        val source = AudioCdnHostHealthDataSource(upstream, health)
        repeat(4) { assertSame(failure, runCatching { source.open(spec) }.exceptionOrNull()); source.close() }
        repeat(2) { assertFalse(health.shouldSkipHost(host)) }
    }

    @Test
    fun unrelatedTransportErrorsKeepMedia3sNormalPolicy() {
        assertEquals(0L, retryDelay(SocketTimeoutException(), count = 1))
        assertEquals(1000L, retryDelay(SocketTimeoutException(), count = 2))
        val failure = SocketTimeoutException()
        val source = AudioCdnHostHealthDataSource(Upstream().apply { openFailure = failure }, AudioCdnHostHealth())
        val other = spec.buildUpon().setUri("https://example.invalid/audio").build()
        assertSame(failure, assertThrows(IOException::class.java) { source.open(other) })
    }
}
