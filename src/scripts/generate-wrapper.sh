#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_BIN="${GRADLE_BIN:-gradle}"
"$GRADLE_BIN" -p "$ROOT" wrapper --gradle-version 8.11.1 --distribution-type bin
test -s "$ROOT/gradle/wrapper/gradle-wrapper.jar"
test -x "$ROOT/gradlew"
echo "Gradle 8.11.1 wrapper generated in $ROOT"
