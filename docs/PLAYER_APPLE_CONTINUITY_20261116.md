# Capsule Player — Continuous container motion and GPU budget (16 Nov 2026)

## Design intent and evidence

The real HONOR Android 17 recording **12890.mp4** showed a huge bright Immersion cover
appearing as a separate page, the Mini not physically reading as its source, and large
translucent/ghosted surfaces. Multiple previous fixes changed stiffness and alpha windows
without defining ONE geometry. The rejected variants remain in prior review branches.

Read and used:

- [Apple Human Interface Guidelines — Motion](https://developer.apple.com/design/human-interface-guidelines/motion/)
  - Purposeful, precise motion; same spatial cause and effect; user can cancel.
- [WWDC23 — Animate with springs](https://developer.apple.com/videos/play/wwdc2023/10158/)
  - Continue existing velocity on interruption; never snap to a new scripted trajectory.
- [WWDC24 — Enhance your UI animations and transitions](https://developer.apple.com/videos/play/wwdc2024/10145/)
  - Zoom from an actual source view for perceptual continuity.
- [MaterialContainerTransform](https://developer.android.com/reference/com/google/android/material/transition/MaterialContainerTransform)
  and [Compose shared bounds](https://developer.android.com/develop/ui/compose/animation/shared-elements)
  - One transforming mask, source and destination visible within it with an explicit handoff.
- [Compose quick guide — animation performance](https://developer.android.com/develop/ui/compose/animation/quick-guide)
  and [Compose performance](https://developer.android.com/develop/ui/compose/performance)
  - Prefer graphics/draw phase over recomposition and repeated layout changes.

## Physical invariant

A single anchored spring drives `state.visualProgress`, with no animation clock for the
Mini or for the destination frame. The **visible rounded rectangle** is the interpolation from
the real 12dp-inset Mini card (actual top, actual MiniPlayerHeight) to the full viewport.
Its radius is exactly 14dp at Mini and 0 at fullscreen.

No source-approximate 24% full UI, no content width stretching, no fake centered preview,
no separate screenshot image, and no independently animated fading FULL-SCREEN layer.
The container material is always opaque (alpha 1) from the first pixel of its growth.
The real source Mini stays in place and disappears with a 6dp soft lift.

Incoming actual player content is at **100% natural size**, moving vertically with the
same frame's top edge. Its own opacity is a subordinate visual property (30%–72% of
the ONE geometry progress). The real album field slowly reveals inside the same
opaque material (40%–79%). In Immersion this prevents a white full-photo screen from
appearing over Home before the card has space to hold it.

On reversing direction, every pixel is the same pure function of the same progress:
no duplicated clocks, tween restart or z-index swap. Full player input is gated until
90% progress and compact input is gated by the underlying state. Screens behind cannot
receive taps during the transform.

## Energy / thermal changes

1. One full-screen container `graphicsLayer` clips only DURING the finite transform.
   At >=99.9% progress its shape becomes RectangleShape and clip=false, avoiding
   a permanent full-screen rounded offscreen rendering target.
2. Source Mini `drawWithContent` and its `LocalCapsuleBackgroundMotionEnabled`
   gate both end as soon as its alpha reaches zero; an invisible Mini does not keep
   drawing or animating its decorative background.
3. Full-page draw can sleep when Activity is stopped; `motionEnabled=onScreen`
   is passed from the player host and used for page/foreground/source draw gates.
4. The full player's heavy artwork/video ambient backdrop is not even composed
   until progress >30%, then its opacity follows the one frame's timeline.
   The background clock starts only when actual foreground content begins showing.
5. The underlying Home scene already skips drawing after the opaque full player
   fully covers it; keep that invariant. There is no infinite transition or
   frame-rate cap added to the launch/close animation.
6. No media decoder, player service, download, cache or network path changes.

**Thermal claim boundary:** This reduces avoidable rendering work. It is NOT proof of
a measured battery or temperature improvement. Capture Perfetto frame traces and
power stats on the target HONOR at 60/90/120Hz to quantify results.

## Validation protocol

Check real HONOR recordings for opening and closing (normal, rapid repeated,
open→Back→open reversal), both Light and Immersion, dark/bright cover,
audio/video-first-frame, landscape and system motion scale disabled, hit targets,
video surface visibility, large fonts and app STOP/START. Native Robolectric
tests cover pure geometry, alpha contracts, artwork aspect, rerouting and
visibility, but cannot replace manual perception/physical GPU testing.
Keep as draft until approval.
