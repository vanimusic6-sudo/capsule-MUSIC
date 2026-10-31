# Capsule Music transport architecture decision — 31 Oct 2026

**Status:** experimental TEST build only; no claims about H3 negotiation or live SABR playback.
**Base:** `experimental/playback-reliability-20261031` (review PR #60).
**Target:** reliable Android 17 AUDIO/VIDEO/download with bounded requests, unchanged offline integrity, no surprise energy or proxy regressions.

## 1. Two different layers

| Technology | Layer / contract | Does it replace the other? |
| --- | --- | --- |
| HTTP/1.1 or HTTP/2 over TCP | Standard HTTPS transfer of an already-resolved media body | Alternatives to HTTP/3, not to SABR |
| HTTP/3 over QUIC/UDP | Secure network transport for HTTP requests; connection-level multiplexing/loss handling | Can carry **both** direct CDN range requests **and** SABR requests if server/network choose H3 |
| YouTube SABR (Server Adaptive BitRate), UMP | Application-level stateful stream protocol (session, POST request, protobuf UMP response containing initialization/media/response instructions, readahead, seek, attestation) | Replaces the *direct signed-file-range model*, **not** the underlying HTTP/QUIC transport |
| MPEG-DASH or HLS | Media-format manifest/segment orchestration, often over HTTPS | May be an output/consumer of SABR segments; not identical |

HTTP/3 by itself does not grant access to protected content, fix a client-bound 403, or guarantee a server will negotiate H3. TCP-based HTTP/2 is still modern and can outperform QUIC through certain proxies and UDP-restricted VPNs.

## 2. Current Capsule state (verified in source)

- AUDIO: `CapsuleInnerTubeXPlayer.kt`, `ContentHints.withStreamCapabilities(allowHls=false, allowSabr=false, allowBoundedRange=true)`. It rejects/rolls over any `ExtractedStream.sabrBootstrap != null`. Current `ResolvingDataSource` expects `PlaybackData.streamUrl`, an HTTP(S) media file and a format-specific byte-range cache identity.
- VIDEO: `YouTubeVideoResolver` + isolated `:capsule_video` extractor process, direct VR/VisionOS/PipePipe/Reel/NewPipe fallback, Media3 progressive/HLS/DASH handling and 4 MiB ranges. There is no `SabrDashMediaSource` or native SABR consumer in Capsule's active VIDEO MediaSource.
- DOWNLOAD: `ResolvingAudioDownloader` + `ProgressiveDownloader` uses canonical media-byte length and cache completion. Its existing URL/format assumptions do not handle a SABR POST session/UMP envelope.
- PRESENT LIBRARY: InnerTubeX pinned `v0.11.2` already has `SabrAudioStream`, `SabrVideoStream`, `SabrBootstrap`, `SabrChunk` (byte offset, initialization/sequence/time), strict URL validation and protection-state errors. That is an **excellent base for an adapter**, not permission to set `allowSabr=true` on today's ProgressiveMediaSource.
- Other references: PipePipe's `SabrDashMediaSource` and YouTube.js/GoogleVideo UMP codec. They are architecture/reference sources; copying their entire playback system into Capsule would duplicate features and introduce different failure, seek and dependency contracts.

## 3. Selected experimental stack

- Player/renderer: **Media3 ExoPlayer 1.11.1**.
- Extractor/API: **InnerTubeX v0.11.2** pinned, preserving Capsule's resolver and authentication/session boundary.
- Direct AUDIO/download network: **OkHttp** unchanged while custom redirect, bot/rate-limit, signed-URL header, connection diagnostic and cache behavior are parity-tested. HTTP/2 remains negotiated when possible.
- Experimental VIDEO CDN: **Android HttpEngine + Media3 HttpEngineDataSource** on **Android API 34+**, **debug build only**, **without an app-configured stream proxy**, **only for *.googlevideo.com**.
- Other VIDEO hosts, proxied/debug traffic and **all optimized DEV/release traffic** keep the current OkHttp video pipeline.
- The HTTP/3 candidate reuses a single platform HttpEngine and bounded callback executor, preserves Media3 DataSpec request headers and the VIDEO bot/rate-limit guard. A 403/429 from the server is **not retried silently via OkHttp**; this is vital to keep request density low.
- API 26–33 remain fully supported via existing code.
- **SABR consumer not enabled.** No user-visible SABR option is advertised until seeking, cancellation, cache completion, protection status and video/audio handoff work.

## 4. Next engineering stages — strict acceptance gates

**A. Instrument H3 candidate before choosing a default.**
- Actual negotiated protocol must be observed on-device (HTTP/3, HTTP/2 or HTTP/1.1); configuring HttpEngine with QUIC enabled is not proof of UDP/H3.
- Compare on same device/network: p50/p95 DNS/connect/first-body-byte/first-frame, stream failure and 403/429 count, stalls/hour, requests/socket, CPU and thermal impact.
- Test Android 17 with real VPN, system proxy and app-specific proxy. Honor `YouTube.streamProxy`, no bypass.
- Verify protected CDN redirect and required request headers remain strict; production defaults stay unchanged until safe.

**B. SABR offline protocol harness in parallel.**
- Use the pinned library's own `SabrBootstrap` and `SabrAudioStream/SabrVideoStream`. Write deterministic fixtures for UMP media/header/context/redirect/protection, chunk reordering, missing/duplicate sequences, bounded buffering and cancellation.
- Preserve init segment + correct selected itag/last-modified IDs; verify contiguous byte range or a mapped seekable segment timeline; do not write UMP envelopes as audio payload.
- Implement a **dedicated SABR MediaSource/ChunkSource adapter** (not a superficial URL substitution). Maintain explicit session/clock and correctly map Media3 seek, pause, resume, and track transitions. Keep AUDIO playing until VIDEO first frame ready. Never re-request a full video on seek.
- Follow server attestation/PoToken and HTTP 429/bot decisions; do not rotate identity or invent a bypass. A protection error should be classified distinctly from network timeout.
- First permit SABR as an **explicit experimental AUDIO-only** path for streams where the extractor actually returned a valid bootstrap; compare with direct GVS, avoid duplicate /player work.
- VIDEO SABR next via synchronized audio/video representations, adaptive bitrate and phone decoder tests.
- Finally integrate SABR downloads with byte-identity, resume and completeness checks; only then allow non-experimental downloads over SABR.
- Never add fallback loops that increase /player requests per tap. Keep the same primary preference, and retain the proven direct/HLS/Range stream as bounded fallback when allowed.

**C. Objective release gates.**
- 30 tracks, 20 rapid skips, hour-long podcasts, 10 seeks, toggling VIDEO at mid-track, app background/foreground.
- 100 queued downloads plus one manual download, shared transfer fairness, cancellation at 10% and 90%, cache corruption simulation.
- Fully offline 0-network playback across a restart; no fresh video from an audio-only flag; no signed URLs/tokens in logs.
- Tests and both TEST and optimized DEV CI passing are necessary but not sufficient: *physical-phone network traces* are required before enabling H3 or SABR by default.

## 5. Why not immediately set allowSabr=true?

Today's `MusicService` converts a resolved stream into `Media3 DataSpec.setUri(playback.streamUrl)`, and the downloader creates `ProgressiveDownloader(MediaItem(uri=streamUrl))`. A SABR response is **not a streamUrl**: it is a stateful session endpoint and UMP protocol. Flipping the flag without a matching consumer would produce spurious errors or byte-cache corruption, exactly the opposite of the reliability objective.

## References

- Android Media3 network stacks: https://developer.android.com/media/media3/exoplayer/network-stacks
- Android HttpEngine: https://developer.android.com/reference/android/net/http/HttpEngine.Builder
- MetrolistGroup InnerTubeX v0.11.2: https://github.com/MetrolistGroup/innertubex/tree/v0.11.2
- PipePipe SABR architecture and client/extractor split: https://priveetee.github.io/Docs-PipePipe/developer-guide/introduction.html
- PipePipe `SabrDashMediaSource`: https://github.com/InfinityLoop1308/PipePipeClient/blob/dev/app/src/main/java/org/schabi/newpipe/player/datasource/SabrDashMediaSource.java
- YouTube.js GoogleVideo UMP reference: https://www.ytjs.dev/
