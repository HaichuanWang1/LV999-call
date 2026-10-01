# lv999call · AI 通话

基于 Jetpack Compose 的 Android 语音对话应用：接上你自己的 LLM / ASR / TTS，
和角色打一通**有形象、有情绪、有记忆**的电话。

<p align="center">
  <a href="docs/live2d.md"><img src="https://img.shields.io/badge/Live2D-形象%20·%20口型%20·%20摸头-ff69b4?style=for-the-badge" alt="Live2D"></a>
  <a href="docs/tts.md"><img src="https://img.shields.io/badge/TTS-边收边播-4c8dff?style=for-the-badge" alt="TTS"></a>
  <a href="docs/memory.md"><img src="https://img.shields.io/badge/记忆-跨会话-9b59b6?style=for-the-badge" alt="记忆"></a>
  <a href="docs/characters.md"><img src="https://img.shields.io/badge/角色-内置%20·%20自定义-2ecc71?style=for-the-badge" alt="角色"></a>
  <a href="docs/architecture.md"><img src="https://img.shields.io/badge/架构-技术栈%20·%20目录-95a5a6?style=for-the-badge" alt="架构"></a>
</p>

## 能做什么

- **全链路语音对话**：说话 → VAD 停顿检测 → ASR → LLM 流式生成 → TTS 合成 → 边收边播
- **Live2D 形象**：口型跟着 TTS 音量走、状态联动、LLM 用 `[[e:生气]]` 标签实时换脸；
  **按住头顶揉几下**她会做出被摸头的反应，连揉会不耐烦（详见 [Live2D](docs/live2d.md)）
- **三种发声方式**：跟随设置 / 锁定预置音色 / 角色自带克隆音色；
  TTS 风格提示词**按角色各存一份**，互不污染（目前仅支持 MiMo 系列，需自行申请 Key）
- **ASR 双引擎**：任意 OpenAI 兼容 HTTP 接口，或 Vosk 离线模型（无网也能识别）
- **长期记忆**：挂断自动总结成一条备忘、下次开聊按角色注入；记忆库可看 / 可筛 / 可删 / 可清空 / 可导出
- **多角色并列**：内置角色是**数据行**而不是散落各处的硬编码 ——
  加角色 = 加一行数据 + 一份提示词 + 一档形象参数，调用链一行都不用改
- 对话历史本地持久化（Room），支持「继续上次对话」；LLM / ASR / TTS 全部可配

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
> 使用自备模型见 [Live2D 形象 → 使用自备模型](docs/live2d.md)。

### 首次配置
1. 打开 App → 点击右下角 ⚙️ **设置**
2. 填入 **LLM** 的 Base URL 和 API Key（兼容 OpenAI 格式）
3. 填入 **TTS** 的 API Key（MiMo）
4. 点击 🔄 按钮自动获取模型列表，选择模型
5. 上传一段参考音频作为默认音色
6. 保存设置，返回首页开始通话

> 如果使用本地局域网部署的模型（如 192.168.x.x），直接填入 HTTP 地址即可，已放行明文流量。

## 内置角色

| 角色 | 形象 | 发声 |
|---|---|---|
| **银狼** | Live2D（Q 版，带变身过场） | 角色自带参考音频克隆 |
| **DeepSeek酱（大肥鱼）** | Live2D（半身立绘） | 锁定 MiMo 预置少女音「冰糖」 |
| **自定义方案** | 任意头像 / 背景 / 参考音频 | 自己配 |

加角色的完整步骤、自定义方案的「继续对话」怎么恢复上下文 →
**[角色与自定义](docs/characters.md)**

## 文档

| 分册 | 里面有什么 |
|---|---|
| **[Live2D 形象](docs/live2d.md)** | 工作原理、模型动作图谱、程序化待机、偶发待机动作、变身过场、LLM 情绪表情、**摸头互动**、自备模型、水印清理、自测 |
| **[TTS 播放链路](docs/tts.md)** | 边收边播、为什么必须用 pcm16、为什么不用 `PipedInputStream`、**朗读超时**、排查日志 |
| **[长期记忆](docs/memory.md)** | 数据模型、总结时机、三级门槛、写入端校验、加载注入、记忆库、按角色隔离、排查日志 |
| **[角色与自定义](docs/characters.md)** | 内置角色注册表、加角色步骤、自定义方案的续聊恢复、两个踩过的坑 |
| **[架构与项目结构](docs/architecture.md)** | 技术栈、目录树、API 兼容性 |

> 主自述文件只留「能做什么 + 怎么跑起来」；实现细节与踩坑记录都在上面这几册里。

## 许可证

MIT License。

第三方 Live2D 运行时与模型的版权归各自作者所有，**不入库**、需本地获取，
详见 [`app/src/main/assets/live2d/LICENSES.md`](app/src/main/assets/live2d/LICENSES.md)。
