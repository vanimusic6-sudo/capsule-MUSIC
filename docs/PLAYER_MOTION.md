# Capsule player motion — opaque sheet

## Design decision

The mini-to-full player transition is a spatial navigation transition, not an artwork transition. The live expanded page enters as one opaque, vertically translated surface. The dock, Home and navigation retain their actual geometry and are covered by that surface. The reverse path uses exactly the same geometry. Album art, photographic immersion, video and controls are not separately translated, scaled or faded by the outer transition.

This deliberately replaces both the shared-artwork/container morph and the full-screen crossfade. They caused overlapping art, duplicated optical handoffs, translucent glass capture shifts, and a visible synthetic palette on the closing tail. We do not simulate physical mass by adding secondary animation clocks.

## References reviewed

- Android Developers: [Animations in Compose](https://developer.android.com/develop/ui/compose/animation/quick-guide) and [Customize animations](https://developer.android.com/develop/ui/compose/animation/customize). Defer frequent position reads to the layer phase; springs carry velocity on interruption.
- Android Developers: [Material motion codelab](https://developer.android.com/codelabs/material-motion-android). Choose a transition pattern that communicates the relationship between destinations; do not combine unrelated effects.
- [ArchiveTune BottomSheet](https://github.com/rukamori/ArchiveTune/blob/main/app/src/main/kotlin/moe/rukamori/archivetune/ui/component/BottomSheet.kt): native sheet offset from anchor position; expanded and compact content have their own opacity ramps. Capsule uses the simpler opaque page rather than those ramps.
- [Metrolist BottomSheet](https://github.com/MetrolistGroup/Metrolist/blob/main/shared/src/commonMain/kotlin/com/metrolist/shared/ui/components/BottomSheet.kt): layer translation from anchor position, theme motion scheme, and explicit avoidance of duplicated clipping. Capsule keeps one physical layer and does not copy the morph renderer.
- Android Developers: [Macrobenchmark frame metrics](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics) and [Compose performance](https://developer.android.com/codelabs/jetpack-compose-performance).

Both referenced client source files carry GPL-3.0 notices. This implementation is original Capsule code; no external source snippets or dependencies were incorporated.

## Active contract

- `BottomSheetState` owns intent, position, velocity, interruption, and lifetime. Open and close use critically damped springs (420 / 480 stiffness).
- `PlayerWindow` uses one vertical layer translation. At compact rest its page is outside the viewport. At expanded rest it is at the exact native layout position. There is no outer opacity or scaling animation.
- The 24 dp top contour resolves to zero only near fullscreen. It clips the page once; no second artwork mask.
- The mini and navigation are underneath the page. They do not move for player transitions, apart from the independent mini dismiss gesture and route navigation behavior.
- Home does not scale or dim. No captured glass pixels are moved by the transition.
- Decorative animation clocks can sleep during the finite transition. Keep playback, nested menus, lyrics and queue ownership independent of this outer motion.
- The last 0.2% of normalized travel is outside the viewport, with drawing and touch ownership released together. This is not a translucent tail.

## Acceptance on device

Record at least ten opening/closing cycles in each supported full-player design with the Theme, Glow and Glass mini backgrounds, then exercise the other compact background styles. Include a cold image cache, warmed image cache, a track change, video, lyrics, immediate Back, rapid reversal, a size/orientation change, and a disabled system animation scale.

Check frame-by-frame that the page has one edge, no separate moving photo, no exposed synthetic plate, no displaced glass sample, no clipped controls at rest, no taps reaching Home during transition, and no inaccessible dock after close. Compare the actual screenshots at both endpoints.

Frame timing is a separate acceptance criterion, not an inference from the spring equations. Use a physical target device and a release-like build. Android's FrameTimingMetric reports frame timing and overruns; positive frame overruns indicate missed deadlines. Measure at the device's actual refresh rate (approximately 16.7 ms at 60 Hz or 8.3 ms at 120 Hz). Use Perfetto or Android Studio system trace to locate expensive first-frame composition, image processing, layout or RenderThread work. The following manual diagnostic also works for a development APK:

```sh
adb shell dumpsys gfxinfo com.vanimusic.capsule.dev reset
# Open/close the player repeatedly on the device.
adb shell dumpsys gfxinfo com.vanimusic.capsule.dev framestats
```

Do not treat a mathematical geometry test, successful compilation, or emulator screenshot as proof of a visually smooth transition on the target phone.
