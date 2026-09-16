# CRDT compatibility checkpoints (Phase 2a–2b)

This isolated prototype tests **Yjs 13.6.32 ↔ yrs 0.27.4** using real binary
updates. An opt-in Kotlin/JVM JNI bridge exercises the same core in-process.
An opt-in [local web/desktop preview](../../docs/crdt-preview.md) now uses this
core for live synchronization. Released/default apps still use the Phase 1 JSON
protocol; these checkpoints do not change their behavior.

## Try it

With Node 22+, pnpm and the stable Rust toolchain installed:

```sh
pnpm install --frozen-lockfile
pnpm test:crdt
```

The command builds the Rust harness, runs Rust unit tests, and runs seven
cross-language scenarios. It is also included in `pnpm test` and the Linux and
macOS CI test jobs. Cargo dependencies are locked in `Cargo.lock`.
This checkpoint has no UI changes to try yet.

## Kotlin boundary checkpoint (2b)

With JDK 21 also installed, run from the repository root:

```sh
pnpm test:crdt:kotlin
```

Gradle builds a host-specific Rust shared library, runs six Kotlin/JNI tests,
then the same seven Yjs scenarios run through **Yjs → Kotlin → JNI → yrs** and
back. The JVM loads the Rust library directly, not a Rust subprocess. The Node
test driver starts a JVM for each harness request, just as checkpoint 2a starts
the Rust CLI. Lifecycle and concurrent-access tests exercise persistent handles.
Both test paths enable the JVM's JNI checking mode (`-Xcheck:jni`).

`CrdtBoard` is a common Kotlin API. `JvmCrdtBoard` is currently desktop-only,
compiled with `-Pmeshboard.crdtInterop=true`; the regular desktop distribution
does not include its implementation or load a native CRDT library. Current
app builds do not require Rust. CI runs this checkpoint on its existing Linux,
Apple Silicon macOS, and Intel macOS desktop jobs. Only Linux has been run locally.

The Rust side keeps opaque, non-reused handle IDs under a mutex, instead of
passing native pointers to Kotlin. Calls copy byte arrays, cap boundary input and
output at 4 MiB, and translate errors into JVM exceptions. Rust panics are caught
at JNI entry points; a poisoned registry fails closed. Use `use { ... }`/`close()`
to release documents; close is idempotent in Kotlin and further calls fail.
Production-style constructors generate client IDs; deterministic IDs are internal
to the compatibility harness.

The JNI path now stages mutations on a separate document and validates the
resulting shape schema, root types, and 2 MiB document budget before committing.
The CLI remains an unrestricted compatibility harness. This is **not a complete
untrusted-network security boundary yet**: binary decoding still needs adversarial
CPU/allocation testing and isolation/resource-budget work before non-local use.
An opt-in [Android JNI checkpoint](../native/androidApp/README.md#opt-in-android-crdt-bindings--checkpoint-2e)
now reuses this wrapper and the same seven compatibility scenarios on a tablet
with `pnpm test:crdt:android`. It does not enable live Android CRDT sharing.
iOS C interop remains a separate platform checkpoint.

## Candidate document format

- One root shared map named `elements`, keyed by the existing element ID.
- Values are atomic JSON objects using the existing board-element fields.
  Numbers, nested point arrays and objects remain JSON values, not JSON strings.
- Deletes remove map entries. Rendering order stays deterministic by element ID;
  shared-map iteration order must not be used as drawing order.
- Concurrent replacement of one element resolves through the CRDT's map rules.
  Fields do not merge independently yet. Collaborative text and per-field edits
  will need shared nested types and an explicit schema/migration decision.
- Updates and state vectors use Yjs's **v1 binary encoding** on both sides.
  This is distinct from Meshboard's existing JSON protocol's `v: 1` field.
- Previews/presence are ephemeral and are not part of the persistent CRDT state.

The CLI exchanges JSON byte arrays only to make automated tests easy to drive.
It is not a network protocol, server, or public input boundary. Input schema and
resource-limit enforcement remain required before integration into the apps.
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

These binary-only tests do **not** establish mobile FFI, live transport
compatibility, four-device convergence, encrypted admission, or mobile performance.
The separate local preview suite now tests actual y-webrtc signaling/sync/awareness
against desktop, including an unmodified provider and a negotiated large-message
extension. See the linked preview guide for the exact scope and limits.
Release migration and mobile integration remain pending. Keep pausing at runnable checkpoints.
Do not claim Phase 2 is complete on these tests alone.
