# Android system media card (Media3 / HONOR)

This document describes the **working** native media integration. Avoid
reintroducing the early generic-foreground-notification or periodic refresh
experiments: both left HONOR's system mini-player without an Android media card.

## Lifecycle

- `MainActivity` **binds** the music service; it does not eagerly start a
  generic foreground service on every screen opening.
- `MusicService` creates one `MediaLibrarySession`. It installs
  `CapsuleMediaNotificationProvider` (delegating to Media3's
  `DefaultMediaNotificationProvider`) **before** `addSession`.
- `MediaLibrarySessionCallback.onConnect` uses
  `AcceptedResultBuilder(session, controller)`, preserving Media3's
  built-in notification controller and its timeline commands.
  Only trusted/internal clients receive custom playback commands;
  untrusted clients still cannot browse local library data.
- Playback makes the service a started service. Normal Media3
  `onUpdateNotification` does the native foreground handover.
  Never override its notification with `CATEGORY_SERVICE`, or call
  `super.onUpdateNotification` manually outside the override.
- Every `MediaItem` supplies `isPlayable=true`, title, artist and artwork;
  artwork decode is bounded.

## Required fallback for real devices

A physical HONOR Android 17 log from 2026-10-31 showed
`playing=true`, `sessionAdded=true`, `channelImportance=2`, but
`notificationCount=0`, even after the app went Home. The system had
nothing to animate. The **working** fix preserves
`NativeMediaCardFallback`.

`MusicService.scheduleNativeMediaCardCheck()` is **event-driven**: allow
450 ms for Media3, then if no real media notification is posted, publish a
platform `Notification.MediaStyle` with the **same** platform session token,
channel, notification ID and transport actions. When Media3 successfully
publishes its own media card, it takes precedence. Do not create another
MediaSession or show an in-app fake status-bar capsule.

Fallback behavior must remain correct across pause/resume, track changes,
leaving the Activity, audio playing in the background and service teardown.
No polling timer or CPU-intensive recurring notification updates are needed.

## Regression checks

1. Build DEV and TEST variants; run playback, notification and offline
   download unit tests.
2. Play on HONOR, go Home, verify the top media capsule and quick-settings
   player. Pause/resume, skip, return to app; audio must not reset.
3. Confirm there is **one** system media session, with real transport controls,
   and no generic service notification replacing it.
4. For diagnosis, use the one-shot fallback's `CapsuleSystemMedia` success
   or error entry only when diagnostic logging is enabled. Avoid storing
   complete track IDs, account data or signed CDN URLs in logs.

Tests cannot assert HONOR's proprietary animation on CI: the final behavior
is a physical device acceptance gate.
