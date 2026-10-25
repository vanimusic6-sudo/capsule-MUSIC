# Capsule Music — cleanup branch parity audit and video rollback
Date: 2026-10-25

Reference: `cleanup/capsule-player-only` at `aaa5a82a4981ddc6b010fa1baddbdb890e3a5819`.
Working branch: `release/01-core-reliability`. No changes to `main`.

## Exact code comparisons / regression mechanism

| Path | Cleanup reference | Post-reference regression | Remediation |
| --- | --- | --- | --- |
| VIDEO extraction in `CapsuleVideoExtractorProvider.kt` | One isolated NewPipe `resolve()` request, one **20-second** cancellation budget; no direct backend carousel | Changed to up to **five sequential routes** (ANDROID_VR 4s + VISIONOS 4s + PIPEPIPE 10s + NEWPIPE 10s + ANDROID_REEL 4s), up to **36 seconds** extraction. One disabled/slow direct backend could consume seconds before a viable URL. | Reinstated a **single** NewPipe `resolve()` path with the original **20-second** extraction limit. Kept modern IPC, cancellation, explicit failure classification, refresh and process isolation for compatibility. |
| VIDEO stream selection in `CapsuleNewPipeExtractor.kt` | `StreamInfo.getInfo`, original progressive **muxed-first** choice for AUTO/P360; separate audio only when necessary for requested quality | Rewritten to fetch extractor inventory, prefer **indexed DASH** with separate audio and video, then HLS, then adaptive progressive, with muxed fallback. This opened a second audio transport and created new failure cases where picture resolves but its audio cannot. | Restored the **original cleanup implementation of `resolve()`** and original `selectMuxed/selectAdaptive` fallback behavior; current extended result DTO remains for binary compatibility, but actual source is progressive. |
| Video network downloader | `CapsuleNewPipeDownloader.kt` | **No regression:** byte-for-byte identical file SHAs, `e25c695` on both branches. | Left unchanged. |
| Video UI, IPC and playback model | Separate video process, optional video mode | Current app added first-frame retention, cached streams, muxed/DASH/HLS MediaSource, stronger cancellation and UI features. Blindly replacing all files would break the current UI and IPC model, and could revive previous audio regressions. | Retain current separate process, caching, first-frame UI and cancellation **while restoring the exact old extraction/selection behavior**. This is a compatibility-safe *core rollback*, not a byte-for-byte rollback of all video files. |
| `AudioChunkedDataSource.kt` | Bounded audio ranges when length exceeds initial chunk; seek/previews intentionally stage small first range | Introduced `shouldUseOneRequestForShortAudio`: up to **4 MiB** could bypass chunking entirely, conflicting with established transport pacing evidence and restoring full short-track CDN opens. This was speculative optimization, not measured proof of speed improvement. | Removed the whole-short-track override; reinstated established `shouldChunkAudioRequest(length, firstBytes)` path and first-window staging. Kept other tested audio cache/recovery safeguards. |
| Player **More → Download** | Playback/download controls have distinct states | Later shared `YouTubeDownloadMenuItems` closed the sheet when removing a completed/failed download; recent keep-open-on-start omitted a progress animation. Audius and saved offline entries also explicitly called `onDismiss()` on removal. | Both start **and removal** preserve the player menu; comet starts immediately on tap while Media3 adds the queued download and continues on active transfer. Same principle for Audius, with a scoped comet and no destruction of the playlist. |

## Key distinction
The failure where VIDEO resolves a picture but cannot get its audio is **consistent with** the new preference for separate DASH audio/video or adaptive transport, and 18-second starts are **consistent with** cumulative sequential route budgets. This is an evidence-backed code-path regression hypothesis; **device verification of the same clip** is required to confirm its contribution in the user's environment. The old path avoids these extra failure points by preferring muxed progressive streams.

The user approved restoring the old VIDEO engine. Rather than transplanting incompatible old UI/IPC types and deleting newer Media3 stability guards, the **actual selection and backend call sequence** were restored to that reference branch and regression-guarded. It intentionally returns at most the reference's 720p quality for AUTO, rather than trying to retain recent adaptive 1080p experiments.

## Validation to perform
1. Build signed Universal Release and run all `:app` core/unit and `:innertube` tests; compare full suite with `f11295e` historical failures.
2. Device: cold and warm VIDEO open, same song and official video, AUTO/P360/P720, first video and next clip; confirm first **audio byte**, decoder READY, first frame and active signed source. Record p50/p95 and count backend calls (should be **one**, unless a user explicitly retries).
3. Simultaneously run YouTube downloads and foreground audio; confirm video errors do not cancel normal music, and cache/URL cleanup is mediaId-scoped.
4. Start and remove downloads from player More for YouTube, Audius and saved offline files; menu must stay open, comet visible only while work is active, and contents/update actions accurately reflect Media3/MediaStore.
5. Vary network/transport and verify HTTP 403 vs 429, transient timeouts, cache hits and stopped requests; do not bypass server enforcement by switching identities.
6. Recheck returned VIDEO formats: if no progressive muxed format is available, the cleanup reference legitimately falls back to adaptive VIDEO + AUDIO. No universal guarantee of VIDEO audio if YouTube does not serve a compatible track.

## Release caveat
Static source review and passing CI cannot prove that CDN/bot issues have disappeared on the user's phone. Keep a known-good APK and provide a new one only when the target commit builds successfully; avoid claiming production parity without an on-device run.
