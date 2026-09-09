#!/usr/bin/env python3
from pathlib import Path
import sys

CHECK = "--check" in sys.argv


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"anchor missing in {path}: {old[:160]!r}")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"anchor not unique in {path}: count={count}")
    if not CHECK:
        p.write_text(text.replace(old, new, 1))
        print(f"patched {path}")


# The first Step36 patch adds this constructor argument. Keep source compatibility
# with the existing coordinator tests/callers by defaulting progress to the
# already-supplied healthy playback predicate. MusicService still overrides it
# with the less strict READY-only predicate used by transport recovery.
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/PlaybackRecoveryCoordinator.kt",
    "    private val recoveryProgressProvider: (String) -> Boolean,\n",
    "    private val recoveryProgressProvider: (String) -> Boolean = healthyPlaybackProvider,\n",
)

# Healthy playback must clear both the retry budget and the one-shot no-stream
# recovery claim. Do it through the single public reset method instead of only
# resetting the numeric budget.
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/PlaybackRecoveryCoordinator.kt",
    "                    retryBudget.reset(mediaId)\n",
    "                    resetRetry(mediaId)\n",
)

# The classifier was initially inserted as a MusicService member, which makes it
# unavailable to a pure unit test. Move it to file scope; behavior is unchanged.
member_classifier = '''    internal fun PlaybackException.isNoPlayableStreamFailure(): Boolean =
        generateSequence(this as Throwable?) { it?.cause }
            .take(8)
            .any { throwable ->
                val message = throwable?.message.orEmpty()
                message.contains("No playable stream found for this track", ignoreCase = true) ||
                    message.contains("InnerTubeX returned no playable AUDIO stream", ignoreCase = true)
            }

    private fun PlaybackException.isTransientNetworkFailure(): Boolean {
'''
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt",
    member_classifier,
    "    private fun PlaybackException.isTransientNetworkFailure(): Boolean {\n",
)

top_level_anchor = '''internal fun AudioQuality.normalizedPlaybackQuality(): AudioQuality =
    if (this == AudioQuality.HIGHEST) AudioQuality.HIGH else this

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
'''
top_level_replacement = '''internal fun AudioQuality.normalizedPlaybackQuality(): AudioQuality =
    if (this == AudioQuality.HIGHEST) AudioQuality.HIGH else this

/**
 * A deterministic extractor miss is recoverable once with a clean same-policy
 * resolve. Keep this classification narrow: generic REMOTE_ERROR must not turn
 * into an automatic request loop.
 */
internal fun PlaybackException.isNoPlayableStreamFailure(): Boolean =
    generateSequence(this as Throwable?) { it?.cause }
        .take(8)
        .any { throwable ->
            val message = throwable?.message.orEmpty()
            message.contains("No playable stream found for this track", ignoreCase = true) ||
                message.contains("InnerTubeX returned no playable AUDIO stream", ignoreCase = true)
        }

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
'''
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt",
    top_level_anchor,
    top_level_replacement,
)

# Root transport fix from the expanded field log. AUDIO media requests are GETs
# against an already-resolved CDN URL. Let OkHttp recover a broken pooled socket
# or TLS connection itself. This does NOT call InnerTube/player again, does not
# rotate clients, and remains underneath the existing bounded Media3 recovery.
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt",
    '''        val audioHttpClient = mediaOkHttpClient.newBuilder().retryOnConnectionFailure(false)
            .addInterceptor(CapsuleAudioRequestInterceptor(guardStreams = true)).build()
''',
    '''        val audioHttpClient =
            mediaOkHttpClient
                .newBuilder()
                // Safe transport-level reconnect for an already-resolved CDN GET.
                // No player/InnerTube request or client rotation happens here.
                .retryOnConnectionFailure(true)
                .addInterceptor(CapsuleAudioRequestInterceptor(guardStreams = true))
                .build()
''',
)

if CHECK:
    print("step36 finalizer is applicable")
