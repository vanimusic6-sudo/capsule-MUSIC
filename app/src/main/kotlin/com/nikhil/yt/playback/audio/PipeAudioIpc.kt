/* Capsule MUSIC — bounded isolated PipePipe AUDIO reserve. GPL-3.0. */
package com.nikhil.yt.playback.audio

import android.net.Uri
import android.os.Bundle
import com.nikhil.yt.App
import com.nikhil.yt.innertube.models.response.PlayerResponse
import com.nikhil.yt.innertube.video.CapsulePipeAudio
import com.nikhil.yt.innertube.video.CapsuleNewPipeExtractor
import com.nikhil.yt.innertube.video.CapsuleNewPipeFailure
import com.nikhil.yt.innertube.video.PipeAudioAccessRestrictedException
import com.nikhil.yt.innertube.video.PipeAudioQuality
import com.nikhil.yt.innertube.video.PipeAudioStream
import com.nikhil.yt.innertube.video.VideoExtractionRequests
import com.nikhil.yt.playback.PlaybackFailureKind
import com.nikhil.yt.playback.video.CapsuleVideoIpc
import com.nikhil.yt.playback.video.cancellableRemoteCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import timber.log.Timber

internal object PipeAudioIpc {
    const val METHOD = "resolve_audio"
    private const val BUDGET_MS = 4_500L
    private val executor by lazy {
        // Do not accumulate requests if the remote parser ignores cancellation.
        ThreadPoolExecutor(0, 1, 30L, TimeUnit.SECONDS, SynchronousQueue<Runnable>()) { task ->
            Thread(task, "capsule-audio-parser").apply { isDaemon = true }
        }
    }
    private val cancellationExecutor by lazy {
        Executors.newSingleThreadExecutor { task -> Thread(task, "capsule-audio-parser-cancel").apply { isDaemon = true } }
    }

    /** Server: one request, one backend, no video search and no account data. */
    fun resolve(requestId: String, extras: Bundle?): Bundle {
        val id = extras?.getString(CapsuleVideoIpc.EXTRA_VIDEO_ID).orEmpty()
        val quality = PipeAudioQuality.entries.firstOrNull { it.name == extras?.getString(CapsuleVideoIpc.EXTRA_QUALITY) }
        if (!id.matches(Regex("[A-Za-z0-9_-]{11}")) || quality == null) return failure("EXTRACTOR")
        return try {
            val stream = VideoExtractionRequests.withRequest(requestId, BUDGET_MS) {
                CapsulePipeAudio.resolve(id, quality)
            } ?: return failure("EXTRACTOR")
            if (!stream.valid(id)) return failure("EXTRACTOR")
            Bundle().apply {
                putBoolean("ok", true)
                putString("id", stream.id)
                putString("url", stream.url)
                putInt("itag", stream.itag)
                putString("mime", stream.mimeType)
                putString("codec", stream.codec)
                putInt("bitrate", stream.bitrate)
                putLong("length", stream.length)
                putInt("rate", stream.sampleRate ?: -1)
                putLong("duration", stream.durationMs ?: -1)
                putLong("expiry", stream.expiresAtMs)
            }
        } catch (_: PipeAudioAccessRestrictedException) {
            failure("AGE")
        } catch (e: Exception) {
            failure(CapsuleNewPipeExtractor.classify(e).name)
        } catch (_: LinkageError) {
            failure("EXTRACTOR")
        }
    }

    private fun failure(kind: String) = Bundle().apply { putString("failure", kind) }

    suspend fun resolveRemote(id: String, quality: PipeAudioQuality, session: Any): CapsuleInnerTubeXPlayer.PlaybackData? {
        val context = App.instance.applicationContext
        val uri = Uri.parse("content://${context.packageName}.capsule.video.extractor")
        val extras = Bundle().apply {
            putString(CapsuleVideoIpc.EXTRA_REQUEST_ID, UUID.randomUUID().toString())
            putString(CapsuleVideoIpc.EXTRA_VIDEO_ID, id)
            putString(CapsuleVideoIpc.EXTRA_QUALITY, quality.name)
        }
        val start = System.nanoTime()
        val response = try {
            withTimeoutOrNull(BUDGET_MS + 500L) {
                cancellableRemoteCall(executor, cancellationExecutor,
                    call = { context.contentResolver.call(uri, METHOD, null, extras) },
                    cancel = { context.contentResolver.call(uri, CapsuleVideoIpc.METHOD_CANCEL, null, extras) })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val result = response?.takeIf { it.getBoolean("ok") }?.let {
            PipeAudioStream(it.getString("id").orEmpty(), it.getString("url").orEmpty(),
                it.getInt("itag"), it.getString("mime").orEmpty(), it.getString("codec").orEmpty(),
                it.getInt("bitrate"), it.getLong("length"), it.getInt("rate").takeIf { value -> value > 0 },
                it.getLong("duration").takeIf { value -> value > 0L }, it.getLong("expiry"))
        }?.takeIf { it.valid(id) && (quality != PipeAudioQuality.AAC || it.mimeType == "audio/mp4") }
        val elapsed = (System.nanoTime() - start) / 1_000_000L
        if (result == null) {
            val reason = response?.getString("failure").orEmpty()
            Timber.tag("CapsuleInnerTubeX").i("AUDIO pipepipe outcome=%s id=%s ms=%d",
                reason.takeIf { it in CapsuleNewPipeFailure.entries.map { kind -> kind.name } || it == "AGE" } ?: "unavailable", id, elapsed)
            when (reason) {
                "RATE_LIMITED" -> {
                    CapsulePlaybackSafety.markHttpStatusFailure(429)
                    throw IOException("HTTP 429")
                }
                "BOT_BLOCKED" -> {
                    CapsulePlaybackSafety.noteWireBotCheck()
                    throw IOException("Confirm that you're not a bot")
                }
                "AGE" -> throw AudioEntitlementException(PlaybackFailureKind.AUTH_REQUIRED)
                "UNAVAILABLE" -> throw AudioEntitlementException(PlaybackFailureKind.UNAVAILABLE)
            }
            return null
        }
        Timber.tag("CapsuleInnerTubeX").i("AUDIO pipepipe outcome=ok id=%s itag=%d ms=%d", id, result.itag, elapsed)
        return CapsuleInnerTubeXPlayer.PlaybackData(
            audioConfig = null,
            videoDetails = PlayerResponse.VideoDetails(videoId = id, lengthSeconds = result.durationMs?.div(1000L)?.toString()),
            playbackTracking = null,
            format = PlayerResponse.StreamingData.Format(itag = result.itag, url = result.url,
                mimeType = "${result.mimeType}; codecs=\"${result.codec}\"", bitrate = result.bitrate,
                contentLength = result.length, quality = "", approxDurationMs = result.durationMs?.toString(),
                audioSampleRate = result.sampleRate),
            streamUrl = result.url,
            streamExpiresInSeconds = ((result.expiresAtMs - System.currentTimeMillis()) / 1000L).coerceIn(1L, 21_600L).toInt(),
            streamClient = PIPE_AUDIO_CLIENT,
            streamHeaders = mapOf("User-Agent" to CapsulePipeAudio.userAgent),
            rangeChunkSizeBytes = 0L,
            routeSession = session,
        )
    }
}
