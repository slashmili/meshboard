# Phase 3 security design and validation

Status: encryption primitives implemented for Web Crypto and JVM (desktop/Android
source set). **Live sessions still use Phase 2 and are not application-layer
encrypted.** The authentication design was approved on 2026-11-04. Apple implementation,
integration, and live security validation remain outstanding.

## Trust model and design decision

The invitation is a bearer capability. Any recipient can read and modify the whole
board and invite others. The signaling and TURN services are untrusted for board
confidentiality and peer authentication. They can still deny service and observe
network/session metadata. A compromised endpoint or web origin serving modified
JavaScript is outside this protection: that code can read the invitation secret.

The original requirement pins the creator's DTLS certificate in the invitation.
That pin cannot authenticate an independently generated certificate belonging to
another peer. It also cannot authenticate a late join when the creator is offline.
Browsers/native WebRTC normally create certificates per connection; a room ID is
not an identity credential.

Approved replacement (2026-11-04): authenticate **every pair of
DTLS fingerprints** using the 256-bit invitation secret. Do not substitute a hash
of the key and call it a DTLS fingerprint. The QR/link carries the secret in its
fragment, and that secret is the out-of-band trust anchor. Certificate fingerprints
may travel in SDP, but SDP alone is never trusted for application admission.

Required integration:

1. Generate 32 random bytes using the platform CSPRNG when creating a room. Encode
   the key as canonical, unpadded base64url in the invitation fragment. Reject
   incomplete/old invitations; do not negotiate plaintext fallback.
2. On each reliable ordered data channel, exchange a bounded, non-secret hello
   containing a fresh 32-byte connection challenge. Set a handshake deadline.
3. Read the local and remote SHA-256 DTLS fingerprints from the actual accepted
   connection descriptions, not a separate unverified signaling property. WebRTC
   verifies the certificate presented during DTLS against the remote description.
   Require an unambiguous data-channel fingerprint; reject missing/conflicting
   fingerprints. Verify native API behavior on all platforms.
4. Derive separate sending and receiving keys from the invitation secret with
   HKDF-SHA-256. Bind the protocol version, room, sender/receiver identities,
   ordered fingerprints, and both fresh challenges in a canonical context. Freeze
   the exact context serialization in cross-platform vectors before integration.
   Challenges prevent replay across reconnects even when certificates are reused.
5. Exchange an encrypted confirmation of the binding before admitting the peer,
   sending document state, showing it as connected, or processing CRDT/awareness.
   A signaling MITM that substitutes a certificate produces a different binding
   on each side and cannot generate the required confirmation without the secret.
6. Encrypt every subsequent frame, including fragmented snapshots, sync requests,
   previews, and awareness. Decrypt/authenticate before reassembly and parsing.
   Disconnect on any authentication, sequence, limit or framing failure. Keep
   asynchronous queues bounded and preserve transport order.

No creator remains privileged. Any existing participant can authenticate a late
joiner. This authenticates possession of the shared invitation, not a personal
identity, and does not provide application-layer forward secrecy or revocation.

This decision supersedes the original creator-pin requirement in AGENTS.md.
It is not a claim that integration is complete or that the protocol has received
an independent security audit.

## Implemented encryption primitive

`FrameCipher` (Web Crypto) and `JvmFrameCipher` (JCA) implement the same one-way
record cipher. They do not perform or bypass authentication. They are not yet
called by the app controllers.

Inputs: a 32-byte secret, a 32-byte salt, and 1–4096 UTF-8 context bytes. HKDF uses
SHA-256, that salt, and the context as `info`; its 32-byte output is the AES-256
key. The handshake must ensure distinct directional keys and new connection
contexts. Reusing these inputs for a new sender would reuse nonces and is forbidden.

Frame format:

| Field | Bytes | Meaning |
| --- | ---: | --- |
| Magic/version | 4 | `4d 42 45 01` |
| Sequence | 8 | Unsigned big-endian, starting at zero |
| Ciphertext | 1–16384 | AES-256-GCM encrypted payload |
| Tag | 16 | Full 128-bit GCM authentication tag |

The GCM nonce is four zero bytes followed by the eight sequence bytes. Additional
authenticated data is the 12-byte header followed by the context bytes. Exactly
the next sequence is accepted; duplicate, skipped and reordered frames fail.
Each direction allows at most 1,048,576 records, then closes and requires a fresh
connection. The limit bounds GCM use; counters never wrap or reset. Any failed
operation closes the cipher. Web operations also reject overlapping calls and
suppress results if disposed while a crypto operation is in flight.

Test vector: secret = 32 bytes of `07`, salt = 32 bytes of `09`, context =
`meshboard/phase3/vector/sender->receiver`, cleartext = `00 00 01 00`.

```
sequence 0: 4d424501000000000000000001b1593506cf39475764eccdb4115385127f7844
sequence 1: 4d42450100000000000000017a1534afd35753a2733f173cc06ed3eecbc319e4
```

Web Crypto and JCA are checked against these values; the web tests independently
calculate them with Node's HKDF and streaming AES-GCM APIs.

## Validation and remaining work

Primitive tests cover matching ciphertext, successful decryption, wrong secret,
salt/context changes, every single-byte corruption, replay, gaps, plaintext,
truncation, size limits, counter exhaustion, key encoding, disposal, and web
concurrent-call rejection. Commands:

```
pnpm --filter @meshboard/web test
pnpm --filter @meshboard/web check
./packages/native/gradlew -p packages/native desktopTest --tests meshboard.crypto.JvmFrameCipherTest
```

Validated on Linux: all 37 web unit tests, web and test-harness typechecks, seven
JVM cipher tests, Android debug/test APK builds, and the same seven cipher tests
on the Android 35 x86_64 tablet emulator. Android instrumentation command (with
the local SDK configured and emulator running):

```
./packages/native/gradlew -p packages/native -Pmeshboard.android=true :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=meshboard.crypto.JvmFrameCipherTest
```

Still required before Phase 3 can be called complete:

- Freeze and implement the approved authenticated handshake.
- Implement Apple crypto and validate all platform bindings with the same vectors.
- Integrate secret-bearing invitations, QR codes, admission and encryption into
  every normal app; remove all plaintext peer compatibility paths.
- Exercise actual browser/Linux/Android mixed peers, creator departure, late
  joins, temporary outages, large snapshots and previews over encrypted channels.
- Test wrong keys, missing keys, tampered/replayed packets, reflected handshakes,
  substituted SDP fingerprints, stale reconnect traffic and handshake floods.
- Capture signaling and TURN traffic in local tests; assert no secret, recoverable
  drawing content or plaintext fallback. Test real relay connections.
- On macOS: compile Debug/Release device/simulator Apple frameworks; run crypto,
  controller and mixed-peer iOS tests; verify packaged macOS crypto.
- On iPad: QR/link joining, background/reconnect, creator departure, wrong-key
  rejection, and real restrictive-network/TURN behavior. Keep Apple execution
  explicitly pending until it runs on the user's Mac/device.

## Primary references

- [Web Crypto: HKDF and AES-GCM](https://www.w3.org/TR/webcrypto/)
- [RFC 5869: HKDF](https://www.rfc-editor.org/rfc/rfc5869)
- [NIST SP 800-38D: GCM nonce and authentication requirements](https://csrc.nist.gov/pubs/sp/800/38/d/final)
- [WebRTC certificate and description APIs](https://www.w3.org/TR/webrtc/)
- [RFC 8827: WebRTC authentication and signaling trust](https://www.rfc-editor.org/rfc/rfc8827.html)
