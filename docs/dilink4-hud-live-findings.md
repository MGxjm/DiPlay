# DiLink 4.0 车机活体取证（2026-10-03 首次 ADB 采集）

证据文件：`scripts/probe/hud_dump/20261003_hud_diag.txt`、`20261003_hud_toggle.txt`

---

## 0. 车机固件 ≠ 离线固件包（必须先记住）

```
车机   BYD-AUTO/DiLink4.0/DiLink4.0:10/QKQ1.210218.001/eng.build.20240613.191359:user/release-keys
zip包  BYD-AUTO/DiLink4.0/DiLink4.0:10/QKQ1.210218.001/eng.build.20250609.213409
```

车机比那个 zip **旧约一年**，而且是 `user/release-keys`（正式签名版）。
所以 `docs/firmware-dilink4-hud-analysis.md` 里的静态结论只能当**地图**，
真值一律以车机为准复核。

---

## 1. 集群屏 flag 取证 —— 修正了我之前的一个判断

```
DisplayDeviceInfo{"fission_bg_xdjaVirtualSurface":
  uniqueId="virtual:com.xdja.containerservice,1000,fission_bg_xdjaVirtualSurface,0",
  1920 x 720, modeId 2, ... touch NONE, rotation 0, type VIRTUAL, state ON,
  owner com.xdja.containerservice (uid 1000),
  FLAG_PRESENTATION, FLAG_OWN_CONTENT_ONLY}
    mFlags=11
    mDisplayId=1
```

`mFlags = 11 = 0x1(SUPPORTS_PROTECTED_BUFFERS) + 0x2(SECURE) + 0x8(PRESENTATION)`

**关键：没有 `FLAG_PRIVATE`（0x4）。**

这跟我之前的说法不一样，得修正：

| 之前的判断 | 实际 |
|---|---|
| 固件把集群屏设为私有，App 枚举不到 | ❌ 它带 `FLAG_PRESENTATION`，属于公开 presentation 分类 |
| 只能是"看不见" | ✅ 真实限制是 `FLAG_OWN_CONTENT_ONLY` + `owner uid 1000` —— **只有 owner（system）能往这块屏里放内容** |

所以准确说法是：**限制在"内容所有权"，不在"可见性"。**
第三方 App 大概率能枚举到这块屏，但 `Presentation` 建窗口时会被拒（不是 owner）。
`am start --display` 能成功，是因为走的是 shell/系统侧门，绕开了 owner 校验。

⚠️ 这条还差一次决定性验证：DiPlay 的 `findDisplay()` 到底有没有枚举到它、
是在枚举阶段失败还是在建 Presentation 阶段失败。需要看 DiPlay 自己的日志（见第二轮命令 §6）。

---

## 2. 车机上真实的 BYDAuto 服务（实测 15 个）

| # | 服务名 | 接口 |
|---|---|---|
| 4 | android.hardware.bydauto.panorama.IBYDAutoPanoService | 全景 |
| 5 | IBYDCDRService | 行车记录 |
| 7 | bydcameramanager | 摄像头 |
| 8 | upgrade_server | OTA |
| 24 | **AutoContainer** | android.os.IAutoContainer |
| 25 | byd_auth_service | 鉴权 |
| 26 | BYDMgmt | android.os.IBYDMgmtService |
| 27 | connect_manager_service | 连接管理 |
| 178 | diagnosticsrv | 诊断 |
| 186 | gbacqservice | 采集 |
| 187 | acquisitionsrv | 采集 |
| 199 | mqttserv | 云 MQTT |
| **205** | **autoservice** | **android.gui.BYDAutoServer** ← 核心 |
| 206 | AutoContainerNative | (空) |

**没有名为 instrument / hud 的独立服务。**
→ 仪表盘/HUD 应该是 `autoservice` 内部的一个 **device_type**，不是独立服务。

---

## 3. 事件编码格式（已破解）

日志长这样：

```
BYDAutoSensorDevice: postEvent device_type: 1043, event_type =2230003f, value = 1
BYDAutoPM2p5Device:  postEvent device_type: 1008, event_type =9900002c, value = 22
AbsBYDAutoDevice: set featureIDs is int[]: [6dc00826, 6dc00810, 6dc00818, 6dc00808, 6dc00827, ]
```

**已实测的 device_type：**

| device_type | 含义 |
|---|---|
| 1008 | PM2.5 |
| 1009 | 充电 |
| 1014 | 统计 |
| 1043 | 传感器 |

**⚠️ 坑：event_type 是用 `%d` 打印的有符号数**，大于 `0x7FFFFFFF` 的会显示成负十进制。
例：`-1728052722` → `+2^32` → `0x9900020E`。解析时必须先做这个转换。

已观察到的 event_type：`0x2230003f`、`0x43a00028`、`0x44600030`、`0x44400018`、
`0x4f600010`、`0x4f60001c`、`0x9900002c`、`0x9900020e`。
featureIDs：`0x6dc00826 / 0x6dc00810 / 0x6dc00818 / 0x6dc00808 / 0x6dc00827`。

**HUD / instrument 的 device_type 还没拿到** —— 这是下一轮的核心目标。

---

## 4. 这次没抓到的两样

1. **HUD/instrument 事件：0 条。** 采样窗口里只有 Sensor / Charging / PM2.5 / Statistic / ADAS 的常规噪声，
   说明抓取时高德大概率没在投 HUD，或 instrument device 根本没发事件。
2. **`packages` 节和 `clusterdebug` 节都是空的。**
   Android 10 上 `pm list packages` / `dumpsys package` 在 adb shell 下表现不稳，
   第二轮改用 `cmd package`。

---

## 5. 第二轮命令（shell 后面的内容，直接复制）

### ① 列出所有活跃 device_type（最关键，一条定乾坤）
```
logcat -d -t 30000 | grep -oE 'device_type: [0-9]+' | sort | uniq -c
```

### ② 列出所有活跃的 BYDAuto Device 类
```
logcat -d -t 30000 | grep -oE 'BYDAuto[A-Za-z0-9]*Device' | sort | uniq -c
```

### ③ 开着高德 HUD 投屏时抓 instrument/hud/cluster/navi
```
logcat -d -t 30000 | grep -iE 'instrument|hud|cluster|navi' > /sdcard/hud2.txt 2>&1
```

### ④ 修正版包列表（用 cmd 不用 pm）
```
cmd package list packages | grep -iE 'byd|xdja|amap|autonavi|cluster|hud'
```

### ⑤ ClusterDebug 到底在不在
```
dumpsys package com.byd.clusterdebug | head -40
```

### ⑥ dump 核心服务 autoservice，看它内部有哪些 device
```
dumpsys autoservice | grep -iE 'instrument|hud|cluster|device' | head -80
```

### ⑦ 集群屏完整字段（确认 owner / flags / displayId）
```
dumpsys display | grep -A12 'fission'
```

### ⑧ DiPlay 自己看到什么屏（决定性，需先开一次 DiPlay 投屏）
```
logcat -d -t 20000 | grep -iE 'ClusterMirror|ClusterMapPresentation|describeDisplays|no cluster projection' > /sdcard/diplay_disp.txt 2>&1
```

拉回手机（带 adb）：
```
adb pull /sdcard/hud2.txt /sdcard/Download/
adb pull /sdcard/diplay_disp.txt /sdcard/Download/
```

---

## 6. 优先级

先做 **① + ②**（两条命令，10 秒），它俩能直接告诉我们：
车机上到底有没有一个 instrument/hud device，以及它的 device_type 是多少。
有了 device_type，才能定位该监听哪一类 event，进而判断 DiPlay 该改哪里。

---

# 第二轮结果（2026-10-03 20:49 回传）

证据：`scripts/probe/hud_dump/20261003_hud2.txt`

## R1. device_type 全集（采样窗口 30000 行）

| device_type | 次数 | Device 类 | 含义 |
|---|---|---|---|
| 1005 | 2 | BYDAutoPowerDevice | 电源 |
| 1008 | 76 | BYDAutoPM2p5Device | PM2.5 |
| 1009 | 43 | BYDAutoChargingDevice | 充电 |
| 1014 | 57 | BYDAutoStatisticDevice | 统计 |
| 1016 | 6 | BYDAutoTyreDevice | 胎压 |
| 1043 | 341 | BYDAutoSensorDevice | 传感器 |

活跃 Device 类：ADAS(31) / Charging(35) / Device 基类(13) / PM2p5(187) /
Power(2) / Sensor(294) / Statistic(48) / Tyre(6)。

**没有 Instrument / HUD / Cluster 设备。**

## R2. 为什么是零 —— 高德根本没在跑

整份日志里 `amap / autonavi / gaode / automap` **命中 0 次**。
采样窗口是 20:35–20:49，这段时间车机上没有地图进程活动，
所以 HUD 事件不可能出现。**这是时机问题，不是命令问题。**

## R3. 两个有价值的旁证

**① 车机确实有 HUD 入口：**

```
10-03 20:36:29.161 I ActivityTaskManager: START u0 {act=android.intent.action.BYD_HUD
   cat=[android.intent.category.DEFAULT] cmp=com.byd.carsettings/.MainActivity} from uid 10071
```

`android.intent.action.BYD_HUD` 是一个真实存在的 action，由 **com.byd.carsettings** 处理
（即车机设置里的 HUD 开关页）。它可以直接被 `am start -a android.intent.action.BYD_HUD` 拉起，
方便我们在开/关 HUD 的瞬间抓事件。

**② 车机上已装 DiPlay 的 hudtest 变体：**

```
com.shihab.diplay.hudtest  (PackageParser 警告，20:49:01 安装)
```

→ 可以直接抓 DiPlay 自己的日志，看 `findDisplay()` 到底返回了什么。

## R4. 仍未拿到

- instrument / HUD 的 device_type（前提：高德必须真的在导航并投 HUD）
- `cmd package list packages` 依然输出为空（第二轮也一样），包列表仍待获取
- `dumpsys autoservice` 未回传

---

# 第三轮命令（先让高德跑起来，再抓）

## 步骤 1：确认车机上的地图 App 到底叫什么

```
ps -A | grep -iE 'map|amap|autonavi|byd'
```

## 步骤 2：拉起 HUD 设置页（确认 HUD 功能在位）

```
am start -a android.intent.action.BYD_HUD
```

## 步骤 3：开着高德导航 + HUD 投屏，再抓这两条

```
logcat -d -t 30000 | grep -oE 'device_type: [0-9]+' | sort | uniq -c
```

```
logcat -d -t 30000 | grep -oE 'BYDAuto[A-Za-z0-9]*Device' | sort | uniq -c
```

对比 R1 的表：**多出来那个 device_type 就是 HUD/仪表盘。**

## 步骤 4：补上还没拿到的两块

```
dumpsys autoservice | grep -iE 'instrument|hud|cluster|device' | head -80
```

```
dumpsys package com.byd.carsettings | grep -iE 'hud' | head -20
```

## 步骤 5：DiPlay 自己看到了什么（决定性）

```
logcat -d -t 20000 | grep -iE 'ClusterMirror|ClusterMapPresentation|describeDisplays|no cluster projection' > /sdcard/diplay_disp.txt 2>&1
```

```
adb pull /sdcard/diplay_disp.txt /sdcard/Download/
```

---

# ⚠️ 修正：我上一节的判断是错的

我说"采样窗口内高德进程活动 = 0，所以是时机问题"——**这是错的**。
错因：我用 `amap / autonavi / gaode / automap` 当关键词，但这台车（2024 固件）上
导航包名不含这些词。DiPlay 源码里其实早就写明了：

```kotlin
// common/src/main/java/com/shilapi/xcertplay/HomeScreenMonitor.kt:81
val HOME_PACKAGES = setOf("com.android.launcher3", "com.byd.launchermap",
                          "com.byd.naviauto", "com.byd.mycar")
```

**`com.byd.naviauto` 才是这台车的导航包名。** 关键词从一开始就对不上，
所以"0 命中"是我的脚本问题，不是高德没跑。

---

# 第三轮结果（2026-10-03 20:53 回传，hud3.txt）

## R5. 铁证：naviauto 确实存在，且有 secret 服务

```
10-03 20:51:55.218 W ActivityManager: Unable to start service
  Intent { act=com.byd.naviauto.secret flg=0x1000020 pkg=com.byd.naviauto } U=0: not found
```

→ 车机上有 `com.byd.naviauto`（导航），并且存在 `com.byd.naviauto.secret` 这个服务。
（DiPlay 源码里 naviauto 只出现在 HomeScreenMonitor 的桌面包判断，不含 secret service，
所以这条 intent 不是 DiPlay 发的，是车机系统/其它 App 发的。）

## R6. 更重要的结论：HUD 不走 BYDAuto 的 postEvent

开着高德导航 + HUD 正常显示时抓的 403 行里：

- 实质命中（排除 NavigationBar 噪音）**只有 8 条**
- 其中 **0 条**是 instrument / HUD / cluster 的 postEvent
- BYDAuto 的 device_type 依然是电源/PM2.5/充电/统计/胎压/传感器那几个

→ **HUD 数据不经过 BYDAuto 的 Java 层 postEvent 通道。**

这跟固件分析的推论吻合：HUD 数据走的是 **ClusterDebug → CAN** 那条路
（`broadcastToCAN` / `BroadcastReceiverCAN`），而不是 BYDAuto 的 event 体系。
也解释了为什么 `BydHudBridge`（SOME/IP）在你车上必然失败 —— 那套东西在这台车上压根不参与 HUD。

---

# 第四轮：顺着 CAN / naviauto 这条路查

## 一条总命令（复制一次跑完）

```
(ps -A | grep -iE 'navi|amap|map|auto|byd'; echo '--- bound services ---'; dumpsys activity services | grep -iE 'navi|cluster|hud|instrument|amap|byd' | head -60; echo '--- clusterdebug ---'; dumpsys package com.byd.clusterdebug | head -40; echo '--- naviauto ---'; dumpsys package com.byd.naviauto | head -40) > /sdcard/hud4.txt 2>&1
```

```
adb pull /sdcard/hud4.txt /sdcard/Download/
```

## 拆开的话

```
ps -A | grep -iE 'navi|amap|map|auto|byd'
```

```
dumpsys activity services | grep -iE 'navi|cluster|hud|instrument|amap|byd' | head -60
```

```
dumpsys package com.byd.clusterdebug | head -40
```

```
dumpsys package com.byd.naviauto | head -40
```

## 目标

1. 找到导航进程 PID，确认它活着
2. `dumpsys activity services` 看导航 bind 了谁 —— **那就是 HUD 的真正通道**
3. 确认 `com.byd.clusterdebug` 是否在车上（DiPlay 的中转候选，一直没确认）

---

# 静态交叉验证：高德的仪表盘/HUD 是「渲染」，不是「数据通道」

用户确认车机（20240613）与 zip（20250609）同为 DiLink 4.0、仅小版本差异，
所以可以直接用 zip 里的 `BydAutomap.apk`（原车高德，30MB dex）反推机制。

## S1. 决定性证据：MeterActivity + setLaunchDisplayId

```
com/byd/automap/extra/MeterActivity          ← 仪表盘 Activity
  ├ $quiteReceiver$1        ("quiteReceiver onReceive action=")
  ├ $changeFragment$1$1     ("changeFragment drive..." / "changeFragment explore...")
  └ $layoutInfoProvider$1

setLaunchDisplayId                            ← ActivityOptions.setLaunchDisplayId()
launchDisplayId=
.android.hardware.display.category.PRESENTATION
displayObserver onDisplayCreated  displayId=
displayObserver onMapDoRender     displayId=
displayObserver beforeEGLDevCreated displayId=
displayObserver onMapFirstPaint   displayId=
```

**机制还原**：高德把 `MeterActivity` 通过 `ActivityOptions.setLaunchDisplayId(<集群屏id>)`
直接启动到 `fission_bg_xdjaVirtualSurface` 那块虚拟屏上，然后用 **EGL 在该 display 上渲染地图**
（`onMapDoRender` / `beforeEGLDevCreated` / `onMapFirstPaint`）。
MeterActivity 内部还有 drive/explore 两种 fragment 布局切换。

## S2. 这解释了活体取证的所有异常

| 观察 | 解释 |
|---|---|
| 高德投 HUD 时 BYDAuto 无任何 HUD/instrument 事件 | **内容是画出来的，不是发数据** |
| BYDAuto device_type 只有电源/PM2.5/充电/统计/胎压/传感器 | HUD 内容不经过 event 体系 |
| 高德 dex 里有 `SET_HUD_MODE_SET` / `DisplayTypeHud` 等 | 那些只是**原厂 HUD 硬件的开关/模式设置**，不是导航内容通道 |
| 集群屏 `FLAG_PRESENTATION` + `FLAG_OWN_CONTENT_ONLY` | 高德是系统 App（/system/app），可以往里放内容；第三方不行 |

## S3. 对最初那个问题的最终回答

> ICCOA 的「投屏到 HUD」能不能改？

- ICCOA 的 HUD 大概率是**手机把 HUD 数据发给车端接收端**的架构。
  比亚迪原厂走的是**渲染到集群虚拟屏**，两者根本不是一回事。
- 你车上 ICCOA HUD 无反应 → 车端没有 ICCOA 的 HUD 接收端 → **改手机端无解**，与之前的判断一致，
  但现在有了机制层面的解释（架构不同，不是参数不对）。
- **好消息**：比亚迪这条「渲染到集群屏」的路，DiPlay 已经走通了（`ClusterMirror` 完美投屏）。
  要在仪表盘上显示 HUD 风格内容，**不需要任何 HUD 协议，自己画就行**。

## S4. 还差一块拼图（车机验证）

静态是 2025 版固件推出来的，需要在你车上确认高德确实是这么干的。
投 HUD 时跑这一条，看 `MeterActivity` 是不是挂在 Display #1：

```
dumpsys activity activities | grep -iE 'Display #|MeterActivity|naviauto|automap|meterscreen'
```

```
dumpsys window windows | grep -iE 'navi|automap|meter|Display #'
```

---

# 接下来怎么分析：三条路

## 路线 A（推荐，直接进入实现）：做「集群屏自定义 HUD」

机制已经清楚，不需要再挖协议。基于现有 `ClusterMirror` + `ClusterMirrorActivity`
加一个 HUD 渲染模式：

- 数据源：DiPlay 已有的 `BydHudRouteState`（转弯箭头/距离/路名）+ CarPlay 导航事件
- 渲染目标：集群屏（DiPlay 已能放 Activity 上去）
- 优点：零新权限、零逆向、开源可控
- 工作量：UI 布局 + 数据绑定，`ClusterMirrorActivity` 才 78 行，很轻

## 路线 B：先补齐车机侧证据（1–2 条命令）

跑上面 S4 两条，确认 MeterActivity 在 Display #1 → 机制完全坐实，路线 A 无风险。

## 路线 C：验证能否免 ADB（可选）

第三方 App 能不能像高德那样 `setLaunchDisplayId`？
看 DiPlay 自己尝试时的报错：

```
logcat -d -t 20000 | grep -iE 'ClusterMirror|ClusterMapPresentation|describeDisplays|no cluster projection|SecurityException' > /sdcard/diplay_disp.txt 2>&1
```

```
adb pull /sdcard/diplay_disp.txt /sdcard/Download/
```

如果日志里是 `SecurityException` / permission denied，说明第三方无解，
ADB 侧门是唯一路；如果只是「枚举不到」，可能还有空间。

---

# 修正（2026-10-03）：②③ 的原生 HUD 路径分析更正

之前对 ②（amapservice 广播）和 ③（clusterdebug 广播）的归因是错的，按固件实锤更正。

## 实锤：4.0 固件里相关 APK 的真实包名（从 AndroidManifest 解出）

| APK | 真实包名 | 代码硬编码找的是 |
|---|---|---|
| AmapService.apk | `com.example.amapservice`（占位包名） | `com.byd.amapservice` |
| BydAutomap.apk | `com.byd.automap` | `com.byd.automap`（available 兜底） |
| ClusterDebug.apk | `com.byd.clusterdebug` | （仅 ③ 用，且被路由挡在 4.0 外） |

## ② 的正确结论

`BydClusterBridge` 写死 `getPackageInfo("com.byd.amapservice")`，而 4.0 适配器真实包名是
`com.example.amapservice` → 必然 `NameNotFoundException` → `available=false` → **② 在 4.0 确定死**。
（旧分析误用「naviauto 在跑所以 amapservice 可能不在」——非因果；且把"必然"错说成"可能"。）

## ③ 的正确结论（路由搞反了）

`BydNavigationOutputs.start()`：`useStandalone = BydStandaloneHudOutput.available()`。
4.0 指纹不符 → `useStandalone=false` → 走 **else 分支只跑 ①②，根本不进 ③**。
③ 是 **DiLink 5.1 专属路径**（指纹 `eng.build20260722.221155` + 签名 `efe3ca8a…` + 版本 `10601004`
都是 5.1 开发车 ClusterDebug 的特征）。硬掰 `useStandalone=true` 也过不了 4.0 的签名/版本关。
**旧分析把 ③ 当"4.0 上被锁的后门/潜在捷径"是错的；「放开指纹试 ③」的建议作废。**

## 4.0 上真正被启动的路径

`useStandalone=false` → ① SOME/IP（`com.ts.car.someip.service` zip 里 0 命中，包不存在）+ ②（包名不符）。
**①② 都死，③ 不路由。** 结论（4.0 无可投喂 HUD 通道）不变，理由以上述实锤为准。

## 连带疑点（待实车确认）

zip 地图包 = `com.byd.automap`，但实车日志出现 `com.byd.naviauto.secret`。若实车地图真是
`com.byd.naviauto` 且无 `com.byd.automap`，则 `BydOutputSettings.available()=false` →
「BYD navigation」设置区 + HUD 开关在实车**不显示**，桥接器不启动。需实车确认。

---

# 最终定论（2026-10-03 晚）：②③ 更正后的最终版

## 用户是对的：签名/版本/包名在 4.0 上全对得上，只剩指纹一条

对 4.0 固件 ZIP 里的 `ClusterDebug.apk` 逐一实测：

| 检查项 | DiPlay 锁定值 | 4.0 ZIP 实测 | 结果 |
|---|---|---|---|
| 签名 SHA-256 | `efe3ca8a…` | `EF:E3:CA:8A:…:3E:FC` | ✅ |
| versionCode | `10601004` | `10601004` | ✅ |
| 包名 | `com.byd.clusterdebug` | `com.byd.clusterdebug` | ✅ |
| receiver exported | true | `0xffffffff`(true) | ✅ |
| receiver permission | 空 | 无 | ✅ |
| **Build.FINGERPRINT** | `IVI:13/…/eng.build20260722` | `DiLink4.0:10/…/eng.build.20240613` | ❌ 唯一失败 |

→ `BydStandaloneHudOutput.available()` 在 4.0 上**唯一不过的是指纹这一条**。
→ **之前「放开指纹也过不了签名关」是错的**：签名/版本/包名/receiver 在 4.0 全过。

## 定性

- 指纹 + 签名 + 版本 + receiver 检查 = **防篡改白名单**（确保只对「正版 OEM clusterdebug + 已知固件」发
  危险广播），不是「5.1 专属机制」。用户判断正确。
- 车机与 ZIP 同平台：`BYD-AUTO/DiLink4.0/DiLink4.0:10/QKQ1.210218.001`（Android 10），
  只差 INCREMENTAL 打包时间（`eng.build.20240613.191359` vs `eng.build.20250609.213409`）。
- 唯一要精确的一点：DiPlay 代码里锁的指纹是 `IVI:13/TP1A/…/eng.build20260722`（Android 13、device=IVI、
  2026 构建），**不是**用户车机的 `DiLink4.0:10`（Android 10）。所以这条白名单当前指向的是另一代（Android 13）
  车机，不是「4.0 的另一个打包时间」。但这不是「平台硬不兼容」，只是白名单字符串没覆盖 4.0。

## 实际含义（重要）

签名/版本/包名/receiver 在 4.0 全过 → **唯一障碍是那条指纹字符串**。把白名单指向 4.0 指纹
（`DiLink4.0:10/…/eng.build.20240613.191359`，或放宽指纹判定），③(clusterdebug 广播) 在 4.0 上
是「可实验的、低成本的」——不是我之前说的「5.1 专属、签不过」。风险仍是：clusterdebug 是开放后门，
且该 receiver 在 Android 10(4.0) 上的运行时行为未实测，属未验证、需在车上试。

---

# 行动记录（2026-10-03 深夜）：已放开 4.0 指纹白名单，出 debug 版待实测

基于上游最新代码（main = `81a0767`，`BydStandaloneHudOutput.kt` 与 main 一致）做了最小改动。

## 改动内容

`shared/.../hud/BydStandaloneHudOutput.kt` 的 `available()`：

- 原：`Build.FINGERPRINT != "BYD-AUTO/IVI/IVI:13/.../eng.build20260722.221155:user/release-keys"` → 直接 return false（单指纹精确匹配）。
- 新：抽成 `fingerprintAllowed(Build.FINGERPRINT)`：
  - 精确匹配原 5.x 指纹（`BYD-AUTO/IVI/IVI:13/...`）→ 通过（向后兼容）；
  - 前缀匹配 DiLink 4.0（`BYD-AUTO/DiLink4.0/DiLink4.0:10/`）→ 通过。
- **签名 SHA-256、versionCode、包名、receiver enabled/exported/无权限、FLAG_SYSTEM 五道防篡改检查完全不动**——它们才是真正的防篡改，且已实测在 4.0 的 ClusterDebug.apk 上全部通过。

## 为什么用前缀而非精确指纹

指纹含 INCREMENTAL（打包时间），`eng.build.20240613` vs `eng.build.20250609` 每版都变，精确匹配永远对不上；前缀 `BYD-AUTO/DiLink4.0/DiLink4.0:10/` 锁死"Android 10 + DiLink4.0"平台身份，防篡改仍由签名/版本等硬检查兜底。

## 放开后的链路（已核对源码）

1. `BydOutputSettings.available()` = `BydStandaloneHudOutput.available() || 装 amapservice || 装 someip || 装 com.byd.automap` → 4.0 上只要 clusterdebug 检查通过即 true → 「BYD navigation」设置区显示。
2. `BydNavigationOutputs.start()` 里 `useStandalone = available()` → true → 走 standalone（`byd.hud.NAVIGATION` 广播给 `com.byd.clusterdebug/.BroadcastReceiverCAN`）。
3. 数据源 `BydStandaloneNavigationBridge`（导航转弯/距离/路名）→ session → 广播。

## 实测步骤

1. 安装 `mobile-debug.apk`（本目录）。
2. 开 CarPlay 导航。
3. DiPlay → BYD navigation → 确认设置区出现、打开开关。
4. 看风挡 HUD / 仪表盘是否出现转向箭头 + 距离 + 路名。

**仍属未验证**：clusterdebug 是开放后门；该 receiver 在 Android 10(4.0) 上的运行时行为未实测。若签名/版本/包名/receiver 与 ZIP 不完全一致，`available()` 仍会 false。

## 备注

路线 A（`ClusterHudView` 自绘卡片）的改动已 `git stash` 暂存（`stash@{0}`）+ `ClusterHudView.kt` 移至 `scripts/probe/hud_wip_archive/`，未纳入本次干净 APK。

---

# 关键补充（2026-10-03 深夜）：zip 的 ClusterDebug 是 2025 版，车机是 2024 版，可能不是同一个包

用户新问题：「zip 里的 clusterdebug 和车机上的同一个版本吗？」

## zip 侧权威基准（重新 aapt+keytool 确认）

- 包名 `com.byd.clusterdebug`、versionCode=`10601004`
- **versionName=`1.6.1.4.2506031514.1fa4238`**（`250603`=2025-06-03 构建）
- 签名 SHA-256 `EF:E3:CA:8A:DA:0D:10:C6:55:C3:DF:99:10:AD:2E:BC:12:1A:47:D9:A6:35:84:34:EB:24:07:43:09:93:3E:FC`
- minSdk=29(Android10)、targetSdk=33(Android13)、platformBuildVersionName=13

## 关键推理：很可能不是同一个版本

- zip 固件 = `eng.build.20250609`（2025-06-09），ClusterDebug versionName 的 `250603` 与之吻合 → 2025 年构建。
- 车机 = `eng.build.20240613`（2024-06-13），**早了一年** → 车机上的 ClusterDebug 极可能是 2024 年旧构建，
  versionCode 大概率 ≠ 10601004，签名也可能不同。

## 含义（重要）

之前「签名/版本/包名/receiver 在 4.0 全过」是拿 **zip(2025 版)** 的 ClusterDebug 去对的。
如果车机(2024 版)的 ClusterDebug versionCode/签名不同，那么 `available()` 里
`info.longVersionCode == 10601004L` 和签名 SHA-256 check 在车机上**照样失败**——光放开指纹还不够。

## 待实车验证（唯一能定死的方法）

pull 车机 ClusterDebug.apk 下来做 aapt+keytool 对比。命令见主对话。

---

# 最终改动（2026-10-03 22:10）：`available()` 简化为「包存在就尝试」

> 取代上一节「行动记录」的方案：用户明确要求「代码里只要存在这个 APP，就去尝试」。

## 为什么不再做防篡改检查

上一版保留了签名/版本/指纹/receiver 五道检查，前提是「zip(2025 版) 的 ClusterDebug 就是车机上的那个」。
但已确认：zip 的 ClusterDebug versionName `1.6.1.4.2506031514` = 2025-06-03 构建，而车机是
`eng.build.20240613`(2024-06-13)，**早一年** → 车机的 ClusterDebug 极可能是旧构建，
versionCode/签名/指纹都对不上。这些检查只在作者那一台 5.1 开发车上成立，对 4.0 是无效门槛。

## 最终实现

`shared/.../hud/BydStandaloneHudOutput.kt` 的 `available()` 只保留两项：

1. `Build.VERSION.SDK_INT >= 28` 且包名属于 DiPlay/HeadUnitReloaded（及其 hudtest）—— 防滥用，保留。
2. **`getPackageInfo("com.byd.clusterdebug", 0)` 不抛异常**（即包已安装）—— 唯一门槛。

已彻底删除：
- `Build.FINGERPRINT` 精确/前缀匹配（`fingerprintAllowed()` 整个函数删除）
- 签名 SHA-256 = `efe3ca8a…` 校验
- `longVersionCode == 10601004L` 校验
- `FLAG_SYSTEM`、`receiver.enabled/exported/permission`、`signers.size == 1` 校验
- 随之删除 `ApplicationInfo`、`PackageManager`、`MessageDigest` 三个 import

## 性质

这是**试验版**：把「到底渲染不渲染」的判定从「APK 内的静态白名单」下放到「车机运行时」。
成功与否由实车验证决定，不看版本号。

## 实测步骤

1. 安装 `mobile-debug.apk`。
2. 开 CarPlay 导航。
3. DiPlay → BYD navigation → 设置区应出现（因为 `BydOutputSettings.available()` 里
   `BydStandaloneHudOutput.available()` 现在只要包装了就 true）→ 打开开关。
4. 看风挡 HUD / 仪表盘是否出现转向箭头 + 距离 + 路名。

**可能失败点**（都不是本改动能预判的，需实车看）：
- 车机 receiver 未 exported / 有 permission → 广播被系统拦（Android 10 对显式组件广播仍会校验 exported）。
- 车机 receiver 类名不是 `BroadcastReceiverCAN`。
- 车机 receiver 存在但内部逻辑不处理 `byd.hud.NAVIGATION` / extra `normal` 格式不同。

---

# 合并版（2026-10-03 22:17）：两个方案打进同一个 APK

用户要求「一起」→ 恢复路线 A 并与原生 HUD 简化判定合并编译成功。

## 两个开关在 App 里的位置（实车对照用）

| 开关 | 所在分区 | 显示条件 | 写入位置 |
|---|---|---|---|
| **原生 HUD**（clusterdebug 广播） | BYD navigation | `BydOutputSettings.available()`（现只要 clusterdebug 装了就 true） | `BydOutputSettings.KEY_ENABLED` |
| **自绘卡片**（路线 A） | 集群镜像区 | `if (adbCluster != null)` 才显示 | `ClusterHudView.enabled()` |

自绘卡片 key = `cluster_hud_title`：英文 "Navigation HUD on the dashboard" / 中文「仪表盘导航 HUD」。

## 建议实车对照顺序

1. 先只开**原生 HUD**（关掉自绘卡片）→ 看风挡/仪表盘有无内容。有 → 原生通了，卡片方案可弃。
2. 没有 → 再开**自绘卡片**（需 adb 集群镜像可用）→ 看集群屏上 DiPlay 自己画的箭头/距离/路名。
3. 两者都开也能并存，但画面会叠加（卡片背景不透明，会盖住地图流）→ 建议分开测。

---

# ✅ 实车验证成功（2026-10-03 22:20）：原生 HUD 在 DiLink 4.0 上奏效

**用户上车实测：原生 HUD（clusterdebug 广播）成功。** 自绘卡片方案随之移除。

## 最终形态

整个针对 DiLink 4.0 HUD 的改动收敛为**一个文件、一处逻辑**：

`shared/.../hud/BydStandaloneHudOutput.kt` 的 `available()`（+5 / -16）：
只保留①SDK≥28 且包名属 DiPlay；②`com.byd.clusterdebug` 已安装。

删掉的（都是作者那台 5.1 开发车的特征值，对 4.0 无效且会误挡）：
`Build.FINGERPRINT` 精确匹配、签名 SHA-256 `efe3ca8a…`、`longVersionCode == 10601004L`、
`FLAG_SYSTEM`、`receiver.enabled/exported/permission`、`signers.size == 1`。

## 为什么这印证了用户一路的判断

- 用户坚持「车机和 zip 都是 DiLink 4.0，只是打包时间不同」→ 对，两者同为
  `BYD-AUTO/DiLink4.0/DiLink4.0:10`(Android 10)。
- 用户指出「指纹写死是防篡改，不是平台专属」→ 对，但更准确：它是**只在作者自己那台
  开发车上验证过的白名单**，随固件打包时间失效。
- 实测证明车机侧的 `com.byd.clusterdebug` 收得下、画得出 —— 从来不是能力问题，只是被
  过时的静态白名单挡在门外。

## 自绘方案 ClusterHudView 已弃

- 源码已从 `common/src/main/java/.../ClusterHudView.kt` 移出，
  备份：`scripts/probe/hud_wip_archive/ClusterHudView.kt.bak`。
- `ClusterMirrorActivity` / `DiPlayActivity` / 两份 strings.xml 中相关改动已全部
  `git checkout` 还原，源码树无残留引用。
- `docs/dilink4-cluster-hud-design.md` 保留作历史参考，**勿再沿用**。

## 最终 APK

`mobile/build/outputs/apk/debug/mobile-debug.apk`（22:24 构建），
相对上游 HEAD 只有 1 个文件改动，干净可推送 / 可发 upstream PR。

---

# HUD 周期性消失的根因与修复（2026-10-03 22:30）

## 现象

用户反馈：HUD 隔一段时间就消失。疑为「刷新间隔太长」。

## 诊断结论：恰好相反

不是刷新慢，是**刷新太勤快 + 遭遇无法构造的包就立刻擦除**。

`BydStandaloneNavigationBridge` 每 **500ms** 拉一次（`scheduleWithFixedDelay(::tick, 0, 500, MILLISECONDS)`），
tick 里 `if (frame == null) output?.clear()`。

排查了三处可能的 null 来源：

1. `BydClusterFrame.from(maneuver)` —— **永不返回 null**（未知 maneuver 给 icon=0，
   注释：Unknown/no maneuver must not become a false straight arrow）。排除。
2. `route.currentApple()` → null —— 走 `activeManeuver()` 的两道过期闸门
   （`STALE_ROUTE_NS`=30s、`EMPTY_LIST_HIDE_NS`=3s）。次要嫌疑。
3. **`BydStandalonePackets.guidance()` → null** ← 真凶。

## 真凶：未知 maneuver 触发清除链

```
Apple maneuver type 未被 DiPlay 映射（when 的 else 分支，如 type 15/16/17 等）
  → BydClusterFrame.from() else -> icon = 0
  → BydStandalonePackets.guidance(icon=0, exit, dist, road)
  → BydFactoryTurnCode.map(0, exit)：第7行 `if (icon !in 2..28) return null`  ← icon=0 被拒
  → guidance() 返回 null
  → BydStandaloneSession.update()：`if (packet == null) { clear(); return }`  ← 擦掉 HUD
  → 发 clear 包给车机 → HUD 消失
```

即：**只要导航数据里出现一个 DiPlay 不认识的 maneuver 类型，HUD 就被擦除**，
直到下一个可映射的 maneuver 到来才恢复 —— 表现为「隔一段时间就消失」。

注意：这条链只影响 standalone（clusterdebug 广播）路，因为只有它用
`BydStandalonePackets.guidance()`；SOME/IP 与 amapservice 路另有自己的包构造。

## 修复

`shared/.../hud/BydStandaloneSession.kt` 的 `update()`：

```kotlin
// 原： if (packet == null) { clear(); return }
// 新：
if (packet == null) return   // 保持上一次 maneuver，不擦屏
```

理由：作者坚持「未知 maneuver 不得伪造成直行箭头」（安全考量，保留），
但「无法构造就不显示」不等于「应该擦掉有效导航」。保留旧信息比让 HUD 突然消失更安全，
驾驶员不会误以为导航断了。

清理机制未受影响 —— 导航真正结束仍走两条正常路径：
- iPhone 发 state=0(NoRouteSet)/2(Arrived) → `route.clear()` → `currentApple()`=null
  → tick 的 `frame == null` → `output?.clear()`
- App 关闭：`BydNavigationOutputs.endNow()`。

## 备用：若改良后仍周期性消失

则是 `STALE_ROUTE_NS`=30s（iPhone 超过 30 秒未发 ROUTE_GUIDANCE_UPDATE 即判过期）。
可放宽到 300s。该常量在 `BydHudRouteState` companion object，三条 BYD 路径共用，
改动影响面大于本次修复，故留作第二步。
