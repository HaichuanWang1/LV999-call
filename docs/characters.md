# 内置角色与自定义方案

> 本文件是 [主自述文件](../README.md) 的技术分册，内容偏实现细节与踩坑记录。

## 内置预设

内置角色不是硬编码，而是**并列的数据行**。早期「银狼」散落在全项目：模型路径写死在
`bridge.js`、提示词写死在 `StartCallUseCase`、音色兜底写死在 `ProcessAudioUseCase`、
背景图写死在路由、头像兜底写死在 `CallScreen`……加第二个角色时这些点会互相污染。

现在全部收敛进 [`BuiltInCharacter`](app/src/main/java/com/lv999call/app/domain/model/BuiltInCharacter.kt)：

| 维度 | 字段 |
|---|---|
| 身份 | `id` / `displayName` / `subtitle` / `emoji` |
| 人格 | `promptAsset`（assets 里的系统提示词） |
| 形象 | `live2dProfileId` / `modelPath` / `expressions` |
| 外貌 | `avatarResId`（通话头像）/ `cardIconResId`（首页图标） |
| 背景 | `backgroundResId` |
| 发声 | `ttsPolicy` / `defaultTtsPrompt` |
| 演出 | `hasTransform`（是否有变身过场） |
| 署名 | `credit` |
| 文案 | `prepareTitle` / `prepareDescription` |

配套的三张并列注册表：

- [`BuiltInCharacters.ALL`](app/src/main/java/com/lv999call/app/preset/BuiltInCharacters.kt) —— 角色注册表，顺序即首页顺序
- [`Live2DExpressions`](app/src/main/java/com/lv999call/app/domain/model/Live2DExpression.kt) —— 每角色一套表情 / 姿势 + few-shot 示例
- `bridge.js` 的 `PROFILES` —— 每角色一档形象参数（待机通道 / 布局 / 呼吸 / 变身）

路由也是**参数化**的（`character_prepare/{characterId}` / `character_call/{characterId}`），
不是每个角色两条专属路由 —— 否则 NavGraph 会随角色数线性膨胀。

**加一个角色的完整步骤**（`BuiltInCharacters` 加一行 + assets 放提示词 +
`Live2DExpressions` 加一套 + `bridge.js` 加一档 profile + 首页图标/背景两张 drawable）
只是骨架，真正容易漏的是这三条：

1. **提示词里不要自己写 `[[e:…]]` 标签协议**。那块协议由宿主在
   `ProcessAudioUseCase` 里按 `!expressions.isEmpty` 追加在角色提示词之后，
   角色提示词再写一遍就是两份互相打架的规则。
2. **`bridge.js` 必须真的加一档 profile**。`PROFILE_ID` 查不到会**静默回落银狼档**，
   症状不是报错，而是"模型对了、布局和待机参数是银狼的"。
3. **参考音频要单独剪**（`tools/make_ref_voice.py`），见 [`tts.md`](tts.md)。

### 没有 Live2D 模型的角色：静态头像降级

「流萤」是第一个**没有 Live2D 模型**的内置角色，走的是既有的降级路径，不需要新代码：

    modelPath 指向的目录不存在
      → bridge.js 的 Live2DModel.from() 被 reject → notify('error')
      → Live2DController.handleEvent("error") → Live2DStatus.ERROR
      → CallScreen 的 live2dActive = false → StaticAvatar（那张 avatarResId）

要点：

- **别指望 `onReceivedError`**：它只在**主框架**加载失败时回调，而模型是页面内的子资源，
  404 不会走到那儿。真正兜住的是 bridge.js 的 `.catch()`（和 15 秒的 `markLoadTimeout()`）。
- **空表情集是有意义的状态，不是"还没填"**：`Live2DExpressions.FIREFLY` 故意留空。
  宿主只在 `!expressions.isEmpty` 时才注入标签协议，所以空集时 LLM 完全不知道
  `[[e:…]]` 存在。反过来先塞一套猜的表情名，标签会被正常输出、正常剥离（不会念出来），
  但表情**永远不生效** —— 最难查的那种静默失效。
- `tools/check_expression_names.cjs` 会把「模型没装 + 表情集为空」判为 **OK**，
  只有「模型装了却一条表情都没开放」才判 FAIL。

### 一个踩过的坑：形象参数不能当成"会变的状态"

`AndroidView` 的 `factory` **只在首次组合时执行一次**，之后 `modelPath` / `profileId`
变了也不会重建 WebView。而续聊时角色是**异步**反查出来的（`matchCharacterByPrompt`，
会话表里没存角色 id），首帧必然是 `null` → `bridge.js` 回落到默认档位（银狼）→
角色到位后 WebView 已经建好，不会重建。

现象就是**打开 DeepSeek酱 显示的是银狼**，且日志里模型 URL 明确是
`models/silverwolf/silverwolf.model3.json`（表情标签还会报「模型没有这个表情」）。

两处修复：
1. `Live2DController.loadModel()` 记录已加载的 `modelPath`/`profileId` 并比对，
   不一致就重载页面（重载会复位 `status` 并自增 `loadGeneration`，
   让超时兜底重新计时）；`Live2DView` 里用 `LaunchedEffect(modelPath, profileId)` 触发。
2. `NavGraph` 的内置角色路由**优先用路由参数里同步已知的角色**，而不是等
   ViewModel 的异步状态 —— 否则首帧仍会按银狼建一次再重载，白闪一下。

### 自定义方案的「继续对话」怎么恢复上下文

自定义方案（`presets` 表）与内置角色有两点本质区别，续聊的处理因此不能共用一条路径：

| | 提示词来源 | 形象 / 音色 | 续聊的判据 |
|---|---|---|---|
| 内置角色 | assets 资产，随版本发布 | 角色自带（头像/背景/音色/表情集） | `matchCharacterByPrompt`：提示词全文比对 assets |
| 自定义方案 | `presets.prompt`，用户随时可改 | 方案自带（头像/背景/参考音频/TTS 语气） | `sessions.characterKey` = `preset:<id>`，反查回那一行 |

- **方案提示词必须真的落库**：`startPresetCall` 通过
  `StartCallUseCase.createSession(systemPromptOverride = preset.prompt)` 把它写进
  `sessions.systemPrompt`。只放在 ViewModel 内存里的话，通话中看着一切正常，
  一旦续聊（`continueSession` 从库里读回）人设就整个消失 —— 这是「自定义板块
  续聊即裸聊」的根因。
- **续聊按 `characterKey` 反查方案**（`Session.presetIdFromCharacterKey`），
  恢复提示词、参考音频、TTS 语气、头像与背景，与「开始通话」共用
  `CallViewModel.restorePresetContext`，保证两条路径看到的方案完全一致。
- 因此**不**用"拿提示词去和 assets 比对"来认自定义方案：方案提示词只要与某个
  内置角色逐字相同，就会用错头像/背景/发声策略（`characterKey` 才是可靠判据）。
- 方案被删除后，历史会话仍保留它当初的提示词（`sessions.systemPrompt` 是快照），
  但音色/头像/背景会退回默认 —— 记忆仍留在 `preset:<id>` 桶里可看可删。
