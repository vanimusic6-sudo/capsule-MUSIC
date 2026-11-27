# Capsule player motion

## Design

The actual compact player rises together with the rounded leading edge of the full player. The expanded page keeps its native size and is clipped to the growing contour. Both real surfaces crossfade; there is no separate moving artwork, screenshot, synthetic color plate or Home scaling. Navigation retracts early and returns on the reverse trajectory.

## Active timing contract

- BottomSheetState owns position, intent and inherited velocity. Critically damped springs: opening stiffness 270, closing 225.
- PlayerWindow, mini and navigation sample the same normalized position. The dock approach integrates a smooth speed ramp rather than multiplying position by another easing. This keeps the spatial derivative continuous (maximum approximately 1.0583).
- Navigation tucks over progress 0.008–0.27; destination opacity rises over 0.08–0.60; compact opacity fades over 0.42–0.76. All three use C2 quintic phases. The mini can lead the expanding edge by at most 7 dp with a smooth, reversible bell-shaped offset. Closing reverses these phases.
- The tiny closing-only landing accent is continuous in the actual velocity and begins only after the geometry reaches the compact anchor: mini at most +2.5 dp and navigation at most -1.75 dp. It vanishes at rest and cannot dismiss playback.
- Full page geometry starts at the real mini's horizontal inset and rounded contour; native photo and controls are not scaled independently.
- Retain the compact content (including decoded thumbnail) while fullscreen is visible, without rendering it. Keep background clocks paused, stop marquee once covered, and resume live position updates before the mini is visible. This trades a small amount of retained UI state and event-driven subscriptions for avoiding cold composition on close.
- Home draw visibility is a derived Boolean; do not invalidate its drawing every frame. Avoid creating a custom contour when the destination is invisible or fully expanded. The hidden mini is placed outside the viewport.
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
