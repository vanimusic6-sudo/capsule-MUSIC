#!/usr/bin/env python3
"""Align Capsule WEB_REMIX recovery with Metrolist/InnerTubeX v0.5.2."""
from __future__ import annotations

import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PLAYER = Path("app/src/main/kotlin/com/nikhil/yt/playback/audio/CapsuleInnerTubeXPlayer.kt")
SERVICE = Path("app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt")
TEST = Path("app/src/test/kotlin/com/nikhil/yt/playback/audio/CapsuleInnerTubeXPlayerTest.kt")


def read(path: Path) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, found {count}")
    return text.replace(old, new, 1)


def patch_player(check_only: bool) -> None:
    text = read(PLAYER)
    if "withEmbeddedConfigFallback" in text:
        raise SystemExit(f"{PLAYER}: final marker already present")

    text = replace_once(
        text,
        "import com.metrolist.innertubex.extraction.TokenProviderCapabilities\n",
        "import com.metrolist.innertubex.extraction.TokenProviderCapabilities\nimport com.metrolist.innertubex.extraction.YtConfigParser\n",
        str(PLAYER),
    )
    text = replace_once(
        text,
        " * - a deterministic EJS failure retires cipher profiles for this session;\n",
        " * - EJS is best-effort: library Faraday/parser fallbacks remain available after QuickJS failures;\n",
        str(PLAYER),
    )
    text = replace_once(
        text,
        "    private const val MAX_CIPHER_FAILURE_DETAIL_LENGTH = 180\n",
        "",
        str(PLAYER),
    )

    breaker_block = '''    /**
     * InnerTubeX deliberately converts QuickJsException into an empty cipher
     * result, so it never reaches the Result failure returned to this class.
     * Observe that library event and retire cipher profiles for the rest of
     * this process session. Network changes must not clear a deterministic
     * player-JS failure.
     */
    @Volatile
    private var cipherSessionFailure: String? = null

'''
    text = replace_once(text, breaker_block, "", str(PLAYER))

    old_resolve = '''                            // Check after waiting: an earlier queued web resolve may
                            // have opened the breaker while this request was waiting.
                            checkCipherSession(playbackClientOverrideId)
                            val extractionBundle = bundle()
                            if (playbackClientOverrideId != "VISIONOS") {
                                val waitStartedAt = System.nanoTime()
                                val preparation = extractionBundle.prewarm.start()
                                val reused = preparation.isCompleted
                                val warmed = preparation.await()
                                Timber.tag(TAG).i(
                                    "Web prewarm awaited id=%s priority=%s selectedProfile=%s reused=%s ok=%s waitedMs=%d",
                                    videoId,
                                    priority,
                                    playbackClientOverrideId,
                                    reused,
                                    warmed.isSuccess,
                                    (System.nanoTime() - waitStartedAt) / 1_000_000L,
                                )
                                checkCipherSession(playbackClientOverrideId)
                            }
                            Timber.tag(TAG).i(
'''
    new_resolve = '''                            // Do not await extractor prewarm on the playback critical path.
                            // InnerTubeX coordinates config/cipher state internally and can resolve
                            // on demand while the optional app-level warmup runs independently.
                            val extractionBundle = bundle()
                            Timber.tag(TAG).i(
'''
    text = replace_once(text, old_resolve, new_resolve, str(PLAYER))

    check_fn = '''    private fun checkCipherSession(clientId: String) {
        cipherSessionFailure?.takeIf { clientId != "VISIONOS" }?.let { failure ->
            throw IllegalStateException(
                "Cipher playback is disabled for this session after EJS failure: $failure",
            )
        }
    }

'''
    text = replace_once(text, check_fn, "", str(PLAYER))

    old_parser = '''                    configParser = YtConfigParserImpl(httpClient, innerTube, remoteStore, logger),
'''
    new_parser = '''                    configParser =
                        YtConfigParserImpl(httpClient, innerTube, remoteStore, logger)
                            .withEmbeddedConfigFallback(),
'''
    text = replace_once(text, old_parser, new_parser, str(PLAYER))

    old_logger = '''    private val logger =
        InnerTubeLogger { event ->
            val cipherEvent = event.tag == "EjsChallengeSolver" && "EJS solve failed" in event.message
            if (!GlobalLog.isEnabled && !cipherEvent) return@InnerTubeLogger
            val details =
                event.details.entries.joinToString(prefix = " [", postfix = "]") {
                    "${it.key}=${it.value}"
                }
            val message = event.message + details.takeUnless { event.details.isEmpty() }.orEmpty()
            if (
                event.tag == "EjsChallengeSolver" &&
                "EJS solve failed" in message &&
                "QuickJsException" in message
            ) {
                if (cipherSessionFailure == null) {
                    cipherSessionFailure = message.take(MAX_CIPHER_FAILURE_DETAIL_LENGTH)
                    Timber.tag(TAG).e(
                        "Cipher session breaker opened after deterministic QuickJS failure",
                    )
                }
            }
            when (event.level) {
'''
    new_logger = '''    private val logger =
        InnerTubeLogger { event ->
            if (!GlobalLog.isEnabled) return@InnerTubeLogger
            val details =
                event.details.entries.joinToString(prefix = " [", postfix = "]") {
                    "${it.key}=${it.value}"
                }
            val message = event.message + details.takeUnless { event.details.isEmpty() }.orEmpty()
            when (event.level) {
'''
    text = replace_once(text, old_logger, new_logger, str(PLAYER))

    insertion_anchor = '''    private fun AudioQuality.toInnerTubeX(
'''
    fallback = '''    /**
     * Match Metrolist's recovery path: a failed regular watch-page config is
     * retried through the anonymous embedded config instead of poisoning WEB
     * playback. The embedded request intentionally never carries login cookies.
     */
    internal fun YtConfigParser.withEmbeddedConfigFallback(): YtConfigParser =
        object : YtConfigParser by this {
            override suspend fun fetchConfig(
                videoId: String,
                useLoginCookies: Boolean,
            ) =
                try {
                    this@withEmbeddedConfigFallback.fetchConfig(videoId, useLoginCookies)
                } catch (_: IllegalStateException) {
                    this@withEmbeddedConfigFallback.fetchEmbeddedConfig(
                        videoId,
                        useLoginCookies = false,
                    )
                }
        }

'''
    text = replace_once(text, insertion_anchor, fallback + insertion_anchor, str(PLAYER))

    if "cipherSessionFailure" in text or "checkCipherSession(" in text:
        raise SystemExit(f"{PLAYER}: stale cipher session breaker remains")
    if "Web prewarm awaited" in text:
        raise SystemExit(f"{PLAYER}: blocking per-resolve prewarm remains")

    if not check_only:
        write(PLAYER, text)


def patch_service(check_only: bool) -> None:
    text = read(SERVICE)
    old = '''        // An explicit selection resets per-track exclusions, not the global
        // bot/rate-limit cooldown or the deterministic QuickJS session breaker.
'''
    new = '''        // An explicit selection resets per-track exclusions, not the global
        // bot/rate-limit cooldown.
'''
    text = replace_once(text, old, new, str(SERVICE))
    if not check_only:
        write(SERVICE, text)


def test_text() -> str:
    return '''package com.nikhil.yt.playback.audio

import com.metrolist.innertubex.extraction.PlayerConfig
import com.metrolist.innertubex.extraction.YtConfigParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CapsuleInnerTubeXPlayerTest {
    @Test
    fun failedWatchPageFallsBackToAnonymousEmbeddedConfig() =
        runBlocking {
            val expected = PlayerConfig("embedded", 123, null, null)
            var embeddedUsesLogin = true
            val parser =
                object : YtConfigParser {
                    override suspend fun fetchConfig(
                        videoId: String,
                        useLoginCookies: Boolean,
                    ): PlayerConfig = error("HTTP 302")

                    override suspend fun fetchEmbeddedConfig(
                        videoId: String,
                        useLoginCookies: Boolean,
                    ): PlayerConfig {
                        embeddedUsesLogin = useLoginCookies
                        return expected
                    }
                }

            val recovered =
                CapsuleInnerTubeXPlayer.run {
                    parser.withEmbeddedConfigFallback()
                }.fetchConfig("song", useLoginCookies = true)

            assertEquals(expected, recovered)
            assertEquals(false, embeddedUsesLogin)
        }
}
'''


def patch_test(check_only: bool) -> None:
    target = ROOT / TEST
    if target.exists():
        raise SystemExit(f"{TEST}: already exists")
    if not check_only:
        write(TEST, test_text())


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    patch_player(args.check)
    patch_service(args.check)
    patch_test(args.check)


if __name__ == "__main__":
    main()
