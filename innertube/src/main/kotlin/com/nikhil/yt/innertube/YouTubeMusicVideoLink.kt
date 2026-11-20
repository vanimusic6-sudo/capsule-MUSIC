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
import com.nikhil.yt.innertube.models.response.NextResponse
import com.nikhil.yt.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_OMV
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.nikhil.yt.innertube.models.response.SearchResponse
import io.ktor.client.call.body
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

data class YouTubeMusicVideoLink(
    val videoId: String,
    val musicVideoType: String,
    val title: String,
    val score: Int,
)

/**
 * YouTube Music marks the audio and VIDEO versions as counterparts in /next.
 * This is stronger evidence than an artist/title search and must never accept
 * a queue neighbor or an ATV/UGC result as the requested song's VIDEO.
 */
internal fun firstPartyOfficialVideoCounterpart(
    sourceMediaId: String,
    primaryId: String?,
    counterpartId: String?,
    counterpartType: String?,
): String? {
    if (primaryId != sourceMediaId) return null
    val candidate = counterpartId?.trim() ?: return null
    if (!candidate.matches(Regex("[A-Za-z0-9_-]{11}")) || candidate == sourceMediaId) return null
    // An untyped /next counterpart may be a lyric/visualizer upload. Unlike an
    // explicitly typed OMV, its ID alone does not prove a real music video.
    if (counterpartType != MUSIC_VIDEO_TYPE_OMV) return null
    return candidate
}

/**
 * Never assume a watch-next tab's first queue row belongs to our source.
 * The radio may contain multiple songs and multiple audio/video wrappers.
 */
internal fun nextOfficialVideoForSong(response: NextResponse, sourceId: String): String? {
    val entries = response.contents
        .singleColumnMusicWatchNextResultsRenderer
        ?.tabbedRenderer
        ?.watchNextTabbedResultsRenderer
        ?.tabs
        .orEmpty()
        .flatMap { tab ->
            tab.tabRenderer.content
                ?.musicQueueRenderer
                ?.content
                ?.playlistPanelRenderer
                ?.contents
                .orEmpty()
        }
    for (entry in entries) {
        val wrapper = entry.playlistPanelVideoWrapperRenderer ?: continue
        val primary = wrapper.primaryRenderer?.playlistPanelVideoRenderer
        for (counterpart in wrapper.counterpart.orEmpty()) {
            val video = counterpart.counterpartRenderer?.playlistPanelVideoRenderer ?: continue
            val selected = firstPartyOfficialVideoCounterpart(
                sourceMediaId = sourceId,
                primaryId = primary?.videoId,
                counterpartId = video.videoId,
                counterpartType = video.navigationEndpoint?.anyWatchEndpoint
                    ?.watchEndpointMusicSupportedConfigs?.watchEndpointMusicConfig?.musicVideoType,
            )
            if (selected != null) return selected
        }
    }
    return null
}

object YouTubeMusicVideoLinkResolver {
    private const val VIDEO_FILTER = "EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D"
    private const val POSITIVE_CACHE_MS = 2 * 60 * 60 * 1000L
    private const val FIRST_PARTY_TIMEOUT_MS = 4_000L
    private const val SEARCH_TIMEOUT_MS = 6_000L
    private const val ARTIST_CATALOG_BUDGET_MS = 1_800L
    private val artistBrowseId = Regex("""(?:UC|MPLAUC)[A-Za-z0-9_-]{14,80}""")
    // One natural Videos query can overlap a slow /next lookup. Do not
    // launch it if /next returns quickly, and never hedge authenticated
    // InnerTube's shared mutable session.
    private const val ANONYMOUS_SEARCH_HEDGE_MS = 700L
    private const val NEGATIVE_CACHE_MS = 30 * 1000L
    private const val MAX_CACHE_ENTRIES = 600

    private data class CacheEntry(
        val link: YouTubeMusicVideoLink?,
        val expiresAtMs: Long,
        val metadataKey: String? = null,
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

    // Anonymous requests can progress for different songs independently:
    // the former single global lock made every queue skip wait for the slowest
    // previous /search (up to its full retry timeout). Keep authenticated
    // InnerTube's shared mutable session serialized.
    private val authMutex = Mutex()
    private val anonymousLocks = Array(32) { Mutex() }
    private val innerTube = InnerTube()
    private val matchCache = ConcurrentHashMap<String, CacheEntry>()

    @Volatile
    var onCacheEntry: ((CacheRow) -> Unit)? = null

    suspend fun resolve(
        sourceMediaId: String,
        title: String,
        artists: List<String>,
        durationSeconds: Int?,
        artistBrowseIds: List<String> = emptyList(),
        beforeMetadataRequest: suspend () -> Unit = CapsuleVideoRequestGuard::beforeMetadataRequest,
    ): Result<YouTubeMusicVideoLink> =
        runCatching {
            val sourceId = sourceMediaId.trim()
            val sourceTitle = title.trim()
            val cleanArtists =
                artists.map { it.trim() }.filter { it.isNotBlank() }
            val metadataKey = listOf(YouTubeMusicVideoMatcher.normalizeTitle(sourceTitle),
                cleanArtists.map(YouTubeMusicVideoMatcher::normalizeText).distinct().sorted().joinToString("|"),
                durationSeconds?.toString().orEmpty(),
                artistBrowseIds.filter(artistBrowseId::matches).distinct().sorted().take(3).joinToString("|")
            ).joinToString(":")

            require(sourceId.isNotBlank()) { "Missing YouTube Music track id" }
            require(sourceTitle.isNotBlank()) { "Missing song title" }

            cachedOrNull(sourceId, metadataKey)?.let { return@runCatching it }
            if (isNegativeCached(sourceId, metadataKey)) {
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

            val requestLock = if (CapsuleAnonymousSession.enabled)
                anonymousLocks[(sourceId.hashCode() and Int.MAX_VALUE) % anonymousLocks.size]
            else authMutex
            requestLock.withLock {
                cachedOrNull(sourceId, metadataKey)?.let { return@withLock it }
                if (isNegativeCached(sourceId, metadataKey)) {
                    throw IllegalStateException(
                        "Official music video is unavailable for this song",
                    )
                }

                coroutineScope {
                val sourceTitleNorm =
                    YouTubeMusicVideoMatcher.normalizeTitle(sourceTitle)
                val sourceArtistNorms =
                    cleanArtists
                        .flatMap { artist ->
                            // YouTube playlist rows may merge featured artists into
                            // a single credit, while Videos lists only the lead.
                            listOf(artist) + artist.split(
                                Regex("""(?i)\s+(?:feat\.?|ft\.?|featuring|with|x)\s+|\s*[,;&]\s*"""),
                            )
                        }
                        .map(YouTubeMusicVideoMatcher::normalizeText)
                        .filter { it.length >= 2 }
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

                // The first-party counterpart is authoritative. On slow anonymous
                // /next calls, hedge ONE natural Videos search after 700ms.
                // The shared admission is mutex-protected; rate-limit and bot
                // verdicts still terminate the whole operation.
                val searchCanProceed = CompletableDeferred<Unit>()
                val speculativeSearch = if (CapsuleAnonymousSession.enabled) {
                    async(start = CoroutineStart.LAZY) {
                        withTimeoutOrNull(ANONYMOUS_SEARCH_HEDGE_MS) {
                            searchCanProceed.await()
                        }
                        runCatching {
                            searchVideoRenderers(queries.first(), beforeMetadataRequest)
                        }.onFailure { if (it is CancellationException) throw it }
                    }.also { it.start() }
                } else null

                // Prefer the same audio/video toggle as the official Music
                // player. Never infer identity from a similarly named upload
                // when /next explicitly supplies a verified counterpart.
                val canonical = lookupFirstPartyCounterpart(sourceId, beforeMetadataRequest)
                if (canonical != null) {
                    speculativeSearch?.cancel()
                    val link = YouTubeMusicVideoLink(
                        videoId = canonical,
                        musicVideoType = MUSIC_VIDEO_TYPE_OMV,
                        title = sourceTitle,
                        score = YouTubeMusicVideoMatcher.STRONG_MATCH_SCORE + 100,
                    )
                    store(sourceId, CacheEntry(
                        link = link,
                        metadataKey = metadataKey,
                        expiresAtMs = System.currentTimeMillis() + POSITIVE_CACHE_MS,
                    ))
                    return@coroutineScope link
                }
                // Avoid waiting for the 700ms hedge when /next already answered
                // "no counterpart". A fast /next therefore costs no extra delay.
                searchCanProceed.complete(Unit)

                val candidatesById = linkedMapOf<String, Candidate>()
                var rowsSeen = 0
                suspend fun tryArtistCatalog(): YouTubeMusicVideoLink? {
                    // Only after both native /next and global Videos search
                    // FAILED to return a valid clip. First check the already
                    // loaded artist catalog without network, then one verified
                    // artist browse ID. No guessing channels from artist names.
                    YouTubeMusicArtistVideoHints.match(
                        sourceTitle, cleanArtists, durationSeconds, sourceId, artistBrowseIds,
                    )?.let { return it }
                    val artistId = artistBrowseIds.firstOrNull { artistBrowseId.matches(it) }
                        ?: return null
                    val epoch = CapsuleVideoRequestGuard.resetEpoch()
                    withTimeoutOrNull(ARTIST_CATALOG_BUDGET_MS) {
                        beforeMetadataRequest()
                        val response = YouTube.artist(artistId)
                        response.exceptionOrNull()?.let { failure ->
                            val kind = CapsuleVideoRequestGuard.noteApiFailure(failure, epoch)
                            if (kind == CapsuleVideoRequestGuard.FailureKind.RATE_LIMITED ||
                                kind == CapsuleVideoRequestGuard.FailureKind.BOT_CHECK ||
                                kind == CapsuleVideoRequestGuard.FailureKind.FORBIDDEN
                            ) {
                                throw CapsuleVideoRequestGuard.RequestBlockedException(
                                    "VIDEO artist catalog blocked: ${kind.name.lowercase()}", failure,
                                )
                            }
                        }
                        response.getOrNull()
                    } ?: return null
                    // YouTube.artist already remembers its VIDEO shelves.
                    return YouTubeMusicArtistVideoHints.match(
                        sourceTitle, cleanArtists, durationSeconds, sourceId, artistBrowseIds,
                    )
                }

                // One shared deadline for both search variants. The previous
                // per-query budget could double the wait after /next failed.
                // Keep any first-query candidates if the fallback times out.
                val searchCompleted = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                    for ((index, query) in queries.withIndex()) {
                    /*
                     * Only use the explicit fallback query if the natural query
                     * produced no usable official candidate at all.
                     */
                    if (index > 0 && !YouTubeMusicVideoMatcher.needsSecondQuery(
                            candidatesById.values.maxOfOrNull { it.score }
                        )) break

                    val renderers = if (index == 0 && speculativeSearch != null) {
                        speculativeSearch.await().getOrThrow()
                    } else {
                        searchVideoRenderers(query, beforeMetadataRequest)
                    }
                    rowsSeen += renderers.size

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

                    if (!YouTubeMusicVideoMatcher.needsSecondQuery(
                            candidatesById.values.maxOfOrNull { it.score }
                        )) break

                    }
                    true
                } ?: false
                // Cancel any still-running speculative query on timeout; do not
                // let an orphan request outlive this VIDEO resolve generation.
                speculativeSearch?.cancel()
                val best = candidatesById.values.maxByOrNull { it.score }
                if (best == null) {
                    // Artist browse is strictly a FALLBACK. This keeps the
                    // successful 4-5 second native search path authoritative:
                    // no artist-page clip can preempt its verified OMV.
                    // Incomplete searches must not create a negative cache.
                    tryArtistCatalog()?.let { fromArtist ->
                        store(sourceId, CacheEntry(
                            link = fromArtist, metadataKey = metadataKey,
                            expiresAtMs = System.currentTimeMillis() + POSITIVE_CACHE_MS,
                        ))
                        return@coroutineScope fromArtist
                    }
                    if (rowsSeen == 0 || !searchCompleted) {
                        // Empty/unrecognised response layouts are not proof that
                        // the song has no video. Do not cache an N/A verdict.
                        throw IllegalStateException("YouTube Music Videos search incomplete or returned no searchable rows")
                    }
                    store(
                        sourceId = sourceId,
                        entry =
                            CacheEntry(
                                link = null,
                                metadataKey = metadataKey,
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
                            metadataKey = metadataKey,
                            expiresAtMs =
                                System.currentTimeMillis() + POSITIVE_CACHE_MS,
                        ),
                )

                link
                }
            }
        }.onFailure { failure ->
            // runCatching also catches Coroutine CancellationException. A
            // user skip must cancel immediately rather than continue as an
            // ordinary failed search from a stale song generation.
            if (failure is CancellationException) throw failure
        }

    private suspend fun lookupFirstPartyCounterpart(
        sourceId: String,
        beforeMetadataRequest: suspend () -> Unit,
    ): String? {
        val guardEpoch = CapsuleVideoRequestGuard.resetEpoch()
        val response = try {
            withTimeoutOrNull(FIRST_PARTY_TIMEOUT_MS) {
                beforeMetadataRequest()
                val page = if (CapsuleAnonymousSession.enabled) {
                    CapsuleAnonymousSession.videoCounterpartPage(sourceId)
                } else {
                    syncSession()
                    innerTube.next(
                        client = WEB_REMIX,
                        videoId = sourceId,
                        playlistId = "RDAMVM$sourceId",
                        playlistSetVideoId = null,
                        index = null,
                        params = null,
                        audioVideoCounterpart = true,
                    ).body<NextResponse>()
                }
                page
            } ?: return null
        } catch (blocked: CapsuleVideoRequestGuard.RequestBlockedException) {
            throw blocked
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val kind = CapsuleVideoRequestGuard.noteApiFailure(failure, guardEpoch)
            if (kind == CapsuleVideoRequestGuard.FailureKind.RATE_LIMITED ||
                kind == CapsuleVideoRequestGuard.FailureKind.BOT_CHECK ||
                kind == CapsuleVideoRequestGuard.FailureKind.FORBIDDEN
            ) {
                // A denied /next must NOT immediately issue another /search.
                throw CapsuleVideoRequestGuard.RequestBlockedException(
                    "YouTube VIDEO counterpart lookup stopped after ${kind.name.lowercase()}",
                    failure,
                )
            }
            // Layout changes and transient network failures still use bounded search.
            return null
        }
        return nextOfficialVideoForSong(response, sourceId)
    }

    private suspend fun searchVideoRenderers(
        query: String,
        beforeMetadataRequest: suspend () -> Unit,
    ): List<MusicResponsiveListItemRenderer> {
        beforeMetadataRequest()
        val guardEpoch = CapsuleVideoRequestGuard.resetEpoch()

        val response =
            try {
                if (CapsuleAnonymousSession.enabled) {
                    CapsuleAnonymousSession.search(
                        query = query,
                        params = VIDEO_FILTER,
                        client = WEB_REMIX,
                        loadVisitorData = false,
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
            } catch (cancelled: CancellationException) {
                // A winning first-party counterpart cancels the speculative
                // search. That is NOT a YouTube outage or a bot challenge.
                throw cancelled
            } catch (throwable: Throwable) {
                val kind = CapsuleVideoRequestGuard.noteApiFailure(throwable, guardEpoch)

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
        if (renderer.unavailable) return null
        val endpoint =
            renderer.navigationEndpoint?.anyWatchEndpoint
                ?: renderer.overlay
                    ?.musicItemThumbnailOverlayRenderer
                    ?.content
                    ?.musicPlayButtonRenderer
                    ?.playNavigationEndpoint
                    ?.anyWatchEndpoint

        val videoType =
            endpoint?.watchEndpointMusicSupportedConfigs
                ?.watchEndpointMusicConfig
                ?.musicVideoType

        // An explicit ATV/UGC type is not an OMV. But absent metadata is
        // common on Videos search cards: verify the title/artist separately.
        if (videoType != null && videoType != MUSIC_VIDEO_TYPE_OMV) return null

        val videoId =
            endpoint?.videoId
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: renderer.playlistItemData
                    ?.videoId
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: return null

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
                sameOfficialVideo = videoId == sourceMediaId && videoType == MUSIC_VIDEO_TYPE_OMV,
                omvVerified = videoType == MUSIC_VIDEO_TYPE_OMV,
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
            // Older builds persisted 12-hour misses without their source metadata.
            // Keep verified positive matches; recheck those obsolete negative rows.
            if (row.videoId.isNullOrBlank() ||
                !row.sourceMediaId.matches(Regex("[A-Za-z0-9_-]{11}")) ||
                !row.videoId.matches(Regex("[A-Za-z0-9_-]{11}")) ||
                // Older builds persisted OMV matches even for the wrong artist.
                // Only retain confident matches, or an exact source-ID OMV.
                (row.sourceMediaId != row.videoId &&
                    row.score < YouTubeMusicVideoMatcher.STRONG_MATCH_SCORE)
            ) continue

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

    private fun cachedOrNull(sourceId: String, metadataKey: String): YouTubeMusicVideoLink? {
        val entry = matchCache[sourceId] ?: return null
        if (entry.expiresAtMs <= System.currentTimeMillis()) {
            matchCache.remove(sourceId)
            return null
        }
        // Existing 24-hour cache files contain old incorrect title-only
        // matches with no source identity. Force verification when metadata
        // does not match, instead of replaying the wrong clip.
        if (entry.link != null && entry.metadataKey != metadataKey &&
            entry.link.videoId != sourceId) return null
        return entry.link
    }

    private fun isNegativeCached(sourceId: String, metadataKey: String): Boolean {
        val entry = matchCache[sourceId] ?: return false
        if (entry.expiresAtMs <= System.currentTimeMillis()) {
            matchCache.remove(sourceId)
            return false
        }
        return entry.link == null && entry.metadataKey == metadataKey
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
