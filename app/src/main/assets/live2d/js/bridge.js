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
    modelUrl: 'models/silverwolf/silverwolf.model3.json',

    // 口型参数（不同模型可能命名不同，会逐个尝试）
    lipSyncParams: ['ParamMouthOpenY', 'ParamMouthOpen'],

    // 布局
    //
    // autoFit: 用「实际内容包围盒」而不是画布尺寸来缩放。
    //   模型画布常远大于角色本身（横版素材尤其明显），
    //   按画布缩放会把角色裁得只剩局部。
    // fitBy: 'width' 按内容宽度适配 / 'height' 按高度 / 'contain' 取较小者
    autoFit: true,
    fitBy: 'width',
    trimLow: 0.03,     // 内容包围盒下分位（剔除远端离群 drawable）
    trimHigh: 0.97,    // 上分位
    fillRatio: 1.25,   // 内容尺寸 / 视口对应边（>1 表示放大裁切）
    offsetX: 0.0,      // 水平偏移，视口宽度的比例
    offsetY: -0.24,    // 垂直偏移，视口高度的比例（正数 = 下移）
                       // 银狼为 Q 版角色，需上移让出底部消息区

    // 口型动态
    mouthGain: 2.2,    // 音量 → 张口幅度 增益
    mouthCurve: 0.6,   // 值域压缩曲线（<1 提升小音量表现）
    mouthAttack: 0.55, // 张口插值速度
    mouthRelease: 0.30,// 闭口插值速度

    // 渲染
    maxResolution: 2,  // devicePixelRatio 上限（省电）
    idleFocus: true,   // 空闲时轻微视线游移

    // 各通话状态 → 表现映射
    //
    // 银狼模型的动作组只有 Transform / AngryLoop / Sleep（变身、生气循环、睡觉），
    // 没有通用待机动作 —— 待机感由 ParamBreath 呼吸 + EyeBlink 眨眼 + 物理驱动。
    // 因此这里 motion 一律留空，避免播放不合时宜的特效动画。
    // 表情可用值见该模型 model3.json 的 Expressions[].Name。
    states: {
      idle:      { expression: null,    motion: null, focus: 0.25 },
      listening: { expression: null,    motion: null, focus: 0.55 },
      thinking:  { expression: '06 0.0', motion: null, focus: 0.10 },
      speaking:  { expression: null,    motion: null, focus: 0.35 },
      ended:     { expression: null,    motion: null, focus: 0.0  }
    }
  };

  // 允许宿主通过 ?model= 指定模型，换模型无需改本文件
  (function () {
    try {
      var m = null;
      var q = window.location && window.location.search;
      if (q && typeof URLSearchParams === 'function') {
        m = new URLSearchParams(q).get('model');
      } else if (q) {
        // 兜底解析，兼容不支持 URLSearchParams 的环境
        var hit = /[?&]model=([^&]*)/.exec(q);
        if (hit) m = decodeURIComponent(hit[1]);
      }
      if (m) CFG.modelUrl = m;
    } catch (e) { /* 解析失败则沿用默认模型 */ }
  })();

  // ============================ 状态 ============================
  var app = null;           // PIXI.Application
  var model = null;         // Live2DModel
  var modelReady = false;
  var currentState = 'idle';
  var mouthEnabled = true;

  var _mouthTarget = 0;     // Android 推来的原始音量 (0~1)
  var _mouthValue = 0;      // 平滑后的实际张口值 (0~1)
  var _lastUpdate = 0;      // 上一帧时间戳（用于 dt）
  var _lastDt = 1 / 60;     // 本帧 dt（秒），两个钩子共用
  var _focusPhase = 0;      // 视线游移相位
  var _paused = false;
  var _forcedParams = {};   // 调试用：每帧强制写入的参数
  // 口型写入后的读回统计（验证参数是否真的在该帧生效）
  var _applyStats = { min: null, max: null, n: 0, last: null };

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

  /**
   * 降级提示
   *
   * 可见的错误 UI 由 Compose 侧接管（加载失败会回退到静态头像），
   * 这里只在页面内留个印记，避免与原生降级界面叠字。
   */
  function showFallback(text) {
    var el = document.getElementById('fallback');
    if (el) {
      el.textContent = text;
      el.classList.remove('show');
    }
  }

  // ========================== 工具函数 ==========================
  /**
   * 取视口尺寸，带兜底
   *
   * window.innerWidth/Height 在少数时机可能为 0（布局未完成），
   * 若直接用于创建画布会得到 0x0，导致模型完全不可见。
   */
  function viewportSize() {
    var w = window.innerWidth || 0;
    var h = window.innerHeight || 0;
    if (!w || !h) {
      var de = document.documentElement;
      if (de) { w = w || de.clientWidth; h = h || de.clientHeight; }
    }
    if (!w || !h) {
      var c = document.getElementById('stage');
      if (c) { w = w || c.clientWidth; h = h || c.clientHeight; }
    }
    return { w: w || 360, h: h || 640 };
  }
  function clamp(v, lo, hi) {
    return v < lo ? lo : (v > hi ? hi : v);
  }

  /**
   * 取表达式管理器
   *
   * pixi-live2d-display 把它挂在 internalModel.motionManager.expressionManager
   * 上（不是 internalModel.expressionManager），早期写成后者导致表情/复位全部失效。
   */
  function exprManager() {
    if (!modelReady || !model) return null;
    var im = model.internalModel;
    if (!im) return null;
    return (im.motionManager && im.motionManager.expressionManager) || im.expressionManager || null;
  }

  // ======================= 模型布局适配 =======================
  /**
   * 合并所有 drawable 的包围盒，得到角色「实际内容」的范围
   *
   * getDrawableBounds 返回的是模型画布坐标系下的值（画布左上角为原点），
   * 需要减去画布中心才是容器本地坐标。
   */
  function contentBounds() {
    try {
      var im = model.internalModel;
      var core = im.coreModel;
      var n = core.getDrawableCount ? core.getDrawableCount() : 0;
      if (!n || typeof im.getDrawableBounds !== 'function') return null;

      var L = [], R = [], T = [], B = [];
      for (var i = 0; i < n; i++) {
        var b;
        try { b = im.getDrawableBounds(i); } catch (e) { continue; }
        if (!b || !isFinite(b.x) || !isFinite(b.y) || !isFinite(b.width) || !isFinite(b.height)) continue;
        L.push(b.x); T.push(b.y); R.push(b.x + b.width); B.push(b.y + b.height);
      }
      if (L.length < 4) return null;
      var asc = function (a, b) { return a - b; };
      L.sort(asc); R.sort(asc); T.sort(asc); B.sort(asc);
      // 取 3%~97% 分位：特效/失控部件常被摆到画布外很远处，直接取并集会把
      // 包围盒撑得远大于角色本身（实测 4810 高 vs 画布 2600）。
      var q = function (arr, p) {
        return arr[Math.min(arr.length - 1, Math.max(0, Math.floor(arr.length * p)))];
      };
      var x0 = q(L, CFG.trimLow), x1 = q(R, CFG.trimHigh);
      var y0 = q(T, CFG.trimLow), y1 = q(B, CFG.trimHigh);
      if (x1 <= x0 || y1 <= y0) return null;
      return { x: x0, y: y0, width: x1 - x0, height: y1 - y0 };
    } catch (e) { return null; }
  }

  function layout() {
    if (!model || !app) return;

    var sw = app.screen.width;
    var sh = app.screen.height;
    if (!sw || !sh) return;

    // 量测必须在 scale=1 下进行，否则量到的是缩放后的尺寸
    model.scale.set(1);

    var canvasW = (model.internalModel && model.internalModel.originalWidth) || 1;
    var canvasH = (model.internalModel && model.internalModel.originalHeight) || 1;

    var cb = CFG.autoFit ? contentBounds() : null;
    var mw = cb ? cb.width : canvasW;
    var mh = cb ? cb.height : canvasH;
    if (!mw || !mh) return;

    var scale;
    if (CFG.fitBy === 'width') {
      scale = (sw / mw) * CFG.fillRatio;
    } else if (CFG.fitBy === 'contain') {
      scale = Math.min(sw / mw, sh / mh) * CFG.fillRatio;
    } else { // height
      scale = (sh / mh) * CFG.fillRatio;
    }

    model.anchor.set(0.5, 0.5);
    model.scale.set(scale);

    // 把「内容中心」对齐到屏幕中心（补偿画布留白带来的偏移）
    var contentCx = cb ? (cb.x + cb.width / 2) - canvasW / 2 : 0;
    var contentCy = cb ? (cb.y + cb.height / 2) - canvasH / 2 : 0;

    model.x = sw * 0.5 + CFG.offsetX * sw - contentCx * scale;
    model.y = sh * 0.5 + CFG.offsetY * sh - contentCy * scale;
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
        if (id === 'ParamMouthOpenY' && typeof core.getParameterValueById === 'function') {
          var back = core.getParameterValueById('ParamMouthOpenY');
          _applyStats.last = back;
          _applyStats.n++;
          if (_applyStats.min === null || back < _applyStats.min) _applyStats.min = back;
          if (_applyStats.max === null || back > _applyStats.max) _applyStats.max = back;
        }
      } catch (e) { /* 单个参数失败不影响其他 */ }
    }
  }

  /**
   * 每帧钩子：先更新口型曲线，再在模型 update 前写入参数
   */
  /**
   * 每帧入口（挂 afterMotionUpdate）
   *
   * 必须在这里写口型而不是 beforeModelUpdate：框架在一帧里的顺序是
   *   motion 更新 → saveParameters() → 表情/物理 → beforeModelUpdate
   *   → coreModel.update()（算 drawable）→ loadParameters()（复位参数）
   * 若只在 beforeModelUpdate 写，值会在 loadParameters() 被还原，
   * 渲染时读到的仍是闭嘴值 —— 表现为「参数读回是对的，但嘴不动」。
   * 在 afterMotionUpdate 写，值会被 saveParameters() 记住，
   * loadParameters() 复位时恢复的就是我们的值。
   */
  function onAfterMotionUpdate() {
    var now = performance.now();
    var dt = _lastUpdate ? (now - _lastUpdate) / 1000 : 1 / 60;
    _lastUpdate = now;
    dt = clamp(dt, 0.001, 0.1); // 防止后台恢复时的巨大跳变
    _lastDt = dt;

    updateMouth(dt);
    applyMouth();
  }

  function onBeforeModelUpdate() {
    // 表情/物理可能覆盖口型参数，update 前再兜一次
    applyMouth();

    // 调试用强制参数（在口型之后写入，优先级最高）
    for (var fid in _forcedParams) {
      if (!Object.prototype.hasOwnProperty.call(_forcedParams, fid)) continue;
      try {
        var fcore = model.internalModel && model.internalModel.coreModel;
        if (fcore && typeof fcore.setParameterValueById === 'function') {
          fcore.setParameterValueById(fid, _forcedParams[fid]);
        }
      } catch (e) { /* ignore */ }
    }

    // 空闲/聆听时轻微视线游移，让角色"活着"
    if (model && CFG.idleFocus && (currentState === 'idle' || currentState === 'listening')) {
      _focusPhase += _lastDt * 0.6;
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
      var em = exprManager();
      if (s.expression) {
        model.expression(s.expression);
      } else if (em) {
        // 无表情时恢复模型默认表情
        em.resetExpression();
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
        else { var em = exprManager(); if (em) em.resetExpression(); }
      } catch (e) { /* ignore */ }
    },

    /** 播放指定动作组（group 为空则忽略） */
    playMotion: function (group, index) {
      if (!modelReady || !model || !group) return;
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

    /**
     * 调试：每帧强制写入参数值（忽略状态与音量），用于验证某个参数
     * 是否真的驱动了模型部件。传 null / 不传则清空。
     * 例：L2D.debugForce('ParamMouthOpenY', 1)
     */
    debugForce: function (id, value) {
      if (id === null || id === undefined || value === null || value === undefined) {
        _forcedParams = {};
      } else {
        _forcedParams[String(id)] = Number(value);
      }
      return JSON.stringify(_forcedParams);
    },

    /**
     * 调试：导出模型内部状态（表达式定义、口型参数范围与当前值等）
     * 供开发期通过 CDP 排查，不影响正常运行。
     */
    debug: function () {
      var out = {};
      if (!modelReady || !model) return JSON.stringify({ ready: false });
      try {
        var im = model.internalModel;
        var em = exprManager();
        out.expressionManager = !!em;
        out.expressionManagerPath = (im.motionManager && im.motionManager.expressionManager)
          ? 'motionManager.expressionManager' : (im.expressionManager ? 'internalModel.expressionManager' : 'none');
        out.expressionDefinitions = em && em.definitions
          ? em.definitions.map(function (d) { return d && d.Name; })
          : null;
        out.expressionCount = em && em.definitions ? em.definitions.length : -1;
        out.motionGroups = Object.keys(im.motionManager.definitions || {});
        var core = im.coreModel;
        out.lipSync = {};
        CFG.lipSyncParams.forEach(function (id) {
          try {
            var idx = core.getParameterIndex(id);
            if (idx >= 0) {
              out.lipSync[id] = {
                idx: idx,
                min: core.getParameterMinimumValue(idx),
                max: core.getParameterMaximumValue(idx),
                value: core.getParameterValueById(id)
              };
            }
          } catch (e) { /* ignore */ }
        });
        out.currentMouth = _mouthValue;
        out.appliedMouth = _applyStats;
        out.state = currentState;
      } catch (e) { out.error = String(e); }
      return JSON.stringify(out);
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
      info.size = im.originalWidth + 'x' + im.originalHeight;
      var cb = contentBounds();
      if (cb) {
        info.content = Math.round(cb.width) + 'x' + Math.round(cb.height)
                     + '@' + Math.round(cb.x) + ',' + Math.round(cb.y);
      }
      var defs = im.motionManager.definitions || {};
      for (var g in defs) {
        if (Object.prototype.hasOwnProperty.call(defs, g)) info.motions.push(g + ':' + defs[g].length);
      }
      var em = exprManager();
      if (em && em.definitions) {
        em.definitions.forEach(function (d) { info.expressions.push(d.Name); });
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
    var vp = viewportSize();
    try {
      app = new PIXI.Application({
        view: document.getElementById('stage'),
        backgroundAlpha: 0,          // 透明背景，露出 App 的渐变
        antialias: true,
        autoStart: true,
        autoDensity: true,
        resolution: Math.min(window.devicePixelRatio || 1, CFG.maxResolution),
        width: vp.w,
        height: vp.h
      });
    } catch (e) {
      reportError('WebGL 初始化失败: ' + e.message);
      showFallback('当前设备不支持 WebGL');
      return;
    }

    // 视口变化时重新适配
    window.addEventListener('resize', function () {
      var v = viewportSize();
      if (app) app.renderer.resize(v.w, v.h);
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
        model.internalModel.on('afterMotionUpdate', onAfterMotionUpdate);
        model.internalModel.on('beforeModelUpdate', onBeforeModelUpdate);

        currentState = 'idle';
        applyState('idle');

        // 布局可能受异步字体/尺寸影响，下一帧再校正一次
        requestAnimationFrame(function () { layout(); });

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
