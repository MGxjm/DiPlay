# 仪表投屏路径放开与 DiLink 4 设置完善

## Context

当前代码用 `DiLink51ClusterLayout.supported()`（精确匹配 `Build.FINGERPRINT`）来门禁 DiLink 5 路径，并用 `!supported()` 在 `AdbClusterRouter.enabled()` 中禁止 DiLink 5.1 固件走 ADB 路由。这导致：只有一种已测固件能用 DiLink 5 路径，其它 DiLink 5 固件被错误地推进 DiLink 4 ADB 路径；而 DiLink 4/3 固件若凑巧匹配上 5.1 的指纹也会被锁死 ADB 路径。

dev 分支已经做了一部分对齐工作：把 `DiLink4ClusterDisplay.matches()` 放宽成「任何 BYD/XDJA 投影面 + 宽屏面板」、加了 `AdbClusterRouter.DisplayTarget` 手动选择、`cluster_screen_status` 广播开关、`AdbClusterRouter.scan()` 读屏。本计划在此基础上完成用户要求的四件事：

1. DiLink 5 路径对所有 DiLink 5 固件放开（不再用指纹门禁）。
2. DiLink 4/3 统一走 DiLink 4 ADB 路径（不限版本）。
3. DiLink 4 仪表投屏设置中：原车高德改三挡（不禁用 / DiPlay 运行期间禁用 / 长期禁用），加推荐与说明文字；可用屏幕选择首配向导 + 屏幕分类标注。
4. 放开 HUD 歌词显示的固件/包名/签名校验限制。

## 关键设计决策（用户未回答澄清问题，按最合理假设执行）

- **DiLink 5 识别**：按「投影 display 名出现」识别，不依赖指纹。检测到 `fission_bg_XDJAScreenProjection` 或 `shared_fission_bg_XDJAScreenProjection_0/1` 这类显示名 → 视为 DiLink 5，走 DiLink 5 路径。`DiLink51ClusterLayout.supported()` 仍保留，仅用于已测机型的「布局计划 + 主题/对比度」UI 门禁。
- **长期禁用语义**：禁用整个原车高德包，**不写恢复日志**，DiPlay 停止后保持禁用，需要用户切回「不禁用」才会恢复。`PACKAGE` 现有自动恢复语义迁移到「DiPlay 运行期间禁用」之上（沿用 `COMPONENT` 行为更安全）——见下方「原车高德三挡」重构。
- **打开自带高德投屏**：纯引导文案。在首配向导里给步骤说明，让用户在车机仪表菜单里手动开启高德投屏，再用「扫描可用屏幕」按钮触发 `AdbClusterRouter.scan()`。不写 adb 自动拉起 MeterActivity（跨固件不可靠、易误导用户）。
- **三方小窗识别**：尺寸 + owner 综合判断。不匹配 BYD/XDJA 投影名 且 面积小于 960×320 且 owner 非 `com.xdja.containerservice`/`com.byd.*` → 标「疑似三方桌面小窗」。

## 1. DiLink 5 路径放开（不限版本）

**`common/src/main/java/com/shilapi/xcertplay/DiLink51ClusterLayout.kt`**
- 新增 `val NAME_MARKERS = listOf("XDJAScreenProjection")` 与 `fun isDiLink5ProjectionName(name: String): Boolean`：name 含 `XDJAScreenProjection` 即认为是 DiLink 5 投影面（含 `shared_` 前缀的派生层也算，因为它们是 DiLink 5 路径的目标）。
- 新增 `fun diLink5Route(context: Context): Boolean`：遍历 `DisplayManager.getDisplays(DISPLAY_CATEGORY_PRESENTATION)`，任一显示名命中 `isDiLink5ProjectionName` 即返回 true。
- `supported(fingerprint)` 保持原样，仅作为「已测 1920×720 面板的布局计划 + 主题/对比度」门禁。

**`common/src/main/java/com/shilapi/xcertplay/AdbClusterRouter.kt`**
- `enabled(context)`：把 `!DiLink51ClusterLayout.supported()` 改成 `!DiLink51ClusterLayout.diLink5Route(context)`。
  ```kotlin
  fun enabled(context: Context): Boolean = AirPlayPersistence.loadAdbClusterEnabled(context) &&
      !DiLink51ClusterLayout.diLink5Route(context) && ClusterMapPresentation.findDisplay(context) == null
  ```

**`common/src/main/java/com/shilapi/xcertplay/ClusterMapPresentation.kt`**
- `findDisplay()`：把「非 supported 固件 → 回退到 DiLink 4 公共显示」这条路径删掉。DiLink 4/3 一律走 ADB 路由，公共 DiLink 4 显示不再抢占。
  - 现状：`displayName()` 在非 supported 固件下会返回 BASE 名（DiLink 5 路径仍生效）。
  - 改动：当 `displayName()` 返回 null（没有 DiLink 5 投影面）时，直接返回 null（让 ADB 路由接管）；不再回退到 `DiLink4ClusterDisplay.matches()`。
  - 即：DiLink 5 公共显示 → DiLink 5 路径；无 DiLink 5 公共显示 → ADB 路径（DiLink 4/3）。

## 2. DiLink 4/3 统一 ADB 路径（不限版本）

dev 分支已把 `DiLink4ClusterDisplay.matches()` 放宽为「任何 BYD/XDJA 投影名 + 宽屏面板」（`isProjectionName` + `isPanelSize`），覆盖 DiLink 3/4/5 投影面族。本节只需验证上述 §1 的 `enabled()` 改动让 DiLink 4/3 固件能进入 ADB 路径即可，无需额外改动 `DiLink4ClusterDisplay`。

## 3. DiLink 4 仪表投屏设置

### 3a. 原车高德三挡

**`shared/src/main/java/com/shilapi/xcertplay/hud/BydOemClusterHold.kt`**（重命名语义，保留枚举名以兼容已存偏好）
- `OFF` = 不禁用
- `COMPONENT` = DiPlay 运行期间禁用（禁用 MeterActivity 组件，停止后自动恢复 — 沿用现有 `OemClusterHoldSession` journal + restore 机制）
- `PACKAGE` = 长期禁用（禁用整个原车高德包，**不写 journal，不自动恢复**）

**`shared/src/main/java/com/shilapi/xcertplay/hud/OemClusterHoldSession.kt`**
- `acquire(mode=PACKAGE, ...)`：直接 `setState(PACKAGE, DISABLED_USER)`，**不调用 `saveJournal(...)`**。失败时也不写恢复日志（长期禁用语义）。
- `acquire(mode=OFF, ...)`：当前实现是 `return release()`。改为先 `release()`（清理 journal，恢复 COMPONENT 模式留下的禁用），再显式 `setState(PACKAGE, ENABLED_DEFAULT)` 与 `setState(COMPONENT, ENABLED_DEFAULT)` 兜底恢复长期禁用过的包。
  - 新增 `restoreStockMap(): Boolean` 方法：把两个 target 都设回 `ENABLED_DEFAULT`（state 1 = enable），并 `saveJournal(null)`。`acquire(mode=OFF)` 内部调用它。
- `release()`：保持现状（journal 不存在时直接返回 true）。PACKAGE 模式无 journal → 不会触发恢复 → 长期禁用保持。✓

**`shared/src/main/java/com/shilapi/xcertplay/hud/BydOemClusterNavi.kt`**
- `applicable(context)` 保持（仍检测 `com.byd.automap` 是否安装）。
- 新增 `fun restoreStockMap(context: Context)`：在 worker 线程上调 `state(app).restoreStockMap()`，供设置页「恢复原车高德」按钮或切换到 OFF 时调用。
- `holdForLaunch()` 内 mode=PACKAGE 分支不需改 —— `acquire(PACKAGE)` 已不写 journal。

**`common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt`**（约第 655–664 行的 `BydOemClusterHold` choice）
- 三挡选项标签更新：
  - `oem_cluster_hold_off` → 「不禁用」
  - `oem_cluster_hold_component` → 「DiPlay 运行期间禁用」
  - `oem_cluster_hold_package` → 「长期禁用（推荐）」
- 在 choice 下方加 `label(R.string.oem_cluster_map_recommendation, ...)` 显示「推荐长期禁用」。
- 加 `label(R.string.oem_cluster_map_restart_notice, ...)` 显示说明文字：「开启 DiLink 4 投屏后，在车机仪表菜单中设置想要的模式（全屏/小屏），配置好其他设置后，关闭车机再次启动即可，每次切换需要重启车机。」
- choice 回调里：若新选的是 OFF 且旧值是 PACKAGE，调 `BydOemClusterNavi.restoreStockMap(this)`。

**字符串**（`common/src/main/res/values/strings.xml` 与 `values-zh-rCN/strings.xml`）
- 更新 `oem_cluster_hold_off`/`oem_cluster_hold_component`/`oem_cluster_hold_package` 文案。
- 新增 `oem_cluster_map_recommendation`（「推荐：长期禁用，避免原车高德反复抢占仪表投影面」）。
- 新增 `oem_cluster_map_restart_notice`（上述重启说明）。

### 3b. 可用屏幕选择 + 首配向导 + 分类标注

**`common/src/main/java/com/shilapi/xcertplay/AdbClusterRouter.kt`**
- `candidates()`：去掉 `takeIf { it > 0 }` 过滤，**包含 display 0**（主屏）。同时解析 owner 字段，扩到 `DisplayCandidate` 上。
- `DisplayCandidate` 增字段：`owner: String`。
- 新增 `enum class DisplayClass { MAIN, DASHBOARD, WIDGET, OTHER }` 与 `fun classify(c: DisplayCandidate): DisplayClass`：
  - `MAIN`：id == 0
  - `DASHBOARD`：`DiLink4ClusterDisplay.matches(name, w, h)`
  - `WIDGET`：非 MAIN、非 DASHBOARD，且 `w * h < 960 * 320`，且 owner 不属于 `{com.xdja.containerservice, com.byd.*}`
  - 其它：`OTHER`
- `DisplayTarget` 增加 `classify(owner: String): DisplayClass`？—— 否，`DisplayTarget` 用于持久化（不含 owner），分类在 `DisplayCandidate` 上做。`scan()` 返回 `List<DisplayCandidate>`（含 owner + class），UI 直接用。
  - 把 `scan()` 返回类型从 `List<DisplayTarget>?` 改成 `List<DisplayCandidate>?`，并在 `AirPlayPersistence.saveClusterDisplayCandidates` 里继续按 `DisplayTarget` 编码（丢 owner，重启后从 dump 重新解析）。
  - UI 渲染时：若 `DisplayCandidate` 列表里 owner 缺失（来自持久化的旧记录），就只显示 name+尺寸，不标分类。

**`common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt`**
- `loadClusterDisplayCandidates` / `saveClusterDisplayCandidates` 保持按 `DisplayTarget` 编码（不含 owner/class）。UI 把扫描到的 `DisplayCandidate` 投影成 `DisplayTarget` 持久化；渲染时优先用最近一次扫描的 `DisplayCandidate` 列表（含 owner），缓存里只是兜底。

**`common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt`**（`clusterDisplayPicker`，约第 1569 行）
- 渲染选项时按 `DisplayCandidate.classify` 标注：
  - MAIN → 「%s · %dx%d · 当前主屏幕（不可选）」，choice 中该项 `enabled = false`（不让选）。
  - DASHBOARD → 「%s · %dx%d · 疑似仪表（推荐）」
  - WIDGET → 「%s · %dx%d · 疑似三方桌面小窗（不推荐）」
  - OTHER → 「%s · %dx%d」（可选，无标注）
- 「自动」选项保持。
- 选中时保存 `DisplayTarget(name, w, h)` 到 `KEY_CLUSTER_DISPLAY_OVERRIDE`。MAIN 不能选。
- 首配向导（`override == null && remembered.isEmpty()`）显示一个步骤说明块：
  1. 「授权 ADB」按钮 → `authorizeClusterRouting()`（已存在，约第 652 行）
  2. `label(R.string.cluster_display_setup_step2)` → 「在车机仪表菜单中打开原车高德的投屏功能（小屏或全屏导航）」
  3. 「扫描可用屏幕」按钮 → `rescanClusterDisplays()`（已存在）
- 现有 `rescanClusterDisplays()` 改成把 `AdbClusterRouter.scan()` 返回的 `List<DisplayCandidate>` 缓存到内存（供 UI 渲染分类），同时把投影后的 `List<DisplayTarget>` 持久化。

**字符串**
- 新增 `cluster_display_setup_step2`（步骤 2 引导文案）。
- 新增 `cluster_display_main_label`（「当前主屏幕（不可选）」）。
- 新增 `cluster_display_dashboard_label`（「疑似仪表（推荐）」）。
- 新增 `cluster_display_widget_label`（「疑似三方桌面小窗（不推荐）」）。
- 更新 `cluster_display_option` 模板或新增带分类的模板 `cluster_display_option_classed`（`%1$s · %2$d×%3$d · %4$s`）。

## 4. 放开 HUD 歌词显示限制

**`shared/src/main/java/com/shilapi/xcertplay/hud/BydStandaloneHudOutput.kt`**（`available()`，第 64–81 行）
- 去掉 `Build.FINGERPRINT != "..."` 精确匹配。
- 去掉 `context.packageName !in setOf(...)` 包名白名单。
- 去掉 `info.longVersionCode == 10601004L` 精确版本匹配。
- 去掉签名 SHA-256 精确比对。
- 保留：`SDK_INT >= 28`、receiver 包存在、`FLAG_SYSTEM`、`enabled`、`exported`、`permission.isNullOrEmpty()`、`signers.size == 1`。
- 即：只要车机存在系统签名、导出、无权限保护的 BYD HUD 接收器，就放开 HUD 歌词。
- `diagnostics()` 里把去掉的检查项仍写进报告（便于现场诊断），但不再作为门禁。

## 测试与验证

### 单元测试（需更新/新增）

**`common/src/test/java/com/shilapi/xcertplay/AdbClusterRouterTest.kt`**
- `candidatesListEveryBaseDisplayWithItsGeometry`：断言改成 `assertEquals(2, candidates.size)`（display 0 现在被包含），并断言 `candidates.first { it.id == 0 }.owner == "com.android.systemui"`。
- 新增 `classifyDisplayCandidatesIntoMainDashboardWidgetOther`：构造 4 类记录，断言 `classify` 返回正确分类。
- 新增 `diLink5RouteDetection`：mock DisplayManager 不便，改为直接测 `DiLink51ClusterLayout.isDiLink5ProjectionName`。

**`common/src/test/java/com/shilapi/xcertplay/DiLink4ClusterDisplayTest.kt`**（dev 已加测试，保持）

**`shared/src/test/java/com/shilapi/xcertplay/hud/BydOptionalOutputSettingsTest.kt`**
- 新增 `hudSongAvailableWithoutFingerprintGate`：用 shadow PackageManager 构造系统签名 receiver，断言 `available` 不再因指纹不匹配而失败。

**`shared/src/test/java/com/shilapi/xcertplay/hud/OemClusterHoldSessionTest.kt`**
- 新增 `packageModeDoesNotJournalAndDoesNotRestore`：mode=PACKAGE 时 `saveJournal` 不被调用，`release` 后包仍禁用。
- 新增 `offModeRestoresLongTermDisable`：mode=OFF 时 `restoreStockMap` 把两个 target 设回 ENABLED_DEFAULT。

### 构建与人工验证
- `./gradlew :common:compileDebugKotlin :shared:compileDebugKotlin :common:testDebugUnitTest :shared:testDebugUnitTest` 确认编译 + 单测通过。
- 真机：DiLink 5 车机 → 走 DiLink 5 路径（公共 display）；DiLink 4/3 车机 → 走 ADB 路径；首配向导引导授权 ADB → 扫屏 → 分类列表；切「长期禁用」后停 DiPlay，原车高德保持禁用；切回「不禁用」恢复；HUD 歌词在更多固件上可用。

## 涉及文件清单

- `common/src/main/java/com/shilapi/xcertplay/DiLink51ClusterLayout.kt`（新增 `isDiLink5ProjectionName` / `diLink5Route`）
- `common/src/main/java/com/shilapi/xcertplay/AdbClusterRouter.kt`（`enabled` 门禁换；`candidates` 含 display 0 + owner；新增 `DisplayClass`/`classify`）
- `common/src/main/java/com/shilapi/xcertplay/ClusterMapPresentation.kt`（`findDisplay` 删 DiLink 4 公共回退）
- `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt`（原车高德三挡 UI + 重启说明 + 屏幕分类标注 + 首配向导）
- `common/src/main/res/values/strings.xml` + `values-zh-rCN/strings.xml`（新文案 + 改文案）
- `shared/src/main/java/com/shilapi/xcertplay/hud/BydOemClusterHold.kt`（KDoc 语义更新）
- `shared/src/main/java/com/shilapi/xcertplay/hud/OemClusterHoldSession.kt`（PACKAGE 不 journal；新增 `restoreStockMap`）
- `shared/src/main/java/com/shilapi/xcertplay/hud/BydOemClusterNavi.kt`（暴露 `restoreStockMap`）
- `shared/src/main/java/com/shilapi/xcertplay/hud/BydStandaloneHudOutput.kt`（`available` 放开限制）
- `common/src/test/java/com/shilapi/xcertplay/AdbClusterRouterTest.kt`（更新 + 新增分类测试）
- `shared/src/test/java/com/shilapi/xcertplay/hud/OemClusterHoldSessionTest.kt`（新增 PACKAGE/OFF 行为测试）
- `shared/src/test/java/com/shilapi/xcertplay/hud/BydOptionalOutputSettingsTest.kt`（HUD 放开测试）
- `docs/DILINK4_CLUSTER.md`（更新说明：三挡语义、屏幕分类、HUD 放开）

## 不做的事
- 不写 adb 自动拉起原车高德 MeterActivity（跨固件不可靠，纯引导文案）。
- 不删除 `DiLink51ClusterLayout.supported()`（仍用于已测机型布局计划 UI）。
- 不改 `BydClusterScreenStatus`（导航模式广播，dev 已加）。
- 不动 `mobile/build.gradle.kts`、`BydClusterMapPause.kt` 等 dev 已改但不影响本需求的文件。
