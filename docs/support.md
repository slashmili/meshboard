# Platform and browser support

This is the support policy for the current pinned stack, reviewed 2026-11-23.
Version floors are declared build/capability requirements, not proof of device
acceptance. Use an up-to-date browser within these ranges. The boundary checks
below must pass before advertising a platform as release-validated.

## Web

| Browser | Minimum version | Device scope |
| --- | --- | --- |
| Chrome | 113 | Desktop and Android, including phones |
| Edge | 113 | Desktop |
| Firefox | 114 | Desktop and Android, including phones |
| Safari | 17 | macOS, iPadOS 17 and iOS 17, including iPhone web |

The production bundle explicitly targets these versions in
[`vite.config.ts`](../packages/web/vite.config.ts). JavaScript transforms do not
polyfill browser APIs. The floor combines Vite 8's syntax/module baseline
(Chrome/Edge 111, Firefox 114, Safari 16.4) with the app's
`URLSearchParams.size` use for strict invitation parsing (Chrome/Edge 113,
Firefox 112, Safari 17). These are source-derived capability floors; the exact
browser releases have not yet passed Meshboard's full end-to-end suite.

Requirements:

* HTTPS and a secure context; HTTP localhost is allowed for development.
* WebRTC reliable ordered data channels with SHA-256 DTLS fingerprints in SDP,
  WebSocket signaling, and reachable configured STUN/TURN endpoints.
* Web Crypto secure random values, UUIDs, HKDF/SHA-256 and AES-256-GCM.
  Crypto policy restrictions can still cause session startup to fail even if
  `crypto.subtle` exists; encrypted session failures never use plaintext fallback.
* Pointer Events and pointer capture, SVG rendering, dynamic viewport units,
  UTF-8 encoding, BigInt and 64-bit DataView access.
* File/Blob byte reads, FileReader and PNG/JPEG `createImageBitmap` decoding.
  Image insertion uses the ordinary file input; File System Access is not required.

Startup checks run before loading the board or joining an invite. Missing
capabilities display a message explaining HTTPS, encryption, connectivity or
browser updates. The guard uses capability detection, not user-agent parsing.
Very old browsers unable to parse the entry module retain the static loading/
update message; disabled JavaScript gets a dedicated message. The guard is not
certification that a network or browser policy allows WebRTC. Connection failures
remain visible in session status with retry. Clipboard permission is optional:
Share keeps the selectable invitation text when copying fails. Unavailable local
storage does not stop drawing; theme preference persistence may be unavailable.
Pressure is used where the input device exposes it, with a predictable fallback.

Embedded/in-app browsers and WebViews are not release targets; use the full browser
or native tablet app. Browser brand names on iOS do not establish engine/API
support, so the same capability requirements apply there.

## Native apps

Native mobile releases target **iPad and Android tablets only**. Resized tablet
windows remain supported. Native iPhone and Android phone release/acceptance work
is outside scope; those devices use the responsive web app.

| Platform | Version / architecture floor | Basis and current limit |
| --- | --- | --- |
| iPad | iPadOS 16.0, ARM64 | Xcode deployment target and Rust C binding target are 16.0. ARM64 iPad simulator builds are separate from device acceptance. Actual iPadOS 16 touch/Pencil and lifecycle checks remain pending. |
| Android tablet | Android 8.0 / API 26, `arm64-v8a` or `x86_64` | Both Gradle modules declare minSdk 26; NDK-linked yrs uses API 26. No 32-bit JNI libraries are packaged. API 26 and physical ARM64 acceptance remain pending. |
| Linux desktop | Ubuntu 22.04 x86_64 (proposed release baseline) | AppImage build runner is Ubuntu 22.04. Needs glibc, X11/XWayland, OpenGL/EGL, ALSA and fontconfig. Musl-only systems are unsupported. Other distributions/newer glibc are not automatically certified. Full packaged boundary acceptance remains pending. |
| macOS desktop | macOS 13, ARM64 or x86_64 (proposed release baseline) | Separate Apple Silicon/Intel JNI and Rust libraries are selected by the JVM. Workflows build DMGs on macOS 26 ARM64 and macOS 15 Intel; neither proves macOS 13 acceptance. Check every bundled dylib and JVM minimum on macOS 13 before release. |
| Windows desktop | Windows 10 x86_64 (planned baseline) | A source target exists but no Windows packaging/test/release job exists. Windows native release is not currently validated or distributed by the release workflow. Windows ARM64 is not a release target. |

A packaged desktop app includes JDK 21; users do not install Java. Linux ARM64
source target selection is not a released AppImage. Running the whole app on a
supported CPU also requires matching yrs, Skiko, WebRTC JNI and graphics libraries;
Java bytecode portability alone is insufficient. Keep the entire packaged app
and its bundled runtime together. Linux FUSE restrictions can require AppImage
extract-and-run; this does not remove native library/graphics requirements.

OS installers enforce mobile deployment/minSdk floors. Missing desktop native
libraries/unsupported OS versions can fail before the UI opens; there is no
promise of a graceful native dialog on unsupported systems. Use responsive web
when the native app is unavailable. No plaintext or unencrypted compatibility
mode exists.

Tablet-only store eligibility is still a release-preparation check in MES-18/19.
The current Xcode device family is `1,2`; the Android manifest does not by itself
exclude phones. Product scope is not proof that store distribution is restricted.

## Pinned development requirements

Use `mise.toml`, the Gradle wrapper and locked dependencies. Node 22.12+ with pnpm
9.15.9; JDK 21; Kotlin 2.2.21; Compose Multiplatform 1.9.3; Gradle 8.14.5; Android
Gradle Plugin 8.9.3. Android builds use compile/target SDK 35, NDK 28.2.13676358
and stable Rust targets for both packaged ABIs. Compile/target SDK is not minSdk.
Apple builds require an Apple Silicon Mac, full Xcode and the matching iPad
simulator/device SDKs; SwiftPM pins WebRTC 153.0.0. Kotlin's compatibility table
lists Xcode 26.0 for Kotlin 2.2.21, while the repository CI selects Xcode 26.6.
That newer SDK combination needs its own build results; do not infer support for
arbitrary newer Xcode versions or silently upgrade dependencies.

Desktop WebRTC is `webrtc-java` 0.17.0; Android uses WebRTC 150.7871.01.
JVM/Android encryption uses platform AES-GCM/HKDF primitives; Apple uses CryptoKit.
FileKit 0.8.8 provides native image pickers; Linux's runtime includes
`jdk.security.auth`. File selection, PNG/JPEG decoding, cancellation and permission
behavior still need checks on each supported OS. Snapshot/PNG/PDF exports and
in-app QR scanning are separate pending features, not support claims here.

## Verification and release checklist

The workflows define the following **coverage**, not a claim that any particular
revision passed CI. Check the linked run/artifacts before relying on a result.

| Check environment | Configured coverage | What it does not establish |
| --- | --- | --- |
| Ubuntu 22.04 / 24.04 x86_64 | TypeScript/unit/build, current Playwright Chromium, desktop UI and local TURN interop | Browser minimum versions, native Ubuntu packaged usability, Firefox/Safari |
| macOS 26 ARM64 / macOS 15 Intel | Desktop UI/interoperability and release packaging | macOS 13, signed/notarized installation or physical pen behavior |
| Android 15/API 35 x86_64 Nexus 10 CI | UI, JNI and real Android/web/desktop interop | API 26, physical ARM64, stylus or real network restrictions |
| macOS 26 + Xcode 26.6 iPad simulator | Kotlin/binding/iPad interop and unsigned ARM64 device build | iPadOS 16, physical iPad/Pencil, store acceptance |

Local Android emulator baseline is the **landscape Pixel Tablet** through
`scripts/android.mjs`; CI's existing Nexus 10 profile is recorded separately.
Playwright is pinned through the lockfile; its bundled browser revision is not
the product's minimum browser. Simulator viewport/device emulation does not
emulate an old browser engine, OS implementation or physical stylus.

Before calling a boundary release-validated, record revision, OS/browser build,
architecture, commands, artifacts and outcomes in MES-10 and the relevant device
issue. Run these checks at the floor and on one current version:

1. Web Chrome/Edge 113, Firefox 114 and Safari 17: serve `pnpm build` over HTTPS;
   create/join, encrypted cross-browser/native sync, forced TURN, late join,
   creator departure, reconnect, image insertion, theme and touch/pen input.
   Test the version just below each floor for the documented unsupported behavior.
2. Android API 26 and physical ARM64 tablet: build/install through
   `scripts/android.mjs`, verify JNI loading, drawing, file picker, encrypted sync
   and foreground/background/reconnect. Repeat on current Android/Pixel Tablet.
3. Physical iPadOS 16 ARM64 and current iPadOS: install a signed device build;
   test CryptoKit/WebRTC/yrs, image picker, Pencil, safe areas, rotation, window
   resizing and lifecycle. Simulator and unsigned compilation are separate results.
4. Ubuntu 22.04 AppImage, macOS 13 on both architectures and Windows 10 x86_64:
   verify bundled library OS/ABI requirements, launch the packaged app, graphics,
   real file dialogs and encrypted/forced-TURN interop. Windows packaging must be
   implemented before claiming Windows release availability.

MES-10 local results are recorded in Linear. Pending boundary and physical checks
remain pending even when unit tests and a production build pass.

## Sources

* [Vite production browser baseline](https://vite.dev/guide/build.html) and
  [explicit build targets](https://vite.dev/config/build-options): syntax/module
  floor; Vite does not supply runtime API polyfills.
* [MDN maintained compatibility data for URLSearchParams.size](https://github.com/mdn/browser-compat-data/blob/main/api/URLSearchParams.json):
  Chrome 113, Firefox 112 and Safari 17 API floor, combined with Vite above.
* [Web Crypto deriveKey](https://developer.mozilla.org/en-US/docs/Web/API/SubtleCrypto/deriveKey),
  [WebRTC data channels](https://developer.mozilla.org/en-US/docs/Web/API/RTCPeerConnection/createDataChannel),
  [randomUUID](https://developer.mozilla.org/en-US/docs/Web/API/Crypto/randomUUID)
  and [image decoding](https://developer.mozilla.org/en-US/docs/Web/API/Window/createImageBitmap).
* [Android ABI guide](https://developer.android.com/ndk/guides/abis): architecture
  compatibility is independent of the OS API level.
* [Kotlin toolchain compatibility](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)
  and [pinned Compose release](https://github.com/JetBrains/compose-multiplatform/tree/v1.9.3).
  Current framework support tables describe newer releases, not proof for this pin.
* [Pinned Apple WebRTC manifest](https://github.com/stasel/WebRTC/blob/153.0.0/Package.swift):
  dependency declaration alone does not validate the deployed binary slices.
* Repository evidence: `packages/native/build.gradle.kts`,
  `packages/native/androidApp/build.gradle.kts`,
  `packages/native/iosApp/Meshboard.xcodeproj/project.pbxproj`,
  `.github/workflows/tests.yml`, `web-tests.yml`, `release.yml`, and
  `scripts/package-appimage.sh`.
