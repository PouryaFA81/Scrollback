#!/usr/bin/env bash
# Builds the debug APK (and the signed release APK when keystore/ exists), copies them to dist/,
# and keeps timestamped backups in dist/archive/ (never overwrites older ones).
#   --install   adb-install the debug APK when a device is authorised
#   --no-release  skip the release build
set -euo pipefail
cd "$(dirname "$0")/.."

install=0; release=1
for a in "$@"; do
  case "$a" in --install) install=1 ;; --no-release) release=0 ;; esac
done
[[ -f keystore/keystore.properties ]] || release=0

tasks=(assembleDebug); (( release )) && tasks+=(assembleRelease)
./gradlew --console=plain "${tasks[@]}"

version=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -1)
stamp=$(date +%Y%m%d-%H%M)
mkdir -p dist/archive

archive() { # $1 = source apk, $2 = label (e.g. universal-debug, arm64-v8a-release)
  local out="dist/archive/scrollback-${version}-${stamp}-$2.apk" n=1
  while [[ -e "$out" ]]; do out="dist/archive/scrollback-${version}-${stamp}-${n}-$2.apk"; n=$((n + 1)); done
  cp "$1" "$out"
  cp -f "$1" "dist/scrollback-$2.apk"
  printf 'Archived: %s (%s)\n' "$(pwd)/$out" "$(du -h "$1" | cut -f1)"
}

# Debug: only the universal APK is kept (it is what gets installed for testing).
archive app/build/outputs/apk/debug/app-universal-debug.apk universal-debug
cp -f dist/scrollback-universal-debug.apk dist/scrollback-debug.apk
if (( release )); then
  for abi in arm64-v8a armeabi-v7a universal; do
    rel=app/build/outputs/apk/release/app-$abi-release.apk
    [[ -f "$rel" ]] || { echo "Release APK missing: $rel (unsigned?)" >&2; exit 1; }
    archive "$rel" "$abi-release"
  done
fi
rm -f dist/scrollback-release.apk

if (( install )); then
  if adb get-state >/dev/null 2>&1; then
    adb install -r dist/scrollback-debug.apk
    (( release )) && adb install -r dist/scrollback-arm64-v8a-release.apk || true
  else
    echo "No authorized device found (check 'adb devices'); skipped install."
  fi
fi
