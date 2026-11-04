# Changelog

## Unreleased — Phase 3 foundations

### Added
- Matching Web Crypto and JVM/Android AES-256-GCM record ciphers with HKDF-SHA-256 derivation, canonical 256-bit key encoding, bounded frames, strict counters and fatal-error disposal.
- Cross-platform ciphertext vectors and tests for wrong keys/contexts, every-byte tampering, replay, truncation, plaintext, size limits, nonce-budget exhaustion and disposal.
- A concrete peer-authentication proposal and remaining Apple/live-session validation checklist in `docs/phase-3-security.md`.

### Verified
- All 37 web unit tests and web/test-harness typechecks.
- Seven JVM cipher tests and the same seven tests on the Android 35 x86_64 tablet emulator; Android debug/app-test APK builds.

### Pending
- User decision on replacing the static creator fingerprint with invitation-secret authentication of each connection's DTLS fingerprints.
- Apple crypto, live-session integration and end-to-end security tests. The new primitives are not yet used by controllers; normal sessions remain Phase 2. No pushes.

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
