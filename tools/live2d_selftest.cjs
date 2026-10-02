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
const rec = { params: {}, expressions: [], motions: [], focus: [], focusWorldPoint: [], scale: [], events: [], anchor: [] };
let beforeModelUpdate = null;
let afterMotionUpdate = null;
let onMotionFinish = null;

// 真实模型里存在的参数（够覆盖口型 + 待机通道即可）
// 故意不列出 ParamMouthOpen："CFG.lipSyncParams 里有、但模型没有该参数时应被跳过"，
// 这正是容易漏掉的分支（写不存在的参数在旧 WebView 上会抛异常）。
const KNOWN_PARAMS = [
  'ParamMouthOpenY',
  'ParamBrowLY', 'ParamBrowRY', 'ParamBrowLForm', 'ParamBrowRForm',
  'ParamEyeLSmile', 'ParamEyeRSmile', 'ParamEyeLSquint', 'ParamEyeRSquint',
  'ParamMouthForm', 'ParamBreath', 'ParamAngleZ', 'ParamBodyAngleZ',
  // transform sequence + its neutral-value reset
  'key9', 'key11', 'key15', 'Param172', 'Param173', 'Param204', 'Param212',
  'Param210', 'Param211', 'Param213', 'Param214', 'Param218',
];

/**
 * 假 Core 数据（Cubism Core 的原始模型对象，bridge.js 的摸头命中盒要读它）。
 *
 * 形状照着 live2dcubismcore.min.js 的 Drawables / Parts 类来：
 *   drawables.parentPartIndices（Int32Array）/ vertexPositions（每个 drawable 一段 Float32Array）
 *   parts.ids / parentIndices（Int32Array）/ opacities（Float32Array）
 *
 * 部件 id 用**真实的银狼头部部件名**（Part127 脸 / Part94 五官），这样
 * "祖先链上溯"那条逻辑真的被走到；身体部件 Part999 的顶点故意远到天边 ——
 * 只要它漏进命中盒，断言立刻红。
 *
 * 层级：Part0(根) → Part127(脸) → Part94(五官)；Part0 → Part999(身体)
 */
const MOCK_PARTS = ['Part0', 'Part127', 'Part94', 'Part999'];
const MOCK_PART_PARENT = new Int32Array([-1, 0, 1, 0]);
const MOCK_PART_OPACITY = new Float32Array([1, 1, 1, 1]);
const MOCK_DRAWABLE_OPACITY = new Float32Array([1, 1, 1]);
const MOCK_DRAWABLES = [
  { part: 1, verts: new Float32Array([900, 300, 1100, 300, 900, 500, 1100, 500]) },  // 头顶（挂脸）
  { part: 2, verts: new Float32Array([920, 500, 1080, 500, 920, 620, 1080, 620]) },  // 五官（挂五官）
  { part: 3, verts: new Float32Array([0, 0, 4000, 0, 0, 4000, 4000, 4000]) },        // 身体（挂身体）
];

function mockCoreRaw() {
  return {
    drawables: {
      parentPartIndices: new Int32Array(MOCK_DRAWABLES.map((d) => d.part)),
      vertexPositions: MOCK_DRAWABLES.map((d) => d.verts),
      vertexCounts: new Int32Array(MOCK_DRAWABLES.map((d) => d.verts.length / 2)),
      opacities: MOCK_DRAWABLE_OPACITY,
    },
    parts: {
      ids: MOCK_PARTS,
      parentIndices: MOCK_PART_PARENT,
      opacities: MOCK_PART_OPACITY,
    },
  };
}

const coreModel = {
  setParameterValueById(id, v) { rec.params[id] = v; },
  getParameterIndex(id) { return KNOWN_PARAMS.indexOf(id); },
  _model: mockCoreRaw(),
};

const model = {
  /**
   * 公开 API：收**世界坐标点**，内部只取方向、模长恒为 1 —— 表达不了"看正前方"。
   * 单独记一份，用来断言正常路径**没有**走它（走它就会满偏）。
   */
  focus(x, y, i) { rec.focusWorldPoint.push([x, y, i]); },
  expression(n) { rec.expressions.push(n); },
  motion(g, i, p) { rec.motions.push([g, i, p]); },
  anchor: { set(x, y) { rec.anchor.push([x, y]); } },
  // scale 必须真的存住 x/y —— 兜底那条换算要用，只记录不存储会让它退化成 NaN，
  // 而 NaN 又会被 try/catch 吞掉，测试就永远绿。
  scale: { x: 1, y: 1, set(s) { rec.scale.push(s); this.x = s; this.y = s; } },
  x: 0, y: 0,
  destroy() {},
  internalModel: {
    originalWidth: 2048, originalHeight: 2048,
    coreModel,
    /**
     * 视线真正写进去的地方。bridge.js 的 setGaze() 直接写它 ——
     * 因为公开的 model.focus() 只取方向、模长恒为 1，"看正前方"表达不出来
     * （画布正中心 → atan2(0,0)=0 → focus(1,0) → 向右满偏）。
     * 这里的 x / y 就是归一化偏移本身（[-1,1]，正 y 向上）。
     */
    focusController: {
      focus(x, y, i) { rec.focus.push([x, y, i]); },
    },
    /**
     * 命中盒走 im.getDrawableBounds(i) —— 与 layout()/contentBounds() 同源的
     * "画布像素"空间（Core 的顶点是模型单位，两套坐标差了 PixelsPerUnit，见 bridge.js）。
     * mock 直接返回该 drawable 的顶点 AABB，于是这个空间就是 mock 顶点所在的空间，
     * 断言里的期望值不用跟着变。
     *
     * 注意只有 3 个 drawable：contentBounds() 要求 ≥4 个有效包围盒，
     * 所以它照旧返回 null、布局回落到整块画布 —— 与加这个函数之前一致。
     */
    getDrawableBounds(i) {
      const v = MOCK_DRAWABLES[i] && MOCK_DRAWABLES[i].verts;
      if (!v) return null;
      let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
      for (let k = 0; k < v.length; k += 2) {
        if (v[k] < x0) x0 = v[k];
        if (v[k] > x1) x1 = v[k];
        if (v[k + 1] < y0) y0 = v[k + 1];
        if (v[k + 1] > y1) y1 = v[k + 1];
      }
      return { x: x0, y: y0, width: x1 - x0, height: y1 - y0 };
    },
    expressionManager: {
      // 名字刻意与真实模型一致：带编号、空格不统一 —— 正是容易写错的地方
      definitions: [{ Name: '01黑脸' }, { Name: '03 生气' }, { Name: '06 0.0' }, { Name: '月卡' }],
      resetExpression() { rec.expressions.push('<reset>'); },
    },
    motionManager: {
      // PatOnce = 摸头动作组（4 档，由 tools/live2d_make_pat.py 生成）
      definitions: { Idle: [0, 1, 2], Tap: [0, 1], TransformOnce: [{}, {}], PatOnce: [0, 1, 2, 3] },
      // 序列推进依赖 motionFinish 事件与 state.currentGroup（事件在 complete() 之前触发）
      state: { currentGroup: undefined, currentIndex: undefined },
      on(evt, cb) { if (evt === 'motionFinish') onMotionFinish = cb; },
    },
    on(evt, cb) {
      if (evt === 'afterMotionUpdate') afterMotionUpdate = cb;
      if (evt === 'beforeModelUpdate') beforeModelUpdate = cb;
    },
  },
};

function mkEl() {
  return { textContent: '', classList: { add() {}, remove() {} }, style: {} };
}

/** window 上注册的监听器（摸头手势改在页面内捕获后，用例要真的派发事件） */
const winListeners = {};

const win = {
  innerWidth: 1080, innerHeight: 1920, devicePixelRatio: 2,
  addEventListener(type, cb) { (winListeners[type] = winListeners[type] || []).push(cb); },
  AndroidBridge: { onEvent: (type, payload) => rec.events.push([type, payload]) },
};

/** 派发一个 pointer 事件，走 bridge.js 真实的手势路径 */
function fire(type, x, y) {
  (winListeners[type] || []).forEach((cb) => cb({
    clientX: x, clientY: y, isPrimary: true, preventDefault() {},
  }));
}

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

  console.log('\n[12] 变身过场（一次性动作序列）');

  const mm = model.internalModel.motionManager;
  // 模拟"某条动作播完"：事件回调触发时 state.currentGroup 仍是刚播完的那一组
  const fireFinish = (group) => {
    mm.state.currentGroup = group;
    if (onMotionFinish) onMotionFinish();
    mm.state.currentGroup = undefined;
  };

  rec.motions.length = 0;
  check('playTransform("full") 启动序列', win.L2D.playTransform('full') === true);
  check('先播"进入"（索引 0，NORMAL 优先级）',
        rec.motions.length === 1 && rec.motions[0][1] === 0 &&
        rec.motions[0][0] === 'TransformOnce' &&
        rec.motions[0][2] === global.PIXI.live2d.MotionPriority.NORMAL,
        JSON.stringify(rec.motions));

  // 关键回归：Idle 组现在有动作，待机动作播完也会触发 motionFinish，
  // 不做组名判断的话序列会被提前推进
  fireFinish('Idle');
  check('待机动作播完不会推进变身序列', rec.motions.length === 1, JSON.stringify(rec.motions));

  fireFinish('TransformOnce');
  check('"进入"播完自动接"还原"（索引 1）',
        rec.motions.length === 2 && rec.motions[1][1] === 1, JSON.stringify(rec.motions));

  fireFinish('TransformOnce');
  check('序列播完就结束（debug 里 sequence 为 null）',
        rec.motions.length === 2 && win.L2D.debug().indexOf('"sequence":null') >= 0,
        JSON.stringify(rec.motions));

  // 过场期间压掉待机层：变身动作也写眉毛，不压会被后写的待机层盖掉
  rec.motions.length = 0;
  win.L2D.playTransform('out');
  tick(120);
  const mute = win.L2D.getIdle();
  check('过场期间待机层让位（值归零）',
        Object.keys(mute).every((k) => Math.abs(mute[k]) < 0.02), JSON.stringify(mute));
  check('"out" 只播"还原"（索引 1）',
        rec.motions.length === 1 && rec.motions[0][1] === 1, JSON.stringify(rec.motions));

  fireFinish('TransformOnce');
  tick(180);
  const back = win.L2D.getIdle();
  // 不写死具体数值（姿态随当前状态变），只要求"明显离开了 0"
  const moved = Object.keys(back).some((k) => Math.abs(back[k] - (mute[k] || 0)) > 0.05);
  check('过场结束后待机层恢复', moved, JSON.stringify(back));

  // 动作组缺失时必须"返回 false + 警告"，不能静默失败
  mm.definitions = { Idle: [0, 1, 2] };
  let warned = false;
  const origWarn = console.warn;
  console.warn = () => { warned = true; };
  const started = win.L2D.playTransform('full');
  console.warn = origWarn;
  check('动作组缺失时返回 false 并警告', started === false && warned,
        `started=${started} warned=${warned}`);
  // 恢复成完整的一组（含摸头的 PatOnce —— 少了它后面的档位用例会假失败）
  mm.definitions = {
    Idle: [0, 1, 2], Tap: [0, 1], TransformOnce: [{}, {}], PatOnce: [0, 1, 2, 3],
  };

  // ---- 自动恢复兜底：正常播完 / 中途被打断，都必须回到"没变过身"的参数上 ----
  // 正常路径靠 _2 自己还原 + 动作权重淡出，但序列可能被切后台之类打断，
  // 那时角色会停在"变到一半"（眼镜摘了、变身开着），所以要有参数级兜底。
  rec.motions.length = 0;
  clearParams();
  win.L2D.playTransform('full');
  fireFinish('TransformOnce');
  fireFinish('TransformOnce');
  tick(1);
  check('序列播完后把变身参数写回中性值',
        rec.params.key9 === 1 && rec.params.key11 === 0 && rec.params.Param172 === 0,
        JSON.stringify({ key9: rec.params.key9, key11: rec.params.key11, Param172: rec.params.Param172 }));

  // 卡住（motionFinish 再也没按我们的组名到达）：看门狗收尾 + 复位
  clearParams();
  win.L2D.playTransform('full');
  tick(500);      // 8s 远超 2x3.2s 的预算
  check('序列卡住时看门狗会收尾',
        win.L2D.debug().indexOf('"sequence":null') >= 0);
  check('看门狗收尾后同样复位变身参数',
        rec.params.key9 === 1 && rec.params.key11 === 0,
        JSON.stringify({ key9: rec.params.key9, key11: rec.params.key11 }));
  const woke = win.L2D.getIdle();
  check('序列卡住也不会让待机层永久让位',
        Object.keys(woke).some((k) => Math.abs(woke[k]) > 0.05), JSON.stringify(woke));

  console.log('\n[13] dispose 释放');
  win.L2D.dispose();
  check('dispose 后 ready 为 false', win.L2D.ready === false);

  // ========================================================================
  // [14] 形象档位（PROFILES）—— 并列预设的接线
  //
  // 上面的用例跑的是默认档位（银狼）。内置预设已并列化，宿主通过 ?profile=
  // 选档位；这里重新加载 bridge.js 并切到 DeepSeek 酱那档，验证：
  //   - 模型路径确实换了（不是仍加载银狼模型）
  //   - 该档位剔除了模型不存在的通道（smile / squint），不会写无效参数
  //   - 没有一次性演出（transform.enabled=false）→ playTransform 返回 false
  //   - 未知档位安全回落默认档，不抛异常
  // ========================================================================
  console.log('\n[14] 形象档位切换（PROFILES）');

  /**
   * 用指定 query 重新加载 bridge.js，等就绪后返回 ready 事件里的模型能力信息。
   *
   * 两个注意点：
   * 1. 必须 await：bridge.js 异步加载模型（Live2DModel.from(...).then(...)），
   *    同步读 rec.events 只会拿到空数组；
   * 2. 事件 payload 是 JSON **字符串**（notify 内部 JSON.stringify 过），
   *    要 parse 后才能取字段。
   */
  async function bootWith(search) {
    rec.events.length = 0;
    rec.params = {};
    win.location = { search };
    eval(BRIDGE);   // 覆盖 window.L2D，得到一份全新的桥接实例
    await sleep(40);
    const ready = rec.events.filter(([t]) => t === 'ready');
    if (!ready.length) return null;
    try { return JSON.parse(ready[ready.length - 1][1]); } catch (e) { return null; }
  }

  const dsInfo = await bootWith('?profile=deepseek');

  check('DeepSeek 档位加载成功并上报 ready',
        !!dsInfo, JSON.stringify(rec.events.map(([t]) => t)));
  check('档位切换后模型路径随之改变（不是仍加载银狼模型）',
        !!dsInfo && /dafeiyu/.test(dsInfo.modelUrl) && !/silverwolf/.test(dsInfo.modelUrl),
        dsInfo && dsInfo.modelUrl);
  check('模型路径来自档位而非 ?model= 兜底',
        !!dsInfo && dsInfo.modelUrl === 'models/dafeiyu/dafeiyu.model3.json',
        dsInfo && dsInfo.modelUrl);

  // 大肥鱼没有眯眼参数（ParamEyeLSquint / ParamEyeRSquint 不存在），也没有眉毛上下
  // （ParamBrowLY / ParamBrowRY 不存在，只有 ParamBrow*Form），档位里剔除了这两条通道。
  // 反过来 smile（ParamEyeLSmile / ParamEyeRSmile）**存在**，所以必须保留 ——
  // 上一版模型没有它，这两条断言是换模型之后反转过来的。
  //
  // ⚠️ 回归：这里以前是「正则抠出 profile 片段 → 看里面有没有 `smile:`」，
  //    而那个片段正则**从来就没匹配上**（拿到的永远是空串），于是"不含 smile"是
  //    **空集恒真**的 —— 换成肯定式断言才暴露。所以下面先断言片段真的抠到了。
  const dsSrc = fs.readFileSync(BRIDGE_PATH, 'utf8');
  const dsAt = dsSrc.search(/\n {4}deepseek:\s*\{/);
  const dsRest = dsAt >= 0 ? dsSrc.slice(dsAt + 1) : '';
  const dsNext = dsRest.slice(1).search(/\n {4}[a-zA-Z_$][\w$]*:\s*\{/);
  const dsProfile = dsNext >= 0 ? dsRest.slice(0, dsNext + 1) : dsRest;
  const dsChannels = (dsProfile.match(/channels:\s*\{([\s\S]*?)\n\s*\}/) || [])[1] || '';
  check('抠到了 DeepSeek 档位的源码片段（空串会让下面几条断言空集恒真）',
        dsProfile.length > 0 && /channels:/.test(dsProfile), `${dsProfile.length} 字符`);
  check('DeepSeek 档位的待机通道不含 squint（该模型无此参数）',
        !/\bsquint\s*:/.test(dsChannels), dsChannels.replace(/\s+/g, ' ').slice(0, 160));
  check('DeepSeek 档位的待机通道不含 brow（该模型只有 ParamBrow*Form）',
        !/\bbrow\s*:/.test(dsChannels), dsChannels.replace(/\s+/g, ' ').slice(0, 160));
  check('DeepSeek 档位保留 smile（该模型有 ParamEyeLSmile / ParamEyeRSmile）',
        /\bsmile\s*:/.test(dsChannels), dsChannels.replace(/\s+/g, ' ').slice(0, 160));
  check('DeepSeek 档位保留 browForm（该模型唯一的眉毛参数）',
        /\bbrowForm\s*:/.test(dsChannels), dsChannels.replace(/\s+/g, ' ').slice(0, 160));
  // amp / drift / flick 的键必须与 channels 同名，否则 applyPat 里 `amp[name]` 取不到值
  check('DeepSeek 档位的摸头幅度写在 browForm 上（不是 brow）',
        /amp:\s*\{[^}]*\bbrowForm\s*:/.test(dsProfile),
        (dsProfile.match(/amp:\s*\{[^}]*\}/) || [''])[0]);

  // 实证：跑一段时间，确认那些参数一次都没被写过
  win.L2D.setState('listening');
  tick(240);
  check('运行期确实没有写入 squint 参数（该模型没有它）',
        !('ParamEyeLSquint' in rec.params),
        JSON.stringify(Object.keys(rec.params)));
  check('运行期确实写入了 smile 参数（该模型有它，通道有效）',
        'ParamEyeLSmile' in rec.params,
        JSON.stringify(Object.keys(rec.params)));

  check('DeepSeek 档位没有变身演出 → playTransform 返回 false',
        win.L2D.playTransform('full') === false);
  check('无演出时不会误排动作序列', win.L2D.debug().indexOf('"sequence":null') >= 0);

  // ----------------------------------------------------------------------
  // 关键回归：待机 / 呼吸写入的参数**不能是物理输出**
  //
  // 物理每帧都会覆写自己的输出参数，待机层写上去等于没写（静默失效，
  // 不报错、参数读回还是自己的值，只是渲染时被物理盖掉）。
  // 实测踩到过：DeepSeek 酱的 ParamBodyAngleZ / ParamBodyAngleX 都是物理输出
  // （physics3.json setting3 / setting1，权重 100），银狼的则不是 ——
  // 所以这条必须按档位、对着各自的 physics3.json 逐参数校验。
  // ----------------------------------------------------------------------
  function physicsOutputs(modelDirName, physicsFile) {
    const p = path.join(__dirname, '..', 'app', 'src', 'main', 'assets',
                        'live2d', 'models', modelDirName, physicsFile);
    if (!fs.existsSync(p)) return null;
    const phys = JSON.parse(fs.readFileSync(p, 'utf8'));
    const outs = new Set();
    for (const st of phys.PhysicsSettings || []) {
      for (const o of st.Output || []) outs.add(o.Destination.Id);
    }
    return outs;
  }

  /** 从 bridge.js 源码里抠出某个档位的 idle.channels 参数名 */
  function profileIdleParams(profileId) {
    const src = fs.readFileSync(BRIDGE_PATH, 'utf8');
    // 找到 `<profileId>: {` 到下一个同级档位（或文件末尾）之间的片段
    const start = src.search(new RegExp('\\n\\s{4}' + profileId + ':\\s*\\{'));
    if (start < 0) return [];
    const rest = src.slice(start + 1);
    const next = rest.slice(1).search(/\n {4}[a-zA-Z_$][\w$]*:\s*\{/);
    const seg = next >= 0 ? rest.slice(0, next + 1) : rest;
    const ch = (seg.match(/channels:\s*\{([\s\S]*?)\n\s*\}/) || [])[1] || '';
    return [...ch.matchAll(/'([A-Za-z_][\w]*)'/g)].map((m) => m[1]);
  }

  /** 从 bridge.js 源码里抠出某个档位的 breath 幅度 */
  function profileBreath(profileId) {
    const src = fs.readFileSync(BRIDGE_PATH, 'utf8');
    const start = src.search(new RegExp('\\n\\s{4}' + profileId + ':\\s*\\{'));
    if (start < 0) return null;
    const rest = src.slice(start + 1);
    const next = rest.slice(1).search(/\n {4}[a-zA-Z_$][\w$]*:\s*\{/);
    const seg = next >= 0 ? rest.slice(0, next + 1) : rest;
    const br = (seg.match(/breath:\s*\{([\s\S]*?)\n\s*\}/) || [])[1] || '';
    const num = (k) => {
      const m = br.match(new RegExp(k + ':\\s*(-?[\\d.]+)'));
      return m ? Number(m[1]) : 0;
    };
    return {
      angleX: num('angleX'), angleY: num('angleY'), angleZ: num('angleZ'),
      bodyAngleX: num('bodyAngleX'), breath: num('breath'),
    };
  }

  // 键 = profile id（用来解析 bridge.js 里的档位），值 = [模型目录, 物理文件名]。
  // deepseek 档的形象已经换成大肥鱼，所以目录与物理文件名都跟 profile id 不一样了。
  const PROFILE_MODELS = {
    silverwolf: ['silverwolf', 'silverwolf.physics3.json'],
    deepseek: ['dafeiyu', 'dafeiyu.physics3.json'],
  };

  for (const [pid, [dir, physFile]] of Object.entries(PROFILE_MODELS)) {
    const outs = physicsOutputs(dir, physFile);
    if (!outs) { console.log(`  (跳过 ${pid} 物理交叉校验：本地没有模型文件)`); continue; }

    const idleParams = profileIdleParams(pid);
    check(`${pid}: 解析到待机通道`, idleParams.length > 0, idleParams.join(','));
    const badIdle = idleParams.filter((id) => outs.has(id));
    check(`${pid}: 待机通道都不是物理输出`,
          badIdle.length === 0, badIdle.join(','));

    const br = profileBreath(pid);
    const breathParams = [
      ['angleX', 'ParamAngleX'], ['angleY', 'ParamAngleY'],
      ['angleZ', 'ParamAngleZ'], ['bodyAngleX', 'ParamBodyAngleX'],
      ['breath', 'ParamBreath'],
    ].filter(([k]) => br && br[k]);
    const badBreath = breathParams.filter(([, id]) => outs.has(id)).map(([k, id]) => `${k}(${id})`);
    check(`${pid}: 呼吸接管的参数都不是物理输出`,
          badBreath.length === 0, badBreath.join(','));
  }

  // 未知档位必须安全回落，而不是让形象整个加载失败
  const fallbackInfo = await bootWith('?profile=__nonexistent__');
  check('未知档位安全回落默认档（银狼）',
        !!fallbackInfo && /silverwolf/.test(fallbackInfo.modelUrl),
        fallbackInfo && fallbackInfo.modelUrl);

  // ?model= 仍然要能覆盖档位里的模型路径（自定义模型入口）
  const overrideInfo = await bootWith(
    '?profile=deepseek&model=' + encodeURIComponent('models/haru/haru_greeter_t03.model3.json'));
  check('?model= 能覆盖档位内的模型路径',
        !!overrideInfo && /haru/.test(overrideInfo.modelUrl), overrideInfo && overrideInfo.modelUrl);

  // ========================================================================
  console.log('\n[15] 摸头反应（部件命中盒 + 页面内手势）');

  rec.events.length = 0;
  const swInfo = await bootWith('?profile=silverwolf');
  check('银狼档位加载成功并上报 ready', !!swInfo, JSON.stringify(rec.events.map(([t]) => t)));
  check('ready 信息带上摸头能力（宿主不再装触摸层，只看部件盒算不算得出来）',
        !!swInfo && !!swInfo.pat && swInfo.pat.enabled === true && swInfo.pat.boxReady === true,
        swInfo && JSON.stringify(swInfo.pat));
  check('银狼档位的 headParts 与 dump 脚本一致（13 个，不含头发）',
        !!swInfo && swInfo.pat.headParts === 13, swInfo && String(swInfo.pat.headParts));
  check('ready 信息带上动作组与档位表（排查"摸头没什么动静"第一眼看它）',
        !!swInfo && swInfo.pat.motionGroup === true && swInfo.pat.tiers === 4,
        swInfo && JSON.stringify({ g: swInfo.pat.motionGroup, t: swInfo.pat.tiers }));

  // 命中盒必须是「头部部件顶点的并集」，且**不含**身体部件的远端顶点。
  // 期望值直接由 mock 顶点算出（含 8% 外扩），不写死屏幕像素：
  //   头(900~1100, 300~500) ∪ 五官(920~1080, 500~620) = (900~1100, 300~620)
  const PAD = 0.08;
  const expBox = {
    x: 900 - 200 * PAD, y: 300 - 320 * PAD,
    width: 200 * (1 + 2 * PAD), height: 320 * (1 + 2 * PAD),
  };
  const near = (a, b) => Math.abs(a - b) < 0.01;
  const dbg = JSON.parse(win.L2D.debugPatHit(true));
  const mb = dbg.contentBox;
  check('命中盒由头部部件顶点算出（不是兜底矩形）',
        !!dbg.box && dbg.box.w > 0 && dbg.box.h > 0, JSON.stringify(dbg.box));
  check('命中盒 = 头 ∪ 五官（挂在子部件上的顶点靠祖先链算进来）',
        !!mb && near(mb.x, expBox.x) && near(mb.x + mb.width, expBox.x + expBox.width) &&
        near(mb.y, expBox.y) && near(mb.y + mb.height, expBox.y + expBox.height),
        JSON.stringify(mb) + ' 期望 ' + JSON.stringify(expBox));
  check('身体部件的远端顶点没有漏进命中盒',
        !!mb && mb.x + mb.width < 2000 && mb.y + mb.height < 2000, JSON.stringify(mb));

  // 命中点从**上报的盒子**推出来，而不是写死坐标：换布局、换模型都不会让用例失效
  const hitPt = { x: dbg.box.x + dbg.box.w / 2, y: dbg.box.y + dbg.box.h / 2 };
  const isPlaying = ([t, p]) => t === 'pat' && JSON.parse(p).playing === true;

  rec.events.length = 0;
  check('摸头：命中头部盒中心时触发', win.L2D.patHead(hitPt) === true, JSON.stringify(hitPt));
  // 冷却（0.25s）只吞"同一下按压被识别成两下"的抖动，所以紧接着的第二下必须被忽略；
  // 但它是**防抖**不是防连摸 —— 连点计数靠的就是每一下都算数（见第 16 节）
  check('冷却窗口内的第二下被忽略（防抖）', win.L2D.patHead(hitPt) === false);
  check('摸头：点底部不触发（返回 false）', win.L2D.patHead({ x: 0.5, y: 0.99 }) === false);
  check('未命中也会回报原因（不再有"点了没反应却查不到"）',
        rec.events.some(([t, p]) => t === 'pat' && JSON.parse(p).hit === false),
        JSON.stringify(rec.events.filter(([t]) => t === 'pat')));

  tick(20);
  const dbg2 = JSON.parse(win.L2D.debug());
  check('摸头期间 pat.active 为真', !!(dbg2.pat && dbg2.pat.active), JSON.stringify(dbg2.pat && dbg2.pat.active));
  check('摸头确实写入了头部侧倾参数（ParamAngleZ）',
        'ParamAngleZ' in rec.params, JSON.stringify(Object.keys(rec.params)));
  check('摸头确实写入了眉毛参数（ParamBrowLY）',
        'ParamBrowLY' in rec.params, JSON.stringify(Object.keys(rec.params)));
  // 表情只在没有 LLM 情绪表情时才套 —— 这是「不抢 LLM 表达」的硬约定
  const patEvents = rec.events.filter(isPlaying);
  check('命中时下发一次 pat 事件，并带上脸红表情',
        patEvents.length === 1 && JSON.parse(patEvents[0][1]).expression === '02 脸红爱心',
        JSON.stringify(patEvents));
  check('摸头绝不会碰口型通道（ParamMouthOpenY 不参与）',
        !('ParamMouthOpenY' in rec.params) || rec.params.ParamMouthOpenY === 0,
        String(rec.params.ParamMouthOpenY));

  // 冷却：紧接着再点一次必须被忽略（这里已经过了 0.25s，所以改用显式档位验证不受影响）
  tick(120);
  check('摸头演出结束后叠加值归零',
        (() => {
          const p = JSON.parse(win.L2D.debug()).pat;
          return p && !p.active && Object.values(p.values || {}).every((v) => Math.abs(v) < 1e-3);
        })());

  // 关掉的替换件（部件 opacity = 0）不参与命中盒
  tick(120);
  MOCK_PART_OPACITY[1] = 0;
  const dbgOp = JSON.parse(win.L2D.debugPatHit(true));
  check('关掉的替换件不参与命中盒（脸部件 opacity=0 → 盒子收缩到五官那一块）',
        !!dbgOp.contentBox && dbgOp.contentBox.x > 900, JSON.stringify(dbgOp.contentBox));
  MOCK_PART_OPACITY[1] = 1;

  // 拿不到 Core 数据时回落到兜底矩形 —— 不能因此变成"点了没反应"
  tick(120);
  const savedRaw = coreModel._model;
  coreModel._model = null;
  const dbgFb = JSON.parse(win.L2D.debugPatHit(true));
  check('拿不到 Core 部件数据时回落到兜底矩形',
        dbgFb.box === null && !!dbgFb.fallback,
        JSON.stringify({ box: dbgFb.box, fallback: dbgFb.fallback }));
  const fbPt = {
    x: dbgFb.fallback.x + dbgFb.fallback.w / 2,
    y: dbgFb.fallback.y + dbgFb.fallback.h / 2,
  };
  rec.events.length = 0;
  check('兜底矩形仍可命中（数据缺失不等于点了没反应）',
        win.L2D.patHead(fbPt) === true, JSON.stringify(fbPt));
  coreModel._model = savedRaw;

  // ---- 页面内手势：按住 + 滑动（宿主零参与）----
  //
  // 为什么不是轻点：摸头是持续的接触动作。轻点时手指落下就走，
  // 舞台上任何一次误触都算摸头，还容易连点刷档位。
  const VW = 1080, VH = 1920;
  const px = (n) => n * VW;
  const py = (n) => n * VH;
  const srcOf = (ev) => JSON.parse(ev[1]).source;

  tick(200);   // 先把上一节的连点窗口熬过去
  rec.events.length = 0;
  fire('pointerdown', px(hitPt.x), py(hitPt.y));
  fire('pointerup', px(hitPt.x), py(hitPt.y));
  check('轻点不触发（摸头是"按住+滑动"，不是戳一下）',
        !rec.events.some(isPlaying), JSON.stringify(rec.events));

  tick(200);
  rec.events.length = 0;
  fire('pointerdown', px(hitPt.x), py(hitPt.y));
  tick(14);    // 14 帧 ≈ 224ms > pressDelay 180ms
  const holdEv = rec.events.filter(isPlaying).pop();
  check('按住 0.18s 触发第一下（source=hold）',
        !!holdEv && srcOf(holdEv) === 'hold', JSON.stringify(rec.events));

  // 按住期间滑动 → 再揉一下，档位继续涨
  const tierBefore = holdEv ? JSON.parse(holdEv[1]).tier : -1;
  tick(14);    // 让冷却(0.2s)与最小间隔(0.14s)都过去
  rec.events.length = 0;
  fire('pointermove', px(hitPt.x) + 60, py(hitPt.y));
  const strokeEv = rec.events.filter(isPlaying).pop();
  check('按住后滑动 60px 算又揉了一下（source=stroke，档位 +1）',
        !!strokeEv && srcOf(strokeEv) === 'stroke' &&
        JSON.parse(strokeEv[1]).tier === tierBefore + 1,
        JSON.stringify(rec.events));

  // 滑出头部盒：不算（手指滑到她腿上不是摸头）
  tick(14);
  rec.events.length = 0;
  fire('pointermove', px(0.5), py(0.99));
  check('滑出头部不算摸头', !rec.events.some(isPlaying), JSON.stringify(rec.events));

  // 松手：结束这次接触
  tick(14);
  fire('pointerup', px(0.5), py(0.99));

  // 真机踩过：手指一放上去就开始揉，180ms 内已经移动了几十像素 ——
  // 按位移取消会把这种正常操作误杀成"按了没反应"
  tick(200);
  rec.events.length = 0;
  fire('pointerdown', px(hitPt.x), py(hitPt.y));
  fire('pointermove', px(hitPt.x) + 20, py(hitPt.y) + 20);   // 还没按够就先滑了
  tick(14);
  check('按够时间之前就滑动，不会取消这次接触',
        rec.events.some(isPlaying), JSON.stringify(rec.events));

  // 松手后移动不再触发
  fire('pointerup', px(hitPt.x) + 20, py(hitPt.y) + 20);
  tick(14);
  rec.events.length = 0;
  fire('pointermove', px(hitPt.x), py(hitPt.y));
  check('松手后移动不再触发（摸头只发生在按住期间）',
        !rec.events.some(isPlaying), JSON.stringify(rec.events));

  // 点在头部盒之外（下半身那一片）不该触发
  tick(120);
  check('摸头：点头部盒之外不触发', win.L2D.patHead({ x: 0.5, y: 0.95 }) === false);

  // LLM 情绪表情在场时不许抢戏
  // （先熬过连点窗口，否则自动档位会接着上一节从第 3 档开始，测的就不是"前几档"了）
  win.L2D.setExpression('01黑脸');
  tick(400);
  rec.events.length = 0;
  check('有 LLM 情绪表情时摸头仍然出动作（姿势不抢戏、但动作要有）',
        win.L2D.patHead(hitPt) === true);
  const withCue = rec.events.filter(isPlaying);
  check('有 LLM 情绪表情时前几档不下发自己的表情（不覆盖 _cue）',
        withCue.length === 1 && JSON.parse(withCue[0][1]).expression === null,
        JSON.stringify(withCue));
  // 但最后一档"不耐烦"要盖过 LLM 的表情：被摸烦了还挂着笑脸说不通
  tick(200);
  rec.events.length = 0;
  check('最后一档盖过 LLM 的情绪表情（不耐烦优先）',
        win.L2D.patHead({ x: hitPt.x, y: hitPt.y, tier: 3 }) === true &&
        rec.events.filter(isPlaying).some((ev) => JSON.parse(ev[1]).expression === '03 生气'),
        JSON.stringify(rec.events.filter(isPlaying)));
  win.L2D.setExpression(null);

  // ========================================================================
  console.log('\n[16] 摸头档位（连点会不耐烦）');

  // 每档都该播对应的动作文件（PatOnce 第 N 条，FORCE 抢占待机动作）
  const tierOf = (ev) => JSON.parse(ev[1]).tier;
  const playOf = (ev) => JSON.parse(ev[1]);
  // 先把上一节留下的连点计数熬过去（静置 > comboWindow 5s），否则档位不从 0 开始
  tick(400);
  rec.events.length = 0;
  rec.motions.length = 0;
  const tierSeen = [];
  const motionSeen = [];
  const holdSeen = [];
  for (let n = 0; n < 4; n++) {
    if (n > 0) tick(30);   // 30 帧 ≈ 480ms > 冷却 0.25s，但远小于连点窗口 5s
    win.L2D.patHead(hitPt);
    const ev = rec.events.filter(isPlaying).pop();
    if (ev) { tierSeen.push(tierOf(ev)); holdSeen.push(playOf(ev).holdMs); }
    const m = rec.motions[rec.motions.length - 1];
    motionSeen.push(m ? m[1] : null);
  }
  check('连点四下的档位依次递进 0→1→2→3',
        JSON.stringify(tierSeen) === JSON.stringify([0, 1, 2, 3]), JSON.stringify(tierSeen));
  check('每一档都播了 PatOnce 里对应的动作（index 跟着档位走）',
        JSON.stringify(motionSeen) === JSON.stringify([0, 1, 2, 3]), JSON.stringify(motionSeen));
  check('摸头动作以 FORCE 优先级播放（否则被待机动作压住）',
        rec.motions.every((m) => m[0] === 'PatOnce' && m[2] === 3),
        JSON.stringify(rec.motions));
  check('第 4 档的表情是"生气"且保持 9s（不高兴残留）',
        holdSeen[3] === 9000, JSON.stringify(holdSeen));
  const dbgTier = JSON.parse(win.L2D.debug()).pat;
  check('第 4 档之后进入不高兴残留状态',
        dbgTier.sulk === true && dbgTier.tier === 3,
        JSON.stringify({ sulk: dbgTier.sulk, tier: dbgTier.tier }));
  tick(20);   // 让残留姿势收敛几帧（刚触发那一帧还没跑过 updatePat）
  check('残留期间姿势不回中性（歪头 + 眉压真的写进了通道）',
        (() => {
          const v = JSON.parse(win.L2D.debug()).pat.values;
          return (v.tilt || 0) > 1.2 && (v.browForm || 0) > 0.05;
        })(), JSON.stringify(JSON.parse(win.L2D.debug()).pat.values));

  // 静置超过连点窗口 → 重新从第 1 档开始（也顺带把残留熬过去）
  tick(700);   // 700 帧 ≈ 11s > sulkDuration 9s，且 > comboWindow 5s
  rec.events.length = 0;
  win.L2D.patHead(hitPt);
  const afterIdle = rec.events.filter(isPlaying).pop();
  check('静置超过连点窗口后重新从第 1 档开始',
        !!afterIdle && tierOf(afterIdle) === 0, afterIdle && afterIdle[1]);
  const dbgAfter = JSON.parse(win.L2D.debug()).pat;
  check('残留期满后姿势收敛回中性（不再挂着不高兴）',
        dbgAfter.sulk === false, JSON.stringify({ sulk: dbgAfter.sulk }));

  // 模型没有 PatOnce 组时：只播程序化叠层，退化但不报错
  tick(120);
  const savedDefs = model.internalModel.motionManager.definitions;
  delete model.internalModel.motionManager.definitions.PatOnce;
  rec.events.length = 0;
  rec.motions.length = 0;
  const noGroupOk = win.L2D.patHead(hitPt) === true;
  const noGroupEv = rec.events.filter(isPlaying).pop();
  check('没有 PatOnce 组时仍然触发（只剩程序化叠层，不报错）',
        noGroupOk && !!noGroupEv && playOf(noGroupEv).motion === false,
        JSON.stringify(noGroupEv));
  check('没有动作组时不会去调 model.motion', rec.motions.length === 0,
        JSON.stringify(rec.motions));
  model.internalModel.motionManager.definitions = savedDefs;

  // 变身过场期间不抢动作（那套演出在写同一批通道，插进去会把过场顶坏）
  tick(200);
  win.L2D.playTransform('full');
  tick(2);
  rec.motions.length = 0;
  rec.events.length = 0;
  win.L2D.patHead(hitPt);
  check('变身过场期间摸头不抢动作文件（过场优先）',
        rec.motions.every((m) => m[0] !== 'PatOnce'), JSON.stringify(rec.motions));
  tick(400);   // 等过场结束

  // ========================================================================
  console.log('\n[17] 摸头不再进提示词（回归守卫）');
  //
  // 摸头是纯视觉互动：不产生语音、不进历史、不影响对话。
  // 旧实现用 AppModule 里一个全局 AtomicBoolean 把"刚被摸头"捎带进下一轮
  // 系统提示词 —— 它挂断不清、跨角色串味、"刚刚"还可能是几分钟前。
  // 这里直接查 Kotlin 源码，防止它哪天又被加回来。
  const kt = (rel) => fs.readFileSync(path.join(
    __dirname, '..', 'app', 'src', 'main', 'java', 'com', 'lv999call', 'app', rel), 'utf8');
  const useCaseSrc = kt('domain/usecase/ProcessAudioUseCase.kt');
  const appModuleSrc = kt('di/AppModule.kt');
  const callVmSrc = kt('ui/call/CallViewModel.kt');
  const callScreenSrc = kt('ui/call/CallScreen.kt');
  check('ProcessAudioUseCase 不再注入摸头提示词',
        !/HEAD_PAT_NOTE|headPatPending/.test(useCaseSrc));
  check('AppModule 不再持有摸头标记', !/headPatPending/.test(appModuleSrc));
  check('CallViewModel 不再有 onHeadPat', !/onHeadPat/.test(callVmSrc));
  check('CallScreen 不再自己装触摸层（手势在页面内）',
        !/PAT_HEAD_ZONE|detectTapGestures/.test(callScreenSrc));
  check('署名链接已移出舞台 Box（不再与 WebView 抢触摸）',
        /Live2DAuthorCredit/.test(callScreenSrc));

  // ========================================================================
  console.log('\n[18] 视线：直接写归一化偏移，不走 model.focus（回归守卫）');
  //
  // 这个 API 坑了两次，两次的现象完全不同：
  //   ① 旧实现调 `model.focus(fx, fy)`，运行库把参数当**世界坐标里的点**，
  //      只取「画布中心 → 该点」的方向、**模长恒为 1**。传 ±0.09 这种小偏移
  //      几乎等于世界原点 → 舞台左上角 → 两个角色都**满偏地盯着左上角**
  //      （ParamAngleX -21°、ParamEyeBallY +0.707）；而 applyState() 里那句
  //      「视线瞬时归位」`model.focus(0, 0, true)` 归的是同一个角，救不回来。
  //   ② 改成"把偏移反解成世界坐标点"之后仍不对：**画布正中心是这个 API 的奇点** ——
  //      `atan2(0, 0) = 0` → `focus(cos 0, -sin 0) = (1, 0)`，也就是**向右满偏**
  //      （真机截图：两只眼珠都偏在眼白右侧）。"看正前方"用公开 API 根本表达不出来。
  //
  // 结论：视线只能直接写 `internalModel.focusController`（那才是归一化偏移的原生单位，
  // [-1,1]，直接当 ParamEyeBallX/Y 的加量、再 ×30 加到 ParamAngleX/Y）。
  // 下面三条断言分别锁住：归位 = (0,0)、游移幅度 = 配置振幅、**没走公开 API**。
  const im = model.internalModel;
  win.L2D.setState('thinking');           // applyState 里有一次「视线瞬时归位」
  const gzReset = rec.focus[rec.focus.length - 1];
  check('视线归位写的是归一化偏移 (0, 0)（0 就是正前方）',
        !!gzReset && Math.abs(gzReset[0]) < 1e-9 && Math.abs(gzReset[1]) < 1e-9,
        JSON.stringify(gzReset));
  check('归位走瞬时插值（instant = true）', !!gzReset && gzReset[2] === true,
        JSON.stringify(gzReset));

  // 游移幅度：idle 档 focus=0.25 × 振幅 0.35 / 0.2 → 0.0875 / 0.05，留一点浮点余量。
  // ① 的实现下这里会是 1.0（满偏），所以这条断言能真的逮住回归。
  win.L2D.setState('idle');
  tick(120);
  const gzDrift = rec.focus[rec.focus.length - 1];
  check('游移幅度不超过配置振幅（x ≤ 0.09 / y ≤ 0.06）',
        !!gzDrift && Math.abs(gzDrift[0]) <= 0.09 + 1e-6 && Math.abs(gzDrift[1]) <= 0.06 + 1e-6,
        JSON.stringify(gzDrift));

  const tail = rec.focus.slice(-60);
  const distinct = new Set(tail.map((p) => `${p[0].toFixed(4)},${p[1].toFixed(4)}`)).size;
  check('游移真的在动（不是钉死在一个点）', distinct > 5, `不同位置 ${distinct} 个`);

  check('视线没有走公开的 model.focus（它表达不了"正前方"，会满偏）',
        rec.focusWorldPoint.length === 0, JSON.stringify(rec.focusWorldPoint.slice(0, 3)));

  // 拿不到 focusController 时才允许退回公开 API（那条路会满偏，但不会崩）
  const savedFc = im.focusController;
  delete im.focusController;
  win.L2D.setState('idle');
  tick(10);
  check('拿不到 focusController 时退回公开 API 且不抛异常',
        rec.focusWorldPoint.length > 0, JSON.stringify(rec.focusWorldPoint.slice(0, 2)));
  im.focusController = savedFc;

  // ========================================================================
  console.log('\n[19] 内容盒必须裁到画布范围内（回归守卫）');
  //
  // mock 只有 3 个 drawable，而 contentBounds() 要求 ≥4 个有效包围盒才认，
  // 所以那条路径在自测里走的是"整块画布"的分支 —— 覆盖不到。这里退一步做
  // **源码级守卫**（与 [17] 查 Kotlin 源码同一手法）。
  //
  // 为什么这条重要：VTS 风格模型把道具藏在**画布之外**（靠开关参数把网格挪走，
  // 不是靠透明度），3%~97% 分位 trim 拦不住 —— 内容盒会被撑到比画布还大
  // （大肥鱼实测 3511×5925 vs 画布 4704×5348）。而 CallScreen 是按内容盒宽高比
  // 选适配边的，于是角色缩成小小一只杵在中间。删掉那一刀不会有任何报错，
  // 只在真机上"看起来有点小"，所以只能靠守卫拦住。
  //
  // ⚠️ 别改成"先剔除 opacity ≤ 0.01 的网格再取分位"：分位是按网格**数量**取的，
  //    剔掉隐藏网格会让分位点整体下移、盒子在顶部变紧 —— 实测把银狼的发顶和
  //    蓝色天线切掉了（y0 从 561 抬到 789）。那个 3% 余量本来就在替少数几根
  //    发丝兜底，动不得。
  const cbSrc = (fs.readFileSync(BRIDGE_PATH, 'utf8')
    .match(/function contentBounds\(\)[\s\S]*?\n  \}/) || [''])[0];
  check('抠到了 contentBounds 源码片段', cbSrc.length > 0, `${cbSrc.length} 字符`);
  check('contentBounds 把盒子裁到画布内（y1 > ch → y1 = ch）',
        /y1\s*>\s*ch\s*\)\s*y1\s*=\s*ch/.test(cbSrc), '未找到裁剪');
  check('contentBounds 把盒子裁到画布内（x0 < 0 → x0 = 0）',
        /x0\s*<\s*0\s*\)\s*x0\s*=\s*0/.test(cbSrc), '未找到裁剪');
  check('contentBounds 没有按 opacity 剔除网格（那会把银狼的发顶切掉）',
        !/opacities\[i\]\s*<=\s*0\.01/.test(cbSrc), '不要按透明度过滤');

  console.log(`\n${'='.repeat(46)}`);
  console.log(`通过 ${pass} / 失败 ${fail}`);
  process.exit(fail === 0 ? 0 : 1);
})();
