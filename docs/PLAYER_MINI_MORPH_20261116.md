# Capsule MUSIC · Mini ↔ Full Player morph (16 November 2026)

## Reference and chosen base

Built on `feature/player-magnetic-opening-20261114` (draft PR #100), not on main.
The old 44dp lift/98.5% page scale looked like an overlaid screen rather than the
player contracting into its docked source.

Design references: Apple's WWDC23 *Animate with springs*
(https://developer.apple.com/videos/play/wwdc2023/10158/), Apple's HIG Motion
(https://developer.apple.com/design/human-interface-guidelines/motion/),
Android's Material Container Transform
(https://developer.android.com/codelabs/material-motion-android), and the Compose
shared-bounds design (https://developer.android.com/develop/ui/compose/animation/shared-elements).
The iOS 26 SwiftUI mini-player sample uses matched zoom navigation
(https://github.com/ahmetbostanciklioglu/AppleMusicBottombarSwiftUI).
These are principles, not copies of native Apple code.

## The actual trajectory

* A **single** BottomSheetState spring governs both directions. 270 open / 290 close
  stiffness, critical damping, no bounce, 3px anchor visibility threshold.
* The physical frame at progress 0 is the mini card's left/right inset,
  screen-space top and actual MiniPlayerHeight, **including its gap above navigation**.
  At progress 1 it is exactly the full viewport. Rect edges interpolate from the
  very same progress used by Back/reopening; rotation/inset changes use current bounds.
* The expanded page remains fully laid out at its final measured size, and is only
  clipped to that interpolated rounded frame. This avoids per-frame remeasurement,
  owner-detachment, video layout churn, and the 44dp pseudo-shared presentation.
* One graphics layer on its **foreground content** scales uniformly from 24% to 100%
  and translates from the mini-artwork side to its native final position.
  Therefore portrait/square artwork and text are never stretched in X versus Y.
  The wallpaper is clipped by the frame but not scaled.
* The actual mini, not a simulated coloured plate, remains in view during handoff.
  It lifts 8dp, approaches by at most 1.2%, and fades over the early half of frame
  progress. The full background and content ease in within that growing silhouette.
* Corners retain their circular clip. At the end the arc bleeds behind the real display
  edges rather than disappearing in a square snap. There is no blur, additional colour
  blend, dual artwork decoder, screenshot, per-element position registry or new clock.

## Interaction and performance

* Only a tap on Mini opens; Android Back closes; compact swipe-down-to-clear retains
  its independent gesture owner. A reversal resumes from the current spring position
  and velocity; repeated requests coalesce.
* Full controls are noninteractive until 92% frame progress; Mini is interactive only
  near its dock. Underlying navigation cannot be tapped through the moving frame.
* Layers stay attached during the finite transition, then the expanded page and
  hidden Mini are retired as in the previous revision. A hidden activity finishes
  the current target. Reduce-motion system duration scale remains supported by Compose.
* Procedural backgrounds, real artwork/video, playback, lyrics, energy gating,
  landscape fallback and service metadata remain untouched.

## Verification requirements

Run `PlayerOpeningMotionTest`, `PlayerWindowAccessibilityTest`, related
transition/gesture tests, lint, and a signed development build on the PR.
Verify **by physical recording** on HONOR Android 17 at normal refresh rate:
open/close at 0.25x video speed, immediate reversal, mid-animation Back,
different album aspect ratios, video, Light and Immersion, navigation overlap,
landscape, and animation-duration-scale 0. A code-level/CI pass does not verify
the final visual feel or OEM video composition.
