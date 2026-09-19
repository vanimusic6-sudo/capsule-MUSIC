package com.nikhil.yt.playback.audio

import com.metrolist.innertubex.extraction.PlayerConfig
import com.metrolist.innertubex.extraction.YtConfigParser
import com.nikhil.yt.utils.GlobalLog
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/** Measures InnerTubeX's existing watch/embed config fetch without changing it. */
internal class DiagnosticYtConfigParser(
    private val delegate: YtConfigParser,
) : YtConfigParser {
    override suspend fun fetchConfig(videoId: String, useLoginCookies: Boolean): PlayerConfig =
        measured("watch", videoId, useLoginCookies) {
            delegate.fetchConfig(videoId, useLoginCookies)
        }

    override suspend fun fetchEmbeddedConfig(videoId: String, useLoginCookies: Boolean): PlayerConfig =
        measured("embed", videoId, useLoginCookies) {
            delegate.fetchEmbeddedConfig(videoId, useLoginCookies)
        }

    private suspend fun measured(
        kind: String,
        videoId: String,
        authenticated: Boolean,
        block: suspend () -> PlayerConfig,
    ): PlayerConfig {
        if (!GlobalLog.isEnabled) return block()

        val startedAtNs = System.nanoTime()
        Timber.tag(TAG).i(
            "config-fetch-start kind=%s id=%s authenticated=%s",
            kind,
            videoId,
            authenticated,
        )
        return try {
            block().also {
                Timber.tag(TAG).i(
                    "config-fetch-ready kind=%s id=%s authenticated=%s elapsedMs=%d",
                    kind,
                    videoId,
                    authenticated,
                    (System.nanoTime() - startedAtNs) / 1_000_000L,
                )
            }
        } catch (cancelled: CancellationException) {
            Timber.tag(TAG).d(
                "config-fetch-cancelled kind=%s id=%s authenticated=%s elapsedMs=%d",
                kind,
                videoId,
                authenticated,
                (System.nanoTime() - startedAtNs) / 1_000_000L,
            )
            throw cancelled
        } catch (failure: Throwable) {
            Timber.tag(TAG).w(
                failure,
                "config-fetch-failed kind=%s id=%s authenticated=%s elapsedMs=%d",
                kind,
                videoId,
                authenticated,
                (System.nanoTime() - startedAtNs) / 1_000_000L,
            )
            throw failure
        }
    }

    private companion object {
        const val TAG = "AudioConfig"
    }
}
