#!/usr/bin/env node
/**
 * 模型动作文件校验
 *
 * 为什么需要它
 * ------------
 * motion3.json 里的参数名、值域都必须对着 moc3 校，而这类错误在画面上
 * 只表现为「什么都没发生」：
 *   - 参数名写错 / 该参数其实是物理输出 → 曲线被物理每帧覆盖，动作播了没反应
 *   - 值超出 min/max                     → Core 直接 clamp，动作幅度被吃掉一半
 *   - 段计数（CurveCount 等）不自洽        → 运行库按 Meta 里的段数预分配数组，
 *                                          解析越界后动作会残缺甚至整个加载失败
 * 靠肉眼审查几乎不可能发现（和表情名那条坑同一类）。
 *
 * 为什么能在 Node 里跑
 * --------------------
 * live2dcubismcore.min.js 是**自包含的 asm.js** 构建（不依赖 _em_module.wasm），
 * 用 new Function 把它的局部变量挂到 global 上即可拿到 API，离线可用。
 *
 * 运行：node tools/live2d_motion_check.cjs
 * 模型未随仓库分发（见 .gitignore），缺失时自动跳过，不影响 CI。
 */

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const MODEL_DIR = path.join(
  ROOT, 'app', 'src', 'main', 'assets', 'live2d', 'models', 'silverwolf'
);
const MODEL_JSON = path.join(MODEL_DIR, 'silverwolf.model3.json');
const MOC = path.join(MODEL_DIR, 'silverwolf.moc3');
const PHYSICS = path.join(MODEL_DIR, 'silverwolf.physics3.json');
const CORE = path.join(ROOT, 'app', 'src', 'main', 'assets', 'live2d', 'lib',
                       'live2dcubismcore.min.js');

// 由 bridge.js 的程序化待机层每帧写入的通道：动作文件再写一遍会被覆盖
// （那层写在 afterMotionUpdate，晚于动作更新）。与 tools/live2d_make_idle.py
// 里的 RESERVED_BY_IDLE_LAYER 必须保持一致。
// 视线只允许来自 focus / 呼吸。待机动作写 yaw 或眼球，角色就会"看向别处"
// （实测：idle_glance 写了 ParamAngleX=-9°/ParamEyeBallY=+0.1，看起来就是
//  "盯着左上角、不像在看你"，所以这里是硬性禁止）
const GAZE_PARAMS = ['ParamAngleX', 'ParamEyeBallX', 'ParamEyeBallY'];

const OWNED_BY_IDLE_LAYER = [
  'ParamBrowLY', 'ParamBrowRY', 'ParamBrowLForm', 'ParamBrowRForm',
  'ParamEyeLSmile', 'ParamEyeRSmile', 'ParamEyeLSquint', 'ParamEyeRSquint',
  'ParamMouthForm', 'ParamBreath', 'ParamAngleZ', 'ParamBodyAngleZ',
];

let pass = 0, fail = 0;
const check = (name, cond, extra = '') => {
  if (cond) { pass++; console.log('  OK   ' + name); }
  else { fail++; console.log('  FAIL ' + name + (extra ? '  -> ' + extra : '')); }
};
const warn = (msg) => console.log('  WARN ' + msg);

// ---------------------------------------------------------------- Cubism Core
function loadCore() {
  // 让运行库走非浏览器分支（用 fs 读额外文件），并给出 currentScript
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

// ------------------------------------------------------------ motion3 解析
/**
 * 按运行库的规则走一遍 Segments，顺便算出段数/点数
 *
 * 格式（已在自带动作文件上验证）：
 *   先裸写起点 (x0, y0)，之后每段 = 类型 + 该段终点（贝塞尔多两个控制点）
 *   0 = 直线（1 点） 1 = 贝塞尔（3 点：c1、c2、终点） 2 = 阶梯（1 点） 3 = 反向阶梯（1 点）
 */
function walkSegments(segments) {
  if (!Array.isArray(segments) || segments.length < 4) {
    return { ok: false, reason: 'Segments 太短' };
  }
  const nPointsOf = { 0: 1, 1: 3, 2: 1, 3: 1 };
  let prev = { t: segments[0], v: segments[1] };
  const start = prev;
  const segs = [];
  const types = new Set();
  let i = 2;
  while (i < segments.length) {
    const type = segments[i++];
    if (!(type in nPointsOf)) return { ok: false, reason: `未知段类型 ${type} @${i - 1}` };
    types.add(type);
    const p = [];
    for (let k = 0; k < nPointsOf[type]; k++) {
      const t = segments[i], v = segments[i + 1];
      if (typeof t !== 'number' || typeof v !== 'number') {
        return { ok: false, reason: `段 @${i - 2} 数据不完整` };
      }
      p.push({ t, v });
      i += 2;
    }
    const seg = type === 1
      ? { type, p0: prev, c1: p[0], c2: p[1], p1: p[2] }
      : { type, p0: prev, p1: p[0] };
    segs.push(seg);
    prev = seg.p1;
  }
  if (i !== segments.length) return { ok: false, reason: '长度不整除（段结构错位）' };
  for (let k = 1; k < segs.length; k++) {
    if (segs[k].p1.t < segs[k - 1].p1.t) {
      return { ok: false, reason: `时间回退 @${k}` };
    }
  }
  return {
    ok: true, segs, segments: segs.length, pointCount: 1 + 3 * 0 + segs.reduce(
      (n, s) => n + (s.type === 1 ? 3 : 1), 0),
    types: [...types].sort(),
    startTime: start.t,
    endTime: prev.t,
  };
}

/** 求某个段在时刻 t 的值（Cubism 用 x 作参数） */
function evalSeg(s, t) {
  if (s.type === 2) return s.p0.v;            // 阶梯：保持上一个值
  if (s.type === 3) return s.p1.v;            // 反向阶梯：立刻跳到新值
  const span = s.p1.t - s.p0.t;
  const k = span > 0 ? (t - s.p0.t) / span : 0;
  if (s.type === 0) return s.p0.v + (s.p1.v - s.p0.v) * k;
  const m = 1 - k;
  return m * m * m * s.p0.v + 3 * m * m * k * s.c1.v + 3 * m * k * k * s.c2.v + k * k * k * s.p1.v;
}

/**
 * 按 60fps 采样出曲线的真实值域
 *
 * 不能只看关键点：贝塞尔的控制点可以远远落在值域之外（作者常用来做过冲，
 * 属于正常手法），而曲线本身不一定越界；反过来控制点也可能把曲线顶出范围。
 * 所以直接采样求最值。
 */
function sampleRange(walk, fps = 60) {
  let lo = Infinity, hi = -Infinity;
  for (const s of walk.segs) {
    const n = Math.max(1, Math.ceil((s.p1.t - s.p0.t) * fps));
    for (let k = 0; k <= n; k++) {
      const v = evalSeg(s, s.p0.t + (s.p1.t - s.p0.t) * (k / n));
      if (v < lo) lo = v;
      if (v > hi) hi = v;
    }
  }
  if (!isFinite(lo)) { lo = 0; hi = 0; }
  return { lo, hi };
}

function inspectMotion(file, params, physics) {
  const json = JSON.parse(fs.readFileSync(file, 'utf8'));
  const meta = json.Meta || {};
  const curves = json.Curves || [];
  const out = {
    meta, curves: [], roles: {},
    problems: [],   // 硬错误：结构/元数据/参数不存在/起始时间
    rangeIssues: [], // 值域越界：我们自己的文件算错误，作者原文件只提示
  };

  let nseg = 0, npt = 0, maxEnd = 0;
  for (const c of curves) {
    const w = walkSegments(c.Segments);
    if (!w.ok) { out.problems.push(`${c.Id}: ${w.reason}`); continue; }
    nseg += w.segments;
    npt += w.pointCount;
    maxEnd = Math.max(maxEnd, w.endTime);

    const p = params[c.Id];
    if (c.Target !== 'Parameter') out.problems.push(`${c.Id}: Target=${c.Target}（本模型只应有 Parameter）`);
    if (!p) out.problems.push(`${c.Id}: moc3 里没有这个参数`);
    else {
      if (w.startTime !== 0) out.problems.push(`${c.Id}: 起始点时间 ${w.startTime} ≠ 0`);
      const r = sampleRange(w);
      if (r.lo < p.min - 1e-6 || r.hi > p.max + 1e-6) {
        out.rangeIssues.push(
          `${c.Id}: 实际值域 ${r.lo.toFixed(2)}~${r.hi.toFixed(2)} 超出 moc3 的 ${p.min}~${p.max}（会被 clamp）`);
      }
      out.roles[c.Id] = physics.outputs.has(c.Id) ? 'output'
        : (physics.inputs.has(c.Id) ? 'input' : 'free');
    }
    out.curves.push({ id: c.Id, target: c.Target, segments: w.segments, endTime: w.endTime });
  }

  out.counts = { nseg, npt, maxEnd };
  if (meta.CurveCount !== curves.length) {
    out.problems.push(`Meta.CurveCount=${meta.CurveCount} 与实际 ${curves.length} 不符`);
  }
  if (meta.TotalSegmentCount !== nseg) {
    out.problems.push(`Meta.TotalSegmentCount=${meta.TotalSegmentCount} 与实际 ${nseg} 不符`);
  }
  if (meta.TotalPointCount !== npt) {
    out.problems.push(`Meta.TotalPointCount=${meta.TotalPointCount} 与实际 ${npt} 不符`);
  }
  if (maxEnd > (meta.Duration || 0) + 1e-6) {
    out.problems.push(`关键点最晚 ${maxEnd}s 超出 Meta.Duration=${meta.Duration}`);
  }
  return out;
}

// ------------------------------------------------------------------- 主流程
(async () => {
  if (!fs.existsSync(MODEL_JSON) || !fs.existsSync(MOC)) {
    console.log('跳过：本地没有模型文件（模型未随仓库分发，见 .gitignore）');
    console.log('      需要时先跑 tools/setup_live2d_assets.sh，再放好自己的模型。');
    process.exit(0);
  }

  console.log('\n[1] 载入 Cubism Core 与 moc3');
  const core = loadCore();
  const ready = await waitCore(core);
  check('Cubism Core 初始化完成', ready);
  if (!ready) process.exit(1);
  const v = core.Version.csmGetVersion();
  console.log(`  core: ${(v >>> 16)}.${(v >>> 8) & 0xff}.${v & 0xff}（0x${v.toString(16)}）`);

  const buf = fs.readFileSync(MOC);
  const ab = buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength);
  const model = core.Model.fromMoc(core.Moc.fromArrayBuffer(ab));
  const P = model.parameters;
  const params = {};
  for (let i = 0; i < P.count; i++) {
    params[P.ids[i]] = { min: P.minimumValues[i], max: P.maximumValues[i], def: P.defaultValues[i] };
  }
  check('moc3 参数表已读出', P.count > 0, String(P.count));
  console.log(`  参数 ${P.count} 个 / 部位 ${model.parts.count} 个 / drawable ${model.drawables.count} 个`);

  const phys = { inputs: new Set(), outputs: new Set() };
  if (fs.existsSync(PHYSICS)) {
    const pj = JSON.parse(fs.readFileSync(PHYSICS, 'utf8'));
    for (const st of pj.PhysicsSettings || []) {
      for (const i of st.Input || []) phys.inputs.add(i.Source.Id);
      for (const o of st.Output || []) phys.outputs.add(o.Destination.Id);
    }
  }
  console.log(`  物理：输入 ${phys.inputs.size} 个 / 输出 ${phys.outputs.size} 个`);

  console.log('\n[2] 动作组注册');
  const modelJson = JSON.parse(fs.readFileSync(MODEL_JSON, 'utf8'));
  const groups = (modelJson.FileReferences || {}).Motions || {};
  const names = Object.keys(groups);
  check('注册了动作组', names.length > 0, names.join(','));
  check('自带动作组未被破坏（Transform/AngryLoop/Sleep）',
        (groups.Transform || []).length === 2 &&
        (groups.AngryLoop || []).length === 1 &&
        (groups.Sleep || []).length === 1,
        JSON.stringify(names.map((n) => `${n}:${groups[n].length}`)));
  if (groups.TransformOnce) {
    check('变身过场组 TransformOnce 有 2 条（进入 + 还原）',
          groups.TransformOnce.length === 2,
          JSON.stringify(groups.TransformOnce.map((d) => d.File)));
  }

  const hasIdle = !!groups.Idle && groups.Idle.length > 0;
  console.log(hasIdle
    ? `  Idle 组 ${groups.Idle.length} 条（运行库会自动随机播放）`
    : '  WARN 没有 Idle 组：运行库不会播放任何待机动作');

  console.log('\n[3] 逐个动作文件校验');
  let fileFail = 0;
  for (const [group, defs] of Object.entries(groups)) {
    for (const def of defs) {
      const file = path.join(MODEL_DIR, def.File);
      const label = `${group}/${path.basename(def.File)}`;
      if (!fs.existsSync(file)) {
        check(`${label} 文件存在`, false, file);
        fileFail++;
        continue;
      }
      let info;
      try {
        info = inspectMotion(file, params, phys);
      } catch (e) {
        check(`${label} 可解析`, false, e.message);
        fileFail++;
        continue;
      }
      check(`${label} 段结构与元数据自洽`, info.problems.length === 0,
            info.problems.slice(0, 3).join(' | '));
      const outCurves = Object.keys(info.roles).filter((id) => info.roles[id] === 'output');
      const loop = info.meta.Loop === true;
      const ours = /^idle_/.test(path.basename(def.File));
      const copy = /^transform_(in|out)/.test(path.basename(def.File));

      // 值域越界：我们自己的文件必须干净；作者原文件只提示（不是我们改的，
      // 但值得知道 —— 例如 m_transform_2 的 Param172 写着 10~20 而 moc3 上限是 10，
      // 结果是那半段特效一直贴在最大值上，看起来"没在动"）
      if (info.rangeIssues.length) {
        if (ours) check(`${label} 曲线值域未超出 moc3 范围`, false, info.rangeIssues.join(' | '));
        else warn(`${label} 有值域越界（作者原文件，仅提示）：${info.rangeIssues.join(' | ')}`);
      } else if (ours) {
        check(`${label} 曲线值域未超出 moc3 范围`, true);
      }

      // 程序化待机自己生成的动作有额外要求：
      //   - 非循环（循环会永远播同一条，失去"偶发"感）
      //   - 不写物理输出参数（写了不生效）
      //   - 不写程序化待机层占用的通道（那层每帧覆盖）
      if (ours) {
        check(`${label} 是非循环动作（播完换下一条）`, !loop, String(info.meta.Loop));
        check(`${label} 未写物理输出参数`, outCurves.length === 0, outCurves.join(','));
        const owned = Object.keys(info.roles).filter((id) => OWNED_BY_IDLE_LAYER.indexOf(id) >= 0);
        check(`${label} 未与程序化待机层抢通道`, owned.length === 0, owned.join(','));
        const gaze = Object.keys(info.roles).filter((id) => GAZE_PARAMS.indexOf(id) >= 0);
        check(`${label} 未抢视线通道（yaw / 眼球）`, gaze.length === 0, gaze.join(','));
      } else if (copy) {
        // 这些是"只改 Loop"的副本，唯一要求就是别再变回循环
        check(`${label} 是一次性动作（Loop: false）`, !loop, String(info.meta.Loop));
      } else if (outCurves.length) {
        warn(`${label} 有 ${outCurves.length} 条曲线写着物理输出参数（作者原文件，仅提示）：${outCurves.slice(0, 4).join(',')}`);
      }
      console.log(`    ${loop ? 'loop ' : 'once '} ${String(info.meta.Duration).padEnd(5)}s ` +
                  `曲线 ${info.curves.length} 段 ${info.counts.nseg} ` +
                  `参数 ${Object.keys(info.roles).join(',')}`);
    }
  }
  check('所有注册的动作文件都可加载', fileFail === 0, `${fileFail} 个有问题`);

  console.log('\n' + '='.repeat(52));
  console.log(`通过 ${pass} / 失败 ${fail}`);
  process.exit(fail === 0 ? 0 : 1);
})();
