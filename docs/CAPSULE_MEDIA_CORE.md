# Capsule MUSIC media core — maintainer notes

This document describes the **current implementation**. Code is GPL-3.0, like the Capsule/Velune project. It is intended to help downstream forks change one subsystem without accidentally breaking another.

## Ownership and lifecycle

| Concern | Owner | Never do |
| --- | --- | --- |
| AUDIO metadata, stream extraction, client/PoToken selection | `CapsuleAudioEngine` → `CapsuleInnerTubeXPlayer` → InnerTubeX | Rebuild cookies or impersonate a different client just to repeat the same signed URL |
| AUDIO direct CDN | `MusicService.createCacheDataSource` → `AudioChunkedDataSource` → host health + OkHttp | Treat a 403/410/429 as a bad TCP host; send an arbitrary signed URL to another hostname |
| AUDIO SABR | `CapsuleSabrAudioDataSource` → InnerTubeX `SabrAudioStream` | Treat UMP segments as ordinary HTTP range responses |
| Playback recovery | `PlaybackRecoveryCoordinator`, `AudioCdnRecoveryPlan`, `CapsuleSabrRoutePolicy` | Unlimited retry/client churn; reset anti-bot cooldown on a transient failure |
| Offline downloads | `DownloadUtil` → `ResolvingAudioDownloader` → `DownloadAudioTransport` | Share the player's live signed URL, host-health state, or network sockets |
| Song → official VIDEO ID | `YouTubeMusicVideoLinkResolver`, `YouTubeMusicVideoMatcher` | Use the first similar-looking search result, lyric/cover/live/fan upload, or a different artist |
| VIDEO extraction | `YouTubeVideoResolver` → Web InnerTubeX or isolated `:capsule_video` routes | Send AUDIO secrets into the legacy extractor IPC |
| VIDEO playback | `CapsuleVideoCompanion` / Media3 / separate VIDEO cache | Silence AUDIO before VIDEO has an actual decoded frame and a working soundtrack |

## AUDIO: normal playback

1. Select the requested media ID and quality. Stabilization/cancellation prevent discarded rapid-skip entries from issuing speculative network requests.
2. InnerTubeX performs a bounded client resolve. Direct GVS uses a signed HTTPS URL and the format's identity.
3. `AudioChunkedDataSource` handles bounded byte ranges, Opus read-ahead and one eligible same-link socket resume. The host-health wrapper reports actual body-read failures; its health memory is reset on a network change.
4. Playback cache uses a format-aware identity. Do not mix different codec/itag/length or SABR partial spans.
5. On genuine CDN trouble, the recovery coordinator may advance Direct clients, choose SABR (if supported) and, after a SABR failure, return to Direct. **Forced SABR** is a deliberately strict developer diagnostic and never silently switches to Direct.
6. A successful foreground track resets short-lived failure budgets only after sustained healthy playback. HTTP 429 and explicit bot checks stop client rotation.

The target buffer duration is a **maximum**, not a guarantee that the CDN will supply all those seconds. Always diagnose first-byte delays, buffered growth and codec underruns separately.

## Offline downloads

`DownloadUtil` is a singleton: all callers and the Media3 service must share **one** `DownloadManager` / index. The offline executor has separate signed URLs, a separate direct HTTP client and separate `AudioCdnHostHealth`, with history cleared on network changes.

`createDownloadAudioTransport` applies the same Direct CDN chunk/recovery policy as playback, but the outer `DownloadYieldingDataSource` yields to AUDIO and VIDEO buffering. Download retries must be bounded. HTTP 403/410 may invalidate a signed link and change the download's client. HTTP 429 **must not** trigger identity cycling. An interrupted network handoff must not resume an old IP-bound signed URL.

A complete, format-identified player-cache entry may be copied locally without any YouTube request. Never mark a partial or truncated file complete; artwork is repaired after audio completion and may not hold the download at 99%.

**SABR is not yet an offline transfer mode**. Unlike a direct URL, a UMP session needs separate renewal and exact byte-range/resume verification. Its current seek adapter can replay audio from byte zero. Do not turn it on for `ProgressiveDownloader` until seek, crash-resume and content-length tests exist.

## VIDEO: match, extract, render

1. The source Music track ID must first be paired to an official counterpart. The verified YouTube Music `/next` counterpart is stronger evidence than search text.
2. If `/next` is slow, one delayed anonymous Videos query may overlap it. A fast counterpart cancels that speculative query. Authenticated metadata remains serialized. `CapsuleVideoMetadataAdmission` enforces exactly one request admission across these stages.
3. Exact normalized title, artist and, when known, plausible duration must match. Reject lyric, karaoke, visualizer, fan-made, live or other variants unless the requested recording itself is that variant and policy allows it.
4. Keep the matched **clip ID** even when its signed stream URL fails. A recovery must re-extract the same clip, never run a fresh text search and accidentally switch to another performance.
5. The Web InnerTubeX extractor has a bounded primary budget; independent `:capsule_video` routes can fall back to PipePipe, VisionOS, Android VR, NewPipe or reel. A previously healthy route wins; failed/bot-blocked routes cool down. PipePipe's cold probe is explicitly bounded.
6. The VIDEO player has its own Media3 player, codecs, CDN transport, cache and lifecycle. For indexed DASH, use full ISO-8601 duration to initialize near the progressing AUDIO clock and avoid repeated cold seeks.
7. Keep MUSIC audible until VIDEO is ready and its **first frame has been rendered**, then hand off the soundtrack. Any VIDEO failure before handoff must leave normal MUSIC running.

## Safety / energy invariants

- All network addresses and redirect targets must remain validated HTTPS YouTube/GVS endpoints; no credentials in logs, IPC or generic errors.
- Reject stale player/stream results after account, VPN, network, media-ID or playback-generation changes.
- Never spin on zero-byte reads or poll a paused player at high frequency. Keep speculative VIDEO and download activity lower priority than audible playback.
- Cap per-track retry counts, map sizes, byte-range sizes and timeouts. Cancellation is **not** proof that a server or client is unhealthy.
- Background processors are isolated: VIDEO parser crashes may not kill MUSIC. Keep format caches and signed URLs scoped to each process/role.
- Avoid introducing new binary dependencies without reviewing their license and security advisories. This revision adds none.

## Regression suites and diagnostics

- `Capsule Hybrid Audio Regression`: SABR/data-source edge cases, buffer stalls, Direct CDN recovery, format-bound cache, download manager singleton.
- `Capsule Media Core Regression`: official video matcher, metadata admission/cancellation, VIDEO routes, start-near-audio DASH duration, safe download ranges.
- Android: `:app:assembleUniversalDebug`, optimized `:app:assembleUniversalDevRelease` for a separate developer signer; test builds cannot replace production signatures.

Useful structured tags: `CapsuleHybrid`, `AudioCDN`, `PlaybackRecovery`, `AudioDownload`, `CapsuleVideo`, `VIDEO timing`.

**Device validation still required**: hour-long podcasts, cold VIDEO start, rapid skip, partial CDN failures, seek during source replacement, cross-network reconnect, resume-after-process-death, album downloads, user-login content, and thermal behavior over time.
