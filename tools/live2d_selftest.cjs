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
let afterMotionUpdate = null;

// 真实模型里存在的参数（够覆盖口型 + 待机通道即可）
// 故意不列出 ParamMouthOpen："CFG.lipSyncParams 里有、但模型没有该参数时应被跳过"，
// 这正是容易漏掉的分支（写不存在的参数在旧 WebView 上会抛异常）。
const KNOWN_PARAMS = [
  'ParamMouthOpenY',
  'ParamBrowLY', 'ParamBrowRY', 'ParamBrowLForm', 'ParamBrowRForm',
  'ParamEyeLSmile', 'ParamEyeRSmile', 'ParamEyeLSquint', 'ParamEyeRSquint',
  'ParamMouthForm', 'ParamBreath', 'ParamAngleZ', 'ParamBodyAngleZ',
];

const coreModel = {
  setParameterValueById(id, v) { rec.params[id] = v; },
  getParameterIndex(id) { return KNOWN_PARAMS.indexOf(id); },
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
      // 名字刻意与真实模型一致：带编号、空格不统一 —— 正是容易写错的地方
      definitions: [{ Name: '01黑脸' }, { Name: '03 生气' }, { Name: '06 0.0' }, { Name: '月卡' }],
      resetExpression() { rec.expressions.push('<reset>'); },
    },
    motionManager: { definitions: { Idle: [0, 1, 2], Tap: [0, 1] } },
    on(evt, cb) {
      if (evt === 'afterMotionUpdate') afterMotionUpdate = cb;
      if (evt === 'beforeModelUpdate') beforeModelUpdate = cb;
    },
  },
};

function mkEl() {
  return { textContent: '', classList: { add() {}, remove() {} }, style: {} };
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
global.requestAnimationFrame = (cb) => setTimeout(() => cb(performance.now()), 16);
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
    if (afterMotionUpdate) afterMotionUpdate();
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
  check('afterMotionUpdate 已注册', typeof afterMotionUpdate === 'function');
  check('beforeModelUpdate 已注册', typeof beforeModelUpdate === 'function');
  check('模型已完成布局', rec.scale.length > 0 && rec.anchor.length > 0,
        `scale=${JSON.stringify(rec.scale)} anchor=${JSON.stringify(rec.anchor)}`);

  console.log('\n[2] 布局计算');
  // 校验不变量而非写死魔数：模型高度应被缩放到视口高度的合理倍数
  const MODEL_H = 2048;   // mock 模型的 originalHeight
  const VIEW_H = 1920;    // mock 视口高度
  const ratio = (rec.scale[0] * MODEL_H) / VIEW_H;
  check('按视口高度适配（填充比例在合理区间）', ratio > 0.8 && ratio < 1.6,
        `比例=${ratio.toFixed(3)}（期望 0.8~1.6）`);
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

  console.log('\n[9] 状态切换：状态表情已与情绪表达解耦');
  rec.expressions.length = 0;
  rec.motions.length = 0;
  win.L2D.setState('thinking');
  tick(2);
  // 早期 thinking 占位用了 '06 0.0'，而 LLM 打招呼最爱选的也是 0.0，
  // 撞车后「触发成功但脸没变」会被误判成功能失效，故状态不再自带表情。
  check('状态切换不再设置占位表情',
        rec.expressions.every((e) => e === '<reset>'), JSON.stringify(rec.expressions));
  check('该模型无待机动作，不应播放 motion', rec.motions.length === 0,
        JSON.stringify(rec.motions));

  console.log('\n[10] 情绪表情覆盖层（LLM 触发表情）');
  rec.expressions.length = 0;
  win.L2D.setState('thinking');
  tick(2);
  win.L2D.setExpression('03 生气');
  tick(2);
  check('setExpression 应用到模型', rec.expressions.indexOf('03 生气') >= 0,
        JSON.stringify(rec.expressions));
  check('L2D.expression 记录当前情绪', win.L2D.expression === '03 生气',
        String(win.L2D.expression));

  // 宽容匹配：模型名空格不统一，多/少一个空格不该导致表情静默失效
  rec.expressions.length = 0;
  win.L2D.setExpression('03生气');
  tick(2);
  check('缺空格的别名被纠正为模型真实名字',
        rec.expressions.indexOf('03 生气') >= 0 && win.L2D.expression === '03 生气',
        JSON.stringify(rec.expressions) + ' / ' + win.L2D.expression);

  // 名字对不上时必须「保持原状」，不能把已经生效的表情清掉，也不能假装成功
  rec.expressions.length = 0;
  win.L2D.setExpression('不存在的表情');
  tick(2);
  check('未知表情不改变当前情绪', win.L2D.expression === '03 生气',
        String(win.L2D.expression));
  check('未知表情不触发任何下发', rec.expressions.indexOf('不存在的表情') < 0,
        JSON.stringify(rec.expressions));

  // 核心回归：thinking → speaking 的状态切换会重走 applyState，
  // 若情绪只调一次 model.expression()，会被这里的 resetExpression 冲掉。
  rec.expressions.length = 0;
  win.L2D.setState('speaking');
  tick(2);
  check('状态切换后情绪表情被重新应用', rec.expressions.indexOf('03 生气') >= 0,
        JSON.stringify(rec.expressions));
  check('状态切换不得冲掉情绪表情', rec.expressions.indexOf('<reset>') < 0,
        JSON.stringify(rec.expressions));

  rec.expressions.length = 0;
  win.L2D.setExpression(null);
  tick(2);
  check('复位后回落到状态默认表情（speaking 无表情 → reset）',
        rec.expressions.indexOf('<reset>') >= 0, JSON.stringify(rec.expressions));
  check('复位后 L2D.expression 为空', win.L2D.expression === null,
        String(win.L2D.expression));

  // 复位后不能有残留：状态切换不得复活已经清掉的情绪
  rec.expressions.length = 0;
  win.L2D.setState('idle');
  tick(2);
  win.L2D.setState('thinking');
  tick(2);
  check('复位后状态切换不会复活旧情绪',
        rec.expressions.indexOf('03 生气') < 0, JSON.stringify(rec.expressions));

  console.log('\n[11] 程序化待机（不写动作文件，靠每帧参数）');

  // 从 bridge.js 里解析出待机通道清单，用来断言"只写声明过的参数"。
  // 这些参数必须是「物理输入 / 空闲」通道：物理输出每帧都会被物理覆盖，
  // 写上去等于没写（下面还有一条和真实物理表交叉校验的断言）。
  const channelsBlock = BRIDGE.match(/channels:\s*\{([\s\S]*?)\}/);
  const IDLE_PARAMS = channelsBlock
    ? [...channelsBlock[1].matchAll(/'([A-Za-z0-9_]+)'/g)].map((m) => m[1])
    : [];
  check('能从 bridge.js 解析出待机通道清单', IDLE_PARAMS.length >= 6, IDLE_PARAMS.join(','));

  const clearParams = () => { for (const k in rec.params) delete rec.params[k]; };
  // 取一段时间内的均值：偶发"耳抖"只持续 0.28s，均值法能把它抹掉，
  // 否则断言会在"恰好抖到"的时候偶发失败
  const sampleIdle = (frames) => {
    const acc = {}; let n = 0;
    for (let i = 0; i < frames; i++) {
      tick(1);
      const v = win.L2D.getIdle();
      for (const k in v) acc[k] = (acc[k] || 0) + v[k];
      n++;
    }
    const avg = {};
    for (const k in acc) avg[k] = acc[k] / n;
    return avg;
  };

  clearParams();
  win.L2D.setState('idle');
  tick(180);
  check('待机层确实写入了参数', 'ParamBrowLY' in rec.params, JSON.stringify(Object.keys(rec.params)));
  check('待机走 ParamMouthForm，不碰口型开闭',
        'ParamMouthForm' in rec.params && Object.keys(rec.params).indexOf('ParamMouthOpen') < 0,
        JSON.stringify(Object.keys(rec.params)));

  const allowed = new Set(IDLE_PARAMS.concat(['ParamMouthOpenY']));
  const stray = Object.keys(rec.params).filter((id) => !allowed.has(id));
  check('待机写入的参数都在声明通道内', stray.length === 0, stray.join(','));

  const idleAvg = sampleIdle(360);
  win.L2D.setState('listening'); tick(180);
  const listenAvg = sampleIdle(360);
  win.L2D.setState('thinking'); tick(180);
  const thinkAvg = sampleIdle(360);
  win.L2D.setState('speaking'); tick(180);
  const speakAvg = sampleIdle(360);
  win.L2D.setState('ended'); tick(180);
  const endedAvg = sampleIdle(360);

  check('聆听比思考更"笑眼"', listenAvg.smile > thinkAvg.smile + 0.15,
        `聆听=${listenAvg.smile.toFixed(3)} 思考=${thinkAvg.smile.toFixed(3)}`);
  check('思考比聆听眉毛更皱', thinkAvg.browForm > listenAvg.browForm + 0.15,
        `思考=${thinkAvg.browForm.toFixed(3)} 聆听=${listenAvg.browForm.toFixed(3)}`);
  check('思考会眯眼', thinkAvg.squint > 0.2, thinkAvg.squint.toFixed(3));
  check('聆听/思考的头倾方向相反', listenAvg.tilt > 0.5 && thinkAvg.tilt < -0.5,
        `聆听=${listenAvg.tilt.toFixed(2)} 思考=${thinkAvg.tilt.toFixed(2)}`);
  check('说话时呼吸更深', speakAvg.breath > idleAvg.breath + 0.1,
        `说话=${speakAvg.breath.toFixed(3)} 待机=${idleAvg.breath.toFixed(3)}`);
  check('结束状态眉眼下垂', endedAvg.brow < -0.02, endedAvg.brow.toFixed(3));

  // 姿态必须是渐变：从 idle 切到 thinking，第一帧不能直接跳到目标值
  win.L2D.setState('idle'); tick(180);
  const tiltBefore = win.L2D.getIdle().tilt;
  win.L2D.setState('thinking'); tick(1);
  const tiltAfter = win.L2D.getIdle().tilt;
  check('姿态切换是渐变而非瞬跳',
        Math.abs(tiltAfter - tiltBefore) > 0.005 && Math.abs(tiltAfter + 3.0) > 0.5,
        `${tiltBefore.toFixed(3)} → ${tiltAfter.toFixed(3)}`);

  // 有 LLM 情绪表情时压制状态姿态，否则会出现"生气脸配聆听微笑"的错位
  win.L2D.setExpression('03 生气');
  win.L2D.setState('listening'); tick(180);
  const cueAvg = sampleIdle(240);
  win.L2D.setExpression(null);
  check('有情绪表情时状态姿态被压制', cueAvg.smile < listenAvg.smile - 0.1,
        `有情绪=${cueAvg.smile.toFixed(3)} 无情绪=${listenAvg.smile.toFixed(3)}`);

  check('setIdleEnabled 返回新状态', win.L2D.setIdleEnabled(false) === false);
  tick(180);
  const idleOff = win.L2D.getIdle();
  check('关闭待机层后姿态归零',
        Object.keys(idleOff).every((k) => Math.abs(idleOff[k]) < 0.01), JSON.stringify(idleOff));
  win.L2D.setIdleEnabled(true);

  // 与真实模型的物理表交叉校验（模型未随仓库分发，缺失时跳过，同 check_expression_names）
  const PHYS = path.join(__dirname, '..', 'app', 'src', 'main', 'assets',
                         'live2d', 'models', 'silverwolf', 'silverwolf.physics3.json');
  if (fs.existsSync(PHYS) && IDLE_PARAMS.length) {
    const outs = new Set();
    const phys = JSON.parse(fs.readFileSync(PHYS, 'utf8'));
    for (const st of phys.PhysicsSettings) {
      for (const o of st.Output) outs.add(o.Destination.Id);
    }
    const overwritten = IDLE_PARAMS.filter((id) => outs.has(id));
    check('待机通道都不是物理输出（否则会被物理每帧覆盖）',
          overwritten.length === 0, overwritten.join(','));
  } else {
    console.log('  (跳过物理交叉校验：本地没有模型文件)');
  }

  console.log('\n[12] dispose 释放');
  win.L2D.dispose();
  check('dispose 后 ready 为 false', win.L2D.ready === false);

  console.log(`\n${'='.repeat(46)}`);
  console.log(`通过 ${pass} / 失败 ${fail}`);
  process.exit(fail === 0 ? 0 : 1);
})();
