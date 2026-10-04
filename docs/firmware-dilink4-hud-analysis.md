# DiLink 4.0 固件分析：HUD / 仪表盘通道取证

对 `Di4.0_1for2_21.1.2.2506090.1_0.zip`（3.57 GB）离线静态分析的结果。
回答两个问题：**DiPlay 的 HUD 在 DiLink 4.0 上为什么失败**，以及**有没有免 ADB 的投屏路径**。

---

## 1. 固件身份

| 项 | 值 |
|---|---|
| 设备 | BYD-AUTO DiLink4.0（车型代号 **di1for2**） |
| 指纹 | `BYD-AUTO/DiLink4.0/DiLink4.0:10/QKQ1.210218.001/eng.build.20250609.213409` |
| Android | **10（API 29）**，高通平台（sm7250 系） |
| OTA 结构 | 外层升级容器 → `update.zip` → `payload.bin`（A/B） |
| 还原镜像 | system 4711 MB / vendor 884 MB / product 249 MB，均 **ext4** |

### 提取要点（容易踩的坑）

- `payload.bin` 的 system 分区有 2176 个操作标着 `type=8`，标准 AOSP 协议里这是
  `SOURCE_BSDIFF`（差分，需源分区）。但实测：这些操作**没有任何 src_extents**，
  数据块 magic 是 `fd 37 7a 58 5a`（**XZ**），解压后正好等于目标区大小。
  → 厂商自定义操作码，`type=8` = **XZ 压缩替换**，这是**完整包**，可离线还原。
- 两层 zip 都是 STORED，可按绝对偏移随机读取，无需解压 3.3 GB。
- `InstallOperation.dst_extents`（field 6）**每条 body 直接就是一个 Extent**
  （`f1=start_block, f2=num_blocks`），没有额外嵌套层 —— 多剥一层会静默得到空 extents。

工具已固化在 `scripts/firmware-tools/`：`ota_extract.py`（还原镜像）、
`ext4read.py`（只读 ext4）、`ext4walk.py`（枚举全树）、`axmlperm.py`（读权限保护级别）。

---

## 2. 核心结论：HUD 走 BYDAuto HAL，不是 SOME/IP

### 2.1 DiPlay 写死的目标在 4.0 上不存在

全树（8931 条路径，system+vendor）搜索 `someip` / `ts.car` / `tscar`：**零命中**。

DiPlay 的 `BydHudBridge` bind 的是 `com.ts.car.someip.service.manager.SomeIpServerService`
（海外 DiLink 5.0 的 SOME/IP 网关）。这个服务在 DiLink 4.0 固件里根本不存在
→ bind 失败 → 静默无反应。这是 HUD 失败的**直接原因**。

### 2.2 4.0 的真实通道：BYDAuto 车载 HAL

原车高德（`BydAutomap`，165 MB）与 `AmapService` 用的是另一套：

```
android.hardware.BYDAutoManager          // 管理器入口
android.hardware.IBYDAutoDevice          // 设备接口（Binder）
android.hardware.IBYDAutoEvent / IBYDAutoListener
android.hardware.bydauto.BYDAutoEvent / BYDAutoEventValue / BYDAutoFeatureIds
android.hardware.bydauto.instrument.BYDAutoInstrumentDevice   // 仪表盘
android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice
android.hardware.bydauto.sensor.BYDAutoSensorDevice
android.hardware.bydauto.setting.BYDAutoSettingDevice
com.byd.auto.proxy.BydAutoProxy          // 高德的封装层
```

native 实现：`/system/lib64/libbydauto.so`、`/system/lib/libbydautoservice.so`

`AmapService` 里的关键调用与日志串：
- `sendNaviToCluster` / `sendAttitudeSlopeToCluster`
- `onStartCommand: mClusterType = `
- **`send_to di1for2 cluster`**（正是本车型代号）

### 2.3 HUD 的具体 event 常量（高德 dex 中提取）

```
SET_HUD_MODE_SET                  SET_HUD_MODE_CHOICE_SET
SET_HUD_SWITCH_SET                SET_HUD_SWITCH_STATUS_FEEDBACK
SETTING_HUD_REQUEST_COMMAND_SET   SETTING_HUD_IMAGE_TEXT_INFO_FUSION_SWITCH_SET
SET_FSE_HUD_CONFIG_SET            SET_FSE_HUD_MODE_CHOICE_SET
SET_FSE_HUD_MODE_FEEDBACK_SET     SET_HUD_CONFIG / SET_HUD_MODE_CHOICE
DisplayTypeHud                    CAR_HEAD_UP_2D / CAR_HEAD_UP_3D
VEHICLE_SETTING_HUD_MODE          VEHICLE_SETTING_HUD_VISIBLE
```

即 HUD 是 **BYDAuto HAL 上的一组 feature/event id**，通过 `IBYDAutoDevice` 下发，
与 SOME/IP 无关。支持 AR-HUD（常量带 `ARHUD` 后缀）。

---

## 3. 权限矩阵：第三方 App 能走多远

权限分两级定义，保护级别截然不同：

| 定义位置 | 权限形态 | protectionLevel | 第三方 App |
|---|---|---|---|
| `framework-res.apk`（20 个） | `android.permission.BYDAUTO_*_COMMON` | **1 = dangerous** | ✅ 可声明 + 运行时申请 |
| `AutoPermission.apk`（98 个） | `BYDAUTO_*_GET` / `BYDAUTO_*_SET` | **2 = signature** | ❌ 仅系统签名 |

关键两条：

- `BYDAUTO_INSTRUMENT_COMMON` → **dangerous**，第三方拿得到
- `BYDAUTO_INSTRUMENT_SET` / `_GET` → **signature**，第三方拿不到

**推论**：DiPlay 作为普通第三方 App，**无法**直接用 BYDAuto HAL 写 HUD
（`_SET` 是 signature）。要打通必须二选一：
1. 把 DiPlay 装成系统 App（`/system/priv-app` + 平台签名）；
2. 借助已有的系统 App 中转 —— 见下。

---

## 4. 已有的系统 App 中转点

固件里存在两个关键系统 App：

| App | 包名 | 位置 | 说明 |
|---|---|---|---|
| ClusterDebug | `com.byd.clusterdebug` | `/system/priv-app` | 持有 `BYDAUTO_INSTRUMENT_COMMON`、`BYDAUTO_TEST_SET`、`SYSTEM_ALERT_WINDOW`；含 `BroadcastReceiverCAN`、`ClusterDebugService.broadcastToCAN`、依赖 `com.byd.cluster.spi` |
| XdjaContainerService | **`com.xdja.containerservice`** | `/system/priv-app` | `android.uid.system`、`persistent`；`AutoDisplayService`；持有 `CAPTURE_VIDEO_OUTPUT`、`WRITE_SECURE_SETTINGS` |

注意：容器服务包名是 **`com.xdja.containerservice`**，不是 DiPlay 代码里写的
`com.byd.containerservice`（先进数通 XDJA）。该 APK 的 manifest 里有
`original-package` 字段，说明经历过改名。

DiPlay 的 `BydStandaloneHudOutput` 正是向 `com.byd.clusterdebug` 发广播的兜底路径，
**方向是对的**，但它被死锁在固件指纹 `eng.build20260722.221155` 上，
而本车是 `eng.build.20250609.213409` → 被自己的指纹检查挡掉。

---

## 5. 集群屏：为什么必须走 ADB

`/system/etc/init/fission_cluster.fission_host.rc` 显示集群是 **Qt 原生渲染**：

```
service byd_demo_dual  /system/bin/qtandroidnative panel_5_15 /system/lib64/libBydCluster.so
service byd_demo_adas  /system/bin/qtandroidnative panel_5_15 /system/lib64/libAdas.so
service byd_demo_split /system/bin/qtandroidnative panel      /system/lib64/libBydCluster.so
    user root
    seclabel u:r:fission_qtandroidnative:s0
```

配合 `fissiond` / `fission_service` / `fission_eventservice` / `fission_mq` /
`fission_cbox_*` / `fission_disp_mgr` 这套 fission 容器框架。

集群投影区由 fission 容器持有，以 root + 独立 SELinux 域运行，
普通 App 的 UID 既枚举不到该 display，也无法在其上加窗口
→ **DiPlay 现有 `ClusterMirror` 走 loopback adbd `am start --display` 是正确且必要的**，
不存在"换个 API 就能免 ADB"的捷径。

---

## 6. 对 DiPlay 的修复建议

| 目标 | 做法 | 门槛 |
|---|---|---|
| HUD 可用 | 把 `BydHudBridge` 的 SOME/IP 实现换成 BYDAuto HAL（`BYDAutoManager` → `IBYDAutoDevice`，用 `SET_HUD_*` 系列 event id） | 需 `_SET` 权限 → 必须系统签名 / priv-app |
| HUD 可用（轻） | 放开 `BydStandaloneHudOutput` 的固件指纹硬编码，改为探测 `com.byd.clusterdebug` 是否存在 + 按 4.0 的广播协议发 | 中，需先确认广播 action/extra |
| 仪表盘投屏 | 维持 `ClusterMirror` 的 adbd 路径 | 无（已可用） |

下一步最有价值的动作：连车机 ADB，在原车高德投 HUD 时抓 `logcat`/`dumpsys`，
拿到 BYDAuto HAL 的实际 event id 数值与 `ClusterDebug` 的广播 action，
即可把上述常量从"字符串证据"变成"可写入代码的调用参数"。

---

## 附：产物位置

- 镜像：`E:\DiLink4_firmware\{system,vendor,product}.img`
- 全树清单：`E:\DiLink4_firmware\{system,vendor}_tree.txt`
- 关键 APK：`E:\DiLink4_firmware\apks\`（ClusterDebug / XdjaContainerService / AmapService / BydAutomap）
