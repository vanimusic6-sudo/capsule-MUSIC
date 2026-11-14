# Immersion background and motion audit — 2026-11-13

Scope: cover composition, track handoff, live video colour capture, rendering,
pause/resume caches and visible/hidden work. This revision replaces the narrow
edge-bleed/blur-strip architecture with an image-backed audio composition.
The accepted live-video pipeline is unchanged. This is a source audit plus CI
CPU/render checks, not a battery or thermal measurement on an Android phone.

## Photographic composition reference

Inspected ArchiveTune's actual [ImmersiveBackdrop source](https://github.com/rukamori/ArchiveTune/blob/0c15cc3b468841b72f6b8ddfaa940844830aa957/app/src/main/kotlin/moe/rukamori/archivetune/ui/player/immersive/ImmersivePlayerScreen.kt)
at pinned commit `0c15cc3b468841b72f6b8ddfaa940844830aa957` (GPL-3.0).
It combines the cover and a rotated continuation into a full-page blurred source,
then overlays the sharp cover with a graduated alpha mask and light black scrim.
There is no separate special brightening blend. Attribution is retained in the
new renderer. Capsule adapts this composition without introducing live Haze blur.

Audio preparation now area-samples a 32×24 spatial field from the displayed
centre crop, including the existing uniform landscape overscan. The lower 18%
of the stage, mostly hidden by the mask, is excluded from palette construction.
Small isolated pale marks are blended toward the surrounding image material
using image/neighbourhood statistics; broad white paper and saturated coloured lights are retained.
A label outside the crop or in the excluded bottom cannot affect any field cell.

The field becomes a 32×48 page composition with the original vertical
photographic continuation below the cover. Only the horizontal sampling direction
is corrected, so left/right colours stay on their original sides. The lower-row
profile and finite-reach circle experiments are absent; the original image-backed
shape from a03e68b is restored. A separable Gaussian blur and cubic B-spline reconstruction produce
one 192×304 immutable page texture, including the lower scrim. It retains spatial
colour rather than converging to a separately selected single-colour floor.
Preparation runs once on a worker per cover/layout, with cache capacity 3
(~228 KiB per page). Full-size artwork no longer needs a software bitmap or a
second CPU blur pass. The old four-level progressive strip is removed.

The sharp photo stage grows from 55% to 60% of the viewport (200–560dp clamp),
below ArchiveTune's 64%. It uses uniform `ContentScale.Crop`, never independent
axis stretching. Controls retain their previous 55% anchor (200–520dp clamp),
so the taller photo does not push the controls down or change saved editor offsets.

| Path | Finding | Change / retained bound |
| --- | --- | --- |
| Cover composition | A narrow blur strip and an independently selected edge colour produced a visible boundary and amplified tiny labels | Replace both runtime paths with the visible-image-backed page described above. One static prepared texture, no live blur or idle clock. The sharp stage uses the same mask/scrim composition for every cover. |
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

The graphics suite emits native renderer PNGs for detailed photo joins,
photographic colour below the stage, round-feature geometry under the taller crop,
near-zero slider, directional video field, uniform field and alternating-stripe
input. New bitmap checks compare complete results with/without a hidden bottom
label and out-of-crop marks; check the influence of small visible white labels;
and preserve white-paper metadata contrast and spatial colour placement.
Tests also cover frame-flash damping, sustained video scene following, colour
region position, interruption, track timing and foreground restoration.

`energy-preparation.json` reports informational Robolectric-host thread CPU time
for video filtering/texture construction and one static photographic backdrop,
plus cached backdrop bytes. These exclude Android GPU readback, decoding,
display power and phone thermals. They cannot be converted to watts or battery
percentages. No timing assertion depends on a particular CI machine.

Phone follow-up: compare audio idle / video playing / video paused / app hidden
at the same brightness, volume, video quality and refresh mode. Capture a system
trace and battery/thermal readings over sustained runs. Verify rapid skips,
A/V reversal, resume, rotation, 60/90/120Hz and disabled animations. These have
not been claimed as device-tested.
