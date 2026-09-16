#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/ci

case "${1:-}" in
  desktop|android|ios) ;;
  *) echo 'Usage: bash scripts/ci-tests.sh {desktop|android|ios}' >&2; exit 1;;
esac

# Own one local-only relay for the entire test step. Never use production TURN
# credentials or the public deployment in PR jobs (including untrusted forks).
turnserver -c "$PWD/infra/turn/dev.conf" > build/ci/turn.log 2>&1 &
CI_TURN_PID=$!
cleanup() {
  kill "$CI_TURN_PID" 2>/dev/null || true
  wait "$CI_TURN_PID" 2>/dev/null || true
}
trap cleanup EXIT
for attempt in {1..30}; do
  if ! kill -0 "$CI_TURN_PID" 2>/dev/null; then cat build/ci/turn.log; exit 1; fi
  if node -e 'const net=require("node:net"); const s=net.connect(3478,"127.0.0.1",()=>{s.end();process.exit(0)});s.on("error",()=>process.exit(1));s.setTimeout(500,()=>process.exit(1))'; then break; fi
  if [ "$attempt" = 30 ]; then cat build/ci/turn.log; exit 1; fi
  sleep 1
done

case "$1" in
  desktop)
    node --test scripts/*.test.mjs
    pnpm check
    pnpm test
    pnpm build
    pnpm test:native
    pnpm test:crdt:kotlin
    pnpm test:e2e
    pnpm test:interop
    pnpm test:interop:crdt
    ;;
  android)
    ./packages/native/gradlew -p packages/native -Pmeshboard.android=true testDebugUnitTest :androidApp:testDebugUnitTest
    pnpm test:android
    pnpm test:interop:android
    pnpm test:crdt:android | tee build/ci/android-crdt.log
    ;;
  ios)
    ./packages/native/gradlew -p packages/native -Pmeshboard.ios=true iosSimulatorArm64Test
    pnpm test:interop:ios
    pnpm build:ios:device
    ;;
esac
