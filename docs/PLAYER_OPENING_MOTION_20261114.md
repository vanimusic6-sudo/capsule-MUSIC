# Player opening: one card motion with a short Mini handoff

The player grows from the Mini card's real bounds and covers stationary app navigation. Opening
is 460ms and closing 360ms. A completed tap starts the finite anchored mutation immediately;
repeated requests coalesce, and reversals continue from the current position.

## Presentation

- One progress controls the card's position, height, width and corner radius. A native rounded
  rectangle replaces the bowed cubic Path. The extra late edge-catch acceleration is removed.
- Rounded corners remain through the trip, then leave the viewport in its final interval.
  Square screens fill their remaining corner pixels progressively too.
- The app underneath stays at its original size and position. A light 16% black scrim replaces
  the previous 30% scrim, scaling and secondary rounded clipping layer.
- The real full page mounts at the first moving offset and remains opaque internally. The
  existing outer card layer dissolves only between progress 0.06 and 0.28. Below 0.06 the bright
  photo fragment is completely gone; above 0.28 the window is fully opaque.
- Mini stays opaque UNDER that one card fade until it is covered. This makes the top source
  region a direct Mini-to-player blend, without two independent fades exposing the app between
  them. Mini moves in front again only when the upper card is fully transparent, restoring its
  native control hit-testing. Half-revealed full-page artwork cannot hijack taps as Lyrics actions.
- A bounded deformation follows the actual anchored offset velocity: opening stretches Y by at
  most 1% with opposite X compensation up to 0.35%; closing compresses it. The pivot is the centre
  of the visible card, and deformation vanishes at rest, both anchors and the Mini handoff. No
  target-direction flip, extra clock, looping bounce or layout remeasurement is added.
- The page remains mounted through the final visible closing segment. Reopening near Mini no
  longer disposes and reconstructs the still-visible page midway through the reversal.
- Generic sheets retain their original opacity handoff and motion. Artwork, video and control
  measured layouts and decode sizes stay unchanged. Only the explicitly requested subtle card
  transform affects visual proportions; palettes and final gradient rendering remain intact.

## Work and loading

The underlay transform, independent page-opacity layers and complex outline Path stay removed.
The short outer-card dissolve reuses its existing graphics layer and does not run at the full anchor.
Background mounting uses derived visibility booleans, avoiding raw-progress composition of the
background slot. A retained matching Immersion page texture is reused even after shared-LRU
eviction; it is not rendered again merely because the shared cache was evicted. During genuine
image preparation, Immersion uses its already sampled cover colour instead of a generic grey
base. Decoding still uses the existing full-resolution requests and bounded caches.
For a same-cover crop/inset refresh, the previous complete photo/background pair stays visible
until the replacement texture is ready, then retires immediately without another opacity trip.

No permanent clock, hidden full-player composition, additional decoder, sampling timer or blur
is introduced. Visible motion uses the display frame clock and Android duration scale. Covered
app drawing stops; backgrounding finishes the requested anchor without hidden animation frames.

## Verification

Native tests cover absence of the bright photo fragment near Mini, exact Mini-under-card pixel
blending without a transparent middle frame, restored Mini tap ownership before settlement,
opening stretch/closing compression with stable measured layout, stationary app edges, rounded
corner completion, source-card bounds, timing, repeated requests, near-Mini reversal without a
remount, Back and background/idle behavior. Existing player, backdrop, playback and energy checks remain enabled.
Results and APK provenance are recorded in the draft PR.

The user's 12795.mp4 recording and clarification were inspected. No physical-device frame trace, 60/90/120Hz jank,
end-to-end tap latency, temperature or battery measurements are claimed. Appearance stays in
review until the DEV build has been checked on the phone.

References: [Compose graphics](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers),
[Android rounded screens](https://developer.android.com/develop/ui/views/layout/insets/rounded-corners).
