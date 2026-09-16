# Delivery checkpoints

Pause for the user to try the app after each runnable checkpoint. Do not continue
into the next checkpoint until the user provides feedback or says to continue.
Keep [AGENTS.md](../AGENTS.md) as the product specification.

## Working decisions

- Keep Kotlin Multiplatform and Compose Multiplatform. Desktop uses the JVM with
  a bundled Java runtime; iOS uses Kotlin/Native with a SwiftUI/Xcode host.
- Prioritize macOS and iPad/iPhone before Windows. A Windows VM on Linux can be
  used for later validation; do not mark Phase 1 complete before required
  platforms and connectivity have been validated.
- Preserve the **Meshboard** display name and supplied `icons/` artwork.
- Preserve the compact mobile header: logo/name/status, Join/Share, and Clear/Help
  in the overflow menu. Do not restore a separate tall branding bar.
- Use the landscape Pixel Tablet for Android testing. Keep all Android commands
  in `scripts/android.mjs`, including emulator management.

Setup and machine-switching guidance live in the [root README](../README.md),
[desktop README](../packages/native/README.md), [Android README](../packages/native/androidApp/README.md),
and [iOS README](../packages/native/iosApp/README.md).

## Phase 1

1. **Local web canvas — accepted.** Pen, whole-object eraser, shapes,
   colors, widths, pan/zoom, mouse/pen/touch. Validate drawing feel and layout.
2. **Two web peers — accepted.** Introduce the signaling relay and naive WebRTC data-channel
   drawing sync, joining via link/QR, and TURN configuration plus a test that
   forces relay-only connections. Confirm that actual board payloads never use
   signaling. Label the security status accurately before Phase 3; do not claim
   application encryption or authenticated invitations yet.
3. **Web ↔ native desktop — accepted.** KMP with Compose Desktop on the
   JVM is the approved stack; the desktop bundle includes a Java runtime.
   Set up KMP/Compose and native WebRTC. Define and test
   a versioned wire format against the web implementation. Test a real session
   between the web app and Linux desktop with TURN fallback, with both web
   and native taking turns creating the session. Any platform can create a board.
4. **Native platform coverage.** Bring the same session to Android, iOS,
   macOS, and Windows, with device/build validation on each platform. Split
   this checkpoint by platform where useful. This completes Phase 1 only once
   all its platform and connectivity requirements are met.

   **4a — Android local canvas, accepted.** Share the Kotlin drawing
   model and wire codec; add a touch layout with pen, shapes, whole-object erasing,
   color/width controls, two-finger pan/zoom, and clear confirmation. Keep the board
   in a ViewModel across screen recreation. Build and test on a local Android 15
   emulator, then pause for drawing feedback. No Android sharing yet.

   **4b — Android peer connections, sharing accepted.** Android libwebrtc and
   signaling, link/QR creation, joining by pasted invite, and web/desktop full-mesh
   interoperability. Emulator tests cover bidirectional drawing/previews/erasing,
   large snapshots, creator departure/rejoin, and actual relay-only TURN traffic.
   The compact Android header with Clear/Help in an overflow menu and supplied
   Meshboard branding is implemented. Five touch/header/invite UI tests passed
   on the Linux-hosted tablet emulator; explicit Android visual feedback remains pending. QR camera
   scanning/deep links and physical-device validation remain pending.
   Use the landscape Pixel Tablet emulator (`pnpm emulator:android`) for Android
   testing by default, as requested by the user; retain the phone profile for
   optional phone-specific checks.

   **4c — Apple platforms, basic iPad/web sharing accepted.** macOS and
   iPad local drawing are accepted. Physical Apple/Android testing and Windows
   validation remain separate steps.
   Apple Silicon now builds a branded macOS app with a bundled Java runtime.
   The initial Mac validation passed nine native tests and two direct sharing
   tests on Apple Silicon/macOS 26.5.2 with Temurin 21, including
   large snapshots, reconnect, and creator departure. The packaged app and DMG build successfully; the app launches.
   macOS feedback accepted; continued to the iPad local-canvas checkpoint.
   iPad now has a SwiftUI/Xcode host and ARM64 Kotlin framework with the shared
   touch UI. The iPad Air simulator runs it; five iOS model tests and ten desktop
   tests pass. Drawing, rectangle, erasing, zoom/reset, rotation, and Clear were
   checked in the simulator. Local iPad drawing accepted by the user.
   **iOS sharing accepted for basic iPad/web use:** native WebRTC/URLSession with
   link/QR, full mesh, snapshots, previews, and reconnect. Four interop tests pass
   across iOS simulator/web/macOS, including real forced TURN and creator departure.
   Eight iOS tests and ten desktop tests pass; the unsigned device app builds.
   User confirmed real iPad ↔ web sharing works, and macOS works with a local
   server (2026-09-16). These manual checks do not establish pressure-sensitive
   Pencil behavior, forced TURN on a physical device, or an iPhone check.
   Intel execution and distribution signing remain pending.
   See [iPad setup](../packages/native/iosApp/README.md).

## Next steps

1. Validate the Android CRDT binding checkpoint (2e below), then add live Android
   CRDT sharing. Keep iOS bindings and protocol integration as separate checkpoints.
2. Follow up with iPhone, Pencil, and physical-device restrictive-network/TURN
   checks. Basic iPad/web and local-server macOS sharing are user-validated.
3. Complete the remaining Android physical-device, Intel Mac, and Windows checks.
   Phase 1's complete platform-validation milestone remains open.

Default/released sync is custom v1, not Yjs/yrs or y-webrtc compatible. There is no
application-layer encryption, authenticated admission, save/export, persistence,
undo, or pressure-sensitive stroke width yet.

## Phase 2

Integrate Yjs/yrs with explicit cross-language fixtures. Validate compatibility
with y-webrtc's signaling and data-channel protocols, full mesh, late joining,
reconnection, convergence, and creator departure with at least four mixed peers.
Pause after each testable increment.

**2a — Yjs/yrs binary compatibility prototype, accepted.** User ran
`pnpm test:crdt` successfully. `packages/crdt-core` tests
full-state and incremental updates, large boards, concurrent replacements,
deletions, reordered/duplicate updates, and late joining without the creator.
Run `pnpm test:crdt`; see its README for scope and candidate document format.
This is a non-UI checkpoint, not a live app migration: mobile bindings, y-webrtc
protocol integration, and four-device validation are still pending.

**2b — Desktop Kotlin/JNI boundary ready for feedback.** Common `CrdtBoard` API,
opt-in JVM wrapper, and a Rust shared library with opaque handle ownership.
`pnpm test:crdt:kotlin` runs six lifecycle/validation/concurrency tests and the
same seven Yjs scenarios through Kotlin/JNI. Verified locally on Linux; macOS
is wired into CI but not locally validated. Normal app builds and live sync are
unchanged in this checkpoint. Mac validation is deferred until the user returns
to that machine. The local web/desktop follow-up is recorded in 2c below;
Android/iOS bindings and final protocol migration remain pending.

**2c — Local live web/desktop CRDT preview accepted on Linux.** User confirmed
local browser/desktop drawing works. Mac validation remains pending. User approved
continuing on Linux while Mac validation waits until tomorrow. Opt-in browser
and desktop modes exchange Yjs/yrs state vectors and updates over a separately
named channel and development-only signaling namespace. Invalid updates are
staged and validated before committing. Linux live tests cover four mixed
browser/desktop peers, large state, concurrent edits, creator departure/rejoin,
reload recovery, forced TURN, and protocol isolation. See
[CRDT preview instructions](crdt-preview.md). This is an intermediate adapter
using the existing transport, **not y-webrtc protocol compatibility**. Default
and packaged apps still use Phase 1 sync; mobile bindings and broader security
and performance work remain pending. Superseded in the active preview by 2d.

**2d — y-webrtc web/desktop interoperability accepted on Linux; CI passed.** The browser
uses pinned upstream y-webrtc; desktop implements its unencrypted signaling,
binary sync and awareness profile. A separate development-only signaling endpoint
keeps released sessions unchanged. Large board messages use an explicitly
negotiated Meshboard fragmentation extension, not a claimed upstream feature.
Linux tests cover four mixed peers, large snapshots, creator departure/rejoin,
refresh recovery, TURN, an unmodified upstream provider exchanging small raw
sync/awareness messages with desktop, and rejection of invalid incoming updates.
Codec fixtures, fragment limits, awareness expiry and signaling isolation/caps
also pass. User confirmed all CI checks passed on Linux and both macOS runners
after the four-peer convergence deadline/readiness fix. Manual Mac preview
validation remains pending; Android/iOS still use v1.
See [preview instructions and protocol limits](crdt-preview.md). Pause for feedback.

**2e — Android CRDT JNI boundary ready for feedback.** Opt-in debug packaging
reuses the desktop Kotlin/JNI wrapper and Rust core for x86_64 and ARM64 Android.
`pnpm test:crdt:android` runs the six JNI tests on the tablet and all seven existing
Yjs scenarios through Android, including a 12,000-point stroke and deletion-only
sync. Android CI runs the same checks. No new Android UI or live CRDT transport
is enabled; ordinary and released apps remain on v1 without the Rust library.
See [Android checkpoint instructions](../packages/native/androidApp/README.md#opt-in-android-crdt-bindings--checkpoint-2e).
Physical ARM64 execution and iOS bindings remain pending. Pause before adding
the opt-in Android live-sharing preview.

## Phase 3

Implement application-layer AES-256-GCM, secret-bearing URL fragments/QR codes,
authenticated peer admission, and the agreed certificate verification design.
Verify tampered messages and wrong-key joins fail. Inspect signaling/TURN
traffic and confirm that secrets and recoverable board content are absent.

Before implementation, resolve how a creator fingerprint carried in a static
invitation authenticates additional peers and late joins after the creator
leaves. Each peer has its own certificate; one creator fingerprint cannot
directly pin every mesh connection. Document a design satisfying the intended
trust model and creator-independent operation, including native feasibility,
before treating the requirement as met.

## Phase 4

Expand tools, add CRDT-aware undo/redo, theme support, export/import, and
presence. Continue to offer small runnable checkpoints for user review.
