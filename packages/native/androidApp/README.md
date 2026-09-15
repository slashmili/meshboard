# Android sharing — checkpoint 4b

This Android app uses the shared Kotlin Multiplatform drawing model and a Compose
touch interface. It supports pen, rectangle/ellipse/line, whole-object eraser,
six colors, three widths, and pan/zoom. Minimum Android version: 8.0 (API 26).
The first validated device is an Android 15 (API 35) x86_64 Pixel 6 emulator.

Android can create an invite link/QR or join a web/desktop invite. Native
`org.webrtc` data channels carry drawings, live previews, erasing, and snapshots;
OkHttp carries only connection metadata to signaling. TURN fallback is wired in.
Only Internet/network-state permissions are requested, not camera, microphone, or storage.
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
pnpm test:android  # Instrumented drawing, pinch, clear, recreation, and invite UI tests
pnpm test:interop:android # Real Android/web/desktop sessions; run pnpm turn first
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
The compact header keeps Join/Share visible; **⋮ → Clear board / Help** holds
the less frequent actions. Connection status sits underneath the small wordmark.
Two fingers pan and pinch; adding a second finger cancels the unfinished stroke,
and drawing resumes only after all fingers lift. Use the emulator's Ctrl/Cmd pinch
gesture, or use the zoom buttons and Pan tool. Reset view restores the origin.
Rotate the emulator to check that your objects stay on the board.

For sharing, keep `pnpm dev` and `pnpm turn` running on the host computer:

1. In Android, choose **Share → Create invite** using the default app address.
2. Copy the invite and open it in your computer's browser (or paste into desktop's
   **Join** dialog). Wait for **1 peer connected**, then draw/erase on both sides.
3. Try the reverse: create on web/desktop, copy its full invite, then paste it
   into Android's **Join** dialog. Joining replaces the current Android canvas.
4. Add a third participant and let the creator leave. The others keep drawing.
   Rejoin using the same invite while another participant is still connected.

The **debug emulator build** maps localhost/127.0.0.1 network destinations to
Android's host alias `10.0.2.2`. Local-development TURN destinations receive the
same translation; invite links retain the host's original address. You do not
need to expose the development services to your LAN or configure `adb reverse`.
Cleartext traffic is allowed only for these three local hosts in debug builds.
Release builds require HTTPS/WSS. Physical devices require a reachable HTTPS
app origin and TURN service; loopback invites/QR codes will not work on a phone.
Paste invites into Android; camera QR scanning and native deep links are not
implemented yet. A QR can open the web fallback on a device that can reach its origin.

The ViewModel also retains the connection across activity recreation. Background
suspension/process death is not a persistent session or background-service guarantee.
Leaving discards the local board. As elsewhere, there is no host after creation.
This is still a **connection prototype**, with DTLS transport encryption only:
no authenticated invitations, application-layer encryption, CRDT, or disk storage.
Use test drawings, not sensitive information.

Interop tests install a separate test APK and control the real Android controller
through a loopback socket temporarily forwarded by adb. This adapter is not in
the app APK. Tests verify both platforms creating, previews/drawing/erasing,
12,000-point late-join snapshots, Android/web/desktop full mesh, creator departure,
rejoining, and relay-only traffic through actual Coturn. `test:android` skips this
opt-in harness and runs the UI tests without requiring a server. Interop tests
need Playwright Chromium, just like `test:interop`; a compatible installed browser
can be selected with `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH`.

Pressure-sensitive stroke widths, saving, undo, and export remain later features.

References: [Android emulator](https://developer.android.com/studio/run/emulator),
[Kotlin Multiplatform Android setup](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html).
WebRTC binary distribution: [webrtc-sdk/android](https://github.com/webrtc-sdk/android).
