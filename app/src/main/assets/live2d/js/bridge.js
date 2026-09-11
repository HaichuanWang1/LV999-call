/**
 * Live2D 通话形象桥接层
 * ---------------------------------------------------------------
 * 职责：
 *   1. 初始化 PixiJS + Live2D 模型渲染
 *   2. 暴露 window.L2D 给 Android 侧通过 evaluateJavascript 调用
 *   3. 通过 window.AndroidBridge.onEvent(type, json) 回传事件
 *
 * 通信协议
 *   Android → JS :  window.L2D.setState('speaking') / setMouth(0.6) / ...
 *   JS → Android :  AndroidBridge.onEvent('ready'|'error'|'info', jsonStr)
 * ---------------------------------------------------------------
 */
(function () {
  'use strict';

  // ============================ 配置 ============================
  var CFG = {
    modelUrl: 'models/haru/haru_greeter_t03.model3.json',

    // 口型参数（不同模型可能命名不同，会逐个尝试）
    lipSyncParams: ['ParamMouthOpenY', 'ParamMouthOpen'],

    // 布局
    fillRatio: 1.18,   // 模型高度 / 视口高度（>1 表示略大于屏幕，视觉更饱满）
    offsetX: 0.0,      // 水平偏移，视口宽度的比例
    offsetY: 0.02,     // 垂直偏移，视口高度的比例（正数 = 下移）

    // 口型动态
    mouthGain: 1.55,   // 音量 → 张口幅度 增益
    mouthCurve: 0.75,  // 值域压缩曲线（<1 提升小音量表现）
    mouthAttack: 0.55, // 张口插值速度
    mouthRelease: 0.30,// 闭口插值速度

    // 渲染
    maxResolution: 2,  // devicePixelRatio 上限（省电）
    idleFocus: true,   // 空闲时轻微视线游移

    // 各通话状态 → 表现映射（表情名需按实际模型调整）
    states: {
      idle:      { expression: null,  motion: ['Idle'], focus: 0.25 },
      listening: { expression: 'f01', motion: ['Idle'], focus: 0.55 },
      thinking:  { expression: 'f03', motion: ['Idle'], focus: 0.10 },
      speaking:  { expression: null,  motion: ['Idle'], focus: 0.35 },
      ended:     { expression: 'f05', motion: ['Idle'], focus: 0.0  }
    }
  };

  // ============================ 状态 ============================
  var app = null;           // PIXI.Application
  var model = null;         // Live2DModel
  var modelReady = false;
  var currentState = 'idle';
  var mouthEnabled = true;

  var _mouthTarget = 0;     // Android 推来的原始音量 (0~1)
  var _mouthValue = 0;      // 平滑后的实际张口值 (0~1)
  var _lastUpdate = 0;      // 上一帧时间戳（用于 dt）
  var _focusPhase = 0;      // 视线游移相位
  var _paused = false;

  // ======================= Android 通信 ========================
  function notify(type, payload) {
    try {
      var bridge = window.AndroidBridge;
      if (bridge && typeof bridge.onEvent === 'function') {
        bridge.onEvent(type, JSON.stringify(payload || {}));
      }
    } catch (e) { /* 桥接不可用时静默降级，不影响渲染 */ }
  }

  function reportError(message) {
    console.error('[L2D]', message);
    notify('error', { message: String(message) });
  }

  function showFallback(text) {
    var el = document.getElementById('fallback');
    if (el) {
      el.textContent = text;
      el.classList.add('show');
    }
  }

  // ========================== 工具函数 ==========================
  function clamp(v, lo, hi) {
    return v < lo ? lo : (v > hi ? hi : v);
  }

  // ======================= 模型布局适配 =======================
  function layout() {
    if (!model || !app) return;

    var sw = app.screen.width;
    var sh = app.screen.height;
    if (!sw || !sh) return;

    // originalWidth / originalHeight 是模型在 scale=1 时的原始尺寸
    var im = model.internalModel || {};
    var mw = im.originalWidth || model.width || 1;
    var mh = im.originalHeight || model.height || 1;
    if (!mw || !mh) return;

    // 以高度为基准铺满，保证角色够大
    var scale = (sh / mh) * CFG.fillRatio;

    model.anchor.set(0.5, 0.5);
    model.scale.set(scale);
    model.x = sw * 0.5 + CFG.offsetX * sw;
    model.y = sh * 0.5 + CFG.offsetY * sh;
  }

  // ======================= 口型同步核心 =======================
  /**
   * 平滑音量曲线：快速张口 + 稍慢闭口，接近真实说话节奏
   */
  function updateMouth(dt) {
    var target = 0;

    if (modelReady && mouthEnabled && currentState === 'speaking') {
      // 增益 + 曲线压缩，让小音量也能带动嘴型
      target = _mouthTarget * CFG.mouthGain;
      target = Math.pow(clamp(target, 0, 1), CFG.mouthCurve);
      // 轻微抖动，避免机械感
      target *= 1 + (Math.random() - 0.5) * 0.18;
      target = clamp(target, 0, 1);
    }

    // 帧率无关的指数插值
    var rate = target > _mouthValue ? CFG.mouthAttack : CFG.mouthRelease;
    var k = 1 - Math.pow(1 - rate, dt * 60);
    _mouthValue += (target - _mouthValue) * k;

    if (_mouthValue < 0.0005) _mouthValue = 0;
  }

  /**
   * 把当前口型值写入 Cubism 模型参数
   * 调用时机：beforeModelUpdate（模型 update 之前的最后一步）
   */
  function applyMouth() {
    if (!modelReady || !model) return;
    var core = model.internalModel && model.internalModel.coreModel;
    if (!core || typeof core.setParameterValueById !== 'function') return;

    for (var i = 0; i < CFG.lipSyncParams.length; i++) {
      var id = CFG.lipSyncParams[i];
      try {
        if (typeof core.getParameterIndex === 'function' && core.getParameterIndex(id) < 0) {
          continue; // 该模型没有此参数
        }
        core.setParameterValueById(id, _mouthValue);
      } catch (e) { /* 单个参数失败不影响其他 */ }
    }
  }

  /**
   * 每帧钩子：先更新口型曲线，再在模型 update 前写入参数
   */
  function onBeforeModelUpdate() {
    var now = performance.now();
    var dt = _lastUpdate ? (now - _lastUpdate) / 1000 : 1 / 60;
    _lastUpdate = now;
    dt = clamp(dt, 0.001, 0.1); // 防止后台恢复时的巨大跳变

    updateMouth(dt);
    applyMouth();

    // 空闲/聆听时轻微视线游移，让角色"活着"
    if (model && CFG.idleFocus && (currentState === 'idle' || currentState === 'listening')) {
      _focusPhase += dt * 0.6;
      var fx = Math.sin(_focusPhase) * 0.35 * CFG.states[currentState].focus;
      var fy = Math.cos(_focusPhase * 0.7) * 0.2 * CFG.states[currentState].focus;
      try { model.focus(fx, fy); } catch (e) { /* ignore */ }
    }
  }

  // ========================= 状态切换 =========================
  function applyState(state) {
    if (!modelReady || !model) return;

    var s = CFG.states[state] || CFG.states.idle;

    // 表情
    try {
      if (s.expression) {
        model.expression(s.expression);
      } else if (model.internalModel && model.internalModel.expressionManager) {
        // 无表情时恢复模型默认表情
        model.internalModel.expressionManager.resetExpression();
      }
    } catch (e) { /* 模型无此表情时忽略 */ }

    // 动作（循环播放指定动作组）
    try {
      if (s.motion && s.motion.length) {
        var group = s.motion[0];
        var defs = model.internalModel.motionManager.definitions;
        if (defs && defs[group] && defs[group].length) {
          // 随机挑一个同组动作，避免重复感
          var idx = Math.floor(Math.random() * Math.min(defs[group].length, 3));
          model.motion(group, idx, PIXI.live2d.MotionPriority.IDLE);
        }
      }
    } catch (e) { /* 模型无此动作时忽略 */ }

    // 视线瞬时归位
    try { model.focus(0, 0, true); } catch (e) { /* ignore */ }

    _focusPhase = 0;
  }

  // ==================== 对外 API（window.L2D）====================
  var L2D = {
    /** 桥接版本，便于 Android 侧探测 */
    version: '1.0.0',

    get ready() { return modelReady; },
    get state() { return currentState; },

    /** 切换通话状态：idle | listening | thinking | speaking | ended */
    setState: function (state) {
      var next = String(state || 'idle');
      if (!CFG.states[next]) next = 'idle';
      if (next === currentState && modelReady) return;
      currentState = next;
      if (next !== 'speaking') {
        _mouthTarget = 0; // 离开说话状态时立即收敛口型
      }
      applyState(next);
    },

    /** 推送音量（0~1），仅在 speaking 状态生效 */
    setMouth: function (level) {
      var v = Number(level);
      if (!isFinite(v)) return;
      _mouthTarget = clamp(v, 0, 1);
    },

    /** 是否允许口型驱动（关闭后闭嘴） */
    setMouthEnabled: function (enabled) {
      mouthEnabled = !!enabled;
      if (!mouthEnabled) _mouthTarget = 0;
    },

    /** 指定表情（null 恢复默认） */
    setExpression: function (name) {
      if (!modelReady || !model) return;
      try {
        if (name) model.expression(String(name));
        else if (model.internalModel.expressionManager) model.internalModel.expressionManager.resetExpression();
      } catch (e) { /* ignore */ }
    },

    /** 播放指定动作组 */
    playMotion: function (group, index) {
      if (!modelReady || !model) return;
      try {
        model.motion(String(group), index === undefined || index === null ? undefined : Number(index));
      } catch (e) { /* ignore */ }
    },

    /** 调整布局：{fillRatio, offsetX, offsetY} */
    setLayout: function (opts) {
      try {
        var o = typeof opts === 'string' ? JSON.parse(opts) : opts;
        if (o && typeof o === 'object') {
          if (typeof o.fillRatio === 'number') CFG.fillRatio = o.fillRatio;
          if (typeof o.offsetX === 'number') CFG.offsetX = o.offsetX;
          if (typeof o.offsetY === 'number') CFG.offsetY = o.offsetY;
          layout();
        }
      } catch (e) { /* ignore */ }
    },

    /** 暂停/恢复渲染（省电） */
    setPaused: function (paused) {
      _paused = !!paused;
      if (app && app.ticker) {
        if (_paused) app.ticker.stop();
        else app.ticker.start();
      }
      if (model) model.autoUpdate = !_paused;
    },

    /** 上报模型能力，便于调试 */
    requestInfo: function () {
      notify('info', collectInfo());
    },

    /** 释放资源 */
    dispose: function () {
      try {
        if (model) { model.destroy(); model = null; }
        if (app) { app.destroy(false, { children: true }); app = null; }
      } catch (e) { /* ignore */ }
      modelReady = false;
    }
  };
  window.L2D = L2D;

  function collectInfo() {
    var info = { modelUrl: CFG.modelUrl, motions: [], expressions: [], lipSync: [] };
    try {
      var im = model.internalModel;
      var defs = im.motionManager.definitions || {};
      for (var g in defs) {
        if (Object.prototype.hasOwnProperty.call(defs, g)) info.motions.push(g + ':' + defs[g].length);
      }
      if (im.expressionManager && im.expressionManager.definitions) {
        im.expressionManager.definitions.forEach(function (d) { info.expressions.push(d.Name); });
      }
      CFG.lipSyncParams.forEach(function (id) {
        if (typeof im.coreModel.getParameterIndex === 'function' && im.coreModel.getParameterIndex(id) >= 0) {
          info.lipSync.push(id);
        }
      });
    } catch (e) { /* ignore */ }
    return info;
  }

  // ========================== 初始化 ==========================
  function boot() {
    // ---- 运行库自检 ----
    try {
      if (typeof PIXI === 'undefined') { reportError('PixiJS 未加载'); return; }
      if (!PIXI.live2d || !PIXI.live2d.Live2DModel) { reportError('Live2D 运行库未加载'); return; }
    } catch (e) { reportError('运行库检测失败: ' + e.message); return; }

    // ---- 创建渲染器 ----
    try {
      app = new PIXI.Application({
        view: document.getElementById('stage'),
        backgroundAlpha: 0,          // 透明背景，露出 App 的渐变
        antialias: true,
        autoStart: true,
        autoDensity: true,
        resolution: Math.min(window.devicePixelRatio || 1, CFG.maxResolution),
        width: window.innerWidth,
        height: window.innerHeight
      });
    } catch (e) {
      reportError('WebGL 初始化失败: ' + e.message);
      showFallback('当前设备不支持 WebGL');
      return;
    }

    // 视口变化时重新适配
    window.addEventListener('resize', function () {
      if (app) app.renderer.resize(window.innerWidth, window.innerHeight);
      layout();
    });

    // 页面不可见时暂停，省电
    document.addEventListener('visibilitychange', function () {
      if (!app) return;
      if (document.hidden) app.ticker.stop();
      else if (!_paused) app.ticker.start();
    });

    // ---- 加载模型 ----
    PIXI.live2d.Live2DModel.from(CFG.modelUrl, { autoInteract: false })
      .then(function (m) {
        model = m;
        app.stage.addChild(model);

        modelReady = true;
        layout();

        // 关键：口型注入点
        model.internalModel.on('beforeModelUpdate', onBeforeModelUpdate);

        currentState = 'idle';
        applyState('idle');

        notify('ready', collectInfo());
      })
      .catch(function (err) {
        modelReady = false;
        reportError('模型加载失败: ' + (err && err.message ? err.message : err));
        showFallback('Live2D 模型加载失败');
      });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
