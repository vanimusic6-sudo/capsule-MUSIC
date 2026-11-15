# Player surface presentation — 15 November 2026

The current user direction is to replace the Mini-to-full shared-element concept with a restrained
presentation that fits Capsule, for both opening and closing.

## Visible behavior

The complete player rises only 44dp and approaches from 98.5% to its exact final size, pivoting at
the bottom center. Its background, photograph/video, title, controls and lyrics entry stay together
in their real final layout. One opacity follows that same anchored spring and completes within
its first 55% of position progress, before settling, to avoid prolonged app-text ghosting.
Mini retires locally
by 6dp and fades during the early portion; the app underneath remains stationary with a light
16% maximum black scrim. Closing reverses the same geometry and opacity, without a separate exit
concept. Rounded 26dp corners finish beyond the screen edges, avoiding a last-frame square snap.
There is no blur, palette alteration, decorative bounce, staggered controls or per-element flight.

## Ownership and lifetime

PlayerWindow owns the only whole-page graphicsLayer. The existing critically damped 650 opening /
850 closing spring keeps interruption, velocity continuity, repeated-request coalescing and system
motion-duration support. Per-frame progress is read only in layer/draw/input phases, not page
composition or layout. The page remains at final measured dimensions. The shared-element registry,
recorded cover/title lists, cached source bounds, readiness flags and surrogate handoff touch layer
have been removed, including their hooks in Light/Cosmo/Immersion. Native artwork decoding and
video transition behavior remain their existing owners.

The page remains mounted for reversal until it reaches rest. Mini keeps its native controls and
returns to its real dock. Early page taps and the covered app are blocked; native full-player
controls become active near completion. No invisible input layer survives closing. Activity stop
snaps finite UI motion to its current target. Covered decorative clocks/drawing stop at rest.
The service artwork/like isolation and local-sync regression fixes remain unchanged.

## Validation

Focused native UI tests cover the real full-page transform and fixed layout, undistorted portrait
artwork, simultaneous cover/title painting, artwork arriving during motion, exact rest identity,
close cleanup and navigation touch, native Mini controls, early input blocking, reversal/reopening,
repeated Back, lifetime and idle draw/composition. API 37 accessibility stress still exercises lazy
underlay changes and host removal/restoration. Media/like regressions remain in the same CI gate.
Compile, Android lint, Universal Debug/test build and signed optimized DEV build are required.
Exact results and delivered APK provenance are recorded in draft PR 100.

Native CI renders are fixtures, not a recording from HONOR. They cannot establish subjective
appearance, physical 60/90/120Hz smoothness, OEM video composition, heat, battery or TalkBack feel.
Keep this revision on the review branch for phone evaluation.
