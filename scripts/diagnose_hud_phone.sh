#!/usr/bin/env bash
# DiLink 4.0 HUD diagnosis — bash script, runs FROM A PHONE over Wi-Fi (Termux).
#
# NOTE: this script itself runs in Termux on the PHONE. Nothing here runs on the
# head unit except plain `adb shell "..."` one-liners, which use the car's own
# /system/bin/sh (mksh) — Android has no bash and none is needed there.
# If you have no Termux, use the copy-paste commands in
# docs/hud-adb-phone-commands.md §5 instead; no shell environment required.
#
# Usage (Termux on your phone, same Wi-Fi / hotspot as the head unit):
#   pkg install android-tools            # first time only
#   bash diagnose_hud_phone.sh 192.168.1.100:5555
#
# The head-unit IP is usually 192.168.x.1 when your phone is on the car's
# hotspot, otherwise read it from 设置 → 网络 → Wi-Fi on the head unit.
#
# IMPORTANT: run this WHILE the factory Gaode map (原车高德) is actively
# casting to the HUD. That is the only moment the real event ids show up.
#
# Everything is READ-ONLY. Nothing is modified on the car.

set -u
TARGET="${1:-}"
OUT="dilink4_hud_$(date +%m%d_%H%M).txt"
: > "$OUT"

say() { printf '%s\n' "$*" | tee -a "$OUT"; }
hr()  { printf '\n========== %s ==========\n' "$1" | tee -a "$OUT"; }
sh_() { adb shell "$1" 2>/dev/null | tr -d '\r' | tee -a "$OUT"; }

hr "0. 连接"
if [ -z "$TARGET" ]; then
  say "!! 用法: bash $0 <车机IP>:<端口>   例如 192.168.1.100:5555"
  exit 1
fi
adb connect "$TARGET" | tee -a "$OUT"
sleep 2
adb devices | tee -a "$OUT"
adb devices | tr -d '\r' | tail -n +2 | grep -qw device || {
  say "!! 没连上。车机需先开启 ADB（设置→关于→连点版本号进工程模式，或 DiPlay 内已开 adbd）。"
  exit 1
}

hr "1. 固件指纹（确认是不是 20250609 这台）"
sh_ "getprop ro.build.fingerprint"
sh_ "getprop ro.product.model"
sh_ "getprop ro.build.version.release"

hr "2. 关键包是否存在"
say "-- 与 HUD/集群相关的包 --"
sh_ "pm list packages | grep -iE 'someip|ts\\.car|byd|xdja|amap|autonavi|cluster|hud'"
say ""
say "-- DiPlay 写死的三个目标（预期：someip 不存在）--"
for p in com.ts.car.someip.service com.byd.clusterdebug com.xdja.containerservice com.byd.amapservice; do
  if adb shell "pm list packages $p" 2>/dev/null | tr -d '\r' | grep -q "package:$p"; then
    say "  PRESENT  $p"
  else
    say "  MISSING  $p"
  fi
done

hr "3. BYDAuto 车载 HAL 的系统服务注册名（最关键）"
say "-- service list 里的 byd/auto/cluster --"
sh_ "service list | grep -iE 'byd|auto|cluster|hud|vehicle'"
say ""
say "-- lshal 里的 HIDL 服务 --"
sh_ "lshal 2>/dev/null | grep -iE 'byd|hud|cluster' || echo '  (lshal 不可用或无命中)'"
say ""
say "-- getprop 里的 byd --"
sh_ "getprop | grep -iE 'byd|hud|cluster'"

hr "4. 试着 dump 这个服务（把 <name> 换成第 3 节里 byd 开头的那个名字）"
say "  手动执行: adb shell dumpsys <name>"
sh_ "service list | grep -iE 'byd' | head -3"

hr "5. 集群屏 display 取证（免 ADB 路线到底存不存在）"
sh_ "dumpsys display | grep -iE 'DisplayDeviceInfo|mDisplayId=|mFlags=|uniqueId'"
say ""
say "-- fission / 集群投影屏 --"
sh_ "dumpsys display | grep -iE -A6 'fission|XDJA|ScreenProjection' || echo '  (shell 也看不到)'"
say "  判定: 带 FLAG_PRIVATE(0x4) => 固件锁死，只能走 adbd；不带 => 存在免 ADB 路径"

hr "6. 活跃服务绑定（必须在高德投 HUD 时跑）"
sh_ "dumpsys activity services | grep -iE 'amap|autonavi|bydauto|cluster|hud|someip' | head -40"

hr "7. HUD 日志（必须在高德投 HUD 时跑）"
say "-- 最近 4000 行里 HUD/cluster/bydauto 相关 --"
sh_ "logcat -d -t 4000 | grep -iE 'hud|headup|cluster|bydauto|sendNavi|send_to' | tail -60"
say ""
say "-- 高德进程自己的输出 --"
sh_ "logcat -d -t 4000 | grep -iE 'AmapService|automap|HudManager|NaviToCluster' | tail -40"

hr "8. ClusterDebug 的接口（DiPlay 广播中转的候选）"
sh_ "dumpsys package com.byd.clusterdebug | grep -iE -A30 'Receiver Resolver' | head -45"
say ""
say "-- 它持有的权限 --"
sh_ "dumpsys package com.byd.clusterdebug | grep -iE 'usesPermission|permission:' | head -20"

hr "9. 容器服务导出了什么"
sh_ "dumpsys package com.xdja.containerservice | grep -iE -A25 'Receiver Resolver|Service Resolver' | head -40"

hr "10. 完成"
say "输出已保存到: $(pwd)/$OUT"
say ""
say "下一步（强烈建议做一次）：保持高德 HUD 投屏开着，执行"
say "  adb shell logcat -c"
say "然后把 HUD 关掉再打开一次，紧接着执行:"
say "  adb shell \"logcat -d -t 600 | grep -iE 'hud|cluster|bydauto'\""
say "开关瞬间打出来的那几条，就是我们要的 event id 数值。"

echo ""
echo "DONE -> $OUT"
