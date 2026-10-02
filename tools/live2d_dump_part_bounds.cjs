#!/usr/bin/env node
/**
 * 按**位置**列出模型的部件包围盒
 *
 * 为什么需要它
 * ------------
 * `tools/live2d_dump_parts.py` 靠**部件名里的关键词**（头 / 脸 / 眼 / 眉…）挑出
 * 摸头命中盒要用的部件。这对银狼和 DS鲸鱼娘都够用，但对 VTS 风格绑定的模型完全失效：
 * 「大肥鱼」的部件名是 `角度XY-` / `部件15` / `大肥鱼.psd` 这种编辑器占位名，
 * 关键词表只会误命中 `角度XY-` 里的「角」。
 *
 * 那种情况下只能**按位置**判断哪些部件在头上 —— 本脚本就是把这份位置数据算出来：
 * 取每个部件下所有 drawable 的并集包围盒，归一化到**内容包围盒**（0~1，左上为原点），
 * 与 `CFG.pat.hit` / `CFG.pat.padRatio` 同一个坐标空间，可以直接照着填
 * `tools/live2d_dump_parts.py` 里的 `MANUAL_PARTS`。
 *
 * 顺带打印每个部件是「物理输入 / 物理输出 / 空闲」—— 挑通道时同一份数据就能用。
 *
 * 为什么能在 Node 里跑
 * --------------------
 * 与 live2d_motion_check.cjs 同一招：`live2dcubismcore.min.js` 是**自包含的 asm.js**
 * 构建（不依赖 _em_module.wasm），用 new Function 把它的局部变量挂到 global 上即可。
 *
 * ⚠️ 单位坑：Core 的 `drawables.vertexPositions` 是**模型单位**，原点在画布中心、y 向上，
 *    每单位 `canvasinfo.PixelsPerUnit` 像素 —— 换算到画布像素要**乘**，不是除。
 *    搞反过一次：所有部件的包围盒都塌成画布中心一个点（看起来像"顶点读不到"）。
 *
 * 运行：
 *     node tools/live2d_dump_part_bounds.cjs dafeiyu
 *     node tools/live2d_dump_part_bounds.cjs silverwolf
 */

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const MODELS_ROOT = path.join(ROOT, 'app', 'src', 'main', 'assets', 'live2d', 'models');
const CORE = path.join(ROOT, 'app', 'src', 'main', 'assets', 'live2d', 'lib',
                       'live2dcubismcore.min.js');

// ---------------------------------------------------------------- Cubism Core
function loadCore() {
  global.document = global.document || {
    currentScript: { src: 'https://appassets.androidplatform.net/assets/live2d/lib/live2dcubismcore.min.js' },
  };
  const src = fs.readFileSync(CORE, 'utf8');
  const mod = { exports: {} };
  new Function('module', 'exports', 'require', '__dirname', '__filename', 'global',
    src + '\n;global.__L2D = Live2DCubismCore;\n'
  )(mod, mod.exports, require, __dirname, __filename, global);
  return global.__L2D;
}

async function waitCore(core, timeoutMs = 5000) {
  const t0 = Date.now();
  for (;;) {
    try { core.Version.csmGetVersion(); return true; } catch (e) { /* 还没初始化好 */ }
    if (Date.now() - t0 > timeoutMs) return false;
    await new Promise((r) => setTimeout(r, 20));
  }
}

function findModel(tag) {
  const dir = path.join(MODELS_ROOT, tag);
  if (!fs.existsSync(dir)) return null;
  const files = fs.readdirSync(dir);
  const moc = files.find((f) => f.endsWith('.moc3'));
  if (!moc) return null;
  const cdi3 = files.find((f) => f.endsWith('.cdi3.json'));
  const physics = files.find((f) => f.endsWith('.physics3.json'));
  return {
    tag, dir,
    moc: path.join(dir, moc),
    cdi3: cdi3 ? path.join(dir, cdi3) : null,
    physics: physics ? path.join(dir, physics) : null,
  };
}

function physicsRoles(p) {
  const inputs = new Set(), outputs = new Set();
  if (p && fs.existsSync(p)) {
    const pj = JSON.parse(fs.readFileSync(p, 'utf8'));
    for (const st of pj.PhysicsSettings || []) {
      for (const i of st.Input || []) inputs.add(i.Source.Id);
      for (const o of st.Output || []) outputs.add(o.Destination.Id);
    }
  }
  return { inputs, outputs };
}

(async () => {
  const args = process.argv.slice(2);
  const at = args.indexOf('--model');
  const tag = (at >= 0 ? args[at + 1] : args.find((a) => !a.startsWith('-')));
  if (!tag) {
    console.error('用法: node tools/live2d_dump_part_bounds.cjs <模型目录名>');
    console.error('      已装：' + (fs.existsSync(MODELS_ROOT)
      ? fs.readdirSync(MODELS_ROOT).filter((n) => {
          try { return fs.statSync(path.join(MODELS_ROOT, n)).isDirectory(); } catch (e) { return false; }
        }).join(', ')
      : '(没有 models/ 目录)'));
    process.exit(2);
  }

  const m = findModel(tag);
  if (!m) { console.error(`[跳过] 找不到模型 ${tag}（或它没有 .moc3）`); process.exit(1); }

  const core = loadCore();
  if (!(await waitCore(core))) { console.error('Cubism Core 初始化失败'); process.exit(1); }
  const buf = fs.readFileSync(m.moc);
  const ab = buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength);
  const model = core.Model.fromMoc(core.Moc.fromArrayBuffer(ab));
  const phys = physicsRoles(m.physics);

  const nameOf = {};
  if (m.cdi3 && fs.existsSync(m.cdi3)) {
    const cdi3 = JSON.parse(fs.readFileSync(m.cdi3, 'utf8'));
    for (const p of cdi3.Parts || []) nameOf[String(p.Id)] = String(p.Name || '');
  }

  const ci = model.canvasinfo;
  const ppu = ci.PixelsPerUnit;
  const OX = ci.CanvasOriginX, OY = ci.CanvasOriginY;
  // 模型单位 → 画布像素（左上原点、y 向下）。注意是**乘** ppu，见文件头。
  const toPx = (x, y) => [x * ppu + OX, OY - y * ppu];

  const D = model.drawables, parts = model.parts;
  const partBox = new Map();
  let content = null;
  for (let i = 0; i < D.count; i++) {
    const op = D.opacities ? D.opacities[i] : 1;
    const v = D.vertexPositions[i];
    if (!v || v.length < 2) continue;
    let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
    for (let k = 0; k < v.length; k += 2) {
      const [px, py] = toPx(v[k], v[k + 1]);
      if (px < x0) x0 = px;
      if (px > x1) x1 = px;
      if (py < y0) y0 = py;
      if (py > y1) y1 = py;
    }
    // 与 bridge.js 一致：opacity ≈ 0 的替换件（没启用的预设）不参与
    if (!(op <= 0.01)) {
      content = content || { x0: Infinity, y0: Infinity, x1: -Infinity, y1: -Infinity };
      content.x0 = Math.min(content.x0, x0);
      content.x1 = Math.max(content.x1, x1);
      content.y0 = Math.min(content.y0, y0);
      content.y1 = Math.max(content.y1, y1);
    }
    const pi = D.parentPartIndices ? D.parentPartIndices[i] : -1;
    if (pi < 0) continue;
    const cur = partBox.get(pi);
    if (!cur) {
      partBox.set(pi, { x0, y0, x1, y1, n: 1, op });
    } else {
      cur.x0 = Math.min(cur.x0, x0);
      cur.x1 = Math.max(cur.x1, x1);
      cur.y0 = Math.min(cur.y0, y0);
      cur.y1 = Math.max(cur.y1, y1);
      cur.n++;
      cur.op = Math.max(cur.op, op);
    }
  }

  console.log(`模型 ${m.tag}  画布 ${ci.CanvasWidth}x${ci.CanvasHeight}  ` +
              `PixelsPerUnit=${ppu}  drawable ${D.count}  部件 ${parts.count}`);
  if (!content) { console.log('（没有可见 drawable，算不出内容包围盒）'); return; }
  const cw = content.x1 - content.x0, chh = content.y1 - content.y0;
  console.log(`内容包围盒  x${content.x0.toFixed(0)}..${content.x1.toFixed(0)} ` +
              `y${content.y0.toFixed(0)}..${content.y1.toFixed(0)}  (${cw.toFixed(0)}x${chh.toFixed(0)})`);
  console.log('（下面的 x/y 都归一化到这个内容包围盒，0~1，左上为原点）\n');

  const rows = [];
  for (const [pi, b] of partBox) {
    const id = String(parts.ids[pi]);
    rows.push({
      id,
      name: nameOf[id] || '?',
      n: b.n,
      op: b.op,
      nx0: (b.x0 - content.x0) / cw, nx1: (b.x1 - content.x0) / cw,
      ny0: (b.y0 - content.y0) / chh, ny1: (b.y1 - content.y0) / chh,
      w: b.x1 - b.x0, h: b.y1 - b.y0,
      role: phys.outputs.has(id) ? '物理输出'
        : (phys.inputs.has(id) ? '物理输入' : '空闲'),
    });
  }
  rows.sort((a, b) => a.ny0 - b.ny0);

  const w = Math.max(12, ...rows.map((r) => r.id.length));
  console.log('  按"上边缘"排序（越靠上越可能是头）：');
  for (const r of rows) {
    console.log(`  ${r.id.padEnd(w)} ${r.name.padEnd(26)} 网格 ${String(r.n).padStart(3)}  ` +
                `透明度 ${r.op.toFixed(2)}  ` +
                `x ${r.nx0.toFixed(3)}..${r.nx1.toFixed(3)}  ` +
                `y ${r.ny0.toFixed(3)}..${r.ny1.toFixed(3)}  ` +
                `${r.w.toFixed(0)}x${r.h.toFixed(0)}px`);
  }
  console.log(`\n物理：输入 ${phys.inputs.size} 个 / 输出 ${phys.outputs.size} 个`);
})();
