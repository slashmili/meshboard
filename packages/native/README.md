# Meshboard desktop

Checkpoint 03: Kotlin Multiplatform with Compose Desktop **on the JVM**, running
on Linux x86_64. Native WebRTC uses `webrtc-java`'s libwebrtc JNI bindings. The
desktop application is not a Kotlin/Native executable; packaged distributions
include a Java runtime. This is the approved desktop architecture.

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

The generated application folder is `build/compose/binaries/main/app/meshboard/`.
Run `bin/meshboard` inside it. The entire folder, including its bundled runtime and
libraries, must stay together. Native installers and other OS builds are separate
checkpoints; the current WebRTC artifact is explicitly Linux x86_64.

## Layout

- `commonMain`: board geometry, in-memory insert/delete document, strict v1 JSON
  codec/framing, controller contract, Compose UI.
- `desktopMain`: JVM launcher, QR generator, native WebRTC controller and Java
  HTTP/WebSocket signaling client.
- `commonTest` / `desktopTest`: geometry, deletion/snapshot behavior, size and frame
  boundaries, cross-language fixtures, interface tests, interop process adapter.

The desktop controller serializes document changes and JNI operations on its own
executor. Native callbacks copy borrowed buffers and enqueue work before returning.
Each peer has a reliable ordered data channel, bounded send queue, initial snapshot,
live previews, and connection retries. Board payloads never use the signaling socket.

References: [Compose Desktop](https://github.com/JetBrains/compose-multiplatform),
[webrtc-java](https://github.com/devopvoid/webrtc-java),
[ZXing](https://github.com/zxing/zxing).
