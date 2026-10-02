# 任务规划（plan9）

## 目标

把「接口失败」从**只有 Logcat 知道**，变成用户能看懂、能照着处理的弹窗提醒：

1. LLM 返回的报错信息要**正常填写** —— 解析服务端响应体里的 `error.message`
   （而不是只甩一句 Retrofit 的 `HTTP 401 Unauthorized`），并弹窗展示；
2. 返回「没有填写 key」与「额度耗尽」时，弹**专项**提醒；
3. TTS 没配置（key 空 / 没有可用参考音频）同样要提示；
4. 所有提示都要引导用户去「检查 key 和 url」。

## 背景（改造前的问题）

| 现象 | 真实原因 |
| --- | --- |
| 角色突然不说话，界面上毫无痕迹 | `ChatRepository.streamChatCompletion` 把失败 emit 成 `Failure(e.message)`，而 `ProcessAudioUseCase` 只打一行日志就 `return Pair(userMessage, null)` |
| 弹窗里只有 `HTTP 401 Unauthorized` | Retrofit 的 `HttpException.message` 不含响应体，服务端写的 `error.message` 一个字都没读 |
| TTS 报错完全静默 | `synthesizeSpeech` 返回 `InputStream?`，失败 / 没配音色 / key 没填全被压成 `null`，调用方只判了 `!= null` |
| 200 + 错误体被当成"模型什么都没说" | SSE 解析只认 `choices[].delta.content`，不认流内的 `error` 分块 |

## 方案

### 1. 新增失败模型与解析器

- `domain/model/ApiFailure.kt`
  - `ApiFailureKind`：`MISSING_KEY` / `QUOTA_EXHAUSTED` / `TTS_VOICE_MISSING` / `OTHER`
  - `ApiFailure(kind, detail, httpCode)`，`detail` = 给用户看的服务端原话
- `data/remote/ApiErrorParser.kt`
  - `fromHttp(code, reason, body)`：状态码为主判据（401/403 → key，429/402 → 额度），
    文案关键词兜底（有些服务商把额度问题塞进 400/500）
  - `fromThrowable(e)`：`HttpException` 顺带把响应体读出来；DNS/超时/拒连翻译成人话
  - `fromSuccessfulBody(body)`：认「HTTP 200 + `error` 字段」这一形态
  - `configMissing(detail)` / `voiceMissing(detail)`：本地就没配，压根没发请求
  - `extractMessage(raw)`：兼容 `{"error":{"message":…}}` / `{"error":"…"}` /
    `{"code":…,"message":…}` / `{"detail":…}` / 纯文本，统一压空白 + 截断 300 字

### 2. 仓库层带上结构化原因

- `StreamEvent.Failure(reason: String)` → `Failure(error: ApiFailure)`
- 流式解析新增：带 `"error"` 的行先按错误体解析（SSE 分块与整段错误 JSON 都覆盖）
- 本地 `llmBaseUrl` 为空时直接给 `MISSING_KEY`，不白跑一次必然失败的请求
- `CancellationException` 单独 rethrow：挂断/离开页面取消**不是**失败，不能弹窗
- `synthesizeSpeech` 返回值 `InputStream?` → `SpeechResult`：
  `Audio` / `Skipped`（洗完后没内容可念，不算错误）/ `Failed(ApiFailure)`
- TTS 的 key 为空 → `configMissing`；无参考音频 / 音频超 10MB → `voiceMissing`
- `fetchModels` 的错误也走同一套解析，设置页 Snackbar 不再是 `请求失败 (401): Unauthorized`

### 3. 用例层往上送

- `ProcessAudioUseCase.processAudio` 新增 `onApiFailure: (ApiFailure) -> Unit`
- LLM 失败（跳过 TTS 与落库的那条路径）与 TTS 失败各回调一次
- 两个后台用例（`ComposeReminderUseCase` / `SummarizeMemoryUseCase`）改用 `event.error.detail`

### 4. UI 层弹窗

- `CallViewModel`
  - `ApiFailureDialog(title, message)` + `apiFailureDialog: StateFlow` + `dismissApiFailureDialog()`
  - `showApiFailure(failure)` 按 kind 分档拼文案，四档都指向「设置」页检查 key/url
  - **配置类失败按 `kind|detail` 去重**：用户在通话中改不了设置，同一句话每轮弹一次
    就是骚扰；网络类不去重（可能自愈，第二次失败仍要让人看见）
- `CallScreen`：新增 `apiFailureDialog` / `onDismissApiFailure`，用 `AlertDialog` 展示
  （不用状态胶囊：这几类失败必须被看到，2 秒消失的提示容易漏）
- `NavGraph`：三条通话路由（内置角色 / 自定义预设 / 续聊）全部接线

## 验证

- `.\gradlew.bat :app:compileDebugKotlin`
- 手动核对四条路径的文案：401 无效 key、429 额度、TTS key 空、TTS 无参考音频
