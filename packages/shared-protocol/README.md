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
