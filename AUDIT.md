# CloudPlayX audit (v0.2 -> v0.3)

| # | Sev | Problem (file) | Root cause (evidence) | Fix | Needs external config? |
|---|---|---|---|---|---|
| 1 | Critical | Drive login fails after each rebuild (`.github/workflows/android-apk.yml`) | CI builds on a fresh runner; AGP generates a new random debug keystore each time, so the signing SHA-1 changes every build. Google's Android OAuth client is bound to package + SHA-1 | Stable key from secrets (or cached fallback) via `signingConfigs.debug`; SHA-1 shown in app and in the CI summary; code-10 error explained | **Yes**: create the Android OAuth client with that SHA-1, enable Drive API, add test user |
| 2 | Critical | No download/extract/launch/compat workflow at all | Source contains only a streaming provider + block cache; no whole-file path | `Downloader`, `ZipExtractor`, `LibraryStore`, `JobCenter`, `Transfers`, `DownloadService`, `GameCompat`, `LibraryActivity` | No |
| 3 | High | Dead token reused after 401 (`GoogleAuth`, `Http`) | 401 threw but cached token stayed valid for 45 min | `GoogleDriveRemote.authed` drops token and retries once | No |
| 4 | High | CI patched source with `sed` and had no tests/lint (`android-apk.yml`) | Step rewrote `MainActivity.kt` at build time; only `assembleDebug` ran | Removed; added layout/stale-file guards, unit tests, lint, SHA-1 report, commit-tagged artifacts, wrapper | No |
| 5 | High | "Old ZIP gets built" | **Not reproducible from the supplied workflow**: it has no ZIP-selection step and builds the checked-out tree. Likely causes: project uploaded nested in a folder or committed ZIP/BAK. I could not read the GitHub repo (blocked) | CI fails on >1 Gradle project or committed `.zip/.bak`; app footer shows the built commit | Check repo contents on GitHub |
| 6 | High | Files not usable by other apps as real files | Provider only offered streamed proxy descriptors | Second root "CloudPlayX Library" with real fds, delete support | No (file manager must support SAF) |
| 7 | Medium | WebDAV password plain text; http:// to any host (`Settings`, manifest) | SharedPreferences plain text; cleartext globally allowed | Keystore AES-GCM; `UrlPolicy` refuses http for public hosts | No |
| 8 | Medium | Rate-limit 403/Retry-After ignored; opaque errors (`Http`) | Only 429/5xx retried; raw body shown | `ApiErrors` classification + friendly text | No |
| 9 | Medium | UI races: stale folder listing overwrites newer one; threads update destroyed activity (`BrowserActivity`) | No request generation / lifecycle guard | generation counter, `Activity.ui` guard, listing cache | No |
| 10 | Medium | Provider I/O thread leaked on failure; Drive query not escaped; shared drives missing | `openDocument`; string interpolation in `q` | fixed | No |
| 11 | Low | Stray `MainActivity.kt.bak`, no launcher icon, dark-only theme, tiny/unlabelled controls | - | removed / added | No |
| 12 | Low | `XML` parser accepted external entities (`WebDavRemote`) | default factory | entity expansion disabled | No |

Not changed on purpose: scope `drive.readonly` (needed to browse all of Drive; it is a restricted scope), BlockCache design (tests pass).
