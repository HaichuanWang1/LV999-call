# lv999call · 银狼AI通话

基于 Jetpack Compose 的 Android 语音对话 Agent 应用，支持角色扮演式语音交互。

## 功能特性

### 两种对话模式
- **银狼** — 内置角色人设和银狼音色，沉浸式角色扮演语音对话，支持自定义参考音频
- **自定义** — 完全自定义：提示词 + 角色头像/背景 + 参考音频 + TTS风格提示词

### 全链路语音交互
```
用户说话 → VAD检测停顿 → ASR语音识别 → LLM流式生成 → TTS语音合成 → 实时播放
```

### MiMo-V2.5-TTS-VoiceClone 集成
- 上传参考音频（5~15秒），克隆任意音色
- 全局默认音色与自定义模式音色独立配置
- TTS 风格提示词（控制语气、情感、语速等）
- 支持语速调节（0.5x ~ 2.0x）
- **边收边播**：SSE 分块解码后直接喂给 AudioTrack，不等整段合成完（详见「TTS 播放链路」）
- > TTS 当前仅支持 MiMo 系列（目前免费），仍需自行申请 API Key

### ASR 双引擎
- **自定义 HTTP**：兼容 OpenAI Whisper 等任意 ASR API
- **Vosk 离线**：内置中文模型，无需网络即可识别

### Live2D 动态形象
- 通话界面可显示 Live2D 角色，替代静态头像
- **口型同步**：TTS 播放音量实时驱动嘴型张合（快张慢合 + 轻微抖动）
- **状态联动**：聆听/思考/说话/结束各有对应动作与表情
- **情绪表情** ⚠️ *存疑待验证*：LLM 在回复开头插入 `[[e:生气]]` 之类的标签，
  形象实时换脸，保持到本轮说完再回落（观感尚未在真机确认，详见下文）
- 空闲时轻微视线游移，角色观感更自然
- 设置页可开关；模型加载失败自动回退静态头像
- 离线可用：运行时与模型本地内置，不依赖网络 CDN
- **合规**：第三方模型与运行时不入库，需本地获取（见下）

### 核心能力
- 沉浸式通话界面，状态实时指示（聆听/思考/说话）
- 对话历史本地持久化（Room），支持"继续上次对话"
- 全配置可调：LLM / ASR / TTS 的 URL、Key、模型、语速等
- 支持本地局域网部署（明文 HTTP 流量已放行）

## 技术栈

| 模块 | 方案 |
|------|------|
| UI | Jetpack Compose + Material 3 暗色主题 + Dynamic Color |
| 架构 | MVVM (ViewModel + StateFlow) |
| 异步 | Kotlin Coroutines + Flow |
| 数据库 | Room |
| 配置存储 | DataStore Preferences |
| 网络 | Retrofit + OkHttp (SSE 流式) |
| 音频录制 | AudioRecord + 能量阈值 VAD |
| 音频播放 | AudioTrack (WAV/PCM 流式) |
| ASR | 自定义 HTTP / Vosk 离线 |
| TTS | MiMo-V2.5-TTS-VoiceClone (OpenAI 兼容) |
| 图片 | Coil |
| Live2D | WebView + PixiJS 6 + pixi-live2d-display (Cubism 4) |

## 项目结构

```
app/src/main/java/com/lv999call/app/
├── audio/                  # 音频引擎
│   ├── AudioRecorder.kt    #   录音 + VAD
│   ├── AudioPlayer.kt      #   流式播放 (WAV自动检测)
│   ├── AudioPipe.kt        #   边收边播用的有界字节管道
│   ├── VadDetector.kt      #   语音活动检测
│   ├── AsrEngine.kt        #   ASR引擎 (PCM→WAV转换)
│   └── VoskModelManager.kt #   Vosk离线模型管理
├── data/
│   ├── local/              #   Room数据库 + DAO + Entity
│   ├── remote/             #   API服务 (LLM/ASR/TTS/Models)
│   └── repository/         #   数据仓库
├── domain/
│   ├── model/              #   领域模型
│   └── usecase/            #   业务用例
├── di/                     #   手动依赖注入
├── navigation/             #   Compose Navigation
└── ui/                     #   界面层
    ├── home/               #     首页
    ├── prepare/            #     对话准备页
    ├── call/               #     通话页
    ├── live2d/             #     Live2D 形象容器
    │   ├── Live2DView.kt        #   Compose 容器 + 控制器
    │   └── Live2DAssetLoader.kt #   WebView 资源拦截加载
    ├── history/            #     历史记录页
    ├── custom/             #     自定义编辑页
    ├── settings/           #     设置页
    └── theme/              #     Material 3 主题

app/src/main/assets/live2d/  # Live2D 资源
├── index.html               #   承载页面            [入库]
├── js/bridge.js             #   状态机 + 口型同步    [入库]
├── LICENSES.md              #   第三方许可声明       [入库]
├── lib/                     #   运行时              [不入库，需本地获取]
└── models/                  #   模型                [不入库，需本地获取]

tools/
├── setup_live2d_assets.sh     # 一键获取 lib/ 与示例模型
├── live2d_postprocess.py      # 下载后处理（剥离 sourceMapping 等）
├── live2d_selftest.cjs        # 桥接层自测（30 项断言）
├── live2d_fallback_test.cjs   # 降级路径测试（9 项断言）
├── check_expression_names.cjs # 表情白名单 ↔ 模型文件一致性校验
├── audio_pipe_test.sh         # AudioPipe 自测（12 项断言，JVM 直跑真实 .class）
└── AudioPipeTest.java         #   ↑ 的测试主体
```

## 快速开始

### 环境要求
- Android Studio Hedgehog+
- JDK 17
- Android SDK 34

### 构建运行
```bash
git clone https://github.com/HaichuanWang1/LV999-call.git
cd LV999-call
```
用 Android Studio 打开项目，Sync Gradle 后运行。

### 启用 Live2D（可选）

Live2D 的运行时与模型因版权原因不入库，需先本地获取：

```bash
bash tools/setup_live2d_assets.sh
```

该脚本会下载 PixiJS / Cubism Core / pixi-live2d-display 到 `lib/`，
以及 Live2D 官方示例模型 Haru 到 `models/haru/`。

> 跳过此步也能正常构建运行，只是通话界面会回退到静态头像。
> 使用自备模型见下方「Live2D 形象 → 使用自备模型」。

### 首次配置
1. 打开 App → 点击右下角 ⚙️ **设置**
2. 填入 **LLM** 的 Base URL 和 API Key（兼容 OpenAI 格式）
3. 填入 **TTS** 的 URL 和 API Key（MiMo 或其他兼容服务）
4. 点击 🔄 按钮自动获取模型列表，选择模型
5. 上传一段参考音频作为默认音色
6. 保存设置，返回首页开始通话

> 如果使用本地局域网部署的模型（如 192.168.x.x），直接填入 HTTP 地址即可，已放行明文流量。

## Live2D 形象

### 工作原理

```
Compose (CallScreen)
  └── AndroidView → WebView（透明背景 + 硬件加速）
        └── assets/live2d/index.html
              └── PixiJS → pixi-live2d-display → Cubism 4 模型
                    ↑
        口型值 / 状态指令（evaluateJavascript）
                    ↓
        CallViewModel.audioLevel ← AudioPlayer.amplitude (RMS)
```

- 页面通过**虚拟域名 + `shouldInterceptRequest`** 供源，而非 `file://`。
  因为 WebView 下 `file://` 的 XHR 会被同源策略拦截，导致模型无法加载；
  该方案等价于 `WebViewAssetLoader`，但无需引入额外依赖。
- 口型注入点使用 pixi-live2d-display 的 `afterMotionUpdate` 事件。
  只有写在这里的值才能被随后的 `saveParameters()` 捕获并真正上屏；
  写在 `beforeModelUpdate` 会被 `loadParameters()` 覆盖 —— 读回值一路正常，画面却一动不动。

### LLM 情绪表情 ⚠️ 存疑：待真机复验

> **当前状态：机制已跑通，观感未确认，不要当成已完成功能。**
>
> 已证实（真机日志 + 截图）：
> - 标签能被解析并从文本里剥净（`AI回复` 里看不到 `[[e:…]]`）
> - 短标签能解析到模型真实表情名并成功下发（`[L2D] 情绪表情 → 06 0.0`）
> - `model.expression()` 确实改变了画面（生效 / 复位两帧的眼型不同）
>
> 未确认，以及已知踩过的两个坑：
> - 实际观感是「只有第一句朗读前闪一下，说话时没变化」。根因是**保持时长按
>   「触发后固定 7 秒」计时**，而标签在生成阶段就到了、TTS 还要合成参考音色，
>   计时器正好在角色开口时到期 —— 已改为「保持到本轮说完」。
> - 改完又发现 `snapshotFlow { callState }` 捕获的是协程启动那一刻的值
>   （`callState` 是普通参数，快照状态在 NavGraph 那层就读掉了），
>   导致复位永不触发、表情一直挂着 —— 已改用 `rememberUpdatedState`。
> - **以上两处修复都还没在真机上复验过**（设备在验证过程中掉线）。
>
> 复验要点：朗读**全程**都该看得到情绪表情，回到「聆听中」后自动复位。
> 若说话时仍然没变化，说明还有第三个原因，别急着合进 main。

形象的情绪不只跟着通话状态走，还可以由 LLM 自己决定：

```
系统提示词追加「表情标签」协议（仅 Live2D 开启时注入）
  ↓  LLM 回复：[[e:生气]]喂，你这也太离谱了吧。
流式解析 ExpressionTagParser
  ├─→ UI 展示 / TTS 合成：剥掉标签的干净文本（标签不会被念出来）
  └─→ 回调 Live2DExpression → ExpressionCue
        ↓
CallScreen 下发 Live2DController.setExpression()，保持到本轮说完再回落
```

几个必须这么做的原因：

- **标签会被 chunk 切断**（`"[[e:生"` + `"气]]"`）。未闭合的标签整段扣住不显示，
  否则玩家会看到半截标签一闪而过，或者被 TTS 念出来。
- **提示词里只暴露短标签**。模型真实表情名是中文带编号且空格不统一
  （`01黑脸` / `02 脸红爱心` / `03 生气` / `月卡`），让 LLM 原样复述极易写错一个字符。
- **情绪是「覆盖层」**。thinking → speaking 的状态切换会重走 `applyState()`，
  若情绪只 `model.expression()` 调一次就会被 `resetExpression()` 冲掉，
  表现为「LLM 明明调了表情但脸没变」；因此 JS 侧记录 `_cue` 并在每次状态切换后重新应用。
- **保持时长跟着这一轮走，而不是固定 N 秒**。标签在生成阶段就到了，
  而 TTS 要等整段回复生成完再合成参考音色；固定计时器会在角色刚开口时正好到期，
  现象是「表情闪一下就没了」。改为保持到状态回到聆听态，另加最短/最长兜底。
- **状态表情一律留空**。占位表情会和 LLM 的选择撞车（`thinking` 曾占位 `06 0.0`，
  而 LLM 打招呼最爱的也是 `0.0`），触发成功也看不出变化，被误判成功能失效。
- 模型里另外 5 条（吹泡泡 / 外套关闭 / 抱胸手 / 捧心手 / 要饭手）是手部与服装的
  状态切换而非瞬时情绪，混入情绪表达会互相覆盖，故不开放给 LLM。

可调项：`Live2DExpression.kt` 的枚举（标签 ↔ 真实表情名 ↔ 情绪说明）、
`CallScreen.kt` 的 `EXPRESSION_MIN_HOLD_MS` / `EXPRESSION_MAX_HOLD_MS`（保持时长兜底）。

### 资源不入库

`lib/` 与 `models/` 已被 `.gitignore` 排除，原因：

- 模型美术版权属于模型作者，并非本项目所有，公开分发存在法律风险
- Live2D Cubism Core 为专有组件，仅授予「作为应用一部分」的分发权

因此本仓库只包含自研代码（`index.html` / `bridge.js`），
第三方资源请用 `tools/setup_live2d_assets.sh` 在本地获取。

### 使用自备模型

1. 把模型放到 `app/src/main/assets/live2d/models/<your-model>/`
2. 指定模型路径，二选一：
   - 改 `js/bridge.js` 顶部的 `CFG.modelUrl`
   - 或从 Kotlin 传参：`Live2DView(modelPath = "models/<your-model>/xxx.model3.json")`
3. 若模型口型参数不是 `ParamMouthOpenY`，调整 `CFG.lipSyncParams`
4. 按实际观感调整 `CFG.states` 中各状态的表情名
5. 若换了模型的整套表情，记得同步 `Live2DExpression.kt` 的枚举，
   然后跑 `node tools/check_expression_names.cjs` 校验名字是否对得上

> 自备模型同样在 `.gitignore` 覆盖范围内，不会被误提交。

### 自测

WebView 内的 JS 无法用 Android 单元测试覆盖，可用附带的自测脚本验证
状态机与口型链路（mock PIXI/DOM 直接驱动 bridge.js）：

```bash
node tools/live2d_selftest.cjs         # 状态机 / 口型注入 / 情绪表情 / 布局 / 容错
node tools/live2d_fallback_test.cjs    # 资源缺失时的降级上报
node tools/check_expression_names.cjs  # 表情白名单与模型文件是否对得上
bash tools/audio_pipe_test.sh          # AudioPipe：唤醒/背压/打断/环形回绕
```

> `check_expression_names.cjs` 的价值在于：模型表情名少写一个空格 pixi 只会静默忽略，
> 现象是「表情没变」且没有任何报错，肉眼审查根本发现不了。

> `audio_pipe_test.sh` 直接拿 Gradle 编出来的 `.class` 在桌面 JVM 上跑 ——
> `AudioPipe` 是纯 JDK 实现（不碰 Android API），测的就是真正进 APK 的那份代码。
> 换掉 `PipedInputStream` 那个坑就是它逮出来的（见下文「TTS 播放链路」）。

### 许可提醒

- 本地获取的 Haru 为 **Live2D 官方示例模型，仅用于技术验证**，
  请勿随产品分发或商用
- Live2D Cubism Core 受 Live2D 独立授权条款约束，
  商用达到一定规模需购买授权
- 本项目定位个人自用；若要公开发布，请确保对所用模型拥有合法授权

详见 [`app/src/main/assets/live2d/LICENSES.md`](app/src/main/assets/live2d/LICENSES.md)。

## TTS 播放链路（边收边播）

```
MiMo SSE 分块(base64) → decodeTtsSseToPcm 逐块解码 → AudioPipe → AudioPlayer.read → AudioTrack.write
```

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
| `TTS 播放超时（已等 Xms）` | 120s 兜底：音频一直没放完，已强制停止 |

> 参考音频（`assets/silverwolf/ref_voice.wav`，约 640KB，base64 后 ~880KB）每轮都要
> 随请求上传，这是「开口前等待」里除服务端合成之外的另一块固定成本。

## API 兼容性

本应用所有 AI 接口均使用 **OpenAI 兼容格式**：

| 接口 | 端点 | 用途 |
|------|------|------|
| LLM | `POST /v1/chat/completions` | 文本生成 (SSE 流式) |
| ASR | `POST /v1/audio/transcriptions` | 语音识别 (Multipart) |
| TTS | `POST /v1/chat/completions` | 语音合成 (MiMo 格式) |
| Models | `GET /v1/models` | 获取可用模型列表 |

支持的服务商：Groq、OpenAI、MiMo、以及任何 OpenAI 兼容 API。

## 许可证

MIT License
