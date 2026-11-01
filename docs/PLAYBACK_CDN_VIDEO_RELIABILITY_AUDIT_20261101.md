# Capsule playback / downloads / video reliability audit — 2026-11-01

## Evidence and limits

This audit is grounded in **capsule-logs-2026-11-01_21-09-51.txt** (build `03b3616`, 2,858 exported events), the current Capsule source tree, and its existing unit tests. Log excerpts below are local-time references; no request URLs, credentials, IDs, or tokens are copied into this document. One user session is not an A/B benchmark, so the proposed improvements are not described as measured successes.

### Quantitative findings

| Area | Observations | Interpretation |
| --- | --- | --- |
| CDN | `opens=76`, `refused=0`, 32 sockets, 0 HTTP/2, 10 abandoned bodies | This session's audio interruption was **not** primarily HTTP 403/410. Responses used HTTP/1.1; do not pretend that QUIC/h2 was negotiated. |
| Transport | 7 `ConnectionResetException` incidents and another reported socket-level failure | The errors often occurred at a later audio range/open after a previously successful 206, compatible with stale/closed sockets. Some canceled requests also logged generic socket errors; do not count those as server faults. |
| Audible audio | Cold first audio transition `7,058ms`, later transition `10,310ms`. One explicit `6,957ms` rebuffer and decoder underrun after the latter | This was a real audible pause, not merely a logging artifact. At READY the track had only `2,832ms` buffered; a few seconds later buffer reached ~24ms, **while a large download queue was active**. |
| Downloads | 53 terminal successes, 1 error `StreamResolveException`; failure's player response `UNPLAYABLE` with zero formats across attempted clients | A large queue *was* transferring audio; it did not globally fail. Repeatedly retrying the single unplayable item would be counterproductive. |
| Video | Four `No trustworthy YouTube Music video` outcomes; one playback session failed with a **video HTTP 403** midstream, followed by immediate MUSIC restoration and a fallback extractor recovery | The video stream and the audio CDN are distinct. For this video, the first decoder worked; later the stream URL itself was rejected, so upgrading a decoder cannot fix that failure. |
| Video startup | One total first-frame timing `10,280ms`, another `7,258ms`. Multiple preliminary `first-frame` events per session were logged before final handoff | Seek/alignment and CDN network times dominate over decoder initialization (which took tens to hundreds of ms). Do not loop seek corrections faster blindly. |
| Artwork | Detail preview decode/arrival 5–51ms across 19 samples; high-res finish 5–6,375ms across 17 samples; an independent player-artwork-stalled warning appears | At least one full-resolution upgrade is genuinely slow. The preview often arrives immediately. Blocking a **valid local downloaded cover** behind a network upgrade (old `DownloadedArtworkInterceptor`) is a separate, verified avoidable stall. |

### Reproduction points from the capture

- 20:48:36–20:48:43: first playable audio awaits remote resolution, then slow first CDN redirect/open and READY.
- 20:50:44–20:55:57: dozens of queue items download; one item returns UNPLAYABLE from the extractor and fails cleanly.
- 20:54:38–20:55:01: transition loads for 10.31s, becomes READY with 2.832s ahead, re-enters BUFFERING at 4.852s, has an actual underrun, and recovers after 6.957s.
- 20:54:15–20:54:22: 42ms preview, 6.375s high-resolution artwork finish.
- 21:01:43–21:02:53: the VIDEO service resolves, starts, receives HTTP 403 after successful playback, immediately hands audio back to MUSIC, retries a different backend and completes another handoff.
- 21:04:09: a separate player artwork stall is reported; it needs a capture of cache result and active downloader state.
- 21:08:55: reused HTTP/1.1 connection returns a reset, followed by a successful new-socket 206. Replaying the *same bounded bytes* is appropriate here; wholesale client banning is not.

## Changes deliberately isolated in this branch

1. **Lossless CDN socket reset recovery:** `AudioChunkedDataSource` has one additional local, same-signed-URL retry **at open() only** for an identified reset, with unchanged byte position and length, after closing the old socket. It refuses route-changed requests, cancellation, HTTP status responses, timeouts and a second reset. Existing partial-read resume and service-level bounded URL regeneration stay intact.
2. **Protect short audio buffers from batch downloads:** Media3 already yields the downloader during BUFFERING and for 2.5 seconds after READY. This session showed READY with only 2.832s ahead. When READY initially has <8s of audio buffer, the download-only network gate lasts up to 8s, then expires even if the buffer stays low; healthy tracks keep 2.5s. No new polling or priority thread is introduced.
3. **More reliable official-video identification:** YouTube Music's `MUSIC_VIDEO_TYPE_OMV` is still mandatory; matching no longer rejects an OMV merely because it shares its original song ID. A *weak* candidate no longer suppresses the second official qualifier query. Same-title OMV results from a different artist are rejected instead of receiving only a score penalty, reducing false positives. Real audio IDs are never replaced with candidates from external fan sources.
4. **Non-sticky video misses:** Shorten negative match cache from 12h to 15min and bound previously persisted misses at import; keep verified positive matches' expiry. No loops, brute-force searches or weakened lyric/live/visualizer exclusions.
5. **Immediate existing artwork:** `DownloadedArtworkInterceptor` returns a valid saved thumbnail immediately instead of doing a potentially 6s high-resolution network upgrade synchronously. `DownloadedArtwork.save` remains responsible for separate bounded quality upgrades. Existing offline covers remain visible even when CDN is unavailable.

## Existing code deliberately retained

- Local progressive audio cache identity, exact signed byte-range semantics, one read-resume for partial 206, host health/cooldown with evidence, first-byte diagnostics, player seek generation, download Media3 queue state and error reporting.
- VIDEO extraction in its own process, DASH delivery, Media3 decoder fallback, audio audible until the first verified video frame, and MUSIC restoration on VIDEO error.
- The requested photo/title animations and the full atomic search-results layout; this branch does not redesign them.

## Tests / acceptance gates

- Unit: normal chunk assembly byte-for-byte, one reset during a **later open** restores the exact missing range, resets during route change or HTTP refusal do not get silently retried, unplayable results are not fabricated; existing partial-read/cancellation/403/410/429 regression set stays green.
- Unit: low-buffer READY starts a bounded longer download-yield window; healthy READY and pause do not throttle downloads unnecessarily; repeated player callbacks cannot extend the deadline.
- Unit: official clip matching still rejects other artists, lyric uploads, live variants and wrong tags; persisted negative results lose the obsolete 12-hour quarantine.
- Build: full Android regression, playback/extractor tests, separate TEST APK, R8 DEV APK.
- **On-device acceptance still required:** cold/warm audio first sound, an entire 50+ song download while streaming (count audible underruns), long podcasts across CDN ranges, 15 rapid seeks, video start/handoff/stall/403 recovery, direct official clips without description URLs, offline cached cover and video-over-audio transitions. Record times from the existing log tags; do not claim heat, battery, first-frame or zero-drop outcomes without the actual phone capture.

## Known boundaries

No implementation can force a YouTube CDN to deliver a prohibited/unavailable clip, override HTTP 403, or guarantee every song has an official music video. A correct `unavailable` is preferable to playing the wrong artist, live recording or fan visualizer. Full-resolution CDN artwork can still be slow even when the low-resolution preview is instant; the remaining renderer/cache latencies should be addressed based on separately measured cache source and network route, not a global timeout guess.
