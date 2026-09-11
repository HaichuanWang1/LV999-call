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
- > TTS 当前仅支持 MiMo 系列（目前免费），仍需自行申请 API Key

### ASR 双引擎
- **自定义 HTTP**：兼容 OpenAI Whisper 等任意 ASR API
- **Vosk 离线**：内置中文模型，无需网络即可识别

### Live2D 动态形象
- 通话界面可显示 Live2D 角色，替代静态头像
- **口型同步**：TTS 播放音量实时驱动嘴型张合（快张慢合 + 轻微抖动）
- **状态联动**：聆听/思考/说话/结束各有对应动作与表情
- **情绪表情**：LLM 在回复开头插入 `[[e:生气]]` 之类的标签，形象实时换脸，数秒后自动回落
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
├── live2d_selftest.cjs        # 桥接层自测（31 项断言）
├── live2d_fallback_test.cjs   # 降级路径测试（9 项断言）
└── check_expression_names.cjs # 表情白名单 ↔ 模型文件一致性校验
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

### LLM 情绪表情

形象的情绪不只跟着通话状态走，还可以由 LLM 自己决定：

```
系统提示词追加「表情标签」协议（仅 Live2D 开启时注入）
  ↓  LLM 回复：[[e:生气]]喂，你这也太离谱了吧。
流式解析 ExpressionTagParser
  ├─→ UI 展示 / TTS 合成：剥掉标签的干净文本（标签不会被念出来）
  └─→ 回调 Live2DExpression → ExpressionCue
        ↓
CallScreen 下发 Live2DController.setExpression()，7 秒后自动回落
```

几个必须这么做的原因：

- **标签会被 chunk 切断**（`"[[e:生"` + `"气]]"`）。未闭合的标签整段扣住不显示，
  否则玩家会看到半截标签一闪而过，或者被 TTS 念出来。
- **提示词里只暴露短标签**。模型真实表情名是中文带编号且空格不统一
  （`01黑脸` / `02 脸红爱心` / `03 生气` / `月卡`），让 LLM 原样复述极易写错一个字符。
- **情绪是「覆盖层」**。thinking → speaking 的状态切换会重走 `applyState()`，
  若情绪只 `model.expression()` 调一次就会被 `resetExpression()` 冲掉，
  表现为「LLM 明明调了表情但脸没变」；因此 JS 侧记录 `_cue` 并在每次状态切换后重新应用。
- 模型里另外 5 条（吹泡泡 / 外套关闭 / 抱胸手 / 捧心手 / 要饭手）是手部与服装的
  状态切换而非瞬时情绪，混入情绪表达会互相覆盖，故不开放给 LLM。

可调项：`Live2DExpression.kt` 的枚举（标签 ↔ 真实表情名 ↔ 情绪说明）、
`CallScreen.kt` 的 `EXPRESSION_HOLD_MS`（保持时长）。

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
```

> `check_expression_names.cjs` 的价值在于：模型表情名少写一个空格 pixi 只会静默忽略，
> 现象是「表情没变」且没有任何报错，肉眼审查根本发现不了。

### 许可提醒

- 本地获取的 Haru 为 **Live2D 官方示例模型，仅用于技术验证**，
  请勿随产品分发或商用
- Live2D Cubism Core 受 Live2D 独立授权条款约束，
  商用达到一定规模需购买授权
- 本项目定位个人自用；若要公开发布，请确保对所用模型拥有合法授权

详见 [`app/src/main/assets/live2d/LICENSES.md`](app/src/main/assets/live2d/LICENSES.md)。

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
