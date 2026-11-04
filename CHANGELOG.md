# Changelog

## Unreleased — Phase 4

### Appearance and delivery decisions
- Added: System, Light and Dark appearance on web and shared native UI. Controls and dialogs follow the selected theme; the shared paper and stroke colors stay consistent across peers.
- Added: web appearance preference storage with a fallback when storage is blocked; native appearance survives Android activity recreation.
- Fixed: Share/Help and mobile connection labels now describe the implemented authenticated end-to-end encryption instead of the old Phase 1 prototype.
- Verified: 44 web unit tests, six production-browser drawing/appearance tests, 44 desktop tests and seven Android tablet UI tests; Android APK build and dark-layout screenshot review. Apple/Windows appearance checks remain tracked.
- Spec: local board recovery will be opt-in, default off, with a delete action. Record scope, implementation order and deferred Mac/iPad checks in `docs/phase-4-validation.md`.

### Text boxes and sticky notes
- Added: text (T) and note (N) tools on web, desktop and shared mobile UI, with click/tap creation and editing, cancel, multiline Unicode content, content-sized boxes and whole-area erasing.
- Added: matching TypeScript/Kotlin/Rust validation, bounded plain-text content, shared fixtures, and encrypted cross-platform edit/late-join scenarios. Edits replace one CRDT element; character-level collaborative editing is not claimed.
- Fixed: an editor cannot save an object already deleted by another peer; delayed browser dialog-close events cannot clear a newly opened edit. Desktop stroke controls sit beside the expanded toolbar to fit the minimum window height.
- Verified: workspace unit checks, Rust format/Clippy/tests/docs, 48 desktop tests, eight Android tablet UI tests, eight production-browser tests and 20 repeated text/note runs; eight Yjs/yrs scenarios through Kotlin/JNI and live web↔desktop/web↔Android text scenarios. Expanded pinch checks pass on web and Android. iOS scenario is wired into CI; Mac/iPad execution remains pending.

## Unreleased — Phase 3

### Added
- Matching Web Crypto and JVM/Android AES-256-GCM record ciphers with HKDF-SHA-256 derivation, canonical 256-bit key encoding, bounded frames, strict counters and fatal-error disposal.
- Cross-platform ciphertext vectors and tests for wrong keys/contexts, every-byte tampering, replay, truncation, plaintext, size limits, nonce-budget exhaustion and disposal.
- A concrete peer-authentication proposal and remaining Apple/live-session validation checklist in `docs/phase-3-security.md`.

### Verified
- All 37 web unit tests and web/test-harness typechecks.
- Seven JVM cipher tests and the same seven tests on the Android 35 x86_64 tablet emulator; Android debug/app-test APK builds.

### Security decision and pending verification
- Approved (2026-11-04): replace the static creator fingerprint with invitation-secret authentication of each connection's DTLS fingerprints; record the new trust model in AGENTS.md.
- Apple compilation/runtime verification and independent security review. Live-session integration is recorded below. No pushes.

### Phase 3 authenticated sessions
- Changed: require a secret-bearing invite and an authenticated `meshboard.secure-v1` channel; bind directional keys to room, peer identities, actual DTLS fingerprints and fresh challenges.
- Added: encrypted admission confirmation, AES-GCM protection for all sync/awareness/fragment traffic, handshake deadlines and bounded input queues.
- Removed: plaintext upstream-peer compatibility and negotiated plaintext fragmentation fallback.
- Verified: 42 web unit tests, 41 desktop tests, 12 Android cipher/handshake tests and Android release compilation. Browser/Linux drawing, four-peer convergence, creator departure, reconnect and TURN; wrong-key/plaintext peers are rejected. Production browser tests cover ciphertext-only application frames, secret-free signaling, tampering and replay.
- Pending: Apple compilation/runtime verification is tracked separately; this is not an independent security audit.

### Apple secure-session integration (execution pending)
- Added: a CryptoKit record cipher, shared Kotlin fingerprint-bound handshake, secret-bearing invites and authenticated controller admission in the Swift network adapter.
- Added: standalone Apple crypto vector/tamper/replay/limit tests and iOS CI invocation; updated simulator interoperability expectations to reject plaintext and wrong keys.
- Verified here: shared handshake/crypto semantics on JVM/Android, test-harness typechecks and shell syntax. Swift compilation, Kotlin/Swift bridging and iOS runtime tests require the user's Mac; tracked explicitly in the security ledger.

### Phase 3 wire validation and handoff
- Added: a bounded local PCAP checker and capture-image recipe; refreshed the active protocol, security design, README and delivery checkpoints.
- Verified: final production-browser suite (12), browser/Linux suite (9), and Android mixed-platform suite (7) all pass. Packet inspection decoded 50 signaling messages and 98 encrypted TURN application records; no board/secret fields appeared in signaling and no board markers appeared in relay payloads.
- Fixed: negative packet tests send one bad record and suppress further traffic, so a valid invite-holder's later authorized resynchronization cannot obscure rejection of the bad record.
- Handoff: Mac/iPad compile/runtime checks are explicit and reproducible. No pushes or public deployments.

### Phase 3 CI fixes
- Fixed: implement Swift `doNewKey()` as required by the Kotlin-generated AppleNetwork protocol; the previous name blocked iOS host compilation.
- Fixed: native plaintext-peer tests allow either authentication rejection or transport closure while retaining no-leakage/no-admission assertions; Linux CI exposed this ordering race.
- Verified locally: TypeScript checks and ten repeated browser/Linux plaintext-rejection scenarios passed.
- Recorded: CI run 41798835980 passed both macOS desktop runners, Android, and Apple crypto/Kotlin tests at commit 8f6fc61. The corrected iOS host build still needs Mac/CI verification.

### Android CI disk capacity
- Fixed: remove unused hosted-runner NDK versions before installing the pinned Android NDK; run 41804767894 exhausted disk while creating emulator userdata, before any Android build or test ran.
- Verified: eight script tests, including cleanup preservation/idempotency/error cases; a fresh Android 35 Nexus 10 emulator boot and all six Android UI tests passed locally. Hosted-runner headroom requires the next CI run.
- Recorded: run 41804767894 passed Linux, both macOS desktop runners, and the complete iOS simulator/device-build job. Physical-device checks remain in the security ledger.

## Unreleased — Phase 2

### Changed
- Make Yjs/y-webrtc the normal web session implementation and enable its signaling endpoint outside development mode.
- Generate CRDT invites without a preview query flag; reject obsolete Phase 1 invites.

### Fixed
- Close browser peer connections on page exit and restart a restored back/forward-cache page so departed peers do not linger.

### Verified
- Production web build and all eight browser drawing/sharing tests, including actual TURN, creator departure, refresh recovery, and session end.
- All 20 signaling tests, including production configuration and room isolation.

### Native desktop migration
- Changed: normal desktop sessions use yrs and y-webrtc exclusively; remove the Phase 1 networking path.
- Added: bundle the JNI library in desktop resources and verify loading from the packaged launcher.
- Verified: 29 desktop model/UI/JNI tests, six additional checked-JNI tests, nine real browser/desktop scenarios (four mixed peers, creator departure/rejoin, offline edit/delete recovery, TURN, upstream interoperability), and Linux distribution startup.

### Protocol and deployment cleanup
- Removed: obsolete web session implementation, legacy signaling endpoints and superseded live-protocol test suites.
- Changed: route production WSS to `/signal-y-webrtc`; run production browser and normal-app CRDT suites in CI; install Rust for release packaging.
- Fixed: local deployment smoke tests run TURN as the owner of their temporary certificate, matching the production certificate ownership model.
- Verified: 15 signaling tests, 30 web unit tests, production build and eight browser tests, all test-harness TypeScript checks, plus local HTTPS/WSS and authenticated TURN UDP/TLS data relay.

### Android migration
- Changed: normal debug/release builds bundle yrs for ARM64 and x86_64 and use CRDT-only networking; remove emulator-only preview gates.
- Changed: normal interop commands exercise the CRDT suite; obsolete protocol tests are retired.
- Verified: six tablet UI tests, six JNI tests, seven Yjs/Android compatibility scenarios, seven real mixed-platform/ TURN scenarios, debug/release APK builds and packaged ABI contents.

### iOS migration (Apple execution pending)
- Changed: normal simulator/device and Debug/Release frameworks link yrs; the Swift/Kotlin controllers now use CRDT-only networking and HTTPS remote origins.
- Removed: preview factory/build gates and legacy signaling/data-channel branches; debug interop remains simulator-only.
- Added/updated: normal-factory controller tests, deletion synchronization tests, and build-script tests for all four configuration/platform combinations.
- Verified here: build-script tests and interop TypeScript checks. Xcode compilation, iOS controller/live tests, physical iPad and packaged macOS checks require the user's Mac; tracked in docs/phase-2-validation.md.

### Phase 2 handoff
- Added: a validation ledger and explicit macOS/iPad checklist; refreshed platform and protocol documentation.
- Changed: CI checks normal CRDT apps, production web bundles and Android release compilation.
- Verified: production-container browser drawing and late joining after creator departure through HTTPS/WSS, in addition to actual TURN UDP/TLS traffic.
- Scope: no pushes, deployments, encryption, persistence or Phase 4 features. Apple and other hardware-only checks are pending, not claimed complete.
