# Capsule Music — security hardening pass

This supplements SECURITY_AUDIT_2026-10-28.md, which documents the **baseline**. All fixes were developed on `audit/security-deep-20261028`, separate from the playback branch. **Do not interpret a green audit workflow as a malware-free certificate.**

## Changes applied and intent

| Area | Fix | Caveat |
|---|---|---|
| Release login logs | Full Discord token logging removed. Diagnostic export adds best-effort token redaction and tests. | Don't share old logs; assume old Discord token logs were exposed if exported. |
| Discord WebView | Developer tools disabled in release; removed JS-to-Java bridge, token-via-alert path, substring domain acceptance. Exact HTTPS origin checked. No global CookieManager/WebStorage clearing. | This is a legacy user-token extraction integration; official OAuth + PKCE should replace it before a fully trusted public release. |
| YouTube login | Remote JS->native interface replaced with callbacks from trusted top-level music.youtube.com page; WebView file/content access disabled. | Must test login/regression with test account and WebView updates. |
| PoToken manual WebView | Redundant JS bridge removed, source HTTPS allowlist, navigation guard; global cookies preserved, file/content access disabled. | Test PoToken extraction with bot check; app may depend on Youtube login redirects. |
| Automatic cloud/device backup | `android:allowBackup=false`; sensitive DataStore and sharedpref paths explicitly excluded from rules as defense in depth. | Manual user-selected backups remain possible. Old cloud backups already created cannot be remotely deleted by this patch. |
| Manual backup/export | Filters auth cookies/tokens and account identifiers from XML on export and on XML restore. Legacy raw DataStore restore is blocked. | Database export still includes personal listening history and is a plaintext ZIP. Don't share ZIP. |
| Import bomb defense | ZIP entry count, duplicates, per-entry decompressed size, total uncompressed bytes; playlist CSV/M3U read caps + count caps. | Large legitimate databases exceeding 256 MiB are rejected by design; future streaming encrypted backup format preferable. |
| FileProvider | Removed unrestricted `external-path=.` in favor of app-owned external files/cache paths. | Verify all existing share/export flows on physical device. |
| LAN Together | Max frame size 64 KiB, initial handshake timeout, connection count cap, display name length cap, constant-time key comparison. | LAN `ws://` still plaintext; transport confidentiality requires a new encrypted protocol or trusted TLS infrastructure. |
| Known vulnerable libraries | Guava 33.7.2-jre; Wire 6.4.5; jsoup 1.23.2. | OSV comparison reported **0 matches across 356 packages** on the source after this patch; this doesn't prove API/runtime compatibility. |
| Supply chain | Pin Gradle 9.5.0 ZIP SHA-256 (official gradle.org checksum), disable automatic NewPipe signed update/release schedule and immutable release tags. | No comprehensive full source SLSA attestations or approved dependency verification metadata yet. |

## Autonomous reruns

- GitHub Actions `Capsule Security Audit`: source inventory, resolved runtime graph, OSV, Semgrep, MobSF source rules, optional signed baseline APK comparison, debug APK inspection.
- GitHub Actions `Core reliability`: playback/download/cache/gesture/artist regressions + Android lint.
- GitHub Actions `Release APK`: signed APK build, signature validation.
- Each workflow has per-commit run results. **Check the run for the latest commit** and don't confuse successful earlier runs with later code.

## Remaining decisions requiring the owner or test environment

1. **Discord login authentication**: replace session-token scraping with official user-approved OAuth flow using a registered client and redirect URI; this cannot be finalized by code changes alone without app registration and consent. Do not supply your real tokens in this chat.
2. **Plaintext LAN sessions**: You must choose compatibility (current `ws://` on private LAN) vs encryption (protocol version bump, paired clients, optional migration for legacy links). Use test devices and a private Wi-Fi network.
3. **Global cleartext**: Manifest still allows cleartext traffic for local Together / legacy integrations. Disabling globally would break these flows. Only narrow it after transport migration/testing.
4. **Last.fm / Together BuildConfig keys**: a secret included in a client APK cannot be private. Any high-trust backend key needs server-side storage or a provider-supported public-client flow. Never put server credentials into Gradle `BuildConfig`.
5. **User-created database ZIP**: not encrypted; should be clearly labelled sensitive, or use an opt-in password-protected authenticated backup design. Account tokens now filtered; listening history is still present.
6. **Physical device validation**: runtime network endpoints, actual decrypted HTTPS requests (test-only), Media3 audio/video, login, file sharing, Auto Backup behavior and Android 17 changes.
7. **Release signing provenance**: verify signer's certificate fingerprint across versions, verify output SHA-256, check GitHub secret access rules, protection settings, Git commit provenance. Build signing alone does not prove no malicious dependency.

See the separate device-testing guide for PCAPdroid, ADB and offline MobSF (no raw credential uploads).

## Additional Android-specific MobSF source audit

The standalone official **mobsfscan 1.1.0** scanned Android/Kotlin/XML sources
on GitHub Actions run 40659702010. It flagged **75 rule matches**; automated
findings are not confirmed exploitable vulnerabilities:

- 58 `android_kotlin_sql_raw_query`: primarily constants and schema migration
  SQL inside `db/MusicDatabase.kt`. Manual sample inspection found static
  table schema commands and parameter binding; no attacker-controlled SQL
  concatenation was demonstrated in inspected snippets. Further code review
  needed before declaring all 58 harmless.
- 1 `android_kotlin_object_deserialization`: local persisted queue uses
  Java ObjectInputStream in the application's private directory. Added a
  **32 MiB size limit** and explicit allowlist of expected filenames, retaining
  serialization compatibility. Full class allowlisting/format migration remains
  a follow-up before claiming all gadget risks closed.
- 1 each for MD5 and SHA-1 helpers: these appear to be non-authentication
  hashes; do not swap algorithms without auditing their cache/identity usages.
- 1 `android_kotlin_insecure_random` in PalettePicker (visual randomness,
  not a cryptographic secret).
- 1 `android_kotlin_webview` inside the locally loaded PoToken BotGuard
  executor. It deliberately executes YouTube-provided JS in a network-blocked
  WebView; requires threat-model and runtime tests, not an automatic removal.
- Errors for global cleartext flag and task/link configuration: cleartext and
  shared-room WS remain explicit unresolved compatibility decisions.
- 7 native Log calls in database maintenance: manual inspection found
  non-credential status messages / stack traces, but arbitrary error messages
  should still be checked before a log export.
- MobSF parser reported **5 partial parse warnings** on complex Compose
  Kotlin files; scanning those files was incomplete. No interpretation of
  “0 findings” can cover such paths.

**Prior full test run had 1305 tests, 13 failures in HomeStartup,
LyricsUiRegression, ScreenBottomInset, Screens, SearchBarInset and
StandardChrome tests**. These failures are unrelated to the security changes
and were present in the older UI design assumptions; rather than rewriting
unrelated UI to placate assertions, the security CI runs targeted security
unit tests, extractor tests, Android lint and runtime verification. The
full-suite red run remains in GitHub Actions as evidence.

## Current-release provenance gate

`scripts/verify_release_security.py` is invoked for each **new** signed
Release APK, before upload. It checks the package ID, an intentionally
pinned signer certificate fingerprint, non-debuggable packaging, sensitive
permission denylist, duplicate APK ZIP paths and DEX presence. Its JSON
evidence accompanies the APK in a dedicated workflow artifact. Signing
key rotation or adding sensitive permission is an explicit owner-reviewed
change, not something CI silently accepts. This does not detect all
malware; runtime network / device and dependency checks remain essential.


## Legacy queue deserialization allowlist

Replaced unrestricted ObjectInputStream with SafeQueueObjectInputStream for
the three fixed filenames. Unknown classes, arrays of unknown types and
dynamic proxies now fail closed. Keeps the 32 MiB cap. A fresh regression
test exercises media artists/albums/date fields and player state.
Legacy snapshots with obfuscated Kotlin EmptyList names may be discarded;
a new versioned non-Java-serialization queue format remains recommended.
