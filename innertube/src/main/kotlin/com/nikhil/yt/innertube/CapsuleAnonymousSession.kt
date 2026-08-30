/*
 * Capsule MUSIC
 *
 * A separate, credential-free InnerTube session used only by the VIDEO path.
 *
 * Why this exists:
 *   Capsule's normal AUDIO/library traffic is authenticated, because it has to
 *   be: playlists, likes and history belong to the signed-in account. The
 *   optional VIDEO path needs none of that. Official music videos are public.
 *
 *   Sending account cookies on VIDEO requests means any rate limiting or
 *   anti-bot verdict attaches to the user's Google account instead of just
 *   their IP. This object keeps the two apart: its own InnerTube instance, its
 *   own visitorData, no cookie, no dataSyncId, no account poToken, ever.
 *
 * This is not an attempt to look like something Capsule is not. It sends
 * strictly less than the authenticated path does.
 *
 * GPL-3.0
 */

package com.nikhil.yt.innertube

import com.nikhil.yt.innertube.models.YouTubeClient
import com.nikhil.yt.innertube.models.response.PlayerResponse
import com.nikhil.yt.innertube.models.response.SearchResponse
import com.nikhil.yt.innertube.utils.PoTokenGenerator
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

    /**
     * Whether the VIDEO path runs anonymously. Expose this as a setting; the
     * resolvers fall back to the authenticated [YouTube] session when it is
     * false.
     *
     * Anonymous is the safer default, but it is not free: see the note at the
     * bottom of this file.
     */
    @Volatile
    var enabled: Boolean = true

    private const val VISITOR_DATA_TTL_MS = 12 * 60 * 60 * 1000L

    private val VISITOR_DATA_REGEX = Regex("^Cg[t|s]")

    /**
     * Dedicated instance. Never assign [YouTube.authState] to this: the whole
     * point is that the two sessions do not share identity.
     */
    private val innerTube =
        InnerTube().apply {
            authState = PlaybackAuthState.EMPTY
            useLoginForBrowse = false
        }

    private val mutex = Mutex()

    @Volatile
    private var visitorDataFetchedAtMs = 0L

    // ---------------------------------------------------------------------
    // Endpoints
    // ---------------------------------------------------------------------

    suspend fun player(
        videoId: String,
        client: YouTubeClient,
        signatureTimestamp: Int? = null,
    ): Result<PlayerResponse> = runCatching {
        prepare()

        /*
         * Web-family clients expect a service integrity token. The
         * authenticated path derives one from the account's visitorData; here
         * it is derived from the anonymous visitorData instead, so nothing
         * account-derived is sent.
         */
        val poToken =
            if (PlaybackAuthState.needsServiceIntegrity(client)) {
                PoTokenGenerator.generateContentToken(
                    identifier = innerTube.visitorData ?: "capsule_anonymous_visitor",
                    videoId = videoId,
                )
            } else {
                null
            }

        innerTube
            .player(
                client = client,
                videoId = videoId,
                playlistId = null,
                signatureTimestamp = signatureTimestamp,
                poToken = poToken,
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

    // ---------------------------------------------------------------------
    // Stream URL hygiene
    // ---------------------------------------------------------------------

    /**
     * Removes an account-derived GVS poToken from a stream URL.
     *
     * [NewPipeUtils.getStreamUrl] ends with YouTube.appendGvsPoToken(), which
     * reads the *global authenticated* auth state. Without this, an anonymous
     * player response would still produce a stream URL carrying the signed-in
     * user's token, which defeats the separation entirely.
     *
     * Only a token that matches the account's current one is stripped. A `pot`
     * that came from the anonymous player response itself is left alone.
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

    // ---------------------------------------------------------------------
    // Session upkeep
    // ---------------------------------------------------------------------

    /**
     * Mirrors only the things that are not identity: locale and proxy. Auth
     * state is deliberately never copied from [YouTube].
     */
    private suspend fun prepare() {
        mutex.withLock {
            innerTube.locale = YouTube.locale
            innerTube.useLoginForBrowse = false

            val proxy = YouTube.proxy
            if (innerTube.proxy != proxy) {
                innerTube.proxy = proxy
            }

            // Defensive: strip anything credential-shaped that got in.
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

    /**
     * Same source the authenticated session uses, but fetched through this
     * instance so the resulting visitor id is ours and not the account's.
     * Requests still work without it, so a failure here is not fatal.
     */
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

    /** Drops the anonymous identity, e.g. after a network change. */
    fun reset() {
        innerTube.authState = PlaybackAuthState.EMPTY
        visitorDataFetchedAtMs = 0L
    }
}
