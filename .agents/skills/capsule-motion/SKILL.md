---
name: capsule-motion
description: Build and audit physically coherent, low-jank Jetpack Compose animations in Capsule Music, especially settings navigation, controls and reorderable priority lists.
---

# Capsule Motion — Android Compose development skill

Use for every future task changing the animations or touch interactions in Capsule Music.

## Intent and acceptance criteria
1. Start with the user's SPECIFIC gesture and end state, never with an arbitrary spring.
2. Specify what moves (container/content/background), where it starts, its pivot, where it ends, how Back reverses it and what stays visible below.
3. Appearance should be restrained (Apple-like) with a very small soft landing (MagicOS-like). No decorative bounce, opacity wash, grey seams or staggered arrival of random text.
4. The motion must be interruptible. Rapid repeated Back, switches and dragging must not leave stale layers intercepting gestures.
5. Respect animation-duration accessibility settings and never block touch after finishing.
6. Do not modify audio/video playback, gradients, UI palette, artwork or mini-player without explicit request.

## Correct animation architecture
- Use **one owner** for a geometry transition. Avoid animating the same destination both in NavHost and inside a route wrapper; duplicated scales and translations create a visible knock.
- For distinct objects sharing bounds, evaluate SharedTransitionLayout/sharedBounds; do not fake shared elements with arbitrary unrelated fades. Use NavHost only for actual screen lifecycle.
- For settings the previous screen stays opaque and stationary under the exiting settings canvas. Only settings routes opt in to concurrent screens. Do not enable global route transitions: previous attempts crashed destroyed NavBackStackEntry ViewModels.
- Use matching spring stiffness/damping for related scale/offset dimensions, one visual trajectory and reversible geometry.
- For a press, remember the interaction source and animate a tiny scale of the touched element; preserve Material semantics and minimum target size. Read per-frame states in graphicsLayer/lambda offset, not the parent composition.
- In a reorderable LazyColumn, use stable item keys, library-provided animateItemModifier only on non-dragged rows, local synchronous rearrangement, and persist on drag completion. Avoid two different placement animations on the same row.
- Standard Material Switch has its own thumb size animation; do not draw a second off-screen halo or duplicate thumb.
- Do not create infinite transitions for one-shot effects. Prefer finite effects scoped to visibility.
- Be cautious of independent animation clocks and multiple AnimatedVisibility layers around the same route.

## Required verification
- Inspect actual source, dependencies and user videos before editing.
- Compile in CI and run focused tests, then review every failure rather than dismissing it as flaky.
- Verify slow/fast drag, rapid back navigation, edge swipe, large font, screen rotation, 60/90/120 Hz, TalkBack/disabled animations and battery on an Android device. CI and static review alone cannot prove perceived smoothness.
- Never claim a visual effect is pixel-perfect or device-tested without an actual device recording/trace.
- Keep changes on a review branch until user approves the appearance.

## Reading
- https://developer.android.com/develop/ui/compose/animation/shared-elements/navigation
- https://developer.android.com/develop/ui/compose/animation/shared-elements
- https://developer.android.com/develop/ui/compose/animation/quick-guide
- https://github.com/hamen/compose_skill (animation authoring checks)
- https://github.com/Calvin-LL/Reorderable (exact drag API used by project)
