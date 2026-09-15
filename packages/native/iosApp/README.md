# Meshboard for iPad and iPhone

Local-canvas checkpoint, using the shared Kotlin/Compose UI in a small SwiftUI
host. Pen, shapes, whole-object eraser, colors/widths, and two-finger pan/zoom are
available. The header says **Local only**; Join/Share are absent. Drawing stays in
memory through rotation, and is lost when the app process ends. Saving, Apple
Pencil pressure/palm rejection, and iOS peer connections remain future work.

## Prerequisites

- Apple Silicon Mac, full Xcode, and an installed iPad simulator runtime.
- JDK 21 (`mise install java` using the root configuration).
- Enough free disk space for Xcode and Kotlin/Native build caches (allow 10–15 GB).
- Deployment target: iOS/iPadOS 16.0. Older OS versions are not supported.

The project retains Kotlin 2.2.21 / Compose 1.9.3. The standalone simulator
framework compiled with Xcode 26.6 on this Mac, but Kotlin's published compatibility
entry is Xcode 26.0. This does not establish general compatibility with 26.6.
The simulator app builds and runs on iPad Air 11-inch / iOS 26.5. All five iOS
model tests and ten desktop tests pass. Manual simulator checks cover drawing,
rectangle, erasing, zoom/reset, rotation preserving objects, and Clear cancel/confirm.
The unsigned ARM64 device app also builds successfully. Physical-device execution
and Pencil behavior still need validation.

The first build ran out of disk space; after space recovered, the build succeeded.
The initial launch exposed a missing Compose-required plist flag;
`CADisableMinimumFrameDurationOnPhone` is now enabled and the app launches correctly.

## Simulator

From the repository root:

```sh
mise exec node@22 pnpm@9 -- pnpm ios
```

The script selects a booted iPad, otherwise an iPad on the newest installed runtime.
Set `MESHBOARD_IOS_SIMULATOR` to a simulator UUID to select a particular device.
`pnpm build:ios` only builds. Xcode itself can also build and run the project below.
Gradle's Apple targets are enabled by `-Pmeshboard.ios=true`; desktop and Android
builds remain independent of Xcode. The checked-in Xcode build phase invokes
`embedAndSignAppleFrameworkForXcode` and picks up mise even when opened from Finder.
No CocoaPods or XcodeGen installation is required.

## Try on your physical iPad

1. Connect the iPad to the Mac, unlock it, and trust the Mac if prompted.
2. Open `packages/native/iosApp/Meshboard.xcodeproj` in Xcode.
3. In **Xcode → Settings → Apple Accounts**, add your Apple Account if needed.
4. Select the **Meshboard** target → **Signing & Capabilities**. Keep automatic
   signing enabled and select your team (a Personal Team can be used for local
   device testing). Change the bundle identifier if Xcode says it is unavailable.
5. Select your connected iPad as the run destination. Follow Xcode's instructions
   to enable **Developer Mode** on the iPad if required, then press **Run** (⌘R).

Signing and Developer Mode must be configured by the device/account owner.
`pnpm build:ios:device` can check an unsigned ARM64 device build, but its output
cannot be installed directly on an iPad. A successful simulator build also does
not prove physical-device behavior.

Local drawing requires **no server, STUN, TURN, or internet connection**. For future
sharing, both devices need a reachable app/signaling origin and TURN relay. An
invite containing `127.0.0.1` points back to whichever device opens it, not to the
Mac. The current local-only native iOS preview cannot join the existing web/Mac
sessions, even if their networking is configured correctly.

## Validation

```sh
mise exec java@temurin-21 -- ./packages/native/gradlew -p packages/native desktopTest
mise exec java@temurin-21 -- ./packages/native/gradlew -p packages/native -Pmeshboard.ios=true iosSimulatorArm64Test
```

On iPad, check drawing with finger/Pencil, each shape, erasing, color/width,
rotation without losing objects, pinch/pan, Clear → Cancel and Clear → confirm.
Also check that the header and bottom controls avoid the status bar/home indicator.

References: [Apple device setup](https://developer.apple.com/documentation/xcode/running-your-app-on-simulated-or-physical-devices),
[Developer Mode](https://developer.apple.com/documentation/xcode/enabling-developer-mode-on-a-device),
[Kotlin direct integration](https://kotlinlang.org/docs/multiplatform/multiplatform-direct-integration.html).
