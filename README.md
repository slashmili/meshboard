# Meshboard

A session-only whiteboard, working toward peer-to-peer collaboration on web,
mobile, and desktop. Product requirements live in [AGENTS.md](AGENTS.md).

## Current checkpoint: 02 — shared web boards

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
Use test drawings. Yjs/yrs, native apps, export, undo, and production security are
still pending. There are no accounts, analytics, or board persistence.

## Run

Use Node 22.12+ and pnpm 9 (configured in the existing `mise.toml`). Only the
web tools are needed for this checkpoint; Java/Kotlin/Rust can wait.

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

```sh
pnpm check       # TypeScript
pnpm test        # Protocol, signaling, document, framing, and geometry tests
pnpm build      # TypeScript + production bundle
pnpm test:e2e    # Browser tests, including real relay-only connections; run pnpm turn first
```

To install the test browser: `pnpm --filter @meshboard/web exec playwright install chromium`.
On Linux, the browser also needs its normal system libraries. A compatible
existing Chromium can be selected with `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH`.

## Try this checkpoint

1. Draw something, select **Share**, and copy the invite into another tab/browser.
2. Wait for **1 peer connected**. The second peer should see your existing drawing.
3. Draw from both sides. Unfinished strokes appear faintly on the other peer,
   then become solid when finished. Try shapes and erasing too.
4. Clear the board from either peer. This removes the objects currently visible
   to that peer for everyone. A concurrently drawn, unseen object may survive.
5. Join a third tab and close the creator's tab. The remaining two can keep drawing.
   Refresh one while the other stays open; its drawing should return.
6. Leave every shared tab, then reopen the invite. The board should be empty.

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

The next checkpoint introduces `packages/native` and a web ↔ native desktop
session. See [protocol notes](packages/shared-protocol/README.md).

Tooling references: [Vite guide](https://vite.dev/guide/) and
[React documentation](https://react.dev/learn).
Transport references: [WebRTC TURN setup](https://webrtc.org/getting-started/turn-server)
and [Coturn configuration](https://github.com/coturn/coturn/blob/master/examples/etc/turnserver.conf).
