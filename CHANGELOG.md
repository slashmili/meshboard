# Changelog

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
