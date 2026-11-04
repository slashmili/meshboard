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
