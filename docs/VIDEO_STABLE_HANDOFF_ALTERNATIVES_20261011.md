# Capsule VIDEO: guarded handoff and extractor alternatives (2026-10-11)

**Base:** the tested `c77358d` audio kernel + prior `bfef0b2` VIDEO URL dedup/range work. Changes live only in `fix/video-standalone-handoff-20261011`.

## Root causes fixed

- Previously, AUDIO and VIDEO shared ONE ExoPlayer. `replaceMediaItem`, `seekTo` and `prepare` stopped the original AUDIO stream before the VIDEO decoder received a first frame and decoded its soundtrack. The 256KiB HTTP warmup helped networking but **cannot** prepare Media3 decoders.
- The current experiment instead keeps the primary AUDIO MediaSession and queue untouched. Only explicit VIDEO selection constructs a secondary Media3 player with a source factory accepting the **already-resolved signed CDN URLs**, not the AUDIO MediaSourceFactory or its ResolvingDataSource. Its cache, connection pool, HTTP dispatcher and request guard are VIDEO-only.
- The secondary player starts with volume zero. Sound handoff is **forbidden** until: Media3 first frame rendered, READY, original VIDEO audio track selected, AUDIO user intent `playWhenReady`, and VIDEO `isPlaying`. On confirmation, AUDIO output is muted, a brief 115ms silent transition occurs, then VIDEO output is enabled. On VIDEO failure or next AUDIO item, VIDEO is stopped and MUSIC volume restored synchronously.
- Player screen now mounts the VIDEO renderer during `BUFFERING`, before first-frame callback. The service marks `PLAYING` only when sound and image readiness are satisfied. Immersion gradient covers the pending surface, inherits the same color source, and never applies a scale animation to `SurfaceView` (which can cause compositor tearing/misalignment).
- The previous redundant NewPipe resolve during `DataSource.open` was eliminated in `bfef0b2`. This branch also **removes the serial VIDEO warmup HTTP round trip**; the video decoder itself starts buffering while AUDIO continues to play. VIDEO media remains bounded to 4MiB per range request; AUDIO's 1MiB behavior remains untouched.
- NewPipe explicit anonymous `LOGIN_REQUIRED` bot-checks are upstream metadata/playability failures, not caused by loading entire video files. Reducing redundant requests helps load but cannot guarantee that YouTube allows streaming.

## Verified alternative extractors / trade-offs (no untested dependency shipped)

**SimpMusic / SmartTube-derived** (SimpMusic repo MIT): Android client supports up to 1080p VIDEO and says its stream extraction draws from SmartTube. Its independent extraction and client handling merit research for a **new isolated VIDEO-only worker**. However, upstream may also be limited by YouTube token/PO requirements. Its source needs precise technical/license audit before porting.

**PipePipe / PipePipeExtractor** (NewPipe-based; GPL-family): useful endpoint handling, SABR/DASH pathways and yt-dlp history, but **NOT independent of NewPipe's family**, and can suffer the very same `AntiBotException`. PipePipe's July 2026 issue #2637 documents anonymous endpoint trade-offs; issue #2698 shows route/IP false positives.

**yt-dlp via youtubedl-android** (GPL-3.0 wrapper): genuinely different Python extraction engine with mature metadata/format handling. Heavy Android binary/Python packaging, cold-start cost, update strategy and compatibility/API surface; adding into production app for an emergency fallback would increase APK size and heat. Run only in its own process, with fixed ONE attempt and no cascade on explicit 429/bot challenge, after performance and licensing tests.

**Piped/Invidious**: an externally operated proxy is a different network identity and potential alternate stream source, but trust, uptime, privacy, geographic blocks, YouTube policy and third-party dependence make it unsuitable for silent automatic fallback without user opt-in.

**Conclusion:** do not automatically switch extractors on a verified bot challenge. The first goal of this branch is deterministic audio isolation and quick first frame. Actual independent fallback can be added behind a guarded interface after device proof and permission/licensing review.

## Verification checklist

- User tests VIDEO after an AUDIO track starts, in Immersion and Light/Cosmo; first frame should precede the sound handoff, with a ~115ms deliberate gap, and no album-art flash.
- VIDEO CDN black/timeout/bot challenge: must keep AUDIO playable, including NEXT/previous track, and never return a VIDEO URI to MUSIC queue.
- Pause, seek and next-track: original AUDIO playback intent leads; no stale companion streams or phantom audio.
- Screen off/background: VIDEO immediately stops and AUDIO recovers; unlock never triggers a new anonymous request.
- A long video: Media3 ranges remain max 4MiB, and no whole-file request.
- CI compilation/unit tests are NOT proof of on-device YouTube network availability or smooth audio mixing.

### Source review
- SimpMusic https://github.com/maxrave-dev/SimpMusic
- PipePipe https://github.com/InfinityLoop1308/PipePipe/issues/2637 and https://github.com/InfinityLoop1308/PipePipe/issues/2698
- yt-dlp Android https://github.com/yausername/youtubedl-android
