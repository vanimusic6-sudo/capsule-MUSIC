/*
 * Capsule MUSIC — validated hints from already browsed official artist pages.
 * GPL-3.0.
 */
package com.nikhil.yt.innertube

import com.nikhil.yt.innertube.models.SongItem
import com.nikhil.yt.innertube.pages.ArtistPage
import com.nikhil.yt.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_OMV

/**
 * The artist screen already downloads an official YouTube Music catalog. Use
 * its VIDEO rows instead of repeating global title searches for the same song.
 *
 * This is an opportunistic, memory-only hint, NOT a proof that a random upload
 * is official. An ATV/UGC entry, text/lyric variant or different artist cannot
 * win just because it happens to live on a public browse page.
 *
 * No network, image processing or background workers are started here.
 */
internal object YouTubeMusicArtistVideoHints {
    private const val TTL_MS = 30 * 60 * 1000L
    private const val MAX_ARTISTS = 24
    private const val MAX_ITEMS_PER_ARTIST = 100
    private val validId = Regex("""[A-Za-z0-9_-]{11}""")
    private val videoSection = Regex(
        """(?i)(?:\bvideos?\b|\bclips?\b|\bmusic\s*videos?\b|клипы|видео|відео|音樂視頻|音乐视频|뮤직비디오|ミュージックビデオ)""",
    )
    private data class CatalogRow(
        val videoId: String,
        val title: String,
        val artists: String,
        val omvVerified: Boolean,
        val durationSeconds: Int?,
    )
    private data class Snapshot(
        val browseId: String,
        val artistNorm: String,
        val createdMs: Long,
        val rows: List<CatalogRow>,
    )
    private val pages = object : LinkedHashMap<String, Snapshot>(MAX_ARTISTS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Snapshot>): Boolean =
            size > MAX_ARTISTS
    }

    internal fun clear() = synchronized(pages) { pages.clear() }

    fun remember(page: ArtistPage, nowMs: Long = System.currentTimeMillis()) {
        val title = YouTubeMusicVideoMatcher.normalizeText(page.artist.title)
        if (title.length < 2) return
        val browseId = page.artist.id.takeIf { it.length in 8..96 } ?: return
        val rows = page.sections.asSequence()
            .flatMap { section ->
                val explicitlyVideos = videoSection.containsMatchIn(section.title)
                section.items.asSequence().filterIsInstance<SongItem>().mapNotNull { song ->
                    val type = song.endpoint?.watchEndpointMusicSupportedConfigs
                        ?.watchEndpointMusicConfig?.musicVideoType
                    if ((type != null && type != MUSIC_VIDEO_TYPE_OMV) ||
                        (type == null && !explicitlyVideos) ||
                        !validId.matches(song.id)
                    ) return@mapNotNull null
                    CatalogRow(
                        videoId = song.id,
                        title = song.title.take(300),
                        artists = song.artists.joinToString(" ") { it.name }.take(300),
                        omvVerified = type == MUSIC_VIDEO_TYPE_OMV,
                        durationSeconds = song.duration,
                    )
                }
            }
            .distinctBy { it.videoId }
            .take(MAX_ITEMS_PER_ARTIST)
            .toList()
        if (rows.isEmpty()) return
        synchronized(pages) {
            pages[browseId] = Snapshot(browseId, title, nowMs, rows)
        }
    }

    fun match(
        sourceTitle: String,
        artists: List<String>,
        durationSeconds: Int?,
        sourceId: String,
        artistBrowseIds: List<String> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
    ): YouTubeMusicVideoLink? {
        val requestedArtists = artists.flatMap { raw ->
            listOf(raw) + raw.split(Regex("""(?i)\s+(?:feat\.?|ft\.?|featuring|with)\s+|[,;&]"""))
        }.map(YouTubeMusicVideoMatcher::normalizeText)
            .filter { it.length >= 2 }
            .toSet()
        if (requestedArtists.isEmpty()) return null
        val normalizedTitle = YouTubeMusicVideoMatcher.normalizeTitle(sourceTitle)
        if (normalizedTitle.isBlank()) return null
        val candidates = synchronized(pages) {
            val expired = pages.filterValues { nowMs - it.createdMs !in 0L..TTL_MS }.keys
            expired.forEach(pages::remove)
            pages.values.filter { snapshot ->
                snapshot.artistNorm in requestedArtists &&
                    (artistBrowseIds.isEmpty() || snapshot.browseId in artistBrowseIds)
            }.toList()
        }
        return candidates.asSequence().flatMap { it.rows.asSequence() }
            .mapNotNull { row ->
                val score = YouTubeMusicVideoMatcher.scoreCandidate(
                    sourceTitle = sourceTitle,
                    sourceTitleNorm = normalizedTitle,
                    sourceArtistNorms = requestedArtists.toList(),
                    candidateTitle = row.title,
                    secondaryText = row.artists.ifBlank { artists.joinToString(" ") } +
                        (row.durationSeconds?.let { " ${it / 60}:${(it % 60).toString().padStart(2, '0')}" } ?: ""),
                    sourceDurationSeconds = durationSeconds,
                    sameOfficialVideo = false,
                    // Verified OMV or provenance from a dedicated artist VIDEO shelf.
                    omvVerified = row.omvVerified,
                    fromOfficialArtistPage = true,
                ) ?: return@mapNotNull null
                if (row.videoId == sourceId) return@mapNotNull null
                YouTubeMusicVideoLink(row.videoId, MUSIC_VIDEO_TYPE_OMV, row.title, score +
                    if (row.omvVerified) 60 else 15)
            }.maxByOrNull { it.score }
    }
}
