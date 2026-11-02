# Capsule Music — VIDEO acquisition audit (2026-11-02)

## What is actually shipped today

Capsule currently uses an exact-video-ID lookup from YouTube Music, followed by a
single extractor request in an isolated `:capsule_video` process. The existing
production VIDEO acquisition options are:

1. Direct VisionOS player (bounded; anonymous visitor bootstrap).
2. Direct Android VR player.
3. Current upstream NewPipeExtractor **v0.26.5**, which implements the June 2026
   workaround for YouTube forcing SABR-only web playback.
4. Relocated PipePipeExtractor pinned to **f8982ca9e7** (2026-08-24).
5. Independent Android Reel muxed fallback.

Successful backends are preferred on later clips and across process restarts,
while 403/429/bot responses still use the existing VIDEO-only protection.
The routes are *sequential* and subject to bounded per-client timeouts; they
must not race five YouTube `/player` requests against each other.

The VIDEO stream is passed to its own Media3 player, CDN transport, cache,
bounded Range wrapper for progressive content, and DASH/HLS parsers. Existing
MUSIC audio stays audible until video is actually rendered and ready.

## November 2026 upstream research (source-derived)

| Approach | State / capabilities | Capsule decision |
| --- | --- | --- |
| NewPipeExtractor v0.26.5 | Latest tagged NewPipeExtractor release (Aug 15). v0.26.3 included a player-client workaround for SABR enforcement. | Already bundled; retain as reliable URL-based fallback. |
| PipePipe v5.5.0 / its actively developed extractor | Oct 21 release; v5.5 beta1 switched playback to `mweb (SABR)` rather than VisionOS, continuing its Aug 24 native SABR restoration. | Best candidate for a separately tested *SABR transport*, not a drop-in URL extractor. Our pinned Aug 24 fork and adapter only accept playable HTTP/HLS/indexed DASH URLs. |
| googlevideo 4.1.1 (LuanRT) | Maintained UMP framing / SABR protocol toolkit in TypeScript/JavaScript, released Jul 13. | Good protocol reference for a Kotlin/Media3 DataSource, not something we can import as a native Android DataSource without a bridge and tests. |
| yt-dlp + yt-dlp-ejs | Supported EJS JavaScript challenge solving (Deno recommended), but the upstream SABR downloader was tracked as separate work; browser-only clients may supply SABR URLs rather than ordinary format URLs. | Helpful parity/test oracle, not a mobile real-time player runtime. Avoid launching a Node/Deno process per video. |
| Direct Android/VisionOS player | Fast single-request direct-CDN route when YouTube responds with usable media URLs; client-specific server-side policy can change. | Keep bounded primary fast paths, verified formats only, never hard-code a single client as universally reliable. |

Sources:
- https://github.com/TeamNewPipe/NewPipeExtractor/releases
- https://github.com/InfinityLoop1308/PipePipe/releases/tag/v5.5.0
- https://github.com/InfinityLoop1308/PipePipe/discussions/2884
- https://github.com/InfinityLoop1308/PipePipe/issues/2820
- https://github.com/LuanRT/googlevideo/releases
- https://github.com/yt-dlp/yt-dlp/wiki/PO-Token-Guide
- https://github.com/yt-dlp/yt-dlp/wiki/EJS
- https://github.com/yt-dlp/yt-dlp/issues/12482

## What changed in this branch

- Light/Cosmo full player now **waits for full-resolution artwork**. No 256px
  preview, no intentional 480px video-poster fallback. Known ytimg hosts
  use maxresdefault or hq720 only; reject misleading HTTP-200 tiny placeholders.
- Immersion also skips the low-resolution hqdefault fallback. A failed
  high-res request stays on the neutral surface rather than enlarging 480px.
- Fixed a false-negative test case for the previous Range-socket reconnect:
  a Kotlin-delegated DataSource was not forwarding Java interface default
  `getResponseHeaders`, so the test accidentally exercised no-range mode.
- Route priority now puts the maintained NewPipe fallback ahead of the older
  PipePipe URL adapter, but always honors a measured working backend first.
- Two consecutive host DNS failures on independent video endpoints stop a
  futile extra-client cascade; a single DNS failure can still fall back to a
  functioning independent source. DNS trouble does not quarantine a player.

## The next major architecture step: isolated native SABR

Do **not** replace the current URL-based extraction with a "SABR URL" in
`ProgressiveMediaSource`: SABR/UMP is framed, stateful media delivery, not a
normal MP4 byte stream. A valid implementation needs:

1. A native VIDEO-only SABR request/response client handling session/visitor
   identity and any required player/GVS attestation. Avoid copying credentials
   or sending browser cookies to unrelated services.
2. An incremental UMP parser with strict protobuf framing bounds, content
   identity checks, segment indexing, EOF/seek semantics, and cancellation.
3. A Media3-compatible segmented source (or verified remux path), independent
   of MUSIC audio and the existing progressive cache keys.
4. Backpressure and bitrate selection based on real VIDEO bandwidth, not fixed
   1080p requests. Prioritize fast first frame and a replenished buffered runway.
5. A guarded feature flag in **debug builds first**. Compare >=30 cold-start
   trials across Wi-Fi and the user's VPN against existing direct routes,
   including 403/429 rate, first-frame p50/p95, seek latency, steady-state
   rebuffer count, and CPU/network/thermal cost.
6. A fast abort/fallback to already-proven direct or NewPipe routes on
   attestation/UMP/protocol mismatch. Never try new clients in an endless loop.

This branch does *not* implement that protocol, and does not claim to fix the
observed 12-second first frame. Existing logs contain phase timings (`VIDEO timing
match`, `VIDEO timing routes`, `VIDEO timing ready`, `VIDEO timing first-frame`)
that can establish whether the bottleneck is clip search, extractor, CDN,
decoder, or a corrective seek.

## Acceptance criteria

- Full-player covers: no low-resolution preview, correct image across rapid
  track skips; never use a tiny ytimg HTTP-200 placeholder.
- Video: no added guard bypass, no redundant browser/player requests, audio
  still plays during preparation and returns on failure.
- 12 seconds is a **measured symptom**, not proof that extraction alone took
  12 seconds. Measure end-to-end toggle -> first frame on-device.
- The build and tests must pass before merging; no production SABR switch
  until an independent end-to-end streaming test exists.
