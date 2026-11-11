# Player background engine restoration

The user requested the first delivered background engine rather than further changes to extraction or rendering.

Source: `e32d0a35` — the first delivered “Player 11.11.2026 DEV” build from the initial background work. This is the delivered build, not a later 9c641af/2D/organic/edge/Feathered approximation.

Nine production files are restored byte-for-byte: background effects, motion, static backgrounds, Immersion content, colour-field renderer, artwork sampler, video ambient sampler, video texture surface and player background integration. The original source sampling, HSV treatment, colour bands, Gaussian radius, dissolve profiles, timers and background wiring are restored together. Later colour-field and ambient-backdrop replacement files are removed.

The loading-placeholder and keyboard changes in MainActivity/ShimmerHost remain separate from the background engine.

`OriginalBackgroundKernelTest` compares all nine source files against SHA-256 hashes calculated directly from e32d0a35. The original functional tests are restored; two obsolete source-contract spellings are adjusted to describe the original cached fields. Tests for the discarded replacement renderer are removed.

Android compilation, the focused regression gate, lint and optimized DEV packaging run in Actions. Physical-device appearance is not claimed verified.
