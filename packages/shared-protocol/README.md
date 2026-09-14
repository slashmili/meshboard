# Phase 1 protocol v1

This is the temporary, JSON-based connection-validation protocol. It is not
compatible with Yjs updates or y-webrtc signaling/data-channel messages. Phase 2
must replace it with Yjs/yrs and validate y-webrtc compatibility. Every current
message and frame carries `v: 1`; unknown versions and extra fields are rejected.
Executable schemas and limits are in `src/index.ts`.

## Invitations

An invitation is `https://app.example/#room=<random UUID v4>`. This room ID is
sent to signaling to discover peers. It is not an encryption key, and no key or
certificate fingerprint is generated at this stage. Any browser with the link
can join; there is no access revocation or participant identity verification.

## Signaling: `/signal`

Client → server:

- `join`: `{v:1, type:"join", room}`. Only one join per socket.
- `signal`: `{v:1, type:"signal", to, payload}`. Payload contains either
  `{description:{type:"offer"|"answer", sdp}}` or `{candidate:{candidate,
  sdpMid, sdpMLineIndex, usernameFragment?}}`.

Server → client:

- `welcome`: `{v:1, type:"welcome", self, peers}`. Server-assigned peer UUID
  and existing room participants.
- `peer-joined` / `peer-left`: `{v:1, type, peer}`.
- `signal`: `{v:1, type:"signal", from, payload}`. Sender is bound to the
  originating socket, and recipients must belong to the same room.
- `error`: `{v:1, type:"error", code}`; policy errors close the socket.

The lower peer UUID initiates each WebRTC connection to avoid offer glare.
Each pair uses one ordered, reliable channel named `meshboard.v1`. ICE candidates
received before a remote description are queued. TURN configuration is fetched
from `/api/rtc-config` with caching disabled. The standalone service refuses to
start without TURN settings unless local development is explicitly enabled.

The server stores socket membership only, removes empty rooms, and logs no
message payloads. It rejects unknown message types, extra fields, oversized
messages, cross-room routing, room switching, and excess participants. Basic
connection/message limits and heartbeat cleanup are included; this is not a
fully hardened public service. An SDP string remains connection metadata supplied
by a client; schema checks cannot prevent a malicious client from encoding other
information in that string. The application itself never sends board data here.

## Drawing: WebRTC data channel only

- `put`: an immutable completed `element` (ID, type, color, width, points).
- `remove`: an array of object `ids`.
- `snapshot`: current `elements` plus `removed` IDs, exchanged symmetrically
  whenever a data channel opens. No creator or elected host supplies state.
- `preview`: an unfinished `element`, or `null` to cancel. Sent at most every
  50 ms while drawing; never included in snapshots.

The client merges immutable object IDs and retains deleted IDs to avoid reviving
erased strokes from a stale snapshot. Clear removes the IDs currently visible
to its author, so concurrent unseen strokes survive. Drawing order follows IDs
(locally generated with a timestamp prefix and random UUID). This small
insert/delete store has no text editing, general conflict handling, or CRDT undo.

Each JSON message is split into sequential frames `{v:1, id, index, total, data}`
with at most 8,000 characters per chunk and a maximum 16 KiB frame. Messages
cannot interleave on a channel. The receiver validates sequence, size, and schema.
Outgoing queues are bounded and pause as the WebRTC buffered amount grows.
Invalid input closes that peer connection. Snapshots and deletes remain in memory
only and are never sent to signaling or stored on a server.

TURN forwards DTLS-encrypted transport packets. Application-layer AES-GCM and
fingerprint-based authentication remain Phase 3 work, so this protocol does not
yet protect against a compromised signaling service admitting an attacker.
