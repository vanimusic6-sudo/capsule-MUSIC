package com.nikhil.yt.playback.audio

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AudioExtractorBoundaryTest {
    @Test fun incompatibleLibraryBecomesAClientAttemptFailure() = runBlocking {
        val linkage = NoSuchMethodError("missing extractor runtime API")
        val error = runCatching { audioExtractorAttempt<Unit> { throw linkage } }.exceptionOrNull()
        assertTrue(error is IOException)
        assertSame(linkage, error?.cause)
        // The normal client-local failure classifier can advance the existing bounded plan.
        assertTrue(CapsuleAudioFallbackPolicy.canFallbackAfter(CapsulePlaybackSafety.classifyFailure(requireNotNull(error))))
    }
    @Test fun parentCancellationKeepsItsOriginalIdentity() = runBlocking {
        val cancellation = CancellationException("obsolete track")
        val error = runCatching { audioExtractorAttempt<Unit> { throw cancellation } }.exceptionOrNull()
        assertSame(cancellation, error)
    }
    @Test fun vmFailureDoesNotTriggerMoreExtractorAttempts() = runBlocking {
        val fatal = OutOfMemoryError("simulated")
        assertSame(fatal, runCatching { audioExtractorAttempt<Unit> { throw fatal } }.exceptionOrNull())
    }
    @Test fun successfulContractsAndOrdinaryFailuresArePassedThrough() = runBlocking {
        val contract = Any()
        assertSame(contract, audioExtractorAttempt { contract })
        val network = IOException("request failed")
        assertSame(network, runCatching { audioExtractorAttempt<Unit> { throw network } }.exceptionOrNull())
    }
}
