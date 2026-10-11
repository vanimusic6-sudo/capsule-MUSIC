/* Capsule MUSIC — hard HLS quality ceiling before any video segment is loaded. GPL-3.0. */
@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.playback.video

import androidx.media3.common.ParserException
import androidx.media3.common.StreamKey
import androidx.media3.exoplayer.hls.playlist.*
import androidx.media3.exoplayer.upstream.ParsingLoadable

internal class CapsuleHlsPlaylistParserFactory(private val maxHeight: Int) : HlsPlaylistParserFactory {
    private val delegate = DefaultHlsPlaylistParserFactory()
    init { require(maxHeight in 1..720) }

    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> =
        limited(delegate.createPlaylistParser(), requireMaster = true)

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist, previousMediaPlaylist: HlsMediaPlaylist?,
    ): ParsingLoadable.Parser<HlsPlaylist> =
        limited(delegate.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist), requireMaster = false)

    private fun limited(parser: ParsingLoadable.Parser<HlsPlaylist>, requireMaster: Boolean) =
        ParsingLoadable.Parser<HlsPlaylist> { uri, input ->
            val playlist = parser.parse(uri, input)
            if (playlist is HlsMultivariantPlaylist) {
                val keys = playlist.variants.mapIndexedNotNull { index, variant ->
                    if (variant.format.height in 1..maxHeight) StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_VARIANT, index) else null
                }.toMutableList()
                if (keys.isEmpty()) throw ParserException.createForMalformedManifest("VIDEO HLS has no rendition within ${maxHeight}p", null)
                // Keep the clip's original audio groups. Dropping them while
                // filtering video would silently create a video-only source.
                playlist.audios.indices.forEach { keys += StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_AUDIO, it) }
                playlist.subtitles.indices.forEach { keys += StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_SUBTITLE, it) }
                playlist.copy(keys)
            } else {
                if (requireMaster) throw ParserException.createForMalformedManifest("VIDEO HLS requires a master playlist to verify quality", null)
                playlist
            }
        }
}
