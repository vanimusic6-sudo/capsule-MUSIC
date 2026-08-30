/*
 * Capsule MUSIC
 *
 * Strict YouTube Music official-video matcher.
 *
 * Only the YouTube Music Videos surface and MUSIC_VIDEO_TYPE_OMV are accepted.
 * The matcher deliberately prefers "no video" over a wrong live/fan/visualizer
 * result. Positive and negative results are cached to avoid repeated searches.
 *
 * v2 changes:
 *  - The second, more explicit query only runs when the first one produced no
 *    usable candidate at all. Previously it ran whenever the best score was
 *    below STRONG_MATCH_SCORE, which doubled search traffic on most tracks.
 *  - Negative cache raised from 2 minutes to 12 hours. A track that has no
 *    official clip today will not have one in two minutes either, and the old
 *    value meant a browsing session re-searched the same misses constantly.
 *  - The cache is bounded and can be exported/imported, so it survives restarts
 *    instead of forcing a cold re-search of the whole library.
 *  - Scoring, normalisation and rejection lists are unchanged.
 *
 * v3 change:
 *  - The matching search runs through [CapsuleAnonymousSession] by default, so
 *    no account cookie is attached to VIDEO lookups. The authenticated
 *    instance is kept only as an explicit fallback.
 *
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
import kotlin.math.abs

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

    private const val STRONG_MATCH_SCORE = 225

    /** Hard ceiling on in-memory entries; oldest expiring entries are dropped. */
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

    /**
     * Flat row used for persistence. Store these in Room/DataStore and feed
     * them back through [importCache] on startup.
     */
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

    /**
     * Called whenever an entry is added, so the app can persist it without this
     * object knowing about Android storage. Must not block.
     */
    @Volatile
    var onCacheEntry: ((CacheRow) -> Unit)? = null

    suspend fun resolve(
        sourceMediaId: String,
        title: String,
        artists: List<String>,
        durationSeconds: Int?,
    ): Result<YouTubeMusicVideoLink> = runCatching {
        val sourceId = sourceMediaId.trim()
        val sourceTitle = title.trim()
        val cleanArtists = artists.map { it.trim() }.filter { it.isNotBlank() }

        require(sourceId.isNotBlank()) { "Missing YouTube Music track id" }
        require(sourceTitle.isNotBlank()) { "Missing song title" }

        cachedOrNull(sourceId)?.let { cached ->
            return@runCatching cached
        }
        if (isNegativeCached(sourceId)) {
            throw IllegalStateException("Official music video is unavailable for this song")
        }

        // Fail fast instead of queueing behind the mutex while the breaker is open.
        if (CapsuleVideoRequestGuard.isBlocked()) {
            throw CapsuleVideoRequestGuard.RequestBlockedException(
                "YouTube VIDEO search paused for " +
                    "${CapsuleVideoRequestGuard.remainingBackoffMs() / 1000L}s",
            )
        }

        mutex.withLock {
            cachedOrNull(sourceId)?.let { return@withLock it }
            if (isNegativeCached(sourceId)) {
                throw IllegalStateException("Official music video is unavailable for this song")
            }

            val sourceTitleNorm = normalizeTitle(sourceTitle)
            val sourceArtistNorms =
                cleanArtists
                    .map(::normalizeText)
                    .filter(String::isNotBlank)
                    .distinct()

            val queries =
                buildList {
                    val mainArtist = cleanArtists.firstOrNull()

                    /*
                     * The YouTube Music "Videos" filter already narrows the
                     * surface. Start with the natural song query; the second,
                     * more explicit query is a last resort and only runs when
                     * the first returned nothing usable at all.
                     */
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
                 * Only escalate to the fallback query when the first pass found
                 * no candidate whatsoever. A weak-but-valid match is still a
                 * match, and it is not worth a second search request.
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
                        ?.let { it >= STRONG_MATCH_SCORE } == true
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
                            expiresAtMs = System.currentTimeMillis() + NEGATIVE_CACHE_MS,
                        ),
                )
                throw IllegalStateException("YouTube Music did not find a trustworthy official clip")
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
                        expiresAtMs = System.currentTimeMillis() + POSITIVE_CACHE_MS,
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
                ?: renderer.playlistItemData?.videoId?.trim()?.takeIf { it.isNotBlank() }
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

        if (isRejectedVariant(sourceTitle, candidateTitle)) return null

        val candidateTitleNorm = normalizeTitle(candidateTitle)
        if (candidateTitleNorm.isBlank() || sourceTitleNorm.isBlank()) return null

        val allSecondaryText =
            renderer.flexColumns
                .drop(1)
                .flatMap {
                    it.musicResponsiveListItemFlexColumnRenderer
                        .text
                        ?.runs
                        .orEmpty()
                }
                .joinToString(" ") { it.text }

        val secondaryNorm = normalizeText(allSecondaryText)

        val overlap =
            titleTokenOverlap(
                sourceTitleNorm,
                candidateTitleNorm,
            )

        var score = 0

        /*
         * Real official clips are frequently titled as:
         *   Artist - Song (Official Video)
         *   Song | Official Music Video
         *   Song (feat. ...)
         * so exact equality is preferred but no longer mandatory.
         */
        score +=
            when {
                candidateTitleNorm == sourceTitleNorm -> 150
                candidateTitleNorm.contains(sourceTitleNorm) ||
                    sourceTitleNorm.contains(candidateTitleNorm) -> 105
                overlap >= 0.78 -> 88
                overlap >= 0.62 -> 67
                overlap >= 0.50 -> 48
                else -> return null
            }

        if (sourceArtistNorms.isNotEmpty()) {
            val artistMatched =
                sourceArtistNorms.any { artist ->
                    artist.length >= 2 &&
                        (
                            containsWholePhrase(secondaryNorm, artist) ||
                                containsWholePhrase(candidateTitleNorm, artist) ||
                                secondaryNorm.contains(artist)
                        )
                }

            /*
             * Artist metadata in the Videos shelf is not always normalized the
             * same way as the audio item. A strong title + OMV candidate may
             * still pass, but missing artist evidence receives a heavy penalty.
             */
            if (artistMatched) {
                score += 65
            } else {
                score -= 55
            }
        }

        // MUSIC_VIDEO_TYPE_OMV itself is strong evidence.
        score += 55

        val rawTitleNorm = normalizeText(candidateTitle)
        if (
            rawTitleNorm.contains("official music video") ||
            rawTitleNorm.contains("official video")
        ) {
            score += 28
        }

        val candidateDuration = extractDurationSeconds(allSecondaryText)
        val sourceDuration = sourceDurationSeconds?.takeIf { it > 0 }

        if (sourceDuration != null && candidateDuration != null) {
            val delta = abs(sourceDuration - candidateDuration)
            score +=
                when {
                    delta <= 5 -> 42
                    delta <= 12 -> 34
                    delta <= 25 -> 22
                    delta <= 45 -> 10
                    delta <= 75 -> -8
                    else -> return null
                }
        }

        /*
         * This threshold rejects weak same-artist matches, while retaining the
         * normal official clips that v3 found successfully.
         */
        if (score < 115) return null

        return Candidate(
            videoId = videoId,
            title = candidateTitle,
            score = score,
        )
    }

    private fun isRejectedVariant(
        sourceTitle: String,
        candidateTitle: String,
    ): Boolean {
        val source = " ${normalizeText(sourceTitle)} "
        val candidate = " ${normalizeText(candidateTitle)} "

        val contextualRejects =
            listOf(
                " live ",
                " concert ",
                " performance ",
                " session ",
                " acoustic ",
                " cover ",
                " karaoke ",
                " lyric ",
                " lyrics ",
                " visualizer ",
                " animated video ",
                " dance video ",
                " dance practice ",
                " slowed ",
                " reverb ",
                " sped up ",
                " nightcore ",
                " remix ",
                " edit ",
                " fanmade ",
                " fan made ",
                " amv ",
                " reaction ",
                " interview ",
                " behind the scenes ",
                " making of ",
                " teaser ",
                " trailer ",
                " vertical video ",
                " shorts ",
                " challenge ",
            )

        if (
            contextualRejects.any { token ->
                candidate.contains(token) && !source.contains(token)
            }
        ) {
            return true
        }

        val alwaysReject =
            listOf(
                " official audio ",
                " audio only ",
                " topic audio ",
            )

        return alwaysReject.any { token -> candidate.contains(token) }
    }

    private fun normalizeTitle(value: String): String =
        normalizeText(value)
            .replace(Regex("\\bofficial\\s+music\\s+video\\b"), " ")
            .replace(Regex("\\bofficial\\s+video\\b"), " ")
            .replace(Regex("\\bmusic\\s+video\\b"), " ")
            .replace(Regex("\\bofficial\\b"), " ")
            .replace(Regex("\\bremaster(?:ed)?(?:\\s+\\d{4})?\\b"), " ")
            .replace(Regex("\\balbum\\s+version\\b"), " ")
            .replace(Regex("\\bsingle\\s+version\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun normalizeText(value: String): String =
        value
            .lowercase()
            .replace('&', ' ')
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun titleTokenOverlap(
        left: String,
        right: String,
    ): Double {
        val a =
            left.split(' ')
                .filter { it.length > 1 }
                .toSet()
        val b =
            right.split(' ')
                .filter { it.length > 1 }
                .toSet()

        if (a.isEmpty() || b.isEmpty()) return 0.0

        return a.intersect(b).size.toDouble() /
            a.union(b).size.toDouble()
    }

    private fun containsWholePhrase(
        haystack: String,
        needle: String,
    ): Boolean =
        " $haystack ".contains(" $needle ") || haystack == needle

    private fun extractDurationSeconds(text: String): Int? {
        val match =
            Regex("""(?<!\d)(\d{1,2}):(\d{2})(?::(\d{2}))?(?!\d)""")
                .find(text)
                ?: return null

        val first = match.groupValues[1].toIntOrNull() ?: return null
        val second = match.groupValues[2].toIntOrNull() ?: return null
        val third = match.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.toIntOrNull()

        return if (third == null) {
            first * 60 + second
        } else {
            first * 3600 + second * 60 + third
        }
    }

    // ---------------------------------------------------------------------
    // Cache
    // ---------------------------------------------------------------------

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

    /** Restores previously persisted rows. Expired rows are ignored. */
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

    /** Drops a single track's verdict, e.g. after a user "wrong video" report. */
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

    /**
     * Only used when [CapsuleAnonymousSession.enabled] is false. This is the
     * path that attaches the signed-in account to VIDEO lookups.
     */
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
