#!/usr/bin/env bash
# Verify exactly the six reviewed binaries; print a machine-readable TSV.
# Every row is checked even if an earlier binary is missing or corrupt.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCK="$ROOT/upstream/SHA256SUMS.lock"
OUT="$ROOT/app/src/main/assets/arm64"
declare -A seen=()
failed=0
printf 'component\texpected_sha256\tactual_sha256\tresult\n'
while IFS=$'\t' read -r name version url expected; do
  [[ -z "${name:-}" || "$name" == \#* ]] && continue
  case "$name" in
    sing-box|easytier-core|cloudflared|wstunnel|amneziawg-go|awg) ;;
    *) echo "Unsupported lock component: $name" >&2; exit 3 ;;
  esac
  [[ -z "${seen[$name]:-}" && "$expected" =~ ^[a-f0-9]{64}$ ]] || {
    echo "Duplicate component or invalid SHA-256: $name" >&2; exit 3;
  }
  seen[$name]=1
  actual=MISSING
  result=FAIL
  if [[ -f "$OUT/$name" && ! -L "$OUT/$name" ]]; then
    actual="$(sha256sum "$OUT/$name")"
    actual="${actual%% *}"
    [[ "$actual" != "$expected" ]] || result=PASS
  fi
  [[ "$result" == PASS ]] || failed=1
  printf '%s\t%s\t%s\t%s\n' "$name" "$expected" "$actual" "$result"
done < "$LOCK"
[[ ${#seen[@]} -eq 6 ]] || { echo 'The lock must contain exactly six components' >&2; exit 3; }
# The App consumes this second manifest. Require exact semantic agreement
# with the lock as well, so CI and runtime verification cannot diverge.
if ! diff -u <(awk -F'\t' '!/^#/ && NF {print $4 "  " $1}' "$LOCK" | LC_ALL=C sort) \
             <(LC_ALL=C sort "$OUT/SHA256SUMS") >&2; then
  echo 'Runtime SHA256SUMS differs from the reviewed lock' >&2
  failed=1
fi
exit "$failed"
