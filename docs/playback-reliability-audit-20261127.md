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

## First device capture (d439da9)
- 37 CDN opens, 8 refusals, all first requests. Successful and refused requests both use IPv6 with IPv4-issued URLs; no global IPv4 override or TLS changes. Keep HTTP/2 negotiation and route diagnostics.
- Retire 403/410 at the physical open regardless of diagnostic mode, with bounded 10-minute per-track salted link memory. Reject late publication of the same link; terminal recovery clears URL cache. Verified offline bytes stay intact.
- Real loader may request second future period; optional prefetch remains one ahead. Shared prefetch observes loader promotion. Stop default retries for obsolete loads and nested terminal resolver errors. Download rejection is tracked independently and failed delegate URLs discarded.
- SABR empty completion is a failure; first chunk deadline 8s, steady-state deadline unchanged. Add total audio-ready duration across retry-related IDLE events. Age/auth restrictions and rate-limit handling unchanged.
- Device retest and CI required; no new dependency or third-party code.

## Second device capture (bd5298c): VIDEO-only changes
- Audio: 32 opens, zero refusals. The previous failing track resolved through WEB_REMIX; no AUDIO engine changes in this package.
- Three progressive VIDEO sessions all failed 403 after initial readiness and alignment seek. The resolver immediately excluded the only successful WEB backend and used legacy extractors that returned no playable stream. First recovery now tries one fresh same-backend WEB resolution, not its cached inventory; second recovery remains bounded and muxed-only. Failed extraction consumes its slot but no longer prevents the remaining attempt. Guard/cancellation retained.
- Physical VIDEO CDN ranges now retire refused URLs in separate bounded in-memory state, fail fast on terminal HTTP statuses, and log safe range coordinates and salted link references. Cache entries with rejected direct URLs cannot be reused. No credentials or URLs logged by new instrumentation.
- Exact canonical progressive clips may prepare near the current MUSIC position using known duration; distinct clips retain existing duration-discovery logic. Prefer the extractor's own signed representation on initial extraction if within the quality cap; inventory still serves explicit cached quality selection.
- CI and field retest needed; these are targeted architectural changes, not evidence all video CDN failures are gone.

## Parser-first VIDEO transport (following full bd5298c capture)
- Production isolated resolver limits primary extraction to PipePipe/NewPipe; standard indexed DASH, HLS and progressive output. The old five-client direct carousel is not invoked by the app. A single WEB direct reserve remains only for eligible parser/network failures; no VIDEO SABR result enters the source. Explicit unavailable/rate-limit/confirmed bot verdicts stop the plan. Existing SABR implementation remains dormant for diagnostics and source compatibility; AUDIO SABR untouched.
- VIDEO shares its own four-socket OkHttp pool across adaptive and progressive tracks. Server redirects are bounded, HTTPS/googlevideo validated, identity headers stripped; successful 206 targets remembered for 60s on the same network only. No persisted links or external proxy service. Experimental debug-only HttpEngine not selected by this new factory; test and DEV use the same transport.
- Buffer: 30–75s target, 1.8s startup, 8s rebuffer, 32MiB target with size priority. Target is not a hard process memory ceiling. New route/redirect tests; existing audio playback code unchanged. Offline downloader restores transient URL reuse, stops explicit denied link reuse after bounded attempts; verified partial bytes preserved. Existing failed UI backdrop test unrelated, not changed.
- Piped is a separate external API/proxy (not a local extractor); no third-party instance added without explicit configured endpoint. Existing GPL dependencies unchanged, no third-party code copied. Field retest required.

- Follow-up: a READY decoder with a non-advancing clock is now observed at 2s intervals, with 12s frozen-clock recovery; no periodic priority/UI recomputation during healthy playback. 30s sustained clock progress restores the VIDEO recovery budget. Terminal video error evicts local metadata even if retry is exhausted. All child VIDEO requests are validated before DefaultDataSource, including HLS segments and keys; previously refused redirect targets cannot be reused. Added virtual-time tests. Clock sampling detects a frozen media clock, not independently frozen video frames while audio clock continues.
