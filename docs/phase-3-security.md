# Phase 3 security design and validation

Status: the approved authenticated protocol is integrated into web, desktop and
Android and Apple sessions. Apple compilation and simulator runtime validation
passed in CI run 41804767894; physical-device checks remain below. This is not an
independently audited security release.

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
   ordered fingerprints, and both fresh challenges in a canonical context. Use
   the exact context serialization below, checked by cross-platform vectors.
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

## Frozen handshake (secure-v1)

The only accepted data-channel label is `meshboard.secure-v1`. No plaintext or
legacy fragmentation negotiation remains. A hello is exactly the four bytes
`4d 42 48 01` followed by 32 random bytes. Duplicate/reflected hellos are rejected.
The connection must authenticate within 20 seconds of peer creation.

Each directional HKDF context is these eight ASCII fields separated by a single
LF (`0a`), with no trailing newline:

```
meshboard.secure-v1
room UUID
sender UUID
receiver UUID
sender SHA-256 fingerprint (64 lowercase hex characters, no colons)
receiver SHA-256 fingerprint (64 lowercase hex characters, no colons)
sender challenge (64 lowercase hex characters)
receiver challenge (64 lowercase hex characters)
```

UUIDs must be lowercase version-4 UUIDs; sender and receiver must differ. Salt is
32 zero bytes: the fresh challenges are already bound into HKDF `info`. The input
key is the decoded 32-byte invitation secret. Each side encrypts confirmation
`4d 42 41 01` as record zero. Only successful decryption of that exact confirmation
admits the peer. Application records start at sequence one. Keys, challenges and
cipher instances are never reused across reconnects.

Fingerprints are extracted from the actual local/remote descriptions accepted by
WebRTC. Only SHA-256 is supported; every fingerprint line must agree, and the SDP
must contain an application section. WebRTC validates the remote certificate
against its accepted description; the proof authenticates that binding to the
invitation. Duplicate metadata, another room/identity, a substituted certificate,
replayed proofs and wrong secrets cannot produce the matching directional key.

All Yjs sync and awareness messages are fragmented before encryption into at most
16 KiB clear frames. Authenticate/decrypt before assembly or CRDT parsing. Queued
outgoing and incoming data is capped at 8 MiB per peer, with per-frame bounds
and at most 512 pending input records.
Failures close the peer while retaining the existing validated board.

## Implemented encryption primitive

`FrameCipher` (Web Crypto) and `JvmFrameCipher` (JCA) implement the same one-way
record cipher. They are used by the authenticated channel in normal apps. The same Kotlin
handshake is shared with Apple through the Swift CryptoKit adapter.

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

Validated on Linux: all 42 web unit tests, web/test-harness typechecks, 41 desktop
tests (including 12 crypto/handshake tests), Android debug/release/test APK builds,
and the same 12 crypto/handshake tests on the Android 35 x86_64 tablet emulator. Android instrumentation command (with
the local SDK configured and emulator running):

```
./packages/native/gradlew -p packages/native -Pmeshboard.android=true :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=meshboard.crypto.JvmFrameCipherTest,meshboard.crypto.SecureChannelTest
```

## Integration validation

- Passed: production web build and all 12 browser E2E scenarios, including direct
  and actual TURN sessions, creator departure, refresh recovery, tamper/replay
  rejection, missing keys and captures of the data-channel bytes before DTLS.
- Passed: browser/Linux four-peer convergence, large drawings, offline edits and
  deletions, repeated reconnects, previews, TURN, plaintext rejection and wrong-key
  rejection. Fixed vectors cover substituted fingerprints, identities/rooms,
  reflected challenges, stale reconnect proofs and nonce exhaustion.
- Passed: Android tablet drawing UI, web/Android/desktop four-peer large snapshots,
  creator departure, reconnects, TURN, plaintext rejection, wrong-key rejection
  and successful joining afterward with the correct secret.
- Passed: a local loopback PCAP of production-browser traffic (577 packets), with
  50 decoded WebSocket signaling messages, 108 relayed DTLS records including 98
  encrypted application records, and no drawing/secret fields in signaling or
  drawing markers in relay payloads. DTLS 1.3 uses the RFC 9147 unified header.
  The capture had zero kernel drops. Artifact: `build/security/phase3-loopback.pcap`
  (ignored/local only). Check with:

```
python3 scripts/check-security-capture.py build/security/phase3-loopback.pcap
```

Packet inspection complements the cryptographic tests; absence of recognizable
strings alone is not a confidentiality proof. ICE/STUN connectivity checks and
DTLS handshake metadata are expected and are not board content. The capture only
covered local test ports 5173 and 3478, not external traffic or production users.

### Reproducing the local packet capture (Linux)

Use only disposable test boards. Start `pnpm turn` in another terminal. Build the
local capture image (the signaling image is only its Node/Alpine base):

```
docker build -f infra/deploy/Dockerfile --target signaling -t meshboard-signaling:local .
docker build -t meshboard-capture:local infra/security-capture
pnpm build
mkdir -p build/security
docker run -d --name meshboard-phase3-capture --network host --cap-drop ALL --cap-add NET_RAW --cap-add SETUID --cap-add SETGID --security-opt no-new-privileges -v "$PWD/build/security:/capture" meshboard-capture:local -p -i lo -U -s 0 -Z node -w /capture/phase3-loopback.pcap 'udp port 3478 or tcp port 5173'
docker logs meshboard-phase3-capture
```

Confirm the log says `listening on lo` before running the tests. The writer uses
Node's container UID 1000, matching this workspace owner; adjust ownership/user
mapping if your local UID differs. Then:

```
MESHBOARD_TEST_BUILD=1 pnpm --filter @meshboard/web exec playwright test e2e/security.spec.ts e2e/sharing.spec.ts -g 'all application frames|actual TURN relay' --output=../../build/security/browser
docker stop --signal SIGINT meshboard-phase3-capture
docker logs meshboard-phase3-capture
docker rm meshboard-phase3-capture
python3 scripts/check-security-capture.py build/security/phase3-loopback.pcap
```

Stop/remove the capture container even if a test fails. Captures stay local under
ignored `build/`; neither images nor captures are pushed.

## CI follow-up (2026-11-04)

[Run 41798835980](https://github.com/slashmili/meshboard/actions/runs/41798835980)
tested commit `8f6fc61`. Both macOS desktop architectures and Android passed.
The iOS job passed the standalone CryptoKit tests and Kotlin binding/controller
tests, then failed Swift host compilation: Kotlin exports `newKey()` as
`doNewKey()`, while the implementation used `newKey()`. The Swift method now
matches the compiler-reported protocol requirement; full iOS compilation and
runtime still require the next Mac/CI run.

Linux passed its unit and browser tests, but a plaintext-peer test expected an
authentication-specific error. A transport close can win the race and report a
connection failure instead. The test now accepts either rejection report while
asserting zero admitted peers, no leaked state and an intact original drawing.
The same timing-independent expectation is used by Android and iOS tests.

CI run [41804767894](https://github.com/slashmili/meshboard/actions/runs/41804767894)
at `8d73e61` passed all three desktop jobs and the complete iOS job, including
simulator interoperability and the unsigned device build. Android never reached
the build/test script: emulator startup failed with 6697.87 MB available versus
7372.80 MB required for userdata. The Android workflow now removes unused
preinstalled NDK versions before installing the pinned `28.2.13676358`, leaving
SDK platforms, emulator, system images and the required NDK intact. Fixture tests
cover preservation, removal, repeated runs, an empty NDK directory and an unset
SDK path. Hosted-runner disk headroom still needs confirmation in the next CI run.

## Apple and hardware handoff

The iOS network adapter gates controller callbacks on the shared Kotlin handshake,
uses CryptoKit for the identical AES-GCM format, and exposes only decrypted frames
to the board controller. It has no plaintext fallback. The standalone CryptoKit and Kotlin controller tests passed in the CI run above.
The corrected Swift host build, Kotlin/Swift bridge and simulator runtime passed
in run 41804767894. These commands remain available for local Mac reproduction:

```
bash scripts/test-apple-crypto.sh
pnpm test:crdt:ios
pnpm test:interop:ios
pnpm build:ios:device
pnpm test:native
```

The standalone CryptoKit test compiles the production cipher and checks the same
fixed vectors, wrong keys/contexts, every-byte tampering, replay, sizes, nonce-budget exhaustion and disposal.
It is also included in the iOS CI script. Kotlin controller doubles exercise an
already-authenticated/decrypted network boundary; only the real iOS interop suite
validates the Swift adapter and WebRTC certificate binding together.

Also build Release device and simulator configurations in Xcode. On iPad, verify
QR/link joining with the full secret, background/reconnect, creator departure,
wrong-key rejection and restrictive-network/TURN behavior. On macOS verify the
packaged app. Windows and physical Android ARM64 execution remain hardware checks.

The handshake is application-specific and has not had an independent security
audit. Shared-link risks remain: link leakage grants all board access, individual
members cannot be revoked, and malicious authorized members/endpoints are outside
the outsider-authentication guarantee. CRDT parser fuzzing remains future work.

## Primary references

- [Web Crypto: HKDF and AES-GCM](https://www.w3.org/TR/webcrypto/)
- [RFC 5869: HKDF](https://www.rfc-editor.org/rfc/rfc5869)
- [NIST SP 800-38D: GCM nonce and authentication requirements](https://csrc.nist.gov/pubs/sp/800/38/d/final)
- [WebRTC certificate and description APIs](https://www.w3.org/TR/webrtc/)
- [RFC 8827: WebRTC authentication and signaling trust](https://www.rfc-editor.org/rfc/rfc8827.html)

## BroadcastChannel isolation

The pinned y-webrtc dependency carries a pnpm patch adding `disableBc`. Normal
web sessions enable it before provider construction. It prevents subscriptions
and all publications, including startup snapshots, reconnects and teardown.
Unsubscribing after `await provider.key` is insufficient: upstream has already
scheduled a plaintext full-state publication by then. `filterBcConns` only
controls peer filtering, not this alternate transport.

`BroadcastIsolation.test.ts` exercises the real provider through session startup,
plaintext injection, document/presence updates, reconnect and disposal. A control
case confirms the original late-unsubscribe sequence exposes a dummy snapshot.
Keep this patch and regression coverage when upgrading y-webrtc; the Docker build
must copy `patches/` before installing dependencies.
