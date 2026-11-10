# Capsule Music: active development baseline

As of 2026-11-10, the active development branch is `main`.

- Verified player baseline (source before CI housekeeping): `191ac66f1b9b82da3fffd1f301eab6eea9304760` (`fix/player-artwork-resume-energy-20261110`).
- Archived former `main`: `archive/main-2026-11-10-before-player-upgrade` (commit `f83850e100ff7da4e9c9fb3de9ab2f16fed7242f`).
- Start future feature and fix branches from the *current* `main`; do not base them on the pre-November player audit branch.
- Changes to the player should preserve the November 7 layout refactor: scrollable title, controls/transport geometry, and user-editable Capsule Light.
- The main player opens via compact tap and closes via Android Back. Compact swipe-down clears playback/queue, and the queue owns its independent gestures.
- On app background/foreground, retain the decoded artwork cache but unmount the expensive full-player visual tree.

## Workflows kept
- `capsule-test-apk.yml`: TEST APK on pushes and manual runs (seven-day artifact retention).
- `capsule-dev-release.yml`: optimized, non-production-signed DEV build on development branches and manual runs (14-day APK retention).
- `release-apk.yml`: official signing/release, manual-only and still gated.
- `player-ui-quality.yml`: player tests + lint on PRs and manual.
- `debug-apk.yml`: full-regression suite, manual.
- `security-audit.yml`: full security assessment, manual.
- `thermal-motion-regression.yml` and `video-startup-buffer-regression.yml`: targeted tests, manual.
- `newpipe-extractor-update.yml`: optional manual upstream video work.
- `youtube-client-policy-update.yml`: scheduled legacy browse/client maintenance.

One-off workflows bound to long-finished October/November experiment branches were removed from `main`. Their tests remain in the test sources; historical workflow runs are managed separately from git workflow files.
