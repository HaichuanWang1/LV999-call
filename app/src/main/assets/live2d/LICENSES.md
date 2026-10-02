# Live2D 第三方资源与许可说明

> **本目录下的 `lib/` 与 `models/` 已被 `.gitignore` 排除，不随仓库分发。**
> 请通过 `bash tools/setup_live2d_assets.sh` 在本地获取，或放入自备模型。
> 资源缺失时 App 仍可正常运行，通话界面会自动回退到静态头像。

## 为什么这些资源不入库

| 资源 | 版权归属 | 入库风险 |
|------|---------|---------|
| Live2D 模型（`models/`） | 模型美术方（非本项目） | 公开分发可能侵犯著作权 |
| Live2D Cubism Core | Live2D Inc. | 专有组件，仅授予「作为应用一部分」的分发权 |
| PixiJS / pixi-live2d-display | 各自作者 | MIT，可自由分发 |

模型是二次元角色的美术资产，绝大多数模型（含 Live2D 官方示例模型）
**不允许自由再分发**。本项目对模型不主张任何权利，也不代为分发。

## 本地资源清单

### 运行时（`lib/`）

| 文件 | 组件 | 版本 | 许可 |
|------|------|------|------|
| `pixi.min.js` | PixiJS | 6.5.10 | MIT |
| `live2dcubismcore.min.js` | Live2D Cubism Core | 1.0.2 | Live2D Proprietary Software License |
| `cubism4.min.js` | pixi-live2d-display | 0.4.0 | MIT |

- PixiJS — https://github.com/pixijs/pixijs
- pixi-live2d-display — https://github.com/guansss/pixi-live2d-display
- Live2D Cubism Core — https://www.live2d.com/en/sdk/license/

> **Cubism Core 注意**：该文件属于 Live2D 许可协议中的
> 「可再分发代码（Redistributable Code）」，允许作为应用的一部分分发，
> 但使用需遵守 Live2D 条款；达到一定营收规模的主体需购买商业授权。
> 详见 https://www.live2d.com/en/sdk/license/

### 示例模型（`models/haru/`）

| 模型 | 来源 | 许可 |
|------|------|------|
| Haru Greeter | Live2D 官方示例模型 | Live2D Sample Model Terms of Use |

- 条款 — https://www.live2d.com/eula/live2d-sample-model-terms_en.html

> **Haru 仅供本地技术验证与开发调试**，请勿随产品分发或商用。
> 正式发布前必须替换为自有或已获授权的模型。

### 内置角色模型（`models/silverwolf/`、`models/dafeiyu/`）

这两个模型是**内置角色**的形象，同样处于 `.gitignore` 覆盖范围内，需本地获取。

| 模型 | 角色 | 作者 | 获取方式 | 作者声明 |
|------|------|------|---------|---------|
| `models/silverwolf/` | 银狼 | B 站 @槿絮OuO | 自备 | 按作者要求标注来源 |
| `models/dafeiyu/` | DeepSeek酱（大肥鱼） | B 站 @狐宫静（261589131） | `python tools/setup_dafeiyu_model.py --zip <大肥鱼.zip>` | 公开免费皮套；使用说明未附授权条款，按"署名 + 不商用转售"处理 |

`models/deepseek/`（上一版 DS鲸鱼娘，作者 B 站 @氵六青 11272072）**已经不参与运行** ——
profile 与 `BuiltInCharacters.modelPath` 都指向 `models/dafeiyu/` 了。目录保留只是
为了回滚（`python tools/setup_deepseek_model.py --remove` 可以卸掉）。

DeepSeek 酱的模型**必须用安装脚本**而不是手动拷贝，两版模型各有各的原因：

- 大肥鱼是 **VTube Studio 模型**：作者的 `model3.json` 里没有 `Motions` 段与
  `Expressions` 段，`EyeBlink` / `LipSync` 两个组还是**空数组**；文件名全中文；
  贴图是 4096×2048 + 两张 **4096×8192**（超过很多手机的 `GL_MAX_TEXTURE_SIZE`）。
  脚本负责补注册表、ASCII 化文件名、按长边 4096 降采样。
- 上一版 DS鲸鱼娘：`model3.json` 里没有 `Motions` 与 `Expressions` 段
  （44 个表情与 7 条动作都是"裸文件"），不补注册的话 LLM 调表情 / 播动作会全部静默失效。

两版脚本都会把中文文件名 ASCII 化 —— AAPT2 在 Windows 上对 assets 里的非 ASCII
文件名支持不一致。

### 角色头像

| 资源 | 来源 | 许可 |
|------|------|------|
| `res/drawable/deepseek_avatar.xml` | DeepSeek 官方仓库 `deepseek-ai/DeepSeek-Coder-V2` 的 `figures/logo.svg`（仅取鲸鱼 mark，官方主色 `#4D6BFE`） | MIT License, Copyright (c) 2023 DeepSeek |

> 官方 logo 为商标（trademark）；此处仅用于标识该角色对应 DeepSeek，
> 不暗示任何官方背书或关联。

## 使用自备模型

1. 把模型放进 `app/src/main/assets/live2d/models/<your-model>/`
2. 指定模型路径，两种方式任选：
   - 修改 `js/bridge.js` 顶部的 `CFG.modelUrl`
   - 或由 Kotlin 侧传参：`Live2DView(modelPath = "models/<your-model>/xxx.model3.json")`
3. 若模型口型参数不是 `ParamMouthOpenY`，
   调整 `CFG.lipSyncParams`
4. 按实际观感调整 `CFG.states` 中各状态对应的表情名

> 自备模型同样处于 `.gitignore` 覆盖范围内，不会被误提交。

## 合规提醒

本项目为个人自用，若你要公开发布：

- 确保对所用模型拥有合法授权
- 遵守 Live2D Cubism SDK 的商用条款
- 保留本文件所述的署名与许可信息
