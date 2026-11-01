# Extractor cleanup verification — 2026-11-01

## Scope and source

Review branch: `cleanup/extractor-boundaries-20261101`, based on `41bc376c3c5abb3c4297e300dba52f5e568d4fed` after the artwork/header/search cleanup. Tested implementation: `83143c0558d4e62c3d4fe1ea7e47293e03e8bd57`. Later report-only commits do not change the APK implementation.

This is a bounded cleanup around the working playback/download engine. It preserves library versions, audio profile order, extraction budgets, authentication eligibility, source/cache identity, download scheduling and UI behavior. It does not introduce another player, stream transport or SABR implementation.

## Changes

- Removed the unused compatibility wrapper, no-op stream invalidation hook/calls, orphan SABR probe, always-empty quarantine helper and unused cache flag. Real playback URL invalidation, direct-stream rejection and bounded rollover remain.
- Replaced unbounded per-track client-failure maps with separate playback/download LRU caches: 256 tracks, five-minute verdict lifetime. Expired failures cannot reappear when a later client fails. Last-resolved-client hints are bounded to 128 tracks.
- Converted third-party audio `LinkageError` into a local, classified I/O failure inside the existing client attempt. Cancellation and fatal VM errors retain their original behavior.
- Validated video output before route success, caching or player publication. Wrong clip IDs, expired URLs, unsupported delivery, unsafe CDN URLs, malformed indexed DASH, header control characters and oversized IPC data are rejected so the next eligible route can run. The IPC consumer revalidates output. Validation is local and performs no network/DNS lookups.
- Consolidated existing Media3 components and PipePipe/Wire declarations in the version catalog. Kept the protobuf exclusion and all dependency pins.
- Replaced stale architecture claims with current ownership/contract documentation, a dependency maintenance guide and an index separating active contracts from historical experiments.

## Automated evidence

The [playback validation run](https://github.com/vanimusic6-sudo/capsule-MUSIC/actions/runs/41281213247) passed: 674 app tests in 121 suites and 142 `innertube` tests in 19 suites; zero failures, errors or skips. The evidence ZIP SHA-256 is `be34df289695402dbab3664bfa800607e363b34c0b824e16585f899d3aa9acd1`.

Coverage includes invalid first backend → reserve, wrong-track/expired/malformed responses, library binary incompatibility, cancellation, fatal errors, per-track isolation/TTL/LRU and the existing playback/download lifecycle tests. Three Python audit tests also passed.

Android lint and `verifyAndroidJsonRuntime` passed in the [full regression run](https://github.com/vanimusic6-sudo/capsule-MUSIC/actions/runs/41281213278). The lint XML contains zero errors, 624 warnings and 25 hints; this cleanup does not claim a warning-free codebase. The lint evidence ZIP SHA-256 is `d5ae9d7dcaeddf9ef1ff7046dc35235719cb4e37fb03e766dd5325ff46b20bf1`.

The full regression's combined UI/preferences/Home/player stage passed 423 tests in 58 suites, with zero failures, errors or skips. Its evidence includes 78 renderer previews; ZIP SHA-256: `be17763a50500d544f61a03b20fea618ff0bba98ff0a1d4fa1cb787d163bbad0`. The focused artwork/title/discovery stage, search summary/artist metadata tests, `kizzy` tests and video quality/matching/classification tests also passed. Counts across separate jobs can overlap and are not a unique-test total.

The [TEST APK build](https://github.com/vanimusic6-sudo/capsule-MUSIC/actions/runs/41281213311), [optimized DEV build 330](https://github.com/vanimusic6-sudo/capsule-MUSIC/actions/runs/41281213246) and full regression's Universal Debug assembly all passed. All four workflow runs completed successfully.

## DEV artifact

| Field | Verified value |
| --- | --- |
| Source | `83143c0558d4e62c3d4fe1ea7e47293e03e8bd57` |
| Workflow build | DEV 330; R8 and resource shrinking enabled |
| Package | `com.nikhil.yt.dev` |
| Version name / code | `1.1.2-dev` / `10` |
| APK size | 26,785,967 bytes |
| APK SHA-256 | `1c1e4bd4dd8a442cd0425d6476149a1b762c2399eab6f4693492ac342e43857d` |
| Certificate SHA-256 | `09e22d06b0a5221ab71bf631b4325d026d67736087678f43efe27965667c8e2f` |
| Artifact ZIP SHA-256 | `e0f661eac3a39f1de6b4fab899b76cd045acf1527d45edf91fc4eebe17bcd57c` |

CI verified APK v2 signing and the DEV package. The downloaded ZIP digest matches GitHub's artifact digest; the extracted APK's signer matches DEV 327. No production signing identity was used.

## Security review boundaries

The audit used the exact resolved `universalDebugRuntimeClasspath`: 362 Maven packages queried successfully through OSV, zero returned advisory matches. This is a dated dependency-database result, not proof that the application has no vulnerabilities.

The static source audit returned nine evidence candidates and zero blockers. Three are recognized existing guards: credential-filtered manual export, credential exclusions in both backup/transfer rules, and the Gradle distribution SHA-256. The remaining candidates were triaged:

| Candidate | Current interpretation |
| --- | --- |
| Cleartext networking | Existing LAN/radio functionality; global policy changes require a separate transport review |
| Together binding all interfaces | Existing LAN feature; this cleanup does not redesign its protocol/authentication |
| Last.fm and Together BuildConfig fields | Both configured as empty strings in this source; conditional secret-shipping warnings are not evidence of leaked personal credentials |
| JitPack | Existing pinned dependency provenance boundary; no new upstream or version introduced |
| Embedded Google client key | Known public upstream BotGuard client material, not a newly committed personal secret |

Video URL/MPD/IPC checks strengthen the supported output contract. The separate video process provides crash isolation; it is not an Android security sandbox. No physical-device, dynamic network or battery trace was collected for this cleanup.

See [active architecture](PLAYBACK_ARCHITECTURE.md) and [maintenance rules](EXTRACTOR_MAINTENANCE.md) for the supported extension points and required failure scenarios.
