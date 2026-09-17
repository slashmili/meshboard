# CI and desktop releases

## Pull requests and main

The **Tests** workflow runs on every pull request and push to `main`. It can
also be started manually from Actions. It runs the existing automated suites:

| Runner | Checks |
| --- | --- |
| Linux x86_64, macOS Apple Silicon, macOS Intel | TypeScript checks, protocol/signaling/web unit tests, release-script tests, web production build, Kotlin/common and desktop UI tests, browser end-to-end tests, browser/desktop interoperability |
| Linux Android emulator | Kotlin Android unit tests, instrumented UI tests, browser/Android/desktop interoperability, opt-in CRDT JNI and Yjs compatibility on an Android 15 Nexus 10 tablet |
| Apple Silicon with Xcode 26.6 | Kotlin iOS simulator tests, iPad/browser/macOS interoperability, unsigned physical-device compilation |

Interop tests include real local Coturn relay traffic. CI never needs production
TURN credentials or contacts the production deployment. Failing test reports,
Playwright traces and TURN logs are uploaded to the workflow run for seven days.
Windows and physical-device validation are not covered by this workflow.
CI lists the SDK hardware profiles and checks for Nexus 10 before creating its
emulator. The local `pnpm emulator:android` setup remains Pixel Tablet.

Desktop/web jobs also install stable Rust and run the isolated Yjs/yrs binary
compatibility suite through `pnpm test`. This validates the Phase 2 prototype,
not CRDT integration into the currently shipped apps. `pnpm test:crdt:kotlin`
also builds the opt-in JNI library for each desktop runner's JVM architecture,
runs Kotlin lifecycle/validation/concurrency tests, and repeats the seven Yjs
scenarios through Kotlin and JNI. The library is not packaged into releases yet.
`pnpm test:interop:crdt` then tests the opt-in local preview through real WebRTC
and TURN on a separate development server (port 5174), including four-peer
convergence and legacy/preview protocol isolation. The preview uses actual
y-webrtc in the browser; tests also exercise an unmodified upstream provider's
raw sync/awareness against desktop and rejection of invalid updates. Large
Meshboard messages use the documented, negotiated fragmentation extension.

The Android job installs NDK 28.2.13676358 and Rust targets for x86_64/ARM64,
then runs `pnpm test:crdt:android`: six JNI tests and the same seven Yjs scenarios
inside the x86_64 tablet emulator. Both Android ABIs are compiled; ARM64 device
execution is not claimed. This debug-only checkpoint leaves default sync and
release APK contents unchanged. Its instrumentation reports are kept separately
from UI reports, and the Yjs/instrumentation console log is included in CI artifacts.
`pnpm test:interop:android:crdt` additionally runs six tablet UI tests in the
opt-in preview and six live Android/browser/desktop CRDT scenarios, including
large snapshots, four-peer convergence, TURN and unmodified upstream-provider
interoperability. Its log is `build/ci/android-crdt-interop.log`; preview UI
reports are kept under `androidApp/build/reports/androidTests/crdt-preview`.

iOS interop scenarios retain a 60-second test timeout with no automatic retries.
Simulator startup and cleanup run in a separate 120-second Playwright fixture
budget; each asynchronous `simctl` command is capped at 30 seconds. The fixture
also allows 20 seconds for the debug adapter to become ready and cleans up failed
startup attempts. Desktop-peer and extra-browser cleanup use their own fixture
budgets. Traces label setup, board-sync stages, and shutdown separately, so a slow
simulator shutdown does not turn successful board assertions into a test timeout.

For merge enforcement, select these checks in the repository's branch protection
or ruleset after their first run. Running CI alone does not prevent merging a
failed pull request.

## CI caches

- pnpm dependencies are cached by the Node setup action.
- Gradle saves its downloaded dependencies, wrapper, compiled build scripts and
  local task-output cache through `setup-gradle` in every job, including PRs.
  CI enables Gradle's build cache; its configuration cache remains disabled.
- The iOS job caches `~/.konan` (Kotlin/Native compiler, native dependencies and
  compiler caches), keyed by OS, CPU architecture, Xcode/SDK builds and Gradle
  configuration, including the Kotlin version.
- The iOS job also caches `packages/native/build/SourcePackages`, matching the
  Swift package checkout and binary-artifact directory used by `scripts/ios.mjs`.
  Its key includes OS/architecture, Xcode/SDK builds, `Package.resolved` and the
  Xcode project. Simulator and physical-device builds use that same directory.

The first successful run populates the new iOS caches. Later runs of the same PR
can restore them; a successful `main` run seeds caches that other branches and
releases can read. GitHub isolates PR-created caches from `main`, releases and
other PRs. No extra token permissions or repository secrets are needed.

Cache keys invalidate when the relevant toolchain or dependency configuration
changes. The iOS dependency caches deliberately have no broad fallback keys
that could restore a different toolchain. Xcode DerivedData, simulator state,
signing credentials and final app packages are not cached. Builds and tests still
run through the same commands; cache hits do not bypass a workflow step, though
Gradle can reuse cacheable task outputs with matching inputs. Cold builds,
simulator boot and uncached work still take time, so compare the next two runs
rather than expecting the first run to be faster.

See [GitHub cache isolation](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching#restrictions-for-accessing-a-cache)
and [Gradle action caching](https://github.com/gradle/actions/blob/v5/docs/setup-gradle.md#caching-build-state-between-jobs).

## Configure a deployment for release builds

In GitHub, open **Settings → Secrets and variables → Actions → Variables** and
create these **repository variables** (not secrets):

| Variable | Example |
| --- | --- |
| `MESHBOARD_APP_ORIGIN` | `https://board.example.com` |
| `MESHBOARD_TURN_DOMAIN` | `turn.example.com` |

Use your own domains. No installation-specific domain or credential is committed
to the repository. Missing/invalid variables stop the release before building.

`MESHBOARD_APP_ORIGIN` is embedded in the desktop launcher and is used for new
board invites, signaling and `/api/rtc-config`. Joining a link retains the
link's own origin. Local development still defaults to `http://127.0.0.1:5173`;
the `MESHBOARD_APP_ORIGIN` environment variable can override a packaged app's
default at runtime.

`MESHBOARD_TURN_DOMAIN` records the intended TURN host in `release-info.json`.
It is **not a baked-in TURN configuration**: the clients already fetch TURN URLs
and credentials from the app origin's `/api/rtc-config` endpoint. Configure the
server's `MESHBOARD_TURN_URLS` to advertise this host, with working credentials
and the ports/transports your TURN service actually exposes. The release workflow
does not provision DNS, certificates, servers or credentials, or validate the live
deployment. Never put TURN credentials or board keys in build variables.

## Publish a release

1. Merge the workflows and packaging changes and get a green Tests run.
2. Create a tag on that commit such as `v0.1.0` or `v0.1.0-rc.1` and publish its
   GitHub Release. A tag push alone or a saved draft does not trigger packaging;
   publishing a prerelease does.
3. **Release desktop apps** reruns all tests against the release commit. Only
   after those pass does it build Linux and both macOS architectures.
4. After every package passes its launcher smoke test, the workflow attaches:
   - `Meshboard-<tag>-linux-x86_64.AppImage`
   - `Meshboard-<tag>-macos-arm64.dmg`
   - `Meshboard-<tag>-macos-x86_64.dmg`
   - `SHA256SUMS` and `release-info.json`

The macOS DMGs contain the Meshboard `.app`; these are architecture-specific,
not universal binaries. All desktop packages bundle Java, so users do not need
to install a JVM. No Android APK or iOS IPA is published by this workflow.

The workflow uses GitHub's automatically supplied token; no personal access
token is needed. Only the final upload job has `contents: write`. A successful
rerun replaces same-named assets. Failed builds leave the published release
without new packages; inspect Actions and rerun after fixing the failure.
Do not enable **immutable releases** with this publish-then-attach workflow:
those require a different build-to-draft, then-publish sequence.

macOS packages are **not Developer ID signed or notarized**. Gatekeeper may block
them; only approve a downloaded app you trust through macOS Privacy & Security.
Public distribution without this warning needs a separate Apple signing and
notarization setup. Do not disable Gatekeeper globally.

Package versions use the tag's numeric `major.minor.patch` portion. Prerelease
suffixes remain in asset names. Apple's package format disallows a zero major,
so `0.x.y` tags use `1.x.y` for the internal macOS package version only.

## Local Linux packaging checkpoint

Use Node 22+, pnpm, JDK 21, `curl`, `sha256sum`, `tar`, and desktop graphics/audio
libraries. On Ubuntu 22.04 the packaging workflow installs `desktop-file-utils`,
`libasound2`, `libgl1`, `libegl1`, and `libfontconfig1`.

```sh
export RELEASE_TAG=v0.1.0
export RELEASE_APP_ORIGIN=https://board.example.com
export RELEASE_TURN_DOMAIN=turn.example.com
pnpm install --frozen-lockfile
./packages/native/gradlew -p packages/native createDistributable \
  "-Pmeshboard.appOrigin=$RELEASE_APP_ORIGIN" -Pmeshboard.version=0.1.0
bash scripts/package-appimage.sh
```

The script downloads checksum-pinned AppImage tooling, creates the package in
`build/release/`, and executes its `--print-build-info` smoke test. It needs no
FUSE for packaging or the smoke test. Normal launch may require FUSE; alternatively:

```sh
chmod +x build/release/Meshboard-v0.1.0-linux-x86_64.AppImage
build/release/Meshboard-v0.1.0-linux-x86_64.AppImage --appimage-extract-and-run
```

Before treating the first release as validated, download each architecture's
package on the matching OS, launch it, create/join a board with the deployed web
app, and check drawing in both directions and TURN fallback. A launcher smoke
test does not replace this manual packaged-app checkpoint.
