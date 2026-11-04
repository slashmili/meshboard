#!/usr/bin/env bash
set -euo pipefail

# Hosted runners include several large NDKs. This job only uses the pinned one;
# reclaim the others before installing it and creating the Android 35 AVD.
sdk="${ANDROID_HOME:?ANDROID_HOME must point to the Android SDK}"
test -d "$sdk"
shopt -s nullglob
for ndk in "$sdk"/ndk/*; do
  if [[ -d "$ndk" && "${ndk##*/}" != '28.2.13676358' ]]; then
    echo "Removing unused CI NDK: $ndk"
    rm -rf -- "$ndk"
  fi
done
df -h "$sdk"
