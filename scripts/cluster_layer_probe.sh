#!/usr/bin/env bash
# Probe how the BYD cluster projection display is SHARED: which displays exist, which apps own
# layers on them, and whether DiPlay or the stock map is on top.
#
# Read-only by default; only step 6 asks before it does anything.
#
# Run on the PC with the head unit connected over ADB, WHILE DiPlay is mirroring to the cluster.
#
# Usage: bash scripts/cluster_layer_probe.sh
#
# Background: docs/dilink4-oem-cluster-navi.md  (section 0.5 "投屏模型: 同屏层叠")

set -u

OEM="com.byd.automap"
ACT_OEM="com.byd.automap.extra.MeterActivity"
ACT_MIRROR="ClusterMirrorActivity"

hr()    { printf '\n=== %s ===\n' "$1"; }
adbsh() { adb shell "$@" 2>/dev/null | tr -d '\r'; }
ask()   {
  printf '\n>>> %s\n    执行请输入 y（直接回车 = 跳过）：' "$1"
  read -r a </dev/tty 2>/dev/null || a=""
  [ "$a" = "y" ] || [ "$a" = "Y" ]
}

hr "0. Device"
adbsh getprop ro.build.fingerprint | sed 's/^/  fingerprint: /'
adb devices | sed 's/^/  /'

hr "1. All displays  — 看清 name 以 fission_ 开头的那块"
echo "  关注 name / uniqueId / displayId 三列。DiPlay 的日志会打印它选中的名字。"
adbsh dumpsys display | grep -E 'DisplayInfo\{' | sed 's/^/  /' | head -30

hr "2. DiPlay's own choice (its log line)"
echo "  这一行给出 DiPlay 实际用的 displayId 与名字："
adbsh logcat -d -s DiPlay-Cluster | grep -E 'cluster mirror target|cluster mirror launch' | tail -5 | sed 's/^/  /' \
  || echo "  (没有日志；确认 DiPlay 正在镜像)"

hr "3. Who is running"
echo "  -- OEM map process --"
adbsh ps -A | grep -iE 'byd.automap|amapservice' | sed 's/^/  /' || echo "  (not running)"
echo "  -- activities on screen --"
adbsh dumpsys activity activities | grep -iE 'MeterActivity|ClusterMirrorActivity|ResumedActivity' | sed 's/^/  /' | head -12

hr "4. Window layers per display  — 这一节回答「谁在上层」"
echo "  dumpsys window displays: 每个 Display 下面按 z-order 从上到下列出 window。"
echo "  找到那块 cluster display，看 DiPlay 与原车高德谁在前面。"
adbsh dumpsys window displays \
  | grep -nE 'Display: mDisplayId=[0-9]+|mCurrentFocus|mFocusedApp|^ *Window\{|isOnScreen|mOwnerUid' \
  | sed 's/^/  /' | head -160

hr "5. Fallback view (older firmware)"
echo "  如果第 4 节输出太少，试 dumpsys SurfaceFlinger（按 layer 顺序）："
adbsh dumpsys SurfaceFlinger --list | grep -iE 'Mirror|Cluster|fission|Meter|automap' | sed 's/^/  /' | head -40

hr "6. Optional: re-raise DiPlay to the top"
cat <<'TXT'
  如果第 4/5 节显示原车高德压在 DiPlay 上面，这一步验证「重发 am start 能否抬层」。
  需要第 2 节里 DiPlay 用的 displayId（记为 N）。
TXT
printf '    输入 DiPlay 的 displayId（直接回车 = 跳过）：'
read -r DID </dev/tty 2>/dev/null || DID=""
if [ -n "$DID" ] && ask "am start --display $DID -n <pkg>/$ACT_MIRROR"; then
  printf '    输入 DiPlay 的完整包名（如 com.shihab.diplay）：'
  read -r PKG </dev/tty 2>/dev/null || PKG=""
  if [ -n "$PKG" ]; then
    adb shell am start --display "$DID" -n "$PKG/$ACT_MIRROR"
    echo '  已重发。看仪表屏 DiPlay 是否回到最上、有没有闪。'
  fi
fi

hr "7. Wrap up"
cat <<TXT
  请把下面几项记下来，回填到 docs/dilink4-oem-cluster-navi.md 的「待实测清单」第 7~11 条：

    - cluster display 的 name / uniqueId / displayId：
    - DiPlay 选的 displayId 与原车高德的是不是同一个：
    - 该 display 上 window 的先后顺序（谁在上）：
    - 重发 am start 能否抬层、耗时、有无闪烁：

  恢复：本脚本除第 6 步外不改任何状态，不需要恢复。
TXT
