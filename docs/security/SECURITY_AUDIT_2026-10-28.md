# Capsule Music — deep security and privacy review (28 October 2026)

> Status: **source-level investigation and automated scanner candidate**. This is not a
> declaration of safety, malware absence, or privacy compliance.
> Source baseline: `8536fe70f1770fed326ab017955357a12ecca570` on
> `fix/artist-atomic-player-download-efficiency-20261027`.
> Review branch: `audit/security-deep-20261028`.
> No test accounts, decrypted runtime traffic, installed-device filesystem, or release-APK
> executable behavior were available to inspect from this connector.

## What was actually examined

- Repository tree (1,335 files, 893 Kotlin files) and high-risk application files;
  AndroidManifest, backup rules, FileProvider paths, URLs/deeplinks, music downloads,
  network policy, WebViews, logging, shared-session WebSocket server, GitHub release
  signing workflow, Gradle dependencies/version catalog, Gradle wrapper and automation.
- Existing dated security review and regression CI status; at baseline the
  release and core regression workflows were green. A green workflow is NOT a security test.
- Controlled targeted source checks and evidence-gathering workflow on this audit branch.

## Confirmed security-relevant flaws / insecure design

| ID | Priority | File / line approximate | Evidence | Recommended remediation |
|---|---|---|---|---|
| SEC-01 | **P0** | `ui/screens/settings/DiscordLoginScreen.kt:94` | Full Discord user token written by `Log.d`. Credential exposure in logcat/device diagnostics. | **Fixed on audit branch**; immediately re-authenticate affected accounts if logs may have been exported. |
| SEC-02 | **P0** | `ui/screens/settings/DiscordLoginScreen.kt:77` | WebView debugging unconditionally enabled in production. | **Fixed on audit branch**: `BuildConfig.DEBUG`. |
| SEC-03 | **P1** | `viewmodels/BackupRestoreViewModel.kt:392-422` | Every preference, including YouTube cookie and Discord token, is serialized to *plaintext* `settings.xml` in a user-created ZIP. | Stop exporting auth values; offer explicit warning & encrypted export option, test backup compatibility. |
| SEC-04 | **P1** | `AndroidManifest.xml:26`, `res/xml/data_extraction_rules.xml`, `res/xml/backup_rules.xml` | Auto Backup ON, rules exclude caches but not `files/datastore` containing login cookies/tokens. | Exclude auth storage from cloud and D2D backup, or migrate tokens into an excluded encrypted storage and test restore. |
| SEC-05 | **P1** | `DiscordLoginScreen.kt:90-200` | JS native bridge available to all frames, `url.contains("discord.com")` for page check, any JS alert can be treated as a token. | Replace user-token scraping with official OAuth integration; remove bridge + JS-alert token path, enforce exact origin. |
| SEC-06 | **P1** | `LoginScreen.kt:75-150` | Google/YouTube auth WebView exposes JavaScript bridge; exact-page check exists for scraping but not origin isolation of each subframe. | Limit navigation and remove native bridge when no longer required; review injected-frame threat. |
| SEC-07 | **P1** | `AndroidManifest.xml:33` | `usesCleartextTraffic=true` globally. | Audit LAN WS, music-radio and proxy compatibility, narrow cleartext exceptions; prefer HTTPS. |
| SEC-08 | **P1** | `res/xml/provider_paths.xml:2-5` | Non-exported FileProvider still allows grant-scoped sharing from all external storage (`external-path="."`). | Replace broad mapping with intended app-specific share directories; test exports. |
| SEC-09 | **P2** | `together/TogetherServer.kt:90`, `TogetherLink.kt:20` | Host binds `0.0.0.0`, room key travels in plaintext `ws://` inside client hello. A nearby observer may capture key on untrusted LAN. | Threat-model LAN feature, add rate limits + length bounds, use authenticated encryption for nontrusted LAN. |
| SEC-10 | **P2** | `viewmodels/BackupRestoreViewModel.kt:275-350,410+` | User-chosen ZIP has no explicit archive output cap; settings XML read as unbounded `readBytes()`. | Add max total extraction size, per-file caps, entity/ratio caps; reject duplicate entries. |
| SEC-11 | **P2** | `viewmodels/BackupRestoreViewModel.kt:600+` | M3U import uses unbounded `readLines()`. | Limit file and line size, constrain track count, stream parse. |
| SEC-12 | **P2** | `app/build.gradle.kts:70-91` | LASTFM_SECRET and Together bearer are injected into `BuildConfig`. APK constants are not private. | Do not use embedded tokens for server-side identity/authorization; rotate any server-shared credentials. |
| SEC-13 | **P2** | `settings.gradle.kts:17`, `gradle/wrapper/gradle-wrapper.properties` | JitPack dependencies and no `distributionSha256Sum`; some versions not commit-locked. | Dependency verification, checksums, vetted build upstreams, transitive CVE scan. |
| SEC-14 | **P2** | `newpipe-extractor-update.yml` | Scheduled workflow has signing secrets and `contents: write` plus force tag push, increasing supply-chain impact if a dependency/workflow is compromised. | Restrict credentials, permissions and tag mutation, require protected approvals and immutable release provenance. |

### Protections already found, not a clearance certificate

- YouTube *audio download* policy checks HTTPS, approved Google/YouTube suffixes and
  no URL credentials/fragments; Audius download enforces HTTPS on redirect chain,
  max 240 MiB and bounded redirects.
- Isolated `:capsule_video` process provider is `android:exported=false`;
  download service and debug crash activity are also not exported.
- MediaStore deletion verifies ownership.
- Release workflow verifies APK signing and publishes SHA-256; CI secrets are
  obtained from GitHub Secrets rather than source files.
- Current tree filenames contained no obvious keystores, `.env`, `local.properties`,
  private PEMs, or APKs (this does NOT scan Git history or files inside GitHub Secrets).

## Required follow-up on an actual device

1. Use a test Google/YouTube and Discord account; never an ordinary private account.
2. Test background and foreground traffic with PCAPdroid and a controlled proxy:
   startup, search, video, audio, download, backup, Together, idle, log export.
   Record domains and request categories, redacting cookies/headers.
3. Run MobSF/Android app analysis on the **exact** GitHub signed Release APK.
   Analyze merged permissions/components, trackers, DEX strings, native libs,
   TLS/WebView settings and known rules.
4. On Android 17, verify cloud/D2D backups *do not* transfer authentication tokens;
   test explicit export/restore with token-like dummy fixtures.
5. Test URL attacks (oversized/encoded links, websocket join, playlist/browse paths),
   unknown WebView iframes and local LAN participants. Use own test environment.
6. Compare **SHA-256**, package ID and signing cert with GitHub Actions.
7. Verify *reproducibility/provenance*: repository commit to release APK, pinned toolchain,
   dependency checksums, CI secret access and restricted publishers.
8. Run full dynamic scanners (MobSF, JADX + manual review, supported static SAST)
   and external AV engines with a **public test APK** only; avoid uploading private
   binaries, user auth, debug logs, or keystores.

## Scope limits

A green static scan or CVE query **does not prove that no malware exists**.
No live Android app connection / filesystem / HTTPS decryption / Git history
secret-scanner / authenticated CVE feed was available in the audit interface.
The separate audit workflow collects reproducible, sanitised evidence and
expressly records incomplete OSV checks instead of asserting a clean result.
Do not merge changes without reviewing sensitive login and backup compatibility.
