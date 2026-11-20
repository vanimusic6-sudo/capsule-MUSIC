# Capsule MUSIC — thermal / energy audit (20 Nov 2026)

Scope: source review of **actual** main audio, video, Canvas motion, video backdrop, player clocks and offline transfer hot paths, based on `fix/video-artist-catalog-lyrics-20261120`. This is not a measured system-wide power benchmark. No library, extractor or codec was replaced merely to lower a theoretical CPU cost.

## Findings and changes

| Area | Code evidence / root cause | Action |
| --- | --- | --- |
| Silent Canvas / animated artwork | `CapsuleMotionPlaybackSession.play()` started a second ExoPlayer immediately and kept `playWhenReady=true` even without its Immersion sheet. This can silently decode/stream video when player is collapsed, AUDIO is paused, or actual VIDEO mode owns the screen. | Keep a single prepared Canvas player with `playWhenReady=false` initially. Activate only for a mounted, visible and playing motion-artwork surface; pause on release or player/video-mode transition. Preserve timeline and last frame. |
| Live VIDEO wallpaper lag | `CapsuleVideoAmbientBackdrop` crossfaded for **850 ms**, while `CapsuleVideoAmbient` sampled every **700 ms**. New targets could cancel prior fades and rebuilt two CPU-rasterized page textures, visually lagging behind the actual clip. | Restore real Nov 13, 2026 two-dimensional GPU-illuminated backdrop from commit `818e0afb42e4` (180 ms blend, small GPU layer / 112×176 software fallback). |
| Continuous video changes | Default `capsuleFieldChanged` threshold of 0.018 hid gradual video colour changes. | Use the original 0.0045 for live scene publication. Field filtering still rejects short flashes, black bars and color noise. |
| GPU readback heat | `TextureView.getBitmap(48×28)` synchronizes a decoded GPU surface with the UI thread; a fixed 100 ms legacy cadence is wasteful for static scenes, but 700 ms looks stale. | Decoder-frame events only, active ~220 ms cadence. Exponentially quieter sampling (420/660 ms) after unchanged frames. Paused/off-screen capture has no repeating timer. One recycled bitmap used per mounted session. |
| Canvas wallpaper readback | The auxiliary Canvas decoder also ran a frame sampler. | Only while Canvas is visibly active; cap sampling at ~420 ms. Actual VIDEO wallpaper retains its separate responsive sampler. |
| Player wallpaper animations | `rememberCapsuleAnimationTime` already stops in background, on pause, and for non-animated effects; 32 ms full/48 ms compact cadence avoids drawing at 120Hz merely for decorative motion. | Leave unchanged, to avoid stuttering important input/lyrics animations. |
| Progress/readout clock | `Player.kt` polls ~300 ms only for an exposed playing screen, and 1000 ms for compact, not on hidden lyrics/queue/background. | Leave unchanged. |
| Streaming AUDIO + downloads | `AudioCdnRecoveryPlan` and SABR fallback are bounded, `DownloadPlaybackCoexistence` yields bandwidth to the foreground player and download manager stays singleton. | Leave unchanged: no speculative extra client probes, increased audio buffer or aggressive prefetch introduced. |
| VIDEO search | An artist catalog request ran before independent /next and Videos search, resulting in incorrect/extra clips and network work. | Native official match first, two bounded native search variants, optional artist lookup **only when no valid result**. Already loaded catalog is consulted locally; uncertain rows without OMV type require explicit official-video title. |

## Risk and resource assessment

- No new permissions, dependencies, trackers, network services or codecs.
- The restored `RuntimeShader` is gated behind Android API 33. Older Android devices use the Nov 13 112×176 diffused bitmap fallback; it is intentionally sampled at video-frame events rather than on an independent timer.
- Only one external artist browse is permitted in the unsuccessful search fallback (1.8-second budget). Challenge/HTTP-429 verdicts still reach the shared YouTube request guard.
- All artifact textures originate from video frames or small colour fields, not user account secrets; no tokens are logged or passed to the Canvas process.
- Preserve GPL-3.0 obligations for this internal restoration; source commit is `818e0afb42e4`.

## What needs physical-device proof

1. 30–60 min AUDIO-only listen, with screen off/on, log playback stalls and Android battery drain.
2. 20 min VIDEO playback with live wallpaper, checking both animation latency and phone temperature.
3. 20 min Immersion/Canvas with repeated sheet close/reopen and music pause. Verify no hidden Canvas player continues to fetch/decode and that last frame/timeline resume.
4. Offline download during foreground VIDEO/AUDIO buffering, network handoff and idle. Verify downloads are not blocked indefinitely.
5. Test fallback software renderer on an API 32 device. Confirm no bitmap leaks with profiling.
6. Check official clip accuracy on songs where artist pages contain multiple live, lyric and promotional uploads.

Do not claim measured thermal improvement from source inspection or JVM unit tests alone; use Android Studio Energy Profiler / Perfetto, Android battery stats, and real hardware for final verification.
