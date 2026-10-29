# Capsule Music — development APK vs official release APK

Capsule deliberately has **two signing channels**, independent of the
experimental code branches:

| Channel | Test APK | Official Capsule APK |
|---|---|---|
| GitHub workflow | `Capsule Test APK (no release key)` | `Release APK` |
| Trigger | Push to any branch; manual run | **Manual run only** |
| Android application ID | `com.nikhil.yt.debug` | `com.nikhil.yt` |
| Android launcher name | Capsule TEST | Capsule |
| Android build | Universal Debug | Universal Release, R8/shrinking |
| Certificate | Android's generated *debug* key | Existing **official** developer signing key |
| Production signing secrets | **Never used** | `capsule-signing` environment |
| Approval | Not required | Controlled by environment protection |
| Suitable for public release | **NO** | Only after release QA |

Both APKs are signed because Android requires a signed APK for installation.
The test APK is **not** signed by the official developer certificate; it is
not an unsigned executable and must not be distributed as the real release.

## Day-to-day workflow

1. Commit fixes to an ordinary branch, e.g. `audit/security-deep-20261028`.
2. GitHub Actions automatically creates the test build from that commit.
3. Download its artifact `Capsule-TEST-Universal-<run number>`. The app
   installs separately from the official app; its separate data directory
   avoids overwriting the user's normal library or credentials.
4. Once reviewed and tested, merge the source into `main` or a protected
   `release/...` branch.
5. Run **Release APK** via the GitHub Actions workflow manual Run button,
   selecting that vetted branch. Environment approval is requested if enabled
   in GitHub settings. The actual production APK signing key is never required
   for debug/test builds.

## Security and limitations

- The default Android debug key is generated on ephemeral GitHub runners.
  Its signer can change between jobs: Android will **not** update an earlier
  test app signed by a different test key. In that case uninstall the old
  `com.nikhil.yt.debug` before installing the new test APK (this deletes its
  *test app data*, not the official Capsule app). A separately protected,
  persistent **test-only** signing key can be added later to support seamless
  test updates without ever using the official signing key.
- Never use the test APK for sensitive accounts or treat a passing debug
  build as proof that optimized release builds have identical behavior.
- The `capsule-signing` environment's required reviewer can be disabled
  by the owner, but it is helpful against automated unauthorized CI runs.
  It does **not** protect against a fully compromised GitHub owner account.
- The official signing secrets must ultimately exist only as **Environment
  secrets**, **not** as general Repository secrets available to other jobs.
  Do not remove secrets until the owner has verified the originals are
  recoverable and a protected-environment signed build works.
- Protect the approved source branches and release signing environment in
  repository settings. The workflow's source-branch condition is only one
  layer: code and workflow modifications by the account owner can bypass it.
- An independent offline developer-held manifest-signing key protects
  release provenance further and is still to be designed and implemented.
- GitHub Actions workflow files changed on this audit branch must be
  reviewed/merged into the repo default branch before assuming manually
  triggered workflows are active everywhere.

Production `main` or `release/...` signing is **not** the same thing as
running a release build directly from an arbitrary experiment.
