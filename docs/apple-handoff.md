# Apple-platform handoff

Read [AGENTS.md](../AGENTS.md), [checkpoints](checkpoints.md), and this note before
continuing on the Mac. This is a handoff, not a claim that Apple builds work yet.

## Priorities and decisions

- Prioritize macOS, then iPad/iPhone. Windows can wait; a Windows VM on the Linux
  machine is a possible later validation environment.
- Keep Kotlin Multiplatform and Compose Multiplatform. **Desktop uses the JVM**,
  including a bundled Java runtime; the earlier no-JVM request was withdrawn.
- iOS/iPadOS will use Kotlin/Native with an Xcode app host, not the desktop JVM.
- Pause at each runnable checkpoint so the user can try the app before continuing.
- Display name: **Meshboard**. Preserve the supplied artwork in `icons/`.
- Preserve the compact mobile header: logo/name/status, Join/Share, and Clear/Help
  under the overflow menu. Do not restore the old tall separate branding bar.
- Android testing defaults to the landscape Pixel Tablet. All Android commands
  live in one `scripts/android.mjs` file; do not split out another emulator script.

## What works now

- Web, Linux x86_64 desktop, and Android emulator drawing and sharing.
- Pen, shapes, whole-object eraser, colors/widths, pan/zoom, link/QR creation,
  joining by pasted invite, live previews, and in-memory late-join snapshots.
- Android/web/desktop mesh tests cover both sides creating, large 12,000-point
  strokes, erasing, creator departure/rejoin, and relay-only traffic through Coturn.
- The local Android tablet also passes the five touch/header/invite UI tests.
- Web browser tests and the nine Kotlin desktop/model/protocol tests passed on Linux.

This is still Phase 1. Sync uses the documented custom v1 protocol, **not yet
Yjs/yrs or y-webrtc compatibility**. WebRTC supplies DTLS transport encryption,
but invitations are unauthenticated and application-layer encryption is absent.
There is no save/export, session persistence, undo, or pressure-sensitive width.
Use test drawings; do not describe the current app as production-secure E2EE.

## Fresh Mac prerequisites

1. Clone/pull the latest repository, including this handoff, lockfile, Gradle
   wrapper, and `icons/`. Do not copy Linux `node_modules`, Gradle/native build
   outputs, `.android-sdk`, or `.android-avd` to the Mac.
2. Install/open **full Xcode**, finish its first-launch setup, and install an iOS
   simulator runtime with an iPad device. Command Line Tools alone are not enough
   for iOS builds. Select the full Xcode installation for command-line tools.
   See [Apple's component setup](https://developer.apple.com/documentation/xcode/downloading-and-installing-additional-xcode-components).
3. Install **JDK 21**, **Node 22**, and **pnpm 9**. The existing `mise.toml` can
   supply these via `mise install node pnpm java`, with mise activated in the shell.
   Use the checked-in Gradle wrapper; a separate system Gradle or Kotlin CLI is
   unnecessary. Rust is for the later yrs phase, not this Apple Phase 1 checkpoint.
4. Xcode handles the future iOS host project. A Kotlin-capable editor is helpful
   for shared code, but Gradle builds can run from Terminal.
5. Real sharing tests need a verified TURN setup. Do not assume installing Docker
   or Podman on macOS alone makes the existing Linux relay command work.

First collect the actual machine/toolchain details; the Mac's architecture and
Xcode version have not yet been provided:

```sh
uname -m
sw_vers
xcode-select --print-path
xcodebuild -version
xcrun simctl list devices available
java -version
node --version
pnpm --version
```

The project pins Kotlin **2.2.21**, Compose **1.9.3**, Gradle wrapper **8.14.5**,
and Android Gradle plugin **8.9.3**. At handoff, Kotlin's compatibility table lists
**Xcode 26.0** for Kotlin 2.2.21. A newly installed Xcode may be newer: check
[the compatibility table](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)
before adding Apple targets. Do not assume arbitrary newer SDKs work or broadly
upgrade the project without explaining the required compatibility change.

With the selected tools active, these web-side checks can run immediately:

```sh
pnpm install --frozen-lockfile
pnpm check
pnpm test
pnpm dev
```

Browser integration tests additionally need Playwright Chromium, installed on the
Mac with `pnpm --filter @meshboard/web exec playwright install chromium`, and a
working TURN relay for the relay-only cases. Do not reuse the Linux-specific
`PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` from the prior session.

## Known Apple-port gaps

- `packages/native/build.gradle.kts` hard-codes
  `dev.onvoid.webrtc:webrtc-java:0.17.0:linux-x86_64`. Select and verify the matching
  macOS native artifact for the host/JDK architecture. Do not leave Linux JNI
  libraries on the Mac runtime classpath. Desktop packaging currently targets
  Debian only; add the appropriate Mac format and icon without breaking Linux.
- **There is no iOS Xcode project, Swift host, iOS Gradle target, or Apple WebRTC
  implementation yet.** Opening this repo in fresh Xcode will not create them.
- `packages/native/src/commonMain` contains the shared model, wire codec,
  controller contracts, branding resources, and touch UI. Desktop code is in
  `desktopMain`; Android's platform transport is in `androidApp`. Neither JVM
  transport can simply be linked into iOS.
- The future Apple WebRTC framework must support the chosen simulator and device
  architectures. Verify its distribution/integration method before adding
  CocoaPods or other tools; none is currently required by this repository.

## Networking trap to resolve on the Mac

`scripts/turn.mjs` currently uses a Linux-tested container invocation with
`--network=host` and a `:ro,Z` bind mount. macOS container engines run through a VM;
validate networking and mount options rather than assuming equivalent behavior.
Docker Desktop's host networking is opt-in on supported versions and has
[documented limitations](https://docs.docker.com/engine/network/drivers/host/).
Options include adapting/testing the container setup, running Coturn natively,
or using a reachable TURN server configured through `.env.example`.

Run the web/signaling service locally on the Mac for initial same-machine tests.
`127.0.0.1` in an invite is not the Linux computer when opened on the Mac, and it
is not the Mac when opened on a physical iPad/iPhone. Cross-device testing needs
a reachable HTTPS origin, WSS signaling, and TURN addresses/candidates reachable
from every peer. Do not expose the development Coturn loopback peer policy or
fixed credentials publicly. Do not copy secrets into this handoff or Git.

## Next runnable checkpoints

1. **macOS desktop:** inspect the environment, fix native artifact selection,
   verify drawing, then test web/macOS sharing in both directions and real TURN.
   Pause for the user to try it. If useful, split local drawing and networking
   into separate review gates.
2. **iPad local canvas:** add the minimal Xcode host and Kotlin/Native framework
   integration, reuse the shared model/touch UI, and run in an iPad simulator.
   Keep local-only status explicit until transport is implemented. Pause.
3. **iPad/iPhone sharing:** implement Apple-native WebRTC/signaling against the
   existing wire fixtures. Test previews, erasing, late joining, creator departure,
   and forced TURN with web/desktop. Check iPhone layout too. Pause.
4. **Physical device validation:** configure signing and reachable networking,
   then test a real iPad/iPhone. Simulator success is not physical-device proof.

Do not mark all of Phase 1 complete while Windows or other required targets remain
unvalidated. CRDT integration and application-layer encryption remain later phases.

Suggested opening instruction for the next development session:

> Read AGENTS.md, docs/checkpoints.md, and docs/apple-handoff.md. Continue with
> macOS desktop support first, then iPad/iPhone. Inspect this Mac's architecture
> and toolchain before changing dependencies. Keep the approved KMP/Compose stack
> and pause at every runnable checkpoint for me to test. Windows can wait.
