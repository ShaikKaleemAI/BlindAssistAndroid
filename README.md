# Blind Assist — Android (pocket mode)

Phone in pocket, back camera peeking out, walk normally. Everything runs ON THE PHONE (no laptop, no Wi-Fi):
camera → EfficientDet-Lite0 (MediaPipe) → range fusion (width + height + ground-plane) → tracker (Kalman + looming TTC)
→ alerts → speech (Android TTS: English/Hindi/Telugu) + stereo beeps + vibration.
Core logic is a Kotlin port of Blind Assist v11 (range_fusion, tracker, avoidance, alert_engine, i18n).

## Build
1. Android Studio (Koala+), open this folder, let Gradle sync (needs internet once; the model is downloaded to
   `app/src/main/assets/efficientdet_lite0.tflite` automatically. Offline? drop that file there yourself).
2. Plug in the phone (USB debugging) → Run. Android 8.0+.

## Use
- Set **camera height** (cm, from ground to the camera in your pocket) and language. Tap START.
- You get 10 s to place the phone: upright, camera at the top edge out of the pocket, lens unobstructed.
- Earphone button (single press): "what is around me". Notification "Stop" button, or reopen app → STOP.
- Use bone-conduction / open-ear headphones so beeps are stereo and you still hear traffic.
- Phone's own speaker works too, but pocket muffles it, and panning needs headphones.

## Pocket specifics already handled
Tilt from the gravity sensor (live ground-plane correction), auto 180° flip if phone is upside-down, real FOV read from
camera, "camera covered" and "phone not upright" warnings, camera-stall watchdog, foreground service + wake lock.

## Not verified (written without an Android SDK; NOT compiled or run)
Build errors are possible; real accuracy, pocket sway, battery/heat, screen-off camera behaviour per phone brand,
Hindi/Telugu wording and TTS voice availability (install the voices in Android settings).
Known limits: COCO model has no stairs / potholes / drains / glass (use your cane); head-height hazards are not seen
from pocket height; roll (sideways tilt) is not corrected; never says the path is "clear".

---
## Stage 1 additions (build/run, tests, replay, logging)
Status: **still not compiled by the author** (no Android SDK available). Fixes were made by static review; see CHANGES-STAGE1.md.

**Gradle wrapper:** `gradlew` + `gradle-wrapper.jar` are not in the zip. Android Studio works without them; for the command line run once
`gradle wrapper --gradle-version 8.7`, or just use the included GitHub Actions workflow (`.github/workflows/ci.yml`).

**Unit tests (JVM, no phone):** `./gradlew :app:testDebugUnitTest -PskipModel`  (7 test files, ~35 tests: geometry, range fusion,
tracker/Kalman/looming, avoidance, alert levels/timing, i18n parity, metrics).

**Live log:** every second the service writes `<phone>/Android/data/com.kaleem.blindassist/files/logs/live_<ts>.csv`
(fps, latency p50/p95, decode/detect/pipeline ms, capture->analyzer ms, battery %, battery temp, thermal status/headroom) plus a `.meta.txt`
with hfov/camera height. Pull: `adb pull /sdcard/Android/data/com.kaleem.blindassist/files/logs`.
Latency column = capture->alert-dispatched when camera timestamps are on the realtime clock, else analyzer time only. It excludes TTS/audio start.
Battery temperature is the battery sensor, not the chip.

**Replay mode:** in the app tap "Replay a walk video (test mode)", pick a video. Same pipeline, video time as the clock. Output in
`.../files/replay/<name>_{frames,events,dets}.csv`. Record the clip with the same phone, same pocket position, then run one live
session first so the true field of view is saved. Differences from live: no frame dropping, no IMU (constant pitch), no audio.

---
## Deploy: GitHub -> APK -> phone
1. Create a GitHub repo, push this folder (branch `main`).
2. Open the **Actions** tab: workflow `build-apk` runs (about 5-10 min the first time).
3. On your phone, open the repo > **Releases** > "Latest debug build" > download `BlindAssist-debug.apk`.
   (Or Actions > the run > Artifacts > `BlindAssist-debug-apk`, which is a zip.)
4. Tap the APK, allow "Install unknown apps" for your browser/Files app, install, open, grant Camera + Notifications.
Debug builds are signed with a debug key: fine for your own phone. A signed release APK comes in Stage 9.
If the build fails, open the failed run, copy the red error lines, and send them to me.
