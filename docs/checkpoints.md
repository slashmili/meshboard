# Delivery checkpoints

Pause for the user to try the app after each runnable checkpoint. Do not continue
into the next checkpoint until the user provides feedback or says to continue.
Keep [AGENTS.md](../AGENTS.md) as the product specification.

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

   **4b — Android peer connections, current checkpoint.** Android libwebrtc and
   signaling, link/QR creation, joining by pasted invite, and web/desktop full-mesh
   interoperability. Emulator tests cover bidirectional drawing/previews/erasing,
   large snapshots, creator departure/rejoin, and actual relay-only TURN traffic.
   Sharing accepted by the user; current follow-up is a compact Android header
   with Clear/Help in an overflow menu and the supplied Meshboard branding on
   web, desktop, and Android. Pause for visual feedback. QR camera
   scanning/deep links and physical-device validation remain pending.
   Use the landscape Pixel Tablet emulator (`pnpm emulator:android`) for Android
   testing by default, as requested by the user; retain the phone profile for
   optional phone-specific checks.

   **4c — Apple platforms, macOS preview ready for feedback.** The user has a Mac and prioritizes macOS and
   iPad/iPhone over Windows. Start with macOS desktop, then iPad local drawing,
   then Apple peer connections and physical-device validation. Read the
   [Apple handoff](apple-handoff.md) before continuing in the new Mac session.
   Windows can be validated later in a Windows VM on Linux; physical Android
   testing can be a separate checkpoint. Neither is complete yet.
   Apple Silicon now builds a branded macOS app with a bundled Java runtime.
   Nine native tests and two direct web/macOS sharing tests pass, including
   large snapshots, reconnect, and creator departure. The packaged app launches.
   macOS feedback accepted; continued to the iPad local-canvas checkpoint.
   iPad now has a SwiftUI/Xcode host and ARM64 Kotlin framework with the shared
   touch UI. The iPad Air simulator runs it; five iOS model tests and ten desktop
   tests pass. Drawing, rectangle, erasing, zoom/reset, rotation, and Clear were
   checked in the simulator. Pause for iPad drawing feedback. iOS networking,
   physical-device/Pencil validation, Mac TURN, Intel execution, and distribution
   signing remain pending. See [iPad setup](../packages/native/iosApp/README.md).

## Phase 2

Integrate Yjs/yrs with explicit cross-language fixtures. Validate compatibility
with y-webrtc's signaling and data-channel protocols, full mesh, late joining,
reconnection, convergence, and creator departure with at least four mixed peers.
Pause after each testable increment.

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
