# Running QuickTube on an Android emulator

The README and [CONTRIBUTING](../CONTRIBUTING.md) assume a phone. This page covers the emulator, which needs a little
extra setup because release APKs are built for phones only.

## Why it needs an extra step

`app/build.gradle.kts` ships **arm64-v8a** and **armeabi-v7a** only (the bundled Python runtime is per-ABI, so extra ABIs
would bloat every download). An emulator on an Intel or AMD machine is **x86_64**, and Android refuses to install an APK
without a matching native library (`INSTALL_FAILED_NO_MATCHING_ABIS`).

Local builds can opt into the emulator's ABI without changing what gets released:

```bash
./gradlew :app:installDebug -PlocalAbi=x86_64
```

That produces an `x86_64` split next to the usual ones and adds it to the universal APK. Without the flag, releases and
local builds stay exactly as before.

## Set up an AVD

Requirements: JDK 21+, Android SDK with the emulator, and a system image matching your CPU (x86_64 on Intel/AMD,
arm64-v8a on Apple Silicon or ARM Linux).

```bash
sdkmanager "system-images;android-36;google_apis;x86_64"
avdmanager create avd -n QuickTube_API_36 -k "system-images;android-36;google_apis;x86_64" -d pixel_6
```

The image is about 4.4 GB. API 36 (Android 16) is the newest stable image and matches the app's `targetSdk 37` closely;
anything from API 24 (`minSdk`) up works.

## Boot it

```bash
$ANDROID_HOME/emulator/emulator -avd QuickTube_API_36 -memory 2048 -cores 2 -gpu auto
```

- **`-memory` / `-cores`**: the defaults ask for more than a small laptop can spare. Two cores and 2 GB are enough.
- **`-gpu auto`**: pick the host GPU. If the emulator logs `Host Vulkan driver is not supported`, it falls back to
  SwiftShader (software rendering): correct, but slow, and a 1080×2400 AVD then takes ~2 minutes to boot. Prefer a
  smaller device profile, or add `-skin 720x1560`, on older integrated GPUs.
- Use `-no-snapshot` for clean repros; snapshots make repeat boots much faster.

## Install and launch

```bash
./gradlew :app:installDebug -PlocalAbi=x86_64
adb shell am start -n com.edureminder.quicktube/.MainActivity
```

Re-launch after closing the app: `adb shell am start -n com.edureminder.quicktube/.MainActivity`.
Installing by hand also works: `adb install -r app/build/outputs/apk/debug/app-universal-debug.apk` (built with
`-PlocalAbi`, the universal APK carries the emulator's libraries too).

On Android 13+ the app asks for the notification permission at first launch. Grant it, or pre-grant with
`adb shell pm grant com.edureminder.quicktube android.permission.POST_NOTIFICATIONS`.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `INSTALL_FAILED_NO_MATCHING_ABIS` | The APK has no x86_64 libraries: rebuild with `-PlocalAbi=x86_64`. |
| `SIGABRT` in `RenderThread`, `glTexSubImage2D error! GL_INVALID_VALUE` | Old images' host-GPU translator choking on hardware bitmaps. Boot with `-gpu swiftshader_indirect`, or use a newer system image. |
| "System UI isn't responding" | The host ran out of RAM. Run one emulator at a time, lower `-memory`, use a smaller screen, or disable animations: `adb shell settings put global window_animation_scale 0` (same for `transition_animation_scale` and `animator_duration_scale`). |
| Emulator won't boot after a crash | A stale lock or snapshot: `-wipe-data` (wipes app data) or delete the AVD and recreate it. |
| Two emulators confuse `adb` | Target one explicitly: `adb -s emulator-5554 shell …`. |

## On-device tests

Instrumented tests (e.g. `SiteSupportTest`) run on the emulator the same way:

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.edureminder.quicktube.SiteSupportTest
```

Tests leave the app installed on purpose (`android.injected.androidTest.leaveApksInstalledAfterRun=true`), so user data
survives between runs.

## Where things live

- AVDs: `~/.android/avd` (Windows: `%USERPROFILE%\.android\avd`), a few GB each.
- App data: inside the AVD; `-wipe-data` or a fresh AVD resets it.
- System images: `$ANDROID_HOME/system-images` (4–5 GB each; delete unused ones with
  `sdkmanager --uninstall "system-images;android-XX;google_apis;x86_64"`).
