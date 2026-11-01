# Playback and download review — 2026-11-01

Source capture: `capsule-logs-2026-11-01_09-17-06.txt`, build `91df8b2`
(DEV before the confirmed Foundation scroll repair). The new branch is based on
`ecd513d1`, retaining the verified scroll fix and working HONOR media integration.

## Observed failures

- First video presentation: **28,481 ms**. Route extraction spent **16,894 ms**:
  PipePipe timed out at 10,002 ms, Android VR at 4,003 ms, then direct Vision
  succeeded in 2,777 ms. Media3 prepared in 4,726 ms; two alignment seeks added
  more delay before the soundtrack handoff.
- Downloads remained active throughout video preparation and startup seeks.
  Their priority observer only watched canonical MUSIC, which was already READY.
  VIDEO has a separate player and therefore was not protected by that observer.
- A video buffering episode began about two seconds after its handoff. Its
  startup/rebuffer thresholds were only 250/1,200 ms.
- The capture contains 148 download-completion events and 14 failure events
  (13 StreamResolveException and one IllegalStateException). These are event
  counts, not a claim that all queued tracks were downloaded successfully.
- Two AUDIO source failures show `UnexpectedLoaderException` caused by a
  `PlaybackException` thrown from the synchronous resolving data source. That
  boundary is specified to throw IOException. Original extractor unavailability
  must still be reported; changing the wrapper cannot make unavailable media play.
- A download resolve was preempted after its client attempts, then resumed with
  all song-local profiles excluded and reported IllegalStateException. Temporary
  network failures could also enter this five-minute client-failure memory.
- Four direct video routes later reported bot blocks, while the remaining
  NewPipe route repeatedly reported NETWORK on subsequent attempts. No recorded
  AudioTrack underrun is present. CDN session summary reports no 403/410 and no
  HTTP/2 usage; this capture does not prove QUIC or SABR playback.

## Changes

1. Prefer the bounded direct Vision route on cold video start; retain last-winner
   feedback and independent parser fallbacks. Respect recent failure quiet periods:
   five seconds for transient network failures, 45 seconds for other route errors,
   and the existing ten-minute bot quiet period. Explicit 429 still stops the chain.
2. Give AUDIO and VIDEO separate bounded download-yield leases. MUSIC READY cannot
   erase VIDEO preparation/rebuffering priority; stopping VIDEO clears its lease.
   Pauses, cancellation and the 12-second upper bound still release download workers.
   No polling of Player from an IO thread is introduced.
3. Give video a 1-second startup / 3-second rebuffer cushion and a 6–20 second
   loading window instead of 250 ms / 1.2 s and 3–10 seconds. MUSIC stays audible
   until the original video soundtrack and decoded frame are ready.
4. Copy a verified complete playback-cache entry to the persistent download cache
   without another `/player` request or signed URL. The network upstream is absent;
   original format identity/length, partial-target replacement, cancellation, and
   cover completion ordering are preserved. A concurrent LRU eviction can fall back
   to the normal bounded resolver, but cannot mark truncated bytes complete.
5. Throw IOException at the Media3 resolving boundary and preserve the original
   cause. Terminal extraction failures bypass Loader's redundant retries after the
   bounded client plan; actual network IO retains normal network recovery.
6. Do not remember transient connection failures as five-minute client verdicts.
   Keep explicit server rejections, token isolation, bot/rate-limit guards and the
   three-attempt request budget. Remove redundant 350 ms sleeps after ordinary
   client-local verdicts; preserve download request spacing and bot backoff.
7. Preserve typed extraction failure when a resumed plan has no eligible profiles;
   do not replace it with a programming-error IllegalStateException.
8. Check the S extension 7 availability required by Media3 HttpEngine before the
   existing debug-only experimental route. Missing support uses OkHttp. This fixes
   the four pre-existing lint API errors without enabling QUIC in DEV/release.
9. Download failure diagnostics now include a fixed classification and cause type,
   without printing signed URLs, credentials, tokens or response bodies.

## Validation scope

New tests cover real on-disk complete-cache copies without resolver/network
admission, partial caches, encoding changes, cancellation, AUDIO/VIDEO priority
ownership and release, source IO cause preservation, transient client-memory
isolation and video route cooldown/recovery. Existing scroll, media integration,
playback recovery, download and extractor tests remain enabled.

Live YouTube/CDN behavior, video startup time and concurrent downloading still
need confirmation on the phone. No four-second startup, elimination of network
outages or new production SABR/QUIC engine is claimed from deterministic CI.
