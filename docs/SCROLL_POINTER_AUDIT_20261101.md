# Capsule scroll / held-finger audit — 2026-11-01

## Evidence and scope

The second device recording, `10423.mp4`, shows a finger continuing to
move across nested carousels on the MZLFF artist page after an initial drag.
The unwanted vertical displacement repeats while the finger remains down.
The earlier native-fling refactor did **not** solve the held-pointer case.

This review covers parent/child LazyColumn–LazyRow gesture ownership, library
pull-to-refresh, row swipe gestures, reorder handles, scroll-to-top requests,
fixed shelf geometry, and the fast scrollbar. Playback/download/Media3 code
is outside scope.

## Concrete problems in code

1. **Feedback loop introduced by the previous scroll fix.** The parent
   LazyListState.interactionSource was observed through
   `collectIsDraggedAsState()`, and `userScrollEnabled` on every nested row
   was recomposed to the inverse of that state. A running gesture could
   consequently *disable its child while that pointer was still down*.
   Changing gesture modifiers in the middle of a touch session can cancel or
   re-arbitrate input. This is fundamentally wrong even if some quick flings
   look normal. Removed on Home, Explore, Artist and Album.
2. **Library Albums changed refresh gesture policy at its zero-offset
   boundary.** It set `pullToRefresh(enabled = ytmSync && canPullRefresh)`
   where `canPullRefresh` was derived from the changing first visible
   item/offset. Every crossing of index/offset zero rebuilt the nested
   recognizer while a drag could be in flight. Use the same stable
   `enabled = ytmSync` throughout the session; Material3 receives remaining
   downward scroll only at the child's top edge.
3. **Song swipe reset raced the next gesture.** `SwipeToSongBox` previously
   launched a separate 300ms snap-back coroutine after each drag and did not
   cancel it on renewed pointer movement. The old animation could mutate the
   same offset while the new finger drag wrote to it, creating jerks or
   unintended threshold activation. The reset job is now owned, cancelled
   on a new horizontal drag sample, and replaced only on drag stop.

## Interaction policy

- Keep the built-in Compose gesture recognizers mounted and stable through
  DOWN → MOVE → HOLD → MOVE → UP/CANCEL.
- A vertical parent and horizontal child decide direction via native touch
  slop and consumption. Do not use dynamic `userScrollEnabled` as an
  inter-gesture lock; do not infer pointer-down from `isScrollInProgress`.
- A parent fling must not disable a child's new horizontal swipe.
- Native `LazyListState` / `LazyGridState` stays the scroll source of truth;
  no per-frame forced item navigation or manual velocity amplification.
- List refresh is an edge-only remaining-scroll action, not an additional
  per-pixel content-position controller.
- Swipe-to-queue offset is written by exactly one owner at a time.

## Other audited paths

- Artist/Home horizontal shelves use a reserved fixed viewport height so
  different metadata lines cannot reflow the entire vertical feed mid-drag.
- List/grid keys, one-shot scroll-to-top events and explicit reorder handles
  were reviewed. Reorder auto-pan is handle-only and bounded near edges.
- `DraggableScrollbar` is a separate fast-scroll rail with absolute thumb
  mapping. Do not wire it over normal content or widen its hit area without
  a thumb-only start check; it is not needed to solve nested touch ownership.

## Regression tests

`ScrollPointerOwnershipTest` exercises:
1. Vertical drag → long hold → sharp sideways/diagonal correction; the
   horizontal shelf stays at its original scroll position.
2. Horizontal drag → long hold → angled correction; the parent stays put.
3. Stationary long press; neither axis scrolls.
4. Source contract: no live parent-drag `userScrollEnabled` toggling or
   offset-dependent refresh recognizer reconfiguration.

On-device confirmation is still required. Test repeated holds on the
MZLFF artist carousel, then album and playlist libraries in list + grid mode,
then song swipe-to-queue and reorder (both enabled/disabled). Watch for
phantom scroll-to-top, horizontal displacement, layout jumps, and false long
press activation. Do not merge to main based on source review alone.

## Follow-up: Foundation recognizer, not just app-level ownership

The fixes above left the underlying Foundation drag node on **1.12.1** while
UI, runtime and animation already used **1.13.0-beta02**. Two upstream fixes
were consequently missing:

- [b/532946466](https://issuetracker.google.com/issues/532946466): catching an
  ongoing fling starts the parent's drag immediately. Its subsequent events
  must be consumed in the Initial pass, before a nested cross-axis child
  observes them in Main. In the old implementation the child can steal the
  gesture after a sideways correction. Fix introduced in Foundation 1.13.0-alpha02.
- [b/524163413](https://issuetracker.google.com/issues/524163413): stationary
  MOVE events must remain consumed by the active drag recognizer. Merely
  advancing test event timestamps does not exercise stationary MOVE handling.
  Fix introduced in Foundation 1.13.0-alpha01.

See [official release notes](https://developer.android.com/jetpack/androidx/releases/compose-foundation)
and [the initial-pass fix](https://android.googlesource.com/platform/frameworks/support/+/9585db7a8d85522efb0820b285870518c5f120be).
Inspection of both published source JARs confirmed that 1.12.1 lacks
`isDraggableInitialPassConsumptionFixEnabled` and
`isDraggableZeroDeltaConsumptionEnabled`, while 1.13.0-beta02 includes both,
enabled by default. Foundation is now aligned with the Compose version already
used by the rest of the app. No replacement pointer interceptor, frame-by-frame
scroll writer, polling loop or custom velocity integrator is added.

### Why the previous tests missed this

`ScrollPointerOwnershipTest` begins on lists at rest and acquires its initial
axis before changing direction. That avoids `startDragImmediately`, the
recognizer branch used when interrupting an active fling. Also,
`advanceEventTime(740)` does not dispatch a stationary MOVE event by itself.
Thus those tests could pass while both upstream defects remained present.

`ScrollFlingCatchRegressionTest` exercises the actual Foundation drag node
shared by lists/grids: immediate parent acquisition followed by a cross-axis
correction in both orientations, and explicit stationary MOVEs during an
active drag. It asserts consumed events and which recognizer receives deltas.
The CI old-engine job must observe assertion failures with Foundation 1.12.1;
compile errors or missing reports are not accepted as reproductions. The
candidate runs the same behavioral tests plus existing scroll/playback tests.
CI also records dependencyInsight to verify which Foundation version actually
resolved, rather than inferring it from the catalog declaration.

This provides a concrete dependency-level explanation and a repeatable
regression check. It does not substitute for replaying the device gestures in
10418.mp4 / 10423.mp4 or confirm the physical phone's final behavior.

### Verification of commit 9182c6df

- [Paired old/new CI run](https://github.com/vanimusic6-sudo/capsule-MUSIC/actions/runs/41266494258):
  dependencyInsight resolved Foundation **1.12.1** in the baseline and
  **1.13.0-beta02** in the candidate. All three new tests failed with assertions
  on the baseline; all three passed on the candidate. No compile or setup
  failures were used as evidence.
- Candidate reports contain **656 app tests + 133 extractor tests = 789 tests**,
  with zero failures, errors or skips. This includes all four previous pointer
  ownership tests and the three new drag-node tests.
- [TEST APK 375](https://github.com/vanimusic6-sudo/capsule-MUSIC/actions/runs/41266494190)
  and [optimized DEV APK 317](https://github.com/vanimusic6-sudo/capsule-MUSIC/actions/runs/41266494220)
  built successfully. DEV APK SHA-256:
  `1916a7b6f45e11f0d077b353863efbe983cd327418014a27e97f8f268ba99138`.
- No physical-device replay has been performed in this environment.
- [Full Universal Debug workflow](https://github.com/vanimusic6-sudo/capsule-MUSIC/actions/runs/41266494230)
  ran Android lint. Lint reports four `NewApi` errors in the pre-existing
  experimental HTTP/3 video factory (`CapsuleVideoHttp3Transport.kt:150-162`,
  S extension SDK 7 checks). That source is byte-identical to parent d258971f
  and was introduced by 7090a4ae; the scroll change does not modify it.
  The workflow skips its later UI suites after the lint failure, so those
  suites are not claimed as passed. The focused 789-test validation above and
  the separately built TEST/DEV APKs succeeded. No lint rule was suppressed.
