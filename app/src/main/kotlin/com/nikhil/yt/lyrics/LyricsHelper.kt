/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.lyrics

import android.content.Context
import android.util.Log
import android.util.LruCache
import com.nikhil.yt.utils.GlobalLog
import com.nikhil.yt.constants.LyricsProviderOrder
import com.nikhil.yt.constants.LyricsProviderOrderKey
import com.nikhil.yt.constants.PreferredLyricsProviderKey
import com.nikhil.yt.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.utils.dataStore
import com.nikhil.yt.utils.reportException
import com.nikhil.yt.utils.NetworkConnectivityObserver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

class LyricsHelper
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val networkConnectivity: NetworkConnectivityObserver,
) {
    /** Every provider there is, by its id in [LyricsProviderOrder]. */
    private val providersById: Map<String, LyricsProvider> =
        mapOf(
            LyricsProviderOrder.LYRICS_PLUS to LyricsPlusLyricsProvider,
            LyricsProviderOrder.BETTER_LYRICS_PORTATO to BetterLyricsPortatoProvider,
            LyricsProviderOrder.SIMPMUSIC to SimpMusicLyricsProvider,
            LyricsProviderOrder.PAXSENIX_APPLE_MUSIC to PaxsenixLyricsProvider,
            LyricsProviderOrder.LRCLIB to LrcLibLyricsProvider,
            LyricsProviderOrder.KUGOU to BetterLyricsKuGouProvider,
            LyricsProviderOrder.YOUTUBE to YouTubeLyricsProvider,
        )

    private val cache = LruCache<String, List<LyricsResult>>(MAX_CACHE_SIZE)
    @Volatile
    private var currentLyricsJob: Job? = null

    suspend fun getLyrics(
        mediaMetadata: MediaMetadata,
        preferredProviderOnly: Boolean = false,
        forceRefresh: Boolean = false,
    ): String {
        // A playback lookup must never cancel the manual "choose lyrics source"
        // screen. That screen owns its own cancellable job.
        if (forceRefresh) cache.remove(mediaMetadata.id)
        val ordered = orderedProviders()
        val cached = cache.get(mediaMetadata.id)?.firstOrNull()
        val topEnabled = ordered.firstOrNull { it.isEnabled(context) }?.name
        if (!forceRefresh && cached != null && cached.providerName == topEnabled) {
            GlobalLog.append(Log.DEBUG, "LyricsHelper", "Found priority-matching lyrics in cache for ${mediaMetadata.title}")
            return cached.lyrics
        }
        
        GlobalLog.append(Log.DEBUG, "LyricsHelper", "Fetching lyrics for ${mediaMetadata.title} (Artist: ${mediaMetadata.artists.joinToString { it.name }}, Album: ${mediaMetadata.album?.title})")

        val isNetworkAvailable = try {
            networkConnectivity.isCurrentlyConnected()
        } catch (e: Exception) {
            true
        }
        
        if (!isNetworkAvailable) {
            GlobalLog.append(Log.WARN, "LyricsHelper", "Network unavailable, aborting lyrics fetch")
            return LYRICS_NOT_FOUND
        }

        val providers = if (preferredProviderOnly) ordered.take(1) else ordered

        /*
         * Provider order is the contract: the first enabled source that returns actual readable
         * lyrics wins immediately. Do not wait for slower providers merely to compare sync grades.
         *
         * isMeaningfulLyrics() stays load-bearing here, especially for Paxsenix: an empty TTML or
         * timings-only response is a miss and must fall through to the next provider rather than
         * becoming a blank lyrics screen.
         */
        for (provider in providers) {
            currentCoroutineContext().ensureActive()
            if (!provider.isEnabled(context)) continue

            try {
                val result =
                    provider.getLyrics(
                        mediaMetadata.id,
                        mediaMetadata.title,
                        mediaMetadata.artists.joinToString { it.name },
                        mediaMetadata.album?.title,
                        mediaMetadata.duration,
                    )
                currentCoroutineContext().ensureActive()
                val lyrics = result.getOrNull()
                if (lyrics != null && isMeaningfulLyrics(lyrics)) {
                    val chosen = LyricsResult(provider.name, lyrics)
                    GlobalLog.append(
                        Log.DEBUG,
                        "LyricsHelper",
                        "Using first meaningful lyrics from ${provider.name} for ${mediaMetadata.title}",
                    )
                    cache.put(mediaMetadata.id, listOf(chosen))
                    return lyrics
                } else {
                    result.exceptionOrNull()?.let { reportProviderFailure(provider, it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportProviderFailure(provider, e)
            }
        }

        return LYRICS_NOT_FOUND
    }

    /**
     * Manual source discovery streams results as EACH independent provider finishes.
     *
     * The old serial loop was head-of-line blocked by long Apple/LyricsPlus timeouts.
     * Worse, a playback lookup cancelled the shared currentLyricsJob, leaving only
     * one visible provider in the picker. Work on different providers is isolated;
     * one failure/timeout must not suppress the others.
     */
    suspend fun getAllLyrics(
        mediaId: String,
        songTitle: String,
        songArtists: String,
        songAlbum: String?,
        duration: Int,
        callback: (LyricsResult) -> Unit,
    ) {
        currentLyricsJob?.cancel()
        val owner = currentCoroutineContext()[Job]
        currentLyricsJob = owner

        try {
            if (!runCatching { networkConnectivity.isCurrentlyConnected() }.getOrDefault(true)) {
                GlobalLog.append(Log.DEBUG, "LyricsHelper", "Manual lyrics search skipped: offline")
                return
            }

            val providers = orderedProviders()
            val results = mutableListOf<LyricsResult>()
            supervisorScope {
                providers.map { provider ->
                    async(Dispatchers.IO) {
                        currentCoroutineContext().ensureActive()
                        if (!provider.isEnabled(context)) {
                            GlobalLog.append(Log.DEBUG, "LyricsHelper", "Source disabled: ${provider.name}")
                            return@async
                        }
                        val startedAt = android.os.SystemClock.elapsedRealtime()
                        var accepted = 0
                        try {
                            val completed = withTimeoutOrNull(MANUAL_PROVIDER_TIMEOUT_MS) {
                                provider.getAllLyrics(
                                    mediaId, songTitle, songArtists, songAlbum, duration,
                                ) lyricsCallback@{ lyrics ->
                                    if (!isMeaningfulLyrics(lyrics)) return@lyricsCallback
                                    val item = LyricsResult(provider.name, lyrics)
                                    synchronized(results) {
                                        if (results.none {
                                                it.providerName == item.providerName &&
                                                    it.lyrics == item.lyrics
                                            }) {
                                            results.add(item)
                                            callback(item)
                                            accepted++
                                        }
                                    }
                                }
                                true
                            }
                            val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                            GlobalLog.append(Log.DEBUG, "LyricsHelper",
                                "Source ${provider.name}: ${if (completed == null) "timeout" else "$accepted result(s)"} in ${elapsed}ms")
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            reportProviderFailure(provider, failure)
                        }
                    }
                }.awaitAll()
            }
            // Do not cache partial manual searches across source toggles or orders.
            // A new press on "Search" must actually interrogate the current sources.
        } finally {
            if (currentLyricsJob === owner) currentLyricsJob = null
        }
    }

    /**
     * Records why a provider came back empty, at the level that outcome deserves.
     *
     * Most tracks have no lyrics at some provider or other, so a miss is the common case. It was
     * reaching the log as an application error with a full stack trace: two and a half minutes of
     * playback produced nine E/ entries, every one a provider saying it had nothing or that it
     * was in its own cooldown. One of those, read by somebody else, was taken as proof that the
     * cooldown was broken — the message it prints is the cooldown working, and it never touches
     * the network. A healthy path that reads as a crash is a bug in the reporting, not a
     * cosmetic one.
     *
     * Anything that is not an ordinary miss still gets the stack trace, because a parse failure
     * or a null from our own mapping code is worth one.
     */
    private fun reportProviderFailure(provider: LyricsProvider, failure: Throwable) {
        when {
            failure is CancellationException -> throw failure
            failure.isOrdinaryLyricsMiss() ->
                GlobalLog.append(
                    Log.DEBUG,
                    "LyricsHelper",
                    "No lyrics from ${provider.name}" +
                        failure.message?.let { ": $it" }.orEmpty(),
                )
            else -> reportException(failure)
        }
    }

    /**
     * The providers to try, in the order the user put them.
     *
     * The old single-choice setting is read as a seed so an upgrade keeps whichever provider
     * somebody had already chosen as their first, rather than resetting them to the default.
     * [LyricsProviderOrder.resolve] appends anything missing, so this can never come back short.
     */
    private suspend fun orderedProviders(): List<LyricsProvider> {
        val preferences = context.dataStore.data.first()
        return LyricsProviderOrder
            .resolve(
                raw = preferences[LyricsProviderOrderKey],
                legacyPreferred = preferences[PreferredLyricsProviderKey],
            ).mapNotNull(providersById::get)
    }

    /**
     * Whether there is anything here a person could actually read.
     *
     * This decides whether the search moves on to the next provider, so it has to be judged the way
     * the screen will be: by what is left after the markup, not by whether the response was
     * non-empty.
     *
     * The old check stripped line timestamps with a regex and asked whether anything remained,
     * which was true of far too much. A TTML document with no words in it is angle brackets and
     * attributes all the way down, so it passed — and Paxsenix returns TTML, which is exactly how
     * an empty answer from it could win the search and leave a song with a blank screen while
     * providers that had the lyrics were never asked. Enhanced LRC broke it the same way once word
     * stamps existed: `<00:00.55> <00:00.90>` is not whitespace either.
     *
     * So it now extracts the text with the same parsers the renderer uses. Parsing twice costs one
     * pass over a string we just downloaded, which is nothing next to the request that fetched it.
     */
    internal fun isMeaningfulLyrics(lyrics: String): Boolean {
        val normalized =
            lyrics
                .replace("\uFEFF", "")
                .replace(INVISIBLE_CHARS_REGEX, "")
                .trim { it.isWhitespace() || it == '\u00A0' }

        if (normalized.isEmpty()) return false
        if (normalized == LYRICS_NOT_FOUND) return false

        val readable =
            displayableText(normalized)
                .replace(INVISIBLE_CHARS_REGEX, "")

        return readable.any { !it.isWhitespace() && it != '\u00A0' }
    }

    /** Everything a reader would see, with every kind of timing markup taken out. */
    private fun displayableText(lyrics: String): String =
        try {
            when {
                LyricsUtils.isTtml(lyrics) ->
                    LyricsUtils.parseTtml(lyrics).joinToString(" ") { it.text }

                LINE_TIMESTAMP_REGEX.containsMatchIn(lyrics) ->
                    LyricsUtils.parseLyrics(lyrics).joinToString(" ") { it.text }

                else -> LyricsUtils.stripWordTimings(lyrics)
            }
        } catch (e: Exception) {
            // Something we could not parse is still something; let the renderer decide.
            reportException(e)
            LyricsUtils.stripWordTimings(TIMESTAMP_REGEX.replace(lyrics, ""))
        }

    fun cancelCurrentLyricsJob() {
        currentLyricsJob?.cancel()
        currentLyricsJob = null
    }

    companion object {
        private const val MAX_CACHE_SIZE = 3
        private const val MANUAL_PROVIDER_TIMEOUT_MS = 12_000L
        private val TIMESTAMP_REGEX = Regex("""\[[0-9]{1,2}:[0-9]{2}(?:\.[0-9]{1,3})?]""")
        private val LINE_TIMESTAMP_REGEX = Regex("""\[[0-9]{1,2}:[0-9]{2}[.:][0-9]{2,3}]""")
        private val INVISIBLE_CHARS_REGEX = Regex("""[\u200B\u200C\u200D\u2060\u00AD]""")
    }
}

data class LyricsResult(
    val providerName: String,
    val lyrics: String,
)
