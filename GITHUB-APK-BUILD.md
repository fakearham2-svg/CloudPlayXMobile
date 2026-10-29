# Building the APK with GitHub Actions

1. Upload the **contents** of the `CloudPlayXMobile` folder to the repository root (so `settings.gradle.kts`, `app/`, `.github/` are at the top level, not inside another folder). Do **not** commit ZIP files; the workflow refuses to build if it finds a ZIP/BAK file or more than one Gradle project.
2. Push to `main`/`master`, or open **Actions → Build CloudPlayX APK → Run workflow**.
3. When the run finishes, open it: the **Summary** shows the commit that was built, the package name and the **signing SHA-1**. Download the artifact `CloudPlayXMobile-debug-apk-<commit>` (it contains `app-debug.apk`). The app's footer shows `build <commit>`, so you can confirm the installed APK is the one you just built.
4. Install the APK on the phone (allow installs from your browser/file manager).

## Stable signing key (needed for Google Drive sign-in)
Google ties the Android OAuth client to the SHA-1 of the key that signs the APK. A fixed key keeps that SHA-1 constant.
Create one once on any computer with Java:
```
keytool -genkeypair -keystore cpx.jks -alias cpx -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 cpx.jks      # copy this output
```
In the repository: **Settings → Secrets and variables → Actions**, add `CPX_KEYSTORE_BASE64` (the base64 text), `CPX_KEYSTORE_PASSWORD`, `CPX_KEY_ALIAS`, `CPX_KEY_PASSWORD`. Keep `cpx.jks` private and backed up; never commit it. Without these secrets the workflow falls back to a key kept in the Actions cache, which can be evicted (then the SHA-1 changes and you must register the new one).

Local build: `./gradlew testDebugUnitTest assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` (needs JDK 17 + Android SDK 34).
