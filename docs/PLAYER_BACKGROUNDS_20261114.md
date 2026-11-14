# Light/Cosmo background colour and effect review — 2026-11-14

Scope: ordinary full-player backgrounds, their shared palette/cache and the
inherited Lyrics background. Immersion remains byte-for-byte at d2f3f9c.
The accepted video capture/filter/shader implementation is reused, not changed.

## Corrections

- Area-averaged artwork samples replace isolated point samples for the palette.
  Adjacent hue-family coverage is counted together when ranking textured colours,
  including reds crossing 0/360 degrees; displayed swatches remain actual source
  averages. Tiny isolated chromatic labels cannot become the main accent.
- Remove the forced navy mix in Capsule Star and the repeated grey/white/lavender
  mixes in Nebula. Fog lights retain their actual artwork colours. Neutral dark
  bases do not introduce blue into a neutral cover.
- Move Glow light energy into visible regions around/below the opaque cover and
  reduce duplicate lower darkening. Capsule Glow also retains more visible colour.
  Static effects still reuse cached brushes; no framebuffer blur is added.

## Scene light

The appended SCENE_LIGHT preference is labelled “Scene light” / “Свет сцены”.
It is available to Light/Cosmo and Lyrics when Lyrics follows the player theme.
A cover is sampled with the same native-aspect video colour-field path and
CapsuleVideoLightFilter, then rendered by CapsuleVideoAmbientBackdrop. Both the
modern two-texture shader and older-Android raster projection are shared with
video. A still cover remains still; track changes use the existing finite field
interpolation rather than a wallpaper timer or simulated motion.

One software image decode supplies a three-colour palette and 20×16 immutable
spatial field. Both are published as one scene, so tracks with equal palettes but
different colour positions still update. The scene shares the existing striped
mutex and bounded 32-entry LRU across player/mini/Lyrics/cards. There is no second
image request for the spatial style, no second decoder and no continuous sampling
of static artwork. Texture construction happens only when that style is drawn.

## Verification boundaries

CI checks cache coalescing/eviction, cached resume and equal-palette field changes;
native renderer images check red fog, visible glow below the cover, neutral
artwork across procedural styles, and exact field agreement between artwork and
video. Existing player, video, gesture and Immersion checks are retained.
No physical-device temperature, battery consumption or perceived smoothness
measurement is claimed. Display/animation frame policies and media quality are
unchanged. Visual changes remain on a review branch.
