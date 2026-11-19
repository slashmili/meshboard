# Coding guide

Meshboard is a session-based, end-to-end encrypted P2P whiteboard.

- Read the assigned Linear issue and [product requirements](https://linear.app/slashmili/document/product-requirements-9d44b4d02dc7).
  Use the [roadmap](https://linear.app/slashmili/document/roadmap-and-remaining-work-186cfc6d1799) for status and remaining work.
  Record implementation results and pending checks in Linear.
- When a new feature conflicts with existing behavior, favor the new feature
  and remove the superseded legacy code. If the intended replacement or removal
  scope is unclear, ask the user before proceeding.
- Web: React/TypeScript, Yjs and patched y-webrtc in `packages/web`.
  Native: Kotlin Multiplatform/Compose in `packages/native`, yrs Rust bindings
  in `packages/crdt-core`. Desktop uses the JVM; iOS uses a SwiftUI host.
  Shared wire schemas/fixtures live in `packages/shared-protocol`;
  `packages/signaling` only relays connection metadata.
- Preserve cross-platform wire compatibility. Update shared fixtures when
  changing schemas; keep elements atomic. Read [protocol notes](docs/crdt-preview.md)
  and the [secure-v1 design](https://linear.app/slashmili/document/encrypted-sessions-security-design-and-validation-f70efdce32e6)
  before transport/security changes.
- Keep full-mesh, creator-independent sessions. Authenticate before admission;
  encrypt all board/awareness traffic. Never put invite secrets or board data
  in signaling, logs or analytics, and never allow plaintext fallback.
  Persist board state/secrets only through explicitly enabled local recovery.
- Preserve supplied `icons/` artwork and the compact mobile header.
  Use the landscape Pixel Tablet for Android tests; Android commands belong
  in `scripts/android.mjs`.
- Setup and commands: [development guide](docs/development.md), then the relevant
  [desktop](packages/native/README.md), [Android](packages/native/androidApp/README.md)
  or [iOS](packages/native/iosApp/README.md) guide. Use pinned tools and the
  Gradle wrapper; keep generated outputs and credentials out of Git.
- Run checks appropriate to the change: `pnpm check`, `pnpm test`, `pnpm build`;
  add native/binding/live interoperability checks for affected paths.
  Distinguish local, CI, simulator and physical-device results. Never mark
  unavailable platform checks as passed.
- Preserve unrelated workspace changes and existing license notices.
  First-party code and supplied artwork are MPL-2.0; per-file headers are optional.
