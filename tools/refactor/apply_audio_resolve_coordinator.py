#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import re
from pathlib import Path

EXPECTED_BLOB = "0e9129d60c7e73c771ae002a26f5c36a6ae3704d"
DEFAULT_PATH = Path("app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt")


def git_blob_sha(data: bytes) -> str:
    header = f"blob {len(data)}\0".encode("ascii")
    return hashlib.sha1(header + data).hexdigest()


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly 1 match, found {count}")
    return text.replace(old, new, 1)


def regex_once(text: str, pattern: str, replacement: str, label: str) -> str:
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly 1 regex match, found {count}")
    return updated


def transform(text: str) -> str:
    if "AudioResolveCoordinator<CapsuleAudioEngine.PlaybackData>" in text:
        raise RuntimeError("Audio resolve coordinator is already integrated")

    text = replace_once(
        text,
        "import com.nikhil.yt.playback.audio.AudioPlaybackContext\n"
        "import com.nikhil.yt.playback.audio.AudioResolvePriority\n",
        "import com.nikhil.yt.playback.audio.AudioPlaybackContext\n"
        "import com.nikhil.yt.playback.audio.AudioResolveCoordinator\n"
        "import com.nikhil.yt.playback.audio.AudioResolvePriority\n",
        "add coordinator import",
    )

    for imp in (
        "import kotlinx.coroutines.CompletableDeferred\n",
        "import kotlinx.coroutines.Deferred\n",
        "import kotlinx.coroutines.async\n",
    ):
        if imp not in text:
            raise RuntimeError(f"remove import: missing {imp.strip()}")
        text = text.replace(imp, "", 1)

    text = replace_once(
        text,
        "    private val playbackUrlCache = PlaybackDataCache(currentContext = ::playbackContext)\n",
        "    private val playbackUrlCache = PlaybackDataCache(currentContext = ::playbackContext)\n"
        "    private val audioResolveCoordinator =\n"
        "        AudioResolveCoordinator<CapsuleAudioEngine.PlaybackData>(\n"
        "            scopeProvider = { ioScope },\n"
        "            cachedValue = { mediaId -> playbackUrlCache.get(mediaId) },\n"
        "        )\n",
        "create coordinator",
    )

    ownership_pattern = (
        r"    private val inFlightAudioResolves =\n"
        r"        ConcurrentHashMap<String, Deferred<Result<CapsuleAudioEngine\.PlaybackData>>>\(\)\n\n"
        r"    private val songRecoveryJobs = ConcurrentHashMap<String, Job>\(\)\n"
        r"    private val audioResolveLock = Any\(\)\n"
        r"    private val audioResolveStability = PlaybackStabilityGate\(\)\n\n"
        r"    @Volatile\n"
        r"    private var audioPolicyGeneration = 0L\n\n"
        r"    private fun cancelInFlightAudioResolves\(\) \{.*?\n"
        r"    \}\n\n"
        r"    private fun audioResolveJob\(\n"
        r"        mediaId: String,\n"
        r"    \): Deferred<Result<CapsuleAudioEngine\.PlaybackData>> =\n"
        r"        synchronized\(audioResolveLock\) \{.*?\n"
        r"        \}\n\n"
        r"    /\*\n"
        r"     \* Resolve what is coming next"
    )
    ownership_replacement = '''    private val songRecoveryJobs = ConcurrentHashMap<String, Job>()
    private val songRecoveryLock = Any()
    private val audioResolveStability = PlaybackStabilityGate()

    private fun audioResolveJob(
        mediaId: String,
    ) =
        audioResolveCoordinator.resolve(mediaId) { policyGeneration ->
            audioResolveStability.awaitStable {
                withContext(Dispatchers.Main.immediate) {
                    mediaId == player.currentMediaItem?.mediaId ||
                        mediaId in upcomingAudioIds()
                }
            }
            val selection = playbackContext()
            val priority = withContext(Dispatchers.Main.immediate) {
                if (mediaId == player.currentMediaItem?.mediaId) AudioResolvePriority.PLAYBACK
                else AudioResolvePriority.PREFETCH
            }
            val startedAt = System.currentTimeMillis()
            Timber.tag(CAPSULE_RESOLVE_TAG).i(
                "resolve start id=%s",
                mediaId,
            )
            CapsuleAudioEngine
                .playerResponseForPlayback(
                    mediaId,
                    audioQuality = selection.quality,
                    connectivityManager = connectivityManager,
                    streamPolicy = selection.policy,
                    avoidCodecs = avoidStreamCodecs,
                    priority = priority,
                )
                .also { result ->
                    if (selection != playbackContext()) {
                        throw kotlinx.coroutines.CancellationException("Playback context changed")
                    }
                    result.getOrNull()?.let {
                        cacheResolvedPlayback(mediaId, it, policyGeneration, selection)
                    }
                    Timber.tag(CAPSULE_RESOLVE_TAG).i(
                        "resolve done id=%s ok=%s tookMs=%d",
                        mediaId,
                        result.isSuccess,
                        System.currentTimeMillis() - startedAt,
                    )
                }
        }

    /*
     * Resolve what is coming next'''
    text = regex_once(text, ownership_pattern, ownership_replacement, "move resolve ownership")

    text = replace_once(
        text,
        "        val prefetchGeneration = ++audioPrefetchGeneration\n",
        "        val prefetchGeneration = audioResolveCoordinator.nextPrefetchGeneration()\n",
        "prefetch generation",
    )

    stale_pattern = (
        r"        inFlightAudioResolves\.forEach \{ \(mediaId, job\) ->\n"
        r"            if \(\n"
        r"                mediaId !in relevantIds &&\n"
        r"                inFlightAudioResolves\.remove\(mediaId, job\)\n"
        r"            \) \{\n"
        r"                job\.cancel\(\)\n"
        r"                Timber\.tag\(CAPSULE_RESOLVE_TAG\)\.i\(\n"
        r"                    \"prefetch cancel stale id=%s\",\n"
        r"                    mediaId,\n"
        r"                \)\n"
        r"            \}\n"
        r"        \}"
    )
    stale_replacement = '''        audioResolveCoordinator.cancelStaleExcept(relevantIds).forEach { mediaId ->
            Timber.tag(CAPSULE_RESOLVE_TAG).i(
                "prefetch cancel stale id=%s",
                mediaId,
            )
        }'''
    text = regex_once(text, stale_pattern, stale_replacement, "stale prefetch pruning")

    text = replace_once(
        text,
        "                if (prefetchGeneration != audioPrefetchGeneration) {\n",
        "                if (!audioResolveCoordinator.isPrefetchGenerationCurrent(prefetchGeneration)) {\n",
        "prefetch generation check",
    )
    text = replace_once(
        text,
        "                if (inFlightAudioResolves.containsKey(mediaId)) {\n",
        "                if (audioResolveCoordinator.hasInFlight(mediaId)) {\n",
        "prefetch in-flight check",
    )
    text = replace_once(
        text,
        "    @Volatile\n    private var audioPrefetchGeneration: Long = 0L\n",
        "",
        "remove service prefetch generation",
    )

    text = replace_once(
        text,
        "                synchronized(audioResolveLock) {\n"
        "                    audioPolicyGeneration += 1L\n"
        "                    audioPrefetchGeneration += 1L\n"
        "                    cancelInFlightAudioResolves()\n"
        "                    playbackUrlCache.clear()\n"
        "                }\n",
        "                audioResolveCoordinator.invalidatePolicy(\n"
        "                    invalidatePrefetch = true,\n"
        "                    onInvalidate = playbackUrlCache::clear,\n"
        "                )\n",
        "network policy invalidation",
    )

    text = replace_once(
        text,
        "    ) = synchronized(audioResolveLock) {\n"
        "        if (songRecoveryJobs[mediaId]?.isActive == true) return@synchronized\n",
        "    ) = synchronized(songRecoveryLock) {\n"
        "        if (songRecoveryJobs[mediaId]?.isActive == true) return@synchronized\n",
        "separate recovery lock",
    )

    remaining_cancel_all = text.count("cancelInFlightAudioResolves()")
    if remaining_cancel_all != 4:
        raise RuntimeError(
            f"cancel-all call count changed: expected 4, found {remaining_cancel_all}"
        )
    text = text.replace("cancelInFlightAudioResolves()", "audioResolveCoordinator.cancelAll()")

    text = replace_once(
        text,
        "            synchronized(audioResolveLock) {\n"
        "                inFlightAudioResolves.remove(currentMediaId)?.cancel()\n"
        "                playbackUrlCache.remove(currentMediaId)\n"
        "            }\n",
        "            audioResolveCoordinator.cancelMedia(currentMediaId) {\n"
        "                playbackUrlCache.remove(currentMediaId)\n"
        "            }\n",
        "stream refresh invalidation",
    )

    text = replace_once(
        text,
        "                val alreadyRunning = inFlightAudioResolves.containsKey(mediaId)\n",
        "                val alreadyRunning = audioResolveCoordinator.hasInFlight(mediaId)\n",
        "loader in-flight observation",
    )

    text = replace_once(
        text,
        "        if (generation != audioPolicyGeneration) return\n",
        "        if (!audioResolveCoordinator.isPolicyGenerationCurrent(generation)) return\n",
        "early stale generation rejection",
    )

    text = replace_once(
        text,
        "        synchronized(audioResolveLock) {\n"
        "            if (generation == audioPolicyGeneration) {\n"
        "                resolveContext.ensureActive()\n"
        "                publishResolvedLoudness(mediaId, loudness)\n"
        "                playbackUrlCache.put(mediaId, playback, selection)\n"
        "            }\n"
        "        }\n",
        "        audioResolveCoordinator.publishIfCurrent(generation) {\n"
        "            resolveContext.ensureActive()\n"
        "            publishResolvedLoudness(mediaId, loudness)\n"
        "            playbackUrlCache.put(mediaId, playback, selection)\n"
        "        }\n",
        "atomic resolve publication",
    )

    text = replace_once(
        text,
        "        synchronized(audioResolveLock) {\n"
        "            inFlightAudioResolves.remove(mediaId)?.cancel()\n"
        "            playbackUrlCache.remove(mediaId)\n"
        "        }\n",
        "        audioResolveCoordinator.cancelMedia(mediaId) {\n"
        "            playbackUrlCache.remove(mediaId)\n"
        "        }\n",
        "manual fresh stream invalidation",
    )

    text = replace_once(
        text,
        "        audioPrefetchGeneration += 1L\n"
        "        if (reloadCurrentAudio) player.stop()\n\n"
        "        synchronized(audioResolveLock) {\n"
        "            audioPolicyGeneration += 1L\n"
        "            audioResolveCoordinator.cancelAll()\n"
        "            playbackUrlCache.clear()\n"
        "        }\n",
        "        audioResolveCoordinator.invalidatePrefetches()\n"
        "        if (reloadCurrentAudio) player.stop()\n\n"
        "        audioResolveCoordinator.invalidatePolicy(\n"
        "            invalidatePrefetch = false,\n"
        "            onInvalidate = playbackUrlCache::clear,\n"
        "        )\n",
        "explicit policy reload",
    )

    forbidden = (
        "inFlightAudioResolves",
        "audioResolveLock",
        "audioPolicyGeneration",
        "audioPrefetchGeneration",
        "cancelInFlightAudioResolves",
        "CompletableDeferred",
        "Deferred<Result<CapsuleAudioEngine.PlaybackData>>",
    )
    leftovers = [token for token in forbidden if token in text]
    if leftovers:
        raise RuntimeError(f"legacy resolve ownership remained: {leftovers}")

    required = (
        "private val audioResolveCoordinator",
        "private val songRecoveryLock = Any()",
        "audioResolveCoordinator.publishIfCurrent(generation)",
        "audioResolveCoordinator.cancelStaleExcept(relevantIds)",
        "audioResolveCoordinator.invalidatePolicy(",
    )
    missing = [token for token in required if token not in text]
    if missing:
        raise RuntimeError(f"required integration missing: {missing}")

    return text


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--file", type=Path, default=DEFAULT_PATH)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    path: Path = args.file
    data = path.read_bytes()
    actual_blob = git_blob_sha(data)

    if actual_blob != EXPECTED_BLOB:
        decoded = data.decode("utf-8", errors="replace")
        if "AudioResolveCoordinator<CapsuleAudioEngine.PlaybackData>" in decoded:
            raise SystemExit("Audio resolve coordinator already integrated")
        raise SystemExit(
            "Refusing to edit an unreviewed MusicService.kt.\n"
            f"Expected git blob: {EXPECTED_BLOB}\n"
            f"Actual git blob:   {actual_blob}"
        )

    original = data.decode("utf-8")
    updated = transform(original)

    if args.check:
        print("OK: exact reviewed MusicService matched and step-1 transform is applicable")
        return

    path.write_text(updated, encoding="utf-8", newline="\n")
    print(f"Updated {path}")


if __name__ == "__main__":
    main()
