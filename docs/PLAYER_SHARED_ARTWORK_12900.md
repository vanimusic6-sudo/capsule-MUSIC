# Capsule Mini → Immersion artwork: source-matched motion (12900.mp4)

## What the video actually showed

The 21.5s HONOR recording **12900.mp4** includes repeated tap-to-open and Back-to-close,
including rapid consecutive transitions. At roughly **0.60–0.76 seconds**, the player reveals
the enormous full-bleed car photograph abruptly, covering half the Home screen. The Mini
album circle has not grown into that photo; it remains a separate object at the bottom.
During closing (~1.45–1.60s), the giant artwork and Mini likewise exchange visibility
rather than moving towards each other. This cannot be repaired by spring stiffness,
rounding, or changing the full-page opacity.

## New architectural rule

One current-track decoded image must move between the *real* compact album circle and
the actual expanded cover slot, with dimensions obtained from UI coordinates. Everything
else can fade/reveal, but it must not replace the artwork trajectory.

- The real Mini AsyncImage publishes its already-decoded Coil Painter and
  `LayoutCoordinates.boundsInRoot()` to `PlayerArtworkHandoff`.
- The actual Immersion layout publishes its artwork stage size via its measured
  `BoxWithConstraints` (`maxWidth`, `immersiveArtworkHeight(maxHeight)`).
- `PlayerWindow` measures its own root position and translates source bounds into
  root-local space. The shared image traverses those bounds continuously using the
  **same** anchored spring progress; there is no additional `Animatable`, tween or
  coroutine scheduling the image.
- The shared painter is cropped *without distortion* using a draw-time round-rect clip
  and uniform scale. The radius continuously shrinks from the true Mini circle to the
  unrounded full-bleed region. The bridge is active only during the spring and fades
  to the already-rendering high-res full artwork before rest.
- Crucially, the FULL Immersion audio photo/gradient scene is gated by the SAME progress
  and cannot paint a second giant car behind the shared travelling cover. The native
  full-resolution scene becomes visible over progress 0.66–0.92 as the moving preview
  fades away, so the original 12900.mp4 pop is addressed at its source, not merely covered.
- Media identity is checked at publication and immediately invalidates the previous
  painter on track change. Missing image => graceful fallback to existing normal player.
- VIDEO does not use the shared painter: it keeps its existing TextureView ownership and
  transition logic, avoiding decoder/surface recreation.
- This first scoped implementation is **Immersion audio**. Capsule Light target layout
  is configurable and must be measured independently, not guessed with fixed dp.

## Energy and performance

The shared image comes from Mini's existing Coil decode and is drawn via a single
Canvas node only while the animation is running. No extra network fetch, bitmap copy,
new color extraction or continuous repaint clock. Only one existing anchored spring
controls movement. The existing full-screen clip is already disabled at rest.
This is a reduction in accidental complexity, not a measured mAh/°C gain.

## QA

- Pure tests: exact endpoints for measured source/target, monotonic rect expansion,
  crop identity and opacity envelope.
- Existing native Robolectric player motion, photo, gesture and lifecycle tests
  plus Android lint and R8/DEV/test APK CI.
- Required device checks: 90/120Hz recordings on HONOR for bright and dark cover,
  repeated Back/reopen, Mini controls, orientation changes, app background resume,
  track change while expanding, as well as VIDEO. Measure battery only after tracing
  actual device GPU/frame workloads.

Keep PR draft until recording approved.
