# Application identity and migration

## Install IDs

| Build | Capsule install ID | Earlier Capsule / Velune-derived ID |
| --- | --- | --- |
| Official release | `com.vanimusic.capsule` | `com.nikhil.yt` |
| Optimized DEV | `com.vanimusic.capsule.dev` | `com.nikhil.yt.dev` |
| TEST / Universal Debug | `com.vanimusic.capsule.debug` | `com.nikhil.yt.debug` |

`capsuleApplicationId` in `gradle.properties` owns the base install ID. Gradle adds the existing build-type suffixes. The in-app official-APK verifier, command-line release gate, diagnostic script and CI package checks derive their expected identity from that setting. Signing certificates and version numbers are independent and have not changed in this migration.

Velune's upstream `applicationId` was retained in earlier Capsule releases. Android identifies installations using that ID, not their launcher name. A different version code or signer cannot make two apps with the same ID coexist. See [Android's application ID and namespace documentation](https://developer.android.com/build/configure-app-module).

## Runtime boundaries

The source namespace stays `com.nikhil.yt`. It owns Kotlin classes, generated `R`/`BuildConfig`, Hilt code, serialization names and ProGuard rules. It does not identify the installed app. Do not bulk-rename source packages as an installation fix.

- Application, activities and services retain their fully qualified class names under the source namespace.
- FileProvider, video extractor, AndroidX startup and generated component permissions belong to the final install ID. Provider authorities must not be fixed to the release package in DEV/TEST builds.
- Launcher shortcut actions and target packages are generated from each variant's final ID. The target class remains `com.nikhil.yt.MainActivity`. The runtime test parses the shortcut XML with Android's `Intent.parseIntent`, including on API 26.
- Capsule registers its own `capsule://together` and `capsule://playlist` links. It no longer claims implicit `velune://` links when installed beside Velune. Explicitly shared/imported legacy Together links can still be decoded.
- Existing explicit private media intents, persisted preference keys, queue class names, backup XML tags and private filenames are compatibility contracts. Their old spelling does not cause package/provider collisions. Upstream credits, license headers and historical changelogs are retained.

The APK identity gate inspects actual compiled metadata: install ID, application/activity/service classes, provider authorities and link scheme ownership. Release/DEV merged manifests and resolved TEST shortcuts are checked in Android CI. This does not replace a device installation test.

## Moving from an earlier Capsule build

Changing the ID creates a separate installation and Android private-data directory. It cannot update an earlier Capsule or automatically read its private files, even with the same signing certificate.

1. Keep the old Capsule installed. In its settings, create a backup using **Backup and restore** and save it outside app-private storage. Prefer the existing password-encrypted backup; keep its password.
2. Install the new Capsule build. Restore that backup through the same settings section. The existing export contains the library database and eligible settings; its format has not been renamed.
3. Sign in again. Cookies/tokens and other credentials are deliberately excluded from portable settings exports. Re-grant notification/media/file permissions and select local music sources again where needed.
4. Download offline tracks again. Audio/video caches, downloaded media bytes, OS-keystore secrets, placed widgets and launcher shortcuts are not in the portable backup. Existing downloaded dates/local-file entries do not transfer the media itself.
5. Check the library and settings in the new app before removing the old one. Removing the old installation also removes its private data and downloads. New builds using the new ID and unchanged signer can subsequently update normally.

If the device still has an earlier Capsule installed as `com.nikhil.yt`, that old installation continues to occupy Velune's ID. Installing the new Capsule does not rename the old installation: after migration, remove only the old Capsule if you want to install Velune's release beside the new Capsule.

## Other fork inconsistencies corrected

Current store titles/descriptions, theme resource name, theme search/default name, Year in Music exports/labels, exported picture directory and ListenBrainz submission client now identify Capsule. Attribution to Velune remains explicit in About and source/license material. Playback/extractor order, account policy and UI motion are unchanged.
