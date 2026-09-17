# Local cross-platform CRDT previews — checkpoints 2d, 2f and 2h

This opt-in checkpoint uses the actual **y-webrtc 10.3.0 provider** in the browser
and compatible signaling, binary Yjs sync, and awareness on the yrs/Kotlin desktop/mobile
side. It is **local-only**, separate from the released v0.1.0 apps.
Default mobile builds remain on the old protocol. Do not deploy or use sensitive
drawings. Android/iOS previews are emulator/simulator-only and explicitly enabled below.

## Try on Linux (Mac validation pending)

Install the usual web/desktop requirements plus stable Rust and JDK 21. Run
`pnpm install --frozen-lockfile`. In separate terminals from the repository root:

```sh
pnpm turn          # Skip if meshboard-turn-dev is already running.
pnpm dev:crdt      # Dedicated localhost server on port 5174.
pnpm native:crdt   # Builds Rust and launches the opt-in desktop app.
```

Open <http://127.0.0.1:5174/?crdt=1>. The browser title area says **CRDT preview ·
local only**; the desktop window title identifies the preview too. Keep your
existing server/app on 5173 separate. If you set `MESHBOARD_APP_ORIGIN` globally,
unset it in the preview terminal or set it to `http://127.0.0.1:5174`.
Restart both preview processes after updating from checkpoint 2c: the old
running desktop/server cannot speak the new protocol. Create a fresh test board.

1. Draw in the browser, Share, and paste its invite into the preview desktop app.
2. Draw shapes and erase from both sides. Check previews and finished drawings.
3. Open the same invite in another browser window, then leave from the creator.
   Remaining peers should keep drawing.
4. Refresh one remaining browser while another peer stays connected. It should
   recover the current board, including deletions, without replaying gestures.
5. Try starting a board from the desktop and joining from the browser.

Pause here for feedback. Mac runs use the same commands, but have not been tried
locally. No changes to installed AppImage/DMG apps are required.

## Try the Android tablet — checkpoint 2f

Install the [Android CRDT toolchain](../packages/native/androidApp/README.md#opt-in-android-crdt-bindings--checkpoint-2e)
first. Keep `pnpm dev:crdt` and the local TURN service running. In separate terminals:

```sh
pnpm emulator:android  # Pixel Tablet; skip if already running
pnpm android:crdt      # Opt-in debug build; starts on the tablet
```

The compact header must say **Meshboard · CRDT preview · local only**. Share uses
`http://127.0.0.1:5174` by default; keep that address when creating invites.
Debug emulator networking maps it to the host without changing the invite.

1. Create a board at <http://127.0.0.1:5174/?crdt=1>, then paste its full invite
   into Android's **Join** dialog. Draw shapes, change colors, and erase both ways.
2. Start a fresh board from Android's **Share → Create invite**. Join from web
   and optionally `pnpm native:crdt`; open another browser window for four peers.
3. Draw from several peers, then leave from the creator. Remaining peers should
   keep drawing. Reload a browser while another stays connected; erased strokes
   must not return. Rejoin Android using the same invite to recover current state.
4. Rotate the tablet and check that the drawing and live session survive.

Pause here for feedback. This is not the normal Android build and cannot join
v1 (`#room=`) invites or normal iOS sessions. It refuses non-local origins and requires
both an emulator and `localDevelopment` server configuration. Physical-device
CRDT sharing is not enabled. Only debug builds can set `CRDT_PREVIEW=true`;
ordinary/release builds never load the optional Rust library. `pnpm android`
reinstalls normal mode. Switching builds can end the in-memory session.

## Try the iPad simulator — checkpoint 2h

On an Apple Silicon Mac with the [iOS CRDT toolchain](../packages/native/iosApp/README.md#opt-in-crdt-bindings--checkpoint-2g),
keep the local TURN service and `pnpm dev:crdt` running. Launch `pnpm ios:crdt`.
The header must say **Meshboard · CRDT preview · local only** and Share should
use `http://127.0.0.1:5174`. Keep that origin; the simulator shares Mac loopback.

1. Create an invite in the web preview and join from the simulator. Copy the
   entire link, transfer the Mac clipboard with Simulator's Edit → Paste, then
   paste into Join. Draw/erase both ways and check live unfinished strokes.
2. Leave, create a fresh board from the simulator, and join from the browser,
   desktop preview and a separate browser window. Draw from several peers.
3. Leave from the simulator creator; remaining peers must keep drawing. Delete
   a stroke, reload one browser, then rejoin the simulator with the same invite.
   All peers should agree, and the deleted stroke must not return.
4. Rotate the simulator and confirm the live board survives.

Pause for feedback. The initial iOS binding CI passed; this live preview still
needs Apple CI and manual validation. Physical iPad CRDT sharing is not enabled.
The Swift host refuses non-local origins and production RTC configuration, and
the build script refuses device/Release preview requests. `pnpm ios` restores
normal mode; released apps are unchanged. No app-layer encryption yet.

## Automated checks

With the local TURN relay running:

```sh
pnpm test:crdt          # Rust/Yjs binary compatibility
pnpm test:crdt:kotlin   # Kotlin/JNI lifecycle plus the same compatibility cases
pnpm test:interop:crdt  # Real Chromium/desktop WebRTC and TURN
pnpm test:interop:android:crdt # Tablet UI, then Android/web/desktop WebRTC and TURN
pnpm test:interop:ios:crdt # On Mac: iPad simulator/browser/desktop WebRTC and TURN
```

The live suite covers web creation; native creation with a 12,000-point stroke;
two browser and two native replicas; concurrent drawings; creator departure and
rejoin; refresh recovery; forced TURN; and refusal of mismatched invite protocols.
It also runs an **unmodified upstream provider**, without Meshboard transport
hooks, against desktop for raw sync, deletion and awareness in both directions,
with both browser-created and desktop-created sessions.
An invalid upstream update must be rejected without changing the browser board.
The dedicated Playwright configuration starts the server on 5174 when needed.
Linux and both macOS CI desktop jobs include the desktop suite. Android CI also
runs six preview UI tests and six live scenarios: bidirectional updates and
awareness, retry, four mixed peers with a 12,000-point snapshot, creator departure,
reload/rejoin recovery, large messages through TURN, unmodified upstream peers
creating in either direction, invalid-update rejection, and local/protocol guards.
The iOS job now runs six equivalent CRDT preview scenarios in addition to v1
checks, plus four controller tests with the binding tests. Android and iOS
simultaneous execution is not covered by these separate CI jobs.
Network assertions have bounded 30-second budgets;
test retries are not used to hide failures.

## Protocol profile and isolation

- Invitations: `http://127.0.0.1:5174/?crdt=1#crdt=<UUID>` instead of `#room=…`.
- Signaling: `/signal-y-webrtc`, topics `meshboard-crdt:<UUID>`. Disabled when
  `localDevelopment` is false; never joins `/signal` or the old `/signal-crdt`.
  Supports y-webrtc subscribe/unsubscribe, announce, signal and ping/pong.
  The relay accepts only SDP/ICE and discovery metadata, binds sender IDs to a
  socket, caps each topic at eight participants, and limits size/rate/backlog.
- Sync: standard binary lib0/y-protocols messages: outer type 0, with inner
  step 0 (state vector), 1 (state response), or 2 (update). Delete-only differences
  synchronize even when state vectors have not advanced.
- Awareness: standard outer types 1 (update) and 3 (query), including clocks,
  removal, periodic refresh and expiry. The application field
  `meshboard: {peerId, preview}` carries ephemeral drawing previews, not CRDT state.
  Previews above 48 KiB are omitted; the completed stroke still synchronizes.
- Full mesh: all browser peers use WebRTC, with same-browser BroadcastChannel
  synchronization disabled after provider initialization. TURN settings come from
  the local `/api/rtc-config`, not upstream/public signaling or relay defaults.

### Large-message extension

Upstream y-webrtc sends a whole update in one data-channel message. That can exceed
SCTP limits for Meshboard's large atomic strokes/snapshots. Meshboard peers opt in
to fragmentation through the exact channel label `meshboard.y-webrtc.fragments-v1`
**and both peers advertising** the session-level SDP attribute
`a=meshboard-fragments:1`. A label alone is not consent: upstream providers can
accept an arbitrary channel name without understanding its extensions. Without
both capabilities, messages remain ordinary binary y-webrtc, with no envelope.

On that negotiated channel, messages over 16,379 bytes are split into at most
16 KiB frames: byte `127`, a four-byte big-endian total message length, then payload.
Ordered/reliable delivery, one in-flight assembly, a 30-second assembly deadline,
and a 2 MiB + 1 KiB message ceiling bound reassembly. Small messages stay unchanged.
Send queues are limited to 8 MiB per peer and honor channel backpressure.

This fragmentation is a **Meshboard extension**, not part of y-webrtc. The upstream
interop test deliberately uses small raw messages and an unmodified provider.
Large-board support with arbitrary third-party providers is not claimed. Password-
encrypted signaling, broadcast-channel peer discovery, physical-device CRDT and
application-layer security are outside this checkpoint. The pinned provider's
per-peer hooks are covered by the live tests; review them when upgrading y-webrtc.
Android creates ordered/reliable channels, but its current Java WebRTC API does
not expose reliability settings for received channels. This preview relies on
the matching peers' defaults; hostile DCEP/channel configuration needs further
hardening before non-local use.

## Validation and limits

Both implementations stage mutations on a separate CRDT document, validate the
result, and commit only if valid. Tests cover invalid elements, mismatched IDs,
foreign roots, stale snapshots, malformed updates and oversized input. This
preserves a healthy visible board after rejected input. Shape fields reuse the
existing schema; elements must remain atomic JSON, not shared nested types.

This preview caps binary document/update size and visible JSON at 2 MiB and uses
the existing 2,000-element/12,000-point limits. Native calls still have a separate
4 MiB JNI copy cap. Cloning for validation costs time proportional to document
size; mobile and larger-board performance need validation before wider use.
Awareness is bounded to 64 client identities per preview session and 64 KiB JSON
per client. Very long sessions with repeated browser reloads need a new board
once that identity budget is exhausted; this remains a preview limitation.

These checks are **not a complete hostile-input security boundary**: CRDT binary
decoder CPU/allocation behavior still needs fuzzing and isolation/resource-budget
work. Loopback-only clients and development-only signaling limit the checkpoint's
scope. Application-layer encryption and authenticated admission remain Phase 3.
