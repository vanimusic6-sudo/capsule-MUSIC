#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def patch_music_service(text: str) -> str:
    text = replace_once(
        text,
        '''internal fun shouldEnableAudioOffload(\n    requested: Boolean,\n    crossfadeDurationMs: Int,\n): Boolean = requested && crossfadeDurationMs == 0\n''',
        '''internal fun shouldEnableAudioOffload(\n    requested: Boolean,\n    crossfadeDurationMs: Int,\n): Boolean = requested && crossfadeDurationMs == 0\n\n/** HIGHEST is a retired alias: InnerTubeX maps it to the same stream tier as HIGH. */\ninternal fun AudioQuality.normalizedPlaybackQuality(): AudioQuality =\n    if (this == AudioQuality.HIGHEST) AudioQuality.HIGH else this\n''',
        "MusicService quality normalizer insertion",
    )
    text = replace_once(
        text,
        '''    private fun playbackContext() = AudioPlaybackContext(\n        audioQuality, audioStreamPolicy, connectivityManager.isActiveNetworkMetered,\n    )\n''',
        '''    private fun playbackContext() = AudioPlaybackContext(\n        audioQuality.normalizedPlaybackQuality(),\n        audioStreamPolicy,\n        connectivityManager.isActiveNetworkMetered,\n    )\n''',
        "MusicService playback context normalization",
    )
    text = replace_once(
        text,
        '''        audioQuality = dataStore[AudioQualityKey].toEnum(AudioQuality.AUTO)\n''',
        '''        audioQuality =\n            dataStore[AudioQualityKey]\n                .toEnum(AudioQuality.AUTO)\n                .normalizedPlaybackQuality()\n''',
        "MusicService initial quality normalization",
    )
    text = replace_once(
        text,
        '''                    prefs[AudioQualityKey].toEnum(AudioQuality.AUTO),\n''',
        '''                    prefs[AudioQualityKey]\n                        .toEnum(AudioQuality.AUTO)\n                        .normalizedPlaybackQuality(),\n''',
        "MusicService observed quality normalization",
    )
    return text


def patch_player_settings(text: str) -> str:
    text = replace_once(
        text,
        'import com.nikhil.yt.ui.component.EnumListPreference\n',
        'import com.nikhil.yt.ui.component.ListPreference\n',
        "PlayerSettings ListPreference import",
    )
    text = replace_once(
        text,
        '''    val (audioQuality, onAudioQualityChange) =\n        rememberEnumPreference(\n            AudioQualityKey,\n            defaultValue = AudioQuality.AUTO,\n        )\n''',
        '''    val (audioQuality, onAudioQualityChange) =\n        rememberEnumPreference(\n            AudioQualityKey,\n            defaultValue = AudioQuality.AUTO,\n        )\n    // HIGHEST used to be exposed as “maximum”, but the playback backend maps\n    // it to exactly the same InnerTubeX tier as HIGH. Keep the enum only as a\n    // migration tombstone so existing installs do not break.\n    val effectiveAudioQuality =\n        if (audioQuality == AudioQuality.HIGHEST) AudioQuality.HIGH else audioQuality\n''',
        "PlayerSettings effective quality",
    )
    text = replace_once(
        text,
        '''        EnumListPreference(\n            title = { Text(stringResource(R.string.audio_quality)) },\n            icon = {\n                Icon(\n                    painterResource(R.drawable.graphic_eq),\n                    null,\n                )\n            },\n            selectedValue = audioQuality,\n            onValueSelected = onAudioQualityChange,\n            valueText = {\n                when (it) {\n                    AudioQuality.HIGHEST ->\n                        stringResource(R.string.audio_quality_max)\n                    AudioQuality.HIGH ->\n                        stringResource(R.string.audio_quality_high)\n                    AudioQuality.AUTO ->\n                        stringResource(R.string.audio_quality_auto)\n                    AudioQuality.LOW ->\n                        stringResource(R.string.audio_quality_low)\n                }\n            },\n        )\n''',
        '''        ListPreference(\n            title = { Text(stringResource(R.string.audio_quality)) },\n            icon = {\n                Icon(\n                    painterResource(R.drawable.graphic_eq),\n                    null,\n                )\n            },\n            selectedValue = effectiveAudioQuality,\n            values =\n                listOf(\n                    AudioQuality.AUTO,\n                    AudioQuality.HIGH,\n                    AudioQuality.LOW,\n                ),\n            onValueSelected = onAudioQualityChange,\n            valueText = {\n                when (it) {\n                    AudioQuality.HIGHEST,\n                    AudioQuality.HIGH,\n                    -> stringResource(R.string.audio_quality_high)\n                    AudioQuality.AUTO ->\n                        stringResource(R.string.audio_quality_auto)\n                    AudioQuality.LOW ->\n                        stringResource(R.string.audio_quality_low)\n                }\n            },\n        )\n''',
        "PlayerSettings remove duplicate maximum quality",
    )
    return text


def patch_scheduler(text: str) -> str:
    text = replace_once(
        text,
        '''internal class AudioResolveScheduler(\n    private val monotonicNowMs: () -> Long = { System.nanoTime() / 1_000_000L },\n    private val downloadStartSpacingMs: Long = DOWNLOAD_START_SPACING_MS,\n) {\n''',
        '''internal class AudioResolveScheduler(\n    private val monotonicNowMs: () -> Long = { System.nanoTime() / 1_000_000L },\n    private val downloadStartSpacingMs: Long = DOWNLOAD_START_SPACING_MS,\n    private val promotedPrefetchRestartAfterMs: Long = PROMOTED_PREFETCH_RESTART_AFTER_MS,\n) {\n''',
        "AudioResolveScheduler constructor",
    )
    text = replace_once(
        text,
        '''    private class Ticket(val mediaId: String, var priority: AudioResolvePriority) {\n        val turn = CompletableDeferred<Unit>()\n        var worker: Deferred<*>? = null\n        var preempted = false\n    }\n''',
        '''    private class Ticket(val mediaId: String, var priority: AudioResolvePriority) {\n        val turn = CompletableDeferred<Unit>()\n        var worker: Deferred<*>? = null\n        var workerStartedAtMs: Long? = null\n        var preempted = false\n    }\n''',
        "AudioResolveScheduler ticket timing",
    )
    text = replace_once(
        text,
        '''    fun promote(mediaId: String) = synchronized(lock) {\n        (waiting + listOfNotNull(active)).filter {\n            it.mediaId == mediaId && it.priority == AudioResolvePriority.PREFETCH\n        }.forEach {\n            it.priority = AudioResolvePriority.PLAYBACK\n        }\n        preemptBackground()\n    }\n''',
        '''    fun promote(mediaId: String) = synchronized(lock) {\n        val nowMs = monotonicNowMs()\n        (waiting + listOfNotNull(active)).filter {\n            it.mediaId == mediaId && it.priority == AudioResolvePriority.PREFETCH\n        }.forEach { ticket ->\n            ticket.priority = AudioResolvePriority.PLAYBACK\n            val startedAtMs = ticket.workerStartedAtMs\n            if (\n                active === ticket &&\n                startedAtMs != null &&\n                promotedPrefetchRestartAfterMs >= 0L &&\n                nowMs - startedAtMs >= promotedPrefetchRestartAfterMs\n            ) {\n                // A prefetch that has already spent several seconds inside the\n                // extractor must not hold foreground playback hostage. Restart\n                // only this stale request; young prefetches are still reused.\n                ticket.preempted = true\n                ticket.worker?.cancel(Preempted())\n            }\n        }\n        preemptBackground()\n    }\n''',
        "AudioResolveScheduler stale promotion",
    )
    text = replace_once(
        text,
        '''    suspend fun <T> run(mediaId: String, priority: AudioResolvePriority, block: suspend () -> T): T {\n        while (true) {\n            currentCoroutineContext().ensureActive()\n            val ticket = Ticket(mediaId, priority)\n''',
        '''    suspend fun <T> run(mediaId: String, priority: AudioResolvePriority, block: suspend () -> T): T {\n        var effectivePriority = priority\n        while (true) {\n            currentCoroutineContext().ensureActive()\n            val ticket = Ticket(mediaId, effectivePriority)\n''',
        "AudioResolveScheduler effective priority",
    )
    text = replace_once(
        text,
        '''                    val work = async(start = CoroutineStart.LAZY) {\n                        awaitDownloadStartWindow(ticket.priority)\n                        block()\n                    }\n''',
        '''                    val work = async(start = CoroutineStart.LAZY) {\n                        awaitDownloadStartWindow(ticket.priority)\n                        synchronized(lock) {\n                            ticket.workerStartedAtMs = monotonicNowMs()\n                        }\n                        block()\n                    }\n''',
        "AudioResolveScheduler worker start timestamp",
    )
    text = replace_once(
        text,
        '''            } catch (_: Preempted) {\n                // Only our own preemption is retried. Parent cancellation always propagates.\n                currentCoroutineContext().ensureActive()\n''',
        '''            } catch (_: Preempted) {\n                // Only our own preemption is retried. Parent cancellation always propagates.\n                // A stale PREFETCH promoted by the loader retries as PLAYBACK.\n                effectivePriority = ticket.priority\n                currentCoroutineContext().ensureActive()\n''',
        "AudioResolveScheduler promoted retry priority",
    )
    text = replace_once(
        text,
        '''    private companion object {\n        const val DOWNLOAD_START_SPACING_MS = 4_000L\n    }\n''',
        '''    private companion object {\n        const val DOWNLOAD_START_SPACING_MS = 4_000L\n        const val PROMOTED_PREFETCH_RESTART_AFTER_MS = 4_000L\n    }\n''',
        "AudioResolveScheduler stale threshold",
    )
    return text


def patch_scheduler_test(text: str) -> str:
    anchor = '''    @Test fun parentCancellationIsNeverTreatedAsPreemption() = runTest {\n'''
    addition = '''    @Test fun stalePrefetchPromotionRestartsAsForeground() = runTest {\n        val scheduler =\n            AudioResolveScheduler(\n                monotonicNowMs = { testScheduler.currentTime },\n                promotedPrefetchRestartAfterMs = 4_000L,\n            )\n        var calls = 0\n        val prefetch = async {\n            scheduler.run("next", AudioResolvePriority.PREFETCH) {\n                calls += 1\n                if (calls == 1) {\n                    delay(10_000)\n                    1\n                } else {\n                    7\n                }\n            }\n        }\n        runCurrent()\n        assertEquals(1, calls)\n\n        advanceTimeBy(4_000)\n        scheduler.promote("next")\n        runCurrent()\n\n        assertEquals(7, prefetch.await())\n        assertEquals(2, calls)\n    }\n\n'''
    return replace_once(
        text,
        anchor,
        addition + anchor,
        "AudioResolveSchedulerTest stale promotion regression",
    )


def patch_audio_normalization_test(text: str) -> str:
    text = replace_once(
        text,
        'package com.nikhil.yt.playback\n\n',
        'package com.nikhil.yt.playback\n\nimport com.nikhil.yt.constants.AudioQuality\n',
        "AudioNormalizationTest quality import",
    )
    anchor = '''    @Test\n    fun offloadIsDisabledWhileCrossfadeIsActive() {\n'''
    addition = '''    @Test\n    fun retiredHighestQualityNormalizesToHigh() {\n        assertEquals(AudioQuality.HIGH, AudioQuality.HIGHEST.normalizedPlaybackQuality())\n        assertEquals(AudioQuality.HIGH, AudioQuality.HIGH.normalizedPlaybackQuality())\n        assertEquals(AudioQuality.AUTO, AudioQuality.AUTO.normalizedPlaybackQuality())\n        assertEquals(AudioQuality.LOW, AudioQuality.LOW.normalizedPlaybackQuality())\n    }\n\n'''
    return replace_once(
        text,
        anchor,
        addition + anchor,
        "AudioNormalizationTest quality alias regression",
    )


PATCHES = {
    Path("app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt"): patch_music_service,
    Path("app/src/main/kotlin/com/nikhil/yt/ui/screens/settings/PlayerSettings.kt"): patch_player_settings,
    Path("app/src/main/kotlin/com/nikhil/yt/playback/audio/AudioResolveScheduler.kt"): patch_scheduler,
    Path("app/src/test/kotlin/com/nikhil/yt/playback/audio/AudioResolveSchedulerTest.kt"): patch_scheduler_test,
    Path("app/src/test/kotlin/com/nikhil/yt/playback/AudioNormalizationTest.kt"): patch_audio_normalization_test,
}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    outputs: dict[Path, str] = {}
    for rel, patcher in PATCHES.items():
        path = ROOT / rel
        original = path.read_text(encoding="utf-8")
        patched = patcher(original)
        if patched == original:
            raise SystemExit(f"{rel}: patch produced no change")
        outputs[path] = patched

    if args.check:
        print("step35 patch is applicable")
        return

    for path, patched in outputs.items():
        path.write_text(patched, encoding="utf-8")
        print(f"patched {path.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
