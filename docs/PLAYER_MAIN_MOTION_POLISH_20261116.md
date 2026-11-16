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
- The same progress controls geometry, corner radius and handoff opacity, with the original
  main timings for compact UI and full content. No extra bounce, blur, wash, layer screenshot,
  artificial floating cover, frame limiter, or background changes.
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
