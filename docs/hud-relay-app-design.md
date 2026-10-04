# HUD Relay —— 独立 HUD 中继 App 调研与设计方案

目标：做一个**独立 APK**，跑在车机（DiLink 4.0）上，接受车机上其他 App 的导航/文本信息，
归一化后转发到比亚迪原生 HUD。本文先给出对 Carlink / CarLife / HiCar / 亿连 等常见 HUD
数据流的调研结论，再给出可落地的架构。

---

## 一、调研：HUD 数据流到底分几段

关键结论先说：**"HUD 数据流"不是一个协议，而是两截，中间有个断点。第三方独立 App 只能接后半截。**

```
[手机 App] ──①互联通道──> [车机上的互联 App] ──②车机内部通道──> [仪表 / HUD]
            私有 + 需认证                     Android 广播 / OEM 私有
            第三方拿不到                      第三方可监听（我们的位置）
```

### ① 手机 → 车机：全部是认证封闭协议，独立 App 接不了

| 方案 | 通道形态 | 有没有 HUD 相关能力 | 第三方能否接入 |
|---|---|---|---|
| **Apple CarPlay** | iAP2 over USB/WiFi，导航走 `NowPlayingUpdate(0x5001)` / `RouteGuidanceUpdate` | 有：maneuver + distance + road name | 本项目已逆向实现（MFi 凭据自备） |
| **ICCOA Carlink** | 1.0 投屏 / 1.5 融合桌面 / 1.6 小窗 / 2.0 镜像 / 3.0 多窗口 | 联盟明确写了「导航和音乐信息可显示在仪表盘或 HUD」，**由车机端 SDK 输出** | ❌ 仅联盟成员可拿 SDK，车机需过联盟认证 |
| **百度 CarLife(+)** | 六通道：数据流 / H.264 视频流 / 音频流（媒体音+导航音分开）/ 语音流 / 反控流 | 有：车机端明确宣传「直投手机导航关键信息到 HUD」「来电/时间/天气/车速/油耗 投影到 HUD」 | ❌ 车机端需百度测试发证书；手机端只是普通 App 无系统权限 |
| **华为 HiCar / Car Kit** | 分布式软总线；`CarBaseEngine` + `CarMapEngine`（应用侧）、HiCar SDK（设备侧）；鸿蒙侧 `navigationInfoMgr` 上报导航 | 有：官方宣传「导航信息可同步至中控屏与仪表盘」 | ❌ 应用侧要集成 SDK 并对接模板；设备侧要华为认证 |
| **亿连 Carbit** | 应用级映射（类似 Bosch mySPIN）：**手机 App 需集成 Carbit SDK**，仪表侧渲染；主战场是两轮车/后装 | 有：手机导航/音乐界面按仪表屏特点重排后投射 | ❌ 需 App 侧适配 SDK |

**共同规律**：这一段要么是「App 适配 SDK」（HiCar/Carbit），要么是「车机端集成 SDK + 联盟认证」
（Carlink/CarLife）。**没有任何一家给第三方独立 App 提供一个"监听别人导航数据"的入口。**

### ② 车机内部：这才是能抓的，且国内有事实标准

好消息是：车机上的互联 App / 车机自带地图拿到导航数据后，往仪表和 HUD 投递的那一段，
在国产车机上高度收敛到两套**公开的 Android 广播**协议：

#### (a) 高德 AmapAuto 标准广播 —— 事实标准，覆盖面最广

- 出向 action：`AUTONAVI_STANDARD_BROADCAST_SEND`（高德发出）
- 入向 action：`AUTONAVI_STANDARD_BROADCAST_RECV`（外部发给高德）
- 用 extra `KEY_TYPE` 区分接口

| KEY_TYPE | 含义 | 关键 extras |
|---|---|---|
| **10001** | **引导信息透出**（导航/巡航/模拟导航） | `TYPE`(0 GPS/1 模拟/2 巡航)、`CUR_ROAD_NAME`、`NEXT_ROAD_NAME`、`ICON`、`NEW_ICON`、`SEG_REMAIN_DIS`、`SEG_REMAIN_TIME`、`ROUTE_REMAIN_DIS`、`ROUTE_REMAIN_TIME`、`NEXT_SEG_REMAIN_DIS`、`CAMERA_DIST/CAMERA_TYPE/CAMERA_SPEED`、`SAPA_DIST/SAPA_NAME`、`LIMITED_SPEED`、`CUR_SPEED`、`ROAD_TYPE`、`TRAFFIC_LIGHT_NUM` |
| 10019 | 地图状态 | `EXTRA_STATE`：0 启动 / 3 前台 / 4 后台 / **8 开始导航** / **9 结束导航** / 10 模拟导航 / 24 巡航 / **39 到达目的地** |
| 13012 | 车道信息 | `EXTRA_DRIVE_WAY`（JSON） |
| 13011 | 路况光柱 | `EXTRA_TMC_SEGMENT`（JSON） |
| 12011 | 高速出口 | `EXIT_INFO_DISTANCE` / `EXIT_INFO_TIME` |
| 60073 | 红绿灯倒计时（非标扩展） | `redLightCountDownSeconds` 等 |

**第三方 App 可以直接监听**：开源项目
[AMap Companion](https://github.com/zuo-qirun/amap-companion) 就是靠读这套广播，
在主屏/仪表副屏画导航悬浮窗的——已经证明这条路走得通。

本项目里 `BydClusterBridge` 已经在**主动发送**这套广播给 `com.byd.amapservice`，
说明比亚迪的仪表/HUD 适配器吃的就是它（见 `BydClusterBridge.kt:18`）。

#### (b) 百度地图车机版 opencontrol 广播

| action | 含义 | 关键 extras |
|---|---|---|
| `com.baidu.map.auto.NOTIFY.ACTION_NAVI_INDUCUD` | 导航中诱导信息 | `TURN_ICONINFO`（**转向图标名字符串**）、`CUR_ROAD_NAME`、`NEXT_ROAD_NAME`、`LEFT_DISTANCE`、`LEFT_TIME`、`ALL_DISTANCE`、`ALL_TIME`、`CUR_SPEED`、`NEXT_TURN_ICON_DISTANCE` |
| `com.baidu.map.auto.NOTIFY.ACTION_NAVI_STATUE` | 导航生命周期 | method = `NAVI_START` / `NAVI_END` |
| `com.baidu.map.auto.NOTIFY.ACTION_MAP_INFO` | 前后台/版本 | `map_foreground` / `map_backgound` / `map_version` |
| `com.baidu.map.auto.NOTIFY.ACTION_ROUTE_INFO` | 算路页 | `ROUTE_START` / `ROUTE_END` / `ROUTE_COUNT` |

`TURN_ICONINFO` 是字符串而非数字，取值如：`turn_front`(直行)、`turn_left`、`turn_right`、
`turn_left_front`(左前方)、`turn_right_front`、`turn_back`(掉头)、`turn_ring*`(环岛)、
`turn_dest`(目的地)、`turn_via_1`(途径点)、`turn_left_side`/`turn_right_side`(靠左/靠右)、
`turn_branch_*`(分歧)、`turn_tollgate`(收费站)、`turn_inferry`(渡口)。
（完整对照见 https://lbsyun.baidu.com/index.php?oldid=12034 ）

另有一套 AIDL Service：`com.baidu.baidumaps.opencontrol.ACTION.REQUEST`，
通过 `method` 名字拉数据（`remainLeftDistance` / `nextTurnDistance` / `isNavigating` 等）。

#### (c) AAOS 官方路线（拿不到，仅供对照）

`CarService` + `InstrumentClusterRenderingService`，需要
`android.car.permission.CAR_INSTRUMENT_CLUSTER_CONTROL`——系统权限，第三方 App 无解。

#### (d) 比亚迪车机上的两个落地点（本项目已实车验证）

1. `com.byd.clusterdebug` —— 接收 `byd.hud.NAVIGATION`，extra `normal` 是十六进制 CSV 串。
   **裸广播后门，第三方 App 直接发就能上 HUD**（`BydStandaloneHudOutput.kt`）。
2. `com.byd.amapservice` —— 接收 `AUTONAVI_STANDARD_BROADCAST_SEND`（`BydClusterBridge.kt`）。
   DiLink 4.0 上原厂地图包名叫 `com.byd.automap`，没有 amapservice，所以走 1。

---

## 二、方案：做一个「车机侧 HUD 中继器」

### 定位

不做「接入 Carlink/CarLife 协议」——那需要联盟 SDK 和认证，做不到。
做的是**站在第 ② 段的中继/聚合器**：车机上任何 App 只要按公开协议广播（或调我们的 API），
我们就把它搬到 HUD 上，并做多源仲裁。

### 分层

```
com.shihab.diplay.hudrelay (独立 APK)

ingest/   入站适配（每路一个 Source）
  ├── AmapAutoSource      AUTONAVI_STANDARD_BROADCAST_SEND 10001/10019
  ├── BaiduNaviSource     com.baidu.map.auto.NOTIFY.*_NAVI_INDUCUD / _NAVI_STATUE
  ├── OpenApiSource       自有开放协议广播 com.shihab.diplay.hudrelay.PUSH
  ├── OpenApiService      AIDL IHudRelay（需要回执/高频的场景）
  └── DiPlaySource        （可选）DiPlay 的 CarPlay 输出，走同一通道时统一仲裁

model/    归一化
  ├── HudGuidance(maneuver, distanceM, roadName, routeRemainM, routeRemainS,
  │               speedKph, limitKph, camera…)
  ├── HudLine(text)                      自由文本行（歌词/来电/自定义）
  └── Maneuver                           内部 canonical = 高德 code

arbiter/  仲裁
  └── SourceArbiter       优先级 + 5s 心跳超时降级 + 结束信号立即让位

output/   出站（直接复用 shared/.../hud 下这几个文件）
  ├── BydStandalonePackets.kt   43,E0,00,3A... / 0x43F01018 / 0x43F01010 / 0x43FA1008
  ├── BydStandaloneSession.kt   start/去重/1s 保活/失败恢复
  └── BydStandaloneHudOutput.kt 发 byd.hud.NAVIGATION

ui/       MainActivity     源状态、当前帧、开关、原始 extras 诊断回放
```

### 统一 maneuver 词汇

内部 canonical 直接用**高德 code**，因为 BYD HUD 的图标集本身就是高德的
（`shared/src/main/assets/byd-hud-icons/0x<code>.png`），`BydFactoryTurnCode.map()` 也是按它查表：

```
0 none | 1 左 | 2 右 | 3 稍左 | 4 稍右 | 7 急左 | 8 急右
9 左掉头 | 10 右掉头 | 11 直行 | 13 环岛进入 | 24..33 环岛出口1..10 | 48 终点
```

各源 → canonical 的映射：
- **高德 `NEW_ICON`**：基本 1:1 透传（`BydClusterFrame` 就是直接 `putExtra("NEW_ICON", icon)`）
- **百度 `TURN_ICONINFO`**：新增字符串→code 表（`turn_front→11`、`turn_left→1`、`turn_right→2`、
  `turn_left_front→3`、`turn_right_front→4`、`turn_back→9`、`turn_ring*→13`、`turn_dest→48`…）
- **Apple iAP2**：已经有 `BydManeuverCodes.gaode(appleType, drivingSide)`

### 仲裁策略

1. 只有"正在导航"的源参与竞争（10001 有数据 / NAVI_START 之后）
2. 默认优先级：`DiPlay(CarPlay) > 高德车机版 > 百度车机版 > 开放 API`
3. 源静默 > 5s 视为已结束，自动让位给次高优先级
4. 收到显式结束信号（`EXTRA_STATE=9/39`、`NAVI_END`、API `end`）立即让位并清屏
5. **写 HUD 前先 clear 上一源**，避免两个源交替时残留

### 对第三方 App 的开放协议（我们自己定）

广播版（零依赖，任何 App 都能发）：

```kotlin
Intent("com.shihab.diplay.hudrelay.PUSH").apply {
    setPackage("com.shihab.diplay.hudrelay")
    putExtra("source", "com.example.myapp")          // 便于仲裁与诊断
    putExtra("priority", 50)                          // 可选，默认最低
    // 导航模式
    putExtra("maneuver", 1)                           // 统一 code
    putExtra("distance_m", 320)
    putExtra("road", "科苑南路")
    putExtra("route_remain_m", 8400)
    putExtra("route_remain_s", 720)
    // 或纯文本模式（与导航互斥，二选一）
    putExtra("text", "前方 500 米有测速")
    // 结束
    putExtra("end", false)
}
```

AIDL 版留给需要回执/高频推送的 App（`IHudRelay.push(HudFrame): Int`）。

---

## 三、从本项目代码学到的硬约束（照抄，别自己造）

1. **包名白名单**：`BydStandaloneHudOutput.available()` 要求自身包名前缀是
   `com.shihab.diplay` 或 `com.andrerinas.headunitrevived`。
   → 独立 App 用 `com.shihab.diplay.hudrelay` 即可放行；若改包名必须同步改前缀表。
2. **文本上限**：HUD 路名 feature（`0x43FA1008 + i<<12`）HAL 上限 96 字节，
   `streetName()` 封顶 48 字符 / 24 汉字，UTF-16LE，超出直接截断。
3. **有导航时文本必须塞进 guidance 包**：`update(icon, exit, dist, line ?: frame.road)`。
   HUD 只有一行文本位，另发一个文本包会跟箭头抢这一行、看起来像卡顿。
   无导航时才单独 `showText()`（`BydStandaloneNavigationBridge.tick()`）。
4. **距离范围** `0..16777214`；且 BYD HUD 在 0~10 m 会显示乱码
   （`BydHudPayload.MIN_DISTANCE_METERS = 11`，standalone 路建议同样钳到 ≥11）。
5. **采样 250ms + session 侧 1s 保活去重**：歌词逐行滚动，500ms 采样会漏行；
   去重在 session 里，采样加快不增加线上发送量。
6. **发送侧要带隐藏 flag** `FLAG_RECEIVER_INCLUDE_BACKGROUND = 0x01000000`，
   stock client 就是这么发的（给 amapservice 那一路用；clusterdebug 那一路用
   `FLAG_RECEIVER_FOREGROUND`）。
7. **Android 8+ 静态注册受限**：入站接收器一律**动态注册**（前台 Service 里），
   别指望 manifest 静态注册能收到 `AUTONAVI_STANDARD_BROADCAST_SEND`。
   车机 ROM 后台限制更狠，需要常驻前台服务 + 通知（也顺便说明"正在中继 HUD"）。
8. **无需 root / 无需 ADB**：clusterdebug 广播是普通 App 就能发的，
   `Process.myUid()` 是普通 uid 也照样渲染（已实车验证）。

---

## 四、价值与局限（说实话）

**能做到**
- 车机上任意 App 通过公开广播/我们自己的 API 往 HUD 写导航和文本
- 把高德车机版、百度车机版的导航引导搬到 HUD（DiPlay 目前只管 CarPlay，这一段是空白）
- 多源仲裁，谁在导航谁上屏
- 自定义内容（车速、限速、测速、歌词、来电）上 HUD

**做不到 / 有风险**
- 拿不到 Carlink / CarLife / HiCar **手机端到车机端**那一段的数据（要 SDK + 认证）
- 如果车机上的 Carlink/CarLife 客户端**自己已经在投 HUD**，我们跟它抢的是同一条通道，
  "最后写入者赢"，会出现互相覆盖。这不是 bug 是通道特性，只能靠仲裁窗口缓解
- 不同车型 ROM 的广播行为、后台限制、DPI 都不一样，需要实车逐个验证
  （AMap Companion 的 wiki 也明确写了"无法保证所有车型完全一致"）

---

## 五、落地步骤建议

1. 建模块/工程，把 `BydStandalonePackets` / `BydStandaloneSession` / `BydStandaloneHudOutput`
   三个文件搬过去（它们只依赖 `BydFactoryTurnCode`、`BydManeuverCodes`，无网络无 NDK）
2. `AmapAutoSource` + `BaiduNaviSource` 两个接收器 + 归一化，先只做"打印到 UI"验证收得到
3. 接 `SourceArbiter` + 出站，实车验证高德导航 → HUD
4. 补 `OpenApiSource` 广播 + AIDL，写一份给第三方 App 的接入文档
5. 诊断面板：录制最近 200 条原始广播的 action/KEY_TYPE/extras，支持导出，便于适配新车型

---

## 参考资料

- 高德 AmapAuto 标准广播协议速查（社区整理）：https://github.com/zuo-qirun/amap-companion/wiki/AmapAuto-Standard-Broadcast-Protocol
- 高德车机版入门指南：https://blog.csdn.net/weixin_39729840/article/details/114408346
- 百度地图车机版 opencontrol（含转向图标名对照表）：https://lbsyun.baidu.com/index.php?oldid=12034
- 百度地图车机版开放平台概述：https://vodp.baidu.com/docs/map/overview
- 百度 Android HUD SDK 变更记录：https://lbsyun.baidu.com/index.php?title=hud-sdk-android/updateLog&oldid=5198
- ICCOA 联盟（Carlink SDK 仅对成员开放）：https://www.iccoa.cn/
- 亿连 Carbit（应用级映射，需 App 集成 SDK）：https://carbit.com.cn/home
- HiCar 开放能力：https://developer.huawei.com/consumer/cn/doc/HiCar-Guides/carengine-0000001195195891
- AAOS Instrument Cluster API（需系统权限，对照用）：https://source.android.com/docs/automotive/displays/cluster_api
