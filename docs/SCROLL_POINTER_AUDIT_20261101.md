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
