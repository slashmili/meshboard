#!/bin/sh
set -eu
if [ "${OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED:-}" = "YES" ]; then exit 0; fi
cd "$SRCROOT/.."
# Xcode launched from Finder does not inherit an interactive shell's PATH.
export PATH="/opt/homebrew/bin:/usr/local/bin:$HOME/.local/bin:$PATH"
interop=false
if [ "${CONFIGURATION:-}" = "Debug" ] && [ "${PLATFORM_NAME:-}" = "iphonesimulator" ]; then interop=true; fi
crdt=false
if [ "${MESHBOARD_CRDT_PREVIEW:-0}" = "1" ]; then
    if [ "$interop" != "true" ]; then
        echo 'CRDT preview is Debug simulator-only; unset MESHBOARD_CRDT_PREVIEW for device/Release builds.' >&2
        exit 1
    fi
    crdt=true
    export PATH="$HOME/.cargo/bin:$PATH"
fi
if [ -z "${CI:-}" ] && command -v mise >/dev/null 2>&1; then
    exec mise exec java@temurin-21 -- ./gradlew -Pmeshboard.ios=true "-Pmeshboard.iosInterop=$interop" "-Pmeshboard.crdtIos=$crdt" "-Pmeshboard.crdtIosPreview=$crdt" embedAndSignAppleFrameworkForXcode
fi
exec ./gradlew -Pmeshboard.ios=true "-Pmeshboard.iosInterop=$interop" "-Pmeshboard.crdtIos=$crdt" "-Pmeshboard.crdtIosPreview=$crdt" embedAndSignAppleFrameworkForXcode
