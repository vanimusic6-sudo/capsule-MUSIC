# VIDEO session rebuild — October 11, 2026

Base: `58b1d73` on `fix/video-stable-incremental-20261011`. Work is isolated in `refactor/video-session-rebuild-20261011`; stable branches are preserved.

## Observed failures

The October 11 16:39 log records first frames taking 4–8 seconds, a frame/soundtrack timeout, repeated prepares, and no-match requests. The recording `8963.mp4` shows the video window appearing inside the Immersion background. These are observations from older builds, not a device test of this change.

## New ownership model

The canonical MUSIC player owns its queue and controls. A separate VIDEO session owns only clip decoding, readiness, surface attachment and sound transfer. VIDEO never changes the MUSIC MediaItem or uses the MUSIC stream resolver. The primary source factory now handles MUSIC and SoundCloud only; persisted synthetic items from earlier versions normalize to their canonical MUSIC ID. The old VIDEO extraction from the primary loader and duplicate adaptive source creation are removed.

States distinguish extraction, preparation, playback and recovery. Preparation keeps the surface attached; visibility is not conditional on receiving a first frame. Tracks, READY and first-frame callbacks can arrive in any order. A selected clip soundtrack plus actual playback and a post-alignment video frame are required before MUSIC is muted. The clip aligns to the latest MUSIC position after buffering rather than a stale position captured before extraction. A generation guards asynchronous sound-transfer completion. The requested small sound seam is 115 ms toward VIDEO and 105 ms toward MUSIC.

Both player designs now use Media3's Compose ContentFrame/PlayerSurface, FIT scaling and an opaque black shutter. Page-colored masks and the PlayerView artwork layer are removed. A disposed older component cannot clear the new component's surface ownership. Surface/foreground transitions preserve an established clip's track selection, timeline and soundtrack: disabling/re-enabling VIDEO could reselect a muxed period, so it is no longer done. Media3 owns the codec output/placeholder when the UI is absent. Reopening a committed session starts no seek or new sound seam. Background energy and device codec behavior require device measurement. Selected VIDEO mode survives without extracting again on return. A request without a visible surface does not consume a first-frame timeout. The watchdog exists during preparation/stalls only, not during healthy playback.

## Extraction and recovery

In the credential-free `:capsule_video` process, the direct VISIONOS route now establishes its own anonymous visitor session (reused for six hours), then obtains the player response from the mobile API endpoint. Bootstrap plus player share a five-second budget. The previous request omitted the visitor session and discarded HLS when present in that same response. Valid HLS is now preferred, with checked GoogleVideo host/expiry. Before segment loading, master playlists exclude every rendition above the requested cap (never above 720p) and retain original audio groups. Oversized/unknown-only video fails into recovery. HLS uses the isolated VIDEO transport without the progressive Range wrapper; Media3 handles its synchronization/adaptation.

Complete MP4 formats remain a fallback. Wrong IDs, expired URLs, unresolved `n`/signature challenges, baked partial ranges and silent adaptive video are rejected. NewPipe remains the parser/cipher fallback; its adapter requests streams without optional related metadata/previews. A direct-route timeout can fall back; request-wide cancellation stops all work. AUDIO visitor/tokens/cipher configuration is not shared. Logs separately measure matching, extraction and tap-to-first-frame. No numeric speed improvement has yet been measured on HONOR.

A runtime VIDEO network failure restores MUSIC audibility, then allows at most two recovery attempts for the already matched clip: fresh NewPipe URLs, then one muxed-stream fallback. Recovery does not repeat the official-clip search. Request generation, current song and selected mode are checked before publishing recovery. Bot/rate-limit cooldown stops recovery. Metadata invalidation and refresh occur in the same IPC request. Each prepared source pins its exact resolved formats rather than consulting a mutable latest-quality cache.

Large YouTube reads remain bounded to 4 MiB. A malformed/refused YouTube range now fails into VIDEO recovery rather than opening an unbounded whole-file request. Continuation ranges must keep the resource length consistent. Generic non-YouTube compatibility fallback is retained.

## Verification scope

CI covers the existing player/navigation/energy regressions plus decoder event ordering, alignment after slow preparation, pause/cancellation during the sound seam, detached surfaces, retaining clip sound in the background, immediate MUSIC fallback during buffering, direct-format validation, pinned sources and bounded ranges. Debug and signed Release are built from this branch. No tests simulate YouTube server acceptance or the HONOR native codec/network stack.

Final runtime acceptance still needs the HONOR: first frame, pause/resume, seek, rapid AUDIO/VIDEO toggles, next/previous, all player designs, Home/lock/return, 40+ minute videos and a real bot/CDN refusal. A successful build does not establish that these device/network scenarios work.

## Alternatives checked — October 12 local time

- Native Media3 HLS: implemented segmented delivery, audio/video synchronization and adaptation. https://developer.android.com/media/media3/exoplayer/hls
- Media3 Compose output: replaces PlayerView interop; black initial shutter and FIT letterboxing. https://developer.android.com/media/media3/ui/surface
- NewPipeExtractor 0.26.5: already current published release at inspection; keep process-isolated fallback. Visitor/player sequence inspected in YoutubeStreamHelper. https://github.com/TeamNewPipe/NewPipeExtractor/releases
- yt-dlp / legacy Android VR: current source documents 403 for VR 1.65.10; do not add it as a reliable bypass. https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/_base.py
- SABR/UMP via GoogleVideo / YouTube.js: requires a protocol/runtime/player adapter, not a drop-in Media3 URL backend. Researched, not implemented or claimed tested. https://github.com/LuanRT/googlevideo

Additional regressions cover HLS ceiling/audio retention, malformed manifests, paused reopen, and detached surfaces without reselection/seek/new sound seam. Lint previously reported seven errors on the base and is advisory in the existing workflow; report it separately from tests and APK builds.
