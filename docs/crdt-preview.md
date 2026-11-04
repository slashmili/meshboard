# CRDT session protocol

This was previously the opt-in preview guide. CRDT is now the default on every
platform; use `pnpm dev`, `pnpm native`, `pnpm android` or `pnpm ios`. Stable Rust
is required by native builds. No preview flag or local-only restriction remains;
remote devices require HTTPS/WSS and reachable TURN.

For validation commands and deferred hardware checks, see
[Phase 2 validation](phase-2-validation.md). Phase 3 encryption/authentication is
not implemented. Use test drawings.

## Protocol profile

- Invitations: `https://your-board.example/#crdt=<UUID>` instead of `#room=…`.
- Signaling: `/signal-y-webrtc`, topics `meshboard-crdt:<UUID>`. Available in development and production; `/signal` and `/signal-crdt` are retired.
  Supports y-webrtc subscribe/unsubscribe, announce, signal and ping/pong.
  The relay accepts only SDP/ICE and discovery metadata, binds sender IDs to a
  socket, caps each topic at eight participants, and limits size/rate/backlog.
- Sync: standard binary lib0/y-protocols messages: outer type 0, with inner
  step 0 (state vector), 1 (state response), or 2 (update). Delete-only differences
  synchronize even when state vectors have not advanced.
- Awareness: standard outer types 1 (update) and 3 (query), including clocks,
  removal, periodic refresh and expiry. The application field
  `meshboard: {peerId, preview}` carries ephemeral drawing previews, not CRDT state.
  Previews above 48 KiB are omitted; the completed stroke still synchronizes.
- Full mesh: all browser peers use WebRTC, with same-browser BroadcastChannel
  synchronization disabled after provider initialization. TURN settings come from
  the same-origin `/api/rtc-config`, not upstream/public signaling or relay defaults.

### Large-message extension

Upstream y-webrtc sends a whole update in one data-channel message. That can exceed
SCTP limits for Meshboard's large atomic strokes/snapshots. Meshboard peers opt in
to fragmentation through the exact channel label `meshboard.y-webrtc.fragments-v1`
**and both peers advertising** the session-level SDP attribute
`a=meshboard-fragments:1`. A label alone is not consent: upstream providers can
accept an arbitrary channel name without understanding its extensions. Without
both capabilities, messages remain ordinary binary y-webrtc, with no envelope.

On that negotiated channel, messages over 16,379 bytes are split into at most
16 KiB frames: byte `127`, a four-byte big-endian total message length, then payload.
Ordered/reliable delivery, one in-flight assembly, a 30-second assembly deadline,
and a 2 MiB + 1 KiB message ceiling bound reassembly. Small messages stay unchanged.
Send queues are limited to 8 MiB per peer and honor channel backpressure.

This fragmentation is a **Meshboard extension**, not part of y-webrtc. The upstream
interop test deliberately uses small raw messages and an unmodified provider.
Large-board support with arbitrary third-party providers is not claimed. Password-
encrypted signaling, broadcast-channel peer discovery, application-layer security are outside this checkpoint. The pinned provider's
per-peer hooks are covered by the live tests; review them when upgrading y-webrtc.
Android creates ordered/reliable channels, but its current Java WebRTC API does
not expose reliability settings for received channels. The transport relies on
the matching peers' defaults; hostile DCEP/channel configuration needs further
hardening in the security phase.

## Validation and limits

Both implementations stage mutations on a separate CRDT document, validate the
result, and commit only if valid. Tests cover invalid elements, mismatched IDs,
foreign roots, stale snapshots, malformed updates and oversized input. This
preserves a healthy visible board after rejected input. Shape fields reuse the
existing schema; elements must remain atomic JSON, not shared nested types.

The session caps binary document/update size and visible JSON at 2 MiB and uses
the existing 2,000-element/12,000-point limits. Native calls still have a separate
4 MiB JNI copy cap. Cloning for validation costs time proportional to document
size; mobile and larger-board performance need validation before wider use.
Awareness is bounded to 64 client identities per session and 64 KiB JSON
per client. Very long sessions with repeated browser reloads need a new board
once that identity budget is exhausted; this remains a session limitation.

These checks are **not a complete hostile-input security boundary**: CRDT binary
decoder CPU/allocation behavior still needs fuzzing and isolation/resource-budget
work. Application-layer encryption and authenticated admission remain Phase 3.
