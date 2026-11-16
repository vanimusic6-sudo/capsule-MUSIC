# Player animation: return to the main branch's moving BottomSheet (16 November 2026)

## Scope and source

This review branch starts from `feature/player-mini-morph-20261116` to retain the newer
playback, metadata, Immersion and lifecycle fixes. The **active player presentation** is restored
to the original `main` architecture at `e729a1ffab292753581908915714065f7b696780`,
not the experimental independent `PlayerWindow` implementation.

The experiment remains compiled but **is no longer the presentation chosen by MainActivity**;
it is not deleted in this focused motion review because the existing native tests depend on
its symbols. Removing it is a separate cleanup after approval.

## Why the main transition is better

- The `BottomSheet` itself follows a real physical anchor from the dock to the top.
  The real Mini is a child of the sheet, so it **moves with the parent** instead of sitting
  in place while a second layer draws a tiny copy of the entire player.
- The full sheet uses only a subtle horizontal GPU scaling from the actual Mini inset,
  while its rounding continuously resolves to the fullscreen contour.
  There is no independently rescaled/translated page with conflicting coordinates.
- The Mini hands off opacity as the sheet travels; the main background and controls
  are composed within the same sheet. The layout does not warp artwork, navigation or video.
- We preserve the newer modal background touch interception, Back ownership,
  activity-stop cancellation, and velocity-preserving anchored mutation.

## Carefully limited polish

- Opening: critically damped anchored spring, stiffness 232, 1px visibility threshold.
- Closing: critically damped anchored spring, stiffness 250, 1px visibility threshold.
- The same anchor progress still drives geometry and visual ownership. After reviewing
  **12882.mp4** the original handoff was further corrected: the surface becomes fully
  opaque by progress 0.14 (before full artwork appears), Mini fades over 0.03–0.42,
  and full foreground eases in over 0.16–0.62. Real content mounts invisibly at 0.025,
  avoiding a sudden attachment/pop at progress 0.25. The background palette is untouched.
- The sheet continues its original full travel, but Mini counter-travels by 78% of the same
  physical displacement. Thus Mini itself rises a restrained 22% until it fades; it
  does **not** fly all the way up over the app before disappearing. The source's
  z-index changes only after it is entirely invisible.
- No extra bounce, blur, wash, artwork duplication, colour overlay, frame limiter
  or changes to audio/video playback.
- Player-only tuning: menus, queue and other sheets retain their old spring settings.
- All other motion/non-motion code from the review branch is retained.

## Tests

- `PlayerTransitionGestureTest`: new real Compose case checks main-sheet travel,
  mid-motion reversal without a teleport, exact Mini return, stable opacity and idle.
- Existing gesture, accessibility, overlay ownership, visual/thermal and media tests remain
  included in the player UI quality gate; DEV and TEST APK CI jobs run for the branch.

## Verification limits

CI can establish compilation, platform semantics and tested geometry, but cannot replace
a slow-motion real HONOR recording. Assess both Light and Immersion, audio and video,
immediate Back, repeated taps, landscape/insets, system motion-duration scale and
physical refresh rates before merging. Keep the PR as draft.

## Recording-specific diagnosis (12882.mp4)

At ~0.25–0.45s of the supplied 592x1280 recording, Home remained visible through
an oversized partially transparent expanded canvas; source Mini had already started
fading while cover text/photo emerged on a weak surface. This was not spring speed:
`playerHandoffSurfaceAlpha` rose only to 0.25 by the old handoff at progress 0.25,
and the full foreground mounted precisely then. The actual Mini also rode 100% of
the sheet's vertical displacement; the two surfaces visually split apart, with
source z-index switching during the fade.

This revision changes ONLY these player-classic-specific values; generic menus and
queue keep their old behavior. Verify the new playback on a device because static
CI and Robolectric cannot guarantee the perceptual result of video and album paint.
