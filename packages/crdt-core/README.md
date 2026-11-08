# CRDT core

The native apps use **yrs 0.27.4**, compatible with the browser's **Yjs 13.6.32**.
Desktop and Android use `JvmCrdtBoard` through JNI; iOS uses `AppleCrdtBoard`
through the C API. Normal and release builds include these bindings.

## Tests

```sh
pnpm test:crdt         # Rust tests and seven Yjs/yrs binary scenarios
pnpm test:crdt:kotlin  # checked JNI lifecycle tests and the same scenarios through Kotlin
pnpm test:crdt:android # device/emulator JNI and cross-language compatibility
pnpm test:crdt:ios     # iOS binding/controller tests and compatibility (Mac required)
```

Stable Rust, Node 22 and pnpm are required; JVM tests also need JDK 21. Android
needs NDK 28.2 and both Rust Android targets; iOS needs Xcode and both Apple ARM64
targets. See [current validation and setup](../../docs/phase-2-validation.md).

The Rust side keeps opaque, non-reused handle IDs under a mutex. Calls copy byte
arrays, cap boundary input/output at 4 MiB and translate errors into exceptions.
Panics are caught at the FFI boundary. Close documents explicitly; Kotlin close
is idempotent and later calls fail. The application bindings stage mutations,
validate the shape schema and 2 MiB budget, then commit valid changes.

Desktop resources bundle the shared library; the loader extracts it to a private
temporary directory. Android packages it in ABI-specific APK directories. iOS
links it statically. Native test adapters remain separate from production APIs.

## Document format

- One root shared map named `elements`, keyed by the existing element ID.
- Values are atomic JSON objects using the existing board-element fields.
  Numbers, nested point arrays and objects remain JSON values, not JSON strings.
- Deletes remove map entries. Rendering order stays deterministic by element ID;
  shared-map iteration order must not be used as drawing order.
- Concurrent replacement of one element resolves through the CRDT's map rules.
  Fields do not merge independently. Text boxes and notes use atomic whole-box
  replacement; character-level collaborative text would need nested shared types
  and an explicit schema decision.
- Updates and state vectors use Yjs's **v1 binary encoding** on both sides.
  This is distinct from Meshboard's existing JSON protocol's `v: 1` field.
- Previews/presence are ephemeral and are not part of the persistent CRDT state.

The CLI exchanges JSON byte arrays only to make automated tests easy to drive.
It is not a network protocol, server, or public input boundary. Input schema and
resource limits are enforced by the application bindings.
Fixed client IDs are only for deterministic test replicas, not for production.

## Joining and reconnecting

A fresh participant receives the current CRDT document as a full-state update,
then incremental changes. They do not replay each user action or drawing gesture.
The encoded state also carries merge/deletion metadata; it is not just visible
shapes and is not guaranteed to stay constant in size throughout a long session.

A returning peer that still holds its CRDT document advertises a state vector,
and another peer computes the missing update. A refresh without retained state
needs full synchronization again. Delete-only changes can matter even when a
state vector is unchanged, so vector equality alone must never skip syncing.
Any remaining participant can seed a newcomer; the creator is not required.

See the [Yjs update API](https://docs.yjs.dev/api/document-updates) and
[yrs API](https://docs.rs/yrs/0.27.4/yrs/) for the underlying encoding operations.

## What this verifies

1. Existing board fixtures round-trip Yjs → yrs → Yjs, including native deletion.
2. A 12,000-point native snapshot loads in Yjs; subsequent small edits use a delta.
3. Duplicate/out-of-order Yjs updates converge in yrs without reviving deletes.
4. Duplicate/out-of-order yrs updates converge in Yjs.
5. Concurrent replacements converge across the two implementations.
6. Delete-only updates synchronize even with unchanged state vectors.
7. A remaining peer seeds a newcomer and accepts its edits without the creator.

Binary tests alone do not establish live transport behavior. The normal-app
interop suites test y-webrtc signaling/sync/awareness, four mixed peers, creator
departure, recovery and TURN. See [the validation ledger](../../docs/phase-2-validation.md)
for completed tests and pending Apple/device checks. Application encryption and
authenticated admission remain Phase 3.
