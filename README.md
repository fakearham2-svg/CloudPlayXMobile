# CloudPlayX Mobile v0.3

Android (no root, Android 8+) app to browse Google Drive / WebDAV, **download and verify** files, extract ZIPs, and hand files to games/installers/file managers, within what Android allows.

## How it works
```
You -> App UI (MainActivity / BrowserActivity / LibraryActivity)
        -> Auth (Google Identity AuthorizationClient, drive.readonly; or WebDAV basic auth, password in Keystore)
        -> Remote (GoogleDriveRemote / WebDavRemote, OkHttp, retry + 401 refresh)
        -> two paths:
           A) Stream: CloudProvider (DocumentsProvider "CloudPlayX") -> BlockCache (4 MiB blocks on disk, LRU) -> any app via the system picker
           B) Download: Transfers -> JobCenter (2 at a time) -> Downloader (resume, MD5, atomic) -> Library folder
                -> ZipExtractor / Android installer / "Open with" / copy to a folder / CloudPlayX Library root in the picker
```
Source map: `GoogleAuth`, `SigningInfo`, `Http`, `ApiErrors`, `GoogleDriveRemote`, `WebDavRemote`, `BlockCache`, `CloudProvider`, `Downloader`, `ZipExtractor`, `LibraryStore`, `JobCenter`, `Transfers`, `DownloadService`, `GameCompat`, `UrlPolicy`, `SafeNames`, `SecretStore`, `Ui` + the three activities.

## Google Drive setup (the part only you can do)
1. Google Cloud Console: create/select a project, enable **Google Drive API**.
2. OAuth consent screen: add your Google account as a **test user** (the `drive.readonly` scope is "restricted"; more than 100 users would need Google verification).
3. Credentials -> **Create OAuth client ID -> Android**: package name `app.cloudplayx.mobile`, SHA-1 = the value shown in the app under *Google sign-in setup* (or in the GitHub Actions run summary). No client ID or secret goes into the app.
4. Use a **stable signing key** (see GITHUB-APK-BUILD.md), otherwise every CI build has a new SHA-1 and sign-in breaks again.
5. In the app: Connect Google Drive -> approve -> it only says "Connected" after a real listing succeeds.

## What works with games (honest table)
"Supported" = CloudPlayX performs every step with standard Android APIs. **None of this has been verified on a physical device or against a real Google account yet** (see Testing).

| File | Level | What CloudPlayX does | What Android/the game limits |
|---|---|---|---|
| APK | Supported | Download, verify, hand to Android's installer (you confirm) | Needs "install unknown apps" permission; games may check signatures / need extra data |
| ZIP | Supported | Download, safe extract into library | Password-protected ZIPs unsupported |
| XAPK/APKS/APKM | Partial | Download, extract | Installing split APKs needs another installer app or ADB |
| OBB | Partial | Download, verify, copy to a folder you choose | Android 11+ blocks writing to Android/obb for every app |
| 7z/RAR/TAR | Partial | Download, verify | Extraction only for ZIP |
| ROMs / disc images | Partial | Download, verify, copy to folder | Needs an emulator app; use only files you may use |
| Engine asset files (.pak ...) | Partial | Download | Only usable by their own game; cannot write into another app's private storage |
| Windows .exe/.msi | Unsupported | Download only | Android cannot run Windows programs |
| Media/documents | Supported | Download or open streamed | - |

Installed games can be launched from *Launch an installed app*. No DRM, signature or sandbox protection is bypassed.

## Limits you should know
- Android cannot mount a drive letter. Other apps see the cloud only through the document-picker interface; file managers that do not implement it will not list CloudPlayX.
- Downloads are single files (no recursive folder download yet). Google Docs/Sheets/shortcuts are skipped.
- Downloads survive the app being backgrounded (foreground service) but not the process being killed; they resume from the saved partial file when you tap Retry.
- Library files live in app storage and are deleted if you uninstall the app.

## Testing
`./gradlew testDebugUnitTest` runs 59 JVM tests: block cache, downloader (resume, checksum, cancel, retry, space, 404, 401), ZIP extractor (zip-slip, bombs, space, cancel), library, job queue, compatibility rules, URL policy, error mapping.
