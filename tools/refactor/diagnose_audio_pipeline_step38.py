from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one anchor, found {count}: {old[:120]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# 1) Network-stage diagnostics. This wrapper sits only on CacheDataSource's upstream,
# so cache hits stay untouched and only real network loads are measured.
network_diag = ROOT / "app/src/main/kotlin/com/nikhil/yt/playback/audio/AudioNetworkDiagnosticDataSource.kt"
network_diag.write_text(
    r'''package com.nikhil.yt.playback.audio

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.nikhil.yt.utils.GlobalLog
import timber.log.Timber

/**
 * Debug-only timing around the real AUDIO network upstream.
 *
 * The wrapper deliberately logs only host/key/timings and never the resolved
 * googlevideo URL, query string, signatures, cookies, or PoTokens. When field
 * logging is disabled it becomes a thin pass-through: no timestamps, strings,
 * counters, or diagnostic allocations are produced.
 */
internal class AudioNetworkDiagnosticDataSource(
    private val upstream: DataSource,
) : DataSource {
    private var diagnosticsEnabled = false
    private var startedAtNs = 0L
    private var openCompletedAtNs = 0L
    private var firstByteLogged = false
    private var endLogged = false
    private var bytesRead = 0L
    private var mediaKey: String? = null
    private var host: String? = null

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        diagnosticsEnabled = GlobalLog.isEnabled
        if (!diagnosticsEnabled) return upstream.open(dataSpec)

        startedAtNs = System.nanoTime()
        openCompletedAtNs = 0L
        firstByteLogged = false
        endLogged = false
        bytesRead = 0L
        mediaKey = dataSpec.key?.take(64)
        host = dataSpec.uri.host?.take(96)

        Timber.tag(TAG).i(
            "cdn-open-start id=%s host=%s position=%d length=%d",
            mediaKey ?: "none",
            host ?: "unknown",
            dataSpec.position,
            dataSpec.length,
        )

        return try {
            upstream.open(dataSpec).also { resolvedLength ->
                openCompletedAtNs = System.nanoTime()
                Timber.tag(TAG).i(
                    "cdn-open-ready id=%s host=%s openMs=%d resolvedLength=%d",
                    mediaKey ?: "none",
                    host ?: "unknown",
                    elapsedMs(startedAtNs, openCompletedAtNs),
                    resolvedLength,
                )
            }
        } catch (failure: Throwable) {
            Timber.tag(TAG).w(
                failure,
                "cdn-open-failed id=%s host=%s elapsedMs=%d",
                mediaKey ?: "none",
                host ?: "unknown",
                elapsedMs(startedAtNs, System.nanoTime()),
            )
            throw failure
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!diagnosticsEnabled) return upstream.read(buffer, offset, length)

        return try {
            upstream.read(buffer, offset, length).also { count ->
                if (count > 0) {
                    bytesRead += count.toLong()
                    if (!firstByteLogged) {
                        firstByteLogged = true
                        val now = System.nanoTime()
                        Timber.tag(TAG).i(
                            "cdn-first-byte id=%s host=%s fromOpenStartMs=%d afterOpenMs=%d firstReadBytes=%d",
                            mediaKey ?: "none",
                            host ?: "unknown",
                            elapsedMs(startedAtNs, now),
                            if (openCompletedAtNs != 0L) elapsedMs(openCompletedAtNs, now) else -1L,
                            count,
                        )
                    }
                } else if (count == -1 && !endLogged) {
                    endLogged = true
                    Timber.tag(TAG).d(
                        "cdn-eof id=%s host=%s bytes=%d elapsedMs=%d",
                        mediaKey ?: "none",
                        host ?: "unknown",
                        bytesRead,
                        elapsedMs(startedAtNs, System.nanoTime()),
                    )
                }
            }
        } catch (failure: Throwable) {
            Timber.tag(TAG).w(
                failure,
                "cdn-read-failed id=%s host=%s bytes=%d elapsedMs=%d",
                mediaKey ?: "none",
                host ?: "unknown",
                bytesRead,
                elapsedMs(startedAtNs, System.nanoTime()),
            )
            throw failure
        }
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        try {
            upstream.close()
        } finally {
            if (diagnosticsEnabled && startedAtNs != 0L) {
                Timber.tag(TAG).d(
                    "cdn-close id=%s host=%s bytes=%d firstByte=%s elapsedMs=%d",
                    mediaKey ?: "none",
                    host ?: "unknown",
                    bytesRead,
                    firstByteLogged,
                    elapsedMs(startedAtNs, System.nanoTime()),
                )
            }
            diagnosticsEnabled = false
            startedAtNs = 0L
            openCompletedAtNs = 0L
            firstByteLogged = false
            endLogged = false
            bytesRead = 0L
            mediaKey = null
            host = null
        }
    }

    internal class Factory(
        private val upstreamFactory: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            AudioNetworkDiagnosticDataSource(upstreamFactory.createDataSource())
    }

    private companion object {
        const val TAG = "AudioCDN"

        fun elapsedMs(startNs: Long, endNs: Long): Long =
            if (startNs == 0L || endNs < startNs) -1L else (endNs - startNs) / 1_000_000L
    }
}
''',
    encoding="utf-8",
)

# 2) Config-stage diagnostics around InnerTubeX. No extra request is made; this
# only measures the exact request InnerTubeX already performs.
config_diag = ROOT / "app/src/main/kotlin/com/nikhil/yt/playback/audio/DiagnosticYtConfigParser.kt"
config_diag.write_text(
    r'''package com.nikhil.yt.playback.audio

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
''',
    encoding="utf-8",
)

music_service = ROOT / "app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt"
replace_once(
    music_service,
    "import com.nikhil.yt.playback.audio.AudioCacheSource\nimport com.nikhil.yt.playback.audio.CapsuleAudioRequestInterceptor",
    "import com.nikhil.yt.playback.audio.AudioCacheSource\nimport com.nikhil.yt.playback.audio.AudioNetworkDiagnosticDataSource\nimport com.nikhil.yt.playback.audio.CapsuleAudioRequestInterceptor",
)
replace_once(
    music_service,
    """        val streaming = CacheDataSource.Factory().setCache(playerCache)\n            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(this, OkHttpDataSource.Factory(audioHttpClient)))\n            .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)""",
    """        val networkUpstream =\n            AudioNetworkDiagnosticDataSource.Factory(\n                DefaultDataSource.Factory(this, OkHttpDataSource.Factory(audioHttpClient)),\n            )\n        val streaming = CacheDataSource.Factory().setCache(playerCache)\n            .setUpstreamDataSourceFactory(networkUpstream)\n            .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)""",
)

inner_player = ROOT / "app/src/main/kotlin/com/nikhil/yt/playback/audio/CapsuleInnerTubeXPlayer.kt"
replace_once(
    inner_player,
    """                    configParser =\n                        YtConfigParserImpl(httpClient, innerTube, remoteStore, logger)\n                            .withEmbeddedConfigFallback(),""",
    """                    configParser =\n                        DiagnosticYtConfigParser(\n                            YtConfigParserImpl(httpClient, innerTube, remoteStore, logger)\n                                .withEmbeddedConfigFallback(),\n                        ),""",
)

print("Step38 audio pipeline diagnostics applied")
