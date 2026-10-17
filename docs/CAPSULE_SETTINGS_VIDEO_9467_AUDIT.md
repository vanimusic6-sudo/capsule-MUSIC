# Capsule Settings: motion and priority drag audit (2026-10-17)

Evidence: user recording `9467.mp4`, 41.19 seconds, 592 × 1280 px, ~45.85 fps. Reviewed as timestamped contact sheets and individual source-resolution frames. This is a real user-visible recording, not a generated animation reference.

## Observed defects

| Time in clip | Seen on screen | Root cause in previous implementation | Repair |
| --- | --- | --- | --- |
| 1–5 s | Settings opens and exits from the right, but the main screen remains fully readable alongside it for an extended portion of the transition. | Full-screen `slideInHorizontally` + `scaleIn` assigned to settings while underlying page is still displayed. Different spring phases leave an awkward final settling. | Preserve one animation owner (NavHost); root entrance/exit is isolated with matched position+scale physics. The underlying home is not separately animated. |
| 7–10 s | Opening `Capsule под себя` creates two simultaneously readable settings headers and rows. Existing page is stationary while incoming page slides partially over it; the new text is squashed by `scaleIn`. | Every settings subpage incorrectly reused partial-slide + scale rather than a correlated transition for both pages. | Replace nested navigation with a synchronized full-width push of outgoing and incoming opaque pages, no scale, alpha, or child relayout. |
| 17–24 s | Toggling mode then entering/leaving Appearance repeats visible sideways crowding; view partially paints two independent content trees in one viewport. | Same duplicated nested navigation transition architecture. | Same single NavHost coordinated push for forward/back settings navigation. |
| 25–40 s | Priority card drag is interrupted by visual gaps, peers sometimes move as jumps, and the active row appears to overtake before neighbours move into place. | Library key-based drag itself updates locally, but DataStore is persisted immediately on finger-up while Reorderable still animates into its final position; the low-stiffness neighbour spec accentuates backlog. | Keep synchronous local list reorder on each crossing; write to DataStore only after 320ms drag settle or on dismissal; raise placement stiffness, preserve stable item heights and ellipsize descriptions. |
| 25–40 s | At the bottom of the priority dialog, reset/done controls are partly off-screen, the note/list occupies the lower space. | Fixed list max height plus header, note, vertical paddings and actions made the dialog larger than the available viewport. | Clamp dialog maximum height to available constraints; list consumes remaining height via Column.weight(fill=false). Buttons/note stay inside modal constraints. |
| 25–40 s | Small ≡ target does not reliably feel like an intentional physical handle. | 36dp text width and glyph-sized height, with drag modifier applied to Text only. | Explicit 48×48dp gesture target; keep key-based ReorderableItem and physical row shadow/scale. |

## Verification / known gaps

- Static and Kotlin unit tests verify routing eligibility, nested push, stable drag state, delayed persistence, bounded dialog and one animation owner.
- Build Debug Universal APK and execute Android lint and all existing CI suites. If something fails, inspect the actual task logs and repair it before providing the branch for device trial.
- **Still requires device validation:** HOLD then drag 1st → 4th → 2nd, release; reverse; repeat quickly while still settling; tap Done while row moving; Back/Home rapid tapping; audio continuing while entering/exiting settings; Android predictive-back gesture; long localized settings labels.
- Do not claim verified 60/90/120Hz frame pacing or memory consumption without on-device system tracing.
- Scope is settings only. No audio, video, player, theme or gradient changes.
