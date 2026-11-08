# Phase 4 implementation and validation

Phase 3 CI is green, as confirmed by the user. Phase 4 is in progress; the
checkpoints below are independent tested increments, not a completion claim.

## Scope and decisions

- Appearance: follow the OS by default with System, Light and Dark overrides.
  Theme changes affect local controls/dialogs, not the shared canvas's colors.
  Keep paper light so every existing stroke stays visible and exports can match
  what peers see. Web stores its appearance preference; native retains the override
  for the current app instance, including Android activity recreation.
- Recovery: user approved **opt-in** “Remember this board on this device”, default
  off, with an explicit delete action. Retain the board and invite secret only on
  the opting-in device. Never persist a live cipher's keys/counters/challenges.
  Fresh authenticated channels are established on reconnection. This supersedes
  the earlier unconditional claim that all state disappears when everyone leaves.
- Snapshots/import: a documented versioned format shared by web and native;
  imported boards start new sessions with new secrets. Exported files must not
  include the live session's invite secret or transport authentication state.
- Preserve encrypted CRDT and awareness transport for every new feature.

## Increments

1. Appearance and accurate sharing/help text.
2. Shared element model and tools: text, sticky notes, arrows/connectors, images,
   multi-select and move, with cross-language fixtures and mixed-peer tests.
3. CRDT-aware undo/redo that does not undo someone else's unrelated edits.
4. Snapshot import/export and PNG/PDF export on each platform.
5. Connected-peer names and live cursors using encrypted awareness.
6. Pressure-sensitive pen input/rendering and graceful mouse/touch fallback.
7. Opt-in local recovery, resume/delete UX, and reconnect/security tests.

## Appearance verification

Implemented as checkpoint 4a. Passed on Linux: 44 web unit tests, TypeScript
checks and production build, six production-browser drawing/appearance tests,
44 native desktop tests, Android debug APK build and seven Android tablet UI
tests. Reviewed desktop/web-phone dark screenshots; corrected selected-tool
contrast and reran the affected desktop UI suite. Apple/Windows execution remains
pending below.

Find Appearance in web Help, the desktop header, or mobile Board options.

Commands:

```sh
pnpm --filter @meshboard/web check
pnpm --filter @meshboard/web test
pnpm --filter @meshboard/web build
MESHBOARD_TEST_BUILD=1 pnpm --filter @meshboard/web exec playwright test e2e/appearance.spec.ts e2e/board.spec.ts
./packages/native/gradlew -p packages/native desktopTest
ANDROID_HOME="$PWD/.android-sdk" ./packages/native/gradlew -p packages/native -Pmeshboard.android=true :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.meshboard.android.CanvasTest
```

Web tests cover live OS changes, explicit override, reload, blocked storage and
unchanged stroke data. Compose tests cover mode resolution, desktop/mobile
controls, actual dark header pixels and drawing preservation. Android's new test
checks that both drawing and the appearance override survive activity recreation.

## Deferred platform checks

- macOS: native system-theme changes while open, explicit override, packaged app.
- iPad/iPhone: system-theme changes, override, safe areas, rotation, native dialogs.
- Windows: system-theme detection and packaged app.
- Stylus: Apple Pencil, Android pressure stylus and desktop tablet hardware; synthetic events cannot establish device feel.

These checks are pending, not implied by Linux or shared Compose tests. Session
recovery and the other increments above are not implemented by appearance work.

## Text and sticky notes (checkpoint 4b)

Text (T) and Note (N) tools are implemented on web, desktop and the shared mobile
UI. Click/tap to create a box, or click an existing text/note with either tool to
edit it. Save commits one element; Cancel discards the draft. Notes expand
vertically to fit up to 500 UTF-16 code units. Erasing through a box's interior
removes the whole object. A second touch cancels placement and starts navigation.

The cross-platform format and atomic-edit semantics are documented in
[shared schemas](../packages/shared-protocol/README.md#text-and-sticky-note-elements).
There is no character-level collaborative text editing. Image insertion,
connectors, selection/moving, undo/redo, export/import, presence, pressure and
local recovery remain separate increments.

Coverage includes strict shared validation and invalid-update rollback; identical
Unicode wrapping; native and web create/edit/cancel/erase flows; Android activity
UI; encrypted mixed-peer edits/late join/deletion while editing; Yjs/yrs Unicode
round trips and concurrent replacements. The new live scenario is wired into
Linux desktop, Android and iOS CI. iOS execution remains pending CI/Mac.

Mac/iPad follow-up: create and edit both tools with keyboard/touch, compare
Unicode text with web, pan/zoom, cancel a placement with a pinch, and confirm
keyboard dismissal and text-field focus. Physical devices remain unverified here.

Verified on Linux for 4b: 46 web unit tests (plus shared-protocol/signaling suites),
eight production-browser tests, 20 repeated new text/note runs after fixing the
queued dialog-close race, 48 desktop tests, eight Android UI tests and expanded
pinch coverage. Eight Yjs/yrs scenarios passed through Kotlin/JNI, including
Unicode and concurrent text replacements. Live web/desktop and web/Android text
scenarios passed; Android requires the local TURN relay (`pnpm turn`). Rust format,
all-target/all-feature Clippy with warnings denied, six Rust tests and docs pass.
Desktop UI tests also exercise text creation at the minimum 650-pixel window
height. No iOS runtime pass is claimed for this increment.

## iPad CI convergence investigation (2026-11-08)

Run [41929821839](https://github.com/slashmili/meshboard/actions/runs/41929821839)
at appearance commit `43dabd0` compiled iOS successfully, then failed the four-peer
large-state interoperability test. Five other iPad scenarios passed. Before
cleanup, both native replicas reported three connected peers, no error, the large
seed and both browser strokes. Each had its own native stroke but lacked the
other native stroke (four elements instead of five). This is an unresolved
convergence failure; neither compilation nor a missing browser gesture explains it.

The Linux four-peer regression now makes both native replicas edit concurrently,
matching the iPad scenario. Ten repeated runs passed, followed by three more with all test processes pinned
to one CPU. Workspace typechecks passed with the repository Node 22 toolchain.
The iPad assertion now includes element IDs in failure output and captures both
browser states before cleanup; native failure attachments omit the invite secret
and preserve error states. The original count and equality requirements and
30-second assertion timeout remain intact. No production workaround was added
without a reproduced cause.

The failed iPad job was rerun with `gh` on the same commit (attempt 2). The user confirmed that the rerun passed; this does not validate the later text/note commit or the new local
diagnostics. Run the updated iPad suite on macOS/CI.
Do not mark this issue resolved solely because the Linux repetitions pass.

## Straight arrows (checkpoint 4c)

Arrow (A) is available on web, desktop and the shared mobile toolbar. Drag from
tail to head; desktop/web Shift snaps to 45-degree increments. The arrowhead is
part of the erasable outline, scales with stroke width, and shrinks for short
arrows. Zero-length arrows never produce non-finite coordinates. Wire data stores
two endpoints; both implementations derive matching geometry. These are free-standing
arrows, without object-attached endpoints or automatic routing.

Verified: shared schema fixtures, web geometry tests, Rust validation/round trips,
desktop pointer and shared mobile touch UI, seven production-browser drawing tests,
and encrypted web/desktop arrow edit/reload/erase interoperability. Rust format,
Clippy, tests and docs passed. The same interop scenario is wired into Android/iOS
CI; Android pinch cancellation now includes arrows. Android emulator execution
passed (eight UI tests and arrow/pressure interoperability). macOS/iPad execution
and physical-device checks remain pending, including touch toolbar scrolling
and arrowhead rendering at different zooms.

## Pressure-sensitive pen (checkpoint 4d)

Web pointer/coalesced events and native stylus pointer events now capture bounded
pressure samples. Rendering and erasing use the documented shared width curve;
mouse/touch retain the chosen constant width. Lift-off preserves the last contact
pressure. Pressure samples survive encrypted synchronization, native edits and
browser reloads. This is input/rendering support, not a hardware-quality claim.

Verified: 49 web unit tests, 33 shared-protocol tests, Rust validation/round trips,
format/Clippy/tests/docs, desktop/common tests including mobile rendering pixels,
six production-browser tests including Chromium pen injection, live encrypted
web/desktop pressure and arrow scenarios, Android app/instrumentation APK builds.
Android and iOS pressure interoperability scenarios are wired into CI. The Android
tablet emulator passed all eight UI tests and both new encrypted arrow/pressure
interoperability scenarios after installing the rebuilt APKs.

Pending hardware checks: Apple Pencil and Android stylus light/heavy strokes,
stationary pressure changes, lift-off, palm/pinch interaction, zoom, coalesced
input and long strokes; desktop tablet pressure on each OS/driver. Verify the
pressure curve feels useful at each selected stroke width. iOS runtime execution of
these new scenarios remains pending; synthetic Chromium events and Compose
pixel tests do not establish physical-device behavior.

## Multi-select and move (checkpoint 4e)

Select (V) is available on web, desktop and mobile. Click/tap an object to select
it, or drag a box that fully encloses several objects. Drag a selected object to
move the group. Web/desktop Shift-click toggles individual objects. Escape,
changing tools, or adding a second touch cancels an unfinished move. Selection
outlines and movement previews are device-local; peers receive the committed move
on release.

Moves preserve IDs, text, pressure and other fields from the current document.
Objects deleted before the move is committed are skipped. Each move commits as
one CRDT update; if any object would exceed coordinate/document limits, the whole
move is rejected. Simultaneous edits to the same object retain the existing
whole-element CRDT conflict resolution; independent movement of object fields is
not claimed. A group move is atomic locally and sent as one update, while later
concurrent edits can still resolve independently per object.

Verified: shared geometry tests, web and Rust atomic rollback/delete/pressure
checks, JNI bindings, desktop pointer and mobile touch group moves, Escape/pinch
cancellation, production-browser toolbar and movement tests, and encrypted
bidirectional web/desktop moves with a remote edit/delete during drag and reload.
Android passed all eight tablet UI tests and the bidirectional encrypted move
scenario. Final local totals: 54 web unit tests, 59 desktop/common/JNI tests,
seven production-browser drawing tests plus a focused final selection rerun,
Rust format/Clippy/tests/docs and Android APK builds. Apple C binding tests
and the same mixed-peer scenario are wired into CI but cannot run on Linux.

Mac/iPad follow-up: marquee selection in both directions, Shift-click where a
keyboard is attached, touch group movement, pinch cancellation, pressure/text
preservation, and remote deletion during a move. No object resize/rotation,
attachment routing, or undo is introduced by this increment.

## CI test-server isolation (2026-11-08)

The Linux web/desktop job in run
[42522240765](https://github.com/slashmili/meshboard/actions/runs/42522240765)
failed at commit `cfb72d4`, before the pressure scenario could join. Its Playwright
network trace records HTTP 429 for `/api/rtc-config`. Earlier scenarios consumed
the shared server's 30-requests-per-minute credential budget. Faster runners could
hit the limit, while slower runs crossed the expiry window. Both macOS jobs and
the Android job passed at that commit; this failure was not a pressure assertion.

Interop scenarios now own a fresh Vite/signaling server, started before peer
fixtures and closed after their cleanup. They use port 5174 and fail if it is
already occupied instead of reusing an unrelated development server. Desktop,
Android and iOS suites use the same fixture. Production rate limits are unchanged.
Two regression scenarios each use all 30 allowed requests, check that request 31
returns 429 and verify health stays available. With the former shared server,
the second scenario fails on its first request; with isolation both pass.

Validated locally: the regression fails before the fixture change and passes
after it; all 15 desktop interoperability scenarios, all 15 signaling tests and
workspace typechecks pass. The new fixture still needs the next remote CI run
for macOS/Android/iPad validation. No application code or rate limit was changed.
