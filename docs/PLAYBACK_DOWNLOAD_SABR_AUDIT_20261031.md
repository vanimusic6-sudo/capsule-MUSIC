# Capsule Music — playback / downloads / HONOR / SABR source audit (31 Oct 2026)

**Branch:** `fix/playback-download-fairness-sabr-harness-20261031`. This work is stacked on PR #63, #62, #60, #61; no changes were merged into `main`. Device confirmation is required.

## Evidence vs hypothesis

The 16:02–16:10 HONOR Android 17 field log (DEV `df02705`) included 31 *completed* audio downloads and 30 queued with internal waiting `stopReason=2`, plus one terminal /player timeout. Thus “downloads never work” cannot be concluded from the Media3 index alone; UI display, offline library visibility and true network throughput must be tested independently. No audio underruns occurred in that particular capture; an earlier capture did report an AudioTrack underrun despite substantial Media3 buffer ahead. A renderer underrun with buffered source data is not proof of a CDN problem.

The prior playback experiment additionally captured mid-chunk socket resets and unexpectedly many Opus cache-tail read() calls. PR #62 contains the bounded same-signed-link read recovery and hot-path correction; neither can by itself guarantee the absence of system AudioTrack underruns.

## Fault domains and boundaries

| Layer | Verified source behavior | Failure mode to distinguish |
| --- | --- | --- |
| Android SystemUI / HONOR | PR #63 removes a system-only exported service permission, restores trusted-controller authorization, and removes aggressive artwork replacement/notification polling | Session controller cannot bind; missing Media3 notification; OEM feature toggle or incompatible allowlist. Avoid claiming OEM Magic Capsule is guaranteed for third-party players. |
| Foreground player | Media3 1.11.1; MediaLibrarySession; `ResolvingDataSource` and player-cache routing; source resolution uses priority `PLAYBACK` | Source `STATE_BUFFERING`, renderer AudioTrack underrun, lost audio focus, invalid URL after network change, OEM route change; record distinctly. |
| Download manager | Media3 `DownloadManager.maxParallelDownloads=2`, shared two-file transfer slots, `ProgressiveDownloader` and format-aware complete-cache check | 2 file transfers can compete for bandwidth with foreground playback while already-resolved media is being read; scheduler priority was only applied to /player resolution, not to CDN body transfers. |
| Download resolver | Separate clients/cache/bot gate; shared `AudioResolveScheduler` gives playback priority | Genuine /player timeout, 403/410 file expiry/refusal, 429 explicit cooldown and cancellation must not all be classified as the same “server throttling”. |
| Signed CDN | Playback has bounded Range requests and redirects guard; downloader has its own OkHttp client | In-flight TCP reads, socket resets, public network exit changes, redirects to unsuitable host, incomplete cache length. |
| SABR/UMP | InnerTubeX v0.11.2 already contains `SabrAudioStream`, `SabrVideoStream`, bootstrap and strict response protections | UMP is an encapsulated, session-oriented **POST**, not a signed URL and not raw progressive bytes. Media3 progressive seek/download/cache cannot accept it unchanged. |
| HTTP/3 | PR #61 contains a TEST-only Android HttpEngine video CDN experiment | QUIC enabled does not prove HTTP/3 negotiated; VPN/proxy limitations; still independent from SABR. |

## Changes on this branch

1. **Network arbitration without downloader restarts:** `DownloadPlaybackNetworkPriority` observes Media3 transitions on the player looper; in an active buffering window the YouTube downloader yields its **network** reads for at most 12 s; after READY it yields for 2.5 s to replenish audio. Repeated events do not continuously reset the same timer. Pausing releases immediately.
2. **Isolation at byte source:** `DownloadYieldingDataSource` wraps the YouTube download **CDN network** upstream only. The complete download cache, playback cache, Media3 requests/index, file write identity, and downloader cancellation/queue are unchanged. Every 64 KiB of downloaded data or next range open checks player priority. An interrupted worker propagates `InterruptedIOException`; yielding never changes credentials, fabricates 429 or cancels completed bytes.
3. **Audius parity:** the independent approved-source WorkManager download has its own HttpURLConnection + MediaStore copy (not Media3), so merely wrapping YouTube's downloader does not protect playback. Wrap its network InputStream in `DownloadYieldingInputStream` with the same bounded policy; unit tests verify byte-for-byte copied output. Connection establishment and one in-flight blocking read can still compete; the design does not claim strict kernel QoS. The check uses 200 ms sleeps rather than a busy poll to reduce wakeups.\n4. **Tests** for priority lifetime, changes of media item, READY refill, pause and copy integrity. No claim of a guaranteed bandwidth share; in-flight kernel socket reads and other providers can still compete.
5. **SABR on real transport, but explicitly TEST-only:** a manually invocable `debugProbeSabrAudio` in `CapsuleInnerTubeXPlayer` asks InnerTubeX for an actual bootstrap using `allowSabr=true`. The `probeSabrAudioWire` calls its real UMP client, obtains initialization + first media, checks byte offsets, then cancels further flow reads. The debug probe neither alters standard player or download profiles nor writes UMP bytes to the progressive cache. No separate /player request is triggered by an ordinary play or download action.
6. **SABR fixture tests** check init/media offsets and missing media. These tests do not demonstrate a working production player.
7. **CI** runs branch-specific playback/downloader unit regressions and separate TEST/optimized DEV APK jobs. Even a successful compile will not validate HONOR Magic Capsule or mobile thermal behavior.

## Gates to promote SABR from probe to actual audio

- Build a dedicated SABR-aware Media3 `MediaSource`/`ChunkSource`, **or** a rigorously seekable `DataSource` that maps byte positions to the correct UMP `SabrChunk` and maintains a format-specific init/segment index. A progressive facade that restarts from zero on every seek is unacceptable.
- Support real mid-song seek, readahead, pause/resume, connection timeout, cancellation, session refresh / attestation, distinct `SabrFailureKind`, repeated/duplicate/missing segments and format switches without corrupting the AAC/Opus byte cache.
- Integrate video only after audio contract is stable, retain audio until the first video frame is decoded, support synchronized A/V, and preserve independent video process and safe source fallback.
- Enable SABR download completion/resume only after the same exact byte-identity/completeness tests as the direct `ProgressiveDownloader`; never write UMP envelopes to the audio cache.
- Limit client /player request budget and respect explicit 403/429/bot protection. This is transport work, not a circumvention feature.

## Device matrix required

1. **System controls:** with zero downloads, test Android Quick Settings media card, lock-screen controls, headset media keys, title/art/position and HONOR Magic Capsule. Confirm the device's `Magic Capsule → Media playback` toggle where available; export `CapsuleSystemMedia` session logs. A healthy Android card with missing HONOR bubble isolates OEM capability.
2. **Coexistence:** on the same connection play 30 tracks with zero downloads; repeat with 1 and 2 ongoing collection downloads. Trigger first-track cold startup, rapid 20 skips, long podcast and three nonzero seeks. Compare stalls/hour and time to first audible sound.
3. **Downloads:** queue 100, start one manual foreground track; stop/remove at 10% and 90%; restart app; confirm completed audio **plus artwork** works in airplane mode. Verify two-file cap, zero cache corruption, no repeated /player carousel.
4. **Network and power:** test Wi-Fi, cellular, VPN/proxy and network handoffs; capture AudioTrack underruns with `bufferedAheadMs`, `audioFocus`, decoder and actual route. Compare Perfetto CPU/wakelock/thermal with idle-downloader baseline, not hand warmth alone.
5. **SABR debug probe:** invoke explicitly on a valid test ID, record only non-sensitive init/media offset/status/latency and protection classification. Do not paste signed URLs or tokens into shared logs.

## Explicit out-of-scope

No automatic SABR production audio or video, no default HTTP/3 traffic switch, no changes to official signing/release, no visual redesign, no changes to `main`. The branch improves coexistence and supplies a real SABR protocol probe, **not yet a complete production SABR playback implementation**.
