#!/usr/bin/env bash
# Fetch + verify upstream arm64 components and install them into
# app/src/main/assets/arm64/. Requires network access, curl, git, unzip, tar,
# Go 1.27.1, make, sha256sum and Android NDK r27c (27.2.12479018).
# Pins (verified 2026-11-21; see upstream/UPSTREAM.md for archive hashes):
#   sing-box v1.14.4 android-arm64 tar.gz, EasyTier v2.6.4 linux-aarch64 zip,
#   cloudflared 2026.11.1 linux-arm64, wstunnel v11.1.0 android_arm64 tar.gz,
#   amneziawg-go tag v3.1.20261116 at commit
#   08fe3d33bbb116be09a6183d02609311b8b0c9e8 (keep the tag for Go metadata).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/app/src/main/assets/arm64"
AWG_GO_TAG=v3.1.20261116
AWG_GO_COMMIT=08fe3d33bbb116be09a6183d02609311b8b0c9e8
AWG_TOOLS_COMMIT=ee0f0a9aa34ff0a0da4b3433b9512781cfe02843
# Match the toolchains that produced the reviewed binary hashes. Do not let
# Go silently download/select a different toolchain or inherit extra flags.
export GOTOOLCHAIN=local GOENV=off GOWORK=off GOFLAGS=
[[ "$(go env GOVERSION)" == go1.27.1 ]] || { echo 'Go 1.27.1 is required by the binary lock' >&2; exit 5; }
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must point at Android NDK r27c}"
grep -Eq '^Pkg.Revision[[:space:]]*=[[:space:]]*27\.2\.12479018[[:space:]]*$' "$ANDROID_NDK_HOME/source.properties" || { echo 'Android NDK 27.2.12479018 is required by the binary lock' >&2; exit 5; }
mkdir -p "$OUT"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
dl() { curl --fail --location --retry 3 --proto '=https' --tlsv1.2 "$1" -o "$2"; }
chk() { echo "$2  $1" | sha256sum -c -; }
# sing-box (archive hash fb3a6c5197f4044f82c8fd75e01ce14c28b6abdd4c3df84bb987d41a962a320f)
dl "https://github.com/SagerNet/sing-box/releases/download/v1.14.4/sing-box-1.14.4-android-arm64.tar.gz" "$tmp/sing-box.tar.gz"
chk "$tmp/sing-box.tar.gz" fb3a6c5197f4044f82c8fd75e01ce14c28b6abdd4c3df84bb987d41a962a320f
tar xzf "$tmp/sing-box.tar.gz" -C "$tmp"
install -m 0755 "$tmp/sing-box-1.14.4-android-arm64/sing-box" "$OUT/sing-box"
# EasyTier (archive hash f533ec25a7ea714e09f645615012200278058525795cc3bb690ff011aec1a70f)
dl "https://github.com/EasyTier/EasyTier/releases/download/v2.6.4/easytier-linux-aarch64-v2.6.4.zip" "$tmp/easytier.zip"
chk "$tmp/easytier.zip" f533ec25a7ea714e09f645615012200278058525795cc3bb690ff011aec1a70f
unzip -o -q "$tmp/easytier.zip" -d "$tmp/easytier"
install -m 0755 "$tmp/easytier/easytier-linux-aarch64/easytier-core" "$OUT/easytier-core"
# cloudflared (direct binary; hash b155cec3e48e489cfb8e5c602769fb8c771083018d8881d68b4d18a791567c5a)
dl "https://github.com/cloudflare/cloudflared/releases/download/2026.11.1/cloudflared-linux-arm64" "$OUT/cloudflared"
chmod 0755 "$OUT/cloudflared"; chk "$OUT/cloudflared" b155cec3e48e489cfb8e5c602769fb8c771083018d8881d68b4d18a791567c5a
# wstunnel (archive hash 6f00cc63740f1602168e5d6a5b0bc5dab6bbe77ef613e2b11fe68f881641ce7f, matches official checksums.txt)
dl "https://github.com/erebe/wstunnel/releases/download/v11.1.0/wstunnel_11.1.0_android_arm64.tar.gz" "$tmp/wstunnel.tar.gz"
chk "$tmp/wstunnel.tar.gz" 6f00cc63740f1602168e5d6a5b0bc5dab6bbe77ef613e2b11fe68f881641ce7f
tar xzf "$tmp/wstunnel.tar.gz" -C "$tmp"
install -m 0755 "$tmp/wstunnel" "$OUT/wstunnel"
# amneziawg-go: fetch the release TAG, not a bare commit/default branch.
# Go embeds a different main-module version when the tag is absent.
git clone --depth 1 --single-branch --branch "$AWG_GO_TAG" \
  https://github.com/amnezia-vpn/amneziawg-go.git "$tmp/amneziawg-go"
[[ "$(git -C "$tmp/amneziawg-go" rev-parse HEAD)" == "$AWG_GO_COMMIT" && \
   "$(git -C "$tmp/amneziawg-go" rev-parse "refs/tags/$AWG_GO_TAG^{commit}")" == "$AWG_GO_COMMIT" && \
   "$(git -C "$tmp/amneziawg-go" describe --tags --exact-match HEAD)" == "$AWG_GO_TAG" ]] || {
  echo 'amneziawg-go tag/commit mismatch; refusing to change the lock' >&2; exit 5;
}
printf 'amneziawg-go source: %s %s\n' "$AWG_GO_TAG" "$AWG_GO_COMMIT"
(cd "$tmp/amneziawg-go" && GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build -trimpath -o "$OUT/amneziawg-go" .)
go version -m "$OUT/amneziawg-go"
chmod 0755 "$OUT/amneziawg-go"

# awg: AmneziaWG control tool (amneziawg-tools); the daemon exposes only a UAPI
# socket, config is applied via `awg setconf`. No upstream release; build from
# source at pinned commit with the Android NDK (requires NDK r27c+ installed,
# ANDROID_NDK_HOME set; sdkmanager "ndk;27.2.12479018"). Fetch the pinned
# tools commit explicitly, so later default-branch changes cannot break CI.
git init -q "$tmp/amneziawg-tools"
git -C "$tmp/amneziawg-tools" remote add origin https://github.com/amnezia-vpn/amneziawg-tools.git
git -C "$tmp/amneziawg-tools" fetch --depth 1 origin "$AWG_TOOLS_COMMIT"
git -C "$tmp/amneziawg-tools" -c advice.detachedHead=false checkout --detach FETCH_HEAD
[[ "$(git -C "$tmp/amneziawg-tools" rev-parse HEAD)" == "$AWG_TOOLS_COMMIT" ]] || { echo 'amneziawg-tools commit mismatch' >&2; exit 5; }
NDK_CC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android33-clang"
NDK_AR="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar"
CC="$NDK_CC" AR="$NDK_AR" PKG_CONFIG=/bin/false \
make -C "$tmp/amneziawg-tools/src" -j4 CC="$NDK_CC" AR="$NDK_AR" PLATFORM=linux WITH_BASHCOMPLETION= WITH_WGQUICK= WITH_SYSTEMDUNITS= RUNSTATEDIR=/data/local/tmp
cp "$tmp/amneziawg-tools/src/wg" "$OUT/awg"
chmod 0755 "$OUT/awg"
# Verify every installed binary against the unchanged lock. The standalone
# verifier is also run after CI artifact transfer, before the Android build.
bash "$ROOT/scripts/verify-upstream.sh"
echo "Upstream assets installed and verified under $OUT"
