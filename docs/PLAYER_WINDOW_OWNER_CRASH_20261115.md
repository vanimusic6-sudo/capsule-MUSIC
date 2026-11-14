# Player LayoutNode owner crash: field report and hotfix

The supplied HONOR 600 / Android 17 report identifies optimized DEV build 4df1cd4.
The exact mapping was downloaded from artifact 14126770925 of run 43625697752;
archive SHA-256 ac32ffd16707717dc6a42149fa61ab3971a1b30c6d0d74e1f74a1b4c7081606a.
Its pg_map_id matches the report:
26dfe858838238b2fea248686ff1e431af59c11718da8b81f4f0d35ed95f2618.

Relevant retraced frames:

- LayoutNodeKt.requireOwner
- MeasurePassDelegate.remeasure (MeasurePassDelegate.kt:496)
- MeasurePassDelegate.measure (MeasurePassDelegate.kt:486)
- BoxMeasurePolicy.measure (Box.kt:168)
- BlockGraphicsLayerModifier.measure (GraphicsLayerModifier.kt:849)
- OffsetPxNode.measure (Offset.kt:262)
- ZIndexNode.measure (ZIndexModifier.kt:61)
- MeasureAndLayoutDelegate.measureAndLayout (MeasureAndLayoutDelegate.kt:411)
- AndroidComposeView.measureAndLayout (AndroidComposeView.android.kt:2098)
- AndroidComposeViewAccessibilityDelegateCompat.semanticsChangeChecker (AndroidComposeViewAccessibilityDelegateCompat.android.kt:2461)

This is an ownerless child encountered during a semantics-triggered remeasure, not an audio or
network exception. The trace does not identify a specific application composable or prove an
upstream AndroidX bug. The new transition did, however, introduce fragile mid-flight removal of
its source child and a second live glass-backdrop consumer; both are removed by this hotfix.

## Changes

- Source and destination nodes have stable keyed sibling slots for the entire window lifetime.
  The source stops drawing when covered; it is not detached at the opacity threshold.
- The full page is retained through the close animation's actual completion, including its final
  transparent frames. Presentation also follows opening intent, avoiding a late mount gate.
- Mini content remains attached through finite motion and retires only at the settled expanded
  anchor. Its decorative clock is still draw-gated, so attachment does not imply hidden animation.
- The bridge uses a passive artwork-palette gradient for glass style. Only the real Mini owns a
  live backdrop/refraction node; no second owner-coupled glass renderer is created by opening.
- App, Mini and destination accessibility visibility changes through stable semantics modifiers.
  The backdrop no longer inserts/removes a clear-semantics modifier at each focus handoff.
- No exception is swallowed, accessibility service disabled, frame rate capped, or playback
  behavior changed. Motion geometry and the current spring character are preserved.

## Verification

A source-lifetime regression asserts no detach during active motion, no covered-source draw,
and one final release after close. The previous opacity-threshold removal violates that assertion.

PlayerWindowAccessibilityTest uses Android 17/API 37 with native graphics and an enabled real
Compose AccessibilityNodeProvider. It interleaves node-info queries and delayed semantics checks
with repeated direction changes, Scaffold/LazyColumn recycling, and host removal/restoration.
The test runner is upgraded to Robolectric 4.18 because its supported SDKs include Android 17.
All exceptions propagate; no lifecycle error is filtered out of the tests.

CI results and the replacement APK provenance are recorded in draft PR 100. Android 17 under
Robolectric is not a physical HONOR reproduction; no device-level success or thermal measurement
is claimed until the replacement is checked on the phone.
