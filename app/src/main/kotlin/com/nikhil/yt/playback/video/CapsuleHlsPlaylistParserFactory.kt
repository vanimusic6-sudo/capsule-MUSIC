/* Capsule MUSIC — hard HLS quality ceiling before any video segment is loaded. GPL-3.0. */
@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.playback.video

import androidx.media3.common.ParserException
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
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
                val eligible = playlist.variants.indices.filter { playlist.variants[it].format.height in 1..maxHeight }
                if (eligible.isEmpty()) throw ParserException.createForMalformedManifest("VIDEO HLS has no rendition within ${maxHeight}p", null)
                // Prefer the widely supported AVC ladder. Preparing extra codec
                // families and unrelated/dubbed audio groups costs startup reads.
                val avc = eligible.filter { index ->
                    playlist.variants[index].format.codecs.orEmpty().split(',')
                        .any { MimeTypes.getMediaMimeType(it.trim()) == MimeTypes.VIDEO_H264 }
                }
                val codecs = avc.ifEmpty { eligible }
                // Keep the adaptive height ladder, but avoid its duplicate
                // 60fps encodings where the same height offers <=30fps.
                val selected = codecs.filter { index ->
                    val format = playlist.variants[index].format
                    format.frameRate <= 30f || codecs.none { other ->
                        val candidate = playlist.variants[other].format
                        candidate.height == format.height && candidate.frameRate in 1f..30f &&
                            playlist.variants[other].audioGroupId == playlist.variants[index].audioGroupId
                    }
                }
                val keys = selected.map { StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_VARIANT, it) }.toMutableList()
                val audioGroups = selected.mapNotNull { playlist.variants[it].audioGroupId }.toSet()
                playlist.audios.indices.filter { playlist.audios[it].groupId in audioGroups }
                    .groupBy { playlist.audios[it].groupId }.values.forEach { renditions ->
                        // Retain the advertised default soundtrack for every
                        // referenced group, rather than preparing every language.
                        val preferred = renditions.firstOrNull {
                            playlist.audios[it].format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0
                        } ?: renditions.first()
                        keys += StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_AUDIO, preferred)
                    }
                playlist.copy(keys)
            } else {
                if (requireMaster) throw ParserException.createForMalformedManifest("VIDEO HLS requires a master playlist to verify quality", null)
                playlist
            }
        }
}
