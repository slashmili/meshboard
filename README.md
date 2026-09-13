# Meshboard

A session-only whiteboard, working toward peer-to-peer collaboration on web,
mobile, and desktop. Product requirements live in [AGENTS.md](AGENTS.md).

## Current checkpoint: 01 — local web drawing

This is an early part of Phase 1, **not** the Phase 1 exit milestone. You can
draw with a pen, erase whole objects, create rectangles/ellipses/lines, choose
colors and stroke widths, and pan/zoom. Mouse, pen, and touch input are supported.

Drawing exists only in the current tab's memory. Refreshing or closing the tab
discards it. There is no sharing, synchronization, encryption, export, undo,
account, analytics, or board storage yet. No board data is sent over the network.

## Run

Use Node 22.12+ and pnpm 9 (configured in the existing `mise.toml`). Only the
web tools are needed for this checkpoint; Java/Kotlin/Rust can wait.

```sh
mise install node pnpm
pnpm install
pnpm dev
```

Open **http://127.0.0.1:5173**. The server binds to loopback by default.

```sh
pnpm check       # TypeScript
pnpm test        # Geometry and eraser regression tests
pnpm build      # TypeScript + production bundle
pnpm test:e2e   # Browser interaction tests (install Chromium first, see below)
```

To install the test browser: `pnpm --filter @meshboard/web exec playwright install chromium`.
On Linux, the browser also needs its normal system libraries. A compatible
existing Chromium can be selected with `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH`.

## Try this checkpoint

1. Draw a stroke and a single dot. Try the different colors and widths.
2. Draw a rectangle, ellipse, and line. Hold Shift for squares, circles, and
   lines snapped to 45-degree angles.
3. Select the eraser and cross an outline. The whole object disappears.
4. Use the hand tool, hold Space and drag, or scroll to pan. Ctrl/⌘ + scroll
   zooms around the pointer; the +/− buttons zoom around the canvas center.
   On a touch screen, use two fingers to pan/pinch. Reset view returns to
   the original position and 100% zoom.
5. Open Clear board, cancel once, then confirm. Refresh and check that the
   canvas is empty. Another tab has an independent board at this stage.

Keyboard tools: P pen, E eraser, R rectangle, O ellipse, L line, H hand.
Escape cancels an unfinished stroke or shape. The help button lists controls.

## Project structure

```text
packages/web/               React + TypeScript + Vite
  src/board.ts              Plain drawing data, geometry, and viewport math
  src/useBoard.ts           In-memory state and pointer gestures
  src/Element.tsx           SVG rendering
  src/App.tsx               Whiteboard interface
  e2e/                     Browser interaction tests
docs/checkpoints.md         Incremental delivery and manual review gates
```

Later checkpoints introduce `packages/native`, `packages/crdt-core`,
`packages/signaling`, and `packages/shared-protocol` as their implementations
begin. The web drawing data is plain TypeScript data; it is not yet a versioned
wire protocol or native interchange format.

Tooling references: [Vite guide](https://vite.dev/guide/) and
[React documentation](https://react.dev/learn).
