# Live2D 形象

> 本文件是 [主自述文件](../README.md) 的技术分册，内容偏实现细节与踩坑记录。

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

### 模型动作图谱

银狼模型自带的动作只有 4 个文件 / 3 个组，而且**全是大招特效循环**：

| 组 | 文件 | 时长 | 循环 | 实际内容 |
|---|---|---|---|---|
| `Transform` | m_transform_1 | 2.333s | 是 | 变身·进入：摘眼镜、变身开、划卡特效 0→10、划卡 L/R、迈腿、人物变暗 |
| `Transform` | m_transform_2 | 2.333s | 是 | 变身·还原：戴回眼镜、变身关、划卡特效 10→20（其余 22 条曲线与 `_1` 逐值相同） |
| `AngryLoop` | m_angry_loop | 1.667s | 是 | 生气 + 眼镜火焰/高光 + 流泪 |
| `Sleep` | m_sleep | 4.0s | 是 | 睡觉：Z 字 ×3 + 鼻涕泡 + 闭眼低头 |

三个必须知道的结论：

1. **没有 `Idle` 组**。pixi-live2d-display 的自动待机写死找 `Idle` 这个名字
   （`groups={idle:"Idle"}` → `startRandomMotion(this.groups.idle, IDLE)`），
   找不到就一条都不播 —— 这是 `CFG.states[*].motion` 全为 `null` 的根本原因。
2. **4 条全是 `Loop: true`**，没有「播完就停」的演出动作，直接播会永久循环。
3. `Transform_1/2` 是同一段演出的**前后两半**（原意连着播），
   在组里随机单播一条会「变到一半」。

不靠动作文件，角色本来就在动：运行库内置的**呼吸**（对 `ParamAngleX/Y/Z`、
`ParamBodyAngleX`、`ParamBreath` 做**加性**写入，周期 3.23~15.53s）、**眨眼**、
bridge.js 的**视线跟随**，以及 **103 组物理**（50 输入 → 185 输出：双马尾、
袖子、裙子、蝴蝶结、兽耳、睫毛、翅膀……全由头身角度驱动）。

### DeepSeek酱 模型（大肥鱼）

形象从最初的「DS鲸鱼娘 c_0120」换成了 B 站 UP 主「狐宫静」无偿分享的「大肥鱼」
（VTube Studio 皮套）。**profile id 仍然是 `deepseek`** —— 它是「角色 ↔ 形象」的绑定键
（`BuiltInCharacters.live2dProfileId`，自定义预设也会把这个字符串存进数据库），
跟着模型改名会让已经存在的预设找不到档位。

安装：`python tools/setup_dafeiyu_model.py --zip <大肥鱼.zip>`。
模型目录不入库（见下文「资源不入库」），所以**脚本才是这次改动的唯一事实来源**。

与银狼完全不同的形态，差异全部由 profile 承载（下游逻辑零改动）：

| 维度 | 银狼 | DeepSeek酱（大肥鱼） |
|---|---|---|
| 动作组 | `Transform` / `AngryLoop` / `Sleep`（**无 `Idle`**） | **无 `Idle`**（见下第 3 条）+ `Action` ×5（吃饭 / 吃token / token转 / sleep / Scene1） |
| 一次性演出 | `TransformOnce`（变身，接通 + 挂断都播） | 无 → `hasTransform = false`、`transform.enabled = false` |
| 表情数 | 15（含 4 个姿势 key） | 16，但**全是 VTS 道具开关**（钢盆 / token / 气泡 / 滑动变阻器 ×6 / 星星眼 / 爱心眼 / 用户彻底怒了）→ 白名单只剩 11 个 |
| 眯眼 / 眉毛上下参数 | 有 | **不存在**（已扫 moc3 确认）→ 通道表去掉 `squint` / `brow`，只留 `browForm` |
| 笑眼参数 | 有 | **有**（与上一版模型相反，`smile` 通道保留） |
| 身体摆动 | 可程序化驱动 | **物理输出**（`ParamBodyAngleX/Y/Z` 权重 100）→ 去掉 `sway` 通道，交给物理 |
| 摸头命中盒 | 13 个语义部件 | **显式指定 3 个**（`Part2` 前发 + `Part3/4` 左右眼）+ `padRatio` 0.45 |

三件必须知道的事：

1. **作者给的是 VTube Studio 模型，直接塞进 assets 播不了。** 它的 `model3.json`
   只有 Moc / Textures / Physics / DisplayInfo —— **没有 Motions 段、没有 Expressions 段**，
   而且 `EyeBlink` / `LipSync` 两个组是**空数组**（不补的话眨眼与口型整条失效）。
   pixi-live2d-display 只认注册过的条目，不注册就全部静默失效（pixi 找不到就忽略，不报错）。
   文件名还全是中文（`大肥鱼.moc3` / `exp/星星眼.exp3.json`），而 AAPT2 在 Windows 上对
   assets 里的非 ASCII 资源名支持不一致 —— 所以安装脚本干三件事：ASCII 化文件名、
   补全注册表、降采样贴图。
2. **贴图必须降采样。** 作者原图是 4096×2048 + 两张 **4096×8192**（共 34.7MB）。
   4096×8192 超过很多手机的 `GL_MAX_TEXTURE_SIZE`（常见上限 4096），上传失败的表现是
   **整块贴图变黑 / 模型不显示**；就算支持，三张加起来也是 ~290MB 显存。脚本按
   「长边 ≤ 4096」逐页等比缩小（→ 2048×4096），装完整个目录 10.9MB。
3. **没有 `Idle` 组是刻意的。** 作者自带的 `motion/idle.motion3.json` 写了
   `ParamAngleX`（头 yaw）与 `ParamAngleZ`：前者与「视线只来自 focus / 呼吸」的硬性约定
   冲突（见下文「视线」），后者与程序化待机层的 `tilt` 通道抢道。所以安装脚本不注册它 ——
   待机 = 程序化待机层 + 运行库呼吸 + 眨眼，与银狼同一路线。
   （银狼那档的 `Idle` 组是 `tools/live2d_make_idle.py` 生成的，而那个脚本目前只认银狼：
   它的动作定义与通道预算都写死了，且它写 `ParamBodyAngleX/Y` —— 在大肥鱼上是物理输出。）

⚠️ **这个模型一个面部情绪表情都没有**：16 个「表情」写的全是 `Param89~101` 这些道具
开关参数。所以 LLM 的 `[[e:…]]` 只能挑出「星星眼 / 爱心眼 / 用户彻底怒了」三条，
情绪表现力比上一版模型（44 个表情，含脸红 / 生气 / 哭 / 流汗 / 晕晕）弱很多 ——
这是换形象的代价，不是配置漏了。另外 `滑动变阻器`（第 1 个档位）的 `Parameters` 是
**空数组**，触发它不会有任何变化，所以刻意没进标签表（另外 5 个档位在）。

- 署名：模型作者「狐宫静 @bilibili」（`space.bilibili.com/261589131`），展示在
  **主板块底部**（`BuiltInCharacter.credit`）。刻意放在舞台区**之外**：舞台被 WebView
  占满，署名放进去就是点不动的死链接，还会和摸头的手势抢同一次触摸。

⚠️ **写参数前先查物理表**：大肥鱼 moc3 的 138 个参数里有 **90 个是物理输出**，
物理每帧都会覆盖它们，动作曲线或参数写上去等于没写。可安全驱动的是
「物理输入 / 空闲」通道，例如 `ParamBrow*Form`、`ParamEye*Smile`、`ParamMouthForm`、
`ParamAngleZ`、`ParamBreath`；手、耳、蝴蝶结、吊饰、头发、裙子、尾巴、身体
全是物理输出，**做不了程序化动画**。

### 程序化待机层

模型没有待机动作，但画面里不能是个静止立绘，于是 `bridge.js` 在
`afterMotionUpdate` 里每帧补一层微动作（配置在 `CFG.idle`）。
之所以不用动作文件：要的是**跟着通话状态走**的姿态，而运行库的机制是
「没动作时随机播一条 `Idle`」——它不知道通话状态。

| 状态 | 表现 |
|---|---|
| 聆听 | 挑眉 + 笑眼 + 屏息 + 轻微右倾 |
| 思考 | 皱眉 + 眯眼 + 左倾（歪头） |
| 说话 | 呼吸加深 + 眉毛随语气抬起 |
| 结束 | 眉眼下垂 |

- 只写**物理输入**参数：眉毛、眼形、`ParamMouthForm`、`ParamBreath`、
  `ParamAngleZ`、`ParamBodyAngleZ`（不碰 `ParamMouthOpenY`，那是口型的地盘）
- 兽耳是物理**输出**、不能直接动，但眉毛/嘴形/眼睛形状会经物理链带动它
  （`ParamBrowLForm` → `Param3` → `ParamnekoL*`）—— 所以「做微表情」和
  「抖一下耳朵」是同一件事：每 4~9s 一次 0.28s 的短脉冲就是靠这条链
- 微漂移周期刻意避开呼吸的 3.23 / 3.53 / 5.53 / 6.53 / 15.53s，
  否则两者会共振成「机器人抖动」
- 有情绪表情时状态姿态自动压到 30%，避免「生气脸配聆听微笑」
- 真机调参：`L2D.setIdleEnabled(false)` 可整体关掉待机层做 A/B 对比，
  `L2D.debug()` 会连同当前待机值一起返回

### 偶发待机动作（Idle 组）

程序化层负责"一直在线"的微表情，**大一点的自发姿态**（转头张望、换重心）
用动作曲线更自然，所以另外生成 3 条注册进 `Idle` 组：

| 文件 | 时长 | 内容 |
|---|---|---|
| `idle_glance` | 4.5s | 快速瞥一眼旁边（像听到动静） |
| `idle_lookaround` | 7.5s | 慢慢左右张望一圈 |
| `idle_stretch` | 8.0s | 换重心松一下身子 |

- **故意非循环**（`Loop: false`）。运行库的机制是「一条播完立刻随机抽下一条」，
  循环动作会让它永远停在同一条上；非循环 + 末尾留一段静止，才有"偶尔动一下"的节奏。
- **通道与程序化层不重叠**，否则会被覆盖（那层写在 `afterMotionUpdate`，晚于动作更新）：

  | 谁 | 管哪些参数 |
  |---|---|
  | 程序化层（bridge.js） | 眉毛 / 眼形 / 嘴形 / 呼吸 / `ParamAngleZ` 头侧倾 / `ParamBodyAngleZ` 身体摆 |
  | 动作文件（本组） | `ParamAngle{X,Y}` 头 yaw·pitch / `ParamBodyAngle{X,Y}` 腰 / `ParamEyeBall{X,Y}` 眼球 |

- 模型目录不入库，所以**脚本才是改动的唯一事实来源**：
  `python tools/live2d_make_idle.py`（幂等，可反复执行；`--dry-run` 预览、`--remove` 撤销）。
  备份写到 `app/build/live2d-idle-backup/` —— 绝不能放回 `assets/`，
  否则会被打进 APK 一起分发（水印那次已经踩过一次）。
- 校验：`node tools/live2d_motion_check.cjs`。用**真 Cubism Core**
  （asm.js 自包含，不需要 `.wasm`）离线加载 moc3，核对参数是否存在、值域是否越界
  （按 60fps 采样，避免把贝塞尔控制点误判成越界）、段计数是否自洽，
  并与 `physics3.json` 交叉核对"没写到物理输出参数上"。

顺带查出**作者原文件**的三个问题（工具只告警，不改动别人的文件）：

1. `m_transform_2` 的划卡特效 `Param172` 写成 10→20，而 moc3 上限是 **10** ——
   那半段特效一直贴在最大值上，看起来"没在动"（`m_transform_1` 的 0→10 才在范围内）。
2. `m_angry_loop` 里 `Param143/144/148` 写到 2（上限 1）、`Param145` 写到 20（上限 10）、
   `Param55` 写到 10（上限 5），末尾一段同样被 clamp。
3. `Transform` 与 `AngryLoop` 都有曲线写着**物理输出**参数
   （`ParamAngleX2`、`ParamBodyAngleX3` 等），那些曲线会被物理覆盖。

### 视线（`model.focus()` 的坑，踩了两次）

两个角色都「一直盯着左上方、不像在看你」—— 根因不在动作文件，也不在模型数据，
而在 `model.focus()` 的**参数语义**：

```js
// 运行库 Live2DModel.focus(x, y)：参数是**世界坐标里的一个点**
focus(t, e, i) {
  this.toModelPosition(...)                 // worldTransform.applyInverse → local → 画布像素
  let s = px / originalWidth  * 2 - 1,      // → [-1,1]
      r = py / originalHeight * 2 - 1,
      a = Math.atan2(r, s);
  this.internalModel.focusController.focus(Math.cos(a), -Math.sin(a), i)
}
```

它只取「画布中心 → 该点」的**方向**，**模长恒为 1** —— 传进去的数值大小完全不影响力道，
只影响方向。这个性质让两种"看起来都合理"的写法**都错**，而且错法完全不同：

| 写法 | 实际传进去的 | `focusController` | 真机观感 |
|---|---|---|---|
| ① 旧实现：直接传归一化偏移 `(±0.09, ±0.05)` | 几乎就是世界原点 | `(-0.707, +0.707)` | **满偏盯左上角**（`ParamAngleX -21°`、`ParamEyeBallY +0.707`） |
| ② 只把偏移换算成世界坐标点 | 画布正中心 → `atan2(0, 0) = 0` | `(1, 0)` | **满偏盯右边**（两只眼珠全挤在眼白右侧） |

② 之所以也是满偏：**画布正中心是这个 API 的奇点** —— 方向退化成
`(cos 0, -sin 0) = (1, 0)`。换句话说**「看正前方」用公开 API 根本表达不出来**，
它天生只能表达"朝哪个方向看"。

更糟的是 ① 的时候 `applyState()` 里那句「视线瞬时归位」`model.focus(0, 0, true)`
归的**是同一个左上角**，所以每次状态切换都"归位"到左上 —— 这就是为什么这个现象
看起来永远不变、且两个角色一模一样。

**最终修法**：`bridge.js` 的 `setGaze()` 直接写
`internalModel.focusController.focus(fx, fy, instant)` —— 那才是"归一化偏移"的原生单位
（`[-1, 1]`；`updateFocus()` 把它直接当 `ParamEyeBallX/Y` 的加量，再 ×30 加到
`ParamAngleX/Y`）。`fx = fy = 0` 就是正前方。公开 API 那条路只留给
"拿不到 `focusController`"的兜底。

> 教训：这类"数值一路正常、画面却不对"的问题，根因往往在**参数的单位 / 坐标空间**上，
> 而不是数值本身。更要紧的是 —— **同一个 API 的两种错误用法可以给出完全不同的表现**
> （① 盯左上、② 盯右边），光读代码推不出来，只有装到真机上看画面才能确认。
> `tools/live2d_selftest.cjs` 的 `[18] 视线` 一节现在锁住三件事：归位 = `(0, 0)`、
> 游移幅度 = 配置振幅、**没有走公开的 `model.focus`**。

顺带一提，`tools/live2d_motion_check.cjs` 的 `GAZE_PARAMS` 禁令（待机动作不许写
yaw / 眼球）依然成立 —— 那是另一个独立成因（`idle_glance` 写 `ParamAngleX=-9°`），
两者叠加才会显得"完全没在看你"。

### 内容盒（`contentBounds()` 为什么要裁到画布）

`CallScreen` 按「内容盒宽高比 vs 舞台宽高比」选适配边（`fitBy`），所以内容盒的大小
直接决定角色在舞台里多大。而 `contentBounds()` 取的是所有 drawable 包围盒的
3%~97% 分位 —— 这个 trim **拦不住"藏在画布之外"的道具**：

VTS 风格的模型（大肥鱼）把道具（钢盆 / token / 气泡 / 米饭…）藏在画布外 —— 靠开关
参数把网格挪走，**不是**靠透明度。大肥鱼 108 个网格里有一批被摆到画布上方
（y 到 -976，而画布高才 5348），内容盒被撑成 **3511×5925 —— 比画布还高**。于是：
宽高比 0.593 < 舞台 1.435 → 走 `height` 适配 → 用被撑大的高度算缩放 →
**角色缩成小小一只杵在中间**（实测只有银狼的一半宽）。

修法是把盒子**裁到画布范围内**：画布之外的网格本来就渲染不出来，"可见内容"必然是
画布的子集。这对银狼**零影响** —— 它的盒子 597..3404 × 561..2385 本来就在画布
4000×2600 内，`content` 上报值改动前后逐字一致（`2807x1824@597,561`）。

> ⚠️ 别改成"先剔除 `opacity ≤ 0.01` 的网格再取分位"：分位是按网格**数量**取的，
> 剔掉隐藏网格会让分位点整体下移、盒子在顶部变紧 —— 实测把银狼的发顶和蓝色天线
> 切掉了（y0 从 561 抬到 789）。那个 3% 余量本来就在替少数几根发丝兜底，动不得。
> `live2d_selftest.cjs` 的 `[19]` 有一条守卫专门拦这个改法。

### 加载超时（为什么从"页面开始加载"起算）

`Live2DView` 有一道超时兜底（8s → **15s**）：迟迟收不到 `ready` / `error` 时把状态置成
`ERROR`，UI 回退到静态头像，避免一直空白。

这个超时是**墙钟**时间，而它统计的那一段里除了页面加载，还可能夹着主线程被整体卡住的
时间 —— 「装完 APK 后第一次启动」那条路径上主线程被连着卡了 14 秒
（`Choreographer: Skipped 861 frames`：WebView 首次初始化 + Compose 首次组合 + JIT），
超时预算在页面还没开始加载时就耗光了。现象是**更新后第一次通话舞台空白**、
第二次通话又正常，很容易被误判成"模型坏了"。

所以计时改成从 `onPageStarted`（页面真的开始加载）起算：WebView 的 `onPageStarted`
自增 `loadGeneration`，而 `LaunchedEffect(controller, controller.loadGeneration)`
会因此重启计时。真机验证：安装后首次启动直接进通话，角色正常出现。

### 变身过场（TransformOnce）

模型自带的 `Transform_1/2` 是同一段演出的前后两半，但都是 `Loop: true`，
直接播会一直循环。生成器抄了两份**只改 `Meta.Loop`** 的副本
（`transform_in` / `transform_out`，生成时断言"除 Loop 外逐字段相同"），
注册成 `TransformOnce` 组，并接成一次性序列：

- **接通**：播 `full`（进入 → 还原，约 4.7s），正好盖住"等首句"的空白，
  由 `CallScreen` 在模型就绪后触发一次（`entrancePlayed` 保证每通电话只播一次）
- **挂断**：播 `out`（只"还原"，约 2.3s）。ENDED 时 NavGraph 会立刻跳历史页、
  `Live2DView` 也会被暂停，所以三件事必须配套：
  ① `CallScreen` 在 ENDED 触发 `playTransform("out")`；
  ② 过场期间**不暂停渲染**（否则动画停在半路）；
  ③ NavGraph 跳转前等 `HANGUP_TRANSFORM_MS` —— 与触发共用同一个常量，避免两处漂移
- 序列靠运行库的 `motionFinish` 事件推进。注意 **`Idle` 组的动作播完也会触发它**，
  所以回调里必须确认 `state.currentGroup` 是 `TransformOnce`，否则序列会被待机动作提前推进
- 播放期间程序化待机层**整体归零让位**（变身动作自己也画眉毛/眼睛，
  而待机层写在 `afterMotionUpdate`，不让位就会把它盖掉）
- **开关**：设置里「接通/挂断变身过场」（`live2dTransformEnabled`，默认开）。
  关掉时两端都不播，NavGraph 也不再延迟跳转，保持原来的即时手感。
- **自动恢复**分三层：
  1. 正常路径本来就回正 —— `_2` 自己会把眼镜戴回去、变身关掉，
     而且动作权重淡出会把参数带回基准值；
  2. 参数级兜底（`CFG.transform.reset`）—— 序列结束**或被打断**时，
     把变身相关参数一次性写回 moc3 默认值（`key9=1` 正常眼镜、`key11=0` 变身关、
     划卡特效/划卡 L·R/迈腿/人物变暗 归零）。切后台、WebView 暂停、秒挂断这些
     打断场景下，"指望动画一定播完"不可靠；写一次就够（写在 `afterMotionUpdate`
     会被 `saveParameters()` 记进基准值）。
  3. 另有**看门狗**：每条动作给 3.2s 预算，超时强行收尾 —— 防止序列卡住时
     待机层永久处于"让位"状态
- 仍然保留作者那份"越界"的原数据：`transform_out` 的 `Param172` 写着 10~20
  而 moc3 上限是 10，所以后半段的划卡特效会一直贴在最大值上（校验工具会告警）

### LLM 情绪表情

形象的情绪不只跟着通话状态走，还可以由 LLM 自己决定：

```
系统提示词追加「表情 / 动作标签」协议（仅 Live2D 开启时注入）
  ↓  LLM 回复：[[e:生气]]喂，你这也太离谱了吧。  /  [[m:抱胸]]行，随你。
流式解析 ExpressionTagParser（[[e:…]] 情绪、[[m:…]] 姿势，同一个正则两条通道）
  ├─→ UI 展示 / TTS 合成：剥掉标签的干净文本（标签不会被念出来）
  └─→ 回调 Live2DExpression → ExpressionCue
        ↓
CallScreen 下发 Live2DController.setExpression()，保持到本轮说完再回落
```

几个必须这么做的原因：

- **标签会被 chunk 切断**（`"[[e:生"` + `"气]]"`）。未闭合的标签整段扣住不显示，
  否则玩家会看到半截标签一闪而过，或者被 TTS 念出来。
- **提示词里只暴露短标签**。模型真实表情名是中文带编号且空格不统一
  （`01黑脸` / `02 脸红爱心` / `03 生气` / `月卡`），让 LLM 原样复述极易写错一个字符。
- **情绪是「覆盖层」**。thinking → speaking 的状态切换会重走 `applyState()`，
  若情绪只 `model.expression()` 调一次就会被 `resetExpression()` 冲掉，
  表现为「LLM 明明调了表情但脸没变」；因此 JS 侧记录 `_cue` 并在每次状态切换后重新应用。
- **保持时长分两段**，而不是固定 N 秒。标签在生成阶段就到了，而 TTS 要等整段回复
  生成完再合成参考音色；固定计时器会在角色刚开口时正好到期，现象是「表情闪一下就没了」。
  所以：① 生成/等待期一直挂着；② **进入说话态后再保持 `EXPRESSION_SPEAKING_HOLD_MS`
  （3.5s）就回落**。第 ② 段是必须的 —— 像 `04 晕 / 07 星星眼 / 06 0.0` 这类夸张脸
  从生成一路挂到整段话说完（十几秒）会显得很傻，真人也不会一边说话一边定格表情。
  另加最短 1.5s / 最长 30s 兜底。
- **首轮（开场问候）强制普通脸**：实测 LLM 打招呼几乎必然挑 `06 0.0`，挂满开场白
  特别傻（而提示词里的招呼示例恰好就是 0.0，等于我自己教的）。所以
  `CallViewModel.cueExpression` 在还没有任何助手消息时直接忽略标签 ——
  标签已在解析层剥掉，忽略它也不会被念出来。
- **状态表情一律留空**。占位表情会和 LLM 的选择撞车（`thinking` 曾占位 `06 0.0`，
  而 LLM 打招呼最爱的也是 `0.0`），触发成功也看不出变化，被误判成功能失效。
- **姿势与情绪分两条通道**。`外套关闭 / 抱胸手 / 捧心手 / 要饭手` 实质是手部与服装的
  状态切换（模型里各是一个 `key` 开关、互斥），不是瞬时情绪，所以用 `[[m:…]]`
  单独通道触发，`[[e:…]]` 留给情绪；两者都保持到本轮说完才复位。
  标签始终**最多 1 个**：表情与姿势二选一 —— 多条 exp3 会把同组 key 互相归零，
  同时下发的结果不可控。仍未开放：`10 吹泡泡`。

- **同一条标签现在还驱动声音**。`[[e:…]]` / `[[m:…]]` 的 key 会经
  [`EmotionVoiceStyles`](app/src/main/java/com/lv999call/app/domain/model/EmotionVoiceStyles.kt)
  查出一句语气修饰，拼进这一轮的 TTS 风格指令 —— 此前标签**只换脸不换声**，
  「表情变了而语气不变」等于表演只做了一半。详见
  [TTS 播放链路 → 风格提示词](tts.md)。

可调项：[`Live2DExpressions`](app/src/main/java/com/lv999call/app/domain/model/Live2DExpression.kt)
里每个角色的表情集（标签 ↔ 真实表情名 ↔ 情绪说明 ↔ few-shot 示例）、
`CallScreen.kt` 的 `EXPRESSION_MIN_HOLD_MS` / `EXPRESSION_MAX_HOLD_MS`（保持时长兜底）。

### 摸头互动（按模型部件现算命中盒）

**按住**形象的头 0.18s 再**滑动**揉几下，角色会做出被摸头的反应（松手即停）。
整条链路**全在 WebView 页面内**：

```
pointerdown / move / up（页面内监听）
  → 按住 ≥ pressDelay(0.18s) = 手放上去了，摸第一下
  → 之后每滑过 strokeDistancePx(48px) = 又揉一下（档位 +1）
  → 每次触发前先做头部包围盒命中判定 → CFG.pat 叠层 + PatOnce 动作
                              ↑
                头部部件的 getDrawableBounds AABB（触发那一刻现算）
```

**为什么是"按住 + 滑动"而不是轻点**：摸头是持续的接触动作，不是戳一下。
轻点时手指落下就走，舞台上任何一次误触都算摸头，还容易连点刷档位；
按住 0.18s 才认，既像"把手放她头上"，也天然滤掉误触。
**按够时间之前不做任何位移取消** —— 真人把手指放上去就会开始揉，180ms 内移动几十像素
太正常了，按位移取消会把正常操作误杀成"按了没反应"（真机踩过）。
计时用 `_idleTime`（跟渲染帧走）而不是 `setTimeout`：渲染暂停时不该继续计时，
自测里也能用 `tick()` 精确推进。页面加了 `touch-action: none`，
否则浏览器会把滑动解释成滚动/缩放，`pointermove` 会被抢走。

两个"必须这么做"的原因，都是踩出来的：

- **手势必须在页面内捕获**。WebView 是真实 View，绘制与触摸派发都在 Compose 画布之上 ——
  宿主在 Compose 里叠一层触摸层**收不到点击**（旧实现就是如此，现象是"点了没反应"）。
  放在页面内还顺带干掉了两套坐标系：手势与命中判定用的是同一个视口、同一套归一化。
- **命中盒必须按部件算，不能手调矩形**。Q 版银狼与半身立绘 DeepSeek 酱的头，位置与大小
  完全不同，同一个"内容包围盒上 42%"不可能同时对；而且静态矩形在模型呼吸、转头、换布局
  之后必然偏。现在取**头部部件**下所有 drawable 的包围盒，按 `layout()` 的变换换算到
  视口，再外扩 `padRatio`（8%）：天然贴合、天然跟随姿态，**不需要任何标定**。
- **坐标空间别搞混**。Core 的 `drawables.vertexPositions` 是**模型单位**
  （原点在画布中心、y 向上、每单位 `canvasinfo.PixelsPerUnit` 像素，银狼这档是 7000），
  而 `layout()` 用的是**画布像素**（左上原点、y 向下）。第一版拿单位去套像素公式，
  算出来的盒子只有 0.00009 宽、整块跑到屏幕外 —— 现在统一走
  `internalModel.getDrawableBounds(i)`，与 `contentBounds()` 同源。
- **头发不进命中盒**。实测银狼后发 1673×1686 px、上一版 DeepSeek 酱头发 2378×2790 px，
  都会一路垂到身体，混进来"点她大腿也算摸头"，连摸四下还会让她生气。
  所以 `headParts` 只取**贴着头的部件**（脸 / 五官 / 头饰 / 眉 / 眼 / 嘴 / 耳 / 角）。

部件表从哪来：模型都没有 `HitAreas`，而 Core 的 `Drawables.parentPartIndices` /
`Parts.parentIndices` 是唯一的"网格 → 部件"映射（框架层的 `getDrawableParentPartIndex`
不在包里）。部件 id 与中文名的对应只存在于 `cdi3.json`，所以这一步放在离线：

```bash
python tools/live2d_dump_parts.py            # 列出选中/排除的部件，人工核对
python tools/live2d_dump_parts.py --json     # 输出可粘贴进 bridge.js 的 headParts
```

换模型或换版本时重跑它，**不要手写 id**。运行时另有两道过滤：部件与网格
`opacity ≤ 0.01` 的替换件（`猫猫耳` / `发型D2` / `兔兔耳` 这类预设开关）不参与，
否则没启用的那套会把盒子撑歪。拿不到 Core 数据时（老运行时、模型异常）回落到
`CFG.pat.hit` 那个兜底矩形 —— 宁可粗糙，也不要"点了没反应"。

**关键词匹配不成立时走显式指定**（大肥鱼就是这种）。它是 VTS 风格的绑定，部件名是
`角度XY-` / `部件15` / `大肥鱼.psd` 这种编辑器占位名 —— 关键词表只会误命中
`角度XY-` 里的「角」，最后选出 4 个部件（其中两个还是隐藏件）。所以
`live2d_dump_parts.py` 里有一张 `MANUAL_PARTS` 表，改成按**位置**定：
把每个部件下所有 drawable 的并集包围盒算出来、归一化到内容包围盒（0~1，左上原点），
可见部件里只有三个在上半身 ——

| 部件 | 名称 | 归一化包围盒 |
|---|---|---|
| `Part2` | 前发（刘海） | x 0.155~0.691  y 0.096~0.423 |
| `Part3` | 左眼 | x 0.244~0.378  y 0.305~0.390 |
| `Part4` | 右眼 | x 0.468~0.605  y 0.305~0.389 |

（`Part5`「大肥鱼.psd」覆盖整个内容盒、51 个网格，是主容器，**不能**选；
`Part6/7/9/10` 透明度为 0，运行时的 opacity 过滤会剔除。）
并集只到 y 0.42 而头一直延伸到内容盒顶部，所以这一档的 `padRatio` 取 **0.45**
（银狼 / 上一版模型是 0.08），不放大就"只摸得到眼睛"。

> 复算方式：`node tools/live2d_dump_part_bounds.cjs <目录名>` 会把每个部件的并集
> 包围盒（归一化到内容包围盒，与 `CFG.pat.hit` 同一个坐标空间）连同物理角色一起打出来。
> 它用真 Cubism Core 离线读 moc3，按 `drawables.parentPartIndices` 归组；
> 注意顶点是**模型单位**，换算到画布像素要**乘** `canvasinfo.PixelsPerUnit`（不是除），
> 原点在 `CanvasOriginX/Y`。这个乘除搞反过一次：算出来所有部件的包围盒都塌成画布中心一个点。

调试：`window.L2D.debugPatHit(true)`（CDP 亦可）会在舞台上画出**实际参与判定**的红框，
框里写着 `fallback` 就说明走的是兜底矩形；`window.L2D.debug().pat` 里同时给出
`modelBox`（模型坐标）与 `box`（视口归一化）两份数值。

**摸头不产生语音**：不进历史、不进记忆、不影响对话。它是一次纯视觉的即兴互动 ——
旧实现往下一轮系统提示词里注入过一段"玩家刚刚摸了一下你的头"（全局单例标记，
挂断不清、跨角色串味、`刚刚`还可能是几分钟前），已整条删除。

#### 大姿态走动作文件，脸走程序化层

程序化叠层的幅度是"微表情"级别（tilt 3°），读起来更像"轻轻歪了下头" ——
真正的大姿态走**动作文件**：

```bash
python tools/live2d_make_pat.py       # 生成 pat_lv1..lv4 + 注册 PatOnce 组
node tools/live2d_motion_check.cjs    # 校验（段结构 / 值域 / 通道预算 / 组齐全）
```

**通道预算是最要紧的约束**：`applyIdle()` 与 `applyPat()` 每帧写的是同一批通道
（brow / smile / squint / mouthForm / breath / tilt / sway），动作文件写它们必然被覆盖。
摸头动作因此只用 `ParamAngleY` 与 `ParamBodyAngleX/Y` —— 与现有 `idle_nod` / `idle_shift`
同一个预算。分工是：**动作管大姿态，程序化层管脸**（脸红 / 眯眼 / 眉毛）。
这比"演出期间让待机层整体让位"更好：让位会把那张脸一起掐掉。
（DeepSeek 酱的 `ParamBodyAngleX/Y/Z` **全是物理输出**，那一档只能动头。）

`PatOnce` 用 `FORCE` 优先级播放，抢占运行库正在自动播的待机动作；
但**变身过场期间不抢** —— 那套演出在写同一批通道，插进去只会把过场顶坏。

#### 连点档位与"不高兴"

| 档 | 触发 | 动作 | 银狼 / DeepSeek 酱表情 |
|---|---|---|---|
| 1 | 第 1 下 | 头下沉 6.5° 后慢慢回弹 | `02 脸红爱心` / `脸红` |
| 2 | 第 2 下 | 下沉 10.5°，回弹带一次小过冲 | `02 脸红爱心` / `脸红` |
| 3 | 第 3 下 | 下沉 13.5° 压住不回弹 + 身体下沉 | `05 ＞＜` / `流汗` |
| 4 | 第 4 下起 | 猛地抬头过冲 +2.5°（甩开） | `03 生气` / `生气` |

- **一串接触**：相邻两次"摸"的间隔不超过 `comboWindow`（5s）—— 按住期间每滑过
  `strokeDistancePx` 算一次，松手后再按住也接着算；静置超过 5s 就重新从第 1 档开始。
  冷却只有 0.2s —— 它只用来吞掉"同一次接触被识别成两下"的抖动，**不能**拦掉连摸，
  否则第 4 档永远摸不到（旧值 0.6+duration ≈ 1.5s 就有这个问题）。
- **第 4 档之后挂 9s 的"不高兴"**：生气脸保持，姿势偏向"别过头去"（歪头 + 眉毛压低）。
  这是"她记得被摸过"的唯一表达方式 —— 摸头不出声、不进历史、不进记忆。
- 档位表在 `bridge.js` 的 `CFG.pat.tiers`（动作序号 / 时长 / 表情 / 保持时长 / 幅度倍率），
  加角色只改配置，不用碰逻辑。

### 资源不入库

`lib/` 与 `models/` 已被 `.gitignore` 排除，原因：

- 模型美术版权属于模型作者，并非本项目所有，公开分发存在法律风险
- Live2D Cubism Core 为专有组件，仅授予「作为应用一部分」的分发权

因此本仓库只包含自研代码（`index.html` / `bridge.js`），
第三方资源请用 `tools/setup_live2d_assets.sh` 在本地获取。

### 使用自备模型

1. 把模型放到 `app/src/main/assets/live2d/models/<your-model>/`
2. 在 `bridge.js` 的 `PROFILES` 里加一档（照抄 `silverwolf` 或 `deepseek` 那档改），
   再把该档 id 填进角色的 `live2dProfileId`、模型路径填进 `modelPath`；
   调试时也可直接传参：`Live2DView(modelPath = "...", profileId = "...")`
3. 若模型口型参数不是 `ParamMouthOpenY`，调整该档的 `lipSyncParams`
4. ⚠️ **先查 `physics3.json`**：物理输出参数每帧都会被物理覆写，写进去等于没写。
   待机层能安全驱动的只有「物理输入 / 空闲」通道（`ParamBrow*Form`、`ParamMouthForm`、
   `ParamAngleZ`…）—— DeepSeek酱 那档就去掉了 `brow`/`squint`/`sway`
   （前两个参数在**大肥鱼**里不存在，后一个是物理输出）；反过来 `smile` 在大肥鱼里
   **存在**，所以那一档保留它（上一版 DS鲸鱼娘模型里它不存在 —— 换模型时这类
   通道表要重新对着 moc3 核一遍，不能照抄）
5. 在 `Live2DExpressions` 里加一套该角色的表情 / 姿势，然后跑
   `node tools/check_expression_names.cjs` 校验名字与模型文件是否对得上
6. 若没有「变身」这类一次性演出，把 `hasTransform` 设为 `false`
   （否则挂断会白等一段过场），并在该档里 `transform.enabled = false`
7. 跑 `python tools/live2d_dump_parts.py --model <your-model>` 生成摸头命中盒的
   `headParts`（关键词不适用时按上面的 `MANUAL_PARTS` 显式指定），再跑
   `python tools/live2d_make_pat.py --model <your-model>` 生成摸头动作
8. 收尾校验：`node tools/live2d_motion_check.cjs <目录名>`（它默认只查内置角色
   用到的模型，见脚本头部的「校验范围」）

> 自备模型同样在 `.gitignore` 覆盖范围内，不会被误提交。

### 剔除模型自带的水印

网上下载的模型常带署名水印（画序压在全部角色图层之上的贴片，永远可见）。
本项目当前用的银狼模型就带两块，已剔除，脚本留在仓库里可复现：

```bash
python tools/live2d_strip_watermark.py --model-dir app/src/main/assets/live2d/models/silverwolf --dry-run
python tools/live2d_strip_watermark.py --model-dir app/src/main/assets/live2d/models/silverwolf
```

- **坐标不是估的**：用 Cubism Core 解析 `.moc3` 得到「部位名 → drawable → 顶点 UV bbox → 像素矩形」。
  当前两块分别是 `槿絮水印.png`(texture_00 `x17-1016,y17-1463`，画序 373/375)
  与 `夜墨ww黑色.png`(texture_01 `x2192-3170,y1936-2852`，画序 374/375)。
- **安全性**：已逐个 drawable 核对，两个矩形区域内只被水印自己的 UV 引用，
  没有任何角色部件采样到；实测擦除后「矩形外被改动像素 = 0」。
- **换模型要重新解析**，坐标不能照抄。
- 脚本默认把原图备份成 `*.png.orig`，可回滚。

### 自测

WebView 内的 JS 无法用 Android 单元测试覆盖，可用附带的自测脚本验证
状态机与口型链路（mock PIXI/DOM 直接驱动 bridge.js）：

```bash
node tools/live2d_selftest.cjs         # 状态机 / 口型注入 / 情绪表情 / 布局 / 视线 / 摸头命中盒 / 容错
node tools/live2d_fallback_test.cjs    # 资源缺失时的降级上报
node tools/live2d_motion_check.cjs     # 动作文件：段结构 / 值域 / 通道预算 / 视线（内置角色的模型）
node tools/check_expression_names.cjs  # 表情白名单与模型文件是否对得上
python tools/setup_dafeiyu_model.py    # 安装「大肥鱼」（ASCII 化 + 贴图降采样 + 补注册表）
python tools/live2d_dump_parts.py      # 摸头命中盒用的头部部件表（从 cdi3.json 生成）
python tools/live2d_dump_part_bounds.cjs <目录名>  # 按位置列部件包围盒（关键词失效时用）
python tools/live2d_make_pat.py        # 摸头动作文件 PatOnce（4 档）
bash tools/audio_pipe_test.sh          # AudioPipe：唤醒/背压/打断/环形回绕
python tools/memory_migration_check.py # Room 3→4 迁移：结构/数据存活/游标初始化（23 项）
```

> `live2d_selftest.cjs` 里 `[18] 视线` 一节锁住的是 `setGaze()` 的坐标换算 ——
> 这条断言的价值在于：旧实现下 `model.focus(0, 0)` 被解成"舞台左上角"，
> 角色满偏盯着左上角，而**数值读回一路正常**（详见「视线」一节）。

> `check_expression_names.cjs` 的价值在于：模型表情名少写一个空格 pixi 只会静默忽略，
> 现象是「表情没变」且没有任何报错，肉眼审查根本发现不了。

> `audio_pipe_test.sh` 直接拿 Gradle 编出来的 `.class` 在桌面 JVM 上跑 ——
> `AudioPipe` 是纯 JDK 实现（不碰 Android API），测的就是真正进 APK 的那份代码。
> 换掉 `PipedInputStream` 那个坑就是它逮出来的（见下文「TTS 播放链路」）。

> `memory_migration_check.py` 用 Python 标准库 `sqlite3` 造一个 schema=3 的库并跑一遍
> `MIGRATION_3_4`（见「长期记忆 → 数据模型」）。同一类问题的共同点：**迁移写错不会立刻报错，
> 而是表现为「升级后莫名多跑了几十次 LLM」或「用户历史没了」**，只能靠断言逮住。

### 许可提醒

- 本地获取的 Haru 为 **Live2D 官方示例模型，仅用于技术验证**，
  请勿随产品分发或商用
- Live2D Cubism Core 受 Live2D 独立授权条款约束，
  商用达到一定规模需购买授权
- 本项目定位个人自用；若要公开发布，请确保对所用模型拥有合法授权

详见 [`app/src/main/assets/live2d/LICENSES.md`](app/src/main/assets/live2d/LICENSES.md)。
