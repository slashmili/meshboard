# Phase 2 validation and handoff

CRDT is now the normal session implementation: Yjs/y-webrtc on web, yrs with
native WebRTC on desktop, Android and iOS. No preview switch is needed. Invite
links use `#crdt=<UUID>`; old `#room=` invites are rejected. No persisted data
needs migration. Application encryption and authenticated admission remain Phase 3.

## Automated acceptance

Run from the repository root with Node 22, pnpm 9, JDK 21, stable Rust and the
platform toolchain. Start `pnpm turn` separately before live transport tests.

| Command | Coverage |
| --- | --- |
| `pnpm test` | Rust/C ABI, Yjs/yrs binary compatibility, web document/transport, signaling and shared schemas |
| `pnpm build` | TypeScript and production web bundle |
| `MESHBOARD_TEST_BUILD=1 pnpm test:e2e` | Production browser drawing, invites, previews, clear, creator departure, refresh, session end and real TURN |
| `pnpm test:native` | Desktop drawing/UI, codecs, default CRDT factory and packaged JNI loading |
| `pnpm test:crdt:kotlin` | Checked JNI plus cross-language binary compatibility |
| `pnpm test:interop` | Two browsers/two native peers, concurrent drawing, large late-join state, creator departure/rejoin, browser/native repeated partitions, offline edits/deletions, real TURN and upstream y-webrtc |
| `pnpm test:android` | Normal Android drawing/invite UI and JNI tests |
| `pnpm test:crdt:android` | Yjs ↔ Android/JNI/yrs compatibility |
| `pnpm test:interop:android` | Normal Android/web/desktop convergence, creator departure/rejoin, native partition recovery, previews, TURN |
| `pnpm test:crdt:ios` | iOS C binding, controller and cross-language compatibility tests (Mac required) |
| `pnpm test:interop:ios` | iPad simulator/web/macOS live CRDT scenarios (Mac required) |
| `pnpm build:ios:device` | Unsigned device compilation (Mac required) |

The `*:crdt` launch/interop aliases remain for convenience; they no longer select
a different protocol. Interop fixtures use port 5174 to isolate test services;
normal apps default to port 5173. Tests use normal controllers and bundled JNI
resources, not a separately supplied desktop native library.

## Results on Linux — 2026-11-04

- Passed: production build; 25 shared-schema, 15 signaling, 30 web, five Rust and
  seven Rust/Yjs compatibility tests.
- Passed: all eight production browser E2E scenarios, including actual TURN.
- Passed: 29 desktop model/UI/JNI tests, six checked-JNI tests and seven Kotlin/Yjs
  compatibility scenarios. Packaged Linux launcher loads yrs successfully.
- Passed: all nine browser/desktop live scenarios, including four peers and repeated
  browser/native offline-edit/deletion recovery without reopening the board.
- Passed: six Android tablet UI tests, six Android JNI tests, seven Android/Yjs
  compatibility scenarios and all seven Android/web/desktop live scenarios.
- Passed: Android debug/release and test APK compilation; both app APKs contain yrs
  for exactly ARM64 and x86_64. Android release supports these two 64-bit ABIs.
- Passed: browser/interop TypeScript checks and six release/Xcode-script tests.
- Passed: local deployment HTTPS/WSS, temporary TURN credentials, relay permissions
  and bidirectional TURN UDP/TLS traffic. Real production browser pages also drew
  in both directions and restored a late joiner after the creator left, through
  Caddy HTTPS/WSS with production RTC configuration. Public deployment/DNS were not contacted.

No test retries were used to conceal failures. Migration-related failures were
fixed and the affected suites rerun. Apple execution is deferred below.

## Apple checklist — deferred to the user's Mac

These are pending checks, not claimed passes. Earlier preview CI results do not
validate the current migration. Nothing was pushed or sent to GitHub CI.

- [ ] Install Rust targets: `rustup target add aarch64-apple-ios aarch64-apple-ios-sim`.
- [ ] Run `pnpm test:native`, `pnpm test:crdt:kotlin`, and `pnpm test:interop` on macOS.
- [ ] Run `pnpm test:crdt:ios` and `pnpm test:interop:ios` with TURN running.
- [ ] Run `pnpm build:ios:device`; build both Debug and Release in Xcode.
- [ ] Build a DMG (`./packages/native/gradlew -p packages/native packageDmg`),
  launch the packaged app, and verify `--print-build-info` prints `Sync: Yjs/yrs`.
- [ ] On physical iPad, use a reachable HTTPS/WSS/TURN deployment of this revision.
  Create from iPad and from web; join four peers, draw concurrently, erase, leave
  from the creator, and rejoin while another peer stays online.
- [ ] Disconnect/reconnect the iPad network while retaining the open app; edit on
  both sides, then verify convergence and that deleted strokes stay deleted.
- [ ] Rotate, background/foreground, and check touch/Pencil drawing. Pressure-based
  widths are Phase 4; process death does not preserve a local session.
- [ ] Check restrictive-network/TURN operation on physical devices.

## Other hardware checks

Windows desktop runtime, Intel Mac runtime, physical Android ARM64 execution and
physical-device restrictive networks need hardware validation. The corresponding
platform builds use the same CRDT protocol, but cross-compilation is not execution.

## Limits

Full mesh is capped at eight participants. State is in memory only; leaving or
closing the last peer ends the session. Reconnection retains an open replica and
exchanges CRDT differences; a refreshed/restarted app needs a live peer to restore
state. No disk recovery, export/import or application-layer encryption is added.

Tests interrupt sockets/data channels deterministically. They do not establish
recovery from every silent packet-loss pattern, OS suspension or device failure.
The document and awareness resource limits remain documented in
[CRDT protocol notes](crdt-preview.md).
