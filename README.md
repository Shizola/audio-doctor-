# Audio Doctor — Stage 1

A small, offline Android diagnostic app for capturing evidence around intermittent built-in audio failures. It uses only public Android audio APIs. It has no backend, account, analytics, ads, network access, root features, microphone capture, or audio recording.

> **Important limitation:** Android can report that an `AudioTrack` initialized, accepted samples, and advanced its playback head. None of those observations proves that a physical speaker produced sound. Audio Doctor asks the user what they heard and keeps that answer separate from Android's technical results. Its own test also cannot diagnose every other app's playback.

## Build and install

Requirements: JDK 17 or newer and Android SDK 35.

### Restore the Gradle Wrapper JAR

This repository intentionally omits the binary
`gradle/wrapper/gradle-wrapper.jar` because the Codex Cloud PR exporter only
accepts text changes. The wrapper scripts, wrapper configuration, and Gradle
build scripts remain checked in. Before the first build, use a locally installed
Gradle to recreate the JAR with the configured Gradle version:

```bash
gradle wrapper --gradle-version 8.10.2
```

That command recreates `gradle/wrapper/gradle-wrapper.jar`. It does not change
the application source. The regenerated JAR is ignored by Git in this repository.
You can confirm the configured distribution version in
`gradle/wrapper/gradle-wrapper.properties`.

Then build and install the debug application:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open **Audio Doctor**, leaving media volume at the level you want. The app never raises it.

## Permissions and privacy

The manifest requests **no permissions**. Export uses Android's Storage Access Framework document picker; the user explicitly chooses the destination, so broad storage permission is unnecessary. Logs remain in the app's private preferences until the app is uninstalled/cleared. They contain device/audio state, actions, technical test results, and answers—not recordings or unrelated personal data. Exporting makes a copy wherever the user chooses.

## Status fields

* **Audio mode:** Android's current global mode (`NORMAL`, `IN_CALL`, `IN_COMMUNICATION`, etc.). A change near a phone call can be useful evidence, but is not proof of the cause.
* **Media volume/muted:** the current music-stream index, maximum, and Android mute flag. The index is not a physical loudness measurement.
* **Music active:** `AudioManager.isMusicActive`; an Android-reported activity hint, not evidence of audible speaker output.
* **Connected external audio devices:** non-built-in sink devices currently exposed by the public API.
* **Available output devices:** output devices returned by `AudioManager.getDevices`. Availability does not prove selection or operation. Exact active routing cannot always be determined reliably using public APIs.
* **Snapshot:** UTC ISO-8601 capture time. Device callbacks refresh status; resume and Refresh also compare observable audio mode.

The tone is a comfortable 0.8-second, low-amplitude two-note PCM signal played through a media `AudioTrack`. It respects the current volume/route. The app refuses the test when Android reports call/communication mode, but without privileged phone state it cannot guarantee detection of every active-call situation.

## Suggested test procedure

1. While audio is healthy, tap **Refresh**, run **Test Audio**, and answer what you heard to capture a baseline.
2. When the built-in speaker becomes silent or crackles, tap **Audio Failed** or **Static/Distortion** immediately.
3. Run **Test Audio** and record exactly what you hear.
4. Try existing manual workarounds one at a time (scrubbing, switching apps, or making a call), recording each attempt in `EXPERIMENTS.md` outside the app. Do not run Test Audio during a call.
5. Tap **Audio Restored** as soon as sound returns, then repeat the test.
6. Tap **Export JSON**, choose a safe destination, and compare event snapshots and user answers.

Stage 1 intentionally does not perform background monitoring, playback capture, microphone listening, audio focus manipulation, forced routing, recovery actions, Quick Settings integration, or simulated calls.
