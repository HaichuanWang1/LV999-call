/**
 * Live2D 降级路径测试
 *
 * 验证资源缺失/加载失败时，bridge.js 必须把错误上报给 Android 侧，
 * 否则 CallScreen 会一直停在 LOADING 而显示空白（而非静态头像）。
 *
 * 运行：node tools/live2d_fallback_test.cjs
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const BRIDGE = fs.readFileSync(
  path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'live2d', 'js', 'bridge.js'),
  'utf8'
);

let pass = 0, fail = 0;
function check(name, cond, extra = '') {
  if (cond) { pass++; console.log('  ✓ ' + name); }
  else { fail++; console.log('  ✗ ' + name + (extra ? '  → ' + extra : '')); }
}

/** 在隔离沙箱里用指定 PIXI 环境跑一遍 bridge.js，返回收到的事件 */
function runScenario(name, { makePixi }) {
  const events = [];
  const sandbox = {
    console: { log() {}, warn() {}, error() {} },
    setTimeout, clearTimeout, Math, JSON, Date, Promise, Number, isFinite, Object, Array, String,
    performance: { now: () => 0 },
  };
  sandbox.window = {
    innerWidth: 1080, innerHeight: 1920, devicePixelRatio: 2,
    addEventListener() {},
    AndroidBridge: { onEvent: (type, payload) => events.push([type, payload]) },
  };
  sandbox.document = {
    readyState: 'complete', hidden: false,
    getElementById: () => ({ textContent: '', classList: { add() {}, remove() {} }, style: {} }),
    addEventListener() {},
  };
  sandbox.PIXI = makePixi();

  vm.createContext(sandbox);
  try { vm.runInContext(BRIDGE, sandbox, { filename: 'bridge.js' }); }
  catch (e) { events.push(['__throw__', e.message]); }
  return events;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  console.log('\n[A] 模型加载失败（文件缺失 / 404）');
  {
    const events = runScenario('model-reject', {
      makePixi: () => ({
        Application: class { constructor() {
          this.screen = { width: 1080, height: 1920 };
          this.stage = { addChild() {} };
          this.renderer = { resize() {} };
          this.ticker = { stop() {}, start() {} };
        } destroy() {} },
        live2d: {
          MotionPriority: { IDLE: 1 },
          Live2DModel: { from: () => Promise.reject(new Error('404 Not Found')) },
        },
      }),
    });
    await sleep(30);
    const err = events.find(([t]) => t === 'error');
    check('上报了 error 事件', !!err, JSON.stringify(events));
    check('error 事件带 message 字段', !!err && /404|加载失败/.test(err[1]), err && err[1]);
    check('未误报 ready', !events.some(([t]) => t === 'ready'));
  }

  console.log('\n[B] 运行库缺失（lib/ 未下载）');
  {
    const events = runScenario('no-pixi', { makePixi: () => undefined });
    await sleep(30);
    const err = events.find(([t]) => t === 'error');
    check('PIXI 缺失时上报 error', !!err, JSON.stringify(events));
    check('提示 PixiJS 未加载', !!err && /PixiJS/.test(err[1]), err && err[1]);
  }

  console.log('\n[C] Live2D 运行库缺失（core/cubism4 未下载）');
  {
    const events = runScenario('no-live2d-lib', { makePixi: () => ({}) });
    await sleep(30);
    const err = events.find(([t]) => t === 'error');
    check('上报 error', !!err, JSON.stringify(events));
    check('提示 Live2D 运行库未加载', !!err && /Live2D/.test(err[1]), err && err[1]);
  }

  console.log('\n[D] WebGL 初始化抛异常');
  {
    const events = runScenario('no-webgl', {
      makePixi: () => ({
        Application: class { constructor() { throw new Error('WebGL not supported'); } destroy() {} },
        live2d: {
          MotionPriority: { IDLE: 1 },
          Live2DModel: { from: () => Promise.resolve({}) },
        },
      }),
    });
    await sleep(30);
    const err = events.find(([t]) => t === 'error');
    check('WebGL 失败时上报 error', !!err, JSON.stringify(events));
    check('未抛出未捕获异常', !events.some(([t]) => t === '__throw__'),
          JSON.stringify(events.filter(([t]) => t === '__throw__')));
  }

  console.log(`\n${'='.repeat(46)}`);
  console.log(`通过 ${pass} / 失败 ${fail}`);
  process.exit(fail === 0 ? 0 : 1);
})();
