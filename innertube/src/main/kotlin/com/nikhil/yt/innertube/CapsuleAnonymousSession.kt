/*
 * Capsule MUSIC
 *
 * Credential-free InnerTube session used by public playback/search paths.
 *
 * This session never imports Capsule's account cookie, dataSyncId or account
 * PO tokens. It also never fabricates a PO token. If a client starts requiring
 * a real attestation token, that client must be removed from the safe fallback
 * set until Capsule has a genuine compatible provider.
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
        runCatching {
            prepare()

            /*
             * Intentionally NO synthetic/fake PO token.
             *
             * A random/local token is not equivalent to BotGuard/DroidGuard/
             * iOSGuard attestation. Safe Capsule clients are selected specifically
             * so that normal playback does not depend on such a token.
             */
            innerTube
                .player(
                    client = client,
                    videoId = videoId,
                    playlistId = null,
                    signatureTimestamp = signatureTimestamp,
                    poToken = null,
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

    /**
     * Removes an account-derived GVS poToken that the legacy URL helper may
     * append from the global [YouTube] auth state.
     */
    fun stripAccountPoToken(
        url: String,
        client: YouTubeClient?,
    ): String {
        val accountToken =
            YouTube.authState.resolveGvsPoToken(client) ?: return url

        if (!url.contains("pot=$accountToken")) return url

        return url
            .replace("&pot=$accountToken", "")
            .replace("?pot=$accountToken&", "?")
            .replace("?pot=$accountToken", "")
    }

    private suspend fun prepare() {
        mutex.withLock {
            innerTube.locale = YouTube.locale
            innerTube.useLoginForBrowse = false

            val proxy = YouTube.proxy
            if (innerTube.proxy != proxy) {
                innerTube.proxy = proxy
            }

            /*
             * Defensive reset: this object must never retain credentials.
             */
            val current = innerTube.authState
            if (
                current.cookie != null ||
                current.dataSyncId != null ||
                current.poToken != null ||
                current.poTokenGvs != null ||
                current.poTokenPlayer != null
            ) {
                innerTube.authState =
                    PlaybackAuthState(visitorData = current.visitorData)
            }

            val now = System.currentTimeMillis()
            val needsVisitorData =
                innerTube.visitorData.isNullOrBlank() ||
                    now - visitorDataFetchedAtMs > VISITOR_DATA_TTL_MS

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
        runCatching {
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
