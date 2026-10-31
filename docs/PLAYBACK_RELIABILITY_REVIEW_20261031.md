# Capsule Music — reliability engineering review (31 October 2026)

> **Status:** verified source audit and isolated experiments, **not a verified live-YouTube performance claim**. Baseline: `fix/artist-card-motion-catalog-20261030` at `368e8cad`. All original baseline CI builds passed. Keep this work in `experiment/playback-reliability-20261031` until device traces and A/B results exist.

## 1. What is actually in the application

| Boundary | Current mechanism | Established benefit | Remaining failure modes |
| --- | --- | --- | --- |
| AUDIO resolution | `MusicService → CapsuleAudioEngine → CapsuleInnerTubeXPlayer → InnerTubeX v0.11.2` | Shared per-song work, serialized extraction, up to three safe profile attempts, correctly prioritized foreground/prefetch/download | `/player` denial, GVS token/client mismatch, slow route, legitimate auth/geo/age restrictions |
| AUDIO CDN | Media3, OkHttp, bounded 1 MiB ranges, local caches, HTTP/2 requested, socket/redirect health ledger | Resumes long files across bounded requests; distinguishes fresh-URL failures from transport errors | On-device captures negotiated HTTP/1.1 only; new-socket refusals; signed URL expiration; range seam errors |
| AUDIO recovery | Classifier, 403/410 same-link retry then bounded fresh-link/alternative-client recovery, 429 global pause | Does not infinitely rotate identities, protects currently playing track | Same-link refusal may waste a second request; some errors remain unclassified; retries are eventually exhausted |
| VIDEO | Explicit opt-in only; official-clip matching; isolated `:capsule_video` extraction process; Android VR/VisionOS/PipePipe/Reel/NewPipe routes | Crashes/bot failures isolated from AUDIO; AUDIO remains audible until video is ready | Cold extractor startup, sequential route latency, failed/signed video URLs, first frame/seek sync, occasional media decoder faults |
| VIDEO transport | Dedicated cache/OkHttp/dispatcher, 4 MiB bounded ranges, HLS/DASH path where available | Large-file request no longer always monopolizes one slow body | Some upstreams refuse byte ranges or DASH/HLS; materializing the first frame and maintaining A/V timeline still costs time |
| Downloads | Media3 DownloadManager, two global transfer slots, manual priority, separate download policy, format-identity cache, artwork completion | Avoids partial file being marked complete; manual track priority | Cooldowns, link expiration midway, cache partials, artwork failures, network changes, competition with foreground CDN |
| UI/renderer | Media3 1.9.2 baseline, custom 180 s audio buffer and 8 s rebuffer | Conservative large audio buffer on unstable routes | Older Media3 renderer/offload/Surface bugs, extra allocations on slow hardware, up-to-date capabilities not validated |

The architecture is **already considerably more sophisticated** than a single `/player` request feeding ExoPlayer. Replacing it wholesale with a new extractor would lose mature behavior. The highest-value work is now **measured changes at narrow boundaries**.

## 2. Actual evidence vs hypotheses

The October 24 capture and October 25 audit (earlier APK, not this branch) observed:
- 59 InnerTubeX profile attempts: **46 DOWNLOAD**, **12 PLAYBACK**, **1 PREFETCH**. This is *not* a count of HTTP requests.
- 57 player verdicts: **52 OK**, 3 LOGIN_REQUIRED, 2 UNPLAYABLE; no observed HTTP 429.
- A WEB_REMIX stream was returned, then the same signed URL got HTTP 403 twice before any audio bytes. Alternative profiles later returned different playability statuses.
- Other CDN opens took approximately 15 seconds to time out without an HTTP response.
- There were **no VIDEO records** in that specific capture.

These traces justify investigating download pressure and first socket/CDN errors first, **not claiming VIDEO caused those AUDIO failures**. A 403 may be an invalid/expired signed URL, missing client-bound attestation, authorization, a network/proxy issue, or a server policy decision. Do not interpret every 403 as a CAPTCHA. A legitimate server rate-limit/attestation/access decision is not something a faster extractor can guarantee away.

## 3. Changes already isolated on this experiment branch

### Experiment A — stable Media3 1.11.1

The repository used `1.9.2`. The September 10, 2026 stable `1.11.1` has fixes for secondary renderer prewarm stalls, Surface restoration during seek, Matroska/WebM seek handling and other playback issues. The dependency version changes together for the existing Media3 modules; **no player gesture, visual, extractor or cache-key change**. Passing compilation does not establish real-phone compatibility; validate the AUDIO/VIDEO handoff and device-specific media codecs before promotion.

Official releases: https://developer.android.com/jetpack/androidx/releases/media3

### Fix B — download cooldown greater than original 30-second gate

`DownloadUtil.awaitDownloadResolveWindow()` had a hard 30-second timeout even though the global AUDIO HTTP 429 breaker intentionally holds new requests for ten minutes. A download waiting for a **valid server cooldown** could therefore be given a premature `IOException` and appear failed. The new cancellable `awaitDownloadCooldownWindow` observes all three cooldown sources until they clear, without a mutex held during the wait and without issuing a player request. Unit tests cover a ten-minute wait, overlapping gates, and cancellation. **Tradeoff:** up to two Media3 download workers can remain suspended during a long cooldown; they use no busy-loop CPU, but the UI may still display a waiting transfer. The next release task should implement an explicit persisted `WAITING_FOR_SERVER`/stop-reason and timed re-admission instead of holding a worker slot.

## 4. Highest-value follow-on experiments (not yet deployed)

1. **One synchronized, privacy-safe request ledger.** Classify every YouTube-facing operation by origin: AUDIO current/prefetch, DOWNLOAD resolver/CDN, VIDEO metadata/CDN, catalog browse, artwork. Keep anonymous per-session counters and elapsed times; never log URLs, tokens, cookies or account IDs. Correlate `/player` verdict, CDN HTTP status, first byte, first audible playback, buffer underrun, A/V frame readiness and power consumption in the *same one-minute window*. Report p50/p95 and request-per-transition, not one dramatic failure sample.
2. **Per-route transport A/B, not a universal rewrite.** Today audio asks OkHttp for HTTP/2 but earlier phone captures show all CDN responses as HTTP/1.1. Instrument ALPN, whether the configured proxy supports HTTP/2, redirections, reused sockets, server range responses, and link/headers preservation. An opt-in alternate `HttpEngine` on API 34+ or Cronet may enable HTTP/3/QUIC on cooperating hosts. Only activate after verifying proxy/credential/redirect behavior; retain a kill switch and automatic fallback to current OkHttp. Never assume HTTP/3 repairs a server-side 403.
3. **Proof-of-use of a signed URL.** A URL returned by extraction is not proof the CDN accepts it. Track anonymous `resolved → opened → first playable bytes → healthy 5 s` transitions by client/host/itag. Use negative evidence **only for that URL/route**; keep successful proven links healthy. Do not promote a single 403 to a global client ban.
4. **Buffer-aware background admission.** Give current AUDIO CDN continuation priority over download chunks, video-matching discovery, artwork and optional prefetch. Suspend optional work when the audio buffer is low or a recoverable CDN open is pending. Resume fairly after stable READY; avoid repeated pause/resume churn. A measured gate should replace arbitrary extra network requests or a huge permanent buffer.
5. **VIDEO startup pipeline with measurable stages.** Track click → match → extractor → first CDN byte → decoder-ready → first frame → A/V handoff. Cache only the valid **clip identity** independently of short-lived signed URLs. Reuse warm extractor process and existing relevant work, but never auto-start video for a track's metadata flag. Keep AUDIO uninterrupted until a verified frame, then make one time-aligned handoff. Limit unnecessary fallback routes and don't rotate on 429/bot-check.
6. **Download lifecycle and integrity.** Persist a server-cooldown wait state and schedule queue re-admission after expiry; cancellation and manual resume must be idempotent. On expired links refresh without changing the already-downloaded format/itag. Require full contiguous byte coverage, durable artwork, and correctly owned partial removal. Test long playlists, priority single, roaming/offline and restart.
7. **Codec-aware route measurements.** Compare AAC/MP4 versus Opus/WebM on device by real start latency, underrun and CPU/thermal cost, separately from server availability. Avoid hard-coding a format based on a few successes. Keep manual high-quality preference intact.
8. **Failure-injection harness.** Run fake HTTP 206/403/410/416/429, early EOF, range mismatch, 1-byte bodies, resumed-cache spans, proxy disconnect, network switching and cancellation at 10%/90%. Ensure no infinite retries, corrupt complete downloads, wrong-video seek or automatic VIDEO request from AUDIO.

## 5. Baseline before calling a change “better”

Run matched test packs on the same device, same Wi-Fi and VPN configuration, and again without VPN if permitted:
- 30 sequential tracks with 10 manual seeks; 20 rapid next/previous swipes; one hour-long track.
- 100 queued downloads + an immediate manual download + uninterrupted foreground playback; Wi-Fi → mobile handoff; cancel at 10% and 90%; a real or injected cooldown >30 seconds.
- VIDEO cold/warm start on short/long clips, mid-track VIDEO toggle, background/foreground, first-frame swap, pause→resume, fallback AUDIO. Verify the next AUDIO track always plays.
- Fully offline downloaded album: zero YouTube/player/CDN requests; every file and cover still accessible after restart.
- Hold CPU/thermal/network awake baselines for a 30-minute run. Compare *before and after* p50/p95 startup times, stalls per hour, CDN request density, bytes burned on discarded tracks, power and thermal state.

**Release gates:** no new automatic bot/429 retries; no regression in first audio or first video; no bad format/cache identity; no duplicate download admission; no more than two transfer workers; exact original playback position preserved on video failure; no external URLs/tokens in diagnostics. Only then merge selected, verified improvements.

## 6. Source upkeep

`docs/PLAYBACK_ARCHITECTURE.md` describes an older state (e.g. bounded-range declared disabled; global bot check policy), whereas today's InnerTubeX integration advertises bounded ranges and scopes bot challenges to a song/profile with HTTP 429 global. Its original invariants on priority/cancellation still matter, but its descriptive facts must be updated after measured verification, not blindly followed.

**External engineering references:** Android Media3 release notes and Network Stacks documentation; the yt-dlp PO Token Guide for understanding client-scoped attestation, not as a universal workaround; existing Capsule tests and the October 25 request-pressure audit.

**No production migration until CI and phone acceptance.**
