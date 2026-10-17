# Capsule Music — global motion / energy audit (increment 1)

**Baseline:** `fix/immersion-restore-original-white-soft-only-20261016` at
`4508c8f2b54a92b4cde4b71bef597a65f35a11f8`.
**Working branch:** `audit/global-motion-physics-energy-20261017`.

> Status: **targeted, code-grounded first pass**, not a certified whole-app audit and not
> a measured battery improvement. Do not present this branch as release-approved
> without the hardware checks below.

## Reference and motion principles

The supplied 34.17-second, approximately 59.92-fps HONOR screen recording includes
launcher/application entry and photo-gallery navigation. Its useful reference is
continuity from touch through settle, restrained transformations, and a coherent
sense of moving surfaces. It does **not** provide a benchmark for Capsule frame
times or a directly portable spring constant. We should not copy operating-system
animations without considering the app's layout, player and interaction constraints.

Keep pointer tracking direct, hand off velocities when the gesture releases, avoid
secondary independent clocks that fight the gesture, and preserve visual identity
at the rest anchors. Do not add blur or full-screen compositing as a substitute
for well-designed timing.

## Repository reconnaissance

The baseline's recursive tree contains **209 Kotlin UI production files**
(approximately 3.36 MB). The first pass inspected the motion and navigation
infrastructure as well as relevant settings, player, lyrics, menu, bottom sheet,
home and video-transition call sites; it is **not** a line-by-line inspection of
all 209 files. Motion owners identified include:

- `ui/motion/CapsuleEasing.kt` and `CapsuleMotion.kt`: shared easing, monotonic
  geometry, finite progress; no new ticker required.
- `ui/screens/ScreenTransitions.kt`, `DestinationEntrance.kt`,
  `CapsuleRouteSurface.kt`, `MainTabNavigator.kt`: instant NavHost route changes,
  opaque destination surfaces and a *single* entering-screen motion. Restoring
  two-live-screen NavHost transitions would reintroduce lifecycle and overdraw risks.
- `ui/component/BottomSheet.kt` and `BottomSheetMenu.kt`: anchor-owned player
  motion, native Material 3 menu drag/settle. Do not add an independent dismissal
  or rebuild playback state in the presentation layer.
- `ui/player/MiniPlayerSwipeMotion.kt`,
  `CapsuleLyricsTransitionMotion.kt`, `CapsuleVideoArtworkTransition.kt`:
  separate gesture-driven/draw-driven geometry for the mini-player, lyrics and
  audio/video artwork.
- `ui/component/Lyrics.kt`: time sampling is conditional on visibility, focus,
  playback and the need for word-level synchronization. Its 40/100/240 ms
  sampling rates are deliberate. Replacing them with a perpetual frame ticker
  would be an energy regression.

### Confirmed issues and changes

| Area | Before | Change | Why |
| --- | --- | --- | --- |
| Bottom dock indicator | `animateDpAsState` read as a Compose value in the parent | Read the spring's State inside `Modifier.offset { ... }` during placement | Repositions the pill without forcing full tab-row composition each animation frame; maintains its existing spring, geometry and no-extra-layer rendering |
| Settings mode rows | 14 separate `tween` / `spring` specs per composition, placement damping ratio 0.88 | Share immutable fade/placement specs; use critically damped placement | Removes the visible settle/bounce of upper rows after a mode change, without eliminating row movement or lengthening all interactions |
| BottomSheetPage exit | Outside gesture shield disappeared as soon as `isVisible = false`, while the sheet still had an exit animation | Retain shield using `MutableTransitionState.currentState || targetState` and remove it only when exit finishes | Prevents accidental taps on destinations exposed during a moving sheet; guarantees no hidden shield after settle |
| BottomSheetPage spring | 0.80 enter / 0.84 exit damping | 0.92 enter / 0.96 exit | Less secondary overshoot; leaves a brief physical response without a jelly-like bounce |

No changes were made to sound/video extraction, playback services, queue, media sessions,
screen palettes, layouts, content providers or application preference values.

### Areas intentionally *not* retuned by guesswork

- Player-to-mini-player surface ownership, lyric-mask opacity and video artwork
  clipping. Those have prior regressions from overlapping layers and require
  paired visual tests at both endpoints and during interruption.
- Artwork-heavy route transitions: they deliberately avoid translation and
  scale because that can expose edges of full-bleed artwork.
- `DestinationEntrance.kt` durations: a 460–580 ms animation may be
  overlong in common navigation, but changing values without live A/B video
  and input-to-render measurements is not a defensible improvement.
- Mini-player swipe gesture and lyrics clocks: the inspected `while (true)`
  gesture loops suspend on pointer events and are **not** busy frame loops.
  The lyrics polling loop is conditional. Do not delete these as a reflexive
  “infinite loop optimization.”

## Checks made / checks still owed

Added source-level regression guards for animation ownership and repaired the
existing settings regression assertion. Source checks establish *intent*, not
runtime frame pacing. They must be followed by build, Robolectric and device
verification.

Hardware/performance evidence **not obtained in this audit**: Perfetto FrameTimeline,
JankStats, CPU/GPU utilization, memory/GC profiles, thermal and power readings,
and 60/90/120 Hz on-device recordings. Therefore **no percentage reduction
in battery drain, render time, GPU load or missed frames is claimed**.

### Release acceptance matrix

1. On 60 / 90 / 120 Hz hardware (where available), record tab navigation,
   basic/developer settings switches and rapid toggles, mini/full player
   open/close, lyrics open/close, audio/video switch and every supported
   bottom-sheet dismissal path.
2. Capture `FrameTimeline` and `Choreographer`/JankStats before/after
   under the *same* refresh rate, thermal state and background load. Nominal
   refresh intervals are 16.67 / 11.11 / 8.33 ms respectively, **not**
   measurements from this patch.
3. Stress rapid reversals: tap-tap-tap between tabs; begin sheet dismissal by
   drag then reverse; back-gesture cancel/commit; switch settings modes rapidly;
   open a menu, swipe closed, immediately tap where it stood.
4. Verify no invisible touch blocker remains after exit and no touch passes
   through an animated sheet before its disappearance; include accessibility,
   touch-exploration and Android animator duration scale 0×.
5. Profile the player *while audio and video are actually playing*; verify no
   extra streaming errors, dropped input, GC spikes or continual frame work
   after the final animation settles.
6. Do an A/B review against the original baseline on an actual 60 Hz phone
   **before merging**; revert any motion family that looks more mechanical or
   feels slower despite passing structural tests.

## Follow-up audit backlog

A complete inventory must still traverse the remaining UI composables and
Android resources, inspect active scene clocks, lifecycle subscriptions and
expensive dynamic drawing, then collect reproducible per-flow traces on target
devices. This first pass avoids changing those areas speculatively merely to
claim full coverage.
