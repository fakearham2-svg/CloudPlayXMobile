# CloudPlayX Mobile v0.3

**Fixes**
- Google sign-in: the app now shows the package name and the SHA-1 of the key that signed the installed APK, with a Copy button, and explains Google error codes (code 10 = package/SHA-1 mismatch). CI now signs with a stable key so the SHA-1 no longer changes every build.
- An HTTP 401 now drops the cached access token and retries once with a fresh one (before, the dead token was reused for up to 45 minutes).
- Google rate-limit 403s and Retry-After are honoured; Drive "download quota exceeded", "API not enabled" and "permission missing" errors get plain explanations.
- Shared-drive items are listed; Drive query values are escaped.
- WebDAV password is encrypted with an Android Keystore key (plain-text passwords from v0.2 are migrated). http:// is refused for public servers. XML parsing hardened against external entities.
- Provider: I/O thread no longer leaks on failure; listings notify observers.
- Build: removed the `sed` source-patching step and the stray `MainActivity.kt.bak`; CI refuses duplicate projects or committed ZIP/BAK files, runs unit tests and lint, prints the APK's SHA-1, tags artifacts with the commit id. Gradle wrapper added.

**New**
- Resumable, verified, atomic whole-file downloads (Range resume, MD5 check when Drive provides it, free-space check, retry with back-off, cancel/retry, duplicate prevention, 2 at a time) with a foreground-service notification.
- Library: downloaded files are exposed as a second "CloudPlayX Library" root in Android's file picker (real local files, delete supported).
- Safe ZIP extraction (zip-slip, zip-bomb and space checks; all-or-nothing).
- Game compatibility assessment (supported / partial / unsupported) for every file, shown before download.
- Install APK through Android's installer, copy to any folder via the system folder picker, launch any installed app, "clear unfinished downloads".
- Light/dark theme, launcher icon, accessibility labels, 48 dp touch targets, rotation-safe screens.
