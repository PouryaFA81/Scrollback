#!/usr/bin/env bash
# Builds the debug APK, copies it to dist/scrollback-debug.apk, and keeps a timestamped
# backup in dist/archive/ (never overwrites older ones). Optional: --install to adb-install it.
set -euo pipefail
cd "$(dirname "$0")/.."

./gradlew --console=plain assembleDebug

apk="app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$apk" ]] || { echo "APK not found at $apk" >&2; exit 1; }

version=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -1)
stamp=$(date +%Y%m%d-%H%M)
mkdir -p dist/archive
cp -f "$apk" dist/scrollback-debug.apk

archived="dist/archive/scrollback-${version}-${stamp}-debug.apk"
n=1
while [[ -e "$archived" ]]; do
  archived="dist/archive/scrollback-${version}-${stamp}-${n}-debug.apk"; n=$((n + 1))
done
cp "$apk" "$archived"

echo "Latest:   $(pwd)/dist/scrollback-debug.apk"
echo "Archived: $(pwd)/$archived"

if [[ "${1:-}" == "--install" ]]; then
  if adb get-state >/dev/null 2>&1; then
    adb install -r dist/scrollback-debug.apk
  else
    echo "No authorized device found (check 'adb devices'); skipped install."
  fi
fi
