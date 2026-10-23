# HANDOFF — read this first (works for any Claude account)

Paste this file (or the whole ZIP) into a new chat and say: "Continue this project from HANDOFF.md. Do only the next stage after I authorize it."

## DELIVERY RULE (owner's standing instruction, every step)
At the end of EVERY step provide exactly 3 files: (1) the complete project ZIP (source + HANDOFF.md + CHANGELOG.md) to upload to the GitHub repo root, (2) HANDOFF.md, (3) CHANGELOG.md, so the owner can continue on another account if tokens run out. HANDOFF.md and CHANGELOG.md must always be updated to match the ZIP (never leave them one step behind). Owner prefers replies in simple Hinglish (Hindi in English letters), short; always complete ready files (never "change this line" patches); exact GitHub upload steps; at most ONE question at a time; Claude makes the decisions.

## Project
Original professional Android camera app, native Kotlin + Jetpack Compose + Material 3 + CameraX. Working name "Fovea", app ID `app.fovea.camera` (do not change). Owner has no coding experience and works only from an Android phone via GitHub. Every stage ends with ONE complete ZIP (source + HANDOFF.md + CHANGELOG.md); the owner also wants HANDOFF.md and CHANGELOG.md as separate files.

## Owner's workflow
Uploads the stage ZIP to the repo root. A GitHub Action (`ci/unzip-build.yml`, pasted by the owner into `.github/workflows/unzip-build.yml`; ZIP uploads cannot update workflow files) unzips it, commits the source, builds, publishes a GitHub Release `build-N` with `fovea.apk`. The app's Settings > Check for updates downloads that APK and opens Android's installer. IMPORTANT: if ci/unzip-build.yml changed in a stage, tell the owner to re-paste it.

## Locked rules from the owner
- UI: near-black/graphite, white/grey text, one lime accent (#B6F23C), big viewfinder, camera-first, original (not a GCam/phone-camera copy). Owner said Stage 1 UI "didn't look good"; Stage 2 redesigned toward a pro-camera feel (text tokens, mode row, pro strip). Ask what to improve next.
- Never fake features: unsupported controls show N/A or are listed as PLANNED. Capabilities come from the real camera.
- No accounts/analytics. INTERNET permission is used only by the updater. Location tagging off.
- Be honest about what is implemented / tested / not built. Do not change app ID, settings or preset formats silently.

## Owner's requested features (from his phone's camera and a pro-camera app)
Flash, HDR, aspect ratio, timer, tilt-shift, timed burst, watermark; modes Pro, Video, Photo, Portrait, Documents, Ultra HD, Night, Slow motion, Time-lapse, Long exposure, Panorama, Dual video; gridlines, preserve settings, shutter sound, volume-button function, save location, anti-banding, restore defaults; histogram, DNG/HEIF, manual focus/temp/tint/exposure; color mixer, vignette, filters, frame editing.

## Six stages (one at a time, wait for authorization)
1 UI — DONE. 2 Real camera — DONE (owner confirmed). 3 Complete settings — DONE for now (compiled, works; gaps listed under Stage 3). 4 Refinement/stability — DONE (owner installed it and reported no bugs except the Play Protect warning). 5 PLANNED MODES — IN PROGRESS (this ZIP = part 1, written but NOT compiled; owner swapped the old Stage 5/6 order on 2026-10-23). 6 Offline 8-char preset codes (compact versioned bit-packed binary, deterministic, validated) + guide customization (was Stage 5; Look order is APPEND ONLY because codes refer to looks by position). 7 Public release: secret-based signing key, a RELEASE (not debug) build signed with the same key to try to reduce the Play Protect warning (needs the owner to re-paste ci/unzip-build.yml once; no guarantee), README, license, privacy doc, check Google's Android developer verification (started 2026-09-30 in Brazil/Indonesia/Singapore/Thailand, India expected later). Owner said the rest can be decided later.

## Stage 5 status — Planned modes, PART 1 (CameraProject-Stage-05-Modes.zip, app version 0.5.N; written, NOT compiled or tested)
Rule kept: nothing is faked. Only what is real is built; the rest stays listed as PLANNED in the camera screen's MORE sheet (now called "Capture tools").
Built in part 1 (all in the MORE sheet, Photo and Pro modes):
1. Burst: 10 photos one after another (each waits for the previous save). Originals only.
2. Time-lapse (as PHOTOS, not a video): interval 2/5/10/30 s, length 10/30/60/120 photos. Originals only. Honest label: video assembly (MediaCodec) is NOT built. Screen is kept awake while it runs. Press the shutter again to stop; message says how many were saved.
3. Self-timer counts down once before a burst/time-lapse starts. Low-storage guard stops the run. Drive mode is NOT saved (app always starts in Single).
4. Watermark: toggle + custom text (max 40 chars, default "Fovea"), both saved. Adds text bottom-right to a saved COPY (original kept) via LookProcessor.createLookCopy(..., watermark). If a Look is also on, one copy gets both (name suffix e.g. _VIVID_WM, or _WM alone). Not applied to burst/time-lapse frames.
5. Removed the "UI states preview" demo screen from Settings.
6. Stage numbering in app text updated (preset codes = Stage 6). Version 0.5.N.
Code: state/CameraUiState.kt (DriveMode, LapseInterval, LapseFrames, watermark fields), CameraViewModel setters, CameraEngine.takePhoto(..., watermark, ...) + takePhotoAwait, LookProcessor watermark, CameraScreen sequence runner + MoreSheet, SettingsStore (watermarkOn, watermarkText). Unit tests in Stage5ModesTest.
STILL PLANNED (not built; safest order for later parts): Part 2 = HDR, Night and Portrait blur through CameraX Extensions (needs dependency androidx.camera:camera-extensions:1.3.4; only on phones that report support, otherwise show N/A; bind must fall back to a normal camera if the extension fails; histogram analysis may need to be off with extensions). Part 3 = Tilt-shift (processed copy, like Look), Documents (enhance only, no auto-crop), Ultra HD (highest photo resolution; check ResolutionSelector high-resolution mode API first). Hardest / may never work on all phones: RAW/DNG (needs Camera2 capture), Long exposure, Slow motion (high-speed Camera2 session), Panorama, Dual video (concurrent cameras).


## Stage 4 status (done; written earlier as CameraProject-Stage-04-Stability.zip; owner installed it, no bugs reported except Play Protect warning)
Owner asked for FIXES ONLY in Stage 4 (no new features). All changes below are small and low-risk; nothing was compiled because the sandbox has no Android SDK.
1. Golden spiral guide redrawn (owner reported it looked wrong). Old code drew a floating logarithmic spiral. Now a true golden spiral fitted to the frame: pure geometry in state/GoldenSpiral.kt (frame cut at 61.8% repeatedly, one quarter-ellipse arc per piece, 10 arcs, continuous; portrait starts with top piece, landscape with left piece), drawn by ui/camera/GuideOverlay.kt. Unit tests: continuity, stays inside frame, arcs shrink, start corner.
2. Camera is released when the camera screen is left (Settings, Gallery, Presets...): CameraEngine.unbind() called from CameraScreen's DisposableEffect. Stops a running recording safely (video is finalized), saves battery, frees the camera. It rebinds on return. Capabilities are kept so the Device capabilities screen still works.
3. Interrupted video: app sent to background, low storage or size limit no longer shows a meaningless "error code N". The partly recorded video is kept, the message is human text, and recording now has a file-size limit = free space minus 30 MB so it stops cleanly before storage is full. Too-short recording says nothing was saved.
4. Camera start: a cancelled start (screen changed during first start) no longer shows a false "Camera could not start".
5. Updater: check/download now run in UpdateManager's own scope (startCheck/startDownload) so leaving Settings no longer leaves it stuck on "Checking" or "Downloading".
6. Crash guards: Open/Share/Install with no app that can handle it now show a message instead of crashing; failed delete shows a message (can happen for files from an older Fovea install).
7. Look copy: a failed JPEG write or out-of-memory removes the half-written file; no crash if the app is closing while a look is processing.
8. Saved gridline opacity/thickness are clamped when loaded (corrupt value cannot break drawing).
9. Accessibility: shutter and recent-capture buttons have spoken descriptions (Take photo / Start recording / Stop recording / Open recent captures); top tokens, mode tokens and pro tokens are marked as buttons.
10. Version 0.4.N (build.gradle.kts), RELEASE_NOTES.md updated.
Deliberately NOT changed (be honest with the owner): the app stays portrait-locked (screen rotation not supported by design), presets still in memory only (Stage 5), no real-device battery/memory profiling or instrumented tests were possible, TalkBack was not fully audited, errors caused by another app taking the camera are not observed yet, release title in the workflow still says 0.2.N.
Ideas for a later small stability pass: observe CameraX camera state errors, TalkBack audit, rotation support decision, remember flash/timer.


## Stage 3 status (compiled; owner confirmed it works)
Real, persisted settings (SharedPreferences via SettingsStore): open-in mode and start camera (or last used), JPEG quality (100/95/85), prioritize speed (CameraX min-latency), video quality (4K/FHD/HD/SD with fallback), record audio, gridline color/opacity/thickness (Gridlines screen), histogram, shutter sound, volume-button shutter, haptics, reduce animations, larger camera text (x1.15), selected Look. Settings screen also shows storage (file count + free space), permission status (opens app settings), privacy facts, and Restore defaults (never deletes photos/videos/presets and does not change the current mode). Low-storage guard blocks capture under 50 MB (photo) / 200 MB (video). 13 more Looks added (20 total) in state/Look.kt, APPEND ONLY.
Stage 3 still missing: language infrastructure (string resources/locale config), metadata options, custom save folder, accent/theme choices, manual-control defaults, preset behavior settings (Stage 5), location tagging (intentionally not offered: app never reads location).
Likely next small items (bundle with each fix): remember flash/timer, time-lapse or burst, tilt-shift/watermark, DNG capture where hardware allows.

## Stage 2 status
Update 2.1 (Looks, written but NOT compiled): 7 built-in color looks (Vivid Pop, Warm Gold, Cool Teal, Rich Contrast, Soft Fade, Natural+, Off) in state/Look.kt (pure color math, unit tested) and camera/LookProcessor.kt. Chosen via the LOOK token on the camera screen (photo/pro only). On capture the original is kept and a graded copy (max ~8 MP, upright, JPEG 95) is saved as a second file `..._<LOOK>.jpg`. Live preview uses TextureView.setLayerPaint (approximation, color only). Selected look is persisted. These looks are the planned "enumerated built-in looks" for the Stage 5 preset codes; do not change their numbers without a migration. Limits: color grade only (no HDR+/depth/blur), no video looks, no highlights/shadows curves yet.
Stage 2 base:
Implemented (written, NOT compiled or run): CameraX preview; photo (JPEG to Pictures/Fovea); video FHD with audio (Movies/Fovea); front/back; pinch zoom + zoom presets from real zoom range; tap-to-focus; exposure compensation; flash and video torch; self-timer; 3:4 and 9:16 (video is 9:16); Pro mode with ISO/shutter/manual focus/WB presets via Camera2 interop only when supported; live histogram; guides overlay; permission flow incl. denied state; real gallery, review, share, open, delete; real capabilities screen; settings persisted in SharedPreferences (gridlines, aspect, histogram, haptics, shutter sound, volume-button shutter) + restore defaults (never deletes photos/presets); updater (check, download with retry, size + package check, Android install prompt).
Not implemented: HDR, portrait, night, panorama, time-lapse, slow motion, long exposure, documents, Ultra HD, dual video, timed burst, tilt-shift, watermark, DNG/RAW capture, look editing, physical lens picker, screen-rotation handling (app is portrait-locked), preset codes (Stage 5), presets persistence.
Code map (`app/src/main/java/app/fovea/camera/`): camera/ (CameraEngine = only camera code, DeviceCaps, MediaStoreHelper), state/ (CameraUiState, CameraViewModel, PresetCodeFormat), settings/SettingsStore, update/UpdateManager, ui/ (theme, components, camera/CameraScreen + GuideOverlay, screens/Screens.kt), nav/AppNav.
Stack: Kotlin 2.0.20, AGP 8.6.1, Gradle 8.9 (CI), Compose BOM 2024.09.00, CameraX 1.3.4, minSdk 29, compileSdk 35.

## Test status (honest)
Stage 2 base compiled on GitHub Actions and the owner confirmed the camera app works on his phone (tests passed after one fix). Update 2.1 (Looks) compiled and the owner confirmed it works; Stage 3 compiled and works. Stage 4 (stability) was uploaded; the owner then used the app (screenshots 2026-10-23) and reported no bugs, only the Play Protect warning. Stage 5 part 1 (this ZIP) is written but NOT yet compiled or tested: ask the owner (one question) whether its build was green and whether burst, time-lapse and watermark work. If CI fails, ask for the `e: file` lines or the lines near `FAILED` in the log.

## Known issues / notes
- Signing key `app/fovea-personal.jks` is committed on purpose (personal use). The Stage 1 APK used a different key: uninstall Stage 1 once before installing Stage 2; later updates install over each other.
- Updater needs the repo to be PUBLIC. Android always asks the user to confirm installs and to allow "install unknown apps" for Fovea once.
- No Gradle wrapper; CI uses Gradle 8.9. No launcher icon yet.
- Manual controls depend on the phone; many phones limit them. Histogram uses brightness only.
- Presets still in memory only.
- Cosmetic: the release title in `ci/unzip-build.yml` still says "Fovea 0.2.N" while the app version is 0.4.N. Harmless; fix only when the workflow must be re-pasted anyway.
- Handoff/changelog are updated with every ZIP so they always match the code (see DELIVERY RULE).

## Owner's next steps (Stage 5 part 1)
1. Do NOT re-paste `ci/unzip-build.yml` (unchanged).
2. In the GitHub repo (mobile browser): delete any old ZIP in the repo root, then Add file > Upload files > choose CameraProject-Stage-05-Modes.zip > Commit changes.
3. Open the Actions tab and watch the run. If red: copy the lines starting with `e: file` (or lines near `FAILED`) and send them to Claude. If green: install `fovea.apk` from the newest Release (or Settings > Check for updates).
4. Quick test: camera screen > MORE > (a) Drive mode: Burst, close the sheet, press shutter: 10 photos, badge shows 1/10... and pressing the shutter again stops; (b) Time-lapse 2 s, 10 photos: runs, screen stays on, photos appear in Recent captures; (c) Watermark on, set your text, take a normal photo: a second file with the text bottom-right (name ends _WM); (d) Settings no longer has "UI states preview". Report bugs, then say whether to continue with Stage 5 part 2 (HDR/Night/Portrait via phone extensions) or go to Stage 6 (preset codes).
