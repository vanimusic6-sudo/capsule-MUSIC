#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

DEFAULT_PATH = Path("app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly 1 match, found {count}")
    return text.replace(old, new, 1)


def transform(text: str) -> str:
    if "AudioResolveCoordinator<CapsuleAudioEngine.PlaybackData>" not in text:
        raise RuntimeError("AudioResolveCoordinator must be integrated before step 2")

    if "CapsuleAudioEngine\n                .resolvePlayback(" in text and "avoidStreamCodecs" not in text:
        raise RuntimeError("Legacy audio resolve cleanup is already applied")

    text = replace_once(
        text,
        "import android.media.MediaCodecList\n",
        "",
        "remove MediaCodecList import",
    )

    text = replace_once(
        text,
        "    private val avoidStreamCodecs: Set<String> by lazy {\n"
        "        if (deviceSupportsMimeType(\"audio/opus\")) emptySet() else setOf(\"opus\")\n"
        "    }\n",
        "",
        "remove ignored codec-routing state",
    )

    text = replace_once(
        text,
        "            CapsuleAudioEngine\n"
        "                .playerResponseForPlayback(\n"
        "                    mediaId,\n"
        "                    audioQuality = selection.quality,\n"
        "                    connectivityManager = connectivityManager,\n"
        "                    streamPolicy = selection.policy,\n"
        "                    avoidCodecs = avoidStreamCodecs,\n"
        "                    priority = priority,\n"
        "                )\n",
        "            CapsuleAudioEngine\n"
        "                .resolvePlayback(\n"
        "                    videoId = mediaId,\n"
        "                    audioQuality = selection.quality,\n"
        "                    connectivityManager = connectivityManager,\n"
        "                    streamPolicy = selection.policy,\n"
        "                    priority = priority,\n"
        "                )\n",
        "use canonical audio resolve API",
    )

    text = replace_once(
        text,
        "    private fun deviceSupportsMimeType(mimeType: String): Boolean {\n"
        "        return runCatching {\n"
        "            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)\n"
        "            codecList.codecInfos.any { info ->\n"
        "                !info.isEncoder && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }\n"
        "            }\n"
        "        }.getOrDefault(false)\n"
        "    }\n",
        "",
        "remove unused device codec probe",
    )

    forbidden = (
        "avoidStreamCodecs",
        "deviceSupportsMimeType",
        "MediaCodecList",
    )
    leftovers = [token for token in forbidden if token in text]
    if leftovers:
        raise RuntimeError(f"legacy audio routing remained: {leftovers}")

    required = (
        "AudioResolveCoordinator<CapsuleAudioEngine.PlaybackData>",
        "CapsuleAudioEngine\n                .resolvePlayback(\n                    videoId = mediaId,",
        "streamPolicy = selection.policy",
        "priority = priority",
    )
    missing = [token for token in required if token not in text]
    if missing:
        raise RuntimeError(f"required canonical resolve wiring missing: {missing}")

    return text


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--file", type=Path, default=DEFAULT_PATH)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    path: Path = args.file
    original = path.read_text(encoding="utf-8")
    updated = transform(original)

    if args.check:
        print("OK: coordinator-integrated MusicService matches step-2 audio cleanup")
        return

    path.write_text(updated, encoding="utf-8", newline="\n")
    print(f"Updated {path}")


if __name__ == "__main__":
    main()
