# Immersion artwork and timing audit — 11 November 2026

Base: `player-release-11.11.2026`, `76577a9`. Review branch: `fix/immersive-atomic-artwork-20261111`.

## Revision after phone feedback

The flat-background result in `b0a8109` was rejected: the visible photo-to-colour transition had largely disappeared. The new `12574.mp4` also identifies the remaining travelling grey wave as search-result placeholders, not the player's artwork renderer.

- Restore the colour-column/Gaussian-feathering approach from `9c641af`, using seven linear-light area colours sampled from the visible dissolve region. Keep the owned display bitmap and atomic frame preparation.
- A screenshot check showed that quantised modal bins chose almost black in all seven columns. Sample 58–90% of the visible photo with linear-light area averaging so textured colours survive dark foreground detail.
- Begin the photo dissolve around 60% of its height and complete it before the final scanline. Use the same physical-Y colour on the photo overlay and page.
- Retain broad left/right colour into the lower background with progressively wider feathering. Collapse an isolated dark minority quickly on near-white art.
- Replace the shared loading host's travelling shader with one low-amplitude opacity pulse for the entire block. Use `ModulateAlpha` for the separated placeholder shapes, read animation state during drawing, and remove the infinite animation offscreen. Remove the unused moving-shimmer theme/provider.
- Add native Compose pixel checks comparing several rows and columns at multiple animation times, plus clock/inactivity checks. Expand production frame tests to require left/right colour continuation and a gradual photo dissolve.

The table below records the earlier audit; its single-colour floor treatment is superseded by this revision. Physical-device appearance remains subject to review.

## Findings and changes

| Failure | Cause in the previous implementation | Change |
| --- | --- | --- |
| Empty artwork after an immediate open | A 256px sampler, invisible preloader and visible Coil painter had separate readiness and retry state. A decoded sample did not own the final displayed pixels. | One software decode at display quality (960px target), copied into an independently owned bitmap. The displayed image is that bitmap. |
| Background flashing during track changes | Outgoing artwork faded to zero before the next frame entered; page textures were cleared and prepared after frame selection. | Prepare the image, photo overlay and page texture before committing one immutable frame. Hold the previous complete frame until the replacement is ready. No static-artwork opacity clock. |
| Blank reopens or interrupted close | Frame state lived after an early visibility return and could be discarded with the hidden subtree. | Retain frame state before the visibility gate; expensive visual composition still stops offscreen. |
| Stripes beneath the image | Each horizontal position projected its own lower-edge colour down the page. Sparse black detail could become a long dark ray. | Local detail dissolves inside the final 14% of the photo into a coherent lower-crop representative. Continue that colour only 48dp below the photo before reaching the floor. |
| Warm artwork becomes almost black | The renderer ignored its floor input and used `0.05 + 0.165 * channel`. | Preserve source hue and dark colours. Scale only bright floors to a maximum channel of 0.44 for white controls. No synthetic blue/purple tint. |
| Dark minority wins over a textured bright background | A quantised mode can give one uniform dark object more votes than bright pixels distributed across many bins. | Use an actual sampled colour nearest the channel medians. It resists minority borders without inventing a new hue. |
| Edge colours do not match displayed photo | The photo had an extra 1.06x overscan; sampling used configuration geometry rather than the final artwork viewport. | Remove the extra transform and sample the actual viewport crop from the same owned image when preparing the frame. |
| Some valid posters never recover | All multi-candidate images were rejected unless landscape, and the real metadata image was absent from the fallback list. | Reject missing maxres/hq720 placeholder images specifically; retain the original metadata image as the final fallback. High-resolution candidates remain first. |
| Unnecessary decoded artwork and memory retention | Immersion's sampler overlapped the shared artwork prefetch, plus multiple image nodes; its sample cache allowed sixteen entries. | Skip the duplicate Immersion artwork prefetch, remove hidden/visible duplicate painters, and bound the owned display bitmap cache to three entries. Portrait prefetch remains independent. |
| Delayed search focus can arrive after player opens | A delayed search-focus coroutine did not include player focus ownership in its cancellation keys. | Include player ownership in the effect keys and guard focus requests. Existing focus clearing and keyboard dismissal remain. |

## Review boundaries

Reviewed Immersion sampling/cache ownership, visible-frame preparation, photo/page geometry, player asset prefetch, search focus handoff, video ambient handoff, and the neighbouring Light/Cosmo artwork loading path. Playback resolution, network streaming, buffering, gestures, lyric clocks and player layout positions were not modified.

The attached video also shows artist-header scrolling. It is useful for checking navigation and loading, but does not demonstrate the reported fast-open player failure. No claim of reproducing that player race on hardware is made.

## Verification

- `git diff --check` and six Python repository checks pass locally.
- Android unit tests, lint, Universal Debug and optimized DEV compilation run in GitHub Actions. The local environment cannot download the Gradle distribution.
- New Robolectric tests exercise the production frame preparer: narrow black border on white, textured bright majority, orange hue retention, neutral dark floor, actual viewport crop, shared image ownership, late photo dissolve, and opaque matching page/photo join.
- Existing player/search/video lifecycle, geometry and energy regression checks remain in the CI gate. Obsolete assertions requiring removed preload/fade machinery have been replaced.

Physical-device validation remains necessary for immediate playback/open, rapid skips, close/reopen during loading, search/open races, background/foreground, rotation, large font, disabled animations, and 60/90/120Hz. CI pixels do not establish subjective smoothness or measured thermal behaviour.
