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
