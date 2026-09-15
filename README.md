# Meshboard

A session-only whiteboard, working toward peer-to-peer collaboration on web,
mobile, and desktop. Product requirements live in [AGENTS.md](AGENTS.md).

Start with [delivery checkpoints](docs/checkpoints.md) for current progress and next
steps, then use the platform guide: [desktop](packages/native/README.md),
[Android](packages/native/androidApp/README.md), or [iPad/iPhone](packages/native/iosApp/README.md).

## Current checkpoint: 04c — iOS sharing preview

Web, Linux desktop, macOS, Android emulator, and iOS simulator support shared
boards with link/QR invites, live previews, late-join snapshots, and TURN fallback.
The iOS sharing preview is ready for feedback. Physical-device networking and
Pencil validation, Intel Mac execution, and Windows validation remain pending.
See [delivery checkpoints](docs/checkpoints.md) for the validation record.

This is part of Phase 1, **not** the Phase 1 exit milestone. You can
draw with a pen, erase whole objects, create rectangles/ellipses/lines, choose
colors and stroke widths, and pan/zoom. Mouse, pen, and touch input are supported.

Use **Share** to create an invite link and QR code. Peers exchange live previews,
completed strokes, erasing, and existing board state over WebRTC data channels.
Each peer connects directly to the others; the creator has no special role.
The signaling service only handles room membership and connection metadata.

The board exists only in participant memory. Refreshing a shared board restores
it from another connected participant. Once everyone leaves, the content is gone;
opening the same link afterward starts empty. An unshared board stays local.

This is a **connection prototype**: WebRTC encrypts transport with DTLS, but
invites are not authenticated and application-layer encryption is not implemented.
Use test drawings. Yjs/yrs, export, undo, and production security are
still pending. There are no accounts, analytics, or board persistence.

## Switching between development machines

Commit and push source changes before switching, then pull them on the other
machine. Read [AGENTS.md](AGENTS.md), [delivery checkpoints](docs/checkpoints.md),
and the relevant platform README. Update the checkpoints after validation so the
next session knows what passed and what remains.

Keep toolchains and generated files local: do not copy `node_modules`, Gradle or
Kotlin caches, native build outputs, `.android-sdk`, or `.android-avd` between
Linux and macOS. Install the pinned tools with `mise install node pnpm java`, use
the checked-in Gradle wrapper, and run `pnpm install --frozen-lockfile`. A separate
system Gradle/Kotlin installation is unnecessary; Rust is for the later yrs phase.
Install Playwright Chromium on each machine and do not carry over a
`PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` pointing to another machine's browser.

Keep credentials and local `.env` files out of Git. Recheck service addresses when
moving machines: localhost refers to the device using the link, not the previous
development host. Cross-device sessions need reachable HTTPS, WSS, and TURN.

## Run

Use Node 22.12+ and pnpm 9 (configured in the existing `mise.toml`). The desktop
app additionally needs JDK 21; its Gradle wrapper downloads the build tools.

```sh
mise install node pnpm
pnpm install --frozen-lockfile
pnpm dev
```

In another terminal, start the local TURN relay (Podman/Docker on Linux, native
Coturn on macOS; see the platform guides):

```sh
pnpm turn
```

Open **http://127.0.0.1:5173**. Vite also runs the signaling service at `/signal`
and serves connection settings at `/api/rtc-config`. Both the app and local TURN
relay bind to loopback. Keep both terminals open. Ctrl+C stops each service;
`podman stop meshboard-turn-dev` also stops the development relay.

The default invite works **on this computer only**. Try two tabs, separate browser
windows, or different browsers on the same computer. A phone cannot open your
computer's `127.0.0.1` address, even via QR code. Other devices need a reachable
HTTPS app origin, WSS signaling, and a TURN server reachable by all participants.
Simply exposing HTTP on a LAN address is insufficient for secure-context browser
APIs used by this app.
The debug Android app in a local emulator translates loopback destinations to
the emulator's host alias automatically; the same invite works there too.

```sh
pnpm check       # TypeScript
pnpm test        # Protocol, signaling, document, framing, and geometry tests
pnpm build      # TypeScript + production bundle
pnpm test:e2e    # Browser tests, including real relay-only connections; run pnpm turn first
pnpm native      # Launch Compose desktop on Linux or macOS
pnpm test:native # Kotlin model, shared wire fixtures, and Compose UI tests
pnpm test:interop # Real JVM/libwebrtc ↔ Chromium tests; run pnpm turn first
pnpm android     # Build, install, and launch in the running Android emulator
pnpm test:android # Android touch and invite UI tests
pnpm test:interop:android # Android ↔ web ↔ desktop; run pnpm turn first
pnpm ios         # Build, install, and launch in an iPad simulator (macOS)
pnpm test:interop:ios # iOS ↔ web ↔ macOS, including TURN; run pnpm turn first
```

To install the test browser: `pnpm --filter @meshboard/web exec playwright install chromium`.
On Linux, the browser also needs its normal system libraries. A compatible
existing Chromium can be selected with `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH`.

## Try this checkpoint

1. Keep `pnpm dev` and `pnpm turn` running, then launch `pnpm android` with the
   emulator running. Optionally launch `pnpm native` for a desktop participant.
2. Draw in the browser, select **Share**, and copy the invite. In Android (or desktop),
   select **Join**, paste the full link, and select **Join board**.
3. Wait for **1 peer connected**. Try drawing, shapes, and erasing from both sides.
   Unfinished strokes appear faintly on the other peer, then become solid.
4. Also try creating from Android: leave the current board, draw locally, select
   **Share → Create invite**, and open its link in the browser. All three platforms can create.
5. Clear the board from either peer. This removes the objects currently visible
   to that peer for everyone. A concurrently drawn, unseen object may survive.
6. Join a third peer and close the creator. The remaining two can keep drawing.
   Refresh one while the other stays open; its drawing should return.
7. Leave every shared peer, then reopen the invite. The board should be empty.

Desktop uses **Compose on the JVM**, with native libwebrtc accessed through Java
bindings. It is not a Kotlin/Native machine-code executable. The app bundle includes
a Java runtime. Linux x86_64 and Apple Silicon macOS have been validated; Intel
Mac and Windows execution remain pending. iOS uses Kotlin/Native with a SwiftUI
host and native Apple WebRTC, validated in the simulator. Android networking is
validated on the local Android 15 x86_64 emulator, not yet on physical hardware.
See [desktop setup and architecture](packages/native/README.md).

Scroll or use the hand tool to pan. Ctrl/⌘ + scroll zooms around the pointer;
the +/− buttons zoom around the canvas center. Two-finger touch gestures pan
and zoom. Shift constrains shapes; Escape cancels a draft.

Keyboard tools: P pen, E eraser, R rectangle, O ellipse, L line, H hand.
Escape cancels an unfinished stroke or shape. The help button lists controls.

## Project structure

GitHub Actions runs the automated tests on PRs and pushes to `main`, and builds
Linux AppImage and macOS DMG assets when a release is published. See
[CI and release setup](docs/ci-and-releases.md) for required repository variables,
test coverage, and the first-release checklist.

```text
packages/web/                React + TypeScript + Vite
  src/board.ts               Drawing geometry and viewport math
  src/useBoard.ts            Pointer gestures
  src/sync/                  In-memory document, WebRTC mesh, and message framing
  src/ShareDialog.tsx        Link and QR invitations
  e2e/                      Canvas and shared-session browser tests
packages/signaling/          WebSocket membership/SDP/ICE relay; no board state
packages/shared-protocol/    Versioned Phase 1 schemas and protocol notes
  fixtures/                 Messages accepted/rejected by both TypeScript and Kotlin
packages/native/             Kotlin Multiplatform + Compose desktop (JVM)
  src/commonMain/           Drawing model, protocol, controller contract, and UI
  src/desktopMain/          Native libwebrtc transport, QR encoding, and JVM entry point
  androidApp/              Android activity, libwebrtc transport, and instrumented tests
  iosApp/                  SwiftUI/Xcode host and Apple WebRTC transport
  src/iosMain/             Kotlin/Native controller and Compose entry point
packages/web/interop/        Browser ↔ real desktop/Android/iOS transport tests
icons/                      Supplied Meshboard artwork; web public assets and native icon source
infra/turn/dev.conf          Loopback-only Coturn configuration
scripts/turn.mjs             Local TURN launcher (native Coturn on macOS, containers on Linux)
docs/checkpoints.md          Incremental delivery and manual review gates
```

## TURN and standalone signaling

Default local settings match `pnpm turn`. For a reachable TURN service, copy the
settings from [.env.example](.env.example) into a root `.env` and restart Vite.
These are TURN credentials, **not board encryption keys**. Browsers must receive
them to authenticate relay allocations; use short-lived credentials for a public
deployment. Never deploy the fixed development credentials or loopback peer policy.

For a manual relay-only check, set `MESHBOARD_RELAY_ONLY=true` in the root `.env`,
restart `pnpm dev`, and refresh both peers. The desktop status bar should say
**VIA RELAY**, and drawing should still sync. Remove the override afterward.
The browser test forces this same policy and asserts the selected ICE pair uses
relay candidates, with actual drawing traffic through Coturn. This verifies the
local fallback path, not connectivity through a real corporate firewall.

`pnpm signaling` runs the standalone relay at `127.0.0.1:4444`. It requires TURN
environment variables unless `MESHBOARD_LOCAL_DEV=true` is explicitly set. The
standalone process reads process environment; load your `.env` before launching
it. For hosting, serve the built web files and reverse-proxy `/signal` (WebSocket)
and `/api/rtc-config` to the relay on the same origin. HTTPS/WSS, public TURN
networking, and deployment are not set up in this checkpoint.

Limits: 8 participants, 2,000 objects, 10,000 deleted IDs, 12,000 points per
stroke segment, and a 4 MiB document. Long strokes split into segments. This
temporary insert/delete protocol is **not Yjs or y-webrtc compatible**; Phase 2
replaces it. Network reconnection performs a fresh state exchange, but prolonged
partitions and cross-platform convergence remain Phase 2 validation work.

The next checkpoint expands native platform coverage. See
[delivery checkpoints](docs/checkpoints.md) and [protocol notes](packages/shared-protocol/README.md).

Tooling references: [Vite guide](https://vite.dev/guide/) and
[React documentation](https://react.dev/learn).
Transport references: [WebRTC TURN setup](https://webrtc.org/getting-started/turn-server)
and [Coturn configuration](https://github.com/coturn/coturn/blob/master/examples/etc/turnserver.conf).

## License

Meshboard is licensed under the [Mozilla Public License 2.0](LICENSE)
(`MPL-2.0`). This applies to all first-party files in this repository, including
the web and native apps, signaling server, shared protocol, scripts,
configuration, documentation, and supplied artwork, unless a file explicitly
states otherwise. For files without an inline notice, this is their license
notice:

> This Source Code Form is subject to the terms of the Mozilla Public
> License, v. 2.0. If a copy of the MPL was not distributed with this
> file, You can obtain one at https://mozilla.org/MPL/2.0/.

Third-party code and dependencies retain their own licenses; this includes the
Apache-2.0-licensed Gradle wrapper. Existing copyright and license notices must
be preserved. MPL-2.0 does not grant trademark rights to the Meshboard name or logo.

Licensing notices are kept here and in the root `LICENSE`; per-file headers are
not required by this project's convention. When distributing binaries or
minified web assets, provide recipients with the license notice and a way to
obtain the matching MPL-covered source code, including any modifications.
