#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCK="$ROOT/upstream/SHA256SUMS.lock"
OUT="$ROOT/app/src/main/assets/arm64"
mkdir -p "$OUT"

if [[ ! -s "$LOCK" ]]; then
  echo "upstream/SHA256SUMS.lock is empty. Add reviewed fixed-version HTTPS URLs and independently verified SHA-256 values first." >&2
  exit 2
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
: > "$tmp/SHA256SUMS"

while IFS=$'\t' read -r name version url expected; do
  [[ -z "${name:-}" || "$name" == \#* ]] && continue
  [[ "$name" =~ ^(amneziawg-go|sing-box|easytier-core|cloudflared|wstunnel)$ ]] || { echo "Unsupported component: $name" >&2; exit 3; }
  [[ "$version" =~ ^[vV]?[0-9][A-Za-z0-9._+-]*$ ]] || { echo "Invalid pinned version for $name" >&2; exit 3; }
  case "$name:$url" in
    amneziawg-go:https://github.com/amnezia-vpn/amneziawg-go/releases/download/*|\
    sing-box:https://github.com/SagerNet/sing-box/releases/download/*|\
    easytier-core:https://github.com/EasyTier/EasyTier/releases/download/*|\
    cloudflared:https://github.com/cloudflare/cloudflared/releases/download/*|\
    wstunnel:https://github.com/erebe/wstunnel/releases/download/*) ;;
    *) echo "URL is not from the official release repository for $name" >&2; exit 3 ;;
  esac
  [[ "$expected" =~ ^[a-fA-F0-9]{64}$ ]] || { echo "Missing reviewed SHA-256 for $name $version" >&2; exit 3; }
  file="$tmp/$name.download"
  curl --fail --location --retry 3 --proto '=https' --tlsv1.2 "$url" -o "$file"
  actual="$(sha256sum "$file" | cut -d ' ' -f 1)"
  [[ "${actual,,}" == "${expected,,}" ]] || { echo "SHA-256 mismatch for $name $version" >&2; exit 4; }
  install -m 0755 "$file" "$OUT/$name"
  printf '%s  %s\n' "$actual" "$name" >> "$tmp/SHA256SUMS"
  printf '%s\t%s\t%s\t%s\n' "$name" "$version" "$url" "$actual"
done < "$LOCK"

for required in amneziawg-go sing-box easytier-core cloudflared wstunnel; do
  grep -q "  $required$" "$tmp/SHA256SUMS" || { echo "Pinned asset missing: $required" >&2; exit 5; }
done
install -m 0644 "$tmp/SHA256SUMS" "$OUT/SHA256SUMS"
echo "Installed and verified assets under $OUT"
