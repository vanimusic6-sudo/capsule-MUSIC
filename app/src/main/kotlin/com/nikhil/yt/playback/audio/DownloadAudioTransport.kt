/*
 * Capsule MUSIC — direct CDN transport for offline audio.
 * Licensed under GPL-3.0.
 */
package com.nikhil.yt.playback.audio

import androidx.media3.datasource.DataSource

/**
 * Downloads reuse the *validated Direct* half of the playback transport:
 * bounded ranges, adaptive long-form reads, one same-link socket resume and
 * per-CDN-host health. The caller supplies its own HTTP client/connection pool;
 * downloading must never consume foreground playback sockets.
 *
 * Important: a SABR session is not a signed direct URL. Do NOT insert SABR
 * UMP bytes into a ProgressiveDownloader's format-aware cache without
 * independent session renewal, stable byte-range mapping and resume tests.
 */
internal fun downloadAudioChunkSizeBytes(advertisedBytes: Long): Long =
    advertisedBytes.takeIf { it in 64L * 1024..4L * 1024 * 1024 }
        ?: AUDIO_CHUNK_BYTES

internal fun createDownloadAudioTransport(
    upstream: DataSource,
    health: AudioCdnHostHealth,
    advertisedChunkBytes: Long,
    currentNetworkIdentity: () -> Long?,
    shouldYieldToPlayback: () -> Boolean = DownloadPlaybackCoexistence.priority::shouldYield,
): DataSource =
    DownloadYieldingDataSource(
        upstream = AudioChunkedDataSource(
            upstream = AudioCdnHostHealthDataSource(upstream, health),
            chunkBytes = downloadAudioChunkSizeBytes(advertisedChunkBytes),
            currentNetworkIdentity = currentNetworkIdentity,
        ),
        shouldYield = shouldYieldToPlayback,
    )
