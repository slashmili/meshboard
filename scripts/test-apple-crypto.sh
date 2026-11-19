#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
crypto_scratch="$(mktemp -d)"
trap 'rm -rf "$crypto_scratch"' EXIT
xcrun swiftc "$project_root/packages/native/iosApp/Meshboard/SessionCipher.swift" \
  "$project_root/packages/native/iosApp/tests/SessionCipherTests.swift" -o "$crypto_scratch/crypto-tests"
"$crypto_scratch/crypto-tests"
