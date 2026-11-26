#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Dedicated fresh emulator only: this script must not clear a real user's tunnel settings.
if [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]]; then
    echo "Run on a dedicated Android emulator, not a real phone." >&2
    exit 1
fi
PKG=org.example.androidtunnel
DEST=../v2-build-artifacts
mkdir -p "$DEST"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm clear "$PKG"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
adb shell am start -W -n "$PKG/.MainActivity"
# Resolve actual control coordinates from UI hierarchy rather than hard-coding screen sizes.
tap_node() {
    local kind="$1" value="$2"
    adb shell uiautomator dump /sdcard/v2-ui.xml >/dev/null
    adb pull /sdcard/v2-ui.xml "$DEST/.ui.xml" >/dev/null
    local xy
    xy="$(python3 - "$DEST/.ui.xml" "$kind" "$value" <<'PY'
import re,sys,xml.etree.ElementTree as ET
for node in ET.parse(sys.argv[1]).iter("node"):
    if node.get(sys.argv[2]) == sys.argv[3]:
        x0,y0,x1,y1=map(int,re.findall(r"\d+",node.get("bounds","")))
        print((x0+x1)//2,(y0+y1)//2)
        break
else: raise SystemExit(1)
PY
)"
    adb shell input tap $xy
}
# Allow the one-time network version check to finish; close a possible new-version prompt.
# Root is mandatory. A non-root emulator must display the real failure state.
# Dismiss the required-root dialog only to capture the remaining interface.
sleep 15
tap_node text 确定 || true
sleep 10
tap_node text 稍后 || true
sleep 1
adb exec-out screencap -p > "$DEST/main.png"
tap_node resource-id "$PKG:id/menu_button"
sleep 1
adb exec-out screencap -p > "$DEST/menu.png"
tap_node text 调密码
sleep 1
adb exec-out screencap -p > "$DEST/password.png"
rm -f "$DEST/.ui.xml"
