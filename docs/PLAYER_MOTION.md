# Capsule player motion

## Design

The actual compact player rises together with the rounded leading edge of the full player. The expanded page keeps its native size and is clipped to the growing contour. Both real surfaces crossfade; there is no separate moving artwork, screenshot, synthetic color plate or Home scaling; a separate lightweight scrim and thin lift shadow establish depth. Navigation retracts early and returns on the reverse trajectory.

## Active timing contract

- BottomSheetState owns position, intent and inherited velocity. Critically damped springs: opening stiffness 145, closing 200.
- PlayerWindow, mini and navigation sample the same normalized position. The dock approach integrates a smooth speed ramp rather than multiplying position by another easing. This keeps the spatial derivative continuous (maximum approximately 1.0583).
- The expanding lower edge closes the real gap to navigation over progress 0–0.012 while the destination is optically hidden; navigation and the edge then travel together over 0.012–0.38. The broader pressure curve reduces the peak speed while the softer spring also reduces the main frame speed. This uses the actual app navigation height, with no fictitious contact on rail layouts. Destination opacity rises over 0.07–0.59; compact opacity fades over 0.39–0.74. All three use C2 quintic phases. The mini can lead the expanding edge by at most 5 dp with a smooth, reversible bell-shaped offset. Closing reverses these phases.
- The tiny closing-only landing accent is continuous in the actual velocity and builds gradually during existing braking (progress 0.085–0.001), peaks near the compact anchor and relaxes without a separate shove: mini at most +1.75 dp and navigation stretches upward by at most 1.25 dp (3.5% height cap) about the fixed bottom pivot; no negative navigation translation. It vanishes at rest and cannot dismiss playback.
- Full page geometry is viewport-wide from the first frame, including on close and reversal. The real mini retains its dock inset and crossfade; only the destination's vertical bounds and rounded contour animate. Native photo and controls keep their original size.
- Retain the compact content (including decoded thumbnail) while fullscreen is visible, without rendering it. Keep background clocks paused, stop marquee once covered, and resume live position updates before the mini is visible. This trades a small amount of retained UI state and event-driven subscriptions for avoiding cold composition on close.
- Home draw visibility is a derived Boolean; do not invalidate its drawing every frame. Avoid creating a custom contour when the destination is invisible or fully expanded. The hidden mini is placed outside the viewport.
- Skip drawing fully displaced navigation and disable its tab actions while the player owns touches. Pause visible timeline polling during the finite transition and refresh immediately on settling; the playback clock is unaffected.
- The home scrim uses a separate solid render node (up to 19% opacity), and the rising edge has a cached 14 dp gradient shadow (up to 24%). The lift shadow skips drawing outside its visible phase. The destination computes only its radius (no unused bleed contour), uses value-equal shapes and a rectangular outline when radius is zero. Native content has a stable graphics layer, independent of the outer animated outline. This adds small render-node overhead rather than a live blur.
- Prepare the destination on press, defer video ambient sampling until the sheet settles, and repeat keyboard focus clearing only if Search had pending input. Playback and video rendering remain live.
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
