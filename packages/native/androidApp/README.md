# Android local canvas — checkpoint 4a

This Android app uses the shared Kotlin Multiplatform drawing model and a Compose
touch interface. It supports pen, rectangle/ellipse/line, whole-object eraser,
six colors, three widths, and pan/zoom. Minimum Android version: 8.0 (API 26).
The first validated device is an Android 15 (API 35) x86_64 Pixel 6 emulator.

This checkpoint is **local only**. Android sharing, QR invitations, WebRTC, and
TURN are the next checkpoint. There are no network, camera, or storage permissions.
The ViewModel keeps drawings through screen recreation/rotation. There is no disk
storage: finishing the activity or process death discards the board.

## Run

Use JDK 21 and install Android SDK platform 35, build-tools 35.0.0, platform-tools,
the emulator, and a compatible system image. Set `ANDROID_HOME` to your SDK folder;
the scripts also recognize an ignored `.android-sdk` folder in the repository root.
Accept the Android SDK licenses through the SDK manager before building.

Start your emulator using Android Studio's Device Manager. For the project-local
emulator created during development, run from the repository root:

```sh
ANDROID_AVD_HOME="$PWD/.android-avd" .android-sdk/emulator/emulator \
  -avd meshboard-api35 -no-snapshot -no-audio -gpu software \
  -camera-back none -camera-front none
```

Then:

```sh
pnpm android       # Build, install, and open Meshboard on emulator-5554
pnpm build:android # Build only
pnpm test:android  # Instrumented drawing, pinch, clear, and recreation tests
```

Set `ANDROID_SERIAL` for a different device. Keep only the intended device online
when using these scripts. The APK is
`packages/native/androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

Android is enabled with `-Pmeshboard.android=true`; ordinary `pnpm native` and
`pnpm test:native` do not require an Android SDK. The app module depends on the KMP
library; JVM WebRTC and desktop-specific UI stay in `desktopMain`.

## Try it

Draw with one finger or the emulator mouse. Choose shapes and colors, change the
stroke width, then erase by touching an object's outline. Clear asks for confirmation.
Two fingers pan and pinch; adding a second finger cancels the unfinished stroke,
and drawing resumes only after all fingers lift. Use the emulator's Ctrl/Cmd pinch
gesture, or use the zoom buttons and Pan tool. Reset view restores the origin.
Rotate the emulator to check that your objects stay on the board.

Pressure-sensitive stroke widths, saving, undo, and export remain later features.

References: [Android emulator](https://developer.android.com/studio/run/emulator),
[Kotlin Multiplatform Android setup](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html).
