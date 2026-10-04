# 仪表盘自定义 HUD（方案 A）设计与实现

## 结论先行：不做协议，自己画

比亚迪的 HUD / 仪表盘导航**不经过 BYDAuto 事件总线**（活体取证三轮均证实：投 HUD 时
`device_type` 里没有任何 instrument/hud 设备）。原车高德的做法是渲染：

```
com/byd/automap/extra/MeterActivity
  └ ActivityOptions.setLaunchDisplayId(集群屏id)
    → 直接画到 fission_bg_xdjaVirtualSurface (1920x720)
```

也就是说 **HUD 内容是画出来的，不是发数据送出去的**。那么 DiPlay 只要能在同一块屏上开窗口，
就能自己画 HUD —— 不需要任何 HUD 协议、不需要新车机权限。

DiPlay 已经具备这个能力：`ClusterMirror` 通过本机 adbd 的 `am start --display` 把
`ClusterMirrorActivity` 放到那块屏上（已实测投屏正常）。

## 实现内容

### 1. `common/.../ClusterHudView.kt`（新增）

自定义 `View`，按自身尺寸自适应绘制一张导航卡片：

- 左侧大转向箭头（复用现有 `ic_maneuver_*` 矢量图 + `NavigationWidgetUpdater.arrow()` 的
  Apple maneuver → 图标映射）
- 右侧三行：距离（大字、加粗白）/ 路名（灰）/ ETA·剩余（蓝）
- 超出宽度用 `TextUtils.ellipsize` 截断
- 无路线时显示 "CarPlay / No route"，未连接时 "DiPlay / CarPlay is not connected"
- 背景纯黑不透明 → 它一显示就完整盖住下面的地图流，不破坏 Surface

数据源是 `shared` 里已有的公开 `CarPlayGlance`（`CarPlayController` 每帧都喂它，
与 BYD 输出开关无关），`Snapshot` 带 maneuverType / distanceMeters / road / ETA / 歌曲。

设置持久化放在同文件 companion：`ClusterHudView.enabled(context)` / `setEnabled(...)`，
SharedPreferences `diplay_cluster_hud`。

### 2. `common/.../ClusterMirrorActivity.kt`（修改）

- 在 SurfaceView 与等待文案之上再叠一层 `ClusterHudView`（默认 GONE）
- 新增 `setHudMode(enabled)`：切换可见性，并启动/停止 1 秒轮询
  `CarPlayGlance.snapshot()` 刷新（导航状态本身带 30s 过期，需要持续刷新）
- `onCreate` 读设置自动进入 HUD 模式，保证 CarPlay 会话中途重建 Activity 也能恢复
- `onDestroy` 移除回调，不留泄漏

### 3. `DiPlayActivity`（修改）

在「BYD navigation」分区里新增开关 **Navigation HUD on the dashboard**，
仅当 adb 集群镜像目标可用时显示（HUD 卡片依赖 `ClusterMirrorActivity` 这个窗口，
`ClusterMapPresentation` 那条路暂未接入）。开启即时生效，不用重连 CarPlay。

### 4. 字符串

`cluster_hud_title` / `cluster_hud_description`，已加英文与 `values-zh-rCN`。

## 为什么不做 B / C 了

- **B（确认 MeterActivity 挂在 Display #1）**：只影响"我们猜得对不对"，不改代码结论。
- **C（验证免 ADB）**：只影响"要不要保留 adb 这条路"，不影响 HUD 能不能画。
两者都可以在后续顺手验证，但都不阻塞功能。

## 已验证 / 待验证

- [x] `:mobile:assembleDebug` 编译通过（`JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`，
      仅剩历史遗留的 deprecation warning）
- [ ] 车机实测：开 CarPlay 导航 → 进 DiPlay → BYD navigation → 打开
      「Navigation HUD on the dashboard」→ 仪表盘是否显示箭头+距离+路名
- [ ] 仪表盘裁剪：DiLink 4.0 的 1920x720 会被车机自己裁（全屏/小屏导航），
      需确认卡片在两种模式下的可见位置；若被裁掉，下一步把卡片布局按已测的
      `DiLink51ClusterLayout.plan()` 思路做分区偏移。
