# Development

See the [support matrix](support.md) for browser/native version floors, required
capabilities and boundary checks. CI and simulator coverage are listed separately
from physical-device acceptance. Native mobile releases target tablets only.


Transfer committed source changes using your chosen Git workflow before switching
machines. Read [AGENTS.md](../AGENTS.md), the assigned Linear issue, and the relevant platform guide. Record validation and remaining checks in Linear.

Keep toolchains and generated files local: do not copy `node_modules`, Gradle or
Kotlin caches, native build outputs, `.android-sdk`, or `.android-avd` between
Linux and macOS. Install the pinned tools with `mise install node pnpm java`, use
the checked-in Gradle wrapper, and run `pnpm install --frozen-lockfile`. A separate
system Gradle/Kotlin installation is unnecessary. Stable Rust is required for native builds.
Install Playwright Chromium on each machine and do not carry over a
`PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` pointing to another machine's browser.

Keep credentials and local `.env` files out of Git. Recheck service addresses when
moving machines: localhost refers to the device using the link, not the previous
development host. Cross-device sessions need reachable HTTPS, WSS, and TURN.

## Local web development

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

Open **http://127.0.0.1:5173**. Vite also runs the signaling service at `/signal-y-webrtc`
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


## Platform guides

Use the [desktop](../packages/native/README.md), [Android](../packages/native/androidApp/README.md) or [iOS](../packages/native/iosApp/README.md) guide for native setup and controls.

## TURN and standalone signaling


Default local settings match `pnpm turn`. For a reachable TURN service, copy the
settings from [.env.example](../.env.example) into a root `.env` and restart Vite.
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
it. For hosting, serve the built web files and reverse-proxy `/signal-y-webrtc` (WebSocket)
and `/api/rtc-config` to the relay on the same origin.

For self-hosting on a Linux VPS, use the [Docker Compose deployment guide](../docs/deployment.md).
For Ubuntu 26.04 LTS, start with the [server installation steps](../infra/README.md).
It includes HTTPS, self-hosted TURN/TLS, temporary relay credentials, DNS/firewall
instructions, certificate renewal, and a deployment acceptance checklist. All
operator-specific values live in an ignored environment file; no domain, IP, or
secret needs to be committed. Deployment acceptance checks are separate from physical-device validation.

Limits: eight participants, 2,000 objects, 12,000 points per stroke segment and
2 MiB CRDT state/update size. Long strokes split into segments. Native and web
peers use the y-webrtc signaling/sync profile with a negotiated fragmentation
extension for large drawings. See [protocol notes](../docs/crdt-preview.md).

Tooling references: [Vite guide](https://vite.dev/guide/) and
[React documentation](https://react.dev/learn).
Transport references: [WebRTC TURN setup](https://webrtc.org/getting-started/turn-server)
and [Coturn configuration](https://github.com/coturn/coturn/blob/master/examples/etc/turnserver.conf).


## Licensing and distribution


Meshboard is licensed under the [Mozilla Public License 2.0](../LICENSE)
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
