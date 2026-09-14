# Delivery checkpoints

Pause for the user to try the app after each runnable checkpoint. Do not continue
into the next checkpoint until the user provides feedback or says to continue.
Keep [AGENTS.md](../AGENTS.md) as the product specification.

## Phase 1

1. **Local web canvas — accepted.** Pen, whole-object eraser, shapes,
   colors, widths, pan/zoom, mouse/pen/touch. Validate drawing feel and layout.
2. **Two web peers — current checkpoint.** Introduce the signaling relay and naive WebRTC data-channel
   drawing sync, joining via link/QR, and TURN configuration plus a test that
   forces relay-only connections. Confirm that actual board payloads never use
   signaling. Label the security status accurately before Phase 3; do not claim
   application encryption or authenticated invitations yet.
3. **Web ↔ native desktop.** Set up KMP/Compose and native WebRTC. Define and test
   a versioned wire format against the web implementation. Test a real session
   between the web app and Linux desktop with TURN fallback, with both web
   and native taking turns creating the session. Any platform can create a board.
4. **Native platform coverage.** Bring the same session to Android, iOS,
   macOS, and Windows, with device/build validation on each platform. Split
   this checkpoint by platform where useful. This completes Phase 1 only once
   all its platform and connectivity requirements are met.

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
