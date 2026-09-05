#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
from pathlib import Path

SERVICE_PATH = Path("app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt")
INTERCEPTOR_PATH = Path(
    "app/src/main/kotlin/com/nikhil/yt/playback/video/CapsuleVideoStreamInterceptor.kt"
)

INTERCEPTOR_SOURCE = """/*
 * Capsule MUSIC
 * GPL-3.0
 */

package com.nikhil.yt.playback.video

import com.nikhil.yt.innertube.CapsuleVideoRequestGuard
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Owns request pacing and HTTP-status feedback for actual VIDEO media bytes.
 * Metadata/search/extractor requests are guarded in the innertube layer; this
 * interceptor closes the remaining gap for googlevideo/CDN stream requests.
 */
internal class CapsuleVideoStreamInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        try {
            runBlocking {
                CapsuleVideoRequestGuard.beforeStreamProbe()
            }
        } catch (blocked: CapsuleVideoRequestGuard.RequestBlockedException) {
            throw IOException(
                blocked.message ?: "YouTube VIDEO stream requests are temporarily paused",
                blocked,
            )
        }

        val response = chain.proceed(chain.request())
        CapsuleVideoRequestGuard.noteStreamStatus(response.code)
        return response
    }
}
"""


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly 1 match, found {count}")
    return text.replace(old, new, 1)


def regex_replace_once(text: str, pattern: str, replacement: str, label: str) -> str:
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.DOTALL)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly 1 match, found {count}")
    return updated


def transform_service(text: str) -> str:
    if "AudioResolveCoordinator<CapsuleAudioEngine.PlaybackData>" not in text:
        raise RuntimeError("Step 3 requires the audio coordinator refactor first")

    text = replace_once(
        text,
        "import com.nikhil.yt.innertube.YouTube\n"
        "import com.nikhil.yt.innertube.YouTubeFailureClassifier\n"
        "import com.nikhil.yt.innertube.YouTubeFailureKind\n",
        "import com.nikhil.yt.innertube.CapsuleVideoRequestGuard\n"
        "import com.nikhil.yt.innertube.YouTube\n",
        "central VIDEO guard import",
    )

    text = replace_once(
        text,
        "import com.nikhil.yt.playback.video.CapsuleCacheRoutingDataSource\n"
        "import com.nikhil.yt.playback.video.YouTubeVideoResolver\n",
        "import com.nikhil.yt.playback.video.CapsuleCacheRoutingDataSource\n"
        "import com.nikhil.yt.playback.video.CapsuleVideoStreamInterceptor\n"
        "import com.nikhil.yt.playback.video.YouTubeVideoResolver\n",
        "VIDEO stream interceptor import",
    )

    text = replace_once(
        text,
        "    private var videoRequestBackoffUntilMs = 0L\n",
        "",
        "remove service-owned VIDEO backoff state",
    )

    text = regex_replace_once(
        text,
        r"\n    private fun canAttemptCapsuleVideoNow\(\): Boolean =.*?"
        r"\n    private fun isDefiniteNoVideoMatch\(throwable: Throwable\): Boolean \{",
        "\n    private fun isDefiniteNoVideoMatch(throwable: Throwable): Boolean {",
        "remove duplicate VIDEO breaker methods",
    )

    text = replace_once(
        text,
        "        if (isVideoRequestBackoffActive()) {\n",
        "        if (CapsuleVideoRequestGuard.isBlocked()) {\n",
        "read central VIDEO breaker",
    )

    text = replace_once(
        text,
        "                    if (!noMatchingVideo) {\n"
        "                        maybeOpenVideoCircuitBreaker(throwable)\n"
        "                    }\n\n",
        "",
        "remove duplicate VIDEO resolve failure trip",
    )

    text = replace_once(
        text,
        "        maybeOpenVideoCircuitBreaker(\n"
        "            IllegalStateException(message),\n"
        "        )\n",
        "",
        "remove duplicate VIDEO playback failure trip",
    )

    text = replace_once(
        text,
        "                            maybeOpenVideoCircuitBreaker(throwable)\n",
        "",
        "remove duplicate muxed-resolve failure trip",
    )

    text = regex_replace_once(
        text,
        r"    private fun createVideoCacheDataSource\(\): CacheDataSource\.Factory =\n"
        r"        CacheDataSource\n"
        r"            \.Factory\(\)\n"
        r"            \.setCache\(videoCache\)\n"
        r"            \.setUpstreamDataSourceFactory\(\n"
        r"                DefaultDataSource\.Factory\(\n"
        r"                    this,\n"
        r"                    OkHttpDataSource\.Factory\(mediaOkHttpClient\),\n"
        r"                \),\n"
        r"            \)\n"
        r"            \.setFlags\(FLAG_IGNORE_CACHE_ON_ERROR\)",
        "    private fun createVideoCacheDataSource(): CacheDataSource.Factory {\n"
        "        val videoHttpClient =\n"
        "            mediaOkHttpClient\n"
        "                .newBuilder()\n"
        "                .retryOnConnectionFailure(false)\n"
        "                .addInterceptor(CapsuleVideoStreamInterceptor())\n"
        "                .build()\n\n"
        "        return CacheDataSource\n"
        "            .Factory()\n"
        "            .setCache(videoCache)\n"
        "            .setUpstreamDataSourceFactory(\n"
        "                DefaultDataSource.Factory(\n"
        "                    this,\n"
        "                    OkHttpDataSource.Factory(videoHttpClient),\n"
        "                ),\n"
        "            )\n"
        "            .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)\n"
        "    }",
        "guard VIDEO stream HTTP client",
    )

    text = replace_once(
        text,
        'Timber.tag("MusicService").d("Presence manager started with token=$key")',
        'Timber.tag("MusicService").d("Presence manager started")',
        "remove Discord credential from logs",
    )

    forbidden = (
        "videoRequestBackoffUntilMs",
        "canAttemptCapsuleVideoNow",
        "isVideoRequestBackoffActive",
        "maybeOpenVideoCircuitBreaker",
        "YouTubeFailureClassifier",
        "YouTubeFailureKind",
        "Presence manager started with token",
    )
    leftovers = [token for token in forbidden if token in text]
    if leftovers:
        raise RuntimeError(f"Step 3 leftovers remained in MusicService: {leftovers}")

    required = (
        "CapsuleVideoRequestGuard.isBlocked()",
        "CapsuleVideoStreamInterceptor()",
        ".retryOnConnectionFailure(false)",
        'Timber.tag("MusicService").d("Presence manager started")',
    )
    missing = [token for token in required if token not in text]
    if missing:
        raise RuntimeError(f"Step 3 required wiring missing: {missing}")

    return text


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    original_service = SERVICE_PATH.read_text(encoding="utf-8")
    updated_service = transform_service(original_service)

    if INTERCEPTOR_PATH.exists():
        existing = INTERCEPTOR_PATH.read_text(encoding="utf-8")
        if existing != INTERCEPTOR_SOURCE:
            raise RuntimeError(
                f"Refusing to overwrite unexpected existing {INTERCEPTOR_PATH}"
            )

    if args.check:
        print("OK: MusicService matches guarded VIDEO stream extraction preconditions")
        return

    SERVICE_PATH.write_text(updated_service, encoding="utf-8", newline="\n")
    INTERCEPTOR_PATH.parent.mkdir(parents=True, exist_ok=True)
    INTERCEPTOR_PATH.write_text(INTERCEPTOR_SOURCE, encoding="utf-8", newline="\n")
    print(f"Updated {SERVICE_PATH}")
    print(f"Created {INTERCEPTOR_PATH}")


if __name__ == "__main__":
    main()
