#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${ANDROID_TUNNEL_STORE_PASSWORD:?Set ANDROID_TUNNEL_STORE_PASSWORD to a private development password}"
: "${ANDROID_TUNNEL_KEY_PASSWORD:?Set ANDROID_TUNNEL_KEY_PASSWORD to a private key password}"
: "${ANDROID_TUNNEL_KEY_ALIAS:?Set ANDROID_TUNNEL_KEY_ALIAS, e.g. android-tunnel-dev}"
OUT="${ANDROID_TUNNEL_KEYSTORE_OUT:-$ROOT/android-tunnel-dev.jks}"
keytool -genkeypair -v -keystore "$OUT" -storetype PKCS12 -alias "$ANDROID_TUNNEL_KEY_ALIAS" \
  -keyalg RSA -keysize 3072 -validity 3650 -dname "CN=Android Tunnel Development, OU=Development, O=Local Build, C=XX" \
  -storepass "$ANDROID_TUNNEL_STORE_PASSWORD" -keypass "$ANDROID_TUNNEL_KEY_PASSWORD"
chmod 600 "$OUT"
echo "Development keystore created at $OUT; replace it with your own release key before publishing."
