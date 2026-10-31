# Capsule Music — field audio failures / heat audit (31 Oct 2026)

## Tested build and scope

Owner's trace: `capsule-logs-2026-10-31_15-52-36.txt`, export at 15:52:36, build `1.1.2-dev`, `df02705`, **devRelease**, Android SDK 37. Captured at 15:44–15:52, not a 24 h soak test. The logs contain no thermistor, profiler, CPU or power measurements, so they **cannot** establish a proven thermal cause or that the new code lowers device temperature.

**Important:** This exact `devRelease` build **does not use QUIC**, since the HttpEngine experiment is intentionally debug-only. Its audio requests all used existing OkHttp and negotiated `http/1.1`. Rapid startup is encouraging but cannot be attributed to QUIC.

### Observed, not inferred

- Session header: `opens=16 refused=0 requests=18 redirects=2 sockets=8 http2=0`. No observed YouTube HTTP 403/429 in this captured sample.
- First selected direct VISIONOS/Opus track resolved in ~275 ms, first audio READY in ~1035 ms. Next transition resolved ~330 ms and reached READY in ~928 ms.
- Audio format `itag=251`, Opus/WebM, 48 kHz. Media3 selected `c2.android.opus.decoder`; audio offload preference was **disabled**. That codec is frequently software-decoded, but device power impact needs measurement.
- `15:47:08.919`: CDN `ConnectionResetException` on an HTTP/1.1 1 MiB range request; the same URL succeeded after reconnect at 15:47:11.474, no renewed InnerTube /player call.
- `15:49:24.358–.388`: Android default network changed; then a CDN read failed with `SocketException: Connection reset` after **999,424/1,048,576 bytes**, leaving exactly **49,152 bytes** in the currently bounded range. The service flagged `READ_FAILURE`.
- `15:49:32`: another Android default-network change. The conditions suggest route instability/hand-off contributed; this is **not evidence of a Media3 renderer bug**.
- `15:52:03`: second HTTP/1.1 reset recovered by OkHttp reconnect. No bot verdict.
- The `15:48:02` AudioTrack underrun reports `timeSinceFeedMs=13,189` while the player still listed `bufferedAheadMs=76,130`. This is an **output/feed timing issue**, not proof of a CDN byte shortage; it could involve pause/idle, output route or scheduling. Needs on-device audio-focus/route/Media3 event correlation.
- `15:45:31–32`: after a cache-to-CDN tail seek, a **587,961-byte** response took **10,302 TLS read calls**. The expected Opus 8 KiB readahead was **not active** because the current `AudioChunkedDataSource` only coalesced reads when the request was actually split into multiple ranges. Small files and under-1-MiB cache tails used the raw upstream tiny-read path.
- Several 1 MiB requests showed 31–63 s of `idleBetweenReadsMs` with only ~100 ms of active read time. That is consistent with Media3 pausing network consumption after filling the audio buffer. **Do not divide 1 MiB by total open-to-close time and declare CDN throttling.** An idle HTTP/1.1 socket can be reset while waiting.

## Mitigation implemented on isolated `fix/audio-socket-resume-opus-heat-20261031`

1. **Byte-exact inline recovery on a transient reset, only for a currently bounded AUDIO CDN range.** Reopens the same signed link at `nextPosition` for exactly `chunkLeft` bytes, once per chunk. No extra YouTube /player request, no fallback identity rotation, no Media3 decoder/source recreation, no playback seek. Avoids losing the already delivered ~999 KiB when just 49 KiB remain.
2. **Preserve failure semantics.** Immediate cancellation, HTTP 403/410/429 and unrelated timed-out opens remain owned by original classified error handlers. Failed resumptions still propagate to Media3; guard prevents infinite attempts.
3. **Cover Opus cache tails and unsplit short streams.** Apply existing bounded 8 KiB Opus read-ahead to directly forwarded DataSpecs too. Track requested remaining length and never deliver or persist speculative extra bytes. This replaces potentially thousands of small read() calls with a handful of upstream calls.
4. **Reduce log amplification.** When debug logging is enabled, recoverable socket resets are summarized in a single line rather than emitting a 40+ frame R8/OkHttp stack trace for every incident. All unexpected IO failures remain detailed.
5. **Regression tests.** Byte identity across a partial reset and subsequent ranges; second failure escape; 429 read refusal not retried; tiny Opus cache-tail reads; existing CDN/recovery/video/download regressions.

## Not changed, by design

- The 180 s buffer target **remains**: our older long-podcast traces justified this setting under intermittent slow service. Shortening it without a controlled test risks new stalls.
- No new speculative warmups or request-burst helpers.
- No codec quality downgrade or blanket AAC preference, despite the Opus software-decoder suspicion.
- No SABR or QUIC switch for AUDIO. This DEV log predates a validated AUDIO HttpEngine transport and has no SABR session.
- No source-client reordering, no UI or video animation change.

## Next on-device verification

- Repeat the same problematic playlist in old `df02705` DEV and this new DEV, same network route/VPN; observe 30–60 minutes. Compare audible discontinuities per hour and `cdn-read-resume` versus `cdn-retry-policy refresh=READ_FAILURE` and decoder re-inits.
- Seek into a cached Opus tail repeatedly; upstream `readCalls` should drop sharply in that path.
- Collect Android **Battery Historian/Perfetto** CPU, wakeups, thermal status and AUDIO decoder/offload support. A sound-track power claim needs measured before/after.
- Distinguish route changes from playback failure: test stable Wi-Fi, Wi-Fi → mobile handoff, VPN route reconnect. Correlate AudioTrack underrun with app pause/resume and system audio focus; do not assume any underrun is Media3 bug.
- TEST APKs use HTTP3 for eligible VIDEO only and allow network stack experiments; DEV APKs remain on verified VIDEO OkHttp. Audio remains OkHttp in both.
- Release gate: 0 extra server auth/cooldown attempts; no corruption across byte resumes; no regressions in audio/video start, offline cache or download cancellation; tests and optimized DEV build green; owner confirms fewer audible interruptions and no thermal regression.

**This is a targeted transport/CPU mitigation, not proof that all track failures or heating are fixed.**
