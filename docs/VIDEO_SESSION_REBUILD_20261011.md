# VIDEO session rebuild — October 11, 2026

Base: `58b1d73` on `fix/video-stable-incremental-20261011`. Work is isolated in `refactor/video-session-rebuild-20261011`; stable branches are preserved.

## Observed failures

The October 11 16:39 log records first frames taking 4–8 seconds, a frame/soundtrack timeout, repeated prepares, and no-match requests. The recording `8963.mp4` shows the video window appearing inside the Immersion background. These are observations from older builds, not a device test of this change.

## New ownership model

The canonical MUSIC player owns its queue and controls. A separate VIDEO session owns only clip decoding, readiness, surface attachment and sound transfer. VIDEO never changes the MUSIC MediaItem or uses the MUSIC stream resolver. The primary source factory now handles MUSIC and SoundCloud only; persisted synthetic items from earlier versions normalize to their canonical MUSIC ID. The old VIDEO extraction from the primary loader and duplicate adaptive source creation are removed.

States distinguish extraction, preparation, playback and recovery. Preparation keeps the surface attached; visibility is not conditional on receiving a first frame. Tracks, READY and first-frame callbacks can arrive in any order. A selected clip soundtrack plus actual playback and a post-alignment video frame are required before MUSIC is muted. The clip aligns to the latest MUSIC position after buffering rather than a stale position captured before extraction. A generation guards asynchronous sound-transfer completion. The requested small sound seam is 115 ms toward VIDEO and 105 ms toward MUSIC.

Both player designs use one TextureView-based component with explicit attachment/release. A disposed older view cannot clear the new view's surface ownership. When minimized or collapsed, an established clip keeps its own soundtrack, while VIDEO track decoding is disabled. Selected VIDEO mode survives without extracting again on return. A request without a visible surface does not consume a first-frame timeout. The watchdog exists during preparation/stalls only, not during healthy playback.

## Extraction and recovery

In the credential-free `:capsule_video` process, one anonymous VISIONOS player request first checks for complete directly playable MP4 formats. It has a five-second request budget. Wrong IDs, expired URLs, unresolved `n`/signature challenges, baked partial ranges, silent adaptive video and quality above the requested cap are rejected. NewPipe remains the fallback for its maintained parser/cipher work. There is no client carousel, random external proxy or interaction with AUDIO tokens/cipher configuration.

A runtime VIDEO network failure restores MUSIC audibility, then allows at most two recovery attempts for the already matched clip: fresh NewPipe URLs, then one muxed-stream fallback. Recovery does not repeat the official-clip search. Request generation, current song and selected mode are checked before publishing recovery. Bot/rate-limit cooldown stops recovery. Metadata invalidation and refresh occur in the same IPC request. Each prepared source pins its exact resolved formats rather than consulting a mutable latest-quality cache.

Large YouTube reads remain bounded to 4 MiB. A malformed/refused YouTube range now fails into VIDEO recovery rather than opening an unbounded whole-file request. Continuation ranges must keep the resource length consistent. Generic non-YouTube compatibility fallback is retained.

## Verification scope

CI covers the existing player/navigation/energy regressions plus decoder event ordering, alignment after slow preparation, pause/cancellation during the sound seam, detached surfaces, retaining clip sound in the background, immediate MUSIC fallback during buffering, direct-format validation, pinned sources and bounded ranges. Debug and signed Release are built from this branch. No tests simulate YouTube server acceptance or the HONOR native codec/network stack.

Final runtime acceptance still needs the HONOR: first frame, pause/resume, seek, rapid AUDIO/VIDEO toggles, next/previous, all player designs, Home/lock/return, 40+ minute videos and a real bot/CDN refusal. A successful build does not establish that these device/network scenarios work.
