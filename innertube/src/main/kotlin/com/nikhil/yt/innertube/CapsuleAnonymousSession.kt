/*
 * Capsule MUSIC
 *
 * Cookie-free InnerTube session used by public playback/search paths.
 *
 * This session never imports Capsule's account cookie or dataSyncId and never
 * fabricates a PO token. It can use only the genuine web PO tokens explicitly
 * enabled by the user, together with their matching visitorData.
 *
 * GPL-3.0
 */

package com.nikhil.yt.innertube

import com.nikhil.yt.innertube.models.YouTubeClient
import com.nikhil.yt.innertube.models.response.PlayerResponse
import com.nikhil.yt.innertube.models.response.SearchResponse
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

object CapsuleAnonymousSession {
    @Volatile
    var enabled: Boolean = true

    private const val VISITOR_DATA_TTL_MS = 12 * 60 * 60 * 1000L
    private val VISITOR_DATA_REGEX = Regex("^Cg[t|s]")

    private val innerTube =
        InnerTube().apply {
            authState = PlaybackAuthState.EMPTY
            useLoginForBrowse = false
        }

    private val mutex = Mutex()

    @Volatile
    private var visitorDataFetchedAtMs = 0L

    suspend fun player(
        videoId: String,
        client: YouTubeClient,
        signatureTimestamp: Int? = null,
    ): Result<PlayerResponse> =
        runCatchingCancellable {
            prepare()

            val playerPoToken =
                innerTube.authState.resolvePlayerPoToken(client)

            innerTube
                .player(
                    client = client,
                    videoId = videoId,
                    playlistId = null,
                    signatureTimestamp = signatureTimestamp,
                    poToken = playerPoToken,
                )
                .body<PlayerResponse>()
        }

    suspend fun search(
        query: String,
        params: String?,
        client: YouTubeClient = YouTubeClient.WEB_REMIX,
    ): SearchResponse {
        prepare()

        return innerTube
            .search(
                client = client,
                query = query,
                params = params,
            )
            .body<SearchResponse>()
    }

    private suspend fun prepare() {
        mutex.withLock {
            innerTube.locale = YouTube.locale
            innerTube.useLoginForBrowse = false

            val proxy = YouTube.proxy
            if (innerTube.proxy != proxy) {
                innerTube.proxy = proxy
            }

            val current = innerTube.authState
            val configured = YouTube.authState.normalized()
            val tokensEnabled = configured.webClientPoTokenEnabled
            val configuredVisitorData =
                configured.visitorData.takeIf { tokensEnabled }

            /*
             * Copy only explicitly enabled public playback attestation. Login
             * cookies and dataSyncId never cross into this session.
             */
            innerTube.authState =
                PlaybackAuthState(
                    visitorData = configuredVisitorData ?: current.visitorData,
                    poToken = configured.poToken.takeIf { tokensEnabled },
                    poTokenGvs = configured.poTokenGvs.takeIf { tokensEnabled },
                    poTokenPlayer = configured.poTokenPlayer.takeIf { tokensEnabled },
                    webClientPoTokenEnabled = tokensEnabled,
                )

            val now = System.currentTimeMillis()
            val needsVisitorData =
                configuredVisitorData == null &&
                    (innerTube.visitorData.isNullOrBlank() ||
                        now - visitorDataFetchedAtMs > VISITOR_DATA_TTL_MS)

            if (needsVisitorData) {
                fetchVisitorData()?.let { fetched ->
                    innerTube.authState =
                        innerTube.authState.copy(visitorData = fetched)
                    visitorDataFetchedAtMs = now
                }
            }
        }
    }

    private suspend fun fetchVisitorData(): String? =
        runCatchingCancellable {
            Json
                .parseToJsonElement(
                    innerTube.getSwJsData().bodyAsText().substring(5),
                )
                .jsonArray[0]
                .jsonArray[2]
                .jsonArray
                .first { element ->
                    (element as? JsonPrimitive)
                        ?.contentOrNull
                        ?.let { VISITOR_DATA_REGEX.containsMatchIn(it) }
                        ?: false
                }
                .jsonPrimitive
                .content
        }.getOrNull()

    fun reset() {
        innerTube.authState = PlaybackAuthState.EMPTY
        visitorDataFetchedAtMs = 0L
    }
}
