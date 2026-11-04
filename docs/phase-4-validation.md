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
- Stylus: Apple Pencil, Android pressure stylus and desktop tablet hardware once
  pressure support is implemented; synthetic events cannot establish device feel.

These checks are pending, not implied by Linux or shared Compose tests. Session
recovery and the other increments above are not implemented by appearance work.
