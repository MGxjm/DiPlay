#!/usr/bin/env bash
# Probe which lever really stops the stock Amap's instrument-cluster navigation on a BYD head unit.
#
# Run this on the PC with the head unit connected over ADB, WHILE DiPlay is mirroring to the cluster,
# so you can watch the cluster between steps. Every step that changes anything asks for "y" first.
#
# Usage: bash scripts/oem_cluster_navi_off.sh
#
# Background: docs/dilink4-oem-cluster-navi.md

set -u

OEM="com.byd.automap"                 # the factory Amap HD app that owns MeterActivity
RELAY="com.example.amapservice"       # the system-uid phone-Amap -> cluster relay
NAVI_MODE_GET="service call autoservice 5 i32 1007 i32 1086337074"   # 1 Off / 2 Turn-on-by-navi / 3 Small / 4 Full
NAVI_MODE_SET="service call autoservice 6 i32 1007 i32 0x4C10A018"   # INSTRUMENT_NAVI_TYPE_SET (global, also hides DiPlay)
KILL_ACTION="byd.intent.action.KILL_BydAutoMap"

hr() { printf '\n=== %s ===\n' "$1"; }

confirm() {
  printf '\n>>> %s\n' "$1"
  printf '    看仪表屏，确认是否生效后输入 y 继续（直接回车 = 跳过这一步）：'
  read -r a </dev/tty 2>/dev/null || a=""
  [ "$a" = "y" ] || [ "$a" = "Y" ]
}

ask() {
  printf '\n>>> %s\n' "$1"
  printf '    执行请输入 y（直接回车 = 跳过）：'
  read -r a </dev/tty 2>/dev/null || a=""
  [ "$a" = "y" ] || [ "$a" = "Y" ]
}

show_state() {
  printf '  navi mode: '
  adb shell $NAVI_MODE_GET 2>/dev/null | tr -d '\r' | tail -1
  printf '  cluster windows:\n'
  adb shell dumpsys activity activities 2>/dev/null \
    | grep -iE 'MeterActivity|ClusterMirror|resumedActivity' | sed 's/^/    /' | head -8
}

hr "0. ADB link"
adb devices
adb shell getprop ro.build.fingerprint 2>/dev/null | tr -d '\r' | sed 's/^/  fingerprint: /'
show_state

hr "1. Who owns the cluster right now?"
echo "  -- OEM map process --"
adb shell ps -A 2>/dev/null | grep -iE 'byd.automap|amapservice' | sed 's/^/    /' || echo "    (not running)"
echo "  -- MeterActivity / relay present --"
adb shell dumpsys activity activities 2>/dev/null | grep -iE 'MeterActivity' | sed 's/^/    /' | head -5 \
  || echo "    (no MeterActivity)"

hr "2. Lever 1 - stop the factory Amap app (usually decisive)"
if ask "adb shell am force-stop $OEM"; then
  adb shell am force-stop "$OEM"
  echo '  sent. 自带高德的仪表窗口应立刻消失。'
fi
confirm "仪表屏上原车高德的导航卡片是否消失？"
echo "  如果消失了：测一下它多久会被系统拉回来（决定要不要配值守循环）。"
if ask "现在轮询 60 秒，测 $OEM 多久复活？"; then
  start=$(date +%s)
  revived=""
  for _ in $(seq 1 60); do
    if [ -n "$(adb shell pidof "$OEM" 2>/dev/null | tr -d '\r')" ]; then
      revived=$(( $(date +%s) - start ))
      break
    fi
    sleep 1
  done
  if [ -n "$revived" ]; then
    echo "    复活耗时约 ${revived} 秒 —— 若 < 5s 说明有主动保活，光靠 force-stop 不够，要配值守循环或 pm disable-user"
  else
    echo "    60 秒内没有复活 —— force-stop 单发就够"
  fi
fi

hr "3. Lever 2 - the OEM's own 'kill the map' broadcast"
if ask "adb shell am broadcast -a $KILL_ACTION"; then
  adb shell am broadcast -a "$KILL_ACTION"
  echo '  sent. 这会通过 '"$RELAY"' 停掉 CAN 那路；对 '"$OEM"' 只重置桌面小部件。'
fi
confirm "单发这条广播时，仪表上有任何变化吗？（用来判断是不是 '$RELAY' 在投屏）"

hr "3.5 Broadcast route verdict - the ONLY global broadcast that quits the meter"
cat <<'TXT'
  自带高德真正的「退出仪表」信号是进程内广播（LocalBroadcastManager）：
      MeterExtKt.quitMeterAct(ctx)      → sendLocalBroadCast("quitMeterAct")      → MeterActivity.finish()
      BydMapService.exitProcess()       → sendLocalBroadCast("quitCentralMeterAct")
  LocalBroadcastManager 不经过 Binder —— adb 广播送不进去。

  唯一能触达它的全局广播是 android.intent.action.ACTION_SHUTDOWN（BydMapService / PushService
  各注册了一个全局接收器，收到后调 quitMeterAct）。但它在 framework-res.apk 的
  <protected-broadcast> 清单里 → shell(uid 2000) 发送预期被拒。

  本步就是验证「预期被拒」，同时反证广播路线不通。
TXT
if ask "adb shell am broadcast -a android.intent.action.ACTION_SHUTDOWN   （预期：SecurityException）"; then
  adb shell am broadcast -a android.intent.action.ACTION_SHUTDOWN 2>&1 | sed 's/^/    /'
  echo '  若真的发出去了并被接收：仪表可能退出，但会顺带惊动其它系统组件 —— 不要常态化使用。'
fi
confirm "仪表上有变化吗？（预期：无；日志里应能看到 not allowed to send broadcast）"

hr "3.6 Counter-proof - driving MeterActivity externally does NOT close it"
cat <<'TXT'
  MeterActivity 是 exported + singleTask，且读 meterType extra。但：
    onNewIntent → handleMeterType(type) → 只调 setNaviScreenStatus(generateScreenStatus(type)) 写 HAL 位，
    从不 finish() 自己；末尾还会 moveCentralTaskToFront() 把自己顶到前台。
  所以下面这条不但关不掉窗口，反而可能把它顶到 DiPlay 上面 —— 用来反证广播/startActivity 路线无效。
TXT
if ask "adb shell am start -n $OEM/com.byd.automap.extra.MeterActivity --ei meterType 1"; then
  adb shell am start -n "$OEM/com.byd.automap.extra.MeterActivity" --ei meterType 1 2>&1 | sed 's/^/    /'
fi
confirm "仪表上原车高德是「消失」还是「被顶到最前」？（预期：后者）"

hr "4. Lever 3 - report navi-screen STOP to the instrument HAL"
cat <<'TXT'
  语义 = 「我的导航画面停了」，等价于自带高德 MeterType.close 做的事。
  feature 名：INSTRUMENT_SEND_NAVI_STATUS_SET（数值在本机 dex 里是 stub，为 0，须按名解析）
  状态值：1 START_SMALL / 2 START_FULL / 3 STOP

  本脚本不直接发这条命令，因为 transaction 号与参数形态还需在车上确认。三种实测方式：

  a) 只看当前导航模式（已实测可用，无副作用）：
       adb shell service call autoservice 5 i32 1007 i32 1086337074

  b) 用全局「导航模式」做对照实验（注意：会把 DiPlay 自己的镜像也关掉）：
       adb shell service call autoservice 6 i32 1007 i32 0x4C10A018 i32 1     # 1 = Off
       adb shell service call autoservice 6 i32 1007 i32 0x4C10A018 i32 4     # 4 = Full，恢复

  c) 按名取常量 + set()（推荐落地形态，复用 BydClusterSongTool 的 app_process 模板）：
       BYDAutoInstrumentDevice dev = BYDAutoInstrumentDevice.getInstance(ctx);
       BYDAutoEventValue v = new BYDAutoEventValue(); v.intValue = 3;
       dev.set(new int[]{ BYDAutoFeatureIds.INSTRUMENT_SEND_NAVI_STATUS_SET }, v);   // 0 = 成功
TXT

hr "5. Lever 4 - disable the factory Amap (THE CHOSEN ONE, shipped in DiPlay)"
cat <<TXT
  adb shell pm disable-user --user 0 $OEM     # 投屏开始：禁用
  adb shell pm enable      --user 0 $OEM      # 投屏结束：放开

  为什么是它，而不是 1/3/4：
    - 车机是 Android 10 / API 29 → 没有 pm suspend(30+) / am set-inactive(31+)，没有真「冻结」原语；
    - am force-stop 之后它会反复自行重启（步骤 2 的计时就是测这个），压不住；
    - 写 HAL(步骤 4) 只影响仪表 native 那半，管不到原车高德的 Activity window。
  $OEM 装在 /system/app/BydAutomap/，是普通系统 App（非 priv-app、无 persistent、无 sharedUserId），
  预期不在保护名单里，disable-user 可用。

  DiPlay 已把「投屏开始时禁用、结束时放开」实现为开关 oemClusterFreeze（默认开，见 §7），
  本步骤用来在车上验证这套命令本身是否可用。
TXT
if ask "现在禁用 $OEM ？（之后一定要走下面的恢复）"; then
  adb shell pm disable-user --user 0 "$OEM" 2>&1 | sed 's/^/    /'
  echo "  期望输出：Package $OEM new state: disabled-user"
  echo "  若出现 error / SecurityException → ROM 有保护名单，DiPlay 侧会记 refused=true 并退化为不动作。"
  show_state
fi
confirm "仪表屏上原车高德的导航卡片是否立刻消失、且不再回来？"
confirm "DiPlay 的镜像此时是否仍正常（两者不该互相影响）？"

hr "5.1 Restore - the pair of commands DiPlay runs on stop"
if ask "现在放开 $OEM ？（测恢复路径）"; then
  adb shell pm enable --user 0 "$OEM" 2>&1 | sed 's/^/    /'
  echo "  期望：Package $OEM new state: enabled"
  sleep 3
  adb shell am start -n "$OEM/com.byd.automap.activity.StartupActivity" 2>&1 | sed 's/^/    /'
  echo "  检查仪表与 Launcher 的高德入口是否都恢复正常。"
fi
confirm "原车高德是否已恢复（Launcher 入口能打开、仪表能再投屏）？"

hr "5.2 Crash recovery - what DiPlay does when it dies mid-mirror"
cat <<TXT
  模拟「投屏中 DiPlay 崩溃 / 停车直接断电」：原车高德会停在禁用状态。
  DiPlay 的兜底是 BydNavigationOutputs.onAppOpened() → BydOemClusterNavi.restoreIfNeeded()，
  记录在 shared_prefs: diplay_oem_cluster / stock_map_held。

  验证：
    adb shell pm disable-user --user 0 $OEM          # 手工造出「未释放」状态
    adb shell am force-stop com.shihab.diplay        # 杀掉 DiPlay
    adb shell monkey -p com.shihab.diplay 1          # 再打开 DiPlay
    adb shell dumpsys package $OEM | grep -A2 'User 0'   # 期望：回到 enabled
TXT
confirm "重新打开 DiPlay 后，$OEM 是否被自动放开？"

hr "6. Wrap up"
cat <<TXT
  请把每一步「仪表屏有没有变化」的结果记下来，回填到 docs/dilink4-oem-cluster-navi.md 的「待实测清单」。

  恢复顺序建议：
    adb shell pm enable --user 0 $OEM
    adb shell am start -n $OEM/com.byd.automap.activity.StartupActivity
    （force-stop 不需要恢复，用户自己再打开高德即可）

  如果本轮只是在验证 DiPlay 的 oemClusterFreeze 开关，收尾时务必确认 $OEM 已回到 enabled：
    adb shell pm list packages -d | grep $OEM    # 期望：无输出
TXT
