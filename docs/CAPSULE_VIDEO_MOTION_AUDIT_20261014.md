# Capsule VIDEO Motion — engineering audit (14 October 2026)

**Scope:** Capsule Cosmo, Minimal and Immersion artwork ↔ VIDEO window transitions. AUDIO core, URL extraction and network handling are **out of scope**. This branch is layered on top of `polish/video-immersion-jelly-audio-cdn-20261014`.

## What the older animation got wrong

1. **Siloed geometry.** For Cosmo/Minimal, `stagedVideoHeight(p)` finished contracting by ~58% progress, while width didn't start until ~30% and finished at 100%. A single rigid rectangle visibly changed shape in two stages: first flatten, then widen. No material relationship, perceptible corner catch-up.
2. **The “jelly” was nearly invisible.** Immersion used two springs but `stretch = (lead - body) * 0.20 * 4p(1-p)`, clamped to ±1.9%. Typical spring disagreement for the old parameters was much less than 0.2. More importantly, `p = reveal.value.coerceIn(0,1)` before the visual work discarded all endpoint overshoot: the image stopped at the target while the spring kept moving invisibly.
3. **No sticky trailing edge.** The entire contour expanded symmetrically about the center. Even if a scale changed, its edges all moved rigidly together. This feels like a generic cross-fade/resize, not a cohesive soft surface.
4. **Surface lifetime could flutter on overshoot.** `needsSurface || reveal.value > .004` was false when a closing underdamped spring dipped *below zero*, even if it was still running. A native decoder detach/re-attach can blank the frame and destabilize playback.
5. **Expensive alternatives are poor fits.** Animated layout constraints would remeasure an `AndroidView` containing the native VIDEO renderer each frame. A broad blur/metaball `RenderEffect` would add offscreen passes, cost battery and jeopardize texture compositing. A navigation-layer `SharedTransitionLayout` is not directly supported for `AndroidView` interop.

## Reference patterns and choice

- [Android spring specifications](https://developer.android.com/develop/ui/compose/animation/customize): use damping and stiffness, not linear tweens. Spring interruption preserves velocity.
- [Android Animatable](https://developer.android.com/reference/kotlin/androidx/compose/animation/core/Animatable): an interrupted spring continues from the *current value and velocity*, and `isRunning` reveals whether a closing rebound is still active.
- [Compose phase performance](https://developer.android.com/develop/ui/compose/performance/phases): read motion state in `graphicsLayer { }` / drawing phase, not in animated `Modifier.size()` or during composition.
- [Compose graphicsLayer](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers): clip shape, GPU transform, shadow and alpha can be updated without remeasuring the decoder.
- [Material motion container transform](https://github.com/material-components/material-components-android/blob/master/docs/theming/Motion.md): keep a visual identity across cover → container transformations; align mask, content handover, corner and z-order.
- [Compose SharedTransitionScope limitations](https://developer.android.com/develop/ui/compose/animation/shared-elements): direct View/Compose shared-element interoperability is not supported; don't force an AndroidView video into a generic sharedTransition API.

**Chosen architecture:** shared custom GPU contour, *one physical body spring* for all modes and a *separate leading-edge spring only for Immersion*. This captures the principles of premium OS transitions, but is original code; it is **not** proprietary HONOR/MagicOS code or a claim of pixel-perfect parity.

## Implementation

- **No stepped contraction**: width, height, radius and alpha derive from the same body spring; decoder dimensions and the surrounding layout are measured once.
- **Cosmo/Minimal**: 210 stiffness / 0.86 damping, aiming for a concise, non-rubbery handoff. Outline and shadow track the container instead of catching up later.
- **Immersion**: body spring stiffness 105 / damping 0.77. A faster, lighter leading spring (270 / 0.63) pulls the contour ahead. The lag displaces horizontal bounds at most **±4.2%** in the active middle; vertical counterstrain is 55% of that. The top and bottom contour use cubic curves with controlled edge drag, plus a small center-of-mass offset (9 dp × lag). At rest the contour is exactly the original rounded 16:9 rectangle.
- **Endpoint rebound**: the spring's physical overshoot survives the mapping to contour strain. This was *impossible* with `coerceIn(0,1)` on the animated position before every derived visual. The deformation is still clipped to a safe amplitude.
- **Decoder protection**: neither springs nor contour resize/rotate/translate the native video `AndroidView`. Only the **mask** and, subtly, cover art scale. The native decoder is retained until `isRunning` is false and spring offsets have actually settled. The empty audio artwork placeholder does not mount VIDEO.
- **Handover**: the real first frame must be ready before `presentingVideo` goes true. The cover fades only when the silhouette has begun transforming; on mid-flight close, the same function reverses with no separate tween.
- **Performance**: no new ticking coroutines or per-frame state writes in composition. The Immersion-only second spring is not allocated in other styles. The contour path is updated at the graphics phase. Avoid animated blur, huge bitmap masks, layout changes and haptics in every frame.

## Acceptance conditions (automated + real device)

The robot tests must retain a single native VIDEO output in a fixed 16:9 viewport over: initial preparation, first frame, full open, interrupted close/open, settling recoil, and full closed state. The cover must stay fully opaque at the start; neither transition may show a black empty frame in the middle. Checks must hold in Cosmo/Minimal/Immersion on landscape, portrait and square input videos.

**Device-only visual/thermal checks** (cannot be inferred from Robolectric):
1. Open VIDEO → close at 20%, 50%, 80% progress; quickly reverse. No jump in shape, corner radius, alpha, video center or audio timeline.
2. Repeat while VIDEO paused, BUFFERING, unavailable (N/A), and after background→foreground. Missing VIDEO has no phantom window.
3. Watch edge at 120 Hz and 60 Hz. There should be a small, perceptible pull along the top/bottom edges in Immersion, but no scaling of video contents. The settled state must remain sharply 16:9.
4. Compare stable Build frame timing with Android Studio System Trace / JankStats on the actual HONOR device. Target minimal skipped frames; **CI unit tests cannot prove 120-Hz smoothness**.
5. After a long music/video listening session ensure the AUDIO CDN protective quarantines, network behavior, player queue and stream handoffs are identical to the base branch.

## Rejected options / risks

- A full Compose shared element transition across the native Video view: unsupported AndroidView interop and higher risk of detach/black flashes.
- Per-frame `Modifier.size` animation: constant decoder remeasurement.
- Blurred liquid shader/large RenderEffect: unnecessary offscreen compositing, high energy cost and renderer compatibility risk.
- Simply increasing spring bounce: visibly toy-like but does not create adhesive geometry.
- `coerceIn(0,1)` on the actual animated spring value: *erases* the last millimeters of settling, the most important tactile cue.
