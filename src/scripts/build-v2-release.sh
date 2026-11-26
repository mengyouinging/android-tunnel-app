#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set Android SDK root}"
: "${ANDROID_TUNNEL_KEYSTORE:?Set the existing release keystore path}"
: "${ANDROID_TUNNEL_STORE_PASSWORD:?Set release keystore password}"
: "${ANDROID_TUNNEL_KEY_ALIAS:?Set release key alias}"
: "${ANDROID_TUNNEL_KEY_PASSWORD:?Set release key password}"
export ANDROID_TUNNEL_KEYSTORE ANDROID_TUNNEL_STORE_PASSWORD ANDROID_TUNNEL_KEY_ALIAS ANDROID_TUNNEL_KEY_PASSWORD
bash scripts/verify-upstream.sh
if command -v gradle >/dev/null; then GRADLE=(gradle); else GRADLE=(bash gradlew); fi
"${GRADLE[@]}" testReleaseUnitTest assembleRelease assembleDebug --stacktrace
APK=app/build/outputs/apk/release/app-release.apk
test -f "$APK"
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs "$APK"
mkdir -p ../v2-build-artifacts
cp "$APK" ../v2-build-artifacts/android-tunnel-v2-release.apk
(
    cd ../v2-build-artifacts
    sha256sum android-tunnel-v2-release.apk > SHA256SUMS
    wc -c android-tunnel-v2-release.apk
)
