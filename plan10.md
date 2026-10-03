# plan10：新角色「流萤」接入

## 目标

把流萤作为第三个并列内置角色接进 App：克隆音色、专属提示词、头像与背景、角色接线与降级路径全部完成。

Live2D 模型**本阶段不做**：合法来源找不到，自制模型作为**阶段 2 预备方案**记在下面，等有了分层 PSD 再执行。

## 架构现状（为什么改动面很小）

角色已是并列数据行，不是硬编码。加一个角色的完整清单：

| # | 位置                             | 内容                   |
|---|----------------------------------|------------------------|
| 1 | preset/BuiltInCharacters.kt      | 加一行 FIREFLY，进 ALL |
| 2 | assets/firefly_prompt.txt        | 系统提示词             |
| 3 | domain/model/Live2DExpression.kt | 一套表情集             |
| 4 | assets/live2d/js/bridge.js       | 一档 PROFILES.firefly  |
| 5 | res/drawable/                    | 头像 + 背景两张        |
| 6 | assets/firefly/ref_voice.wav     | 克隆参考音频           |

调用链（首页 / 准备页 / 通话页 / 路由 / 记忆桶 / 设置页 TTS 语气格）全部按字段驱动，零改动 —— 已逐个核对 `NavGraph`、`HomeScreen`、`PrepareScreen`、`StartCallUseCase`、`AppModule.cloneRefAudio`、`MemoryViewModel`、`SettingsScreen` 都是遍历 `BuiltInCharacters.ALL`。

模型缺失时 `Live2DStatus.ERROR` → `live2dActive = false` → `StaticAvatar`（不是白屏），这条降级路径已存在且已测过，阶段 1 直接复用。

---

## 1. 提示词（交给子代理写，我只做接入与校验）

- 新文件 `app/src/main/assets/firefly_prompt.txt`，结构对齐 `silverwolf_prompt.txt`（角色设定 / 她是谁 / 性格底色 / 说话方式 / 怎么关心开拓者 / 边界与禁区 / 补充约束 / 对话示例，约 100 行）。
- 子代理负责正文（按你要求隔离上下文污染）。给它的简报包含：
  - 要求：知道自己是流萤但不沉迷人设；她活在现实这一侧、更关注开拓者情绪；用户身份是开拓者；减少「游戏」执着，更像朋友而不是把用户拉进崩铁世界观。
  - 流萤设定：格拉默的火萤（为对抗虫群而生的兵器）、失熵症带来的短寿、机械装甲「萨姆」、与开拓者在匹诺康尼的相遇与「一起看流星」的约定、嗜甜（蜜饼/蛋糕）、温柔而勇敢、珍惜「当下」。
  - 语气锚点：「每个夜晚都值得珍惜」要落成认真过好今天的现实关怀，不是伤春悲秋、不卖惨。
- 我负责的校验（防止污染工具提示词）：
  - `ProcessAudioUseCase:164` 会把 `ExpressionSet.promptBlock()` 追加在角色提示词之后 → 提示词里不许自己写 `[[e:…]]` 协议，不许与「不要 markdown / 不要动作描写 / 1~3 句」冲突。
  - 不许把表情标签机制解释给用户（协议里明确「不要解释这个机制」）。
  - 提示词里不写具体表情键名 —— 那属于表情集。

## 2. 参考音频（克隆音色）

- 新脚本 `tools/make_ref_voice.py`（numpy 2.4 已确认可用）：
  - 读 `sourse/流萤/*.lab`（GBK 编码）列台词，支持 `--list` 只打印候选与时长。
  - 选段 → 每段 30ms 淡入淡出 → 拼接 → 重采样到 22050Hz / 单声道 / 16bit → 峰值归一化 −3dBFS → 总时长 ≈15.0s。
  - 打印规格与 base64 估算，断言 < MiMo 10MB 上限（对齐银狼：15.0s / 661KB / base64 ≈861KB）。
- 选段策略：优先 `archive_firefly_*`（21 条档案语音，语速平稳、最像日常说话），例 `archive_firefly_1`（11.09s「嗨，又见面啦…叫我「流萤」吧。」）+ 1~2 句短句补足；避开带变量语音 `- Placeholder`。
- 输出 `app/src/main/assets/firefly/ref_voice.wav`；接线 `TtsPolicy.CloneVoice(refAudioAsset = "firefly/ref_voice.wav")`。

## 3. 头像与背景

- 头像 `…5589_2.jpg`（Q 版大头 1080×1043）→ `res/drawable/firefly_avatar.png`：1:1 裁切缩到 512×512，~300KB。
  - `AssistantAvatar` 32dp 圆形 Crop、`StaticAvatar` 120dp 圆形 Crop —— 方图正合适。
- 背景 `…5587_2.jpg`（冬装·礼物盒 1672×2508）→ `res/drawable/firefly_bg.jpg`：缩到 867×1300（对齐 `silverwolf_bg.jpg` 规格），JPEG q85，~250KB。
- 用 Pillow（12.2 已确认）；资源名必须小写 ASCII。

## 4. 角色接线

- `BuiltInCharacters.kt` 新增 `FIREFLY`，追加到 `ALL` 末尾：`id="firefly"`（⚠️ 落进 `sessions.characterKey` 与记忆桶，发布后不可改）、`displayName="流萤"`、`emoji="🦋"`、`live2dProfileId="firefly"`、`modelPath="models/firefly/firefly.model3.json"`、`expressions=Live2DExpressions.FIREFLY`、`ttsPolicy=CloneVoice("firefly/ref_voice.wav")`、`hasTransform=false`、`credit=null`（阶段 2 补）。
- `Live2DExpressions.FIREFLY`：阶段 1 故意为空集 —— `ProcessAudioUseCase:164` 判 `!isEmpty` 才注入标签协议，空集时 LLM 完全不知道这套机制，比注入一套模型里不存在的表情（必然静默失效）安全得多；注释写清理由。
- `bridge.js` 新增 `PROFILES.firefly`：⚠️ 必须加，否则 `bridge.js:552` 会回落银狼档。阶段 1 该档只承载「模型缺失 → 快速报错 → UI 回退静态头像」，通道表写最小集标 TODO，不照抄 brow/squint/smile/sway（大肥鱼那次的教训）。
- `EmotionVoiceStyles` 随阶段 2 一起补 key。
- `check_expression_names.cjs` / `live2d_motion_check.cjs` 无需改，但要跑一遍确认空表情集不会「空集合恒真」误报通过。

## 5. 阶段 2 预备方案：自制 Live2D 模型（等分层 PSD）

来源与结论：`tsunehimatoi/psd2live` v2.0.2（GPL-3.0，Compose Desktop，Windows 便携版自带 JRE）。输入**分层 PSD** → 输出 `.moc3` + `.model3.json` + 贴图/物理/动作（可选 `.cmo3`）。自动做网格、变形器链、头身参数、表情参数、待机/眨眼/点头/摇头动作、头发与眼睛物理；不依赖官方 Cubism SDK。CLI：`--input <psd> --output <dir>`，另有 `--atlas`、`--mesh-spacing`、`--head-strength`、`--body-strength`、`--no-cmo3`、`--no-moc3`、`--upscale`、`--lang zh|en|ja`。

⚠️ 导出目标默认 Cubism 5.0，App 用的是 Cubism **4** Core → 必须显式降到 **4.0**。

前置条件（缺一不可，这是当前真正的卡点）：
- 分层 PSD：眼白/瞳孔/上睫毛分开、有**张口**图层、前后发分离且留够遮挡补全余量、身体直立、图层效果已栅格化。
- 官方 STATUS 里「头发拆分与遮挡补全」自评**不可用**，被遮挡区域需要图像生成能力补全 —— 本项目没有。

执行步骤（PSD 到手后）：
1. 跑 psd2live CLI 生成模型（导出目标锁 4.0）。
2. 写 `tools/setup_firefly_model.py`（从 `setup_dafeiyu_model.py` 抽通用逻辑）：ASCII 化文件名、贴图「长边 ≤ 4096」降采样、补 Motions/Expressions/EyeBlink/LipSync 注册表 → `assets/live2d/models/firefly/`（`.gitignore` 已忽略）。
3. `live2d_dump_parts.py` 出摸头命中盒 → `live2d_make_pat.py` 生成摸头动作 → 按真实表情名填表情集 + `EmotionVoiceStyles` → 跑三个校验脚本 → 补 `ModelCredit` 署名。

替代输入：Cubism 模型文件夹（`.model3.json` + `.moc3` + `textures/`）或 VTube Studio 皮套。不要 `.cmo3` 工程、不要 Spine 模型（HSR 官方是 Spine，转不成 Live2D）。

## 6. 版权与来源

- 模型美术归作者 → 模型不入库（`.gitignore` 覆盖 `assets/live2d/models/`），本机安装，App 内用 `ModelCredit` 署名。
- 角色 IP 与游戏语音归米哈游 → 参考音频放 `assets/` 随 APK 分发，与银狼现状一致；项目定位个人使用，公开/商用分发有风险。

## 7. 文档、版本与提交

- `docs/characters.md`（两个角色 → 三个 + 步骤更新）、`docs/live2d.md`（新增流萤一节 + 阶段 2 预案）、`docs/tts.md`（补参考音频规格）、`README.md`（角色列表 + 鸣谢）。
- `.release-notes-v1.8.0.md`；`app/build.gradle.kts`：`versionCode 16→17`、`versionName 1.7.0→1.8.0`。
- archify：功能变更，发布前更新架构图（产物放 `docs/`）。
- `plan10.md` 并入同一次提交。

## 验证

- `.\gradlew.bat :app:compileDebugKotlin`
- `python tools/make_ref_voice.py --list` → 正式生成 → 断言 base64 < 10MB / 时长 ≈15s / 22050 单声道 16bit
- `node tools/check_expression_names.cjs`、`node tools/live2d_selftest.cjs`
- 手动三条：首页出现第三张卡 → 准备页背景/头像正确 → 通话页模型缺失回落静态头像而不是白屏且 TTS 是流萤音色

## 不做什么

不抓模型、不把模型入库、阶段 1 不碰 Live2D 模型、阶段 1 不写未验证的安装脚本、不动 `sourse/`。
