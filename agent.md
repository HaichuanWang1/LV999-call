# agent.md · 给智能体看的项目索引

> 这不是任务清单，也不是要每次通读的文档 —— **当索引用**：知道东西在哪、哪条约束不能碰，
> 需要细节时顺着链接去看代码与 `docs/`。
> 工作区约定（该做什么、不该做什么）在 [`AGENTS.md`](AGENTS.md)，那份会被自动注入，以它为准。

## 项目一句话

`lv999call`：Android（Jetpack Compose）语音通话 App。接自己的 LLM / ASR / TTS，
和角色打一通有形象（Live2D）、有情绪（表情标签 → 语气）、有长期记忆的电话。

## 代码地图（`app/src/main/java/com/lv999call/app/`）

| 位置 | 是什么 |
|---|---|
| `App.kt` / `MainActivity.kt` | Application（建通知渠道 + 同步提醒调度）、唯一 Activity（冷启动按需请求通知权限） |
| `navigation/NavGraph.kt` | 全部路由；**三条通话路由的入口都挂了麦克风权限闸门** |
| `ui/common/PermissionGate.kt` | 运行时权限守卫（进页面即申请 + 拒绝后的说明页 / 系统设置出口） |
| `ui/call/CallViewModel.kt` | 一通电话的状态机：开场问候、录音→ASR→LLM→TTS 循环、挂断触发记忆总结 |
| `domain/usecase/ProcessAudioUseCase.kt` | 一轮对话的主流程；**拼 system prompt、裁历史、发声来源优先级**都在这里 |
| `data/repository/ChatRepository.kt` | LLM 流式 + TTS 合成（SSE → PCM 边收边播）；TTS 请求体在这里组装 |
| `data/repository/ConfigRepository.kt` | DataStore 配置读写（所有配置项的唯一入口） |
| `domain/model/ApiConfig.kt` | 配置数据类 + 默认值（改默认值先看这里） |
| `preset/BuiltInCharacters.kt` | 内置角色注册表（银狼 / DeepSeek 酱），一行一个角色 |
| `domain/model/Live2DExpression.kt` | 每角色的表情/姿势表 + `promptBlock()`（运行时**自动追加**到提示词后面） |
| `domain/model/EmotionVoiceStyles.kt` | 表情 key → 这一句的语气（⛔ 表里不许写语速） |
| `notify/` | 记忆提醒：调度（WorkManager）/ Worker / 通知与权限判断 |
| `assets/*.txt` | 角色系统提示词（`silverwolf_prompt.txt` / `deepseek_prompt.txt`） |
| `assets/live2d/js/bridge.js` | Live2D 桥接层：**`PROFILES` 里一档一个模型**（通道表 / 摸头命中盒 / 视线） |

## 几条硬约束（改之前先读代码里的长注释）

1. **提示词不写标签机制**：表情/动作标签（`[[e:…]]` / `[[m:…]]`）由 `promptBlock()` 追加，
   assets 里的角色提示词**不要**重复定义或自创语法。
2. **TTS 用 `format=pcm16`**：`stream=true` + `wav` 会让每个 SSE 分块自带 RIFF 头，听感是持续「哒哒」声。
3. **风格指令为空就不发 `user` 消息**：不要发 `content: ""`，严格校验的接口会 400。
4. **语气只有两个来源**：`characterTtsPrompts[角色 id]`（→ 角色自带 `defaultTtsPrompt`）与
   自定义方案的 `presets.ttsPrompt`。全局兜底那条跨角色污染通道已删除。
5. **情绪语气不许写语速**：语速归基础风格，情绪层只动音量/音高/气息。
6. **长期记忆块只拼进本次请求**，绝不写回 `sessions.systemPrompt`（写回去会越续越长）。
7. **挂断后的总结必须跑在 `applicationScope`**：`viewModelScope` 会被 `onCleared()` 连根取消。
8. **新的运行时权限要真的去申请**：加权限 → 在调用点用 `PermissionGate`（或同款"开关 + 请求 +
   拒绝回滚"）走一遍，只声明不申请等于做了个静默坏功能。
9. **Live2D 写参数前先查 `physics3.json`**：**物理输出**参数每帧被物理覆写，写上去等于没写
   （动作文件、程序化待机层都算）。同理，动作文件不许写 `ParamAngleX` / `ParamEyeBall*`
   （视线只来自 focus 与呼吸）和待机层占用的通道 —— 有 `tools/live2d_motion_check.cjs` 兜着。
10. **视线只走 `bridge.js` 的 `setGaze()`，别直接调 `model.focus()`**：那个公开 API 收的是
    世界坐标点、只取「画布中心 → 该点」的**方向**、**模长恒为 1** —— 传归一化偏移会得到
    "满偏盯左上角"，传画布正中心会得到"满偏盯右边"（**正中心是它的奇点**）。
    「看正前方」它根本表达不出来，只有 `internalModel.focusController`（`[-1,1]`）能。
11. **Live2D 的 profile id 与模型目录名可以不一致，别"顺手对齐"**：`live2dProfileId`
    是角色 ↔ 形象的绑定键，自定义预设也会把这个字符串存进数据库 —— 改名会让已有预设找不到档位。
    DeepSeek 酱就是 profile 叫 `deepseek`、模型在 `models/dafeiyu/`。

## 文档索引（`docs/`）

| 文件 | 讲什么 |
|---|---|
| `architecture.md` | 技术栈 / 目录 / 架构图的用法与重跑方式（**发版前**更新架构图，小修不必） |
| `tts.md` | 边收边播链路、pcm16 的坑、风格提示词两段式 |
| `characters.md` | 内置角色与自定义方案的并列设计、续聊如何恢复上下文 |
| `memory.md` | 长期记忆的写入 / 注入 / 提醒通知链路 |
| `live2d.md` | Live2D 集成（bridge.js、口型、摸头、表情标签协议） |

## 智能体相关文件在哪

| 路径 | 用途 | 是否入库 |
|---|---|---|
| `AGENTS.md` | 工作区约定，自动注入每个 agent | ✅ 入库 |
| `agent.md` | 本文件（索引） | ✅ 入库 |
| `.agents/skills/archify/` | Archify 技能（dsh 自动发现，不用安装） | ✅ 入库 |
| `.dsh/` | **agent 临时工作区**（阶段规格、审查提词、提交信息草稿、临时库快照） | ❌ gitignore |

`.dsh/` 是纯临时产物，随时可删 —— 阶段规格在 plan 执行完后就是冗余，提交信息在
`git log` 里已经有了，临时 sqlite 快照还带着真机的私人聊天数据。2026-02 那次清理删掉了
8 份提交信息草稿、`plan4/` 阶段规格、`migtest/`（含真机库快照）与 archify 的陈旧交付状态，
并把还能复用的 `parse_prefs.py`（解析 DataStore 的 `preferences_pb`）移到了 `tools/`。

> 排查真机配置时用它：`python tools/parse_prefs.py <path-to-ultraflow_config.preferences_pb>`。
> 真机路径一般在 `/data/data/com.lv999call.app/files/datastore/`（需要 root 或 debug 版 run-as）。

## 看过了但**没动**的东西（留给你决定）

这几份是入库内容、不属于"agent 临时产物"，删不删是你的判断：

| 文件 | 状况 |
|---|---|
| `advice.md` | 一次代码通读后的"可以做什么"建议清单，开头自称与 `plan1.txt`/`plan2.txt` 并列 —— 那两份 plan 已不在仓库里，而它列的部分缺陷（网络错误被 TTS 念出来、`RECORD_AUDIO` 缺运行时请求）已经修掉了。**内容仍然有效**（打断、焦点、延迟、分段 TTS 那几节都没做），但需要在读的时候自己剔除已完成项。 |
| `可能会用？` | 一段对话记录（关于采样参数 key 与分段 TTS 的取舍）。文件名不是给人找的，内容里"等你确认"的两件事至今没结论。 |
| `plan5.md` / `plan6.md` | 已执行完的 plan（AGENTS.md 说执行完就删）。`plan6.md` 里还留着一份真机验收清单，其中「冷启动权限请求」「设置页权限对话框」几项标着"未验"。 |
| `plan7.md` / `plan8.md` | 已执行完的 plan。`plan7.md` 是权限改造 + 两个角色提示词重做；`plan8.md` 是给 DeepSeek 换模型（大肥鱼）+ 修「两个角色都盯着左上角」。两份都可以删了。 |
| `_tmp_ds/` | 第三方 Live2D 模型原始下载包（已 gitignore）。里面是**上一版** DS鲸鱼娘的解包副本；当前形象「大肥鱼」的原始包不在仓库里（在 `~/Downloads/大肥鱼.zip`）。不是 agent 文件，但重复占用磁盘；删了要重新下载才能再用 `tools/setup_*` 脚本。 |
