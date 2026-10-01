# plan6 · 修「对话输出」三个问题 + 待办清单

> 背景：v1.6.0 的两个新功能（声音跟着情绪走 / 记忆提醒通知）已装到真机（`1.5.0(13)` → `1.6.0(14)`，
> 覆盖安装、数据保留），用户测试时发现输出有问题。下面第 1 节是**当前任务**，第 2 节是**待办**。

---

## 1. 当前任务：修真机暴露的三个问题

真机证据（`adb logcat -s ProcessAudioUseCase:D CallVM:D`，2026-10-01 23:43）：

```
CallVM            D  首轮表情被忽略（强制普通脸）: 0.0
ProcessAudioUseCase D  AI回复: The user is just saying hello. I should respond in character as
                       Silver Wolf, with a casual, gaming-oriented greeting.哟，上线了？……
ProcessAudioUseCase D  TTS: textLen=167, refAudioLen=882060, ... policy=CloneVoice(...)
ProcessAudioUseCase D  语气: 表情=0.0 → 语速慢、音量平，几乎没有情绪起伏，冷淡敷衍。
ChatRepo          D  TTS: url=..., text=The user is just say..., ...
```

### 1.1 她在念英文的「内心计划」（用户报的第一条）

**现象**：回复正文开头多了一段英文（"The user is just saying hello. I should respond in
character as Silver Wolf, …"），而且**TTS 把它一起念了出来**（`text=The user is just say...`，
`textLen=167`）。用户听到的是角色先用英文自述一遍再说话。

**根因**：模型这次**没有打 `<think>` 标签**，直接把"计划"当正文吐出来。
`ReasoningStripper` 是**标签驱动**的（只认 `<think>` / `<thinking>` / `<reasoning>` / `<analysis>`），
没有标签就原样放行。`thinking:{type:"disabled"}` 只对认这个私有字段的服务商有效，挡不住这种裸前言。

**方案（两层，都要做）**：

1. **提示词层（主）**：在 `ProcessAudioUseCase` 拼 `effectivePrompt` 的地方追加一段**输出规范**
   （对所有角色与自定义预设一视同仁，不散落到每个角色的提示词里）：
   只输出要对用户说的那句话本身；不要输出分析、计划、解释、旁白、括号动作描写；
   不要输出任何英文说明；不要在正文里重复标签机制。
2. **防御层（兜底）**：加一个「剥掉开头的拉丁文前言」的清洗，放在
   `ProcessAudioUseCase` 里 `aiResponse` 定稿之后（`tagParser.finish(...)` 之后）。
   判据要保守：**第一个 CJK 字符之前**的那一段，若长度 ≥ 30 且几乎全是 ASCII 字母/空格/标点、
   且含至少一个句末标点，就判定为漏出的前言并丢弃。阈值把 `OK，走吧` / `GG` 这类正常开头挡在外面。
   命中时打一条**警告日志**（这类清洗静默生效就没人知道模型在退化）。

**顺带确认**：`SummarizeMemoryUseCase` 也走 `ReasoningStripper`，同样的裸前言会被写进**记忆正文**。
如果提示词层没压住，同一套清洗也要接到总结侧（先看真机还犯不犯）。

**状态**：❌ 未做。

### 1.2 语速慢得离谱（用户报的第二条，也是用户拍板了修法）

**现象**：开场白被念得极慢。

**根因**：两条指令在同一个 TTS 请求里正面打架 ——
- 银狼基础风格（`BuiltInCharacter.defaultTtsPrompt`）：`用清亮软糯的少女音说话，语速稍快，…`
- 我写的 `0.0` 语气：`语速慢、音量平，…`

模型选择听后者，于是"稍快"被覆盖成"很慢"。

**用户拍板的修法**：**语气表禁止写语速相关的提示词**。语速归基础风格独家所有。

**已做的改动**（`EmotionVoiceStyles.kt`，未编译）：
- 29 条全部去掉语速/节奏类词（语速、快、慢、拖、急、节奏），只保留
  **音量 / 音高 / 气息 / 起伏 / 语气色彩**。例：`0.0` 从"语速慢、音量平，几乎没有情绪起伏，
  冷淡敷衍"改成"音量平，几乎没有情绪起伏，冷淡敷衍"。
- 类注释里把这条写成**铁律**，并记下这次踩坑（基础风格说稍快、语气表说慢 → 开场白慢到离谱），
  以及分工原则：**音色与语速是"她是谁"，情绪只是"这一句怎么了"**。
- `composePrompt` 收尾限定语从"（音色与基本语速不变，只调整这一句的情绪和节奏）"
  改成"（**音色和语速保持上面的一贯风格不变**，只调整音量、音高、气息与情绪色彩）"——
  原话自相矛盾（说了语速不变又说调整节奏），模型会自己发挥。

**状态**：🟢 代码已改、**编译通过**；未真机复测。

### 1.3 首轮错位：脸是普通的，语气却按标签演

**现象**：`首轮表情被忽略（强制普通脸）: 0.0` 与 `语气: 表情=0.0 → …` 同时出现 ——
脸被故意忽略了，声音却按 `0.0` 演。开场白因此"面无表情 + 冷淡敷衍"。

**根因**：抑制规则只在 `CallViewModel.cueExpression` 里（首轮强制普通脸），
而**语气捕获在 `ProcessAudioUseCase` 里**，两边不知道对方。这是加语气功能时引入的不一致。

**修法（已做）**：把"这个表情有没有真的被用上"变成**回调的返回值**，语气只在 true 时跟着走：

- `ExpressionTagParser.onExpression`：`(Live2DExpression) -> Unit` → `(Live2DExpression) -> Boolean`
- `ProcessAudioUseCase`：`onExpression` 参数同改；lambda 里
  `val applied = onExpression(expression); if (applied) turnEmotionKey = expression.key`
- `CallViewModel.cueExpression`：返回 Boolean（首轮 `return false`，正常 `return true`）
- `SummarizeMemoryUseCase`：no-op lambda `{}` → `{ true }`（它只是拿 parser 剥文本）

这样以后再加抑制规则只改宿主一处，不会又漏掉语气。

**状态**：🟢 代码已改（5 个文件）、**编译通过**；未真机复测。

### 1.4 收尾步骤

1. ~~`./gradlew :app:assembleDebug`~~ ✅ 已编译通过
2. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
3. 真机复测三条：
   - 回复正文里**没有英文前言**，TTS 也不念（`AI回复:` 与 `TTS: text=` 两行对照看）
   - 开场白语速**正常**（不再"慢到离谱"）；`语气:` 行里**不该再出现语速类词**
   - 首轮应看到 `首轮表情被忽略（强制普通脸）: X（语气也不跟着走）`，且**没有**对应的 `语气:` 行
4. 提交（1.2 / 1.3 的改动已提交，1.1 还没做）

---

## 2. 待办清单

### 2.1 阻塞中的：发布 v1.6.0（用户要求"测试完再说"）

- 版本号已提到 **14 / 1.6.0**（commit `3f7da65`，消息里写明**尚未发布**）
- 发行说明已写好：`.release-notes-v1.6.0.md`
- **tag 与 GitHub Release 都还没建**
- 发布流程：`assembleRelease` → `gh release create v1.6.0 <apk> --target main --notes-file .release-notes-v1.6.0.md`
- 签名凭据在 `local.properties`（`lv999.storeFile` 等四项齐全，构建会正常签名）
- ⚠️ 发布前记得按 AGENTS.md **再跑一次 archify**（架构图已更新过一轮，若代码再动要跟上）
- 发布后核验：tag == main HEAD、asset 大小、下载 HTTP 200

### 2.2 记忆提醒通知：加「立即试一次」按钮（我提议，用户未答）

**问题**：静默时段 22:00–09:00 + 12h 周期 + 24h 频控，三个约束叠起来，
**打开开关后当晚完全测不出来**（第一次机会要等 30 分钟，落在静默时段就被跳过，
下一次要等 12 小时）—— 对用户来说"打开了但什么都没发生"跟坏了没区别。

**方案**：设置页那个开关旁边加一颗「立即试一次」，点一下入队一个**一次性** WorkRequest，
跑同一个 Worker 但**跳过静默时段与频控**（这是用户显式点的，不算打扰），
结果用 Snackbar 回报（生成成功 / 没什么可说的 / 失败原因）。
复用 `MemoryReminderScheduler`，别另写一套判据。

### 2.3 记忆提醒通知的真机验证（尚未做）

| 项 | 状态 |
|---|---|
| 通知渠道创建 | ✅ 已验（`NotificationChannel{mId='memory_reminder', mName=角色的提醒, mImportance=3}`） |
| App 启动按开关同步调度 | ✅ 已验（开关默认关 → `调度: 已取消 unique=memory_reminder`） |
| 权限对话框（允许路径） | ❌ 未验 |
| 权限对话框（**拒绝** → 开关必须回滚 + 提示） | ❌ 未验（最容易写错的一处） |
| `调度: 已入队` | ❌ 未验 |
| WorkManager 实际执行 + 通知弹出 | ❌ 未验（首次 30 分钟后） |
| 点通知的返回栈 | ❌ 未验 |

日志：`adb logcat -s MemoryReminder:D`。**正常路径几乎全是 `跳过: <原因>`，没有 ERROR 通常就代表"按设计没打扰你"**。

### 2.4 `RECORD_AUDIO` 缺运行时权限请求（既存缺口，我没动）

全项目**只 `checkSelfPermission`、从不 `request`**（`AudioRecorder.kt:93`）。
新装用户若不手动去系统设置里给权限，**麦克风不工作**。

`POST_NOTIFICATIONS` 那套「开关 + 请求 + 拒绝回滚」可以直接搬过来。
（用户说过懒得修无伤大雅的东西，但这条影响核心功能，单列出来。）

### 2.5 架构图剩余 4 处建议性交叉

archify 四个门禁（validate / deliver / check / browser-check）**全部 pass**，
`check` 0 issue；4 处交叉是 `visualReviewRecommendation` 的 **advisory** 信号。
来源：后台提醒路径要同时够到**左边的记忆层**和**右边的 LLM 层**。
已做过一轮有界修复（交换 `memory` 与 `room`，交叉 5 → 4）。彻底消除需要重排整张图。

⚠️ 重排前先读 `docs/architecture.md` 末尾那条：**画布宽高比必须 ≥ 1.55**，
低于它就会退回保守的 930px 预算、四个门禁全挂（这次已经踩过一次）。

### 2.6 长期项：记忆的合并 / 衰减

`docs/memory.md` 自己写的"这一步之后**第一优先**要补的"：
一条会话一条记忆，注入只看最近 8 条 —— 第 9 通之后更老的记忆还在库里，
但角色再也想不起来（"假记忆"）。按业界已验证的语义做
（merge / supersede / synthesize 全部是**标记而不删除**，并记录替代者），别自创删除逻辑。

### 2.7 小项：记忆提醒开关是否改成「即时落盘」

现在设置页统一在"保存设置"时落盘，所以"开了开关但不保存就退出"会留下一个
"已入队但配置是关的"的中间态（Worker 自己会跳过、不误推，下次启动按配置取消）。
彻底消除得让开关立即写库（像 `updateMemoryReminderLastAt` 那样只写一个 key），
但那会破坏本页"统一保存"的既有模式。**当前保持现状**，等用户表态。

---

## 3. 当前工作区状态

- 已提交：`3f7da65`（v1.6.0 版本号 + 发行说明，**尚未发布**）
- 本次提交包含：
  - `plan6.md`（本文件）
  - 第 1.2 / 1.3 节的修复，共 5 个文件（**编译通过，未真机复测**）：
    - `domain/model/EmotionVoiceStyles.kt`（语气表去语速 + 铁律注释 + composePrompt 限定语）
    - `domain/usecase/ExpressionTagParser.kt`（回调返回 Boolean）
    - `domain/usecase/ProcessAudioUseCase.kt`（参数类型 + 只在 applied 时捕获语气）
    - `ui/call/CallViewModel.kt`（`cueExpression` 返回 Boolean）
    - `domain/usecase/SummarizeMemoryUseCase.kt`（no-op lambda 改 `{ true }`）
- 第 1.1 节（英文前言）**一行都还没改** —— 下一步从那里开始
- 真机：`com.lv999call.app` = 1.6.0 (14)，debug 包，数据保留；**装机的是修复前的包**，
  改完要重新 `install -r`
