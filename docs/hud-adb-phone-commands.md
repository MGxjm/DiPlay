# 手机端 ADB 抓 HUD —— 命令清单（不用电脑）

目标：在你自己的手机上连车机 ADB，**开着原车高德 HUD 投屏**抓一次日志，
把 DiPlay 需要的 BYDAuto event id 数值和 ClusterDebug 广播 action 拿到手。

全部只读，不改车机任何东西。

---

## 一、手机上怎么获得 adb

按推荐度排：

| 方式 | 说明 |
|---|---|
| **Termux**（推荐） | 装好后 `pkg install android-tools`，就能跑完整脚本，输出可直接存文件回传 |
| 无线 ADB 类 App | 应用商店搜「ADB Shell」「Remote ADB」「Bugjaeger」，能连 IP + 单条命令输入框 |
| 手机自带无线调试 | ⚠️ 不行 —— 那个是让别人连你手机，方向反了 |

Termux 装好先执行一次：

```
pkg update && pkg install android-tools
```

---

## 二、连上车机

1. 手机和车机在同一网段（**手机连车机热点最省事**，此时车机 IP 通常是 `192.168.43.1` 或 `192.168.x.1`；具体在车机「设置 → 网络 → Wi-Fi / 热点」里看）。
2. 车机开启 ADB。DiLink 常见入口：
   - 设置 → 系统/关于 → **连点版本号**进工程模式 → 打开 ADB / 调试
   - 或工程菜单里「ADB 调试」「网络调试」
   - 如果你已经在用 DiPlay 投屏仪表盘，说明车机的 adbd 是活的，只需把端口对外部开放
3. 连接（车机端口通常是 5555）：

```
adb connect 192.168.43.1:5555
adb devices
```

看到设备且状态是 `device` 就成了。首次连接车机屏幕上可能弹「允许调试」，点允许。

---

## 三、黄金三连（最短路径，开 HUD 时跑）

如果只能用「一个输入框一条命令」，就跑这三条，把输出截图发我：

**① 抓 HUD 正在发生什么（最重要）**
```
adb shell "logcat -d -t 4000 | grep -iE 'hud|headup|cluster|bydauto|sendNavi|send_to'"
```

**② 找 BYDAuto 车载 HAL 的系统服务注册名**
```
adb shell "service list | grep -iE 'byd|auto|cluster|hud|vehicle'"
```

**③ ClusterDebug 的广播入口（DiPlay 中转的候选）**
```
adb shell "dumpsys package com.byd.clusterdebug | grep -A30 'Receiver Resolver'"
```

> 注意 `adb shell "..."` 加了引号 —— 管道是在**车机侧**执行的，
> 这样只需要一个命令输入框也能用。

---

## 四、完整清单（分组，按需挑）

### 0. 连通性 / 固件
```
adb devices
adb shell getprop ro.build.fingerprint
adb shell getprop ro.product.model
```

### 1. 关键包在不在
```
adb shell "pm list packages | grep -iE 'someip|ts\.car|byd|xdja|amap|autonavi|cluster|hud'"
```
预期：`someip` 零命中（固件里已确认不存在），`clusterdebug` / `xdja` / `amap` 都在。

### 2. BYDAuto HAL 服务
```
adb shell "service list | grep -iE 'byd|auto|cluster|hud'"
adb shell "lshal | grep -iE 'byd|hud|cluster'"
adb shell "getprop | grep -iE 'byd|hud|cluster'"
```
拿到服务名后（假设叫 `bydauto`）：
```
adb shell dumpsys bydauto
```

### 3. 集群屏 visibility 取证（免 ADB 路线存不存在）
```
adb shell "dumpsys display | grep -iE 'DisplayDeviceInfo|mDisplayId=|mFlags=|uniqueId'"
adb shell "dumpsys display | grep -iE -A6 'fission|XDJA|ScreenProjection'"
```
判定：带 `FLAG_PRIVATE(0x4)` → 固件锁死，只能走 adbd；不带 → 存在免 ADB 路径。

### 4. 高德投 HUD 时的实时绑定
```
adb shell "dumpsys activity services | grep -iE 'amap|autonavi|bydauto|cluster|hud' | head -40"
```

### 5. 开关 HUD 抓瞬时日志（拿 event id 数值的最佳办法）
保持高德投屏，先清日志：
```
adb shell logcat -c
```
然后把 HUD **关掉再打开一次**，立刻执行：
```
adb shell "logcat -d -t 600 | grep -iE 'hud|cluster|bydauto|SET_HUD'"
```
开关瞬间那几条就是我们要的数值。

### 6. 容器服务导出了什么
```
adb shell "dumpsys package com.xdja.containerservice | grep -iE -A25 'Receiver Resolver|Service Resolver'"
```

### 7. ClusterDebug 持有的权限
```
adb shell "dumpsys package com.byd.clusterdebug | grep -iE 'usesPermission|permission:'"
```

---

## 五、零 bash / 免 Termux 方案（推荐，任何 ADB App 都能用）

车机自带的是 `/system/bin/sh`（mksh），**不是 bash**。所以下面这些命令全部
在**车机侧**执行（用引号包起来给 `adb shell`），手机上不需要任何 shell 环境。

### ① 一次性全量采集 → 存到车机 sdcard

把这条整段粘进 ADB App 的命令框执行（很长，但只粘一次）：

```
adb shell "(echo '--- fingerprint ---'; getprop ro.build.fingerprint; echo '--- packages ---'; pm list packages | grep -iE 'someip|byd|xdja|amap|autonavi|cluster|hud'; echo '--- services ---'; service list | grep -iE 'byd|auto|cluster|hud|vehicle'; echo '--- display ---'; dumpsys display | grep -iE 'DisplayDeviceInfo|mDisplayId|mFlags'; echo '--- clusterdebug ---'; dumpsys package com.byd.clusterdebug | grep -A30 'Receiver Resolver'; echo '--- hud log ---'; logcat -d -t 4000 | grep -iE 'hud|cluster|bydauto|sendNavi') > /sdcard/hud_diag.txt 2>&1"
```

### ② 拉回手机（存到下载目录，方便分享出来）

```
adb pull /sdcard/hud_diag.txt /sdcard/Download/
```

之后在手机「文件管理 → 下载」里找到 `hud_diag.txt`，直接发给我，或复制内容贴出来。

### ③ 开关 HUD 抓数值（分三步，拿 event id 的关键）

```
adb shell logcat -c
```
（把高德 HUD 关掉再打开一次）
```
adb shell "logcat -d -t 600 | grep -iE 'hud|cluster|bydauto|SET_HUD' > /sdcard/hud_toggle.txt 2>&1"
adb pull /sdcard/hud_toggle.txt /sdcard/Download/
```

---

## 六、一键脚本（仅当你在手机上装了 Termux）

`scripts/diagnose_hud_phone.sh` 是 **bash 脚本，跑在手机的 Termux 里，不在车机上**。
车机侧依然只用自带的 sh。用法：

```
pkg install android-tools
bash diagnose_hud_phone.sh 192.168.43.1:5555
```

会把 0–10 节全部跑一遍，输出存成 `dilink4_hud_MMDD_HHMM.txt`。
**如果没装 Termux，就用上面第五节，效果一样。**

---

## 七、回传给我什么

最理想的三样：

1. **第 5 节**开关 HUD 时那几条日志（含 `SET_HUD_*` / `bydauto` 的进程名和数值）
2. **第 2 节** `service list` 里 byd 开头那一行（服务注册名）
3. **第 3 节**集群屏那条 `DisplayDeviceInfo`（看 flags）

拿到这些，就能把现在固件里的字符串证据变成可以直接写进 `BydHudBridge` 的参数。
