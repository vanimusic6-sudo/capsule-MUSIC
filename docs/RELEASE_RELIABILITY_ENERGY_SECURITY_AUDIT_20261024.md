# Capsule Music — reliability, power and security audit (2026-10-24)

**Scope:** release/01-core-reliability compared with the device-tested `f11295e` baseline.
**Evidence:** source diff, supplied 21:34:29 device log, two screenshots of the artist page,
Media3/Robolectric regression tests and GitHub Actions. This is a **static and CI audit**,
not a claim of physical-device wattage, thermal or battery measurements.

## Decisions and fixes from the second device feedback

| Severity | Observed problem / causal evidence | Action |
|---|---|---|
| High | Mini-player **plus** was an actual artist-subscription action and consumed 40 dp of tap space | Removed button, Room subscription observer, and related composable. The like/heart and navigation remain. |
| High | Plus-to-check glyph continued moving while the user wanted only label feedback | Artist subscription is now **plain text** (“Подписаться” / “Вы подписаны”); no glyph morph, label crossfade or subscription surface tween. |
| High | Artist toolbar remained transparent over scrolled track rows; prior fixes used a sudden fully-opaque colour at the last frame | Pinned toolbar background follows a continuous ease, becomes opaque **before** the portrait fades fully, and stays opaque while scrolling rows. No second scroll animation timer. |
| Medium | Explicit “E” shifted relative to year because metadata reserved two lines and row vertically centred the badge | Grid badges align to the **first** subtitle line and metadata takes the remaining measured width. |
| High | Tapping an active track offered only **pause**, leaving partial bytes without the requested destructive choice | Download menu shows a **stop-and-delete confirmation**. Confirm calls Media3 removal of the indexed download, which removes its cached bytes; cancelling the dialog preserves them. |
| High | Album and playlist in-progress buttons paused without removing files | Both now require confirmation before deleting their audio. Local playlist removal no longer clears playlist membership. |
| High | Audius in-progress state could not be cancelled and completed MediaStore files were unremovable through the player | Player now offers MediaStore-backed deletion after restart, and requests cancellation of the unique WorkManager worker for an active transfer. The worker's cancellation path deletes its own pending MediaStore record. |
| High | Historic completed downloads bearing the waiting stop reason were shown as queued | State presentation distinguishes **completed** from **waiting**; completed requests never consume admission slots. |

### Queue invariants to preserve

- Maximum **two** parallel file transfers across the independent and YouTube download paths.
- A manually selected song takes priority; after three manual starts, a background entry is protected from starvation.
- Media3 completed entries must remain completed and removable; a repeated add must not reset active downloads.
- An individual deletion targets only that song; a collection stop-and-delete applies to the requested collection and never deletes the library playlist definition.
- Preview/album artwork failure must **never** erase otherwise-valid audio.
- Android MediaStore (Audius) and Media3 cache (YouTube) remain separate and use separate deletion APIs.
- Do not log source URLs, token-bearing query strings or private recording filenames.

## Playback/network stability: audit notes

**Fixed in earlier follow-up:** true current/next Media3 loader demand no longer inherits speculative
prefetch's `isPlaying` + playback-position eligibility, which caused 1–5 ms cancellations and
repeated retry backoff. Repeated Media3 AddDownload for active transfers was also prevented.
Download bot-check handling retains cooldown/client identity; signed 403/410 responses may receive a
bounded fresh resolve and use a dedicated download-only short-lived URL cache.

**Not established:** that CDN read timeouts, server-side 403, socket resets, VPN changes or
proxy-specific disruptions have disappeared. No controlled side-by-side device comparison exists.
Do **not** change the stable audio resolver/itag priority based merely on failures seen on one route.

**Residual hazards worth instrumenting:**

1. On cold start, `DownloadUtil` requests artwork repair for **every** completed Media3 entry on
   any validated network, including metered cellular. Large historical libraries can generate
   background network traffic and battery use without the user requesting a re-download.
2. `DownloadedArtwork.save()` holds one global write mutex during the image-loader's network work.
   Two different covers therefore serialize; slow images may delay download completion.
3. `ResolvingAudioDownloader` joins its artwork job **while holding the shared two-slot transfer
   semaphore**. A slow cover can temporarily block an independent audio download, even after
   audio bytes finish. Separate album-art admission from the audio slot after an instrumented
   baseline test; do not silently drop the cover-completion invariant.
4. `DownloadedArtworkInterceptor` validates files via `BitmapFactory.decodeFile` bounds on
   image cache hits. It uses IO dispatching, not the UI thread, but repeated large-list scrolling
   may still cost filesystem calls/CPU. A bounded fingerprint cache would need invalidation tests.
5. `DownloadUtil.awaitDownloadResolveWindow()` holds a mutex across cooldown delays. This
   intentionally gates extraction but may serialize ready downloads after a network disruption.
6. Album/playlist UI state is assembled from Media3 entries; missing members and stopped jobs
   require separate tests so an incomplete set never claims “Downloaded”.

No CPU, FPS, energy, memory, or battery percentages are claimed without actual traces.

## Security & privacy review

**Corrected:**
- `DebugActivity` was exported and could render a private persisted crash stack. Now
  `android:exported="false"`; in-app crash navigation remains possible.
- MediaStore removal previously checked a `Downloads/Capsule Music` folder but **not**
  `OWNER_PACKAGE_NAME`. Deletion now requires current app ownership, preventing the app from
  accidentally removing unrelated media in the same folder.
- Independent-source HTTP/MediaStore errors previously passed full exception instances into
  logging, potentially including signed URLs. New diagnostics record only error *types*.
- Crash stacks and transient downloaded artwork are excluded from Android cloud/device backup,
  alongside existing media cache exclusions.

**Existing protections checked:** YouTube downloader validates HTTPS and allowed
`googlevideo/youtube/googleusercontent` hosts; independent downloads enforce HTTPS on every
redirect, bounded redirects (6), max recording size (240 MiB), WorkManager cancellation and scoped
MediaStore writes. Media3 download service is not exported; the separate video extractor provider
is not exported. A bearer token baked into `BuildConfig` is **not a secret boundary** and must not
be treated as authentication by the server.

**Open security considerations (not silently changed):**
- Application manifest has global `android:usesCleartextTraffic="true"`; identify all radio
  compatibility dependencies before restricting to a network security config.
- `FileProvider` is non-exported but exposes a very broad external-path mapping under grants;
  audit concrete share entry points and narrow whitelisted paths without breaking exports.
- `MainActivity` is externally browsable by design. Deep-link inputs should be fuzz-tested,
  including huge/encoded URIs and repeated attempts.
- Avoid backing up tokens, credentials or private logs under other app-specific preference files;
  this requires a complete preference inventory and backup/restore test.
- The work cancellation → MediaStore publication race deserves a repeated rapid-cancel test.
  A cancellation acknowledgement is not the same as proof that every byte was deleted.

## Verification and release gate

**CI** (GitHub Actions on the branch): focused playback/queue/cache/UI tests, InnerTube tests,
Android lint, `verifyAndroidJsonRuntime`, debug APK, signed Universal Release APK and the
full suite compared with the prior baseline. Full-suite baseline failures must remain visible.

**Device matrix:**
1. Android 16/17 HONOR; fresh install and over-the-top upgrade **without clearing data**.
2. 50/200-track playlist + immediately manually requested single track, network online/offline,
   repeated taps, pause, process kill and restart. Validate **only two active transfers**.
3. Delete while downloading 10%, 90% and immediately after completion; confirm Media3 index,
   offline playback, cache bytes, cover references and library playlist survival.
4. Audius download/delete with app restart and with cancellation at near-publication; verify no
   orphaned MediaStore pending row or private art file.
5. 20 artist-page fling/overscroll/reverse runs: no toolbar late tint flash, no text drawn
   under the opaque bar, explicit “E” aligned to the metadata first line, touch hit targets valid.
6. Perfetto / Android Studio CPU Profiler, Battery Historian or batterystats and network counters
   for matched **before/after** 30-minute sessions: screen-off audio, scroll-loop artist page,
   large-download workload, Wi-Fi and metered cellular. Compare actual medians and p95.
7. Verify APK signature, installed package identity, exported components, backup restore,
   `FileProvider` grants and HTTP link handling.

**Sign-off:** Static source fixes are committed; Android builds/tests may still be running or
report failures. Production reliability, thermal behaviour, battery life and network stability
**cannot** be signed off without physical traces and the latest successful CI.
