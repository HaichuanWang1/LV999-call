# Live2D 资源第三方许可声明

本目录下的运行时与模型资源均来自第三方，版权归各自所有者所有。
使用、分发或商用前，请自行确认并遵守以下条款。

## 运行时（`lib/`）

| 文件 | 组件 | 版本 | 许可 |
|------|------|------|------|
| `pixi.min.js` | PixiJS | 6.5.10 | MIT License |
| `live2dcubismcore.min.js` | Live2D Cubism Core | 1.0.2 | Live2D Proprietary Software License Agreement |
| `cubism4.min.js` | pixi-live2d-display | 0.4.0 | MIT License |

- PixiJS: https://github.com/pixijs/pixijs — MIT
- pixi-live2d-display: https://github.com/guansss/pixi-live2d-display — MIT
- Live2D Cubism Core: https://www.live2d.com/en/sdk/license/
  - 该文件属于 Live2D 许可协议中的「可再分发代码（Redistributable Code）」
  - **重要**：Live2D Cubism Core 的使用需遵守 Live2D 的许可条款，
    达到一定营收规模的主体需要购买商业授权。详见官网。

## 模型（`models/haru/`）

| 模型 | 来源 | 许可 |
|------|------|------|
| Haru Greeter | Live2D 官方示例模型 | Live2D Sample Model Terms of Use |

- 示例模型条款: https://www.live2d.com/eula/live2d-sample-model-terms_en.html
- **重要**：本仓库内置 Haru 模型**仅用于技术验证与开发调试**。
  正式发布前请替换为自有模型或已获授权的模型，并遵守示例模型条款中
  关于用途、署名与再分发的限制。

## 替换模型

将自备模型放入 `models/<your-model>/`，并修改 `js/bridge.js` 顶部的
`CFG.modelUrl` 指向新的 `.model3.json` 即可。若模型使用非标准口型参数，
同步调整 `CFG.lipSyncParams`。
