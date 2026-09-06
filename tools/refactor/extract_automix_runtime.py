#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
from pathlib import Path

DEFAULT_PATH = Path("app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly 1 match, found {count}")
    return text.replace(old, new, 1)


def function_bounds(text: str, signature: str) -> tuple[int, int]:
    start = text.find(signature)
    if start < 0:
        raise RuntimeError(f"missing function: {signature}")
    brace = text.find("{", start)
    if brace < 0:
        raise RuntimeError(f"missing opening brace for: {signature}")

    depth = 0
    end = None
    for index in range(brace, len(text)):
        char = text[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                end = index + 1
                break
    if end is None:
        raise RuntimeError(f"unterminated function: {signature}")

    while end < len(text) and text[end] == "\n":
        end += 1
    return start, end


def replace_function(text: str, signature: str, replacement: str) -> str:
    start, end = function_bounds(text, signature)
    return text[:start] + replacement + text[end:]


def rename_symbol(text: str, old: str, new: str, minimum_count: int) -> str:
    pattern = re.compile(rf"\b{re.escape(old)}\b")
    count = len(pattern.findall(text))
    if count < minimum_count:
        raise RuntimeError(
            f"rename {old}: expected at least {minimum_count} references, found {count}"
        )
    return pattern.sub(new, text)


def transform(text: str) -> str:
    if "private val audioEffectsController =" not in text:
        raise RuntimeError("Step 9 audio-effects extraction must be applied first")
    if "private val automixRuntime = AutomixRuntime()" in text:
        raise RuntimeError("Automix runtime extraction is already applied")

    old_fields = """    private var scrobbleManager: com.nikhil.yt.utils.ScrobbleManager? = null

    val automixItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val automixLoading = MutableStateFlow(false)
    val automixError = MutableStateFlow<String?>(null)
    private var automixJob: Job? = null
    private var automixSeedMediaId: String? = null

    val autoAddedMediaIds: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
"""
    new_fields = """    private var scrobbleManager: com.nikhil.yt.utils.ScrobbleManager? = null

    private val automixRuntime = AutomixRuntime()
    val automixItems = automixRuntime.items
    val automixLoading = automixRuntime.loading
    val automixError = automixRuntime.error
    val autoAddedMediaIds = automixRuntime.autoAddedMediaIds
"""
    text = replace_once(text, old_fields, new_fields, "replace Automix mutable fields")

    old_recovery = """        val currentAutomix = withContext(Dispatchers.Main.immediate) {
            automixJob.takeIf { automixSeedMediaId == mediaId }
        }
"""
    new_recovery = """        val currentAutomix = withContext(Dispatchers.Main.immediate) {
            automixRuntime.jobForSeed(mediaId)
        }
"""
    text = replace_once(text, old_recovery, new_recovery, "delegate Automix recovery job lookup")

    old_dedupe = (
        "        if (automixSeedMediaId == seedMediaId && "
        "(automixItems.value.isNotEmpty() || automixJob?.isActive == true)) return\n"
    )
    new_dedupe = "        if (automixRuntime.hasItemsOrActiveJobFor(seedMediaId)) return\n"
    text = replace_once(text, old_dedupe, new_dedupe, "delegate Automix seed/job dedupe")

    clear_function = """    fun clearAutomix() {
        automixRuntime.clear()
    }

"""
    text = replace_function(text, "    fun clearAutomix()", clear_function)

    # All remaining uses are policy code that still lives in MusicService in
    # this step. Rename the state accesses mechanically so behavior is not
    # changed while ownership moves behind AutomixRuntime.
    text = rename_symbol(text, "automixJob", "automixRuntime.job", minimum_count=4)
    text = rename_symbol(text, "automixSeedMediaId", "automixRuntime.seedMediaId", minimum_count=6)

    forbidden = (
        "private var automixJob",
        "private var automixSeedMediaId",
        "java.util.Collections.synchronizedSet(mutableSetOf())",
        "automixJob",
        "automixSeedMediaId",
    )
    leftovers = [token for token in forbidden if token in text]
    if leftovers:
        raise RuntimeError(f"legacy Automix runtime ownership remained: {leftovers}")

    required = (
        "private val automixRuntime = AutomixRuntime()",
        "val automixItems = automixRuntime.items",
        "val automixLoading = automixRuntime.loading",
        "val automixError = automixRuntime.error",
        "val autoAddedMediaIds = automixRuntime.autoAddedMediaIds",
        "automixRuntime.jobForSeed(mediaId)",
        "automixRuntime.hasItemsOrActiveJobFor(seedMediaId)",
        "automixRuntime.clear()",
        "automixRuntime.job",
        "automixRuntime.seedMediaId",
    )
    missing = [token for token in required if token not in text]
    if missing:
        raise RuntimeError(f"required Automix runtime wiring missing: {missing}")

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
        print("OK: MusicService matches Automix runtime extraction preconditions")
        return

    path.write_text(updated, encoding="utf-8", newline="\n")
    print(f"Updated {path}")


if __name__ == "__main__":
    main()
