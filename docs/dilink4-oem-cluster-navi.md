# DiLink 4.0 原车高德「投仪表盘」机制与关闭方案

分析对象：`Di4.0_1for2_21.1.2.2506090.1_0(1).zip`（DiLink 4.0 中国版，`1for2` 是车型/平台变体名）。
本文只讲一件事：**原车高德的仪表导航投屏是怎么起来的，DiPlay 镜像到仪表时怎么把它关掉。**

---

## 0. 结论（先看这里）

> **2026-10-04 重要修正**：上一版把这件事理解成「三路抢一个独占屏，谁抢到算谁的」。
> 实车观感 + 固件核对后更正为：**投屏区是一块共享虚拟屏，投上去的东西是层层叠加（overlay）
> 的关系——后加的盖住先加的，下层 App 不会停，只是看不见。** 详见 §0.5。

1. **投屏模型 = 同屏层叠，不是独占抢占。**

   车机把仪表投影区做成一块**共享虚拟显示**（`fission_*` 命名族），凡是投上来的 App 都是往它
   上面**加一层 window**，由 WindowManager 按 z-order 合成。DiPlay 和原车高德用的是**同一套机制**：

   | 来源 | 组件 | 怎么叠上去的 |
   |---|---|---|
   | 车机自带高德 | `com.byd.automap` → `com.byd.automap.extra.MeterActivity` | `ActivityOptions.setLaunchDisplayId(clusterId)` + `startActivity` |
   | 手机高德中转 | `com.example.amapservice`（system uid、persistent） | 收高德标准广播，走 **CAN** 送导航（不经 window） |
   | DiPlay | `ClusterMirrorActivity`（`am start --display N`）/ `ClusterMapPresentation`（`Presentation(display)`） | 往同一个 display 加 window |

2. **原车高德确实「还在跑」**：被 DiPlay 盖住的是它的 `MeterActivity` window，
   它的进程、导航、渲染**都没停**。所以「关掉它」的正确作用点是**进程级**，不是 display 级。

3. **HAL 写不动它**（对上一版 §6③ 的修正）：原车高德的画面是它自己的 **Android Activity window**，
   而 `INSTRUMENT_SEND_NAVI_STATUS_SET` 是**仪表 native 侧**的导航卡状态位。写 HAL=3 只能关掉 native
   那一半（且对 CAN 那路有用），**关不掉原车高德的 Activity window**。

4. **没有留给第三方的「关闭仪表投屏」公开接口**：`PushService`（exported）的 `onStartCommand`
   只 `return START_STICKY`；`switchMeterType` 广播只是 App 发给自己的 toast 通知；
   `byd.intent.action.KILL_BydAutoMap` 在自带高德里只重置桌面小部件（不关仪表）。
   → 想让它从下层消失，只有**杀进程 / 冻结**。

4.5 **「用 adb 发广播关掉它」这条路走不通**（本节回答一个具体问题，证据见 §2.4.1）：
   - 自带高德真正的「退出仪表」信号是 `MeterExtKt.quitMeterAct()` / `quitCentralMeterAct`，
     而它们走的是 **`LocalBroadcastManager`（进程内广播）**，adb 送不进去；
   - **唯一**能触达它的全局广播是 `android.intent.action.ACTION_SHUTDOWN`，而它在本机
     `framework-res.apk` 的 `<protected-broadcast>` 清单里 → adb shell 发送会被 AMS 拒绝；
   - 剩下的 exported 组件（`PushService` / `MeterActivity` / `KILL_BydAutoMap`）都是死路，见 §2.4.1。
   - **广播能做的只有一半**：`KILL_BydAutoMap` 能断 `com.example.amapservice`（手机高德 → CAN 那路），
     断不了车机自带高德那一路。

5. **层叠模型下真正的风险是「层序反转」**：谁最后启动谁在上面。原车高德若因导航触发而
   `startMeterAct`，会盖到 DiPlay **上面**来。这比「把它关掉」更值得处理——**保住 DiPlay 的层级是主线**。

6. **已采用的手段：投屏期间 `pm disable-user`，投屏结束 `pm enable`**（实现见 §7）。
   - 车机是 **Android 10 / API 29** → 没有 `pm suspend` / `am set-inactive`，没有真正的
     「冻结」原语；**杀进程也没用**（`force-stop` 后会反复重启）。
   - 其余备选：保层（重发 `am start`）、`KILL_BydAutoMap`（只断 CAN 那路）、写 HAL（只对
     native 那半有效）。三者都**不是**本轮采用方案。
   - DiPlay 已有现成通路：`BydAdbShell`。
   - **2026-10-04 追加：禁用的粒度改成可配置**（§7.5）。用户实测**整包禁用会连带「小屏导航」
     失效**，而 DiPlay 镜像恰恰依赖小屏/全屏导航模式 → 整包禁用是自相矛盾的。原车高德的仪表
     画面只由**一个 Activity**（`com.byd.automap.extra.MeterActivity`）负责，所以「只禁这个
     组件」理论上能压掉投屏、同时保住导航模式。三档：**不处理 / 只禁投屏组件（默认）/ 禁用整包**。

7. **注意**：「仪表导航模式」（`INSTRUMENT_NAVI_TYPE`，方向盘菜单选的 Off / Turn on by navi /
   Small / Full）是**全局**开关，设成 Off 会**同时关掉 DiPlay 自己的镜像**
   （见 `BydClusterNaviMode.showsMap`）。**不要**用它。

8. 精确的 feature 数值与「哪一种手段在这台车上真的生效」**必须上车实测**。
   探针：`scripts/oem_cluster_navi_off.sh`（关停手段）、`scripts/cluster_layer_probe.sh`（层序与归属）。

---

## 0.5 投屏模型：同一块共享虚拟屏上的层叠（2026-10-04 新增）

**这是本次分析最重要的修正。**

上一版假设「仪表屏上只有一个导航投屏位，谁抢到算谁的」。实车观感（DiPlay 盖住原车高德，
但原车高德仍在跑）与固件核对表明：**它是一块共享虚拟屏，投上去的东西层层叠加，
后加的盖住先加的，下层不会停。**

三条互相独立的证据：

### 证据 1 —— 原车高德自己就是这么找屏的

`com.byd.auto.proxy.ext.BydAutoExtKt.meterDisplay(Context)` 反汇编等价于：

```java
Display[] displays = ((DisplayManager) ctx.getSystemService(DisplayManager.class)).getDisplays();
List<Display> hits = new ArrayList<>();
for (Display d : displays) {
    if (!d.isValid()) continue;
    String name = d.getName();
    if (name.startsWith("fission_")) {            // ① 只挑 fission_ 开头的
        hits.add(d);
        if (name.equals("叠加视图 #1")) { /* 提权 */ }   // ② 优先「叠加视图 #1」
    }
}
return hits.isEmpty() ? null : hits.get(0);
```

拿到 display 后（`MeterExtKt.startMeterAct`）：

```java
ActivityOptions opts = ActivityOptions.makeBasic().setLaunchDisplayId(d.getDisplayId());
Intent i = new Intent(ctx, MeterActivity.class).putExtra("meterType", type);
ctx.startActivity(i, opts.toBundle());
```

**重点两处**：
1. 它靠 `name.startsWith("fission_")` 找目标屏 —— 和 DiPlay 找的是**同一命名族**；
2. 车机 OEM 给这块屏起的名字里就带 **「叠加视图」**（overlay view）—— OEM 自己就把它
   定义成「叠加层」，不是一块独立物理屏。

### 证据 2 —— DiPlay 找的是同族屏

`common/.../DiLink51ClusterLayout.kt`：

```kotlin
const val BASE    = "fission_bg_XDJAScreenProjection"   // DiLink 5.1
const val DILINK4 = "fission_bg_xdjaVirtualSurface"    // DiLink 4.0
```

两个名字都落在 `fission_` 族里，且字面写着 **VirtualSurface / ScreenProjection** ——
是**投影面**，不是独立屏。

### 证据 3 —— DiPlay 源码自己的注释

`common/.../ClusterMapPresentation.kt`：

> BYD exposes the cluster's projection area as **public presentation displays** owned by
> `com.byd.containerservice`; **the stock map draws there the same way**.

即「原车地图和 DiPlay 用同一种方式画在同一块投影 display 上」。

### 三个直接推论

| # | 推论 | 说明 |
|---|---|---|
| 1 | **谁后启动谁在上** | 两边都是普通 window（Activity / Presentation），z-order 由 WindowManager 按启动顺序给 |
| 2 | **原车高德没停** | 它的 `MeterActivity` 进程仍在跑导航、仍在渲染，只是被盖住 → 「关它」只能走**进程级** |
| 3 | **层序会反转** | 原车高德若后 `startMeterAct`，它会盖到 DiPlay 上面 → 这才是要防的主风险 |

> 顺带：`ClusterMapPresentation` 注释里写的是 `com.byd.launchermap`，而本机固件里包名是
> `com.byd.automap` —— 同一角色的不同固件版本命名，别把注释里的包名当成本机事实。

---

## 1. 载荷拆解

`apks/` 下共 4 个已抽取的 APK（其余在 `system.img` / `vendor.img` 内）：

| APK | 包名 | 关键点 |
|---|---|---|
| `BydAutomap.apk` | `com.byd.automap` | 车机自带高德地图，173 MB，4 个 dex |
| `AmapService.apk` | `com.example.amapservice` | 手机高德→仪表中转，system uid、persistent |
| `ClusterDebug.apk` | `com.byd.clusterdebug` | 仪表/HUD 后门（DiPlay HUD 已在用） |
| `XdjaContainerService.apk` | `com.xdja.containerservice` | fission 容器服务，持有仪表 VirtualDisplay |

---

## 2. 自带高德：`com.byd.automap`

### 2.1 对外组件（AndroidManifest）

```
activity  com.byd.automap.extra.MeterActivity         exported=true  singleTask
          taskAffinity=com.byd.automap.extra  excludeFromRecents=true
service   com.byd.automap.service.PushService         exported=true  action=com.byd.automap.secret
service   com.autosdk.protocol.service.ProtocolService exported=true action=action.com.autosdk.protocol.ProtocolService
service   com.byd.automap.NaviService                 (无 intent-filter)
service   com.automap.carlife.service.CarLifeManagerService exported=true (受 com.byd.launchermap.carlie 权限保护)
receiver  com.byd.automap.user.account.BydAccountStaticReceiver exported=true (账号类，无关)
```

### 2.2 仪表投屏的驱动逻辑

`MeterActivity`（`com.byd.automap.extra.MeterActivity`）：

```
handleMeterType(int meterType)              // :130
    if (MeterStateViewModel.bindState != true) return
    int status = MeterExtKt.generateScreenStatus(meterType)
    if (status != 0) VehicleManager.setNaviScreenStatus(status)

initMap()                                   // :122
    HandlerHelper.sendMessage(1, mapTag, 0, 0, 12, null)
```

`MeterExtKt.generateScreenStatus(int)` 反汇编等价于：

```java
int generateScreenStatus(int meterType) {
    switch (meterType) {
        case 1:  // MeterType.close
        case 2:  // MeterType.simple
                 return 3;   // NaviScreenStatus.STOP_SCREEN
        case 3:  return 1;   // START_SMALL_SCREEN
        case 4:  return 2;   // START_FULL_SCREEN
        default: return 0;   // UNKNOWN，调用方会跳过写 HAL
    }
}
```

`VehicleManager.setNaviScreenStatus(int)` → `BydAutoSettingProxy.setEventValue(INSTRUMENT_SEND_NAVI_STATUS_SET, status)`。

`PushService.switchMeter(...)`（**内部**调用，不是外部入口）：

```
setMapSendingState(1)
if (getMeterType().value == type) 日志 "switchMeter same with last"
else setMeterType(type)
when (type) { 2 -> startMeterAct(ctx, type) ; 3,4 -> ... ; else -> "default meter type do not handle" }
... 关闭分支：
  setNaviScreenStatus(generateScreenStatus(type))
  handlerHelper.sendMessage(2, mapMeter, 0,0,0, null)
  quitMeterAct(context)
  setOpenMeterFrom(4)
  reportMeterExit(...) / reportMeterStart(...)
```

`switchMeter` 的调用者都在 App 内部：`PushService$meterListener$1.onNavTypeChanged`、
`BydMapService`、`HomeMapFragment`、`CentralActivity`。

### 2.3 枚举（反汇编值）

| 枚举 | 值 |
|---|---|
| `MeterType` | `default=0`, `close=1`, `simple=2`, `small=3`, `full=4` |
| `MeterScreenType` | `INCH_8=0`, `INCH_12=1` |
| `NaviScreenStatus` | `UNKNOWN=0`, `START_SMALL_SCREEN=1`, `START_FULL_SCREEN=2`, `STOP_SCREEN=3` |

### 2.4 监听的广播

`CentralActivity.registerKillMapBroadcastReceiver()` 动态注册两个：

| action | 收到的行为 |
|---|---|
| `byd.intent.action.KILL_BydAutoMap`（常量名 `ACTION_APP_IS_KILLED`） | 只做 `MapWidgetManager.resetWidget(null,1)`（重置桌面地图小部件）+ `uploadBaseExitEvent(0)`。**不关仪表**。 |
| `switchMeterType` | 只 `showToast(getSwitchMeterToast(meterType), 3000)`。**只弹提示**，真正的切换在别处。 |

另有两个**全局**注册的接收器（`Context.registerReceiver`，不是 Local），都只监听一个 action：

| 位置 | action | 收到的行为 |
|---|---|---|
| `BydMapService$shutDownReceiver$1`（`dex2.txt:485049`） | `android.intent.action.ACTION_SHUTDOWN` | `MeterExtKt.quitMeterAct(ctx)` + `setMapSendingState(0)` + `setNaviStatus(0)` |
| `PushService$shutDownReceiver$1`（`dex3.txt:1c1b78`） | `android.intent.action.ACTION_SHUTDOWN` | 同上（`quitMeterAct` + 清状态） |

`switchMeterType` 这个 action 是 `PushService.switchMeterType4Central(int)` **自己发给自己 UI 的**：

```
PushService.switchMeterType4Central(int type)
    Intent().setAction("switchMeterType").putExtra("meterType", type)  → 发给 CentralActivity
```

**所以从外部广播 `switchMeterType` 只会看到一句 toast，不会有任何实际切换。**
`byd.intent.action.KILL_BydAutoMap` 对自带高德也没有关仪表的作用。

### 2.4.1 「用 adb 广播关掉它」为什么走不通（2026-10-04 定论）

把自带高德里**所有**能导致「仪表退出」的入口列全之后，结论是**没有一条能被 adb 广播触达**。

**① 真正的「退出仪表」信号是进程内广播（LocalBroadcastManager）**

```java
// MeterExtKt.quitMeterAct(Context)   —— dex2.txt:3611bc
ContextExtKt.sendLocalBroadCast(ctx, new Intent("quitMeterAct"));
//   → LocalBroadcastManager.getInstance(ctx).sendBroadcast(intent)
//   → MeterActivity.quiteReceiver 收到 → finish()

// BydMapService.exitProcess()        —— dex2.txt:2e1aee
ContextExtKt.sendLocalBroadCast(ctx, new Intent("quitCentralMeterAct"));
```

`MeterActivity.onCreate`（`3608a8`）注册的正是 **Local**：

```java
LocalBroadcastManager.getInstance(this)
    .registerReceiver(quiteReceiver, IntentFilter("quitCentralMeterAct", "quitMeterAct"));
```

`LocalBroadcastManager` 完全在**进程内**派发、不经过 Binder，**adb 广播送不进去**。

**② 唯一能触达它的全局广播是 `ACTION_SHUTDOWN`，而它受保护**

上面两个 `shutDownReceiver` 收到 `android.intent.action.ACTION_SHUTDOWN` 时会执行
`MeterExtKt.quitMeterAct(ctx)` —— 这是**唯一**一条「全局广播 → 退出仪表」的路径。

但 `ACTION_SHUTDOWN` 在**本机 `framework-res.apk` 的 `<protected-broadcast>` 清单里**
（已从 `system.img` 抽出核对：`work/framework-res.apk` → `AndroidManifest.xml`，第 69 条）。
受保护广播只允许 system 系 uid（root / system / phone / bluetooth / nfc）发送，
**adb shell（uid 2000）会被 AMS 以 `SecurityException: not allowed to send broadcast` 拒绝**。
且它的语义是「系统正在关机」，会惊动其它系统组件 —— **不建议**，只作为车测探针里的一个待验证项。

**③ 三个「看起来像入口」的组件全是死路**

| 入口 | 实测行为 |
|---|---|
| `PushService`（exported，action `com.byd.automap.secret`） | `onStartCommand` 只有 `invoke-super` + `return START_STICKY`（`dex3.txt:1c29c4`），**空转**；`onBind` 无 |
| `MeterActivity`（exported，singleTask，读 `meterType` extra） | `onNewIntent`（`360a50`）→ `handleMeterType(type)`；而 `handleMeterType`（`3606f4`）**只调 `setNaviScreenStatus(generateScreenStatus(type))` 写 HAL 位，从不 `finish()` 自己**；且 `onNewIntent` 末尾还会 `moveCentralTaskToFront()` 把自己顶到前台 |
| `byd.intent.action.KILL_BydAutoMap` | 只 `MapWidgetManager.resetWidget` |

**结论：adb 广播这条路是死的。** 想让原车高德那块窗口从仪表上消失，只能走进程级（§6 ① / ④）。

**④ 广播能做的另一半**：`KILL_BydAutoMap` 对 **`com.example.amapservice`（手机高德 → CAN 那路）**
有效（§3.2 已确认：`reSetGuideInfo()` + `sendNaviToCluster(1)` + 写 `INSTRUMENT_SEND_NAVI_STATUS_SET`）。
即：**广播只能断"手机高德投仪表"那一路，断不了"车机自带高德"那一路。**

### 2.5 附带发现

- `android.hardware.bydauto.BYDAutoFeatureIds` 在自带高德 dex 里是**编译期 stub**
  （`<init>` 抛 `RuntimeException("Stub!")`，静态字段 value 全是 0）。
  所以**数值拿不到，必须用符号名在车机上解析，或从平台 jar 里读**。
- 自带高德 App 里存在 `INSTRUMENT_GET_NAVI_DESTINATION`、`INSTRUMENT_NAVI_TYPE`、
  `INSTRUMENT_SEND_NAVI_STATUS_SET`、`INSTRUMENT_SEND_DESTINATION_STATUS_SET`、
  `INSTRUMENT_GUIDE_INFO_SIMPLE_SET` 等上百个 `INSTRUMENT_*` 特征名（完整清单见"附录 A"）。

---

## 3. 手机高德中转：`com.example.amapservice`

### 3.1 组件边界

```
package  com.example.amapservice
application  android:persistent="true"     sharedUserId="android.uid.system"
uses-permission  BYDAUTO_BODYWORK_COMMON / BYDAUTO_INSTRUMENT_COMMON / RECEIVE_BOOT_COMPLETED
receiver  com.example.amapservice.BootCompleteReceiver   exported=true  (BOOT_COMPLETED)
service   com.example.amapservice.AmapService            exported=true  （无权限保护）
```

`AmapBroadReceiver` **不在 Manifest 里**，是 `AmapService` 运行时动态注册的
（服务 persistent，开机常在）。

> 注意：固件里这个 APK 位于 `/system/priv-app/AmapService/`，但**包名是占位的
> `com.example.amapservice`**，不是 `com.byd.amapservice`。仓库里 `BydClusterBridge` 等
> 写死旧包名的位置已知不通（详见 `docs/dilink4-hud-live-findings.md`）。

### 3.2 `AmapBroadReceiver.onReceive` 分派

先按 `action.hashCode()` 映射，再 `packed-switch`：

| action | 分支行为 |
|---|---|
| `AUTONAVI_STANDARD_BROADCAST_SEND` | `KEY_TYPE=10001` 时解析 `NEXT_ROAD_NAME`/`ROUTE_REMAIN_DIS`/`ROUNG_ABOUT_NUM`… 填 `GuideInfo`；`TYPE` 填 `naviState`；`naviState==0` 或 `==12` 视为"停止导航"，然后 `sendNaviToCluster(1)` |
| `byd.intent.action.KILL_BydAutoMap` | 日志 `KILL_AUTO_MAP` → `mIsKillAutoMap=true` → `reSetGuideInfo()` → `naviState=0` → `sendNaviToCluster(1)` |
| `android.intent.action.ACTION_SHUTDOWN` | `mIsShutdown=true` → `reSetGuideInfo()` → `setNaviStatus(INSTRUMENT_SEND_NAVI_STATUS_SET, …)` → `naviState=0` → `sendNaviToCluster(1)` |
| `android.intent.action.APP_FACTORY_RESET` | `AutoContainerManager.sendInfo(…)` |

`sendNaviToCluster(int)` 里真正落地的是 `sendNavigateInfoToCAN()` —— **走 CAN 总线**，不是普通窗口。

### 3.3 写 HAL 的确切形态（重要的复用样板）

```java
// AmapService.setNaviStatus(int featureId, int value)
mEventValue.intValue = value;                                        // BYDAutoEventValue 的 public 字段
mBYDAutoInstrumentDevice.set(new int[]{ featureId }, mEventValue);   // ← 通用写入
```

对应字节码：

```
iget-object v0, AmapService;.mEventValue:Landroid/hardware/bydauto/BYDAutoEventValue;
iput v4, v0, Landroid/hardware/bydauto/BYDAutoEventValue;.intValue:I
iget-object v4, AmapService;.mBYDAutoInstrumentDevice:Landroid/hardware/bydauto/instrument/BYDAutoInstrumentDevice;
const/4 v0, #int 1
new-array v0, v0, [I
const/4 v1, #int 0
aput v3, v0, v1                       // v3 = featureId
invoke-virtual {v4, v0, v2}, …BYDAutoInstrumentDevice;.set:([ILandroid/hardware/bydauto/BYDAutoEventValue;)I
```

> `AmapService` 是 system uid 才拿得到 `BYDAUTO_INSTRUMENT_COMMON`。
> DiPlay 是普通 App，**但它的 `BydClusterSongTool` 已经证明**：以 **shell 用户** 跑
> `app_process` 时，`BYDAutoInstrumentDevice.getInstance()` 能建出来、`set`/`setMediaState`
> 能返回 0（autoservice 对 shell 放行）。**同一条路可以直接用来写 navi status。**

---

## 4. 仪表屏容器：`com.xdja.containerservice`

```
service  com.xdja.containerservice.AutoDisplayService   exported=true  action=com.xdja.containerservice.AutoDisplayService
         → 持有仪表 VirtualDisplay（字段 mDisplay / mDisplayInfo:QtDisplayInfo）
service  com.xdja.containerservice.AutoContainerService（native: android.os.IAutoContainer）
         → 消息总线：MESSAGE_NOTIFY_CLUSTER_STATE / MESSAGE_RECEIVED_INFO / _INFO2 / _JSON
           checkSendPermissionAndAllowType(int) 白名单；sendInfo(type, arg, data)
```

App 侧客户端是框架类 `android.os.AutoContainerManager`（`sendInfo(int,int,String)`），
`AmapService` 的 `APP_FACTORY_RESET` 分支正是在用它。
`ContainerService.getQtProjectionDispInfo(int)` 给出仪表投影屏信息。

> 这条总线是"App ↔ 集群容器"的通用信道，**发什么 type 能关掉哪一路投屏**没有文档，
> 且 `checkSendPermissionAndAllowType` 会校验调用方。本次未继续深挖——
> 上面 ① ② ③ 三个手段已经够用，且不需要碰它。

---

## 5. DiPlay 侧可用的既有能力

| 能力 | 位置 | 说明 |
|---|---|---|
| 本地 adbd shell | `shared/.../hud/BydAdbShell.kt` | 走 `127.0.0.1:5555`，后台不弹授权框，失败 30s 重试 |
| 以 shell 身份跑代码 | `shared/.../hud/BydClusterSong.kt` 的 `BydClusterSongTool` | `CLASSPATH=<apk> app_process /system/bin <类> <args>`，内部 `ActivityThread.systemMain()` 拿 system context，`Class.forName` 建 BYD 设备 |
| 读仪表导航模式 | `shared/.../hud/BydClusterNaviMode.kt` | `service call autoservice 5 i32 1007 i32 0x40C03032` → 1 Off / 2 Turn on by navi / 3 Small / 4 Full |
| 按模式暂停自己的镜像 | `shared/.../hud/BydClusterMapPause.kt` | 模式为 Off / Turn on by navi 时给 iPhone 发 `stopUI` |
| 仪表镜像 | `ClusterMirror.kt` / `ClusterMirrorActivity.kt` | `am start --display <clusterDisplayId> -n <pkg>/<activity>` |
| 找 cluster display 名 | `common/.../DiLink51ClusterLayout.kt` | 4.0 = `fission_bg_xdjaVirtualSurface`，5.1 = `fission_bg_XDJAScreenProjection`；同属 `fission_*` 族 |
| 抬层（保持置顶） | 复用 `ClusterMirror` 的 `am start` | 对已存在的 activity 重发即带回前台；用 `dumpsys window displays` 验证归属 |

**结论：所需通道全都现成**（包括层叠模型下「保层」所需的 `am start` 与 `dumpsys`），
不需要新权限、不需要新协议。

---

## 6. 可切断点（按推荐度）

> 层叠模型下的排序变了：**让原车高德从下层消失只能靠 ①（进程级）；③ 关不掉你看到的那块窗口。**
> 如果只是不想被抢，用 §7 的「保层（A）」就够了，不必关。

### ① 停掉原车高德 App —— 唯一能真让它从下层消失的手段

```sh
adb shell am force-stop com.byd.automap
```

- 直接杀掉 `MeterActivity` 所在进程 → 仪表屏上的原车高德窗口消失，且不会因为导航状态变化再抢回来。
- 代价：车里那套高德地图也会一起停（用户此时正用 CarPlay 导航，通常无所谓）。
- 风险：App 有 `PushService`（foregroundServiceType=connectedDevice）和开机接收器，
  可能被拉起。**建议配一个值守循环**（见下）。
- **为什么必须做成"镜像期间持续压制"而不是"启动时杀一次"**：用户实测「两边同时投屏，时间长了
  仪表会异常重启」。也就是说只要两者同时在仪表上叠着，系统就会积累到崩。所以压制要覆盖
  整个镜像时段，而不是只在 `launch()` 那一刻。

```text
值守循环（DiPlay 侧，只在镜像期间跑）：
  每 2~3 s：
    pidof com.byd.automap  → 非空 且 不是我们自己拉起的 → am force-stop com.byd.automap
    （可选）同时重发一次 KILL_BydAutoMap，断 com.example.amapservice 那路
  镜像 stop() → 退出循环
```

- 判据用 `pidof`/`ps -A | grep com.byd.automap`，别用 `am stack list`（不同固件输出差异大）。
- 若连续几轮都被拉起（说明系统在主动重启它）→ 退到 ④ `pm disable-user`。
- DiPlay 侧落地：接在 `ClusterMirror.launch()` 成功之后的线程里；`ClusterMirror.stop()` 里停掉。
  命令全部走 `ClusterMirror.shell()` / `BydAdbShell.run()`，无需新权限。

### ② KILL 广播 —— 轻量补充

```sh
adb shell am broadcast -a byd.intent.action.KILL_BydAutoMap
```

- 对 `com.example.amapservice` 有效：它收到后会 `reSetGuideInfo()` 并 `sendNaviToCluster(1)`，
  停掉 CAN 那路仪表导航。
- 对 `com.byd.automap` **无效**（只重置桌面小部件）。
- 建议和 ① 一起发，两条路一起断。

### ③ 写「仪表导航状态 = STOP」—— 只对 native / CAN 那半有效

> **2026-10-04 修正**：原车高德的画面是它**自己的 Android Activity window**，在 Android 侧；
> 本特征位是**仪表 native 侧**的导航卡状态。写它**关不掉原车高德的 Activity**，
> 只能关掉 native 那一半（对 `com.example.amapservice` 的 CAN 路有效）。
> **不要**指望它去盖掉你看到的那块原车高德窗口。

语义：App 告诉仪表"我的导航画面停了"，等价于自带高德 `MeterType.close` 时做的事。

```java
// 在 shell 身份下运行（复用 BydClusterSongTool 的模板）
BYDAutoInstrumentDevice dev = BYDAutoInstrumentDevice.getInstance(systemContext);
BYDAutoEventValue v = new BYDAutoEventValue();
v.intValue = 3;                                       // NaviScreenStatus.STOP_SCREEN
int[] ids = { BYDAutoFeatureIds.INSTRUMENT_SEND_NAVI_STATUS_SET };   // 符号名解析，别写死数值
dev.set(ids, v);                                      // 0 = 成功
```

或走 binder：

```sh
# GET 仪表导航模式（已实测）：1 Off / 2 Turn on by navi / 3 Small / 4 Full
adb shell service call autoservice 5 i32 1007 i32 1086337074

# SET「导航模式」—— 这是全局开关，会把 DiPlay 自己的镜像也关掉，仅用于对照
adb shell service call autoservice 6 i32 1007 i32 0x4C10A018 i32 1

# SET「导航状态」= STOP(3)：目标手段，transaction 与参数形态需实测确认
adb shell service call autoservice 6 i32 1007 i32 <INSTRUMENT_SEND_NAVI_STATUS_SET> i32 3
```

- `INSTRUMENT_NAVI_TYPE_SET = 0x4C10A018` 是本仓库早已记录并实测过的常量（见 `docs/BYD_NAVIGATION.md`）。
- `INSTRUMENT_SEND_NAVI_STATUS_SET` 的**数值未知**（自带高德 dex 里是 stub，值为 0）。
  三条取得途径，任选：
  1. 车机上 `adb shell "grep -r bydauto /system/framework/*.jar"` 类思路取出平台 jar，反编译读常量；
  2. 复用 `app_process` 模板，用 `Class.forName` + `getField(name)` **按名字运行时取**（推荐，零硬编码）；
  3. 从 `AmapService` 的运行日志里抓：它每次都会 `Log.d("AmapService","setNaviStatus: "+value)`，
     日志里看得到调用但看不到 feature id —— 价值有限。
- 风险：自带高德在每次导航状态变化时都会重新写 `START_*`，一次性写 STOP 可能被它覆盖回。
  若实测如此，需要**周期性重写**（例如镜像期间每 2 s 一次）。

### ④ 禁用（本轮采用，粒度可配）

```sh
# 整包禁用 —— 最彻底，但会连同「小屏/全屏导航」模式一起失效（用户实测）
adb shell pm disable-user --user 0 com.byd.automap
adb shell pm enable      --user 0 com.byd.automap

# 只禁仪表投屏组件 —— 压掉投屏，保留高德其余功能与导航模式（默认）
adb shell pm disable-user --user 0 com.byd.automap/com.byd.automap.extra.MeterActivity
adb shell pm enable      --user 0 com.byd.automap/com.byd.automap.extra.MeterActivity
```

- **为什么不用 ①（force-stop）**：实测杀完会**反复自行重启**，压不住。
- **为什么不用 ③（写 HAL）**：见 §0 第 3 条，HAL 管不到另一个 App 的 Activity window。
- **为什么默认选「只禁组件」**：整包禁用会让车机侧的导航模式（小屏/全屏导航）失效，而
  DiPlay 的镜像正是靠这个模式才画得出来 → 整包禁用等于把自己的前提拆了。组件级禁用只拿掉
  那块投屏 Activity，留在进程里的服务仍可维持导航模式。**待实测确认**（§8 第 21 项）。
- 影响面（整包档）：禁用期间 Launcher 里的高德入口、账号绑定、语音「我要导航」等 BYD 集成
  一起失效。同期用户用 CarPlay 导航，通常无所谓。组件档的影响面小得多。
- 落地方式与安全设计见 §7；已实现为 DiPlay 的一项可配置项（`BydOemClusterHold`）。

---

## 7. 实现（已落地，2026-10-04）

### 7.1 为什么是「禁用」而不是「杀进程」

两条硬约束先钉死：

- **车机是 Android 10 / API 29**（`ro.build.version.sdk=29`）→ `pm suspend`（API 30+）与
  `am set-inactive`（API 31+）**都不存在**，没有真正的「SIGSTOP 式冻结」可用。
- **杀进程没用**：`am force-stop com.byd.automap` 之后会**反复自行重启**（用户实车观察），
  所以在层叠模型下它还是会回来抢仪表。

→ 结论：**投屏期间用 `pm disable-user` 禁用，投屏结束 `pm enable` 放开**。

```
pm disable-user --user 0 com.byd.automap     # 投屏开始
pm enable      --user 0 com.byd.automap      # 投屏结束
```

`com.byd.automap` 装在 `/system/app/BydAutomap/`，是**普通系统 App**（非 priv-app、
无 `android:persistent`、无 `sharedUserId`）→ 不在保护名单里，`disable-user` 可用。

### 7.2 落地的代码

`shared/.../hud/BydOemClusterNavi.kt`（新）：

```
object BydOemClusterNavi {
    fun applicable(context): Boolean   // 车机是否装了 com.byd.automap
    fun hold(context)                  // 镜像开始：按策略 pm disable-user（组件或整包）
    fun release(context)               // 镜像结束：两个都 pm enable（幂等）
    fun restoreIfNeeded(context)       // App 启动时兜底，见 7.3
}
```

`release()` **不读当前设置**：无论当初禁用的是组件还是整包，它都把两者 `pm enable` 一遍
（enable 幂等）。这样用户在投屏中途改设置，也不会留下「禁了一半没人放开」的残局。

接入点（`common/.../ClusterMirror.kt`）：

| 位置 | 动作 |
|---|---|
| `launch()` adb 成功之后 | `BydOemClusterNavi.hold(context)` |
| `stop()` | `BydOemClusterNavi.release(appContext)` |
| `detach()`（窗口自己消失） | `BydOemClusterNavi.release(appContext)` |

`ClusterMirror` 在 `launch()` 里存了 `applicationContext`，所以 `stop()` 不需要改签名。

开关：`BydOutputSettings.oemClusterHold`，返回枚举 `BydOemClusterHold`（`OFF` / `COMPONENT` /
`PACKAGE`，默认 **COMPONENT**），设置界面在「仪表投屏」区、只要 `BydOemClusterNavi.applicable()`
（装了原车高德）就出现，用 `choice()` 呈现。文案 `oem_cluster_map` /
`oem_cluster_map_description` / `oem_cluster_hold_{off,component,package}`。

实现走 `BydAdbShell`，与 `BydClusterMapPause` 同款：后台执行、不弹授权、失败静默、
`Log.i(TAG, ...)` 日志（TAG `DiPlay-BYD-OemCluster`）。全部命令排在**同一个单线程**上，
保证「先禁用、后启用」的顺序不会被并发打乱。

### 7.3 三条安全设计（重要）

1. **状态持久化**：`diplay_oem_cluster/stock_map_held` 记录「已禁用」。只有 `pm enable`
   真正执行成功（adb 返回非 null）才清除；adb 不通时保留标记，下次再试。
2. **启动兜底**：`BydNavigationOutputs.onAppOpened()` 调 `restoreIfNeeded()`。
   若 App 在投屏中被杀、或停车直接断电，原车高德还处于禁用状态，下次开 App 立即放开。
   注意**只在 onAppOpened 里做**——不能放进 `start()`，否则手机一连上就会在投屏中途解冻。
3. **不碰 `INSTRUMENT_NAVI_TYPE`**：那是方向盘选的全局显示模式，设成 Off 会**连 DiPlay
   自己的镜像一起关掉**（`BydClusterNaviMode.showsMap`）。

### 7.4 已知取舍

- **整包档**禁用期间，Launcher 里的高德入口、账号、语音「我要导航」等 BYD 集成一起失效——这
  正是用户要的（同期用 CarPlay 导航）。**组件档**只拿掉仪表投屏，其余保留。想全留活口就选
  「不处理」。
- `pm disable-user` 若被车机拒绝（ROM 保护名单），日志里会记 `refused=true`，行为退化为
  「什么都不做」，不会把车搞坏。
- **保层（holdTop）这一路暂未实现**：既然把原车高德压住了，层序反转的前提就不存在。
  留下它作为「选不处理时的备选」，实现要点：`am start --display N -n <pkg>/<activity>`
  对 singleTask/top 的 Activity 会把它重新置顶；用 `dumpsys window displays` 看该 display
  的顶层 window 归属。

### 7.5 为什么「整包禁用」会让「小屏导航」失效，以及组件档的由来（2026-10-04 追加）

**现象**：车主把仪表切到「小屏导航」，DiPlay 侧读回的导航模式也对，但 iPhone 始终不发送地图；
后来发现是**原车高德被整包禁用**导致的。

**为什么自相矛盾**：DiPlay 的镜像走的是 `ClusterMirror`，而是否投屏由
`BydClusterNaviMode.showsMap`（仅 Small / Full 为真）决定。**小屏/全屏导航这个模式本身就是
被禁掉的那半个系统提供的**——把 `com.byd.automap` 整个禁掉，等于先把 DiPlay 赖以投屏的模式
拆了，再谈压制它就没了意义。

**为什么组件档可能同时满足两边**：原车高德往仪表投影**只用一个 Activity**——
`com.byd.automap.extra.MeterActivity`（`AndroidManifest` 里唯一 `taskAffinity=com.byd.automap.extra`、
`excludeFromRecents=true` 的那个）。把它单独置为 disabled，`setLaunchDisplayId` +
`startActivity` 会被 AMS 挡住 → 投屏窗口不再出现；而包本身仍是 enabled，`PushService` 等
服务继续运行 → 导航模式得以维持。

**代价 / 不确定性**：这是**推断**，未实车验证（§8 第 21 项）。若组件档既压不住投屏（高德
换别的 Activity 投），或压住了却仍让导航模式失效，就退回整包档。三档都保留，正因为这个
不确定性只有实车能定。

---

## 8. 待实测清单

1. `am force-stop com.byd.automap` 后，仪表屏上的原车高德导航卡片是否立刻消失？
2. 消失后，继续在高德里导航，它是否会自己回来（多久）？
3. `byd.intent.action.KILL_BydAutoMap` 单独发送，对仪表有无可观察变化？
4. `INSTRUMENT_SEND_NAVI_STATUS_SET=3` 是否能单独关掉、且不影响 DiPlay 自己的镜像？
5. 上述任一手段生效期间，DiPlay 的 `ClusterMirror` 是否仍正常显示（即两者互不干扰）？
6. 断电重启后 `pm disable-user` 的冻结是否仍生效？

**层序相关（本次新增，最重要）**：

7. **归属**：`dumpsys display` 里那块 cluster display 的 `name` / `uniqueId` 是什么？
   DiPlay 的 `fission_bg_xdjaVirtualSurface` 与原车高德的 `叠加视图 #1` 是不是**同一个 displayId**？
8. **层序**：`dumpsys window displays` 里该 display 的 window 列表中，DiPlay 与原车高德谁在上面？
9. **反转**：DiPlay 镜像期间，让原车高德 `startMeterAct`（或用导航触发），DiPlay 是否被盖住？
10. **抬层**：重发 `am start --display N` 能否把 DiPlay 重新抬回最上？耗时多久、会不会闪？
11. HAL ③ 单独写 STOP 时，DiPlay 自己的镜像是否受影响（预期：不该）？

**广播路线（§2.4.1 的验证项）**：

12. `am broadcast -a android.intent.action.ACTION_SHUTDOWN` 能否被 shell 接受？
    预期：`SecurityException: Permission Denial: not allowed to send broadcast`。
    若能通过且仪表真的退出，需评估副作用（它会惊动其它系统组件，不建议常态化使用）。
13. `am start -n com.byd.automap/com.byd.automap.extra.MeterActivity --ei meterType 1`
    是否**不会**关掉窗口（预期：只会把它顶到前台，反而更糟）——用来反证"广播路线不通"。

**值守循环（§6① 的验证项）**：

14. `am force-stop com.byd.automap` 之后多久会被系统拉起（`pidof` 轮询计时）？
    若 < 5 s，说明有主动保活，需要 `pm disable-user`。
15. 「两边同时投屏 → 仪表异常重启」的复现时间：单开 DiPlay 是否也会重启（排除 DiPlay 自身问题）？
    压制原车高德后是否彻底不复现？

探针脚本：
- `scripts/oem_cluster_navi_off.sh` —— 关停手段（逐条执行并打印前后状态）；
- `scripts/cluster_layer_probe.sh` —— display 归属与 window 层序（新增）。

**本轮实现（`pm disable-user` 方案，见 §7）的验证项**：

16. `pm disable-user --user 0 com.byd.automap` 在本车是否被接受（预期：输出
    `new state: disabled-user`；若 `refused=true` 说明 ROM 有保护名单，需换方案）。
17. 禁用后仪表上的原车高德卡片是否**立刻消失且不再回来**（这是选中它而非 `force-stop` 的理由）。
18. 投屏结束（DiPlay 点停止 / 切走）后，原车高德是否**自动恢复**、仪表与 Launcher 是否正常？
19. **兜底**：投屏中直接杀掉 DiPlay（`am force-stop com.shihab.diplay`）→ 再打开 DiPlay，
    原车高德是否被 `restoreIfNeeded()` 放开？（模拟「投屏中崩溃 / 停车断电」）
20. 「两边同时投屏 → 仪表异常重启」在禁用方案下是否彻底不复现？（对照第 15 条）

**可配置禁用粒度（§7.5，本次新增）的验证项**：

21. **组件档是否既压住投屏、又保住导航模式**（本轮最关键）：
    - `pm disable-user --user 0 com.byd.automap/com.byd.automap.extra.MeterActivity` →
      仪表上原车高德窗口是否消失？方向盘菜单里「小屏导航 / 全屏导航」是否仍可选、仍生效？
    - DiPlay 镜像是否正常？
22. 组件档被拒时输出什么（预期 `Component ... new state: disabled-user`；若 `refused=true`
    说明该 ROM 不允许禁系统 App 的组件，需退回整包档）。
23. 投屏中途把设置从「组件」改成「不处理」或「整包」，`release()` 是否把两个状态都放开
    （`pm list packages -d` 与 `dumpsys package com.byd.automap | grep -i enabled` 各看一遍）？

---

## 附录 A：`INSTRUMENT_*` 中与导航相关的特征名

（完整表在 `INSTRUMENT_*` 共 400+ 项，这里只列导航相关）

```
INSTRUMENT_NAVI_TYPE                    INSTRUMENT_NAVI_TYPE_SET
INSTRUMENT_NAVI_POSITINO_DISPLAY        INSTRUMENT_SEND_NAVI_STATUS_SET
INSTRUMENT_SEND_DESTINATION_STATUS_SET  INSTRUMENT_NAVI_FUNCTION_USAGE_STATUS_SET
INSTRUMENT_NAVI_CAM_REMAINING_MILEAGE_SET
INSTRUMENT_NAVI_DESTINATION_CHARGING_STATION_SET
INSTRUMENT_NAVI_ESTIMATED_MILEAGE_SET   INSTRUMENT_NAVI_ESTIMATED_TIME_SET
INSTRUMENT_NAVI_GO_COMPANY              INSTRUMENT_NAVI_GO_HOME
INSTRUMENT_NAVI_LEAD_MSG_ADVANCED_SET   INSTRUMENT_NAVI_SAFETY_REMAINING_MILEAGE_SET
INSTRUMENT_NAVI_TRIP_INFO_HOUR_SET      INSTRUMENT_NAVI_TRIP_INFO_MILEAGE_SET
INSTRUMENT_NAVI_TRIP_INFO_MINUTE_SET    INSTRUMENT_NAVI_TRIP_REMAINING_SECOND_SET
INSTRUMENT_GUIDE_INFO_ADVANCED_ACTION_SET
INSTRUMENT_GUIDE_INFO_AND_ROAD_AHEAD_DISTANCE_SET
INSTRUMENT_GUIDE_INFO_CAMERA_SET        INSTRUMENT_GUIDE_INFO_SAFETY_SET
INSTRUMENT_GUIDE_INFO_SIMPLE_SET        INSTRUMENT_GET_NAVI_DESTINATION
INSTRUMENT_GET_ROAD_NAME_CHECK_STATE    INSTRUMENT_SCREEN_TYPE
INSTRUMENT_PEM_FUNCTION_MAPPING_NAVI_STATUS_SET
```

## 附录 B：分析产物位置

| 产物 | 路径 |
|---|---|
| 自带高德反汇编 | `E:/DiLink4_firmware/work/automap/dex{,2,3,4}.txt` |
| 自带高德关键类切片 | `E:/DiLink4_firmware/work/automap/cls/`（`MeterActivity.txt`、`PushService.txt`、`MeterExtKt.txt`、`enums.txt`…） |
| 自带高德 manifest | `E:/DiLink4_firmware/work/automap/manifest.txt` |
| AmapService 反汇编 | `E:/DiLink4_firmware/work/amapsvc/dex.txt`、`cls_AmapService.txt`、`cls_AmapBroadReceiver_full.txt`、`manifest.txt` |
| 容器服务 | `E:/DiLink4_firmware/work/xdja/` |
| 固件解包 | `E:/DiLink4_firmware/`（`system.img` / `vendor.img` / `product.img`、`*_tree.txt`） |
| 层叠模型 - 原车找屏逻辑 | `E:/DiLink4_firmware/work/automap/dex2.txt` 第 481724 行起（`BydAutoExtKt.meterDisplay`） |
| 层叠模型 - DiPlay 找屏常量 | `common/.../DiLink51ClusterLayout.kt`（`BASE` / `DILINK4`）、`common/.../ClusterMapPresentation.kt` 顶部注释 |
| 层序探针 | `scripts/cluster_layer_probe.sh` |
| 退出仪表的进程内广播证据 | `work/automap/dex2.txt` `MeterExtKt.quitMeterAct`@3611bc、`BydMapService.exitProcess`@2e1aee、`MeterActivity.onCreate`@3608a8（LocalBroadcastManager 注册） |
| `ACTION_SHUTDOWN` 全局接收器 | `work/automap/dex2.txt:485049`（BydMapService）、`work/automap/dex3.txt:1c1b78`（PushService） |
| `ACTION_SHUTDOWN` 受保护广播证据 | `E:/DiLink4_firmware/work/framework-res.apk` → `fr_manifest.txt` 第 69 条 `<protected-broadcast>` |
