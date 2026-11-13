# Immersion background and motion audit — 2026-11-13

Scope: cover continuation, cover blur, track handoff, live video colour capture,
rendering, pause/resume caches and visible/hidden work. Compared with c485859. Follow-up tuning lowers only the cover blur and shortens
the track dissolve; the video-light implementation accepted at 818e0af is unchanged.
This is a source audit plus CI CPU/render checks. It is not a battery or thermal
measurement on an Android phone.

| Path | Finding | Change / retained bound |
| --- | --- | --- |
| Cover blur | Started at 54% and filled a full-cover transparent image; obscured real art too early | Blur begins at 84%, only the bottom 16% is cached/drawn. Full strength is reached by 88.8% and coverage by 87.5%, before the existing photo mask hides the effect. Same original photo feather and continuation geometry. Three progressively blurred levels instead of four; reduced source-strip work. One preparation per cover/crop on a worker, cache capacity 3. |
| Cover gradient | Source exposure recalculated for every 2D kernel tap | Prepare source colours once. Evaluate the same finite triangular kernels in separable vertical/horizontal passes. Original palette, floor, fade height and image resolution preserved. One cached render per cover/layout. |
| Track change | Two independent scene scales distorted the image; fast-out easing compressed visible movement; opacity reads invalidated the parent | Remove both scales. 380ms responsive dissolve with a short eased finish. Graphics-layer opacity; equal metadata colours use equality-checked derived state. Rapid skips coalesce the pending visual target after the active finite dissolve, rather than snapping a half-visible photo to opaque or stacking more layers. Playback/input remain immediate. |
| Static audio | Full-page offscreen surface forced even at opacity 1 | Auto group compositing: retain grouped opacity while fading, avoid forcing the extra outer surface when opaque. Photo alpha mask remains isolated for correct blending. No new idle frame clock. |
| Audio underneath video | Entire cover/mask/continuation still drawn under opaque video | Omit the audio draw tree once video reaches full opacity. Retain prepared assets for immediate reverse handoff. |
| Video motion | Hue-bin centroid guesses warped colours up to 30% of the source; bins changed discontinuously; 3 GPU inputs rebuilt | Remove guessed optical flow and velocity texture. Spatially filter actual frame regions in both axes, with 220ms frame-driven temporal response. Reuse kernels/scratch; 2 tiny immutable colour inputs, continuous display-clock interpolation. No radial lamps or autonomous wallpaper time. |
| GPU bindings | SideEffect recreated bitmap shaders on unrelated Player recompositions | Bind only when prepared textures/floor change, in a keyed effect. Keep existing bounded 360px-wide GPU layer and visible animation cadence. |
| Video extraction | Main-thread readback is a real cost even though the buffer is tiny | Retain one reused 48×28 buffer and the existing at-most-10/s decoder-signal cap. Extract/filter off main. Publish only significant filtered changes. This readback has NOT been measured on the user's GPU and remains a device-profiling item. |
| Pause/hidden | Must not leave a wallpaper timer or attached decoder listener | Listener ownership captured by the effect; detach on pause/exit. Paused retained frame sampled once, recovery bounded to 4 attempts. No sampling when disabled, collapsed, lyrics-only or backgrounded. No second decoder. |
| Older Android | CPU fallback would blur the already filtered field again | Mark already-diffused live inputs; skip its duplicate blur. CPU raster fallback still costs more than the Android 13+ GPU path and needs device profiling. |
| Resume persistence | Frequent frames could imply frequent writes | Existing last-three memory cache and 16-second disk-save limit retained. Old raw colour snapshots migrated through the new spatial filter. |
| Progress / other clocks | Progress could accidentally drive background work | Existing visible-only 300ms expanded / 1000ms compact readout retained; stops on pause/hidden/lyrics. Animated audio wallpaper clock already stops in video; no duplicated Immersion video backdrop owner. |

## Verification

The graphics suite emits native renderer PNGs for the detailed photo join,
visible final-scene detail suppression at 88% (before the photo fades away),
near-zero slider, directional video field, uniform field and alternating-stripe
input. Tests also cover frame-flash damping, sustained scene following, colour
region position, interruption from the current visible field, contrast and
responsive-start, short-finish track timing.

`energy-preparation.json` reports informational Robolectric-host thread CPU time
for video filtering/texture construction, one cover blur and one cover-gradient
preparation, plus cached strip bytes. These numbers exclude Android GPU readback,
decoding, display power and phone thermals. They cannot be converted to watts or
battery percentages. No timing assertion depends on a particular CI machine.

Phone follow-up: compare audio idle / video playing / video paused / app hidden
at the same brightness, volume, video quality and refresh mode. Capture a system
trace and battery/thermal readings over sustained runs. Verify rapid skips,
A/V reversal, resume, rotation, 60/90/120Hz and disabled animations. These have
not been claimed as device-tested.
