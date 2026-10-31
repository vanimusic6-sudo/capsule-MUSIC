# Persistent Capsule DEV signing

The DEV APK package is `com.nikhil.yt.dev`. It must have the **same** dedicated
DEV signing certificate in every build to allow updates without uninstalling.
The production release key is never used or exposed to DEV builds.

## One-time setup on your own Windows PC

1. Run `scripts/setup-capsule-dev-signing.ps1` locally in PowerShell with Android
   Studio/JDK installed. It generates `%USERPROFILE%\CapsuleKeys\capsule-dev.p12`
   and prints a random password only once. Save both in your local password
   manager / secure local storage; make an offline backup of the key file.
2. Open GitHub repository **Settings > Secrets and variables > Actions >
   New repository secret**. Create all FOUR entries:

   - `CAPSULE_DEV_KEYSTORE_BASE64`: Base64 text of the p12 keystore. The script
     prints the PowerShell command that copies it to your clipboard.
   - `CAPSULE_DEV_STORE_PASSWORD`: generated password in your password manager.
   - `CAPSULE_DEV_KEY_ALIAS`: `capsule-dev`.
   - `CAPSULE_DEV_KEY_PASSWORD`: the same generated password.

3. Re-run the **Capsule Optimized DEV Release** workflow or push another
   development commit. It requires the above secrets and will refuse to
   generate a new random certificate. It displays the signing certificate
   SHA-256 in the run summary so future builds can be compared.

4. **One time only:** uninstall any older DEV APK signed with a random
   ephemeral certificate before installing the first stable DEV APK.
   Export/backup any important DEV app data first. After that, all DEV
   builds signed with this unchanged key install as normal updates.

The TEST APK uses the separate `com.nikhil.yt.debug` package and still works
without the DEV signing secrets. Never put the .p12 or passwords in git,
GitHub Issues, comments, logs, or public links. Never use the production key.
