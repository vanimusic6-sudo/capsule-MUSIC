# Capsule Music — targeted energy and regression audit (2026-10-27)

Scope: artist metadata, search, More download controls, background work and thumbnail prefetched images. This is a **source-level audit plus CI checks**, NOT a battery / profiler measurement from HONOR hardware.

## Main findings and changes

| Area | Previous overhead or regression | Change | Trade-off and validation |
| --- | --- | --- | --- |
| Search result rows | Each visible Song row could subscribe to a distinct Room `database.song(id)` Flow, and another active-player metadata observer updated an already-visible subtitle. This caused the second artist to appear after the rest of the row and needless row recompositions/database work. | Removed per-row DB credit collectors in summary and filtered results. Metadata now comes from shared Innertube parsing. | UI no longer patches missing Room credits after display; verify artist coverage with the canonical parser and exact-ID Songs enrichment. |
| Top result metadata | Summary rendered immediately and a second network call patched a collaborator into it ~0.5 s later. | Fetch complete matching song credits with a bounded **1800 ms** timeout BEFORE publishing the summary; exactly one `summaryPage` assignment per successful search. | Initial results may be delayed up to 1.8 s when summary returns incomplete credits. Failure/timeout still shows base data; there is no late text jump. Filtered Songs response is cached for later filter use. Network request only issued for incomplete song-artist results. |
| Artist parsing | Search, playlists, album tracks, suggestions, home, history, library, related and player queue used separate groups/first-only/odd-run parsing strategies. | Consolidated mixed song metadata in `PageHelper.extractSongArtists`; `PageHelper.extractArtists` remains its artist-only building block. Recognizes multiple linked performers across metadata groups, excludes duration/album endpoints, preserves adjacent unlinked featured names and resolves duplicate names in favour of navigable credits. | Requires tests for all known renderer shapes; unknown unlinked names in ambiguous later album groups remain intentionally conservative. |
| More for YouTube singles | Two independent optimistic "download still starting" timers lived in nested Composables: 4 s in `YouTubeDownloadMenuItems` and 2 s in shared row, causing extra scheduled jobs and inconsistent spinner state. | Removed the outer 4 s timer. The shared action owns one short pending window and Media3 is the authoritative state. | Ensure immediately repeated taps work before Media3 index emits. |
| Player-only download interaction | Completed and active single-track download opened confirmation dialogs, contrary to player's fast action requirement. | Shared row adds explicit `instantRemove` policy, set **only** in Player More. A second tap when active, or a tap when complete, issues remove immediately. Other songs/collections retain pause-vs-delete selection and existing confirmation. | Deletion is intentionally irreversible; test two distinct policies. |
| Player Audius downloads | Active Audius cancellation showed a modal while the user wanted a direct stop/delete. | Player Audius WorkManager cancellation uses its existing worker's pending MediaStore cleanup without a modal. Completed Audius saved recording deletes directly. | WorkManager worker must cancel/clean up pending output as currently implemented. |
| Global Audius worker state | More sheet on ordinary YouTube tracks subscribed to global `ExperimentalDownload.states` and `savedFilesRevision`, potentially recomposing for unrelated downloads. | Subscribe only when a current player track is Audius. | Media3 observers remain untouched; check Audius row live status. |
| Metadata CPU churn | Duration regex was incorrectly double escaped while being built repeatedly for candidate runs. | Fixed the digit regex and compiled it exactly once in PageHelper. | Only metadata parsing paths changed. |
| Artwork prefetch (retained) | Earlier audit established an actual 1,200 ms fast-skip settle timer; separate failures were mistakenly changed instead. | Retain 420 ms prefetch settle; no additional URL fetching/caching or GPU effect changes in this patch. | A perceptual/artwork test on HONOR remains essential. |

## Avoided high-risk changes

- Did **not** modify InnerTubeX, cipher decode, CDN 403/410 quarantine, playback retry/resume and video fallback logic.
- Did **not** alter Media3 download admission slots, manual-single priority, persisted queue data, service lifecycle or cache eviction.
- Did **not** add continuous search polling, frame callbacks, new high-frequency Compose animation loops or redraw observers.
- Did **not** change Coil memory cache policy, hardware acceleration, audio/video buffer settings or launcher/UI theme.
- Did **not** add automatic performance logging, background battery collectors or telemetry.

## Hardware QA gates (not yet established)

On HONOR Android device:
1. Search a multi-artist track cold and hot: title/entire artist subtitle must appear at once. Also test long/failed network fetches, slow VPN, filters and returning to search.
2. Inspect Home, search suggestion, album, playlist, history, related and queue rows for complete song credits and absence of album/year/duration false artists.
3. From every non-player More: start, second-tap menu (pause/keep vs stop/delete), paused resume, completed remove, no stale dialogs.
4. From Player More only: single YouTube download second tap cancels and deletes with **no confirmation**; completed tap deletes; Audius worker cancellation cleans temporary MediaStore entries.
5. Confirm user-prioritized singles outrank playlist tracks and audio/video playback starts exactly as before.
6. Profile **CPU, memory, frame time and battery** over comparable 30-minute warm playback and navigation sessions, screen brightness and network held constant. No energy savings claim should be published until measured.

## Acceptance

Unit/Compose regression, Innertube parsing tests, Android lint and signed universal APK build must be green. Existing known test failures are measured by paired-baseline suite. Source-level review alone is not proof of improved battery life.
