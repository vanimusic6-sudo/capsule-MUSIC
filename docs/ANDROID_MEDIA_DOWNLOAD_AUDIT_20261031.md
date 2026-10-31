# Android MediaSession, Magic Capsule, downloads and AudioTrack — 31 Oct 2026

## Source and evidence, not assumptions

Uploaded field log: `capsule-logs-2026-10-31_16-10-47.txt`, 1,545 app log entries, 16:02–16:10 local. **Build is `df02705 devRelease`**, NOT the newer fix PR #62 or this PR. HTTP3 was not active in this DEV variant. The header reports **36 audio CDN opens, 0 HTTP refusals** and only `http/1.1` sockets. This is not proof of a QUIC, SABR or HTTP/429 failure.

Download states recorded from `I/AudioDownload: state` lines:
- **62 distinct download IDs** appear in the log.
- **31 reach Media3 `STATE_COMPLETED=3`** with nonzero byte counts (many 2–3 MB). So the statement `no files download at all` is **not supported by the export**; the UI/list/notification may nevertheless misleadingly show no progress or a stopped queue.
- **1 reaches `STATE_FAILED=4`, 0 bytes**, reason `PlayerClientDirector$PlayerRequestTimeoutException`. It first tries VISIONOS and then WEB_EMBEDDED_PLAYER, both time out. This is an **upstream /player resolve timeout**, not a download cache or filesystem write exception and not a 403/429 response.
- **30 remain `STATE_STOPPED=1`, `stopReason=2`** at export: `2` is Capsule's *internal waiting-for-slot reason*; these are pending queue members, NOT Media3 error code 2. The last item transitions from `STATE_DOWNLOADING=2` to `STATE_COMPLETED=3` on the **final exported line (1554)**. Only two simultaneous download transfers are admitted by design.
- **15 `STATE_REMOVING=5` intermediate callbacks** occurred; these are not terminal failures.
- One `DownloadArtwork: Cover unavailable; audio retained` warning proves a thumbnail failed but **must not** be conflated with a missing audio file.
- No `music-underrun` or `AudioTrack` event appears in **this** 16:02–16:10 export. The *earlier* 15:52 field log included one AudioTrack underrun at 15:48:02 with large buffered audio ahead and elapsedSinceLastFeedMs > 13 s. Neither log proves CPU heating comes from Android session/notification handling.
- 15:44–15:52 earlier log also showed 10,302 network read() calls on a 588 KB Opus cache tail; fixed separately in PR #62. This is **not** a finding unique to the current export.

## Code path inspected

`app/src/main/AndroidManifest.xml` applied system-only `android.permission.BIND_MEDIA_BROWSER_SERVICE` to the exported `MusicService`, despite also advertising Media3 `MediaSessionService` / `MediaLibraryService` and the legacy `MediaBrowserService` interfaces. Android's current Media3 official manifest sample **does not apply this permission**; standard system/OEM controllers need to bind.

`MediaLibrarySessionCallback.onConnect` called `super.onConnect` but **unconditionally replaced its result with `ConnectionResult.accept(...)`**. Media3 v1.11 defaults to restricting untrusted controllers; bypassing this on an exported unrestricted service would unnecessarily expose library data and session mutation commands. Those two defects must be repaired **together**, not separately.

`MusicService` also:
- started a generic ongoing `CATEGORY_SERVICE` foreground notification in `onCreate` before there was a playing MediaItem;
- forced `super.onUpdateNotification` on HONOR/Huawei **every 3 s** while the screen was interactive;
- fetched and JPEG-compressed up to 400 KB of every track's artwork and called `player.replaceMediaItem(...)` on the currently playing item. Such timeline mutation just to populate OEM artwork is not justified when the same `MediaLibrarySession` already exposes `title`, `artist`, `artworkUri` and a `CacheBitmapLoader`.

This does NOT prove the HONOR Magic Capsule can or will show the media player: HONOR documents that **only selected tasks and apps** are supported, and `Settings > Notifications & status bar > Magic Capsule > Media playback` must be enabled if provided. Android 16's generic Live Updates are **not** the same as OEM Magic Capsule and originally excluded media notifications.

`DownloadUtil` used an exception-message heuristic that classified generic `timed out`, `unavailable`, `reset by peer` and `no playable clients` as possible server throttle, delaying the rest of the download queue. It also immediately failed a Media3 download after a `PlayerRequestTimeoutException` despite its existing two-attempt resolve budget.

## Isolated corrections in `fix/android-media-downloads-20261031`

1. Remove `BIND_MEDIA_BROWSER_SERVICE` from `MusicService` manifest registration, matching Android's Media3 exported service contract. Retain foreground service types.
2. In `MediaLibrarySessionCallback`, preserve Media3 1.11's original restrictions for untrusted controllers; only trusted controllers receive Capsule custom commands. Explicitly reject untrusted library/root/children/item/search queries and custom mutations.
3. Allow Media3's native player metadata, bitmap loader and timestamped session playback state to drive SystemUI. Remove the 3-second HONOR notification poll and per-track bitmap->JPEG->`replaceMediaItem` mutation. Keep one-shot true state/seek/track notification refresh and the existing reliable Media3 provider.
4. Stop showing a generic foreground service notification unconditionally during `onCreate`: the service's started / playback-specific paths still meet FGS requirements.
5. Emit bounded privacy-safe `CapsuleSystemMedia: platform-session` diagnostics: media item, playing state, presence of title/artist/art, app notification permission state, notification channel importance. The new `music-underrun` diagnostic records source buffer duration, playback/loading state, audio focus and network presence to distinguish renderer vs source stalls.
6. Retry **one genuinely transient /player client timeout** within the preexisting two-attempt resolve budget; cancellation, HTTP statuses including 403/410/429, and all explicit blocks retain their existing handlers. Do not treat generic timeouts as YouTube bot-check or 429, and never fake server throttle strikes when no exception is available.
7. Regression tests for retry classifier (timeout vs 429/cancellation/authorization). Preserve the download queue admission policy and all media art cache formats.

## Android 17 / HONOR verification on-device

1. Play one YouTube Music track with download queue idle. Open Android Quick Settings, check the standard media playback card with title/art/seek/play/pause, lock screen, Bluetooth buttons; then check HONOR Magic Capsule with the OEM media playback toggle enabled. If the media card appears but Magic Capsule does not, the remaining issue is likely device-specific enablement/compatibility rather than a missing Media3 session.
2. Export fresh logs filtered for `CapsuleSystemMedia`, `CapsuleCodec`, `AudioDownload` and `PlaybackHealth`. Correlate `platform-session` fields with the actual system UI state. No user identifiers or token values are logged by the new diagnostics.
3. Download an individual track first, confirm it reaches `STATE_COMPLETED=3`, appears in Library > Downloaded and plays offline (airplane mode). Then download a collection of ~5 and compare progress. Only after that retry the 60-track list.
4. For the original timed-out media item, verify `resolve-timeout-retry` and that it succeeds or ends in a **clear** terminal failure rather than hanging indefinitely.
5. Compare 30 min playback with background screen off and on, no downloads and two downloads; use Android Perfetto/battery statistics for CPU/wakelocks/heat. A real thermal improvement is **not proven by CI**.
6. When debugging an underrun, prioritize `audioFocus`, `bufferedAheadMs`, playback state, Android audio route and `timeSinceFeedMs` over simplistic network throughput. **Do not change audio codec or buffer time based on one event.**

See Android Media3 documentation for MediaLibraryService manifest and Media3 1.11 trusted-controller defaults. This PR should stay isolated until the device's actual media controls and download visibility are verified.
