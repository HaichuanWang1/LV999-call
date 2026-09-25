# lv999call 后续方向建议

> 本文件是一次代码通读后的方向性建议，不是任务清单。
> 与 `plan1.txt` / `plan2.txt` 并列：plan 系列是**要做什么**，本文件是**可以做什么、为什么**。
> 结论按「值不值得做」排序，每条都标了代码位置，便于核对。

---

## 零、总体判断

**这软件已经不是「缺功能」的阶段了，瓶颈从「能不能用」转到了「通话质感和稳定性」。**

- 角色系统（并列预设 / Live2D / 表情 / TTS 策略）已经做得相当完整，边际收益在下降
- ASR 准确率——最初的痛点——在现有体积约束下**基本摸到天花板**了，继续抠模型收益很小（见第四节）
- 真正影响体感的是几处**确定的缺陷**和**通话本身的质感**（打断、焦点、延迟）

---

## 一、确定的缺陷（不是优化，是 bug）

### 1. 网络错误会被 TTS 念出来，并写进历史

`ChatRepository.kt:186` 把异常直接 emit 成字符串：

```kotlin
} catch (e: Exception) {
    emit("[错误: ${e.message}]")
}
```

这个串会被当成**正常回复**：上气泡 → 送 TTS 合成 → 存进 Room。
而 `ChatRepository.kt:45` 剥标注的正则只吃 1~10 个字符：

```kotlin
private val REGEX_STYLE_ANNOTATION = Regex("[（(][^）)]{1,10}[）)]|\\[[^\\]]{1,10}]")
```

`[错误: Connection reset]` 远超 10 字符，剥不掉。
**现象：断网时角色会一本正经地念一句「错误 连接超时」，而且这句话永久留在聊天记录里。**

修法：错误走独立通道（异常 / sealed class），不要伪装成正文；TTS 前对整段文本再做一次错误标记过滤。

### 2. 长回复会「自听自说」

两层超时对不上：

| 位置 | 超时 |
|---|---|
| `CallViewModel.kt:552` 整轮 | `withTimeoutOrNull(60_000L)` |
| `ProcessAudioUseCase.kt:222` 等播放 | `awaitPlaybackEnd(120_000L)` |

回复一长（朗读超过 60s），**外层先超时** → `result == null` → `finally` 把状态收回 `LISTENING` 并 `startListening()` 开麦。
但播放器跑在**自己的 scope**（`AudioPlayer.scope`）里，还在继续放。

于是麦克风开始录，而 `AudioRecorder` 是**刻意关掉 AEC** 的（见 `AudioRecorder.applyAudioEffects()`）——
**AI 把自己的话录进去当用户输入**。顺带这一轮的助手回复也丢了。

修法：外层超时按「TTS 播放上限 + 余量」给，或让超时路径显式 `stopCurrentPlayback()`；两者共用同一个常量。

### 3. 设置里的「TTS URL」是假的

`SettingsScreen.kt:363` 有输入框，`ApiConfig.ttsBaseUrl` 也存了，但 `ChatRepository.kt:302` 地址写死：

```kotlin
val url = "https://api.xiaomimimo.com/v1/chat/completions"
```

填了完全没用。同类死配置还有 `ttsProvider`（`ApiConfig.kt:59`）与 `ttsVoiceId`（`:63`）——存了从不读。

修法：要么接上（顺带完成 TTS provider 抽象），要么把输入框撤掉，别留着骗人。

### 4. 「可打断」目前只是一句注释

- `CallState.kt:8` 注释写着 `SPEAKING, // TTS播放中（可打断）`
- `ProcessAudioUseCase.kt:240` 定义了 `stopTts()`
- **全项目没有任何调用点**（已 grep 确认）

也就是说 AI 说话时你说话没用，必须等它说完。
好消息：底层本来就支持——`AudioPipe.close()` 能同时唤醒阻塞的读端和写端（`AudioPipe.kt:141`），`AudioPlayer.stopCurrentPlayback()` 也已实现。

---

## 二、第一优先：通话质感（这才叫「打电话」）

| 做什么 | 价值 | 成本 |
|---|---|---|
| **打断（barge-in）** | 体感提升最大。说话即打断，接上已有的 `stopTts()` 即可 | 中 |
| **音频焦点 + 前台服务** | 见下 | 中 |
| **首字延迟：LLM 分句流水线** | 见下 | 中大 |

### 打断（barge-in）

现在 `ProcessAudioUseCase` Step 3 是「整段生成完 → 一次 TTS → 等播完」。
打断需要：播放期间保持录音（或至少在 SPEAKING 时也监听能量），检测到用户说话 → `stopTts()` → 切回 LISTENING。
注意与缺陷 2 联动：**开麦录音 + 播放中的音频**必须保证 AEC 或时序上不冲突，否则会自激。

### 音频焦点 + 前台服务

当前 `AndroidManifest.xml` 只有 INTERNET / RECORD_AUDIO / 读图权限：

- **没有 `requestAudioFocus`**（已 grep 确认）→ 来消息、来电时音乐不会让路，通话音频也不会自动降音
- **没有 `FOREGROUND_SERVICE`** → 切后台 / 锁屏时通话可能被系统掐掉
- 顺带：`AudioPlayer` 用的是 `USAGE_MEDIA` + `CONTENT_TYPE_SPEECH`，改成 `USAGE_VOICE_COMMUNICATION` 更贴合通话场景

> ⚠️ `targetSdk = 34`（Android 14）。加前台服务必须声明 `foregroundServiceType="microphone"`，
> 并加 `FOREGROUND_SERVICE_MICROPHONE` 权限，否则启动即崩。**按 AGENTS.md 约定，新增权限要一并加上权限请求。**

### 首字延迟：LLM 分句流水线

`ProcessAudioUseCase` 的 Step 2 是**收完整段 LLM 输出**才进 Step 3 合成：

```kotlin
chatRepository.streamChatCompletion(...).collect { chunk -> ... }   // 全部收完
// ↓ 才到这里
onStateChange(CallState.SPEAKING)
val audioStream = chatRepository.synthesizeSpeech(config, aiResponse, ...)
```

TTS 本身已经是流式的（SSE 边收边播），**但被 LLM 卡在前面**。
按标点切句、边生成边合成边播，首字延迟能显著下降。这是延迟的大头。

代价：要处理句间衔接、打断、以及「TTS 请求体里那 ~900KB 参考音频每句都要重传」的成本。

---

## 三、第二优先：稳定性与可信度

- 第一节的 4 个缺陷
- **错误要让人看见**：现在 LLM 失败只打 log（`ProcessAudioUseCase.kt:174`），UI 直接回聆听，用户不知道发生了什么
- **首次通话加载 Vosk 模型没提示**：`VoskModelManager.extractFromAssets()` 要解压 **65.1MB**，发生在 `startCall` 里，UI 停在 IDLE 干等。需要进度提示或提前预热
- **`fallbackToDestructiveMigration()`**（`AppDatabase.kt:62`）：哪天加字段忘了写迁移，用户历史全没。建议补迁移自测（现有 `MIGRATION_1_2` / `MIGRATION_2_3` 是手写的）
- **API Key 明文存 DataStore + `allowBackup=true`**：系统备份会把 Key 带走
- 会话表**没存角色 id**，续聊靠 `matchCharacterByPrompt()` 反查提示词（`CallViewModel.kt:353`）——能用但脆，改提示词一个字就失配

---

## 四、第三优先：ASR 准确率

### 现状（已实测）

- Vosk `vosk-model-small-cn-0.22` 官方 CER：**23.54%**（SpeechIO-02）/ 38.29%（SpeechIO-06）
- 中文模型词表是**单字**，输出形如「你 好 世 界」，已在 `AsrEngine.normalizeVoskText()` 里去掉汉字间空格
- 前端（录音裁剪 / VAD / 音源）上一轮已修到位（`3f04741`）

**结论：23.5% 是模型天花板，前端已经尽力了。** 再上一个台阶只有三条路：

### 路线 A：换 sherpa-onnx + 更大模型（**撞体积约束**）

| 模型 | int8 体积 |
|---|---|
| SenseVoiceSmall | **228MB** |
| Paraformer-zh | **227MB** |

远超「手机用的、体积要小」的约束，**不能内置**。
→ 但可以做成**可选下载**：`VoskModelManager.downloadModel()` 已经写好、UI 也有下载入口，
只是 `AVAILABLE_MODELS` 里只有那个已内置的小模型，等于这套机制没启用。

### 路线 B：零体积 —— 让 LLM 纠错（**推荐先试**）

ASR 原文本来就要送进 LLM。在提示词里加一层：

> 用户输入来自语音识别，可能有同音字 / 断字 / 标点错误，请按语境理解后再回答。

或做一个轻量纠错 pass。**不增加一个字节**，对同音字、专有名词误识别很有效。

### 路线 C：更小的中文模型

sherpa-onnx 有若干流式 zipformer 中文模型，体积需实测确认。
（此项尚未查证，要做的话先调研。）

### 顺带的体积红利

**Vosk 那 65.1MB 是打进 assets 的**，而 `VoskModelManager` 首次使用还会把它**解压到 `filesDir`** ——
设备上实际占用约 **130MB**（APK 内一份 + 解压一份）。

改成「首次使用时下载」，APK 可从 110.8MB 瘦到约 **46MB**，正好对上「手机用的、体积要小」。

> 实测体积（本机核对，非估算）：
> - `assets/vosk-models` **65.1MB**（graph 40.3 + am 15.2 + ivector 9.7）
> - `assets/live2d` **24.0MB**（models 23.1 + lib 0.8 + js 0.1）
> - `assets/silverwolf` 0.6MB
> - release APK **110.8MB**

---

## 五、第四优先：形象表现力

- **口型改成跟音素而非音量**：现在由 `AudioPlayer.amplitude`（RMS）驱动，快张慢合已调得不错，
  但音量驱动本质是「响度」不是「发音」。真音素级要 ASR/TTS 侧给时间戳，成本高，收益看口味
- 触摸交互（点头、摸头反应）、更多自发待机行为
- 两个角色的动作系统已很完整，边际收益在下降

---

## 六、第五优先：工程化

- **TTS provider 抽象**：`ttsProvider` 现在是摆设，接第二个 TTS 得改 `ChatRepository` 硬编码
- 多语言（界面 i18n）、CI 自动出包
- ⚠️ **`main` 分支还停在 v1.0.3 时代，落后 `feature/live2d-avatar` 40 个提交**，
  且历史 tag（v1.2.0 / v1.1.0 / v1.0.6）全在 feature 线上，`main` 不含新代码。这个迟早要处理

---

## 七、如果只做一件事

**做「打断」+ 修第一节那 4 个 bug。**

五项加起来工作量不大，但体感提升最直接：

> 「AI 说话时我能插嘴」和「它不会突然自己跟自己说话」，
> 是「像打电话」和「像个玩具」的分界线。

然后是 **LLM 分句流水线**（延迟）与 **ASR 后纠错**（准确率，零体积）。

**关于换 ASR 引擎的建议：先做可选下载，别动默认路径。**
默认仍是 Vosk 小模型（内置或改下载），愿意折腾的用户在设置里下 SenseVoice。
这样体积约束和准确率诉求都不牺牲。

---

## 附：核对方式

本文件所有代码位置均可直接跳转核对。体积数字由 `Get-ChildItem -Recurse | Measure-Object Length -Sum` 实测得出。
grep 类结论（如「`stopTts()` 无调用点」「无 `requestAudioFocus`」）均已在仓库内验证。
