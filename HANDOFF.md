# HANDOFF — read this first (works for any Claude account)

Paste this file (or the whole ZIP) into a new chat and say: "Continue this project from HANDOFF.md. Do only the next stage after I authorize it."

## Project
Original professional Android camera app, native Kotlin + Jetpack Compose + Material 3. Working name "Fovea", app ID `app.fovea.camera` (changeable only before first real release). Owner has no coding experience and works only from an Android phone via GitHub, so every stage must end with ONE complete ZIP (project source + HANDOFF.md + CHANGELOG.md), never partial, no old ZIPs inside.

## Locked rules from the owner
- UI direction is final: near-black/graphite, white and grey controls, one lime accent (#B6F23C), big unobstructed viewfinder, camera-first, no glassmorphism/neon/AI-looking UI, original design (do not copy GCam).
- Never fake features: unsupported controls must be hidden or labelled; never show a static image as a live feed; query real device capabilities.
- No accounts, analytics, servers or mandatory internet. Location tagging off by default.
- Be honest: separate implemented / tested / partially tested / not implemented / blocked. Never claim a build was tested if it wasn't.
- Do not silently change app ID, settings schema, preset format or architecture. Fix bugs with the smallest safe change; keep known-good state.

## Six stages (do ONE at a time, wait for owner's go-ahead)
1. UI and visual foundation — DONE (this ZIP)
2. Real camera (CameraX; Camera2 where needed): lenses, photo, video, zoom, tap-focus, EV, flash, timer, aspect, resolution, orientation, gallery save, review/share; then ISO, shutter, manual focus, WB, locks, RAW, histogram, peaking, zebra, level, burst, time-lapse
3. Complete persistent settings (every setting must have a real effect; restore defaults never deletes photos/presets without confirmation)
4. Refinement and stability (crashes, lifecycle, rotation, storage, tests)
5. Offline preset codes: 8 alphanumeric characters, compact versioned bit-packed binary (no JSON/floats in code), deterministic, validated, extended code/file if too complex, never silently drop settings; plus golden-line guide engine settings (color, opacity, thickness, mirroring)
6. Public release: README, license, privacy doc, GitHub Actions, GitHub Releases update checker (no silent install promises)

## Current state (Stage 1)
Code layout under `app/src/main/java/app/fovea/camera/`:
- `state/` CameraUiState, CameraViewModel (UI-only logic), PresetCodeFormat (length/alphabet check only, no decoding)
- `ui/theme`, `ui/components`: design system
- `ui/camera`: CameraScreen, GuideOverlay, CameraPreviewHost (interface; Stage 2 plugs CameraX in here)
- `ui/screens/Screens.kt`: all other screens; `nav/AppNav.kt`: routes
Stack: Kotlin 2.0.20, AGP 8.6.1, Gradle 8.9 (CI only), Compose BOM 2024.09.00, Navigation 2.8.0, minSdk 26, compileSdk 35.

## Test status (honest)
- Gradle build: NOT RUN (authoring environment had no internet/Android SDK).
- 11 unit tests in `app/src/test/.../StateTests.kt`: written, NOT RUN.
- On-device UI: NOT TESTED. Code was reviewed by reading only; compile errors are possible.
- First real build = GitHub Actions workflow "Build and test" (downloads APK artifact `fovea-debug-apk`).

## Known issues
- No Gradle wrapper (can't be made offline); CI uses Gradle 8.9 directly.
- No launcher icon (default icon).
- Toggles and presets are in memory only (persistence in Stages 3 and 5).
- Manual-controls sheet, HDR, lens list, capabilities are UI only / unchecked until Stage 2.
- Guides not customizable yet; spiral is an approximation.

## Owner's next steps
1. Extract ZIP, upload folder CONTENTS to GitHub (include hidden `.github` folder), commit to main.
2. Actions tab > "Build and test". If red, send the error text to Claude to fix (this is the first real compile).
3. If green, install the APK, report bugs, then authorize Stage 2.
Use a new branch from Stage 2 onward; replace project files, never upload a ZIP into the repo.
