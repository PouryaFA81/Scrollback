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
if [[ ! -s "$pat" ]]; then
  echo "check-private: no pattern file ($pat); skipping. Create it locally to enable the check." >&2
  exit 0
fi
list=$(mktemp); trap 'rm -f "$list"' EXIT
grep -vE '^\s*(#|$)' "$pat" > "$list" || true
[[ -s "$list" ]] || { echo "check-private: $pat has no patterns; skipping." >&2; exit 0; }
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
