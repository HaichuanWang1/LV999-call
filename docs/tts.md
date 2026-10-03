# TTS 播放链路（边收边播）

> 本文件是 [主自述文件](../README.md) 的技术分册，内容偏实现细节与踩坑记录。

## TTS 播放链路（边收边播）

```
MiMo SSE 分块(base64) → decodeTtsSseToPcm 逐块解码(+剥头) → AudioPipe → AudioPlayer.read → AudioTrack.write
```

- **请求 `format=pcm16`，不是 `wav`**。这是**必须**的，理由见下节。
- **真流式**：老实现把整段 SSE 音频攒进 `ByteArrayOutputStream` 才返回 InputStream，
  「开口前的静默期」就等于整段合成时长（句子越长越明显）。现在第一块音频到达即可出声。
- **不占主线程**：整条链路跑在 `viewModelScope`（主线程）上，而老实现的解析是同步阻塞的，
  于是**整段合成期间主线程被堵满** —— 现象是「开口前 UI 卡一下」，非常像死锁，但不是：
  没有任何锁循环等待，是同步 I/O 直接压在主线程上。
  现在请求/响应头在 IO 线程，解码在 `ChatRepository.ioScope`，播放器在自己的 IO 作用域。
- **背压**：`AudioPipe` 只有 64KB，写满即阻塞，播放多快就解码多快，不会把整段音频攒内存里。
- **可打断**：挂断/退出时 `AudioPlayer.stopCurrentPlayback()` 会 `close()` 当前流，
  同时唤醒阻塞在 `read` 的播放线程和阻塞在 `write` 的解码线程
  （`Job.cancel()` 叫不醒阻塞中的 `read`，只有 close 才行）。
- **播放收尾**：`AudioPlayer.awaitPlaybackEnd()` 直接 join 播放任务，不再轮询 `isPlaying` ——
  服务端一块音频都没下发时 `isPlaying` 会在一帧内 true→false，轮询会整个错过、白等一个超时。
- **朗读超时**：设置页「🔊 TTS 语音合成」里的**朗读超时**（`tts_playback_timeout_sec`，默认 180s、
  可调 30~600s）是**整段朗读**的预算：从发起 TTS 请求开始计时，请求阶段花掉的部分会从预算里扣掉，
  剩下的给播放阶段。超时就 `stopCurrentPlayback()` 并回到聆听 —— 兜住「服务端不下发音频」与
  「放一半断流」两种卡死。它同时也是长回复的天花板，所以做成可调而不是写死。

### 流式必须用 pcm16（否则整段语音都是「哒哒」声）

实测 MiMo 的行为：`stream=true` + `format=wav` 时，**每个 SSE 分块都是一个独立的完整
WAV 文件**，各自带 44 字节 RIFF 头：

```
format=wav   stream=true
  [0] RIFF total=7724  dataOff=44 pcm=7680
  [1] RIFF total=15404 dataOff=44 pcm=15360   ← 每块都从头开始
  [2] RIFF total=15404 dataOff=44 pcm=15360
  ...  chunks=11  headerBytes=484

format=pcm16 stream=true
  chunks=11  headerBytes=0  pcmBytes=168960   ← 纯 PCM，零头
```

而播放端只认得**开头那一个**头（`AudioPlayer` 的 RIFF 检测），后续每块的 44 字节头
都会被当成 PCM 采样写进 AudioTrack —— 每块边界爆出 22 个垃圾采样。
一段 3.5 秒的话分 11 块，就炸 11 下，听感是**持续不断的「哒哒」声（约每秒 3 下）**。

所以做了两件事：

1. 请求改为 `format="pcm16"`（24kHz / PCM16LE / 单声道，与 `AudioPlayer` 默认参数一致）。
   官方文档同样要求：流式调用请指定 `pcm16` 以便拼接成完整音频。
2. 解码端加 `stripWavHeader()` 兜底：**逐个子块扫描 `data` 块**（不写死 44 字节偏移，
   因为带 `LIST`/`fact` 等附加块的文件更长），就地剥头。
   服务端若因版本/兼容原因回落成 wav，也只会播纯 PCM，不会把噪声播出去。

> 排查提示：若再听到周期性杂音，先看 `TTS解析: 块=N` —— 块数远大于 1 说明确实在流式，
> 此时杂音基本就是分块边界问题。

### 为什么不用 `java.io.PipedInputStream`

它的写端在缓冲区**没写满**时不会唤醒阻塞中的读端：`receive()` 里只有
「缓冲区已满」(`awaitSpace`) 和写端关闭 (`receivedLast`) 两处 `notifyAll()`，
正常写入路径一句都没有，读端只能靠 `wait(1000)` 超时才发现有新数据。

Android 源码（`$SDK/sources/android-36.1/java/io/PipedInputStream.java`）和 JDK 21 都是这样；
实测「写一小段、缓冲区远没满」时读端要 **~800ms** 才被唤醒 —— 边收边播会被切成
~1 秒一顿的节奏，比原来的「等整段合成」还糟。所以用
`ReentrantLock + Condition` 自己实现了 [`AudioPipe.kt`](app/src/main/java/com/lv999call/app/audio/AudioPipe.kt)。

### 风格提示词：基础风格 + 本轮情绪

TTS 请求里放两条消息：`user` 是**风格指令**、`assistant` 是**要念的正文**
（见 `ChatRepository.synthesizeSpeech`）。这是 MiMo 官方支持的两条风格控制路径之一；
另一条是把 `[笑]` / `（叹气）` 这类音频标签直接写进正文，本项目没用。

> ⚠️ 风格指令**为空时整条 `user` 消息都不发**，而不是发一条 `content: ""`。
> 空串 content 是"看着无害"的陷阱：它仍然是请求体里的一个 message，严格校验的接口会
> 直接 400，而现象只是"这个角色突然不出声了"。两个内置角色的默认语气现在都是空的
> （银狼从来就没有，DeepSeek 酱改为默认不预置），所以这条路径是**常态**，不是边界。

风格指令由两段拼成，**分工不能混**（混了模型会把它们当成同一层级的两句话随便挑一句）：

| 段 | 来源 | 管什么 |
|---|---|---|
| 基础风格 | 角色 / 预设的 `ttsPrompt`（准备页可改，**默认可能是空串**） | 音色与一贯的说话方式 |
| 本轮情绪 | `EmotionVoiceStyles`，按 `[[e:…]]` / `[[m:…]]` 的 key 查表 | 只这一句的语气与节奏 |

- **基础风格只有两个来源**：`characterTtsPrompts[角色 id]`（准备页里为该角色单独设的那一格，
  取不到时回落到角色自带的 `defaultTtsPrompt`）与自定义方案自带的 `PresetEntity.ttsPrompt`。
  这里曾经还有一份**全局**兜底 `ApiConfig.ttsPrompt`，是一条实打实的跨角色污染通道 ——
  银狼的默认语气是空的，会一路回落到全局那一格，用别人的语气说话，而且从准备页完全看不出来。
  字段已整条撤掉。
- **默认留空是常态**：银狼没有预置语气；DeepSeek 酱锁定的预置音色「冰糖」本身就是它的声音，
  再叠一段"清亮软糯、语速稍快"的指令等于在音色之上又压一层表演。留空 = 让模型按音色自然读。
- **「声音跟着情绪走」默认关**（`emotionVoiceEnabled`）：情绪段是叠在基础风格之上的第二段指令，
  基础风格为空时它很容易被当成"整段风格"来演，听感上像这一句换了个人。它是加分项，不是默认行为；
  设置页里作为 Live2D 的子开关给想要的人打开。

为什么**能**按轮改：**TTS 不是跟着 LLM 流式走的** —— 它等整段回复生成完才发起合成。
所以这一轮的表情标签在开嗓之前就已经拿到了，可以拿它去改这一轮的语气。
「表情变了而语气不变」（笑着说出很凶的话）就是因为缺了这一步。

- 没有表情标签时（提示词里要求"情绪不明显就宁可少加"）情绪段为空，拼出来就是原样的
  `ttsPrompt` —— 与加这个功能之前**完全一致**。
- **关掉 Live2D 就听不到情绪**：标签协议只在 `live2dEnabled` 打开时注入，LLM 不输出标签，
  这张表也就无从生效，语气会一直停在基础风格上。这是刻意的取舍
  （理由见 `EmotionVoiceStyles` 的类注释），Live2D 默认开着，只有主动关掉才会遇到。
- 键名写错**不会报错**，只会"怎么没效果"。所以本轮出现了表情却查不到语气时会打一条
  警告日志，真机跑一轮就能看出表里漏了哪个键。

### 排查播放链路问题看这些日志

```bash
adb logcat -s ChatRepo:D AudioPlayer:D ProcessAudioUseCase:D
```

| 日志 | 含义 |
|------|------|
| `TTS解析: 行=N, 块=N, 字节=N` | SSE 解码统计（**块数=1 说明服务端根本没在流式下发**，边收边播会退化成整段等待） |
| `音频流就绪: wav=.. headerRead=.. sr=.. ch=.. 首块等待=Xms` | 从 `playStream` 到首个音频块到达 —— **X 就是「开口前」的等待时间** |
| `开始出声: 自playStream=Xms` | 第一帧真正写进 AudioTrack 的时刻 |
| `TTS流式解码完成: 字节=N, 耗时=Xms` | 整段解码耗时（现在不该再等于静默期） |
| `TTS 朗读超时（上限 Xs，已等 Xms）` | 朗读超时兜底（设置页可调，默认 180s）：音频一直没放完，已强制停止 |
| `TTS 朗读超时（合成阶段就等满 Xs）` | 连音频都没合成出来就耗光了整份预算，本轮不朗读 |
| `语气: 表情=X → …` | 本轮按情绪改了语气（后面跟的是查表得到的语气描述） |
| `本轮表情「X」在 EmotionVoiceStyles 里没有配语气…` | 表里漏了这个键，已静默退回基础风格 |

> 参考音频（`assets/silverwolf/ref_voice.wav`，22.05kHz / 单声道 / 16bit / 15 秒，661KB，
> base64 后约 861KB，远低于 MiMo 的 10MB base64 上限）每轮都要
> 随请求上传，这是「开口前等待」里除服务端合成之外的另一块固定成本。
>
> 流萤那份（`assets/firefly/ref_voice.wav`）按同一规格剪：14.92 秒 / 643KB /
> base64 约 857KB。**这个"约 15 秒"不是随便定的** —— 它是每句话都要多传的那几百 KB
> 与音色还原度之间的平衡点，改长之前先想清楚代价。
>
> 参考音频由 [`tools/make_ref_voice.py`](../tools/make_ref_voice.py) 从游戏语音里剪：
>
> ```bash
> python tools/make_ref_voice.py --list        # 看候选（转写 + 时长 + 自动切出的句子）
> python tools/make_ref_voice.py               # 按默认选段生成
> ```
>
> 剪的时候有两条经验值得记住：
> - **挑日常闲聊，别挑剧情独白**。参考音频决定的是音色，但**语气会一起被带过去** ——
>   拿"兵器""残骸""熄灭"这种台词剪出来的音色会偏沉。
> - **按停顿取连续区间，别按句切开再垫统一静音**。后者拼出来是"一顿一顿"的节奏，
>   克隆出的韵律也跟着走样（试过：18 段拼出 17.4 秒，比原句还散）。
>
> 仓库里曾经还躺着一份 `assets/silverwolf_audio.wav`（旧版 `merged.wav`，1.3MB）——
> 那是角色化改造**之前**的硬编码音色，改造后音色统一走
> `TtsPolicy.CloneVoice.refAudioAsset`（即上面的 `silverwolf/ref_voice.wav`），
> 它再也没有任何代码引用，已删除。排查"样本音频没上传"时，先确认读的是哪一个文件：
> `AppModule.cloneRefAudio()` 只认策略里声明的那个路径，读不到会**静默**返回空串，
> 然后 `ChatRepository` 打一条「角色锁定了克隆音色但参考音频为空，跳过TTS」。
