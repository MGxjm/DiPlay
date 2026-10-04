# 为什么 CarPlay 音频会卡，以及为什么调大缓冲只能改善一半

> 2026-10-04。针对「音频频繁卡顿；调大缓冲貌似能减少，但时间长了还是会卡」。
> 本文的结论来自源码 + AirPlay 协议语义推导，**改动已编译通过但未实车验证**。
> 实车验证步骤见 §8。

---

## 0. 结论（先看这里）

卡顿不是单一原因，而是**四条独立的放大链**叠在一起。调大缓冲只能缓解其中两条，
所以「有效但治不好」是预期结果，不是没调够。

| # | 机制 | 调大缓冲有用吗 | 本次是否已修 |
|---|---|---|---|
| 1 | **上报给手机的播放位置跑在错误的时钟上**（用 `System.nanoTime()` 冒充音频时钟，且完全不扣接收端缓冲） | ❌ 完全无用，甚至更糟 | ✅ 已修 |
| 2 | 欠载后必须重新填满**整个**配置缓冲才恢复 → 一次抖动 = 一段和设置一样长的静音 | ⚠️ 有害：缓冲越大，静音越长 | ✅ 已修 |
| 3 | 音频渲染线程**没有优先级**，和主屏/仪表盘的 H.264/HEVC 解码抢 CPU | ⚠️ 有一点（垫得越深越抗饿死） | ✅ 已修 |
| 4 | 压缩包队列满时**丢最新的包**，播放位置持续落后于手机 | ⚠️ 有一点 | ✅ 已修 |

**机制 1 是「时间长了还是会卡」的主因**：它让手机按系统时钟而不是喇叭的晶振时钟来喂数据。
两者频率差是**固定偏差**（几十到几百 ppm），不是噪声，也不是网络问题。缓冲只是买时间——
偏差会一直累积，迟早把缓冲走干（欠载）或走满（丢包），无论缓冲设多大。
这就是「调大缓冲减少卡顿，但永远消不掉」的教科书症状。

---

## 1. 音频从网络到喇叭的完整链路

```
iPhone ──UDP/RTP(AAC-LC)──► AudioStream.runData()        airplay/AudioStream.kt:86
                                    │ 解密后交给监听者
                                    ▼
                        CarPlayMediaEngine.onRtp()       airplay/CarPlayMediaEngine.kt:160
                                    ▼
                        AndroidMediaSink.onAudioRtp()    media/AndroidMediaSink.kt:265
                                    ▼
                        AudioRenderer.submit()           media/AndroidMediaSink.kt:783
                                    │ 压缩包队列（最多 192 包 ≈ 4.4 s AAC）
                                    ▼
                        AudioRenderer.handle()           → MediaCodec 解码 → writePcm()
                                    ▼
                        AudioTrack(MODE_STREAM, 写阻塞) → 喇叭
        ▲                           │
        │  /feedback 回报「现在在放哪个 sample」
        └───────────────────────────┘
```

**关键：这是一条只有「位置回报」没有其他反馈的闭环。** 手机根据 `/feedback` 里我们上报的
`sampleTime` 判断我们播到哪了，据此调整发送节奏（快/慢/重传）。所以这个回报值的**时钟域**
决定了整条链路的稳定性。

---

## 2. 机制 1：播放位置跑在错误的时钟上（主因）

### 2.1 手机怎么问

发送端定期 `POST /feedback`：

- `airplay/AirPlaySession.kt:424` —— 收到 `/feedback` 就调 `media.onFeedback(this)`
- `airplay/CarPlayMediaEngine.kt:294` —— `onFeedback()` 用 `streams[].{timestamp, sampleTime}` 回答
- 语义：**「在 `timestamp` 这个时刻，我（车机）正在播放 `sampleTime` 这个采样」**

### 2.2 原来怎么算（错的）

修改前的 `onFeedback()`：

```kotlin
val elapsedSec = max(0.0, (System.nanoTime() - originNs) / 1e9 - playoutLatencyMs / 1000.0)
val sampleTime = (firstSample + round(elapsedSec * sampleRate)) and 0xffff_ffffL
```

两个问题，各自独立：

**(a) 用系统时钟代替音频时钟。**
`originNs` 是收到第一个包的时刻，之后 `sampleTime` 就按 `System.nanoTime()` 线性增长——
也就是按 **Android 系统时钟** 走。但喇叭是按 **车机音频 DAC 的晶振** 走的。两者频率不同
（典型 ±20~100 ppm），于是我们上报的「播到哪了」和真实的「播到哪了」会**持续分叉**。

手机看到的是：车机的播放位置相对它自己的发送节奏在**缓慢漂移**，于是它按错误的速率去补偿，
结果就是把车机缓冲往一个方向推——最后要么推干（欠载 → 静音），要么推满（队列溢出 → 丢包 → 爆音）。
漂移速率决定「多久卡一次」，缓冲深度决定「能撑多久」。**缓冲调大只是把卡顿推迟，改不了斜率。**

**(b) 完全不扣除接收端自己的缓冲。**
上式等于宣称「我（车机）在零延迟地播放刚收到的采样」。实际上音频在我们这里要排两次队：

| 缓冲 | 深度 | 位置 |
|---|---|---|
| 压缩包队列 | 最多 `MAX_QUEUED_PACKETS=192` 包 ≈ 4.4 s（48 kHz AAC-LC） | `AndroidMediaSink.kt:1368` 附近 |
| AudioTrack 硬件缓冲 | `MediaAudioBuffer.plan()` 算出的 `trackBufferBytes`，1000 ms 预设 ≈ 1.2 s | `media/MediaAudioBuffer.kt:29` |

两者合计可达数秒。上报时把这部分当成 0，等于对手机谎报「我比实际听得更靠前几秒」。
手机据此会**少喂 / 晚喂**，把缓冲压到它认为的零延迟——也就是把我们自己辛苦攒的缓冲又掏空。

> 顺带：`AirPlayInfoPlist.audioLatencies()`（`airplay/AirPlayInfoPlist.kt:98`）里
> `outputLatencyMicros` 全部写死 **0**。握手阶段告诉手机「我的输出零延迟」，和 (b) 是同一个谎，
> 只不过发生在更早的一步。见 §7 的实验建议。

### 2.3 改成了什么

新增 `AudioRenderer.playedSampleTime()`（`media/AndroidMediaSink.kt:812`），
直接用 **AudioTrack 的播放头**回报：

```kotlin
val playedFrames = track.playbackHeadPosition.toLong() and 0xffff_ffffL
return (origin.toLong() + playedFrames).toInt()
```

`playbackHeadPosition` 是「DAC 已经吐出去多少帧」，**它就是音频时钟本身**。手机拿到的
`sampleTime` 斜率从此与喇叭一致，漂移从「结构性」降为「无」，缓冲深度不再被推着走。

**1:1 映射校验**（`verifyRtpClock()`，`AndroidMediaSink.kt:827`）：上面这个换算是建立在
「解码输出 1 个 PCM 帧对应 RTP 时间戳 1 个采样」的假设上。我们协商的所有编码都满足
（AAC-LC 一个包 1024 采样 / LPCM / Opus 都是 1:1），但仍用**前 2 秒真实流量**核对一次
（容许 90~110%），**结果只锁存一次**，避免中途换时钟域把手机搞晕；不通过就退回原来的系统时钟算法。

**保留了 `- playoutLatencyMs`**：这是手机在 SETUP 里声明的、它自己给这条流加的延迟，
语义上和接收端缓冲无关，原样保留以免改变音画对齐基准。

---

## 3. 机制 2：一次抖动被放大成一段静音

`AudioRenderer.writePcm()` 在 `playbackStarted == false` 时会攒够 `startThresholdBytes` 才
`play()`。而 `maintainPlaybackBuffer()`（`AndroidMediaSink.kt:1308`）检测到硬件缓冲真的排干
（`AudioBufferProgress.shouldRebuffer()`：欠载 + 队列空 + `queuedBytes == 0`）后会
`pause()` 并把 `prebufferBytes` 清零。

于是**恢复条件又是「攒满整个 startThresholdBytes」**：

| 音乐缓冲设置 | 一次欠载要等的静音 |
|---|---|
| 300 ms | ≈ 0.3 s |
| 500 ms | ≈ 0.5 s |
| 1000 ms | ≈ 1.0 s |

网络抖一下 → 硬件缓冲排干 → 等 1 秒 → 恢复。**短抖动被放大成长静音**，
而且缓冲设得越大，这个惩罚越重。用户感受到的「卡顿」很大程度上是这段人为静音。

**改法**：区分「首次启动」和「欠载后重启」。新增 `Plan.resumeBytes`
（`media/MediaAudioBuffer.kt:22`，`RESUME_MILLIS = 120`），
`startThreshold()`（`AndroidMediaSink.kt:1300`）在 `rebufferCount > 0` 之后改用 120 ms 门槛。
首次仍然按用户选的深度垫够，欠载恢复只要 120 ms。

---

## 4. 机制 3：音频线程没有优先级

全仓库搜索 `setThreadPriority` —— **零处命中**。`AudioRenderer` 的工作线程
（`Thread(::run, "carplay-audio")`）跑在默认优先级（0），而同一进程里 H.264/HEVC 解码
（主屏 + 仪表盘镜像，见 `docs/dilink4-oem-cluster-navi.md`）在抢 CPU。

后果：DAC 把缓冲唱完之前，音频线程没被调度上来补写 → `track.underrunCount` 增长 → 触发 §3 的静音。
**负载越高越明显**，长时间行驶 + 仪表盘镜像叠加时尤其容易，也解释了为什么「车机热点开着、
主屏 30 fps 时更容易卡」（`docs/LOCAL_HOTSPOT_TEST.md`）。

**改法**：`AndroidMediaSink.kt:851`

```kotlin
runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }   // -19
```

`-19` 是 AudioTrack 自己回调线程的标准优先级，不需要任何权限。

---

## 5. 机制 4：队列满时丢错了包

原实现（`submit()`）：

```kotlin
if (!started || !queue.offer(AudioPacket(rtp, sample))) {
    ...
    Log.w(TAG, "audio queue full; dropping newest packets to bound latency")
}
```

队列满意味着**我们比手机慢**。此时丢掉刚到的（最新的）包，等于持续丢弃「现在这一刻」的声音，
而播放位置只会离实时越来越远，端到端延迟被永久锁在队列上限（≈4.4 s）。

**改法**（`AndroidMediaSink.kt:783`）：满时 `queue.poll()` 掉最老的、再放入最新的，保持「贴着直播沿」。
新增 `droppedOldestTotal` 计数，日志文案同步改为 `dropping the oldest packet to stay near live`。

---

## 6. 本次改动清单

| 文件 | 改动 |
|---|---|
| `shared/.../media/AndroidMediaSink.kt` | `playedSampleTime()` + `verifyRtpClock()`（音频时钟回报）；线程优先级 `URGENT_AUDIO`；`startThreshold()` 区分首启/欠载恢复；队列满改丢最老；统计行新增 `queueMs` / `trackQueuedMs` / `outputLatencyMs` / `playedSample` / `droppedOldestTotal` |
| `shared/.../media/MediaAudioBuffer.kt` | `Plan` 增加 `resumeBytes`（`RESUME_MILLIS=120`）；预设增加 **2000 ms** |
| `shared/.../airplay/CarPlayMediaEngine.kt` | `MediaSink.audioPlayedSample()` 新接口；`onFeedback()` 优先用音频时钟，取不到才退回原算法 |
| `shared/.../media/AndroidMediaSink.kt` (sink) | `audioPlayedSample()` 实现 |
| `common/.../DiPlayActivity.kt` + `values*/strings.xml` | 音乐缓冲新增「2000 毫秒 · 容忍最长断流」 |

编译验证：`JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat :mobile:assembleDebug --offline`
→ **BUILD SUCCESSFUL**（47 s）。

---

## 6.5 与旧版音频共存：设置里的「经典音频路径」

新版**还没有实车验证**，所以把旧版行为整体保留成一个开关，方便在车上做 A/B。开关打开 = 完全退回改动前。

- 存储：`AirPlayPersistence` 的 `legacy_audio_path`，默认 `false`（用新版）。
- UI：设置页「显示与性能」→ 音乐缓冲正下方，「经典音频路径」。**下次连接生效**（与音乐缓冲同一约定）。
- 传递：`CarPlayHostActivity.createMediaSink()` → `AndroidMediaSink(legacyAudioPath = …)` → `AudioRenderer`。

| # | 新版（默认） | 经典路径 |
|---|---|---|
| 1 | `/feedback` 回报 DAC 音频时钟（`playbackHeadPosition`） | `playedSampleTime()` 恒返回 null → engine 自动退回 `System.nanoTime()` 推算 |
| 2 | 队列满丢**最老**包，贴近实时 | 队列满丢**最新**包（保留已排队的积压） |
| 3 | 音频线程 `URGENT_AUDIO` | 保持默认优先级 |
| 4 | 欠载恢复只等 120 ms 就复播 | 恢复时重新灌满整个设定缓冲 |

机制 1 的切换**不需要改 engine**：`CarPlayMediaEngine.onFeedback()` 本来就保留了「拿不到音频时钟就退回系统时钟」的 fallback，开关只是让 sink 在这个分支上主动返回 null。音乐缓冲预设（含 2000 ms）两条路径都能选。

---

## 7. 待验证 / 下一步实验

### 7.1 判定到底是不是时钟漂移（最重要，零成本）

打开音乐播放，抓 `DiPlay-AudioStats` 日志（每 5 秒一行），看这三个字段：

```sh
adb logcat -s DiPlay-AudioStats
```

- **`outputLatencyMs`** = `queueMs + trackQueuedMs`，即手机到喇叭的真实端到端延迟。
- **判据 A（时钟漂移）**：`outputLatencyMs` 在连续十几个窗口里**单调地**往一个方向走
  （慢慢逼近 0 或者慢慢逼近队列上限），与 `maxGapMs`（网络最大到达间隔）无关 → 就是机制 1。
- **判据 B（网络断流）**：`outputLatencyMs` 大体稳定，但 `maxGapMs` 周期性飙到几百毫秒以上，
  同时 `sinceRxMs` 变大 → 是 Wi-Fi 丢包，得先解决链路（5 GHz、关掉车机 Wi-Fi 客户端并存连接）。
- **判据 C（CPU 饿死）**：`underruns=+` 增长但 `maxGapMs` 和 `outputLatencyMs` 都正常，
  同时 `maxWriteMs` 很大 → 是调度问题，线程优先级改动应能改善。

### 7.2 建议的下一步实验（本次**故意没有**实现）

`AirPlayInfoPlist.audioLatencies()` 把 `outputLatencyMicros` 写死 0，等于在握手阶段就告诉手机
「我不需要缓冲」。真正的杠杆是**让它和音乐缓冲设置一致**（例如 1000 ms 预设就声明 1000 ms），
手机才会按这个深度提前发送。

没直接改的原因：这是协议握手层，改错可能导致完全没声音，而目前没法上车验证。
上一步（§2.3 的音频时钟回报）已经让上报值变得**真实**，此时再改声明值才有意义——
两者必须一致，否则手机会追一个不存在的偏差。

若要走这一步，改 `AirPlayInfoPlist.kt:98` 的 `outputLatencyMicros`，
先用一个设置项做 A/B，观察 `outputLatencyMs` 是否稳定收敛到声明值附近。

---

## 8. 实车验证步骤

1. **装车测包**（必须带 MFi 凭据，否则连不上 iPhone）：
   ```sh
   JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" \
     DIPLAY_AUTH_ASSETS_DIR="C:/Users/RuiRR/.diplay-mfi" \
     ./gradlew.bat assembleStandaloneDebug --offline
   ```
2. 音乐缓冲先设 **500 ms**，连上 CarPlay，播放音乐，**连续放 30 分钟以上**。
3. 全程抓 `adb logcat -s DiPlay-AudioStats xcertplay-usb`。
4. 回填下表：

| 验证项 | 期望 | 实测 |
|---|---|---|
| `Audio: playout clock mapped to the DAC` 是否出现 | 出现（说明 1:1 映射成立） | |
| 是否出现 `playout clock rejected` | 不出现 | |
| `outputLatencyMs` 是否单向漂移 | 收敛、不单向走 | |
| `rebuffers=` 30 分钟内 | 接近 0 | |
| 静音时长（主观） | 从「≈缓冲设置」降到 ≈120 ms | |
| 音画同步 | 不应变差；若有变化记录下来（见下） | |
| 仪表盘镜像同时开着时的卡顿 | 应明显减轻（线程优先级） | |

5. **音画同步需要单独确认**：§2.3 把上报位置从「超前数秒」改成了真实值，
   手机的对齐基准随之改变。若发现口型/视频比声音超前，把 `CarPlayMediaEngine.onFeedback()`
   里音频时钟分支的 `latencySamples` 换成正值微调，或暂时注释掉该分支退回旧算法对比。

### 若实车确认无效

按 §7.1 的判据分流：
- 判据 B（网络）→ 修链路，别在 App 里继续加缓冲。
- 判据 A/C 仍成立 → 继续做 §7.2 的声明值实验，并考虑实现真正的
  「PCM 域漂移校正」（在解码后按 ±0.x% 的比例丢弃/填充帧，带淡入淡出拼接）。
