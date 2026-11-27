# Capsule player motion

## Design

The actual compact player rises together with the rounded leading edge of the full player. The expanded page keeps its native size and is clipped to the growing contour. Both real surfaces crossfade; there is no separate moving artwork, screenshot, synthetic color plate or Home scaling. Navigation retracts early and returns on the reverse trajectory.

## Active timing contract

- BottomSheetState owns position, intent and inherited velocity. Critically damped springs: opening stiffness 300, closing 250.
- PlayerWindow, mini and navigation sample the same normalized position. The dock approach integrates a smooth speed ramp rather than multiplying position by another easing. This keeps the spatial derivative continuous (maximum approximately 1.0583).
- Navigation tucks over progress 0.025–0.22; destination opacity rises over 0.075–0.52; compact opacity fades over 0.39–0.69. Closing reverses these phases.
- The tiny closing-only landing accent is continuous in the actual velocity and begins only after the geometry reaches the compact anchor: mini at most +3 dp and navigation at most -2 dp. It vanishes at rest and cannot dismiss playback.
- Full page geometry starts at the real mini's horizontal inset and rounded contour; native photo and controls are not scaled independently.
- No new dependencies or external source excerpts.

## References

- [Compose animations](https://developer.android.com/develop/ui/compose/animation/quick-guide)
- [Customize Compose animations](https://developer.android.com/develop/ui/compose/animation/customize)
- [Material Motion](https://developer.android.com/codelabs/material-motion-android)
- [ArchiveTune sheet](https://github.com/rukamori/ArchiveTune/blob/main/app/src/main/kotlin/moe/rukamori/archivetune/ui/component/BottomSheet.kt)
- [Metrolist sheet](https://github.com/MetrolistGroup/Metrolist/blob/main/shared/src/commonMain/kotlin/com/metrolist/shared/ui/components/BottomSheet.kt)

The two referenced clients use GPL-3.0; their source was reviewed, not copied.

## Acceptance

Check opening, closing, reversal, cold artwork, track change, all player designs, glass and other mini materials, video, lyrics, orientation, system animation scale and touch ownership. Inspect native endpoint screenshots and intermediate frames. Use [Macrobenchmark FrameTimingMetric](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics) or Perfetto on the physical device for missed deadlines; a mathematical timing test alone cannot demonstrate frame pacing. A simple diagnostic is `adb shell dumpsys gfxinfo com.vanimusic.capsule.dev framestats` after repeated transitions.
