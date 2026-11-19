# Obsolete synchronization cleanup validation

This cleanup removes the unused Phase 1 JSON framing/model and the temporary
Phase 2 JSON/base64 wrapper. Shared fixture coverage now targets atomic board
elements. Web document tests exchange binary Yjs updates; native UI tests use
the real JNI CRDT document and explicitly close their handles after every test.
The desktop, Android and Apple controllers dispatch local mutations directly to
`CrdtBoard`, preserving their state-vector diff, report and encrypted broadcast.

Verified on Linux:

- `pnpm check`: all workspace TypeScript checks passed.
- Shared-protocol tests: 40 passed; web unit tests: 64 passed; signaling tests:
  15 passed after removing the unused legacy signaling envelopes.
- `pnpm --filter @meshboard/web build`: production build passed.
- `node --test scripts/*.test.mjs`: all 8 passed, including the four Xcode
  build-configuration checks. Executable test fixtures required running outside
  the filesystem sandbox.
- `pnpm test:native`: all 70 desktop/common/JNI/UI tests passed after migrating
  the local UI helper, including its explicit teardown.
- `pnpm test:crdt`: 18 Rust/C-ABI tests and 8 Yjs/yrs compatibility scenarios
  passed; `pnpm test:crdt:kotlin`: JNI tests and all 8 compatibility scenarios passed.
- Android shared/app Kotlin compilation passed with the repository Android SDK.
- `pnpm test:interop`: all 19 live browser/desktop scenarios passed, including
  actual TURN, four-peer convergence, reconnects, plaintext/wrong-key rejection,
  image/history/connector sync and server isolation.

Apple compilation, simulator tests and physical-device checks for this change
remain pending because this machine runs Linux. Existing PR14 fixes to cached
Apple document decoding, incoming channel initialization and snapshot polling
are retained; this cleanup does not replace their recorded validation.

Retained intentionally: current binary fragmentation and encrypted handshake,
old-link/plaintext/wrong-key rejection tests, awareness drawing previews,
`SessionDocument`'s UI-facing operations, and Rust-binding interop build flags.
The `crdt-test` commands test bindings, not an optional application mode.
Historical Phase 2/checkpoint records remain labelled historical; their old
commands do not describe the current CLI.
