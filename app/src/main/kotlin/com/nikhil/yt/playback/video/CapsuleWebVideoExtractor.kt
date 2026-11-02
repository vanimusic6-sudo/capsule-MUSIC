/* Capsule MUSIC — independent token-backed VIDEO extraction context. GPL-3.0. */
package com.nikhil.yt.playback.video

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.*
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.nikhil.yt.App
import com.nikhil.yt.innertube.CapsuleVideoRequestGuard
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.PlaybackAuthState
import com.nikhil.yt.innertube.YouTubeFailureClassifier
import com.nikhil.yt.innertube.YouTubeFailureKind
import com.nikhil.yt.playback.audio.CapsuleAudioClientStrategy
import com.nikhil.yt.playback.audio.CapsuleInnerTubeXPlayer
import com.nikhil.yt.playback.audio.potoken.PoTokenGenerator
import com.nikhil.yt.utils.GlobalLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

/** No AUDIO scheduler, extractor, HTTP pool, client-health state or minter lifetime is shared. */
internal object CapsuleWebVideoExtractor {
    private val lock = Mutex()
    private var current: Context? = null
    private val logger = InnerTubeLogger { event ->
        if (GlobalLog.isEnabled) Timber.tag("VideoInnerTubeX").d("%s", event.message)
    }

    suspend fun resolve(videoId: String, maxHeight: Int): ExtractedStream = withContext(Dispatchers.IO) {
        withTimeout(10_000L) {
            lock.withLock {
                checkAdmission()
                val identity = CapsuleInnerTubeXPlayer.sessionIdentity()
                if (current?.identity != identity) {
                    current?.close()
                    current = create(identity)
                }
                val context = requireNotNull(current)
                context.botObserved.set(false)
                try {
                    val stream = requireNotNull(context.extractor.extract(
                        videoId = videoId,
                        hints = ContentHints(wantVideo = true, playbackClientOverrideId = "WEB_REMIX", maxVideoHeight = maxHeight)
                            .withStreamCapabilities(allowHls = false, allowSabr = false, allowBoundedRange = true),
                        audioQuality = AudioQuality.MP4,
                        clientPlaybackNonce = generateClientPlaybackNonce(),
                    )) { "VIDEO client returned no stream" }
                    if (identity != CapsuleInnerTubeXPlayer.sessionIdentity()) throw CancellationException("VIDEO session changed")
                    stream
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (context.botObserved.get()) throw YouTubeVideoResolver.VideoBackendException(
                        CapsuleVideoFailure.BOT_BLOCKED, "VIDEO client bot challenge", failure)
                    throw failure
                }
            }
        }
    }

    private fun checkAdmission() {
        if (CapsuleVideoRequestGuard.isBlocked()) throw IOException("VIDEO cooldown active")
    }

    private fun create(identity: Any): Context {
        val auth = YouTube.authState
        val botObserved = AtomicBoolean(false)
        val http = HttpClient(OkHttp) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }) }
            install(HttpTimeout) { requestTimeoutMillis = 6_000L; connectTimeoutMillis = 4_000L; socketTimeoutMillis = 6_000L }
            engine {
                YouTube.proxy?.let { proxy = it }
                config {
                    retryOnConnectionFailure(false)
                    addInterceptor { chain ->
                        checkAdmission()
                        val response = chain.proceed(chain.request())
                        if (response.code == 429) {
                            CapsuleVideoRequestGuard.noteHttpStatus(429)
                            response.close()
                            throw IOException("VIDEO HTTP 429")
                        }
                        if (chain.request().url.encodedPath.endsWith("/player")) {
                            val body = try { response.peekBody(64 * 1024L).string() }
                            catch (failure: IOException) { response.close(); throw failure }
                            val playability = runCatching {
                                (Json.parseToJsonElement(body) as? JsonObject)?.get("playabilityStatus") as? JsonObject
                            }.getOrNull()
                            if (YouTubeFailureClassifier.classify(
                                    playabilityStatus = (playability?.get("status") as? JsonPrimitive)?.contentOrNull,
                                    text = playability?.toString(),
                                ) == YouTubeFailureKind.BOT_CHECK) botObserved.set(true)
                        }
                        response
                    }
                }
            }
        }
        val innerTube = InnerTube(httpClient = http, logger = logger).apply {
            locale = com.metrolist.innertubex.models.YouTubeLocale(YouTube.locale.gl, YouTube.locale.hl)
            replaceSession(cookie = auth.cookie, visitorData = auth.visitorData, dataSyncId = auth.dataSyncId,
                authUser = "0", useLoginForBrowse = YouTube.useLoginForBrowse)
        }
        val store = RemotePlayerConfigStore(http,
            CapsuleInnerTubeXPlayer.AndroidPlayerConfigRepository(App.instance.applicationContext), logger)
        val cipher = YouTubeCipherService(http, store, logger)
        val parser = YtConfigParserImpl(http, innerTube, store, logger)
        val tokens = VideoTokens(auth)
        return Context(identity, http, innerTube, cipher, tokens, botObserved, InnerTubeExtractor(
            configParser = object : YtConfigParser by parser {
                override suspend fun fetchConfig(videoId: String, useLoginCookies: Boolean): PlayerConfig = try {
                    parser.fetchConfig(videoId, useLoginCookies)
                } catch (_: IllegalStateException) { parser.fetchEmbeddedConfig(videoId, false) }
            },
            cipherService = cipher, innerTube = innerTube,
            fallbackStrategy = CapsuleAudioClientStrategy, tokenProvider = tokens, logger = logger,
        ))
    }

    private class VideoTokens(private val auth: PlaybackAuthState) : TokenProvider {
        // A timed-out VIDEO WebView may dispose only this session, never AUDIO's minter.
        private val generator = PoTokenGenerator(App.instance.applicationContext, independent = true)
        override val capabilities = TokenProviderCapabilities(providers = setOf(PoTokenProviderKind.WEB_BOTGUARD), usesWebView = true)
        override suspend fun getPoToken(videoId: String, visitorData: String, cookie: String?): PoTokenResult? {
            if (!auth.poTokenPlayer.isNullOrBlank() && !auth.poTokenGvs.isNullOrBlank()) return PoTokenResult(
                playerRequestToken = auth.poTokenPlayer!!, streamingDataToken = auth.poTokenGvs!!, visitorData = visitorData)
            return generator.getWebClientPoToken(videoId, visitorData)?.let {
                PoTokenResult(it.playerRequestPoToken, it.streamingDataPoToken, visitorData)
            }
        }
        override suspend fun close() = generator.close()
    }

    private class Context(
        val identity: Any, val http: HttpClient, val innerTube: InnerTube,
        val cipher: YouTubeCipherService, val tokens: VideoTokens,
        val botObserved: AtomicBoolean, val extractor: InnerTubeExtractor,
    ) {
        suspend fun close() {
            tokens.close()
            cipher.dispose()
            innerTube.close()
            http.close()
        }
    }
}
