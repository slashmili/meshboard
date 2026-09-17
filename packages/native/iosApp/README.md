# Meshboard for iPad and iPhone

Kotlin/Compose drawing UI hosted by SwiftUI, with native Apple WebRTC and URLSession
signaling. iOS can create or join the same Phase 1 session as web, macOS, and Android.
Use **Share → Create invite** or **Join** to paste a full invite. Each peer connects
directly to every other peer; there is no privileged host. Invites include a QR code.

Pen, shapes, whole-object eraser, colors/widths, and two-finger pan/zoom are available.
Drawing stays in memory through rotation and is lost when the app process ends or
when you leave. Saving and Apple Pencil pressure/palm rejection remain future work.
The protocol is still the custom v1 prototype, not Yjs/yrs. WebRTC encrypts transport;
invites are unauthenticated and application-layer encryption is not implemented.

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

## Opt-in CRDT bindings — checkpoint 2g

This checkpoint adds **no UI or sharing changes**. The normal app continues to
use v1, and its builds neither require Rust nor include the CRDT C binding.
On an Apple Silicon Mac, install the stable Rust toolchain in addition to the
Xcode/JDK/Node prerequisites above, then run from the repository root:

```sh
rustup target add aarch64-apple-ios aarch64-apple-ios-sim
pnpm test:crdt:ios
```

The command selects/boots the same iPad simulator as `pnpm ios` (override with
`MESHBOARD_IOS_SIMULATOR=<UUID>`). It enables `meshboard.ios=true` and
`meshboard.crdtIos=true` only for this build. It runs five Kotlin/Native boundary
tests, seven real Yjs↔Kotlin/Native↔Rust compatibility scenarios, and links an
ARM64 device framework. These cover lifecycle/stale handles, malformed and
oversized data, a 12,000-point snapshot, incremental/delete-only updates,
duplicate/out-of-order delivery, concurrent writes and creator-independent sync.
Compatibility requests run in a standalone simulator executable over stdin/stdout;
there is no socket listener or new app debug endpoint. No signing team is needed.

The Rust C API returns owned byte buffers; Kotlin copies them and frees every
result in `finally`, including errors. Documents use opaque non-reused IDs, and
both Kotlin calls/close and Rust registry access are serialized. Explicit `close()`
is required; this is not yet a hardened untrusted-network boundary.
The binding uses Kotlin's [C interop and static-library definition support](https://kotlinlang.org/docs/native-definition-file.html).

CI runs this before the existing v1 iPad interoperability and unsigned app build.
Binding reports live in `packages/native/build/reports/tests/crdt-ios/` and
`packages/native/build/test-results/crdt-ios/`; `build/ci/ios-crdt.log` contains
the seven Yjs scenario results. All are uploaded in `test-results-ios`.
The new Apple path is not validated on this Linux development machine: **wait
for green Apple CI before starting the live iOS CRDT preview**. The device library
is compile/link checked only; this does not establish physical iPad execution.

## Install on your physical iPad

1. Connect and unlock the iPad; trust the Mac if prompted.
2. Open `packages/native/iosApp/Meshboard.xcodeproj` in Xcode.
3. Add your Apple Account in **Xcode → Settings → Apple Accounts** if needed.
4. In the Meshboard target's **Signing & Capabilities**, select your team with
   automatic signing enabled. A Personal Team can be used for local device testing.
   Change the bundle identifier if Xcode says it is unavailable.
5. Select your iPad, follow Xcode's Developer Mode instructions if required, and
   press **Run** (⌘R). Allow Local Network access when testing nearby peers.

Signing and Developer Mode are configured by the device/account owner. No signing
team or account is stored in Git. Physical iPad/Pencil execution is still a separate
validation step from the successful unsigned build and simulator tests.

## Networking

Local drawing needs no server. Sharing needs the app's `/api/rtc-config` endpoint,
`/signal` WebSocket relay, and reachable TURN servers from the returned configuration.

### Simulator on this Mac

The default app address is `http://127.0.0.1:5173`. Run these in separate terminals:

```sh
mise exec node@22 pnpm@9 -- pnpm dev
# Native Coturn on macOS (install with brew install coturn when Homebrew is writable):
TURN_RUNTIME=native mise exec node@22 pnpm@9 -- pnpm turn
```

`TURNSERVER_PATH` may specify another Coturn executable. The native launcher uses
`infra/turn/dev.conf`, bound to loopback with testing-only credentials. It is suitable
for simulator/browser/Mac checks on this computer, not public deployment. Linux
container behavior is unchanged; `CONTAINER_RUNTIME` selects a container explicitly.
The Linux invocation uses host networking and a `:ro,Z` bind mount; do not assume
those settings work inside a macOS container VM without separate validation.

On the development Mac, Homebrew installation was blocked by existing directory
ownership. TURN verification used a temporary Coturn 4.6.3 source build linked
against installed OpenSSL/libevent, selected with `TURNSERVER_PATH`; no Homebrew
ownership was changed. Temporary executables are machine-local and may need to be
rebuilt or replaced with an installed Coturn after switching machines.

### Physical iPad

Use a reachable **HTTPS Meshboard origin** in Share's App address field, backed by
WSS signaling and reachable TURN addresses. Use the same origin on every platform.
`127.0.0.1` in an invite or TURN URL points to the iPad itself, not to the Mac.
The Mac's current loopback dev server and relay therefore cannot serve a physical
iPad. STUN alone cannot guarantee connectivity across restricted networks.

Configure a reachable deployment using the root `.env.example` settings. If hosting
from this Mac, it needs a trusted HTTPS endpoint and appropriate TURN routing;
just replacing localhost with a LAN IP is insufficient. Do not expose the local
Coturn config's loopback policy/fixed credentials publicly. A public tunnel to the
web app does not automatically make the loopback TURN service reachable.

## Validation

```sh
mise exec java@temurin-21 -- ./packages/native/gradlew -p packages/native -Pmeshboard.ios=true iosSimulatorArm64Test desktopTest
# Start the web service + TURN above, then:
mise exec node@22 pnpm@9 -- pnpm test:interop:ios
```

The interop command builds/installs a Debug simulator app, prepares the Mac transport,
and runs Chromium against the real iOS transport. The opt-in test listener binds to
simulator loopback only and is excluded from device and Release builds. Tests cover:

- Web-created sessions: previews, drawing, erasing, leave/rejoin.
- iOS-created sessions: 12,000-point late-join snapshots and creator departure.
- Forced TURN on both iOS and browser, with selected relay candidates verified.
- Three-peer iOS/macOS/web mesh and rejoin after the iOS creator leaves.

Eight iOS model/controller tests and ten desktop tests pass. Simulator local-canvas
checks covered drawing, rectangle, erasing, zoom/reset, rotation, and Clear controls;
sharing UI checks covered invite creation and QR rendering. The iPhone 17
header and Share dialog were also checked. Device signing, physical
iPad/Pencil behavior, and mixed-platform networking outside this Mac remain pending.

Implementation: `AppleBoardController.kt` owns the shared document, framing, previews,
and state; `AppleNetwork.swift` owns URLSession and native peer connections. All
session callbacks are serialized on the main queue, stale callbacks are ignored,
queues/messages are bounded, and signaling reconnects with backoff. Board payloads
travel exclusively on the reliable ordered data channels.

References: [Apple device setup](https://developer.apple.com/documentation/xcode/running-your-app-on-simulated-or-physical-devices),
[Kotlin direct integration](https://kotlinlang.org/docs/multiplatform/multiplatform-direct-integration.html),
[WebRTC binary package](https://github.com/stasel/WebRTC/tree/153.0.0).
