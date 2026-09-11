/**
 * Live2D 桥接层自测
 *
 * 用 mock 替换 PIXI / DOM，直接驱动 assets/live2d/js/bridge.js，
 * 验证状态机、口型注入、布局计算与异常容错。
 *
 * 之所以需要它：WebView 内的 JS 无法用 Android 单元测试覆盖，
 * 而口型同步的正确性又依赖「音量 → 平滑曲线 → 参数注入」这条链路。
 *
 * 运行：node tools/live2d_selftest.cjs
 */
/* Live2D 桥接层自测：用 mock 驱动 bridge.js，验证状态机与口型注入 */
const fs = require('fs');
const path = require('path');

const BRIDGE_PATH = path.join(
  __dirname, '..', 'app', 'src', 'main', 'assets', 'live2d', 'js', 'bridge.js'
);
const BRIDGE = fs.readFileSync(BRIDGE_PATH, 'utf8');

// ---------------- 记录与 mock ----------------
const rec = { params: {}, expressions: [], motions: [], focus: [], scale: [], events: [], anchor: [] };
let beforeModelUpdate = null;

const coreModel = {
  setParameterValueById(id, v) { rec.params[id] = v; },
  getParameterIndex(id) { return id === 'ParamMouthOpenY' ? 3 : -1; },
};

const model = {
  focus(x, y, i) { rec.focus.push([x, y]); },
  expression(n) { rec.expressions.push(n); },
  motion(g, i, p) { rec.motions.push([g, i, p]); },
  anchor: { set(x, y) { rec.anchor.push([x, y]); } },
  scale: { set(s) { rec.scale.push(s); } },
  x: 0, y: 0,
  destroy() {},
  internalModel: {
    originalWidth: 2048, originalHeight: 2048,
    coreModel,
    expressionManager: {
      definitions: [{ Name: 'f00' }, { Name: 'f01' }],
      resetExpression() { rec.expressions.push('<reset>'); },
    },
    motionManager: { definitions: { Idle: [0, 1, 2], Tap: [0, 1] } },
    on(evt, cb) { if (evt === 'beforeModelUpdate') beforeModelUpdate = cb; },
  },
};

function mkEl() {
  return { textContent: '', classList: { add() {} }, style: {} };
}

const win = {
  innerWidth: 1080, innerHeight: 1920, devicePixelRatio: 2,
  addEventListener() {},
  AndroidBridge: { onEvent: (type, payload) => rec.events.push([type, payload]) },
};

global.window = win;
global.document = {
  readyState: 'complete',
  hidden: false,
  getElementById: () => mkEl(),
  addEventListener() {},
};
let simNow = 0;
global.performance = { now: () => simNow };
global.PIXI = {
  Application: class {
    constructor() {
      this.screen = { width: 1080, height: 1920 };
      this.stage = { addChild() {} };
      this.renderer = { resize() {} };
      this.ticker = { stop() {}, start() {} };
    }
    destroy() {}
  },
  live2d: {
    MotionPriority: { NONE: 0, IDLE: 1, NORMAL: 2, FORCE: 3 },
    Live2DModel: { from: () => Promise.resolve(model) },
  },
};
global.console = console;

// ---------------- 运行 bridge.js ----------------
eval(BRIDGE);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// 以真实时间轴推进 N 帧
function tick(frames = 1, dtMs = 16) {
  for (let i = 0; i < frames; i++) {
    simNow += dtMs;              // 关键：推进模拟时钟，否则 dt≈0 插值不生效
    if (beforeModelUpdate) beforeModelUpdate();
  }
}

let pass = 0, fail = 0;
function check(name, cond, extra = '') {
  if (cond) { pass++; console.log('  ✓ ' + name); }
  else { fail++; console.log('  ✗ ' + name + (extra ? '  → ' + extra : '')); }
}

(async () => {
  await sleep(30);

  console.log('\n[1] 初始化与事件上报');
  check('window.L2D 已暴露', !!win.L2D);
  check('L2D.version 存在', win.L2D && !!win.L2D.version, String(win.L2D && win.L2D.version));
  check('ready 事件已上报', rec.events.some(([t]) => t === 'ready'), JSON.stringify(rec.events));
  check('beforeModelUpdate 已注册', typeof beforeModelUpdate === 'function');
  check('模型已完成布局', rec.scale.length > 0 && rec.anchor.length > 0,
        `scale=${JSON.stringify(rec.scale)} anchor=${JSON.stringify(rec.anchor)}`);

  console.log('\n[2] 布局计算');
  // 1920 高 / 2048 原始高 * 1.18 ≈ 1.1063
  const expScale = (1920 / 2048) * 1.18;
  check('缩放按高度基准计算', Math.abs(rec.scale[0] - expScale) < 0.01,
        `期望≈${expScale.toFixed(4)} 实际=${rec.scale[0]}`);
  check('锚点居中', rec.anchor[0][0] === 0.5 && rec.anchor[0][1] === 0.5);

  console.log('\n[3] 空闲状态：口型应闭合');
  win.L2D.setState('idle');
  tick(30);
  check('idle 时 ParamMouthOpenY ≈ 0', (rec.params.ParamMouthOpenY || 0) < 0.01,
        String(rec.params.ParamMouthOpenY));

  console.log('\n[4] 说话状态：音量驱动口型');
  win.L2D.setState('speaking');
  win.L2D.setMouth(0.9);
  rec.params.ParamMouthOpenY = -1;
  tick(20);
  const open = rec.params.ParamMouthOpenY;
  check('口型被驱动张开', open > 0.3, `ParamMouthOpenY=${open}`);
  check('口型不超过 1', open <= 1.0, String(open));
  check('写入的是小写正确参数名', 'ParamMouthOpenY' in rec.params,
        JSON.stringify(Object.keys(rec.params)));

  console.log('\n[5] 音量归零 → 口型应收敛');
  win.L2D.setMouth(0);
  tick(60);
  check('停止输入后口型回落到 ≈0', (rec.params.ParamMouthOpenY || 0) < 0.05,
        String(rec.params.ParamMouthOpenY));

  console.log('\n[6] 非说话状态：即使推音量也不张口');
  win.L2D.setState('listening');
  win.L2D.setMouth(1.0);
  tick(30);
  check('listening 时保持闭嘴', (rec.params.ParamMouthOpenY || 0) < 0.01,
        String(rec.params.ParamMouthOpenY));

  console.log('\n[7] setMouthEnabled(false) 强制闭嘴');
  win.L2D.setState('speaking');
  win.L2D.setMouth(1.0);
  win.L2D.setMouthEnabled(false);
  tick(40);
  check('关闭口型后闭嘴', (rec.params.ParamMouthOpenY || 0) < 0.01,
        String(rec.params.ParamMouthOpenY));
  win.L2D.setMouthEnabled(true);

  console.log('\n[8] 非法输入不应崩溃');
  let crashed = false;
  try {
    win.L2D.setMouth(NaN); win.L2D.setMouth(Infinity); win.L2D.setMouth('abc');
    win.L2D.setMouth(-5); win.L2D.setMouth(99);
    win.L2D.setState('不存在的状态'); win.L2D.setState(null); win.L2D.setState(undefined);
    win.L2D.setLayout('{bad json'); win.L2D.playMotion(); win.L2D.setExpression(null);
    win.L2D.setPaused(true); win.L2D.setPaused(false); win.L2D.requestInfo();
    tick(5);
  } catch (e) { crashed = true; console.log('    异常: ' + e.message); }
  check('异常输入被安全处理', !crashed);
  check('非法 setState 回落为 idle', win.L2D.state === 'idle', win.L2D.state);

  console.log('\n[9] 状态切换触发动作/表情');
  const before = rec.motions.length;
  win.L2D.setState('thinking');
  tick(2);
  check('切换状态会播放动作', rec.motions.length > before,
        `motion 调用 ${before} → ${rec.motions.length}`);
  check('动作使用 Idle 组 + IDLE 优先级',
        rec.motions.slice(-1)[0][0] === 'Idle' && rec.motions.slice(-1)[0][2] === 1,
        JSON.stringify(rec.motions.slice(-1)));

  console.log('\n[10] dispose 释放');
  win.L2D.dispose();
  check('dispose 后 ready 为 false', win.L2D.ready === false);

  console.log(`\n${'='.repeat(46)}`);
  console.log(`通过 ${pass} / 失败 ${fail}`);
  process.exit(fail === 0 ? 0 : 1);
})();
