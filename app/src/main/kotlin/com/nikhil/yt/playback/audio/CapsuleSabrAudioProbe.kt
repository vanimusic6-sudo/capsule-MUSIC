package com.nikhil.yt.playback.audio

import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import com.metrolist.innertubex.sabr.SabrAudioStream
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrChunk
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout

/** Diagnostic contract only: this never writes to the player's byte-range cache. */
internal data class SabrAudioProbeSummary(
    val firstMediaBytes: Int,
    val hasInitialization: Boolean,
    val mediaRangeFollowsInitialization: Boolean,
    val codecItag: Int,
)

/**
 * Real SABR wire probe, available only through an explicit developer call.
 * InnerTubeX handles the HTTPS POST, UMP demux, selected representation and
 * protection statuses. Taking two chunks cancels the remaining response body.
 *
 * This is NOT a Media3 DataSource: a production adapter needs a timestamp-based
 * seek contract and persistent, format-identified segment/cache mapping. Never
 * expose the UMP envelope as progressive AAC/Opus or mark a download completed.
 */
@OptIn(ExperimentalSabrApi::class)
internal suspend fun probeSabrAudioWire(
    client: HttpClient,
    bootstrap: SabrBootstrap,
): SabrAudioProbeSummary {
    val chunks = withTimeout(SABR_PROBE_TIMEOUT_MS) {
        SabrAudioStream(client, bootstrap).chunks().take(2).toList()
    }
    return assessSabrAudioProbe(chunks, bootstrap.audioFormat.itag)
}

internal fun assessSabrAudioProbe(
    chunks: List<SabrChunk>,
    itag: Int,
): SabrAudioProbeSummary {
    val init = chunks.firstOrNull { it.isInitialization }
    val media = chunks.firstOrNull { !it.isInitialization }
    require(init != null && init.data.isNotEmpty()) { "SABR probe did not receive an init segment" }
    require(media != null && media.data.isNotEmpty()) { "SABR probe did not receive media bytes" }
    return SabrAudioProbeSummary(
        firstMediaBytes = media.data.size,
        hasInitialization = true,
        mediaRangeFollowsInitialization = media.startRange == init.endRangeExclusive,
        codecItag = itag,
    )
}

private const val SABR_PROBE_TIMEOUT_MS = 12_000L
