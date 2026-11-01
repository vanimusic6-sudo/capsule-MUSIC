# Independent Capsule identity — verification, 2026-11-01

Implementation: `63d14cf0635a1e3bca5c8085f48cd49008000451`, based on the extractor cleanup at `fe73115f82caa3fb3c73827df723aed7112a9ba0`. [Draft PR 73](https://github.com/vanimusic6-sudo/capsule-MUSIC/pull/73). Report-only commits after this revision do not change the built implementation.

## Findings and changes

| Finding | Resolution |
| --- | --- |
| Capsule release and upstream Velune both used `com.nikhil.yt` | Independent Capsule base ID `com.vanimusic.capsule`, with `.dev` and `.debug` variants |
| Main shortcuts targeted the release package; TEST overrode them with a separate hard-coded copy | One shared shortcut XML; package/actions generated from each final variant |
| APK verifier, CI and device script expected old IDs | Expected install identity derived from `capsuleApplicationId` in `gradle.properties`; certificate pins retained |
| Capsule claimed Velune's implicit Together scheme | Capsule owns `capsule://together` and `capsule://playlist`; explicit legacy decoding retained |
| Current UI/translations, exports, ListenBrainz and store metadata still identified Velune | Current product labels identify Capsule; corrected misplaced Korean button/update/Discord text |
| Old names also appear in class packages, backup tags, filenames and credits | Retained deliberately: source/serialization compatibility and upstream attribution are distinct from install identity |

The audit checked runtime package literals, explicit component construction, provider authorities, build-type resource overlays, release verification, diagnostic/build scripts, current product strings and metadata. No source namespace migration, signing-key change, playback/fallback change, dependency upgrade or motion redesign is included.

## Verification

Six local Python tests passed, including compiled-manifest parser/identity fixtures and existing security-audit guard tests. All edited translation XML parsed successfully. The current-product strings in `values*/strings.xml` contain no Velune brand references; separate attribution resources remain unchanged. `git diff --check` passed.

The focused stage of [full Android CI](https://github.com/vanimusic6-sudo/capsule-MUSIC/actions/runs/41282963785) passed: release/DEV merged manifests, runtime identity/shortcut/provider/link tests on API 26/35, native branding checks across 18 translated configurations, backup/Together tests and the existing artwork/title/discovery tests.

[Playback/extractor validation](https://github.com/vanimusic6-sudo/capsule-MUSIC/actions/runs/41282963788) passed 674 app tests in 121 suites and 142 `innertube` tests in 19 suites, with zero failures, errors or skips. Evidence ZIP SHA-256: `8a8514a0e24101a31929e2b3f35d1a2ab258cfcae097d5e57d85f4b8e5b9653c`. The exact runtime graph contains 362 Maven packages; OSV completed all queries with zero advisory matches. The static source audit reported zero blockers. This is dated evidence, not a comprehensive security verdict.

[TEST build 390](https://github.com/vanimusic6-sudo/capsule-MUSIC/actions/runs/41282963775) and [optimized DEV build 332](https://github.com/vanimusic6-sudo/capsule-MUSIC/actions/runs/41282963787) passed. Compiled APK metadata verifies their `.debug` / `.dev` install IDs, the original fully qualified runtime classes and variant-scoped FileProvider/video/startup authorities.

Android lint and `verifyAndroidJsonRuntime` passed: zero errors, 619 warnings and 25 hints. Lint evidence ZIP SHA-256: `d44f6bd13aeeda83cdfa20eebdfb841918a57ed100e91bacd305625956cab292`. This task does not claim a warning-free codebase.

The combined UI/preferences/Home/player stage passed 423 tests in 58 suites with zero failures, errors or skips. Its evidence contains 80 renderer previews; ZIP SHA-256: `2414f693a18a576cad4eb44bd31043b95f3ae120e6bf3e8fb4e49148ed2d6ec5`. Search/artist metadata, `kizzy`, video quality/matching/classification checks and Universal Debug assembly also passed. All four workflow runs completed successfully. Test counts across jobs overlap and are not a unique-test total.

## DEV 332 artifact

| Field | Verified value |
| --- | --- |
| Implementation | `63d14cf0635a1e3bca5c8085f48cd49008000451` |
| Install package | `com.vanimusic.capsule.dev` |
| Version / code | `1.1.2-dev` / `10` |
| Size | 26,787,279 bytes |
| APK SHA-256 | `9721140cc66aa10d61a765329472cdde6bbab667330c3465f4449c70b9b337a0` |
| Certificate SHA-256 | `09e22d06b0a5221ab71bf631b4325d026d67736087678f43efe27965667c8e2f` |
| GitHub artifact ZIP SHA-256 | `fe5f31969732339ee4e2273589e0370ad0aef26e6fc7adff33e83e039c5ae7d6` |

CI verified APK v2 signing, R8/resource shrinking and compiled install metadata. The downloaded ZIP matches GitHub's digest; the extracted APK's certificate matches DEV 330. Matching certificates do not turn different package IDs into an in-place update: this APK installs beside DEV 330.

## Migration and limits

The new package is a separate installation. Portable backup restores the database and eligible settings; credentials, media caches/download bytes, widgets and OS permissions do not move automatically. Keep the old installation until the new library/settings are checked. An earlier Capsule occupying `com.nikhil.yt` must be removed after migration to free Velune's release ID. See [the migration guide](APP_IDENTITY.md).

This review has no physical-device coexistence, playback, battery or OS-integration trace. Compiled metadata and Robolectric tests verify the boundaries described above; they do not prove every vendor's installer or launcher behavior.
