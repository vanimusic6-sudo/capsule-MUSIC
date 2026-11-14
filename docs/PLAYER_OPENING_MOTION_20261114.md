# Player opening: one physical card reveal

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
- The real full page mounts at the first visible card offset, stays opaque, and is revealed by
  the card's bounds. Mini yields at that same threshold. There is no independent page-opacity
  reveal, temporary enlarged Mini background or partly visible Mini/full UI overlap.
- The page remains mounted through the final visible closing segment. Reopening near Mini no
  longer disposes and reconstructs the still-visible page midway through the reversal.
- Generic sheets retain their original opacity handoff and motion. Artwork, video and control
  proportions stay unchanged; accepted artwork palettes and final gradient rendering are intact.

## Work and loading

The underlay transform, two full-page opacity layers and complex outline Path are removed.
Background mounting uses derived visibility booleans, avoiding raw-progress composition of the
background slot. A retained matching Immersion page texture is reused even after shared-LRU
eviction; it is not rendered again merely because the shared cache was evicted. During genuine
image preparation, Immersion uses its already sampled cover colour instead of a generic grey
base. Decoding still uses the existing full-resolution requests and bounded caches.

No permanent clock, hidden full-player composition, additional decoder, sampling timer or blur
is introduced. Visible motion uses the display frame clock and Android duration scale. Covered
app drawing stops; backgrounding finishes the requested anchor without hidden animation frames.

## Verification

Native tests cover first-visible artwork pixels instead of an empty/grey transition plate,
stationary app edges, rounded corner completion, source-card bounds, stable media dimensions,
tap responsiveness, timing, repeated requests, near-Mini reversal without a remount, Back and
background/idle behavior. Existing player, backdrop, playback and energy checks remain enabled.
Results and APK provenance are recorded in the draft PR.

The user's 12793.mp4 recording was inspected. No physical-device frame trace, 60/90/120Hz jank,
end-to-end tap latency, temperature or battery measurements are claimed. Appearance stays in
review until the DEV build has been checked on the phone.

References: [Compose graphics](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers),
[Android rounded screens](https://developer.android.com/develop/ui/views/layout/insets/rounded-corners).
