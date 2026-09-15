# Meshboard

A session-only whiteboard, working toward peer-to-peer collaboration on web,
mobile, and desktop. Product requirements live in [AGENTS.md](AGENTS.md).

## Current checkpoint: 04b — Android peer connections

The Android local canvas is accepted. Android now creates and joins shared boards
with web and Linux desktop, including link/QR creation, live drawing previews,
late-join snapshots, and TURN fallback. Its touch canvas retains pen, shapes,
eraser, colors/widths, and two-finger pan/zoom.
See [Android setup and controls](packages/native/androidApp/README.md).

With the local Android emulator running, use `pnpm android` to build, install,
and open the app. `pnpm test:android` runs its emulator tests. The existing web and
desktop sharing workflow below continues to work.

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
Use test drawings. Yjs/yrs, iOS, export, undo, and production security are
still pending. There are no accounts, analytics, or board persistence.

## Run

Use Node 22.12+ and pnpm 9 (configured in the existing `mise.toml`). The desktop
app additionally needs JDK 21; its Gradle wrapper downloads the build tools.

```sh
mise install node pnpm
pnpm install
pnpm dev
```

In another terminal, start the local TURN relay (Podman or Docker required):

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
pnpm native      # Launch the Kotlin Multiplatform / Compose desktop app (Linux x86_64)
pnpm test:native # Kotlin model, shared wire fixtures, and Compose UI tests
pnpm test:interop # Real JVM/libwebrtc ↔ Chromium tests; run pnpm turn first
pnpm android     # Build, install, and launch in the running Android emulator
pnpm test:android # Android touch and invite UI tests
pnpm test:interop:android # Android ↔ web ↔ desktop; run pnpm turn first
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
a Java runtime. Only Linux x86_64 is wired and validated at this checkpoint;
macOS, Windows, and iOS remain later platform checkpoints. Android networking is
validated on the local Android 15 x86_64 emulator, not yet on physical hardware.
See [desktop setup and architecture](packages/native/README.md).

Scroll or use the hand tool to pan. Ctrl/⌘ + scroll zooms around the pointer;
the +/− buttons zoom around the canvas center. Two-finger touch gestures pan
and zoom. Shift constrains shapes; Escape cancels a draft.

Keyboard tools: P pen, E eraser, R rectangle, O ellipse, L line, H hand.
Escape cancels an unfinished stroke or shape. The help button lists controls.

## Project structure

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
packages/web/interop/        Browser ↔ real desktop/Android transport tests
icons/                      Supplied Meshboard artwork; web public assets and native icon source
infra/turn/dev.conf          Loopback-only Coturn configuration
scripts/turn.mjs             Local TURN container launcher
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
