# Player opening: firm attachment and a focused app scene

Agreed behaviour: retain the existing growing-window basis, keep rounded corners through the
whole trip, carry them past the viewport at the end, add confident edge attachment without a
rebound, dim/recede the app underneath, and let the player cover stationary app navigation.

## Ownership

- The existing anchored sheet offset remains the only physical timeline. A player-only profile
  uses a finite 600ms opening and 460ms closing; generic sheets retain their existing motion.
- Vertical travel catches the screen smoothly at progress 0.97. Horizontal contour attachment
  and a small outward side bend derive from the same progress. The last interval carries the
  rounded clipping arcs beyond the viewport; the radius itself never collapses to zero.
- The clip changes; artwork, text, controls and the video viewport are not stretched. Native
  device corners supply their normal physical crop. A square viewport completes corner filling
  progressively, without relying on rounded-display APIs or per-frame window-inset queries.
- Full content follows one opacity handoff and becomes opaque at progress 0.72. The sheet surface
  is already opaque when Mini yields at 0.25, so navigation cannot shine through the new player.
- One app-scene parent owns a maximum 2.4% recession and 30% black scrim. The player is a sibling
  above it; navigation stays in the app scene and is covered/uncovered by the player itself.
- Back action routing is registered after NavHost within Scaffold's subcomposition, preserving
  lyrics/queue-first closing. Further Back presses during the closing trip are consumed until
  the player has actually left. Modal input/semantics belong to the overlay from opening intent.

## Energy and interruption

There is no new permanent animation, blur, shader, readback, decoder or sampling timer. Visual
state is read during placement, drawing and graphics-layer updates; media retains stable
measurement. Covered app-scene drawing is skipped at the full anchor. Android duration scale
applies to the finite transition. Backgrounding finishes the requested player anchor without
waiting for hidden display frames. Repeated open requests do not restart the current trip;
opposite requests begin at the current position through the existing anchored mutation owner.

## Checks

The quality workflow renders native corner/window previews and tests contour completion on a
square viewport, navigation occlusion and touch ownership, stable media dimensions, the 600ms
trip with repeat taps, rapid reversal, Back registration order, background completion and idle
drawing. Existing player, media, backdrop and energy checks remain enabled. CI results and APK
provenance are recorded in the draft pull request.

No physical-device frame trace, temperature/battery measurement or 60/90/120Hz visual validation
is claimed. The supplied recording was inspected frame-by-frame before implementation. Review
the DEV build on the user's phone before merging appearance changes.

Implementation references: [Compose graphics modifiers](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers),
[Android rounded-screen behaviour](https://developer.android.com/develop/ui/views/layout/insets/rounded-corners).
