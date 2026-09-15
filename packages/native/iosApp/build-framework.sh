#!/bin/sh
set -eu
if [ "${OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED:-}" = "YES" ]; then exit 0; fi
cd "$SRCROOT/.."
# Xcode launched from Finder does not inherit an interactive shell's PATH.
export PATH="/opt/homebrew/bin:/usr/local/bin:$HOME/.local/bin:$PATH"
if command -v mise >/dev/null 2>&1; then
    exec mise exec java@temurin-21 -- ./gradlew -Pmeshboard.ios=true embedAndSignAppleFrameworkForXcode
fi
exec ./gradlew -Pmeshboard.ios=true embedAndSignAppleFrameworkForXcode
