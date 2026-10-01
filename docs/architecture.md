# 架构与项目结构

> 本文件是 [主自述文件](../README.md) 的技术分册，内容偏实现细节与踩坑记录。

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
| 音频播放 | AudioTrack (PCM 流式) |
| ASR | 自定义 HTTP / Vosk 离线 |
| TTS | MiMo-V2.5-TTS 系列（预置音色 / 音色克隆，OpenAI 兼容） |
| 图片 | Coil |
| Live2D | WebView + PixiJS 6 + pixi-live2d-display (Cubism 4) |

## 项目结构

```
app/src/main/java/com/lv999call/app/
├── audio/                  # 音频引擎
│   ├── AudioRecorder.kt    #   录音 + VAD
│   ├── AudioPlayer.kt      #   流式播放 (PCM16，自动兼容 WAV 头)
│   ├── AudioPipe.kt        #   边收边播用的有界字节管道
│   ├── VadDetector.kt      #   语音活动检测
│   ├── AsrEngine.kt        #   ASR引擎 (PCM→WAV转换)
│   └── VoskModelManager.kt #   Vosk离线模型管理
├── data/
│   ├── local/              #   Room数据库 + DAO + Entity
│   │   ├── entity/MemoryEntity.kt  #   长期记忆表（memories）
│   │   └── dao/MemoryDao.kt        #   记忆读写：哈希去重 / 时间闸门 / lastUsedAt
│   ├── remote/             #   API服务 (LLM/ASR/TTS/Models)
│   └── repository/         #   数据仓库
│       └── MemoryRepository.kt     #   写记忆 + 推游标（同一个 Room 事务）
├── domain/
│   ├── model/              #   领域模型（含 BuiltInCharacter / ExpressionSet）
│   │   └── Memory.kt       #   一条长期记忆 + 它覆盖的消息区间
│   └── usecase/            #   业务用例
│       ├── SummarizeMemoryUseCase.kt #  一段对话 → 一条记忆（复用 LLM 通道）
│       └── LoadMemoryUseCase.kt      #  记忆 → system prompt 末尾的记忆块
├── di/                     #   手动依赖注入
├── navigation/             #   Compose Navigation（内置角色走参数化路由）
├── preset/
│   └── BuiltInCharacters.kt #  内置预设注册表（唯一事实来源）
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
    ├── memory/             #     记忆库（筛选 / 长按删除 / 清空 / 导出 / 立即整理）
    └── theme/              #     Material 3 主题

app/src/main/assets/           # 提示词与角色参考音频（各角色 *_prompt.txt 等）
└── memory_summary_prompt.txt  #   记忆总结提示词（改文案不用动 Kotlin）

app/src/main/assets/live2d/  # Live2D 资源
├── index.html               #   承载页面            [入库]
├── js/bridge.js             #   状态机 + 口型同步    [入库]
├── LICENSES.md              #   第三方许可声明       [入库]
├── lib/                     #   运行时              [不入库，需本地获取]
└── models/                  #   模型                [不入库，需本地获取]

tools/
├── setup_live2d_assets.sh     # 一键获取 lib/ 与示例模型
├── setup_deepseek_model.py    # 注册 DeepSeek酱 模型的动作/表情组（可重复执行）
├── live2d_postprocess.py      # 下载后处理（剥离 sourceMapping 等）
├── live2d_strip_watermark.py  # 剔除图集里的署名水印（坐标由 moc3 解析得到）
├── live2d_make_idle.py        # 生成待机动作（Idle 组），改模型文件的可重复来源
├── live2d_motion_check.cjs    # 动作文件校验（真 Cubism Core + moc3 值域/物理表交叉核对）
├── live2d_selftest.cjs        # 桥接层自测（45 项断言）
├── live2d_fallback_test.cjs   # 降级路径测试（9 项断言）
├── check_expression_names.cjs # 表情白名单 ↔ 模型文件一致性校验
├── audio_pipe_test.sh         # AudioPipe 自测（12 项断言，JVM 直跑真实 .class）
├── AudioPipeTest.java         #   ↑ 的测试主体
└── memory_migration_check.py  # Room 3→4 迁移实测（23 项断言，纯标准库 sqlite3）
```


---

## API 兼容性

本应用所有 AI 接口均使用 **OpenAI 兼容格式**：

| 接口 | 端点 | 用途 |
|------|------|------|
| LLM | `POST /v1/chat/completions` | 文本生成 (SSE 流式) |
| ASR | `POST /v1/audio/transcriptions` | 语音识别 (Multipart) |
| TTS | `POST /v1/chat/completions` | 语音合成 (MiMo 格式) |
| Models | `GET /v1/models` | 获取可用模型列表 |

支持的服务商：Groq、OpenAI、MiMo、以及任何 OpenAI 兼容 API。

---

## 交互式架构图

**在线看**：<https://haichuanwang1.github.io/LV999-call/architecture-call-pipeline.html>
—— 由 [`.github/workflows/pages.yml`](../.github/workflows/pages.yml) 把 `docs/` 发到 GitHub Pages
（GitHub 的代码页只会把 `.html` 当源码显示，所以必须走 Pages 才能点开）。

**本地看**：[`architecture-call-pipeline.html`](architecture-call-pipeline.html)，离线单文件，双击即开。

图本身是 Archify 生成的可交互通话链路图：
主链路 `用户 → CallScreen → CallViewModel → AudioRecorder → AsrEngine → ChatRepository → AudioPlayer`，
外加 Live2D、表情→语气、长期记忆、记忆提醒（后台）、Room 与三个远程 AI 端点，
共 **16 个组件、20 条关系、5 张说明卡**，每条都带源码位置。

Skill 本体在 `.agents/skills/archify/`（MIT，v3.0.1），dsh 以 `project-agents` 优先级自动发现，无需安装。
改了通话链路后重跑：

```bash
node .agents/skills/archify/bin/archify.mjs finalize architecture \
  .archify/architecture-<slug>/candidate.json \
  .archify/architecture-<slug>/<slug>.html \
  --repo-root . --quality showcase
```

> 浏览器门禁需要 Chromium；本机没装 Chrome 时指向 Edge：
> `$env:ARCHIFY_CHROME = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'`

### 加节点前先看这条：画布宽高比必须 ≥ 1.55

`finalize` 的 `composition/desktop-readability` 门禁会按画布比例选预算
（`desktop-readability.mjs` 的 `DECLARED_WIDE_READER_RATIO = 1.55`）：

- **宽高比 ≥ 1.55** → 认「宽版阅读契约」，按实际投影字号判定，本图 1538×888（1.73）走的就是这条；
- **低于 1.55** → 退回保守的 930px 桌面预算，此时 1538 宽的画布会被判「字太小」而**直接失败**。

所以**加内容时要横向铺开，不要往下堆行**：这张图是从 888 高加到 1248 高之后才发现比例掉到
1.23、四个门禁全挂在 validate 上的。同理，把某条 `via` 回线往画布底部挪（例如 y=820 → y=1180）
也会连带把比例拉垮 —— 回线要贴着内容走，别让它单独撑高画布。
