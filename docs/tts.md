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

> 参考音频（`assets/silverwolf/ref_voice.wav`，约 640KB，base64 后 ~880KB）每轮都要
> 随请求上传，这是「开口前等待」里除服务端合成之外的另一块固定成本。
