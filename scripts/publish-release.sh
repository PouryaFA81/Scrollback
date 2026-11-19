#!/usr/bin/env bash
# Publishes a GitHub Release for an existing pushed tag, attaching the debug and release APKs.
# Usage: scripts/publish-release.sh v0.6.0 [notes-file]
# APKs come from dist/scrollback-{arm64-v8a,armeabi-v7a,universal}-release.apk and
# dist/scrollback-universal-debug.apk (build them first with build-apk.sh).
set -euo pipefail
cd "$(dirname "$0")/.."
tag="$1"; notes="${2:-}"
ver="${tag#v}"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
assets=()
for kind in arm64-v8a-release armeabi-v7a-release universal-release universal-debug; do
  if [[ -f "dist/scrollback-$kind.apk" ]]; then
    cp "dist/scrollback-$kind.apk" "$tmp/scrollback-$ver-$kind.apk"
    assets+=("$tmp/scrollback-$ver-$kind.apk")
  fi
done
(cd "$tmp" && sha256sum *.apk > SHA256SUMS) && assets+=("$tmp/SHA256SUMS")
if [[ -z "$notes" ]]; then
  notes="$tmp/notes.md"
  git tag -l --format='%(contents)' "$tag" > "$notes"
fi
gh release view "$tag" >/dev/null 2>&1 || gh release create "$tag" --title "Scrollback $ver" --notes-file "$notes" --verify-tag
# Asset uploads one by one. The local HTTP proxy resets large uploads, so try a direct connection
# first and fall back to the proxy.
for f in "${assets[@]}"; do
  env -u HTTPS_PROXY -u https_proxy -u HTTP_PROXY -u http_proxy -u ALL_PROXY -u all_proxy \
    timeout 600 gh release upload "$tag" "$f" --clobber || gh release upload "$tag" "$f" --clobber
done
gh release view "$tag" --json assets --jq '.assets[] | "\(.name) \(.size)"'
