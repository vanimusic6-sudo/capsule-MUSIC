# VIDEO session rebuild — October 11, 2026

Base: `58b1d73` on `fix/video-stable-incremental-20261011`. Work is isolated in `refactor/video-session-rebuild-20261011`; stable branches are preserved.

## Observed failures

The October 11 16:39 log records first frames taking 4–8 seconds, a frame/soundtrack timeout, repeated prepares, and no-match requests. The recording `8963.mp4` shows the video window appearing inside the Immersion background. These are observations from older builds, not a device test of this change.

## New ownership model

The canonical MUSIC player owns its queue and controls. A separate VIDEO session owns only clip decoding, readiness, surface attachment and sound transfer. VIDEO never changes the MUSIC MediaItem or uses the MUSIC stream resolver. The primary source factory now handles MUSIC and SoundCloud only; persisted synthetic items from earlier versions normalize to their canonical MUSIC ID. The old VIDEO extraction from the primary loader and duplicate adaptive source creation are removed.

States distinguish extraction, preparation, playback and recovery. Preparation keeps an invisible surface attached while artwork remains visible. Surface attachment is separate from presentation: a playing clip is revealed only after a decoded frame, selected soundtrack and completed sound handoff. Paused preparation may show a decoded still without taking sound. Tracks, READY and first-frame callbacks can arrive in any order. A selected clip soundtrack plus actual playback and a post-alignment video frame are required before MUSIC is muted. The clip aligns to the latest MUSIC position after buffering rather than a stale position captured before extraction. A generation guards asynchronous sound-transfer completion. The sound seam is 180 ms toward VIDEO followed by an 80 ms gain ramp, and 150 ms toward MUSIC. Volume updates preserve the ramp gain. Seek, pause, buffering or generation cancellation prevents a stale handoff from revealing/unmuting a clip.

Both player designs now use Media3's Compose ContentFrame/PlayerSurface, FIT scaling and an opaque black shutter. Page-colored masks and the PlayerView artwork layer are removed. A disposed older component cannot clear the new component's surface ownership. Surface/foreground transitions preserve an established clip's track selection, timeline and soundtrack: disabling/re-enabling VIDEO could reselect a muxed period, so it is no longer done. Media3 owns the codec output/placeholder when the UI is absent. Reopening a committed session starts no seek or new sound seam. Background energy and device codec behavior require device measurement. Selected VIDEO mode survives without extracting again on return. A request without a visible surface does not consume a first-frame timeout. The watchdog exists during preparation/stalls only, not during healthy playback.

## Extraction and recovery

In the credential-free `:capsule_video` process, the direct VISIONOS route now establishes its own anonymous visitor session (reused for six hours), then obtains the player response from the mobile API endpoint. Bootstrap plus player are bounded by a four-second route budget. The previous request omitted the visitor session and discarded HLS when present in that same response. Valid HLS is now preferred, with checked GoogleVideo host/expiry. Before segment loading, master playlists exclude every rendition above the requested cap (never above 720p) and retain original audio groups. Oversized/unknown-only video fails into recovery. HLS uses the isolated VIDEO transport without the progressive Range wrapper; Media3 handles its synchronization/adaptation.

Complete MP4 formats remain a fallback. Wrong IDs, expired URLs, unresolved `n`/signature challenges, baked partial ranges and silent adaptive video are rejected. NewPipe remains the parser/cipher fallback; its adapter requests streams without optional related metadata/previews. A direct-route timeout can fall back; request-wide cancellation stops all work. AUDIO visitor/tokens/cipher configuration is not shared. Logs separately measure matching, extraction and tap-to-first-frame. No numeric speed improvement has yet been measured on HONOR.

A runtime VIDEO network failure restores MUSIC audibility, then allows at most two recovery attempts for the already matched clip: fresh URLs through a different backend first, then one muxed-stream fallback. The previous NewPipe-only recovery restriction is removed. Recovery does not repeat the official-clip search. Request generation, current song and selected mode are checked before publishing recovery. Bot/rate-limit cooldown stops recovery. Metadata invalidation and refresh occur in the same IPC request. Each prepared source pins its exact resolved formats rather than consulting a mutable latest-quality cache.

Large YouTube reads remain bounded to 4 MiB. A malformed/refused YouTube range now fails into VIDEO recovery rather than opening an unbounded whole-file request. Continuation ranges must keep the resource length consistent. Generic non-YouTube compatibility fallback is retained.

## Verification scope

CI covers the existing player/navigation/energy regressions plus decoder event ordering, alignment after slow preparation, pause/cancellation during the sound seam, detached surfaces, retaining clip sound in the background, retaining VIDEO sound ownership/presentation during ordinary buffering and restoring MUSIC on terminal failure, direct-format validation, pinned sources and bounded ranges. Debug and signed Release are built from this branch. No tests simulate YouTube server acceptance or the HONOR native codec/network stack.

Final runtime acceptance still needs the HONOR: first frame, pause/resume, seek, rapid AUDIO/VIDEO toggles, next/previous, all player designs, Home/lock/return, 40+ minute videos and a real bot/CDN refusal. A successful build does not establish that these device/network scenarios work.

## Alternatives checked — October 12 local time

- Native Media3 HLS: implemented segmented delivery, audio/video synchronization and adaptation. https://developer.android.com/media/media3/exoplayer/hls
- Media3 Compose output: replaces PlayerView interop; black initial shutter and FIT letterboxing. https://developer.android.com/media/media3/ui/surface
- NewPipeExtractor 0.26.5: already current published release at inspection; keep process-isolated fallback. Visitor/player sequence inspected in YoutubeStreamHelper. https://github.com/TeamNewPipe/NewPipeExtractor/releases
- yt-dlp / legacy Android VR: current source documents 403 for VR 1.65.10; do not add it as a reliable bypass. https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/_base.py
- SABR/UMP via GoogleVideo / YouTube.js: requires a protocol/runtime/player adapter, not a drop-in Media3 URL backend. Researched, not implemented or claimed tested. https://github.com/LuanRT/googlevideo

Additional regressions cover HLS ceiling/audio retention, malformed manifests, paused reopen, and detached surfaces without reselection/seek/new sound seam. Lint previously reported seven errors on the base and is advisory in the existing workflow; report it separately from tests and APK builds.


## October 12 — presentation/sound commit and independent Reel fallback

The user reported a six-second empty black stage and a visible clip over the MUSIC soundtrack before the sound switch. Both UI designs now keep artwork during resolution/preparation, render to an attached hidden TextureView, then reveal video only after the session completes the quiet seam and gain ramp. The rendering surface is never made conditional on presentation readiness; that would prevent first-frame delivery. Artwork and video crossfade using compositor alpha.

The route chain is VISIONOS guest player → Android Reel guest endpoint → NewPipe parser/cipher fallback. Android Reel accepts only a matching `videoDetails.videoId` and a complete muxed MP4 with audio, trusted CDN URL, valid expiry, no unresolved cipher/n/range, and height within the requested ceiling. It can return lower quality when no larger complete stream is available. It uses its own guest visitor, no MUSIC credentials and no NewPipe initialization. Recovery moves the failed backend to the end. Each route runs once, network budgets are 4/4/10 seconds within the existing 20-second request deadline. A route timeout cancels its HTTP calls without cancelling the next route. Request cancellation or HTTP 429 stops the chain; an unsatisfied bot verdict is preserved for VIDEO cooldown.

Android Reel request shape was adapted from `YoutubeStreamExtractor.fetchAndroidReelMuxedFormats` in InfinityLoop1308/PipePipeExtractor, commit `586325e33ed374ffddd62364dd20c26a999fb107`, under GPL-3.0-or-later. Upstream attribution: Copyright (C) Christian Schabesberger 2019; NewPipe/PipePipe contributors. Source: https://github.com/InfinityLoop1308/PipePipeExtractor/blob/586325e33ed374ffddd62364dd20c26a999fb107/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/extractors/YoutubeStreamExtractor.java

BravePipe/PipePipe were inspected as maintained forks, but they share NewPipe's Java namespaces; adding them as simultaneous unrelocated dependencies would conflict. Current PipePipe supports SABR via a session transport, requiring a player/transport adapter rather than a normal signed URL. yt-dlp documents client-specific proof-token requirements and failure cases. No public fallback is assumed to bypass a service-wide IP/bot denial.

Additional regressions exercise presentation after the quiet seam/ramp, buffering cancellation during the ramp, paused first-frame presentation, exact-ID Reel selection, independent fallback without NewPipe, failed-backend recovery order, rate limit/cancellation stopping and a route deadline cancelling the actual socket while allowing the next route. Native Android/HONOR surface behavior and live YouTube acceptance of the new Reel route are not established by unit tests or builds.


## October 12 07:00 device log — local quota and timeline buffering

The attached `capsule-logs-2026-10-12_07-00-52.txt` identifies build `5f53093` on SDK 36. Initial clips successfully resolved through `direct-visionos` HLS, then `VIDEO request quota exhausted` blocked exact extraction and even valid CDN playlist/segment reads. These failures were raised locally by CapsuleVideoRequestGuard, not a server bot/rate-limit response. The old shared bucket held 20 tokens, charged each stream request 0.34 tokens, and refilled only one token per 20 seconds; segmented video therefore spent the allowance intended for subsequent metadata requests.

Healthy VIDEO no longer has a finite lifetime bucket. Only metadata/search admission remains paced (900–1500 ms between reservations, bounded queue); normal CDN playlists, segments, initialization data and progressive ranges do not spend or delay those reservations. Real bot/HTTP 429 cooldown and repeated 401/403 feedback are preserved for both surfaces. Cancelling the latest queued metadata request releases its unused reservation. AUDIO policy/transport/extraction remains separate.

A VIDEO session commits after the initial frame and sound seam/ramp (or a fully prepared paused frame). Once committed, seek, pause, ordinary buffering and detached output retain VIDEO ownership and presentation. MUSIC remains muted while the clip buffers; Media3 produces silence until its own soundtrack resumes. No extra sound seam or artwork reveal occurs. ContentFrame retains its last image during temporary track/reset events. First-frame readiness is cleared only before initial presentation or when the session is replaced/stopped. Actual decoder first-frame events remain logged, while presentation-ready is published once per session.

Committed buffering has a dedicated phase that keeps the surface selected and drives the UI loading indicator. Visible player/mini progress reads the clip timeline after presentation, so the silent canonical MUSIC clock does not make the displayed position advance while VIDEO is buffering. Canonical queue and transport commands stay on MUSIC and seeks continue to forward to the companion. A 12-second persistent VIDEO stall still stops the session and enters bounded recovery; a paused clip has no active stall timeout. There are no additional background progress timers. VIDEO keeps the screen awake while play intent is active, including buffering.

Regressions cover 35 healthy clip selections with 3500 media reads followed by another metadata request, CDN reads during a queued metadata reservation, cancellation releasing a reservation, a real rate-limit stopping CDN reads, repeated playing/paused timeline seeks retaining presentation/ownership, no second seam after rebuffering, and terminal stall restoration. Native HONOR picture retention and live network behavior still require device confirmation.
