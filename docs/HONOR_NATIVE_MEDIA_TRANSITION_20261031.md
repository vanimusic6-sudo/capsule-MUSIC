# HONOR Magic Capsule native media transition — ArchiveTune parity (31 Oct 2026)

**Evidence:** user recording `10386.mp4` (~15 s, Android 17 HONOR). In Capsule Music, a track remains visible in the full player, but leaving for the launcher does **not** hand its media status to the island (~2.5–4.5 s). ArchiveTune returns to the launcher with a black native media pill and expandable playback controls (~11.6–14.9 s). This is a *native SystemUI media integration* issue, NOT a missing in-app Compose animation.

## Source comparison (ArchiveTune main vs Capsule prior to this patch)

ArchiveTune:
- Registers its `MediaLibrarySession` in `MusicService` with `addSession(mediaSession)` as soon as the session is built.
- Sets a `MediaNotification.Provider` that delegates to `DefaultMediaNotificationProvider` and remembers the latest actual native `MediaNotification`.
- Uses the cached native MediaStyle notification when a service start arrives during resumed playback, rather than replacing it with `CATEGORY_SERVICE`.
- Leaves the platform native service notification, metadata, media session and clock to Media3.

Capsule had:
- An async internal `MediaController.Builder` binding and `onGetSession`, but no proactive `addSession`. Android Media3 documents that `onGetSession` registers a session automatically when called, so missing `addSession` is not *by itself* proof of a regression, but proactive registration removes the internal-controller race.
- `MusicService.ensureStartedAsForeground()` unconditionally constructed a generic `Notification.CATEGORY_SERVICE` using the **same notification ID (888)** as Media3's real media notification. It was called from *every foreground-required* `onUpdateNotification`, and from *every* `onStartCommand`. A later service start could thus replace the MediaStyle notification with a generic placeholder and stop HONOR recognizing a media session. The one-shot boolean guarded only Capsule's placeholder, not Media3's actual foreground status.
- On the earlier PR #63 branch, the exported MediaLibraryService manifest binding was corrected, and image-induced `player.replaceMediaItem()` / forced 3-second notification refresh removed. Those fixes remain intact.

## This patch

1. Introduce `CapsuleMediaNotificationProvider`, delegating all metadata, icons, media buttons and asynchronous updates to Media3's default provider. It retains the latest actual `MediaNotification` for start-command handover; it does not implement a fake notification or claim private HONOR SDK access.
2. Proactively `addSession(mediaSession)` once, guarded by `isSessionAdded()`. Continue returning the same session in `onGetSession`. Media3 will remove the session on release.
3. Stop calling Capsule's generic-foreground helper from `onUpdateNotification`. Let Media3 publish and manage the native media-style notification/foreground transition.
4. When the service is restarted while it has a current song, prefer the cached genuine Media3 media notification. If none has been generated, request a native notification first; only then use a generic placeholder to satisfy a cold `startForegroundService` deadline.
5. Add `sessionAdded` and `nativeNotificationReady` to existing privacy-safe SystemUI diagnostics. Do not log sensitive metadata, signed links or user tokens.
6. Fix two test regressions observed on the previous playback-fairness branch: bound direct AAC reads now enforce a nontruncated content-length just as Opus already did; Audius yields before its first body read and unit cancellation on that yield remains responsive.

## Device gate before claiming solved

- With Capsule music playing, exit to launcher: verify the same HONOR top pill transition visible with ArchiveTune. Tap to expand: title, cover, seek, pause, next and back-to-app.
- Pause/resume inside app, with screen off / lock screen / Bluetooth, then repeat with one and two downloading songs. Verify music never stops on app close/launcher transition.
- Confirm Android Quick Settings media tile first. If that tile is correct but the HONOR island stays absent, verify `Settings → Notifications & status bar → Magic Capsule → Media playback` and inspect OEM eligibility; Android cannot force an unsupported third-party capsule.
- Compare `CapsuleSystemMedia` diagnostics for `sessionAdded=true`, `nativeNotificationReady=true`, title/artist/artwork fields and notification/channel permissions.
- Ensure cached media is a valid `Notification.MediaStyle` with system transport controls; no 3-second polling, forced player queue replacement, or background bitmap recompression.
- Confirm no trusted-controller permission regression and that no unauthorized library/search commands are exposed.

**Not yet proven on device.** The code addresses two concrete differences against ArchiveTune and a possible foreground notification overwrite. A passing CI build means only that the code compiles and tests run; it does not guarantee the HONOR firmware will present a capsule.
