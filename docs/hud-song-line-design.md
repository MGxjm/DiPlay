# HUD 曲目行 / 歌词（2026-10-03）

分支：`carplay-lyrics-to-hud`（基于 `cluster-mirror-dilink4.0-china-with-adb` 上已实车验证的 HUD 成果）。

## 数据源（用户实车观察，关键前提）

用户在车上观察到的行为：

- CarPlay 用**第三方音乐 App** 时，**仪表盘的歌曲信息区会滚动播放歌词**；
- 用 **Apple Music** 时，同一位置只显示稳定的曲名/歌手。

这个现象把数据源锁死了：**第三方 App 把歌词逐行塞进 iAP2 `NowPlayingUpdate` (0x5001) 的曲名字段（TITLE=1）滚动重发**；
Apple Music 不发歌词，所以那一行停在曲名。用户描述的"其实就是 CarPlay 首页音乐区显示的内容"与之一致。

由此得到一个重要推论：**DiPlay 已经在接收这条数据了** —— `ClusterSongState.accept(frame)`
本来就在解析每一帧 `NowPlayingUpdate`，只是此前只把它投向仪表盘音乐卡片（`BydClusterSong`）。
所以本功能不需要任何新协议、不需要新增 iAP2 订阅，只需把已经拿到的行转发到 HUD。

## HUD 侧的可投递性

`BydStandalonePackets` 里有一个独立的文本 feature：`streetName()` 用 `0x43FA1008 + (i << 12)`
把任意文本编码成 UTF-16LE（HAL 上限 96 字节 / 48 字符）。
导航的距离与转向走的是另一批 feature（`0x43F01018` / `0x43F01010` / `0x43F01030`）。

**文本与 maneuver 是不同的 feature**，因此只发文本记录、不带任何 maneuver 记录，
理论上可以只更新那一行而不动箭头和距离 —— 这也是本次实现所选的路。
（是否真的如此，由实车决定：见下"待验证"。）

## 设计：与导航共存

HUD 只有一个文本行，`tick()` 每 500ms 一次，两者必须分主次：

| 状态 | HUD 行为 |
|---|---|
| 歌词开关开 + 无导航 | 发**纯文本包**（曲目行/歌词），HUD 不再每秒被 `clear()` 擦空 |
| 歌词开关开 + 有导航 | 正常发 guidance，但 `road` 传 null —— 把文本行让给歌词，箭头/距离照常 |
| 歌词开关关 + 无导航 | 走原路径 `clear()`（行为不变） |
| 歌词暂停/停止 | `ClusterSong.playing == false` → 不显示，退回 `clear()` |

导航期间让出文本行而不是抢占，是因为路名本就是导航的次要信息，
而押韵/歌词行在第三方 App 下变化频繁，两者交替会让那一行闪烁。

## 改动清单（均在底线 Dynam standalone 路，不影响 SOME/IP 与 amapservice）

| 文件 | 改动 |
|---|---|
| `BydStandalonePackets.kt` | 新增 `text(value)` —— `streetName` 的语义别名，注释说明它可被音乐行复用 |
| `BydStandaloneSession.kt` | 抽出私有 `publish(packet)`（原 `update()` 的发送/去重/保活/失败恢复逻辑，**行为不变**）；新增 `showText(text)` |
| `BydStandaloneHudOutput.kt` | 暴露 `showText(text)` |
| `BydClusterSong.kt` | 新增 `current()` 暴露当前行给其他输出用 |
| `BydStandaloneNavigationBridge.kt` | `tick()` 支持曲目行；上述共存策略 |
| `BydOutputSettings.kt` | 新增 `KEY_HUD_SONG` = `hud_song` 及读写 |
| `DiPlayActivity.kt` | 「BYD navigation」区新增开关（在 navigation 开关之后，**不需要 ADB**，故不放在 ADB 区） |
| `strings.xml`（英文/中文） | `song_on_hud` / `song_on_hud_description` |

`tick()` 每 500ms 读一次设置，开关即时生效，无需重连、无需通知 bridge。

## 待实车验证（唯一不确定项）

**只发文本记录、不带 maneuver 记录，clusterdebug receiver 会不会渲染？**
这是本方案的唯一假设。三种结果：

1. **正常显示** → 成功，且导航与歌词可共存。
2. **无反应** → 说明 receiver 要求包里带有效的 guidance 冗余，退路是构造一个
   「固定 icon（如直行）+ 该文本作 road」的完整包（会显示箭头，但如果用户接受也可用）。
3. **显示但残留** → 暂停/切歌时清不掉，需要在 `ClusterSong.playing == false` 且
   非新值时主动补一次 clear。

另外需留意 96 字节上限：中文歌词行约 24 字，长句会被 `streetName()` 自行截断。

## 已规避的坑

- **不依赖 ADB**：走的是已打通的 clusterdebug 广播，与 `cluster_song`（仪表盘卡片，需要网络 ADB）无关。
- **不新增 iAP2 订阅**：数据源已在收，`ClusterSongState` 甚至"在设置关闭时也持续跟随"，所以
  开启开关的瞬间就有内容，不需要等下一首歌。
- **不改 `available()` 判定**：保持上一轮「包存在就尝试」的结论。
