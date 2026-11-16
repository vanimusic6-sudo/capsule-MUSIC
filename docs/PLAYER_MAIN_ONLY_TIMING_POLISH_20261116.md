# Capsule Player — main-based motion polish (2026-11-16)

## Explicit design boundary

The user wants the **exact kind of animation that exists on `main`** — NOT a
mini → full container-zoom, NOT a scale-to-Mini closing transform, NOT a
shared-element artwork flight. Prior PRs #102–104 all violated or overcomplicated
this request. THIS BRANCH STARTS FROM MAIN AND RETAINS ITS ORIGINAL BOTTOMSHEET.

The original behavior is deliberately untouched:
- The actual Mini stays a child of the single physical BottomSheet.
- The whole sheet and Mini rise together and return together.
- Full Player content gradually emerges and is not scaled into the Mini.
- Main's `playerFrameHorizontalScale` and 14dp top corners stay unchanged.
- Existing queue, video, artwork gradients, lyrics and mini gestures keep their code.
- The only animation anchor remains `AnchoredDraggableState`.

## Narrow perceptual changes

| Metric | Original main | Polished |
| --- | --- | --- |
| Mini disappears | 25% | **25% (unchanged)** |
| Full backdrop fully visible | 55% | **48%** |
| Full controls fully visible | 88% | **70%** |
| Tap open spring | low stiffness (~200f) | **216f, critical damping, 0.75px stop** |
| Android Back close spring | low stiffness (~200f) | **236f, critical damping, 0.75px stop** |
| Repeated tap/back intent | may relaunch same spring | **coalesces duplicates** |
| Start dispatch | next coroutine dispatch | **on-call-frame for player only** |
| Other sheet specs | original | **unchanged** |

The spring is intentionally NOT playful/bouncy. The visual hierarchy is:
source Mini yields at 25%, then the original backdrop finishes at 48% and
full controls finish at 70%, with no long trailing ghost at the end. These
are progress fractions of the SAME physical spring, not extra animations.

## What was intentionally not changed

No artwork reparenting, no `PlayerWindow`, no screen clipping mask, no scene
dim, no container-bound interpolation, no separate mini scale, no colors,
no video surface changes, no arbitrary frame cap, no new continuous animation.
The sole new state flag `useMainPlayerMotionPolish` keeps these tweaks local
to the full Player. Queue and generic BottomSheet retain the old spring.

## Energy

The work adds zero idle draw nodes and no continuously-running clock.
By reducing the 0.88 visual tail and ending the spring inside a subpixel
threshold, it should eliminate unnecessary short animation-tail frames,
but actual power/thermal benefit is **not claimed without an on-device trace**.

## Verification

Native Compose tests must confirm Mini physical elevation, same-position
reversal, dock restoration, fade endpoints and duplicate intent coalescing.
Run other player gesture, video, background, lyrics, battery structural tests,
Android Lint, debug TEST APK and R8 DEV APK. On the actual HONOR compare short
recordings at normal and slow playback, including dark/light/Immersion, rapid
Back/open, 60/90/120Hz. Keep DRAFT until the user approves the aesthetic result.

This is a main-based review branch. It intentionally does not claim to contain
feature work in the later experimental branches; those should NOT be carried
into the review until requested and checked for compatibility.
