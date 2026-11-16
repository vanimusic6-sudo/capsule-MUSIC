# Capsule main player — seamless polish (16 November 2026)

## Direction: preserve the accepted main animation

Base: `fix/player-main-timing-polish-20261116` (the latest accepted main-derived
player animation, PR #105). Preserve the original anchored `BottomSheet`, its
physical Mini lift and return, its full-size player UI, 25% compact handoff,
background completion at 48%, content completion at 70%, and open/close springs.
Do **not** introduce container transformations, fullscreen snapshots, mini-cover
reparenting or screen morphing (rejected PR #102–104).

## Verified references

- [ArchiveTune BottomSheet.kt](https://github.com/rukamori/ArchiveTune/blob/main/app/src/main/kotlin/moe/rukamori/archivetune/ui/component/BottomSheet.kt)
  uses top corners of **16dp while not expanded**, 0dp after fully expanded.
  Capsule retains its own 14dp radius while moving and eases it to 0 only
  over the final 12% of travel, avoiding an abrupt last-frame corner pop.
- [Apple WWDC23: Animate with springs](https://developer.apple.com/videos/play/wwdc2023/10158/)
  emphasizes continuous position/velocity, including when the direction reverses.
  Capsule uses the same critically damped anchor for the sheet, never a second
  conflicting spring for Mini size.
- [Android Compose: Animation performance](https://developer.android.com/develop/ui/compose/animation/quick-guide)
  recommends `graphicsLayer` for alpha/scale and `offset { }` for translation:
  all new per-frame reads are in these phases.

## Timing and appearance

| Property | Implementation | Why |
| --- | --- | --- |
| Sheet motion | unchanged original anchored spring | keep the accepted main look |
| Mini title/artwork handoff | unchanged 25% | no double player |
| Player artwork top contour | hold 14dp through progress 0..0.88; finish to 0 at 1 | avoid the slow loss of roundness |
| Mini weight/squash | X scale 1→0.952→1, Y 1→0.973→1 over progress 0..0.25 | reversible tactile pressure without overshoot |
| Nav starts | tapping the Mini's **background** begins nav exit on press down | lead player click without delaying playback |
| Nav opening exit | ~rapid 1500f critical spring | nav almost gone before large player content appears |
| Nav closing return | only when sheet progress drops below 0.16, then 900f critical spring | avoid nav overlapping player on early close |
| Nav silhouette | X 1→0.93, Y 1→0.81, bottom transform origin | bar tucks under viewport rather than remaining full size during downward slide |
| Route nav hiding | existing `CapsuleMotion.either` combination preserved | no double offscreen displacement |

Critical visual constraint: **do not reparent, independently fade, change colors
or duplicate the full-player artwork**. Mini squash is applied to the *existing*
Mini graphic layer, only while its content fades; no second drawable.

## Energy and perf budget

- The sheet retains its one anchored animation and draw/placement modifier path.
- Mini compression is a **pure function** of the same progress, not a new
  `Animatable` or another `LaunchedEffect`.
- Nav has one tiny, bounded Float spring (rather than progressing for the
  entire sheet travel). Press collection emits only press/release events.
- GPU applies nav X/Y scale and translation to one small bar. No blur, no
  full-screen alpha, no extra image decoder, no continuous wallpaper redraw.
- Effects have exact identity at rest; no frame-rate reduction.
- Power/temperature benefits must not be claimed without device measurements.

## QA

Test stable top radius, generic sheet unchanged, Mini symmetry/exact 0.952/0.973
compression, nav early press / late close return, bounded scales and hostile inputs.
Run Robolectric player gesture, UI background/video and energy regressions,
Android Lint, TEST APK and R8 DEV APK. Device visual review on HONOR for both
Light and Immersion, animations interrupted by Back/reopen, no click-through,
audio/video switching and high refresh rates. Keep review as draft until approved.
