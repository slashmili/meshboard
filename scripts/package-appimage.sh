#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test "$(uname -s)" = Linux && test "$(uname -m)" = x86_64
: "${RELEASE_TAG:?Set RELEASE_TAG}"
# Validate before using a release tag in paths. Production settings are required.
node scripts/release-config.mjs > /dev/null

APPIMAGE_SOURCE="$PWD/packages/native/build/compose/binaries/main/app/Meshboard"
test -x "$APPIMAGE_SOURCE/bin/Meshboard"
APPIMAGE_WORK=$(mktemp -d)
trap 'rm -rf -- "$APPIMAGE_WORK"' EXIT
APPIMAGE_DIR="$APPIMAGE_WORK/Meshboard.AppDir"
mkdir -p "$APPIMAGE_DIR/usr/lib" build/release
cp -a "$APPIMAGE_SOURCE" "$APPIMAGE_DIR/usr/lib/meshboard"
install -m 0755 infra/appimage/AppRun "$APPIMAGE_DIR/AppRun"
install -m 0644 infra/appimage/meshboard.desktop "$APPIMAGE_DIR/meshboard.desktop"
install -m 0644 icons/meshboard-icon-1024.png "$APPIMAGE_DIR/meshboard.png"

# Versioned upstream assets and checked SHA-256s; do not execute a mutable
# "continuous" download or let appimagetool fetch an unpinned runtime itself.
curl --fail --location --retry 3 --output "$APPIMAGE_WORK/appimagetool" \
  https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage
curl --fail --location --retry 3 --output "$APPIMAGE_WORK/runtime" \
  https://github.com/AppImage/type2-runtime/releases/download/20251108/runtime-x86_64
printf '%s  %s\n' \
  ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0 "$APPIMAGE_WORK/appimagetool" \
  2fca8b443c92510f1483a883f60061ad09b46b978b2631c807cd873a47ec260d "$APPIMAGE_WORK/runtime" | sha256sum --check
chmod +x "$APPIMAGE_WORK/appimagetool"
APPIMAGE_OUTPUT="$PWD/build/release/Meshboard-${RELEASE_TAG}-linux-x86_64.AppImage"
ARCH=x86_64 "$APPIMAGE_WORK/appimagetool" --appimage-extract-and-run \
  --runtime-file "$APPIMAGE_WORK/runtime" "$APPIMAGE_DIR" "$APPIMAGE_OUTPUT"
chmod +x "$APPIMAGE_OUTPUT"

# Exercise the actual AppImage and bundled Java runtime, not the source tree.
"$APPIMAGE_OUTPUT" --appimage-extract-and-run --print-build-info | tee build/release/linux-build-info.txt
grep -Fx "App origin: $RELEASE_APP_ORIGIN" build/release/linux-build-info.txt
