# CloudPlayX Mobile v0.2 source update

- Replaced the plain setup form with a dark dashboard layout and grouped connection/cache panels.
- Added an in-app browser with folder navigation and file opening via content URIs.
- Added a button to open Android's system document picker for checking `DocumentsProvider` visibility.
- Fixed Google authorization result handling to retain the access token returned from the permission flow.
- Changed the status label so a selected backend is not falsely described as connected before a real test succeeds.
- Added concise Google API HTTP error details to help diagnose OAuth/scope/API enablement failures.
- Clarified that a `DocumentsProvider` is not a system-wide mounted drive and may not appear inside every OEM file manager.

This is a source-only revision; the APK was not built in this environment because an Android SDK and downloadable Gradle dependencies were unavailable.
