# Meshboard for iPad and iPhone

Kotlin/Compose drawing UI hosted by SwiftUI, with native Apple WebRTC and URLSession
signaling. iOS can create or join the same CRDT session as web, macOS, and Android.
Use **Share → Create invite** or **Join** to paste a full invite. Each peer connects
directly to every other peer; there is no privileged host. Invites include a QR code.

Pen, shapes, whole-object eraser, colors/widths, and two-finger pan/zoom are available.
Drawing stays in memory through rotation and is lost when the app process ends or
when you leave. Saving and Apple Pencil pressure/palm rejection remain future work.
The app uses yrs and the y-webrtc sync profile, with invite-secret authentication
and AES-256-GCM application encryption through CryptoKit. This Phase 3 Apple
integration requires Mac compilation and runtime verification; see the
[security checklist](../../../docs/phase-3-security.md). Run
`bash scripts/test-apple-crypto.sh` from the repo root on macOS before simulator tests.

## Fresh Mac setup

Install and open full Xcode, finish first-launch setup, and install an iOS runtime
with an iPad simulator. Command Line Tools alone cannot build iOS. Select the full
Xcode installation for command-line tools. See [Apple's component setup](https://developer.apple.com/documentation/xcode/downloading-and-installing-additional-xcode-components).

Check the selected toolchain before changing dependencies:

```sh
uname -m
sw_vers
xcode-select --print-path
xcodebuild -version
xcrun simctl list devices available
mise exec java@temurin-21 -- java -version
```

The project pins Kotlin 2.2.21, Compose 1.9.3, Gradle wrapper 8.14.5, and Android
Gradle plugin 8.9.3. Check [Kotlin's compatibility table](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)
when changing Xcode or dependencies; explain required compatibility changes before
upgrading the shared stack. Keep local signing settings and credentials out of Git.

## Build and run

Requires an Apple Silicon Mac, full Xcode, JDK 21, and an installed iPad simulator.
Use the root mise configuration. Allow 10–15 GB of free space for native caches.
Deployment target is iOS/iPadOS 16; older supported OS versions still need device
validation. The simulator/device builds have been checked with Xcode 26.6 and the
existing Kotlin 2.2.21 / Compose 1.9.3 pins. Kotlin's documented Xcode version is 26.0;
these local checks do not establish general compatibility with all newer SDKs.

```sh
mise exec node@22 pnpm@9 -- pnpm ios
```

The script selects a booted iPad, otherwise an iPad on the newest installed runtime.
Set `MESHBOARD_IOS_SIMULATOR` to a simulator UUID to select a specific device.
`pnpm build:ios` builds without launching. `pnpm build:ios:device` checks an unsigned
ARM64 device app, which cannot be installed directly without signing.

Apple targets are enabled by `-Pmeshboard.ios=true`; desktop/Android builds remain
independent of Xcode. Xcode's build phase invokes `embedAndSignAppleFrameworkForXcode`
and finds mise even when opened from Finder. No CocoaPods or XcodeGen is required.
SwiftPM pins `stasel/WebRTC` at **153.0.0**, including its binary checksum and resolved
revision. The XCFramework contains device and simulator slices. The app embeds it
and uses `@executable_path/Frameworks` at runtime. Only data channels are created;
the app does not request camera/microphone access or create media tracks.

### Build and launch troubleshooting

- Native compilation previously exhausted disk space; check available space before
  retrying a failed compiler/cache task.
- The Xcode host explicitly targets ARM64. Requesting an Intel simulator slice
  without a matching Kotlin target caused Compose resource synchronization to fail.
- Keep `CADisableMinimumFrameDurationOnPhone` enabled in `Info.plist`; Compose's
  startup check aborted the app when that entry was missing.
- Keep `@executable_path/Frameworks` in the runtime search path for embedded WebRTC.

## CRDT toolchain and validation

Every simulator/device and Debug/Release build links yrs. Install stable Rust:

```sh
rustup target add aarch64-apple-ios aarch64-apple-ios-sim
pnpm test:crdt:ios
pnpm test:interop:ios # keep pnpm turn running
pnpm build:ios:device
```

Normal `pnpm ios` creates/joins `#crdt=` invites and uses port 5173 for local
simulator sharing. No preview environment variable is needed. Device builds use
a reachable HTTPS/WSS origin and TURN service. The test listener remains restricted
to Debug simulator builds; it is not included on devices or in Release.

The default-app migration must be compiled and run on macOS before Apple validation
can be marked complete. Follow the [Apple checklist](../../../docs/phase-2-validation.md).
