#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Pourya FA
# Fails if a tracked file (or a tag/release notes file you pass in) contains a private detail
# from the maintainer's own setup: personal domains, home IPs, server accounts, library sizes, etc.
# The patterns live in .private-patterns (gitignored, one extended regex per line, # for comments),
# so the patterns themselves never reach GitHub.
# Usage: scripts/check-private.sh [extra-file ...]
set -euo pipefail
cd "$(dirname "$0")/.."
pat="${PRIVATE_PATTERNS:-.private-patterns}"
list=$(mktemp); trap 'rm -f "$list"' EXIT
# Built-in rule (always on): the maintainer's bare domain and the public contact address
# admin@homelabgate.lol may appear, but no subdomain of it (those are private services).
# Any label right before ".homelabgate.lol" (a hostname like <service>.homelabgate.lol) is caught.
echo '[a-z0-9_-]\.homelabgate\.lol' > "$list"
if [[ -s "$pat" ]]; then
  grep -vE '^\s*(#|$)' "$pat" >> "$list" || true
else
  echo "check-private: no pattern file ($pat); only the built-in rules run. Create it locally for the full check." >&2
fi
found=0
if git grep -nIiE -f "$list" -- . ':!.private-patterns'; then found=1; fi
for f in "$@"; do
  if grep -nHIiE -f "$list" "$f"; then found=1; fi
done
if [[ $found -ne 0 ]]; then
  echo "check-private: private details found above. Remove or genericize them before pushing." >&2
  exit 1
fi
echo "check-private: clean."
