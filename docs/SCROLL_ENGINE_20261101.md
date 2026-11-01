# Capsule scroll system — unified refactor (2026-11-01)

## Scope and invariant

Ordinary vertical feeds, list/grid libraries, music collections, recommendations,
filters, settings and action-sheet lists now share **one** native Compose scroll
contract: `rememberCapsuleFlingBehavior()`, a deliberate no-rebound boundary
(`overscrollEffect=null`), platform drag recognition, and native per-axis fling
ownership. Do not add a second velocity integrator or duplicate nested-scroll
connection. `LazyListState` and `LazyGridState` remain the source of truth.

Explicit **page** layouts and artist/player gestures retain dedicated gesture
logic. A pager must snap to a page; a scrolling list or music shelf must not snap
to an arbitrary item. Drag-to-reorder remains handle-only.

## Bugs addressed

- **Reorder auto-scroll when finger held near middle**: library/playlist/queue
  previously passed the *entire* player-aware bottom insets as
  `scrollThresholdPadding`. Reorderable adds that to its edge trigger, which can
  turn the middle of a small screen into an auto-pan zone. New activation uses
  at most 56dp occlusion + a 32dp edge threshold and a bounded, single
  `rememberScroller` velocity of 1.8% viewport per 100ms, capped at 28dp.
  Drag handles remain required.
- **Horizontal shelf unavailable or interrupted while Home is still flinging**:
  `userScrollEnabled` was tied to its parent's `isScrollInProgress`. Remove
  that gate. Native Compose direction locking owns nested axes.
- **Competing shelf physics**: ordinary horizontal lists and grids now use the
  same free-scroll fling; legacy snap-fling providers on Explore and Forgotten
  Favorites removed. True 1-page-at-a-time carousels remain pagers.
- **Forced scroll-to-top while a finger is down**: library, Home, Explore, and
  settings navigation consume the one-time event before suspending and skip
  the programmatic move when the user is scrolling or flinging.
- **Queue yanked back to now-playing on every track change**: initial
  `shouldScrollToCurrent` request is consumed once, never repeatedly after
  the user browses. Opening queue still centers the current track when idle.
- **Race in old draggable scrollbar**: dozens of `launch { scrollToItem() }`
  could queue behind one drag and finish out of order. A single conflated
  channel and `collectLatest` now applies only the latest index. All
  coordinates and indices are bounded; 24dp rail owns pointer input; no
  state mutation inside `derivedStateOf`, no spring animation per pixel.

## What must remain unchanged

- MusicService, downloader, Media3/HONOR system media card, notifications.
- Playback gesture physics and video/lyrics choreography.
- Collection titles/artwork/header animation, colors, typography, fixed
  navigation, bottom/player insets, custom ordering and section content.
- Stable content keys for lazy collections. Prefer `items(key = ...)` to
  position-only keys when loading data dynamically.

## Tests

`CapsuleScrollbarMathTest` asserts bounded 0-item and million-item ranges,
invalid float dimensions, monotonicity through 500 drag samples, edge
clamping and suppression of scroll-to-top during user input.

On-device checks are still necessary (CI cannot synthesize HONOR hardware):
1. Home vertically fling and immediately swipe a horizontal shelf; no
   stutter, parent bounce, or accidental long-distance jump.
2. Library tabs (playlists / albums / artists / songs) slow drag, quick
   flick, keep finger stationary halfway down, release, scroll back.
3. Change list ↔ grid while near bottom, switch filters, then Home/back;
   no unsolicited reset while a finger gesture is active.
4. Enter reorder mode, drag via handle to viewport bottom and top; auto-pan
   must start only near edge, advance gently and stop as soon as released.
5. In a local playlist, drag handles and swipe-to-dismiss should not steal
   normal scroll; queue scroll must not reset each time the next song starts.
6. Settings, search results, lyrics, About, sheets: consistent friction,
   no end recoil or overscroll stripes; last entry remains visible above
   bottom navigation and mini player.
7. Run the same gesture sequence on low battery / 60Hz and 120Hz; monitor
   jank, temperature and battery. No background polling/timers added.

The new native behavior is the baseline. Avoid manual scrollToItem per frame,
unbounded Animatable loops, or expensive layouts in item drawing.
