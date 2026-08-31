/*
 * Capsule MUSIC
 *
 * Strict YouTube Music official-video matcher.
 *
 * Only the YouTube Music Videos surface and MUSIC_VIDEO_TYPE_OMV are accepted.
 * The matcher deliberately prefers "no video" over a wrong live/fan/visualizer
 * result. Positive and negative results are cached to avoid repeated searches.
 *
 * Scoring is delegated to [YouTubeMusicVideoMatcher] so the exact production
 * thresholds can be covered by unit tests without a network call.
 * GPL-3.0
 */

package com.nikhil.yt.innertube

import com.nikhil.yt.innertube.models.MusicResponsiveListItemRenderer
import com.nikhil.yt.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_OMV
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.nikhil.yt.innertube.models.response.SearchResponse
import io.ktor.client.call.body
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

data class YouTubeMusicVideoLink(
    val videoId: String,
    val musicVideoType: String,
    val title: String,
    val score: Int,
)

object YouTubeMusicVideoLinkResolver {
    private const val VIDEO_FILTER = "EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D"
    private const val POSITIVE_CACHE_MS = 24 * 60 * 60 * 1000L
    private const val NEGATIVE_CACHE_MS = 12 * 60 * 60 * 1000L
    private const val MAX_CACHE_ENTRIES = 600

    private data class CacheEntry(
        val link: YouTubeMusicVideoLink?,
        val expiresAtMs: Long,
    )

    private data class Candidate(
        val videoId: String,
        val title: String,
        val score: Int,
    )

    data class CacheRow(
        val sourceMediaId: String,
        val videoId: String?,
        val title: String?,
        val score: Int,
        val expiresAtMs: Long,
    )

    private val mutex = Mutex()
    private val innerTube = InnerTube()
    private val matchCache = ConcurrentHashMap<String, CacheEntry>()

    @Volatile
    var onCacheEntry: ((CacheRow) -> Unit)? = null

    suspend fun resolve(
        sourceMediaId: String,
        title: String,
        artists: List<String>,
        durationSeconds: Int?,
    ): Result<YouTubeMusicVideoLink> =
        runCatching {
            val sourceId = sourceMediaId.trim()
            val sourceTitle = title.trim()
            val cleanArtists =
                artists.map { it.trim() }.filter { it.isNotBlank() }

            require(sourceId.isNotBlank()) { "Missing YouTube Music track id" }
            require(sourceTitle.isNotBlank()) { "Missing song title" }

            cachedOrNull(sourceId)?.let { return@runCatching it }
            if (isNegativeCached(sourceId)) {
                throw IllegalStateException(
                    "Official music video is unavailable for this song",
                )
            }

            if (CapsuleVideoRequestGuard.isBlocked()) {
                throw CapsuleVideoRequestGuard.RequestBlockedException(
                    "YouTube VIDEO search paused for " +
                        "${CapsuleVideoRequestGuard.remainingBackoffMs() / 1000L}s",
                )
            }

            mutex.withLock {
                cachedOrNull(sourceId)?.let { return@withLock it }
                if (isNegativeCached(sourceId)) {
                    throw IllegalStateException(
                        "Official music video is unavailable for this song",
                    )
                }

                val sourceTitleNorm =
                    YouTubeMusicVideoMatcher.normalizeTitle(sourceTitle)
                val sourceArtistNorms =
                    cleanArtists
                        .map(YouTubeMusicVideoMatcher::normalizeText)
                        .filter(String::isNotBlank)
                        .distinct()

                val queries =
                    buildList {
                        val mainArtist = cleanArtists.firstOrNull()
                        if (mainArtist != null) {
                            add("$mainArtist $sourceTitle")
                            add("$mainArtist $sourceTitle official music video")
                        } else {
                            add(sourceTitle)
                            add("$sourceTitle official music video")
                        }
                    }.distinct()

                val candidatesById = linkedMapOf<String, Candidate>()

                for ((index, query) in queries.withIndex()) {
                    /*
                     * Only use the explicit fallback query if the natural query
                     * produced no usable official candidate at all.
                     */
                    if (index > 0 && candidatesById.isNotEmpty()) break

                    val renderers = searchVideoRenderers(query)

                    for (renderer in renderers) {
                        val candidate =
                            candidateFromRenderer(
                                renderer = renderer,
                                sourceMediaId = sourceId,
                                sourceTitle = sourceTitle,
                                sourceTitleNorm = sourceTitleNorm,
                                sourceArtistNorms = sourceArtistNorms,
                                sourceDurationSeconds = durationSeconds,
                            ) ?: continue

                        val previous = candidatesById[candidate.videoId]
                        if (previous == null || candidate.score > previous.score) {
                            candidatesById[candidate.videoId] = candidate
                        }
                    }

                    if (
                        candidatesById.values
                            .maxOfOrNull { it.score }
                            ?.let {
                                it >= YouTubeMusicVideoMatcher.STRONG_MATCH_SCORE
                            } == true
                    ) {
                        break
                    }
                }

                val best = candidatesById.values.maxByOrNull { it.score }
                if (best == null) {
                    store(
                        sourceId = sourceId,
                        entry =
                            CacheEntry(
                                link = null,
                                expiresAtMs =
                                    System.currentTimeMillis() + NEGATIVE_CACHE_MS,
                            ),
                    )
                    throw IllegalStateException(
                        "YouTube Music did not find a trustworthy official clip",
                    )
                }

                val link =
                    YouTubeMusicVideoLink(
                        videoId = best.videoId,
                        musicVideoType = MUSIC_VIDEO_TYPE_OMV,
                        title = best.title,
                        score = best.score,
                    )

                store(
                    sourceId = sourceId,
                    entry =
                        CacheEntry(
                            link = link,
                            expiresAtMs =
                                System.currentTimeMillis() + POSITIVE_CACHE_MS,
                        ),
                )

                link
            }
        }

    private suspend fun searchVideoRenderers(
        query: String,
    ): List<MusicResponsiveListItemRenderer> {
        CapsuleVideoRequestGuard.beforeMetadataRequest()

        val response =
            try {
                if (CapsuleAnonymousSession.enabled) {
                    CapsuleAnonymousSession.search(
                        query = query,
                        params = VIDEO_FILTER,
                        client = WEB_REMIX,
                    )
                } else {
                    syncSession()
                    innerTube
                        .search(
                            client = WEB_REMIX,
                            query = query,
                            params = VIDEO_FILTER,
                        )
                        .body<SearchResponse>()
                }
            } catch (blocked: CapsuleVideoRequestGuard.RequestBlockedException) {
                throw blocked
            } catch (throwable: Throwable) {
                val kind = CapsuleVideoRequestGuard.noteApiFailure(throwable)

                if (
                    kind == CapsuleVideoRequestGuard.FailureKind.RATE_LIMITED ||
                    kind == CapsuleVideoRequestGuard.FailureKind.BOT_CHECK ||
                    kind == CapsuleVideoRequestGuard.FailureKind.FORBIDDEN
                ) {
                    throw CapsuleVideoRequestGuard.RequestBlockedException(
                        "YouTube VIDEO search stopped after a ${kind.name.lowercase()} response",
                        throwable,
                    )
                }

                throw throwable
            }

        return response.contents
            ?.tabbedSearchResultsRenderer
            ?.tabs
            ?.firstOrNull()
            ?.tabRenderer
            ?.content
            ?.sectionListRenderer
            ?.contents
            .orEmpty()
            .flatMap { section ->
                section.musicShelfRenderer
                    ?.contents
                    .orEmpty()
                    .mapNotNull { it.musicResponsiveListItemRenderer }
            }
    }

    private fun candidateFromRenderer(
        renderer: MusicResponsiveListItemRenderer,
        sourceMediaId: String,
        sourceTitle: String,
        sourceTitleNorm: String,
        sourceArtistNorms: List<String>,
        sourceDurationSeconds: Int?,
    ): Candidate? {
        val endpoint =
            renderer.navigationEndpoint?.anyWatchEndpoint
                ?: renderer.overlay
                    ?.musicItemThumbnailOverlayRenderer
                    ?.content
                    ?.musicPlayButtonRenderer
                    ?.playNavigationEndpoint
                    ?.anyWatchEndpoint
                ?: return null

        val videoType =
            endpoint.watchEndpointMusicSupportedConfigs
                ?.watchEndpointMusicConfig
                ?.musicVideoType
                ?: return null

        if (videoType != MUSIC_VIDEO_TYPE_OMV) return null

        val videoId =
            endpoint.videoId
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: renderer.playlistItemData
                    ?.videoId
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: return null

        if (videoId == sourceMediaId) return null

        val candidateTitle =
            renderer.flexColumns
                .firstOrNull()
                ?.musicResponsiveListItemFlexColumnRenderer
                ?.text
                ?.runs
                ?.firstOrNull()
                ?.text
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return null

        val secondaryText =
            renderer.flexColumns
                .drop(1)
                .flatMap {
                    it.musicResponsiveListItemFlexColumnRenderer
                        .text
                        ?.runs
                        .orEmpty()
                }
                .joinToString(" ") { it.text }

        val score =
            YouTubeMusicVideoMatcher.scoreCandidate(
                sourceTitle = sourceTitle,
                sourceTitleNorm = sourceTitleNorm,
                sourceArtistNorms = sourceArtistNorms,
                candidateTitle = candidateTitle,
                secondaryText = secondaryText,
                sourceDurationSeconds = sourceDurationSeconds,
            ) ?: return null

        return Candidate(
            videoId = videoId,
            title = candidateTitle,
            score = score,
        )
    }

    private fun store(
        sourceId: String,
        entry: CacheEntry,
    ) {
        matchCache[sourceId] = entry
        pruneIfNeeded()

        onCacheEntry?.invoke(
            CacheRow(
                sourceMediaId = sourceId,
                videoId = entry.link?.videoId,
                title = entry.link?.title,
                score = entry.link?.score ?: 0,
                expiresAtMs = entry.expiresAtMs,
            ),
        )
    }

    private fun pruneIfNeeded() {
        if (matchCache.size <= MAX_CACHE_ENTRIES) return

        val now = System.currentTimeMillis()
        matchCache.entries.removeIf { it.value.expiresAtMs <= now }

        if (matchCache.size <= MAX_CACHE_ENTRIES) return

        matchCache.entries
            .sortedBy { it.value.expiresAtMs }
            .take(matchCache.size - MAX_CACHE_ENTRIES)
            .forEach { matchCache.remove(it.key) }
    }

    fun importCache(rows: Collection<CacheRow>) {
        val now = System.currentTimeMillis()

        for (row in rows) {
            if (row.expiresAtMs <= now) continue

            val link =
                row.videoId
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        YouTubeMusicVideoLink(
                            videoId = it,
                            musicVideoType = MUSIC_VIDEO_TYPE_OMV,
                            title = row.title.orEmpty(),
                            score = row.score,
                        )
                    }

            matchCache[row.sourceMediaId] =
                CacheEntry(link = link, expiresAtMs = row.expiresAtMs)
        }

        pruneIfNeeded()
    }

    fun exportCache(): List<CacheRow> {
        val now = System.currentTimeMillis()

        return matchCache
            .filterValues { it.expiresAtMs > now }
            .map { (id, entry) ->
                CacheRow(
                    sourceMediaId = id,
                    videoId = entry.link?.videoId,
                    title = entry.link?.title,
                    score = entry.link?.score ?: 0,
                    expiresAtMs = entry.expiresAtMs,
                )
            }
    }

    fun forget(sourceMediaId: String) {
        matchCache.remove(sourceMediaId.trim())
    }

    fun clearCache() {
        matchCache.clear()
    }

    private fun cachedOrNull(sourceId: String): YouTubeMusicVideoLink? {
        val entry = matchCache[sourceId] ?: return null
        if (entry.expiresAtMs <= System.currentTimeMillis()) {
            matchCache.remove(sourceId)
            return null
        }
        return entry.link
    }

    private fun isNegativeCached(sourceId: String): Boolean {
        val entry = matchCache[sourceId] ?: return false
        if (entry.expiresAtMs <= System.currentTimeMillis()) {
            matchCache.remove(sourceId)
            return false
        }
        return entry.link == null
    }

    private fun syncSession() {
        innerTube.locale = YouTube.locale
        innerTube.authState = YouTube.authState
        innerTube.useLoginForBrowse = YouTube.useLoginForBrowse

        val proxy = YouTube.proxy
        if (innerTube.proxy != proxy) {
            innerTube.proxy = proxy
        }
    }
}
