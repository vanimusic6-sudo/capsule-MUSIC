# Downloads, streams and artist motion — 2026-10-24

Base: `01f526b55c0af1d7c3e6c00dc8abebe4dcb454ba`.
Branch: `release/01-core-reliability`; initial working tree clean.

## Download queue and shared status

- Media3 now runs one file at a time, using its persistent DownloadIndex queue. The existing
  ProgressiveDownloader/AudioChunkedDataSource still reads bounded ranges in sequence. No
  parallel range fan-out or second transfer engine was introduced.
- Independent approved-source WorkManager transfers also use one permit; a waiting transfer
  is not prematurely marked DOWNLOADING.
- The first resolver ticket still starts immediately. Existing 3.2–5.8 second spacing applies
  between download resolutions, not before the first. If a complete transfer takes longer
  than this interval, the next item does not wait an extra interval after completion.
- The token-free download chain remains `VISIONOS_0_1 → WEB_EMBEDDED_PLAYER → VISIONOS`.
  It does not inherit foreground WEB priorities, mint Po tokens, or modify audio client failure
  state. Bot challenges stop identity fallback and retain the download-only cooldown.
  Global 429 protection, bounded 403/410 recovery and complete-cache verification remain.
- Collection menus, album rows, playlist rows and album/playlist headers use one status
  calculation against current membership and the same observable DownloadIndex snapshot.
  An empty list cannot be downloaded; a missing/failed member cannot be complete; one queued
  member keeps a running batch visible even if another member failed. Recycled album cells
  and playlist menus observe their current entity IDs, not the first composition's ID.
- Playlist badges observe an ID-only Room query, avoiding hydration of every song, artist and album
  just to compute a small icon. Download progress/state updates do not re-run this membership query.
- The remote playlist menu previously computed status from its optional, usually empty
  `songs` parameter while fetching songs only when Download was pressed. It now loads missing
  membership once when opened and uses that membership for status and download actions.
  Download remains disabled until there are known members; it does not start a duplicate
  membership fetch from the button.
- Collection Cancel uses Media3 stop reasons, preserving partial bytes for a later Download
  action and leaving completed entries complete even if completion races with Cancel. Explicit
  Remove Download still removes files through the existing confirmation path.

## Stream dependencies, formats and security

- InnerTubeX pinned `v0.5.2 → v0.11.2` (released 2026-10-22), Kotlin `2.4.10 → 2.4.21`,
  Ktor `3.5.2 → 3.6.0`. Exact versions, no snapshots. NewPipe stays at `v0.26.5`, the latest
  published release verified during this task. The independent PipePipe video fallback pin
  is unchanged: no unverified snapshot replacement.
- Reviewed upstream extraction/session changes, including session-bound credentials, bounded
  response reads, redirect restrictions, original audio-track selection, player deciphering
  and cancellation. Capsule retains explicit client manifests and its request budgets; it does
  not rewrite token rules or turn on unsupported SABR/HLS transports.
- AAC/M4A participates automatically in normal quality selection. A follow-up removes the
  briefly exposed AAC-only quality choice: the UI keeps Auto/High/Low. High can choose AAC when
  it is better; every tier accepts AAC when it is the only suitable audio format.
- A separate **Prefer AAC/M4A** compatibility switch sits immediately after hardware offload
  in the same advanced settings section. It is independent of offload support and defaults off
  (automatic selection stays active). When enabled, the existing MP4 audio selector is used for
  new stream resolutions/downloads. It never relabels or transcodes existing offline files.
  A source must supply suitable AAC for this compatibility path.
- Legacy saved `audioQuality=AAC` remains readable and preserves the previous opt-in. Changing
  quality atomically saves that opt-in under its own key; explicitly disabling it restores normal
  selection. Playback URL cache policy observes the effective format choice. Download selection
  reads the same atomic settings snapshot but retains its separate clients/token-free extractor.
  Approved external `.aac` downloads continue using `audio/aac`.
- HTTPS address validation additionally rejects nonstandard ports and retains host/userinfo/
  fragment checks, including validation of every offline redirect. No DRM or source restrictions
  are bypassed. Account-restricted recordings can still be rejected by token-free downloads.

## Unavailable tracks

- Parse explicit source availability (`isPlayable=false` and the real
  `musicItemRendererDisplayPolicy=...GREY_OUT` wire field). Preserve identified playlist/album
  entries even when thumbnails, duration, album endpoint or set-video ID are absent.
  Removed unsafe `!!` dereferences on those incomplete album fields.
- Carry catalog availability in queue metadata. Shared thumbnails in search, lists, grids,
  library and queue show grayscale/dimming and an error glyph, with an accessible reason.
  An explicitly unavailable catalog item explains why when tapped, without replacing an
  already healthy queue. A complete offline copy remains usable.
- The stream resolver records only final typed source/auth restrictions after its fallback
  chain. DNS, timeouts, generic CDN 403/410 and bot challenges never mark a recording deleted.
  The player error panel presents source unavailability separately from network/auth errors.
- Learned restrictions are bounded to 256 entries per process; successful resolution and
  session/network reset remove stale restrictions. They are not permanent library mutations.
  Entries omitted entirely by a source, or without a usable identifier, cannot be reconstructed.

## Artist and subscription motion

- Shortened the portrait from width×1.36 to width×1.10 and the normal hero from width×1.69+16dp
  to width×1.30+16dp. Retained actions, touch targets, artwork source and palette. Large font
  still stacks the action buttons instead of truncating them to force a fixed phone height.
- One continuous smooth scroll fraction controls photo attenuation and toolbar surface opacity;
  the artist portrait no longer meets an abrupt opaque-toolbar threshold.
- Description text owns its reversible 320ms height transition. Its button follows the measured
  text height; animating only the outer column had still made the inner button jump.
- Shared subscribe glyph continuously morphs plus into check; labels smoothly change size and
  position. Artist action colors interpolate; menus keep their inherited typography.
- Artist hero, both artist menus and mini-player observe Room subscription state. Button taps
  toggle the committed row in a transaction, avoiding repeated writes based on stale button
  snapshots. Explicit Set Subscribed remains available for onboarding/import/outbox operations.
  Removed the hero's never-cleared local override which could hide later external changes.
- Comet uses one continuous phase read at draw time, a tapered trail following the ellipse,
  fixed bounds and indeterminate progress semantics. Removed independent pulse/recomposition
  clocks. Finite transitions use Compose's duration-scale handling and reverse from current state.

## Verification

Actual local verification on the final application sources:

- Full app JVM suite: **1209 tests, 13 failures, 0 errors**. All 13 are the same pre-existing
  HomeStartupStability/LyricsUiRegression/ScreenBottomInset/SearchBarInset/Screens/StandardChrome
  failures recorded before this task. The overall full-suite Gradle command exits 1; it is not
  described as a green run.
- The exact app subset selected by the updated core workflow: **637 tests, 0 failures/errors**.
  This includes real Media3 serialized downloads/cancellation, token startup, fallback, cache
  integrity, AAC selection, unavailable-state handling, artist layout/motion, subscriptions,
  navigation and the previous NaN regression. A separate 22-test final motion/queue/subscription
  run also passed.
- Local InnerTube module: **102 tests, 0 failures/errors**.
- Android JSON runtime verification passed. Universal Debug APK built successfully.
- Completed local Android lint: **0 errors, 595 warnings, 28 hints**. CI repeats lint and builds
  Debug plus signed Release on the committed tree; its workflow artifacts retain the final reports.
- New description motion tests explicitly flush Compose layout frames while the test clock is
  paused; intermediate geometry and interruption are checked, without claiming native touch timing.
- A full-suite-only logging test raced Application's asynchronous debug preference startup.
  Its fixture now uses plain Application because it tests the logging controller directly.
  Both logging checks pass in the final full run. Two scripted HTTP chains also implement the
  updated OkHttp interface through a fail-on-unexpected-operation test delegate.
- `git diff --check` passed; modified-source checks found no private keys, GitHub tokens or Google
  API keys. No signing credentials or generated binaries are committed.

Real Android device is not
available (`adb devices` returned none); HONOR Android 17, refresh-rate smoothness, TalkBack,
network handovers, live YouTube behavior and server bot-check rates cannot be certified by JVM
or mock tests. No before/after device latency, CPU, memory or battery measurement is claimed.

The prior full app suite has 13 known unrelated UI failures documented in
`CRASH_SEEKABLE_TRANSITION_NAN.md`; the focused core workflow remains a separate gate.

## Primary references reviewed

- https://github.com/MetrolistGroup/innertubex/releases/tag/v0.11.2
- https://github.com/MetrolistGroup/innertubex/blob/v0.11.2/CHANGELOG.md
- https://github.com/TeamNewPipe/NewPipeExtractor/releases/tag/v0.26.5
- https://github.com/sigma67/ytmusicapi/blob/main/ytmusicapi/parsers/playlists.py
- https://developer.android.com/media/media3/exoplayer/supported-formats

## AAC preference follow-up

Base: `e6ec26dfa8cfd181852acde22fbd80bdc1419c17`. Adds regression tests for automatic
AAC-only fallback, High choosing AAC versus Opus by quality, offload independence, legacy
preference migration, explicit opt-out and atomic quality changes. The verification totals
above describe the preceding package; the follow-up is verified by the core and release CI
workflows on its own commit. The new workspace has Java 17 and no Android SDK, so no local
Android build result is claimed for this follow-up. No physical device is connected.
