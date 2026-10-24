# Task 01.1: post-test corrections

Baseline: `3164e7226cf762a3c52ed52186aed167851c25c3`, clean `release/01-core-reliability`.
The supplied logs identify build **e6ec26d**, before the AAC default correction.
The two recordings and three screenshots were inspected. No physical Android device is attached.
Local baseline Gradle invocation failed before running tests: the distribution host was unreachable.

## Changes and causes

- One existing Media3 manager admits two downloads. Pending jobs use a reserved, persisted stop
  reason in its existing index. A main-thread listener reserves slots before sending asynchronous
  start commands, preventing duplicate admission. Explicit song actions persist their priority in
  request metadata; title-only old requests remain supported. Three manual starts are followed by
  one waiting collection member. An active transfer is not preempted. Collection cancellation
  preserves independent manual requests and completed files. All file transfers, including approved
  third-party WorkManager jobs, share two cancellable permits. No polling scheduler was added.
- Slot-waiting entries are displayed as queued. The common comet uses its available bounds more
  effectively, with a density-aware minimum trail stroke. Playlist overlay metadata now places the
  indicator before the song count in one row. Compact badges use an 18 dp footprint.
- A main-thread offline projection retains the canonical queue, checks complete caches and local
  content/file readability on IO, and hands only eligible items to Media3. The canonical list and
  index are used for persistence, even when the effective queue is empty. Reconnection restores
  hidden items around a healthy current track without restarting it. The shared audio resolver
  blocks network access on an offline cache miss. Pagination/automix requests are gated offline.
- Artist artwork had already faded toward the page surface while the toolbar applied another
  translucent surface, creating a hard edge. The toolbar now uses the same faded backdrop until
  it is fully opaque. Portrait/hero ratios recover part of the pre-polish height (1.25/1.48 rather
  than 1.10/1.30; original was 1.36/1.69). No palette changes. Description animation measures the
  full text and moves one clipping boundary; maxLines does not change early during collapse.
  While that boundary moves, following track rows use measured placement directly instead of a
  second placement animation chasing every intermediate height. Actual row insert/reorder animation remains.
- Some artist sections had positional identity, local album keys included their list index, and
  Home section keys included the first song ID. These keys could migrate/reset nested scroll
  state during refresh. Keys now identify the section/entity, independent of its current contents.
  The mixed discovery shelf was also reranked after each partial catalog arrival. It now publishes
  one completed provider batch; the ranking algorithm and its final recommendations are unchanged.
  Common song/album/playlist grid metadata reserves both subtitle lines; shelf viewports use
  typography-scaled height so large-font metadata is not clipped by the former fixed dp cap. The swipe wrapper owns the caller's lazy
  placement modifier instead of hiding it inside a child; intentional item animations remain.
  Deferred queue chunks are children of the queue load job and also check queue identity, so an
  old selection cannot append its songs to a newer queue after its delay. Explicit-content filtering
  now remaps the selected index and preserves seek position only when the same track survives.
- Logs contain real TLS open timeouts, CDN redirects and 403s followed by bot checks on fallback
  profiles. One refused CDN URL previously immediately retired its issuing client. Recovery now
  obtains one fresh signed URL through the same policy before bounded client failover, within
  the existing three-attempt budget. Identical refused URLs are still quarantined; bot/429 guards,
  separate download clients and pacing are preserved. VPN causation cannot be proven from these logs.
- Cache completion now verifies that span files exist and are not truncated as well as checking
  coverage/expected length. The index alone cannot establish offline eligibility.

## Verification scope

Final run IDs, test totals and artifact verification are supplied in the task completion report. Added real Media3 queue and offline timeline tests,
priority/fairness/metadata tests, shared permit cancellation tests, cache file disappearance tests,
and Compose metadata geometry coverage. Existing playback, extractor, artist and crash tests remain
in the focused workflow, together with Android lint, runtime verification and Universal Debug APK.

Device-only work remains: perceived scroll/animation smoothness, 60/90/120 Hz, gestures with large
fonts, VPN A/B reproduction, background/process-death testing with actual storage, CPU/RAM/energy
and tap-to-first-sound benchmarks. Source fixes and headless tests are not measurements of these.

First CI pass at `68e7572`: 652 focused tests executed, 650 passed. The two failures were an old
source guard expecting a direct `sendAddDownload` call (now it verifies the priority wrapper AND
its delegation to the same Media3 API) and a portrait-edge pixel sample at the former 1.10 ratio
(now derived from the actual artwork boundary). No test was disabled. Follow-up checks include
restored download admission, failure/cancel isolation, partial catalog publication and full-size
text clipping with following lazy rows.

Second CI pass at `3be5d12`: main Android code compiled; unit-test compilation found a redundant
Robolectric `getApplication<Application>()` type argument in the new restore/failure test. Corrected
to the API's non-generic getter. Collection-menu cancellation was also brought onto the same
stop/resume path as screen actions; explicit deletion of completed collections remains available.

Third CI pass at `63e5e98`: focused tests and InnerTube tests passed; lint/build were still running
when the final race/file-validation follow-up was prepared. The follow-up also tests reconnecting
during disk eligibility checks and legacy cache entries whose length comes from Room. Final file
completion now uses the same real-span validation as offline eligibility.

## Legacy platform boundary

The pre-existing independent-source fallback on Android 8–9 (SDK 26–28) still delegates to the OS
DownloadManager. It cannot share an in-process permit held by Media3/WorkManager; claiming the
new global two-transfer cap for that legacy path would be incorrect. Safely replacing its public
Downloads persistence, permissions and process-death behavior requires a separate legacy migration
and device validation. That fallback was retained rather than silently disabling approved-source
saving or changing the destination of users' files. Media3 has a two-transfer cap on every supported
SDK; the shared Media3/approved-source-worker cap applies on Android 10 and newer.

Final follow-up scope: reconnect-during-disk-check completion is owned by the original user selection,
with no duplicate prepare or lost start index. Complete-file validation is synchronized with cache
span snapshots and is also required before a download may report success. Description text layout
is remembered across height-animation frames. Preload is accepted only when it matches the selected
retained queue item, including explicit-content filtering. Remaining pre-29 system-downloader
limitations above are deliberately disclosed, not treated as tested or fixed.
