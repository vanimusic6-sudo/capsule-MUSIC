#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

SERVICE = Path("app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt")
RECOVERY = Path("app/src/main/kotlin/com/nikhil/yt/playback/PlaybackRecoveryCoordinator.kt")
RECOVERY_TEST = Path("app/src/test/kotlin/com/nikhil/yt/playback/PlaybackRecoveryCoordinatorTest.kt")

SERVICE_FIELD_ANCHOR = "    private val togetherShutdownGate = TogetherShutdownGate()\n"
SERVICE_RECOVERY_ARG_OLD = "            currentPositionProvider = { player.currentPosition },\n"
SERVICE_RECOVERY_ARG_NEW = "            positionGenerationProvider = playbackPositionGeneration::snapshot,\n"
ON_EVENTS_ANCHOR = "    override fun onEvents(player: Player, events: Player.Events) {\n"
ON_EVENTS_NEW = '''    override fun onEvents(player: Player, events: Player.Events) {
        if (events.contains(EVENT_POSITION_DISCONTINUITY)) {
            playbackPositionGeneration.markDiscontinuity()
        }
'''
STREAM_CAPTURE_OLD = '''        val retryPosition = player.currentPosition
        val retryIndex = player.currentMediaItemIndex
        val retryPlayWhenReady = player.playWhenReady
'''
STREAM_CAPTURE_NEW = '''        val retryPosition = player.currentPosition
        val retryIndex = player.currentMediaItemIndex
        val retryPlayWhenReady = player.playWhenReady
        val retryPositionGeneration = playbackPositionGeneration.snapshot()
'''
STREAM_STALE_OLD = "                    player.currentPosition != retryPosition ||\n"
STREAM_STALE_NEW = "                    !playbackPositionGeneration.isCurrent(retryPositionGeneration) ||\n"

RECOVERY_PARAM_OLD = "    private val currentPositionProvider: () -> Long,\n"
RECOVERY_PARAM_NEW = "    private val positionGenerationProvider: () -> Long,\n"
RECOVERY_CAPTURE_OLD = "        val position = currentPositionProvider()\n"
RECOVERY_CAPTURE_NEW = "        val positionGeneration = positionGenerationProvider()\n"
RECOVERY_STALE_OLD = "                    currentPositionProvider() != position\n"
RECOVERY_STALE_NEW = "                    positionGenerationProvider() != positionGeneration\n"
TEST_ARG_OLD = "currentPositionProvider = { 0L },"
TEST_ARG_NEW = "positionGenerationProvider = { 0L },"


def require_count(source: str, needle: str, count: int, label: str) -> None:
    actual = source.count(needle)
    if actual != count:
        raise SystemExit(f"{label}: expected {count} occurrences, found {actual}")


def validate_before(service: str, recovery: str, test: str) -> None:
    require_count(service, SERVICE_FIELD_ANCHOR, 1, "service field anchor")
    require_count(service, SERVICE_RECOVERY_ARG_OLD, 1, "service recovery position provider")
    require_count(service, ON_EVENTS_ANCHOR, 1, "onEvents anchor")
    require_count(service, STREAM_CAPTURE_OLD, 1, "stream retry capture")
    require_count(service, STREAM_STALE_OLD, 1, "stream retry stale position check")
    require_count(recovery, RECOVERY_PARAM_OLD, 1, "recovery position provider parameter")
    require_count(recovery, RECOVERY_CAPTURE_OLD, 1, "recovery captured position")
    require_count(recovery, RECOVERY_STALE_OLD, 1, "recovery stale position check")
    require_count(test, TEST_ARG_OLD, 2, "recovery test position providers")

    forbidden = [
        "private val playbackPositionGeneration = PlaybackPositionGeneration()",
        "retryPositionGeneration = playbackPositionGeneration.snapshot()",
        "positionGenerationProvider = playbackPositionGeneration::snapshot",
    ]
    for marker in forbidden:
        if marker in service:
            raise SystemExit(f"position-generation hardening already present: {marker}")


def transform(service: str, recovery: str, test: str) -> tuple[str, str, str]:
    service = service.replace(
        SERVICE_FIELD_ANCHOR,
        SERVICE_FIELD_ANCHOR + "    private val playbackPositionGeneration = PlaybackPositionGeneration()\n",
        1,
    )
    service = service.replace(SERVICE_RECOVERY_ARG_OLD, SERVICE_RECOVERY_ARG_NEW, 1)
    service = service.replace(ON_EVENTS_ANCHOR, ON_EVENTS_NEW, 1)
    service = service.replace(STREAM_CAPTURE_OLD, STREAM_CAPTURE_NEW, 1)
    service = service.replace(STREAM_STALE_OLD, STREAM_STALE_NEW, 1)

    recovery = recovery.replace(RECOVERY_PARAM_OLD, RECOVERY_PARAM_NEW, 1)
    recovery = recovery.replace(RECOVERY_CAPTURE_OLD, RECOVERY_CAPTURE_NEW, 1)
    recovery = recovery.replace(RECOVERY_STALE_OLD, RECOVERY_STALE_NEW, 1)

    test = test.replace(TEST_ARG_OLD, TEST_ARG_NEW)
    return service, recovery, test


def validate_after(service: str, recovery: str, test: str) -> None:
    required_service = [
        "private val playbackPositionGeneration = PlaybackPositionGeneration()",
        "positionGenerationProvider = playbackPositionGeneration::snapshot",
        "playbackPositionGeneration.markDiscontinuity()",
        "val retryPositionGeneration = playbackPositionGeneration.snapshot()",
        "!playbackPositionGeneration.isCurrent(retryPositionGeneration)",
    ]
    for marker in required_service:
        if marker not in service:
            raise SystemExit(f"missing service hardening marker: {marker}")

    required_recovery = [
        "private val positionGenerationProvider: () -> Long",
        "val positionGeneration = positionGenerationProvider()",
        "positionGenerationProvider() != positionGeneration",
    ]
    for marker in required_recovery:
        if marker not in recovery:
            raise SystemExit(f"missing recovery hardening marker: {marker}")

    if "player.currentPosition != retryPosition" in service:
        raise SystemExit("stream retry still uses exact currentPosition equality")
    if "currentPositionProvider() != position" in recovery:
        raise SystemExit("network recovery still uses exact currentPosition equality")
    if TEST_ARG_OLD in test:
        raise SystemExit("recovery tests still use removed position provider")
    require_count(test, TEST_ARG_NEW, 2, "transformed recovery test generation providers")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    service = SERVICE.read_text()
    recovery = RECOVERY.read_text()
    test = RECOVERY_TEST.read_text()
    validate_before(service, recovery, test)

    if args.check:
        print("Playback retry generation hardening preconditions satisfied")
        return

    service, recovery, test = transform(service, recovery, test)
    validate_after(service, recovery, test)
    SERVICE.write_text(service)
    RECOVERY.write_text(recovery)
    RECOVERY_TEST.write_text(test)
    print("Playback retries now invalidate on discontinuity generation instead of millisecond drift")


if __name__ == "__main__":
    main()
