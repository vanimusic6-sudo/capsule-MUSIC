# Playback reliability audit — 2026-11-27

Baseline: 8c835e57e0584aa948c99019cce772aeffe7ccd9. This is a partial static audit; device traces, full Gradle resolution and end-to-end testing remain required.

## Confirmed findings
- Video's session watchdog previously reset its idle timer on any increase of buffered position. After presentation it had no overall continuous rebuffer deadline. A slowly progressing but never READY source could remain stuck indefinitely. Add a 30 s continuous rebuffer ceiling while preserving the 12 s no-progress deadline.
- DownloadPlaybackNetworkPriority previously released all download workers after 12 s even if the foreground player still buffered. Use bounded recurring priority periods for actual players (12 s priority, 3 s download opportunity). Metadata resolve retains its one-shot limit. Supply the video buffered-ahead sample for its READY refill decision.
- MusicService used Media3 totalBufferedDuration in foreground priority, prefetch and stall checks; this value includes subsequent media items. Use the current item's buffered position minus its current position instead. If the shared retry budget is already exhausted during an audio stall, enter the existing terminal failure policy rather than silently abandoning BUFFERING.
- Audio resolve already has a single-flight coordinator, 8 s request timeout, 18 s extraction budget, and separate CDN/session recovery. Review actual total deadline and cancellation across scheduler, transport, Loader and service; do not blindly increase retries.
- Audio load control already targets 180 s, 6 MiB, 2.5 s start and 8 s rebuffer. Larger buffers alone cannot fix sustained throughput below media bitrate.
- Video uses its own player, bandwidth meter, DASH/HLS and progressive/SABR routes. Keep audio audible until actual video readiness. Test timeline preservation and audio ownership on every recovery.
- Route changes invalidate audio extraction state; verify recovery while playing, not only after explicit retry. Verify video route state and download resume independently.

## Remaining work
- Resolve and audit the full Gradle dependency graph, CVEs and transitive licenses; verify Ktor 3.6.2 with the pinned toolchain. Media3 1.11.1 is the stable release; 1.12.0-rc01 is not chosen for this reliability branch.
- Unify recovery ownership, absolute deadline, and typed outcomes without multiplying retries. Distinguish transport interruption, expired URL, format mismatch, authorization, rate-limit, unavailable content and cancellation. Honor authorization and rate limits; never disable TLS verification.
- Measure effective throughput and headroom; choose quality only at safe representation boundaries. Keep cache identity format-aware; validate Range/Content-Range and refuse mismatched byte sequences.
- Test network switch, stalled reads, trickle responses, short EOF, 403/410/429, cancellation, repeated seeks, offline cache, concurrent downloads, and long playback. Capture p50/p95 start, rebuffer ratio, retry count, bytes wasted, thermal state and battery. No credentials or signed URLs in logs.
- This change is not a full engine rewrite and has not been validated on device.

## Second review — transport contracts
- SABR VIDEO DataSource now respects requested byte length, returns EOF at resource end, rejects out-of-range positions, bounds a blocking read to 25 seconds and maps thread interruption to InterruptedIOException. Uses monotonic seek timing.
- SABR session cancellation is synchronized with call publication, obsolete failures cannot poison buffers after cancellation, 429 reaches the existing video cooldown, and no-media headers no longer acknowledge segments. Stop obsolete SABR producers before video recovery.
- Audio URL TTL is monotonic (wall-clock changes cannot extend a cached URL lifetime). Signed URL expiry, range consistency and account/route context remain distinct.
- Added transport regression cases for bounded reads and header-only responses. Existing media-core CI includes SABR tests and audio URL-cache tests. These changes still require successful CI and device tests.

## CI feedback and audio liveness
- Previous debug APK and InnerTube tests succeeded, but the app unit-test compilation failed because a new test used arithmetic directly on a nullable fake buffered position. Corrected the fixture and preserved the test's actual continuous-progress scenario.
- Audio stall detection also has a 30 s ceiling for a continuously starved buffer even if each sample meets the growth threshold. It never treats adequate current-item headroom or offline state as a CDN failure.
- Release remains blocked until the new commit's compilation, regression suites, optimized APK and long on-device network tests are verified. No dependency or license audit is claimed complete.

## Third review — recovery budgets and CI
- On d1c0b0f, debug APK compiled and VIDEO regression ran 867 tests: 865 passed, two UI source/opacity expectations were stale relative to the earlier matte/animation redesign. Updated tests to retain the visibility and playback conditions plus the newer motion gate, and verify the intentionally increased alpha without changing visual design.
- Signed DEV and media-core failed resolving the published Kotlin 2.4.21 Gradle plugin. The same version compiled in the VIDEO and debug APK jobs; treat as likely repository/runner availability, not evidence to downgrade. New CI must confirm.
- Stream retry counters and the separate audio-stall budget now reset only after 30 seconds of sustained READY/playing with actual position advance and unchanged selection. Queue failure circuit retains the 5-second healthy check. Duplicate READY events no longer restart the healthy timer; buffering/pausing cancels it. Stall watch remains armed when another recovery is in flight.
- Added regression tests for stable playback, no movement and duplicate READY. Release remains unverified pending new CI and live tests.

## Network recovery ownership
- Network reconnect now retains intent if connectivity drops during the post-prepare grace period, not only during the initial delay. The buffer watchdog sees the full recovery lease, and duplicate errors for an unchanged selection no longer exhaust retry slots while that lease is active. An active rate-limit block does not spend a retry slot.
- Added virtual-clock regression for duplicate errors, loss/reconnect during grace, and final retry accounting. No new dependencies or third-party source added.
