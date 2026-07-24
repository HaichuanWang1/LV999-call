# ultraflow · 银狼AI通话

基于 Jetpack Compose 的 Android 语音对话 Agent 应用，支持角色扮演式语音交互。

## 功能特性

### 三种对话模式
- **快速** — 简短提示词，即刻开聊
- **长提示词** — 深度角色扮演，银狼人设完整设定
- **自定义** — 自定义提示词 + 角色头像 + 背景图 + 参考音频

### 全链路语音交互
```
用户说话 → VAD检测停顿 → ASR语音识别 → LLM流式生成 → TTS语音合成 → 实时播放
```

### MiMo-V2.5-TTS-VoiceClone 集成
- 上传参考音频（5~15秒），克隆任意音色
- 全局默认音色（快速/长提示词）与自定义模式音色独立配置
- 自动获取可用模型列表

### 核心能力
- 沉浸式通话界面，头像呼吸灯动画，状态实时指示（聆听/思考/说话）
- 对话历史本地持久化（Room），支持"继续上次对话"
- 全配置可调：LLM / ASR / TTS 的 URL、Key、模型、语速等

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

## 项目结构

```
app/src/main/java/com/ultraflow/silverwolf/
├── audio/                  # 音频引擎
│   ├── AudioRecorder.kt    #   录音 + VAD
│   ├── AudioPlayer.kt      #   流式播放 (WAV自动检测)
│   ├── VadDetector.kt      #   语音活动检测
│   └── AsrEngine.kt        #   ASR引擎 (PCM→WAV转换)
├── data/
│   ├── local/              #   Room数据库 + DAO
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
    ├── history/            #     历史记录页
    ├── custom/             #     自定义编辑页
    ├── settings/           #     设置页
    └── theme/              #     Material 3 主题
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

### 首次配置
1. 打开 App → 点击右下角 ⚙️ **设置**
2. 填入 **LLM** 的 Base URL 和 API Key（兼容 OpenAI 格式）
3. 填入 **TTS** 的 URL 和 API Key（MiMo 或其他兼容服务）
4. 点击 🔄 按钮自动获取模型列表，选择模型
5. 上传一段参考音频作为默认音色
6. 保存设置，返回首页开始通话

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
