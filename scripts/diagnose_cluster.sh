#!/usr/bin/env bash
# Diagnose why DiPlay's instrument-cluster features are missing on a BYD head unit.
# Usage: bash scripts/diagnose_cluster.sh [package]
# The package defaults to com.shihab.diplay (the mobile release build).

set -u
PKG="${1:-com.shihab.diplay}"

FINGERPRINT_51="BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260722.221155:user/release-keys"
FINGERPRINT_50="BYD-AUTO/DiLink5.0/DiLink5.0:12/SKQ1.230128.001/eng.build.20251111.182747:user/release-keys"

hr() { printf '\n=== %s ===\n' "$1"; }

hr "0. ADB link"
adb devices

hr "1. Firmware fingerprint"
FP="$(adb shell getprop ro.build.fingerprint 2>/dev/null | tr -d '\r')"
printf '%s\n' "$FP"
case "$FP" in
  "$FINGERPRINT_51") echo "-> DiLink 5.1 profile: shared_fission_bg_XDJAScreenProjection_0/_1, must be 1920x720." ;;
  "$FINGERPRINT_50") echo "-> DiLink 5.0 (validated): legacy display lookup, name fission_bg_XDJAScreenProjection." ;;
  *) echo "-> Unverified firmware. Legacy lookup applies; the 5.1 theme profile stays off." ;;
esac

hr "2. Gate A: is the BYD navigation section shown at all?"
echo "Installed package: $PKG"
adb shell dumpsys package "$PKG" 2>/dev/null | grep -m1 -E '^ *Package \[|versionCode' || echo "!! $PKG is not installed"
echo "-- allow-listed packages --"
for p in com.andrerinas.headunitrevived com.shihab.diplay com.shihab.diplay.hudtest com.shilapi.xcertplay; do
  adb shell pm list packages "$p" 2>/dev/null | tr -d '\r' | sed "s/^/  found: /"
done
echo "-- factory services that also unlock the section --"
for p in com.byd.amapservice com.ts.car.someip.service; do
  if adb shell pm list packages "$p" 2>/dev/null | tr -d '\r' | grep -q "$p"; then
    echo "  present: $p"
  else
    echo "  MISSING: $p"
  fi
done
echo "-- standalone HUD receiver --"
adb shell pm list packages com.byd.clusterdebug 2>/dev/null | tr -d '\r' | sed 's/^/  /'
adb shell dumpsys package com.byd.clusterdebug 2>/dev/null | grep -m1 -E 'versionCode|versionName' | sed 's/^/  /'

hr "3. Gate B: is a cluster projection display visible to the app?"
echo "-- presentation displays --"
adb shell dumpsys display 2>/dev/null | grep -i -E 'fission|XDJAScreenProjection' | sed 's/^/  /' || echo "  (none matched)"
echo "-- all displays --"
adb shell dumpsys display 2>/dev/null | grep -E 'DisplayDeviceInfo|mDisplayId=|uniqueId' | sed 's/^/  /' | head -40

hr "4. Gate C: saved cluster-map switch"
adb shell "run-as $PKG cat /data/data/$PKG/shared_prefs/*.xml" 2>/dev/null \
  | grep -i -E 'cluster_map|launcher_map_sharing|center_map' | sed 's/^/  /' \
  || echo "  (unreadable without a debuggable build; check Settings > BYD navigation instead)"

hr "5. Read the app's own cluster log"
echo "Watch it while CarPlay is in the foreground:"
echo "  adb logcat -s DiPlay-Cluster DiPlay-MapEmbed"
echo "Expected when it works:  cluster presentation shown display=<id> name=<...>"
echo "When gate B fails:       Cluster map: no cluster projection display among ..."

hr "6. Driver-side checklist"
cat <<'TXT'
  - Settings > BYD navigation > turn on "CarPlay map on dashboard" (default OFF).
  - On the steering wheel, set the cluster to "Small screen navi" or "Full screen navi".
    "Turn on by navi" draws arrows only and never shows the map.
  - Keep CarPlay in the foreground: the cluster window belongs to the CarPlay screen.
  - The switch only appears while a projection display exists, so re-check after
    switching the cluster mode on the wheel.
TXT
