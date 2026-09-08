#!/usr/bin/env python3
"""Corrected entry point for final review step 26.

Reuses the validated step26 transformations and narrows the playlist screen
replacement so manual pull-to-refresh stays immediate while only entry refresh
gets the automatic cooldown flag.
"""
from __future__ import annotations

import argparse
import importlib.util
from pathlib import Path

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location(
    "step26_base",
    HERE / "harden_final_review_step26.py",
)
if SPEC is None or SPEC.loader is None:
    raise SystemExit("Unable to load step26 base hardener")
step26 = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(step26)


def patch_library_screens(check_only: bool) -> None:
    patches = {
        "app/src/main/kotlin/com/nikhil/yt/ui/screens/library/LibrarySongsScreen.kt": [
            ("SongFilter.LIKED -> viewModel.syncLikedSongs()", "SongFilter.LIKED -> viewModel.syncLikedSongs(automatic = true)"),
            ("SongFilter.LIBRARY -> viewModel.syncLibrarySongs()", "SongFilter.LIBRARY -> viewModel.syncLibrarySongs(automatic = true)"),
        ],
        "app/src/main/kotlin/com/nikhil/yt/ui/screens/library/LibraryAlbumsScreen.kt": [
            ("viewModel.sync()", "viewModel.sync(automatic = true)"),
        ],
        "app/src/main/kotlin/com/nikhil/yt/ui/screens/library/LibraryArtistsScreen.kt": [
            ("viewModel.sync()", "viewModel.sync(automatic = true)"),
        ],
    }

    for rel, replacements in patches.items():
        text = step26.read(rel)
        step26.assert_absent(text, "automatic = true", rel)
        for old, new in replacements:
            text = step26.replace_once(text, old, new, rel)
        if not check_only:
            step26.write(rel, text)

    rel = "app/src/main/kotlin/com/nikhil/yt/ui/screens/library/LibraryPlaylistsScreen.kt"
    text = step26.read(rel)
    step26.assert_absent(text, "viewModel.sync(automatic = true)", rel)
    old = """    LaunchedEffect(Unit) {\n        if (ytmSync) {\n            withContext(Dispatchers.IO) {\n                viewModel.sync()\n            }\n        }\n    }\n"""
    new = """    LaunchedEffect(Unit) {\n        if (ytmSync) {\n            withContext(Dispatchers.IO) {\n                viewModel.sync(automatic = true)\n            }\n        }\n    }\n"""
    text = step26.replace_once(text, old, new, rel)
    if not check_only:
        step26.write(rel, text)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    step26.patch_safety(args.check)
    step26.patch_downloads(args.check)
    step26.patch_sync(args.check)
    step26.patch_viewmodels(args.check)
    patch_library_screens(args.check)
    step26.patch_app_prewarm(args.check)

    if args.check:
        print("final review step26 v2 preconditions OK")
    else:
        print("final review step26 v2 applied")


if __name__ == "__main__":
    main()
