# Meshboard desktop (macOS and Linux)

Checkpoint 03: Kotlin Multiplatform with Compose Desktop **on the JVM**, running
on Linux x86_64. Native WebRTC uses `webrtc-java`'s libwebrtc JNI bindings. The
desktop application is not a Kotlin/Native executable; packaged distributions
include a Java runtime. This is the approved desktop architecture.

Checkpoint 04b adds [Android sharing](androidApp/README.md), using the shared model,
wire codec, and mobile Compose layout with Android-native WebRTC and OkHttp signaling.

The [iPad/iPhone local canvas](iosApp/README.md) adds a SwiftUI/Xcode host for the
same touch UI. Use `pnpm ios` on an Apple Silicon Mac, or open its Xcode project
to configure signing and run on your iPad. iOS sharing is not implemented yet.

## Run on Linux

Install JDK 21 (`mise install java` with the repository's configuration), then from
the repository root run these in separate terminals:

```sh
pnpm dev
pnpm turn
pnpm native
```

The Gradle wrapper downloads Gradle and Maven dependencies on first use. A graphical
Linux session and the normal desktop libraries (X11, OpenGL/Mesa, ALSA, fontconfig)
are required. WebRTC uses a headless audio module and never requests microphone or
camera access. The TURN launcher needs Podman or Docker.

## Run on macOS

Use the pinned tools, even if your shell has another Java/Node version:

```sh
mise exec node@22 pnpm@9 -- pnpm install --frozen-lockfile
mise exec node@22 pnpm@9 -- pnpm dev
# In another terminal:
mise exec java@temurin-21 node@22 pnpm@9 -- pnpm native
```

The build selects WebRTC JNI for the running JVM's OS and architecture, including
Apple Silicon and Intel Macs. Apple Silicon has been validated; Intel Mac builds
remain untested. No Android SDK is needed. Local drawing works without the server;
sharing needs the web/signaling service. The Linux container TURN launcher has not
been validated on macOS; use a reachable TURN service for restrictive networks.

Build a standalone app with its own Java runtime:

```sh
mise exec java@temurin-21 -- ./packages/native/gradlew -p packages/native createDistributable
open packages/native/build/compose/binaries/main/app/Meshboard.app
# Optional local disk image:
mise exec java@temurin-21 -- ./packages/native/gradlew -p packages/native packageDmg
```

The DMG is written under `build/compose/binaries/main/dmg/`. These are local
development bundles, without Developer ID signing or notarization. The macOS
packaging version is `1.0.0` because Compose's Apple version validator requires a
positive major number; the project remains the Phase 1 prototype at `0.1.0`.

Use **Share → Create invite** to include your local drawing in a new shared board.
Use **Join** to paste an invite from either desktop or web. Both can create a session;
neither holds special host privileges. Leave discards this window's copy. Closing
the final participant loses the board. No automatic save or export exists yet.

The default app address is `http://127.0.0.1:5173`. It works only on this computer.
For other devices, use a reachable HTTPS app origin with WSS signaling and a TURN
service reachable by all participants. Set `MESHBOARD_APP_ORIGIN` before launch or
edit **App address** in the Share dialog. `MESHBOARD_RELAY_ONLY=true` forces the
desktop peer through TURN; the service's relay-only configuration applies too.

Tools: P pen, E whole-object eraser, R rectangle, O ellipse, L line, H pan.
Space + drag or middle-button drag pans; scrolling pans; Ctrl/⌘ + scroll zooms.
Shift constrains shapes. Escape cancels a draft. Sharing shows a copyable link and QR.
Paste links into desktop; native deep-link registration and camera QR scanning are
not implemented at this checkpoint.

This remains a Phase 1 connection prototype. Invites are unauthenticated and there
is no application-layer encryption yet; WebRTC supplies DTLS transport encryption.
Use test drawings. Yjs/yrs and the final authenticated protocol come in later phases.

## Verify and package

From the repository root:

```sh
pnpm test:native
pnpm test:interop
./packages/native/gradlew -p packages/native createDistributable
```

Interop tests require the local TURN relay and Playwright Chromium. They run the
actual desktop transport in a separate Java process, controlled by a test-only
stdin/stdout adapter, against the real browser app. The adapter is not packaged.
`PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` can select an existing compatible Chromium.
Compose tests exercise drawing and invite controls and save a layout image to
`build/test-artifacts/desktop-board.png`.

The generated application folder is `build/compose/binaries/main/app/Meshboard/`.
Run `bin/Meshboard` inside it. The entire folder, including its bundled runtime and
libraries, must stay together. On macOS the output is `Meshboard.app` instead.
Windows and Intel Mac execution remain separate validation checkpoints.

## Layout

- `commonMain`: board geometry, in-memory insert/delete document, strict v1 JSON
  codec/framing, controller contracts, local controller, and mobile Compose UI.
- `desktopMain`: desktop Compose UI, JVM launcher, QR generator, native WebRTC controller and Java
  HTTP/WebSocket signaling client.
- `androidApp`: Android activity, ViewModel, native WebRTC/OkHttp transport,
  QR encoding, instrumented touch/invite tests, and test-only interop adapter.
- `commonTest` / `desktopTest`: geometry, deletion/snapshot behavior, size and frame
  boundaries, cross-language fixtures, interface tests, interop process adapter.

The desktop controller serializes document changes and JNI operations on its own
executor. Native callbacks copy borrowed buffers and enqueue work before returning.
Each peer has a reliable ordered data channel, bounded send queue, initial snapshot,
live previews, and connection retries. Board payloads never use the signaling socket.

Branding comes from the supplied root `icons/` assets. Gradle packages the PNG
as a shared Compose resource for desktop/Android headers and uses it for the
desktop window and Linux distribution icon. Android's adaptive launcher layers
are vector translations of the supplied SVG gradients and mark, with safe-zone
insets; update those XML drawables if the source artwork changes. The Android
header remains 52 dp tall at the default font size. Display names use **Meshboard**;
package IDs and the wire protocol remain lowercase and unchanged.
The macOS distribution uses `icons/meshboard.icns`, generated from the same supplied
PNG with Apple's `sips` and `iconutil` (16–512 point sizes at 1× and 2×).

References: [Compose Desktop](https://github.com/JetBrains/compose-multiplatform),
[webrtc-java](https://github.com/devopvoid/webrtc-java),
[ZXing](https://github.com/zxing/zxing).
