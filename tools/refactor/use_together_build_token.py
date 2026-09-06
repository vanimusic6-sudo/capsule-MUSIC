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
    if "TogetherSessionRuntime" not in text:
        raise RuntimeError("Step 6 Together runtime extraction must be applied first")
    if "TogetherOnlineCredentials.bearerTokenOrNull()" in text:
        raise RuntimeError("Together online credential wiring is already applied")

    text = replace_once(
        text,
        "import com.nikhil.yt.together.TogetherSessionRuntime\n",
        "import com.nikhil.yt.together.TogetherSessionRuntime\n"
        "import com.nikhil.yt.together.TogetherOnlineCredentials\n",
        "import TogetherOnlineCredentials",
    )
    text = replace_once(
        text,
        '            val togetherToken = "VeluneAdminToken"\n',
        "            val togetherToken = TogetherOnlineCredentials.bearerTokenOrNull()\n",
        "replace online host hardcoded bearer",
    )
    text = replace_once(
        text,
        '            val togetherToken ="velune_server_token"\n',
        "            val togetherToken = TogetherOnlineCredentials.bearerTokenOrNull()\n",
        "replace online guest hardcoded bearer",
    )

    forbidden = ('"VeluneAdminToken"', '"velune_server_token"')
    leftovers = [token for token in forbidden if token in text]
    if leftovers:
        raise RuntimeError(f"hardcoded Together credentials remained: {leftovers}")
    if text.count("TogetherOnlineCredentials.bearerTokenOrNull()") != 2:
        raise RuntimeError("expected exactly two configured Together bearer reads")
    if text.count("if (togetherToken == null)") < 2:
        raise RuntimeError("Together missing-token guards are not present")
    return text


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--file", type=Path, default=DEFAULT_PATH)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    original = args.file.read_text(encoding="utf-8")
    updated = transform(original)
    if args.check:
        print("OK: MusicService matches Together credential wiring preconditions")
        return
    args.file.write_text(updated, encoding="utf-8", newline="\n")
    print(f"Updated {args.file}")


if __name__ == "__main__":
    main()
