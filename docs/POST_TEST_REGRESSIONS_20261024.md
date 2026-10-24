# Follow-up to the 21:34:29 manual-test log

Baseline: `f11295ea80412610e3ec1e5bca4cfc93cc0bcf91`, clean
`release/01-core-reliability`. The supplied log identifies this exact release build.

## Evidence and changes

- Actual next-period Media3 loading inherited the speculative prefetch condition (`isPlaying`
  and minimum played position). While paused or during an early transition it cancelled in
  1–5 ms, repeatedly entering Media3's retry backoff. Real current/next-period demand now uses
  timeline membership; optional prefetch retains its conservative policy and abandoned items
  remain cancelled. URL, resolver and CDN gates agree. This does not remove server-side errors.
- A single-song menu used to remove a queued playlist request on tap. A background member now
  offers **Download now**, promotes the same indexed job and can pause one background transfer
  without deleting partial bytes. Two transfers remain the maximum. A separate eight-entry,
  60-second download URL cache avoids repeating extraction after short pauses; it rejects changed
  quality/network contexts and removes refused URLs before refresh. It never consumes player URLs. After three manual starts
  a background admission is protected from preemption until it finishes. Manual cancel pauses
  its own request; collection cancellation continues to preserve independently requested songs.
- Media3's actual `addDownload` implementation resets an active merged request to QUEUED and
  cancels its task. Duplicate collection submissions now leave healthy active requests intact.
  A tap on an actual transfer pauses it; only a waiting background member offers promotion.
- Adding a partially completed collection could set completed entries to the reserved waiting
  stop reason. The service now leaves completed entries intact and requests artwork repair.
  Explicit retries of failed jobs clear only their own download client failure history.
- Download bot checks previously retired the profile before a 60-second cooldown. The next
  attempt therefore changed client identity after every cooldown (visible in the supplied log).
  A challenge now keeps the same client identity. Existing cooldown and pacing remain. Signed
  CDN 403/410 gets one fresh URL before the client is retired, with the existing bounded recovery
  budget. Safe idempotent transport reconnection is enabled; no DRM/restriction bypass is added.
- Downloaded artwork is persisted atomically in private files separately from Coil's evictable
  cache. Covers start alongside the resolved audio transfer, use bounded waiting, and share
  blobs by image identity with per-song references. A common Coil interceptor serves player,
  lists and media-session artwork, including resized Google images. Removing one track keeps
  shared covers until their last owner is removed. Completed old downloads get sequential
  artwork backfill at startup when Android reports validated internet. A failed image never
  deletes or fails valid audio; only safe error types are logged.
- Subscription glyph uses one reversible turn and connected path instead of a bending horizontal
  stroke with a separately fading vertical stem. Label and size timings match. Menu surfaces use
  the player's committed-close/Back/press lifecycle and native nested scrolling; palette and
  existing appearance variants are preserved. Reopening during close uses a generation guard and
  restores the native sheet, so an old close cannot hide the replacement. Settings no longer impose a separate velocity cap
  on the native scrolling used in the player queue.

## Verification

Local Gradle attempt could not download Gradle 9.5.0 (`Network is unreachable`); no local
Android test execution is claimed. Git diff checks passed. The first CI compile caught the
Coil 3.4 `Chain.withRequest(...).proceed()` API mismatch; it was corrected rather than bypassed.
The second CI ran 675 tests: 674 passed and the new artwork class failed JUnit initialization
because one coroutine test returned Boolean instead of Unit; the test signature was corrected.
A subsequent 680-test run passed 679 checks; offline JPEG decoding failed in Robolectric's
native ImageDecoder FileSource with `Only supported on Android`. The integration test now uses
Coil's real BitmapFactory decoder, disables both caches and network access, and requires a DISK
result. Production decoder selection is unchanged; device ImageDecoder remains a manual check.
CI runs compile/tests/lint/APKs.
Regression coverage includes real Media3 immediate manual admission, two-slot limit, independent
cancellation, persisted priorities, offline local artwork after cache removal, shared ownership,
invalid images, image completion ordering, paused next-period relevance, and shared menu gestures.

The supplied log also contains actual CDN read timeouts, connection resets and 403 responses.
Their association with VPN routing is plausible but unproven; no controlled VPN/device comparison
was available. No claim is made that these backend/network failures disappear after this patch.
Physical-device smoothness, battery, CPU and first-sound timing still need manual verification.

The full app-suite workflow compares this follow-up against the exact `f11295e` tested build.
Known baseline failures are kept visible; focused CI and APK builds have separate conclusions.
