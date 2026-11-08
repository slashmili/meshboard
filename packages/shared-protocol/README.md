# Shared drawing schemas

`src/index.ts` defines board-element validation, geometry limits, RTC configuration
and SDP/ICE metadata validation shared by the browser and signaling service.
Kotlin's counterpart is `packages/native/src/commonMain/kotlin/meshboard/Wire.kt`.

Normal sessions use **Yjs/yrs binary updates and y-webrtc signaling/awareness**.
The active protocol is documented in [CRDT session protocol](../../docs/crdt-preview.md).
The old JSON board-message schemas and fixtures remain useful for local model and
validation tests; no app controller or signaling endpoint uses them to synchronize
boards. `/signal` and `/signal-crdt` have been retired.

Invites use `https://your-app.example/#crdt=<UUID>&key=<base64url-secret>`.
The UUID identifies the session; the secret authenticates membership and derives
directional encryption keys. Neither the secret nor board content reaches signaling.
See [the secure-v1 handshake](../../docs/phase-3-security.md).

Every peer uses `/api/rtc-config` for STUN/TURN settings and `/signal-y-webrtc` for
metadata-only discovery/SDP/ICE. Board updates and ephemeral drawing previews use
WebRTC data channels. Native binary codecs are in `YWire.kt`; browser transport
validation and encrypted fragmentation are in `YWebrtcSession.ts` and `YTransport.ts`.

See [Phase 2 validation](../../docs/phase-2-validation.md) for cross-language fixtures,
real WebRTC/TURN tests, supported limits and pending platform checks.

## Text and sticky-note elements

The element types `text` and `sticky` retain `id`, `color`, `width` and two
`points`, and require an additional `text` string (1–500 UTF-16 code units,
well-formed Unicode). Other element types must omit `text`; unknown fields and
null text are rejected. `text-elements.json` contains shared TypeScript/Kotlin/Rust
validation fixtures and TypeScript/Kotlin wrapping cases.

The editor creates 288-unit-wide boxes, with at least 72 units of height. Explicit
newlines are preserved (CRLF/CR normalized for display); lines wrap after 24 units,
counting each code point above U+00FF as two units and other code points as one.
Surrogate pairs never split. Height grows with the number of wrapped lines.
Render at 18 units with 24-unit line spacing and 12-unit padding; notes have a
pale yellow background. Text is plain content, never HTML or executable markup.

Edits replace one atomic CRDT element under its existing ID. Concurrent edits to
the same box resolve using Yjs/yrs map conflict resolution; this is not character-
level collaborative editing. An editor whose object has been deleted cannot save
and resurrect it. Text and notes use the existing encrypted update path, validation
staging and document size limits. Older builds that lack these element types reject
them; participants should use the same current build.

## Arrow elements

`arrow` uses the standard color/width and exactly two points (tail, tip), without
`text`. Render a shaft plus two open wings: head length is
`min(endpoint distance / 2, max(12, width * 3))`, wing half-width is half that
length. A zero-length arrow has no wings. Rendering and hit testing use this same
outline. Endpoints are absolute board coordinates, not attachments to other objects.
