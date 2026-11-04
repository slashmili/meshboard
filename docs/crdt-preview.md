# CRDT session protocol

This was previously the opt-in preview guide. CRDT is now the default on every
platform; use `pnpm dev`, `pnpm native`, `pnpm android` or `pnpm ios`. Stable Rust
is required by native builds. No preview flag or local-only restriction remains;
remote devices require HTTPS/WSS and reachable TURN.

For validation commands and deferred hardware checks, see
[Phase 2 validation](phase-2-validation.md) and the active
[Phase 3 security protocol and validation ledger](phase-3-security.md).

## Protocol profile

- Invitations: `https://your-board.example/#crdt=<UUID>&key=<base64url-secret>` instead of `#room=…`.
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

The only data-channel label is `meshboard.secure-v1`. Both peers must complete
invite-secret authentication before exchanging sync or awareness. Plain upstream
y-webrtc peers are rejected. The pinned provider's per-peer hooks are covered by
live tests; review them when upgrading y-webrtc.

Messages over 16,379 bytes are split into at most 16 KiB clear frames: byte `127`,
a four-byte big-endian total message length, then payload. Each frame is encrypted
and authenticated separately. Decrypt before assembly. Ordered delivery, a
30-second assembly deadline, and a 2 MiB + 1 KiB message ceiling bound reassembly.
Send and pending input queues are limited to 8 MiB per peer. Sequence enforcement
rejects reordered, missing or replayed records even if a hostile remote requests
an unreliable channel. There is no negotiated plaintext fallback.

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
work. Authenticated members can still submit hostile CRDT data; encryption does
not make authorized input inherently safe.
