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
  //
  // 结构：BASE（所有角色共用） + PROFILES（每个内置角色一档） → 深合并成 CFG。
  //
  // 为什么要有 PROFILES
  // -------------------
  // 早期只有「银狼」一个形象，所有参数平铺在 CFG 里就够了。加入并列的
  // 「DeepSeek 酱」后，两个模型的参数命名、可用通道、动作组、演出方式都不同
  // （银狼有变身过场与兽耳物理链，DeepSeek 酱有真 Idle 动作但没有变身），
  // 平铺就会互相污染。
  //
  // 所以拆成"共用 + 每角色一档"，由宿主通过 ?profile=<id> 选择（见文件末尾的
  // 解析逻辑），合并后仍然叫 CFG —— 下游代码一行都不用改。
  // 新增角色 = 在 PROFILES 里加一档，不需要动任何渲染逻辑。

  var BASE = {
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
    offsetY: 0.0,      // 垂直偏移，视口高度的比例（正数 = 下移）

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
    // motion 一律留空：两个模型的"状态动作"要么不存在、要么不合时宜
    // （银狼只有 Transform / AngryLoop / Sleep 三组特效；DeepSeek 酱只有
    //   idle 与道具演出）。待机感由各自 profile 的 idle 层 + 运行库的
    //   Idle 动作组（若模型有注册）负责。
    //
    // expression 一律留空是刻意的：情绪表情由 LLM 通过 [[e:标签]] 驱动
    // （见 applyStateExpression 的 _cue 覆盖层）。早期 thinking 占位用了 '06 0.0'，
    // 而 LLM 打招呼时最爱选的恰好也是 0.0（死鱼眼），两者撞车的结果是
    // 「明明触发成功但脸没变化」，被误判成功能失效。状态表情与情绪表达必须解耦。
    states: {
      idle:      { expression: null, motion: null, focus: 0.25 },
      listening: { expression: null, motion: null, focus: 0.55 },
      thinking:  { expression: null, motion: null, focus: 0.10 },
      speaking:  { expression: null, motion: null, focus: 0.35 },
      ended:     { expression: null, motion: null, focus: 0.0  }
    },

    // ==================== 摸头反应（程序化，不依赖动作文件）====================
    //
    // 用户点舞台上半部分 → 角色做出「被摸头」的反应。两个模型的材质里都没有
    // 可动的"手"，所以这里只能是**角色的反应**：头部小幅下沉再弹回、眯眼、
    // 眉毛抬高、嘴角上扬，可选叠一个脸红表情。
    //
    // 为什么走参数叠层而不是像变身那样做动作文件：
    //   1. 两个模型参数命名不同，但「哪些参数可写」已经在各自的 idle.channels
    //      里摸清了（哪些是物理输出、哪些是物理输入）。复用那张通道表就不必
    //      逐个模型再做一套动作素材；
    //   2. 摸头是**用户即兴触发**的短演出，必须能随时插进任意通话状态；
    //      走动作组会和运行库的 Idle 自动播放抢优先级（变身过场已经踩过这个坑）。
    //
    // 与程序化待机层的关系：摸头层写在待机层**之后**（同帧、更晚），只做叠加
    // 不改基线。所以摸头期间待机层照常工作，不需要互相让位；变身过场让位
    // （gate=0）时摸头也叠加在归零后的基线上，不会把变身演出顶坏。
    pat: {
      enabled: true,

      duration: 0.95,   // 单次演出时长（秒）
      cooldown: 0.6,    // 冷却：这段时间内的重复触发直接忽略，避免连点叠成乱抖
      waves: 2.2,       // 一个 duration 内包含几个"上下"周期（= 被揉了几下）

      /**
       * 命中区 = **头部部件的顶点包围盒**（不再手调矩形）。
       *
       * 为什么换掉矩形：Q 版银狼与半身立绘 DeepSeek 酱的头，位置与大小完全不同，
       * 同一个"内容包围盒上 42%"不可能同时对；而且矩形是**静态**的，模型呼吸、
       * 转头、换布局之后"看到的头"就偏了 —— 旧实现因此经常点了没反应。
       *
       * 现在的判定：在点击那一刻，取这些部件下所有 drawable 的顶点求 AABB，
       * 按 layout() 的变换换算到视口，再外扩 padRatio。天然贴合、天然跟随姿态。
       *
       * 列表由 `tools/live2d_dump_parts.py` 从模型的 cdi3.json（部件显示名）
       * 生成 —— 换模型/换版本时重跑脚本，**不要手写 id**。
       */
      headParts: [],
      padRatio: 0.08,

      /**
       * 兜底矩形（归一化到模型内容包围盒，0~1，左上为原点）。
       *
       * 只在拿不到 Core 的部件/顶点数据时启用（老运行时、模型异常）。
       * 正常情况下不该参与判定 —— 它正是旧实现"点了没反应"的根源。
       */
      hit: { x0: 0.18, x1: 0.82, y0: 0.00, y1: 0.42 },

      // 各通道的演出幅度（0 表示这个通道不动；通道本身不存在也会自动跳过）
      amp: {
        tilt:      3.2,   // 头部侧倾（度）：被手掌按下去又弹回
        brow:      0.18,  // 眉毛抬高（"被摸得很舒服"）
        smile:     0.22,  // 笑眼
        squint:    0.20,  // 微眯
        mouthForm: 0.10,  // 嘴角（绝不碰 ParamMouthOpenY —— 那是口型的通道）
        sway:      0.0    // 身体左右晃
      },

      /**
       * 有 LLM 情绪表情时，把「和表情抢戏」的通道压到这个比例。
       *
       * 与待机层 cuePoseScale 同一个理由：不能让「明明在生气，眉毛却在笑」。
       * tilt / sway 是纯姿势，不压 —— 摸头的动作感要保住。
       */
      cueScale: 0.35,

      // 叠层收敛速度（越小越慢）。刻意比 idle.poseRate 快一点：
      // 摸头是"被碰了一下"的即时反应，比状态切换该更利落。
      poseRate: 0.16,

      // 可选：摸头时临时套一个表情（null = 不套）。写模型真实 Expressions[].Name，
      // 找不到就静默跳过。宿主会在 holdMs 之后复位 —— 且只在当前没有 LLM 情绪
      // 表情时才套，不抢 LLM 的表达。
      expression: null,
      holdMs: 1200
    }
  };

  var PROFILES = {
    // ======================================================================
    // 银狼
    // ======================================================================
    silverwolf: {
      modelUrl: 'models/silverwolf/silverwolf.model3.json',

      // 银狼为 Q 版角色，需上移让出底部消息区
      offsetY: -0.24,

      // ==================== 程序化待机（不依赖动作文件）====================
      //
      // 模型自带的 3 组动作（Transform / AngryLoop / Sleep）全是「变身 / 生气 / 睡觉」
      // 的特效循环，没有待机动作；而运行库的自动待机只认名字叫 Idle 的动作组，
      // 找不到就一条都不播 —— 于是角色除了呼吸、眨眼、视线之外全程静止。
      //
      // 这里用「每帧写参数」补一层待机微动作，而不是加动作文件，因为要的是
      // 跟通话状态联动（聆听时挑眉屏息、思考时歪头眯眼），
      // 而运行库的机制是「没动作时随机播一条 Idle」——它不知道通话状态。
      //
      // ⚠️ 只能写「物理输入 / 空闲」参数：moc3 的 358 个参数里有 185 个是物理输出，
      //    物理每帧都会把它们覆盖掉，动作/参数写上去等于没写。
      //    下面这组通道还有个附带好处 —— 眉毛/嘴形/眼睛形状经物理链会带动兽耳
      //    （ParamBrowLForm→Param3→ParamnekoL*），所以「做个微表情」和
      //    「抖一下耳朵」是同一件事，不需要单独动耳朵参数（耳朵本身是物理输出，动不了）。
      idle: {
        enabled: true,

        // 各通道对应的模型参数（模型里不存在的会被自动跳过）
        channels: {
          brow:      ['ParamBrowLY', 'ParamBrowRY'],           // 眉毛 上下
          browForm:  ['ParamBrowLForm', 'ParamBrowRForm'],     // 眉毛 变形（皱眉/委屈）
          smile:     ['ParamEyeLSmile', 'ParamEyeRSmile'],     // 笑眼
          squint:    ['ParamEyeLSquint', 'ParamEyeRSquint'],   // 眯眼
          mouthForm: ['ParamMouthForm'],                        // 嘴 变形（不碰开闭：那是口型的地盘）
          breath:    ['ParamBreath'],                           // 呼吸深度（运行库的呼吸会在上面再叠加）
          tilt:      ['ParamAngleZ'],                           // 头部侧倾（单位：度）
          sway:      ['ParamBodyAngleZ']                        // 身体左右摆（单位：度）
        },

        // 各状态的目标姿态
        // 幅度刻意压得很小：这些参数会和 LLM 表情、物理、运行库的呼吸叠在一起，
        // 给大了就会打架（比如「生气脸」配上一个大幅度「聆听微笑」）。
        poses: {
          idle:      { brow:  0.00, browForm: 0.00, smile: 0.00, squint: 0.00, mouthForm:  0.00, breath: 0.10, tilt:  0.0, sway: 1.00 },
          listening: { brow:  0.22, browForm: 0.05, smile: 0.28, squint: 0.00, mouthForm: -0.10, breath: 0.02, tilt:  2.0, sway: 0.45 },
          thinking:  { brow: -0.15, browForm: 0.32, smile: 0.00, squint: 0.30, mouthForm:  0.16, breath: 0.00, tilt: -3.0, sway: 0.20 },
          speaking:  { brow:  0.10, browForm: 0.02, smile: 0.14, squint: 0.00, mouthForm:  0.04, breath: 0.30, tilt:  0.8, sway: 0.70 },
          ended:     { brow: -0.10, browForm: 0.12, smile: 0.00, squint: 0.08, mouthForm: -0.04, breath: 0.00, tilt: -1.6, sway: 0.15 }
        },

        // 姿态过渡速度（越小越慢；约等于 1/poseRate 帧到达 63%）
        poseRate: 0.06,

        // 常驻微漂移：周期刻意避开运行库呼吸用的 3.23 / 3.53 / 5.53 / 6.53 / 15.53s，
        // 否则两者会共振，看起来像机器人在抖
        drift: {
          brow:      { amp: 0.06, period: 7.3 },
          mouthForm: { amp: 0.05, period: 11.7 },
          smile:     { amp: 0.05, period: 9.1 },
          squint:    { amp: 0.03, period: 8.3 }
        },

        // 偶发「抖一下耳朵」：给眉毛/嘴形一个短脉冲，经物理链传到兽耳
        flick: { minGap: 4.0, maxGap: 9.0, duration: 0.28, amp: 0.30 },

        // 有情绪表情（LLM 触发）时，把状态姿态压到这个比例
        // 否则会出现「明明在生气，眉毛却在笑」的错位
        cuePoseScale: 0.3
      },

      // ==================== 呼吸幅度（接管库内置值）====================
      //
      // 运行库内置 CubismBreath，用的是官方示例值：ParamAngleX 峰值 15 × 权重 0.5
      // = 头部左右摇 ±7.5°、周期 6.53s。对"角色在看着你"这件事来说这个摆幅偏大
      // （和待机动作叠加后实测观感就是"没在看你"），所以这里接管成更小的幅度：
      // 头 yaw ±4°、pitch ±2.5°、roll ±3°、身体 ±2°，周期保持官方值。
      // 想恢复官方幅度就把 angleX/angleY/angleZ 改回 15/8/10。
      breath: {
        enabled: true,
        angleX: 8,          // ±4°
        angleY: 5,          // ±2.5°
        angleZ: 6,          // ±3°
        bodyAngleX: 4,      // ±2°
        breath: 0.5,
        cycles: {           // 官方示例周期（秒）
          angleX: 6.5345, angleY: 3.5345, angleZ: 5.5345,
          bodyAngleX: 15.5345, breath: 3.2345
        }
      },

      // ==================== 变身过场（一次性动作）====================
      //
      // 模型自带的 Transform_1/2 是同一段演出的前后两半（_1 摘眼镜+变身开+特效
      // 0→10，_2 戴回眼镜+变身关+特效 10→20），但**都是 Loop: true**，直接播会
      // 一直循环。tools/live2d_make_idle.py 生成两份"只改 Loop"的副本并注册成
      // TransformOnce 组（0 = 进入 / 1 = 还原）。
      transform: {
        enabled: true,
        group: 'TransformOnce',
        inIndex: 0,
        outIndex: 1,

        // 播放期间压掉程序化待机层：变身动作自己会画眉毛/眼睛，
        // 而待机层写在 afterMotionUpdate（更晚），不压就会把它盖掉
        muteIdle: true,

        // 序列结束（或被打断）后，把"变身相关"参数一次性写回中性值
        //
        // 正常路径其实不需要兜底：_2 自己会把眼镜戴回去、变身关掉，而且动作权重
        // 淡出也会把参数带回基准值。但序列可能被中途打断（切后台、WebView 暂停、
        // 模型重载、秒挂断），那时角色会停在"变到一半"的样子上 ——
        // 参数级兜底比"指望动画一定播完"可靠。下面的值就是 moc3 里的默认值。
        reset: {
          key9: 1,        // 09 正常眼镜（默认值本来就是 1）
          key11: 0,       // 11 变身
          key15: 0,       // 14 划卡手
          Param172: 0,    // 划卡特效
          Param173: 0,    // 划卡 R x
          Param204: 0,    // 划卡 R y
          Param212: 0,    // 划卡 R z
          Param210: 0,    // 划卡 L x
          Param211: 0,    // 划卡 L y
          Param213: 0,    // 划卡 L z
          Param214: 0,    // 迈腿
          Param218: 0     // 人物变暗
        }
      },

      // ==================== 摸头反应 ====================
      //
      // 银狼是 Q 版，头相对身体偏大；命中盒由部件顶点算，不写矩形。
      // sway 有效：这个模型的 ParamBodyAngleZ 不是物理输出（与 DeepSeek 酱相反）。
      // 表情用模型自带的「02 脸红爱心」—— 被摸头脸红是最贴的反应。
      //
      // headParts 由 tools/live2d_dump_parts.py 生成（15 个）：
      //   头饰 / 前发 / 五官 / 脸 / 头饰后 / 后发 / 闭眼 / 眼泪 / 眉毛 /
      //   眼睛 / 嘴巴 / 耳朵 / 闭嘴 / 张嘴 / 生气嘴
      // 刻意不含「脖子」（在头下面，含进去会让"摸头"下探到肩膀）与
      // 三个眼镜部件（贴片；其中两个还是变身特效）。
      pat: {
        headParts: ['Part78', 'Part89', 'Part94', 'Part127', 'Part158', 'Part159',
                    'Part95', 'Part102', 'Part112', 'Part113', 'Part120', 'Part128',
                    'Part121', 'Part125', 'Part126'],
        amp: { tilt: 3.2, brow: 0.18, smile: 0.22, squint: 0.20, mouthForm: 0.10, sway: 1.6 },
        expression: '02 脸红爱心',
        holdMs: 1200
      }
    },

    // ======================================================================
    // DeepSeek 酱（DS鲸鱼娘）
    // ======================================================================
    //
    // 与银狼的关键差异（都由 profile 承载，下游逻辑零改动）：
    //   1. 没有变身演出 —— transform.enabled = false。硬播 TransformOnce 会
    //      因为组不存在而静默失败（JS 侧只记一条 warn 并返回 false，不抛异常），
    //      但挂断侧会白等一段过场时间，所以要显式关掉。
    //   2. 有待机动作 —— 模型自带 idle.motion3.json（4s / 89 曲线），已由
    //      tools/setup_deepseek_model.py 注册成 Idle 组，运行库会自动循环播放。
    //      程序化待机层因此只做"状态联动的微表情"，与动作层分工。
    //   3. 没有笑眼/眯眼参数 —— 已直接扫 moc3 确认 ParamEyeLSmile /
    //      ParamEyeRSmile / ParamEyeLSquint / ParamEyeRSquint 都不存在。
    //      所以 channels 里去掉 smile / squint 两条通道：留着也不会报错
    //      （paramOK 会跳过缺失参数），但写进去毫无效果，只会让调参时困惑。
    //   4. 嘴部通道与银狼同名（ParamMouthOpenY / ParamMouthForm），口型层可直接复用。
    //   5. **身体摆动不可用**：这个模型的物理把 ParamBodyAngleZ / ParamBodyAngleX
    //      当作**输出**（physics3.json 的 setting3 / setting1，权重 100），
    //      每帧都会被物理覆写 —— 待机层再写一遍等于没写。
    //      银狼的物理不输出这两个参数，所以那一档的 sway 通道是有效的。
    //      这里去掉 sway，改由物理自己驱动身体摆动。
    deepseek: {
      modelUrl: 'models/deepseek/c_0120.model3.json',

      // 立绘是接近正方的半身像（画布 2048），内容重心比银狼低一点
      offsetY: -0.06,

      idle: {
        enabled: true,

        // 只保留该模型真实存在、且**不是物理输出**的通道（见上方说明 3、5）
        channels: {
          brow:      ['ParamBrowLY', 'ParamBrowRY'],           // 眉毛 上下
          browForm:  ['ParamBrowLForm', 'ParamBrowRForm'],     // 眉毛 变形（皱眉/委屈/抗议）
          mouthForm: ['ParamMouthForm'],                        // 嘴 变形（不碰开闭：那是口型的地盘）
          breath:    ['ParamBreath'],                           // 呼吸深度
          tilt:      ['ParamAngleZ']                            // 头部侧倾（单位：度）
        },

        // 姿态幅度整体比银狼再小一点：这个模型自带 Idle 动作（89 条曲线）会写
        // 大量道具/头发参数，叠加大幅度会打架；而且它是软乎乎的圆脸角色，
        // 微表情做太大就不可爱了。
        //
        // 注意：这里的 sway 只是占位（0），实际不会写进模型 —— 该参数是物理输出，
        // 通道表里已经没有它了。保留字段是为了让姿态表和银狼同构、便于对照。
        poses: {
          idle:      { brow:  0.00, browForm: 0.00, mouthForm:  0.00, breath: 0.10, tilt:  0.0, sway: 0.00 },
          listening: { brow:  0.18, browForm: 0.04, mouthForm: -0.08, breath: 0.02, tilt:  1.6, sway: 0.00 },
          thinking:  { brow: -0.12, browForm: 0.26, mouthForm:  0.12, breath: 0.00, tilt: -2.4, sway: 0.00 },
          speaking:  { brow:  0.08, browForm: 0.02, mouthForm:  0.04, breath: 0.28, tilt:  0.6, sway: 0.00 },
          ended:     { brow: -0.08, browForm: 0.10, mouthForm: -0.04, breath: 0.00, tilt: -1.4, sway: 0.00 }
        },

        poseRate: 0.06,

        // 漂移通道同步收窄（没有 smile / squint 可漂）
        drift: {
          brow:      { amp: 0.05, period: 7.7 },
          mouthForm: { amp: 0.04, period: 11.3 }
        },

        // 这个角色没有兽耳，"抖一下"改成眉毛/嘴形的短脉冲（更像"被戳了一下"）
        flick: { minGap: 5.0, maxGap: 11.0, duration: 0.30, amp: 0.22 },

        cuePoseScale: 0.3
      },

      // 呼吸：头 yaw ±4°、pitch ±2.5°、roll ±3°。
      //
      // 刻意**不含 bodyAngleX**（银狼那档有）：该模型的 ParamBodyAngleX 是物理输出
      // （physics3.json setting1，权重 100），呼吸写进去会被物理每帧覆盖；
      // 让它只作为物理的输入（setting0 权重 50）间接影响身体，观感反而更自然。
      breath: {
        enabled: true,
        angleX: 8,
        angleY: 5,
        angleZ: 6,
        bodyAngleX: 0,      // 该模型由物理驱动身体，不在此接管
        breath: 0.5,
        cycles: {
          angleX: 6.5345, angleY: 3.5345, angleZ: 5.5345,
          bodyAngleX: 15.5345, breath: 3.2345
        }
      },

      // 没有一次性演出：playTransform 会直接返回 false，
      // 宿主据此（hasTransform=false）也不会播、不会等
      transform: {
        enabled: false,
        group: 'TransformOnce',
        inIndex: 0,
        outIndex: 1,
        muteIdle: false,
        reset: {}
      },

      // ==================== 摸头反应 ====================
      //
      // 与该档 idle.channels 的差异保持一致：
      //   - sway 必须留 0：该模型的 ParamBodyAngleZ 是**物理输出**（physics3.json
      //     会每帧覆写），写进去等于没写（通道表里本来也没有它）；
      //   - smile / squint 也留 0：该模型没有 ParamEyeLSmile / ParamEyeLSquint
      //     这些参数，写着不报错但毫无效果，留着只会让调参时困惑。
      // 所以这档的手感主要靠 tilt（歪头）+ brow + mouthForm，再加上表情「脸红」。
      //
      // headParts 由 tools/live2d_dump_parts.py 生成（19 个）：
      //   发型1 / 恶魔角 / 眉型 / 眼睛L / 眼睛R / 发型F2 / 动物耳朵L / 头发 /
      //   动物耳朵R / 嘴巴 / 耳朵L / 脸蛋 / 耳朵R / 发型D2 / 发型蛋筒D2 /
      //   猫猫耳 / 兔兔耳 / 后发短 / R耳
      // 这个模型有一堆"发型/耳朵预设"替换件：这里全留着，靠运行时的
      // opacity 过滤把没启用的那几套剔掉（它们被切换时部件不透明度会归零）。
      pat: {
        headParts: ['Part92', 'Part48', 'Part101', 'Part57', 'Part58', 'Part19',
                    'Part44', 'Part14', 'Part47', 'Part69', 'Part68', 'Part46',
                    'Part45', 'Part160', 'Part161', 'Part98', 'Part95', 'hair_d',
                    'Part96'],
        amp: { tilt: 3.0, brow: 0.16, smile: 0, squint: 0, mouthForm: 0.08, sway: 0 },
        expression: '脸红',
        holdMs: 1200
      }
    }
  };

  /** 深合并：把 profile 的字段覆盖到 BASE 上（对象逐层合并，数组整体替换） */
  function deepMerge(base, override) {
    var out = {}, k;
    for (k in base) {
      if (Object.prototype.hasOwnProperty.call(base, k)) out[k] = base[k];
    }
    for (k in override) {
      if (!Object.prototype.hasOwnProperty.call(override, k)) continue;
      var b = out[k], o = override[k];
      if (b && o && typeof b === 'object' && typeof o === 'object' &&
          !Array.isArray(b) && !Array.isArray(o)) {
        out[k] = deepMerge(b, o);
      } else {
        out[k] = o;
      }
    }
    return out;
  }

  // 宿主可通过 ?profile= 指定形象档位、?model= 覆盖模型路径。
  // 两者都不传时回落到银狼（与改造前的默认行为一致）。
  var _query = (function () {
    try { return (window.location && window.location.search) || ''; }
    catch (e) { return ''; }
  })();
  function queryParam(name) {
    try {
      if (typeof URLSearchParams === 'function') {
        return new URLSearchParams(_query).get(name);
      }
      var hit = new RegExp('[?&]' + name + '=([^&]*)').exec(_query);
      return hit ? decodeURIComponent(hit[1]) : null;
    } catch (e) { return null; }
  }

  var PROFILE_ID = queryParam('profile') || 'silverwolf';
  if (!PROFILES[PROFILE_ID]) PROFILE_ID = 'silverwolf';

  var CFG = deepMerge(BASE, PROFILES[PROFILE_ID]);

  (function () {
    var m = queryParam('model');
    if (m) CFG.modelUrl = m;
  })();

  // ============================ 状态 ============================
  var app = null;           // PIXI.Application
  var model = null;         // Live2DModel
  var modelReady = false;
  var currentState = 'idle';
  var mouthEnabled = true;
  var _cue = null;          // 情绪覆盖层：LLM 触发的表情名，非空时优先于状态默认表情

  var _mouthTarget = 0;     // Android 推来的原始音量 (0~1)
  var _mouthValue = 0;      // 平滑后的实际张口值 (0~1)
  var _lastUpdate = 0;      // 上一帧时间戳（用于 dt）
  var _lastDt = 1 / 60;     // 本帧 dt（秒），两个钩子共用
  var _focusPhase = 0;      // 视线游移相位
  var _paused = false;
  var _forcedParams = {};   // 调试用：每帧强制写入的参数
  // 口型写入后的读回统计（验证参数是否真的在该帧生效）
  var _applyStats = { min: null, max: null, n: 0, last: null };

  // ---- 程序化待机状态 ----
  var _idleValue = { brow: 0, browForm: 0, smile: 0, squint: 0, mouthForm: 0, breath: 0, tilt: 0, sway: 0 };
  var _idleTime = 0;              // 待机相位（秒）
  var _idleEnabled = true;        // 运行期开关（真机调参时可临时关掉对比）
  var _idleFlickAt = 1.5;         // 下一次"耳抖"的时间点（秒），启动后 1.5~3.5s 来第一下
  var _idleFlickEnd = -1;         // 当前耳抖的结束时间点，-1 表示没有正在进行的
  var _idleValid = null;          // {参数id: 是否存在} 缓存，避免每帧都查一遍
  var _sequence = null;           // 正在播的一次性动作序列：{queue:[…], pos:0, deadline:秒}
  var _transformResetPending = false;  // 需要把变身参数写回中性值（只写一帧）

  // 摸头反应（见 CFG.pat）
  var _patUntil = -1;          // 演出结束时间（_idleTime 秒）；-1 表示没在演
  var _patStart = -1;          // 演出开始时间
  var _patCooldownUntil = -1;  // 冷却结束时间（连点直接忽略）
  var _patValue = {};          // 当前摸头层的通道叠加值（与 _idleValue 同构）
  var _patDebug = false;       // 调试：画出命中区
  var _patLastHit = null;      // 调试：最近一次命中判定 {ok,nx,ny,src,box}
  var _patBoxEl = null;        // 调试用的命中框 DOM（仅 _patDebug 时存在）
  var _headPartIdx = null;     // 头部部件索引集合（换模型时清）
  var _patDownAt = -1;         // 手势：按下时刻（performance.now()，毫秒）
  var _patDownX = 0;           // 手势：按下位置（clientX/Y，用于判"划走了"）
  var _patDownY = 0;

  // layout() 算出的布局量缓存（给摸头命中判定用）。
  // 每次 layout() 覆盖；换模型/销毁时清掉，避免拿上一份模型的数据做判定。
  var _layoutCache = null;

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

  /**
   * 把外部传入的表情名解析成模型里真实存在的名字。
   *
   * 模型作者写的名字经常空格不统一甚至带编号（"03 生气" / "01黑脸" / "月卡"），
   * 名字对不上时 pixi 会静默忽略 —— 现象就是「调了表情但脸没变」。
   * 这里先精确匹配，再退化成「忽略所有空白」的宽松匹配。
   *
   * @returns 模型里的真实名字；模型里没有该表情时返回 null
   */
  function resolveExpressionName(name) {
    var want = String(name);
    var em = exprManager();
    var defs = (em && em.definitions) || null;
    if (!defs || !defs.length) return want;   // 拿不到定义表时不拦，交给 pixi 判断
    var i, n;
    for (i = 0; i < defs.length; i++) {
      n = defs[i] && defs[i].Name;
      if (n === want) return n;
    }
    var norm = want.replace(/\s+/g, '');
    for (i = 0; i < defs.length; i++) {
      n = defs[i] && defs[i].Name;
      if (n && String(n).replace(/\s+/g, '') === norm) return n;
    }
    return null;
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

    // 缓存这一帧的布局量给摸头命中判定用。
    //
    // 为什么不让它自己去调 contentBounds()：那个函数依赖
    // internalModel.getDrawableBounds()，在部分运行时/时序下会返回 null。
    // layout() 已经拿到过一份（上面），直接用同一份既准确又便宜，
    // 也保证"看到的缩放"和"命中的区域"永远一致。
    _layoutCache = {
      sw: sw, sh: sh,
      scale: scale,
      canvasW: canvasW, canvasH: canvasH,
      cb: cb || { x: 0, y: 0, width: canvasW, height: canvasH },
      x: model.x, y: model.y
    };
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

  // ======================= 程序化待机 =======================
  /**
   * 参数是否存在于当前模型（结果缓存）
   *
   * 换模型时同一份 bridge.js 要能跑，而写不存在的参数在部分 WebView 版本上会抛异常，
   * 所以先探测再写；每帧探测十来个参数没必要，缓存住。
   */
  function paramOK(core, id) {
    if (_idleValid === null) _idleValid = {};
    if (!Object.prototype.hasOwnProperty.call(_idleValid, id)) {
      var ok = true;
      try {
        if (typeof core.getParameterIndex === 'function') ok = core.getParameterIndex(id) >= 0;
      } catch (e) { ok = false; }
      _idleValid[id] = ok;
    }
    return _idleValid[id];
  }

  /**
   * 待机层：算目标姿态 → 平滑
   *
   * 三种成分叠加：
   *   1. 状态姿态 —— 秒级过渡，让聆听/思考/说话有可辨识的差别
   *   2. 常驻微漂移 —— 让脸"活着"，振幅只有 0.03~0.06
   *   3. 偶发耳抖 —— 每 4~9s 一次短脉冲（走眉毛→兽耳那条物理链）
   */
  function updateIdle(dt) {
    _idleTime += dt;

    // 看门狗：序列被别的动作打断时 motionFinish 可能不会按我们的组名到达，
    // 卡住会让待机层一直处于"让位"状态，所以超时强行收尾
    if (_sequence && _idleTime > _sequence.deadline) finishTransform();

    var i, name;
    if (!_idleEnabled || !CFG.idle.enabled) {
      // 关掉时也要把值收回 0，否则会永远停在上一个姿态上
      for (i in _idleValue) {
        if (Object.prototype.hasOwnProperty.call(_idleValue, i)) _idleValue[i] = 0;
      }
      return;
    }

    var cfg = CFG.idle;
    var pose = cfg.poses[currentState] || cfg.poses.idle;
    var base = cfg.poses.idle;
    // 有情绪表情（LLM 触发）时压制状态姿态，否则会出现「生气脸配聆听微笑」
    var scale = _cue ? cfg.cuePoseScale : 1;
    // 变身过场期间整体让位：那套动作自己会画眉毛/眼睛，而本层写得更晚会盖掉它。
    // 注意要连 idle 姿态的基线（呼吸 0.10、身体摆 1.0）一起压掉，只压"状态偏移"
    // 会留下基线值，等于没完全让位。
    var gate = (_sequence && CFG.transform.muteIdle) ? 0 : 1;
    var target = {};

    // 1. 状态姿态：只取相对 idle 的偏移，新增状态时不必重复写全套
    for (i in pose) {
      if (!Object.prototype.hasOwnProperty.call(pose, i)) continue;
      target[i] = (base[i] || 0) + ((pose[i] || 0) - (base[i] || 0)) * scale;
    }

    // 2. 常驻微漂移
    var dr = cfg.drift;
    for (name in dr) {
      if (!Object.prototype.hasOwnProperty.call(dr, name)) continue;
      target[name] = (target[name] || 0) +
        Math.sin(_idleTime * 2 * Math.PI / dr[name].period) * dr[name].amp * scale;
    }

    // 3. 偶发耳抖：半正弦包络
    var f = cfg.flick;
    if (_idleFlickEnd > 0 && _idleTime >= _idleFlickEnd) _idleFlickEnd = -1;
    if (_idleFlickEnd < 0 && _idleTime >= _idleFlickAt) {
      _idleFlickEnd = _idleTime + f.duration;
      _idleFlickAt = _idleTime + f.minGap + Math.random() * Math.max(0, f.maxGap - f.minGap);
    }
    if (_idleFlickEnd > 0) {
      var p = clamp(1 - (_idleFlickEnd - _idleTime) / f.duration, 0, 1);
      var env = Math.sin(p * Math.PI) * f.amp;
      target.browForm = (target.browForm || 0) + env;
      target.mouthForm = (target.mouthForm || 0) + env * 0.6;
      target.smile = (target.smile || 0) + env * 0.5;
    }

    // 帧率无关的指数插值（和口型同一套算法）
    var k = 1 - Math.pow(1 - cfg.poseRate, dt * 60);
    for (i in _idleValue) {
      if (!Object.prototype.hasOwnProperty.call(_idleValue, i)) continue;
      var want = (target[i] || 0) * gate;   // gate=0 时整体归零（变身过场让位）
      _idleValue[i] += (want - _idleValue[i]) * k;
    }
  }

  /**
   * 把待机值写进模型参数
   *
   * 和口型一样挂在 afterMotionUpdate：晚到 beforeModelUpdate 写会被
   * loadParameters() 复位，早到 update 之前写又会被 saveParameters() 之后的表情/物理覆盖。
   * 待机用的都是物理输入参数（物理只读不写它们），所以不需要在 update 前再兜一次。
   */
  function applyIdle() {
    if (!modelReady || !model) return;
    var core = model.internalModel && model.internalModel.coreModel;
    if (!core || typeof core.setParameterValueById !== 'function') return;

    var ch = CFG.idle.channels, name, ids, i, id;
    for (name in ch) {
      if (!Object.prototype.hasOwnProperty.call(ch, name)) continue;
      ids = ch[name];
      var v = _idleValue[name] || 0;
      for (i = 0; i < ids.length; i++) {
        id = ids[i];
        try {
          if (!paramOK(core, id)) continue;
          core.setParameterValueById(id, v);
        } catch (e) { /* 单个参数失败不影响其他 */ }
      }
    }
  }

  // ======================= 摸头反应（CFG.pat）=======================
  /**
   * 取 Cubism Core 的原始模型对象。
   *
   * 为什么要绕到 `_model`：框架层（CubismModel）只给了"顶点"和"部件"各自的取值，
   * 唯独没有"这个 drawable 属于哪个部件"的映射（`getDrawableParentPartIndex`
   * 不在包里，已逐条核对 cubism4.min.js）。而 Core 的
   * `Drawables.parentPartIndices` / `Parts.parentIndices` 正好就是这张映射表 ——
   * 头部包围盒全靠它。
   *
   * 属性名未被混淆（已核对 live2dcubismcore.min.js），但它毕竟是框架内部字段：
   * 取不到就返回 null，由调用方回落到 CFG.pat.hit 矩形。
   */
  function coreRaw() {
    try {
      var core = model && model.internalModel && model.internalModel.coreModel;
      return (core && core._model) || null;
    } catch (e) { return null; }
  }

  /**
   * 头部部件索引集合（带缓存，换模型时清）。
   *
   * 集合不只是 headParts 里那几个部件本身：模型里网格常常挂在**子部件**上
   * （"眼睛"挂在"脸"下面这种），所以对每个部件向上溯祖先链 ——
   * 链上任意一级命中 headParts，这个部件就属于头部。
   * guard 是防呆：部件层级理论上无环，但数据坏了不能把渲染线程转死。
   */
  function headPartIndexSet(raw) {
    if (_headPartIdx) return _headPartIdx;
    var ids = CFG.pat.headParts || [];
    var p = raw && raw.parts;
    if (!ids.length || !p || !p.ids || !p.parentIndices) return null;

    var want = {}, i;
    for (i = 0; i < ids.length; i++) want[String(ids[i])] = true;

    var all = p.ids, par = p.parentIndices, n = all.length, set = {};
    for (i = 0; i < n; i++) {
      var j = i, guard = 0;
      while (j >= 0 && j < n && guard++ < 64) {
        if (want[String(all[j])]) { set[i] = true; break; }
        j = par[j];
      }
    }
    _headPartIdx = set;
    return set;
  }

  /**
   * 头部包围盒，**模型坐标**（与 drawable 顶点、contentBounds 同一套空间）。
   *
   * 单独抽出来有两个用处：一是 [patHeadBox] 的换算基础，二是调试/自测可以
   * 直接断言"盒子到底框住了哪些顶点" —— 屏幕坐标会把结论糊掉。
   *
   * @returns {minX,minY,maxX,maxY}；null 表示算不出来
   */
  function patHeadModelBox() {
    var raw = coreRaw();
    if (!raw) return null;
    var set = headPartIndexSet(raw);
    if (!set) return null;

    var d = raw.drawables, p = raw.parts;
    if (!d || !d.parentPartIndices || !d.vertexPositions) return null;

    var minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity, used = 0;
    for (var i = 0; i < d.parentPartIndices.length; i++) {
      var pi = d.parentPartIndices[i];
      if (!set[pi]) continue;
      // 关掉的替换件（发型 / 耳朵 / 贴纸预设）不参与，否则盒子会被没启用的那套撑歪
      if (p.opacities && p.opacities[pi] <= 0.01) continue;
      if (d.opacities && d.opacities[i] <= 0.01) continue;
      var v = d.vertexPositions[i];
      if (!v || v.length < 2) continue;
      used++;
      for (var k = 0; k < v.length; k += 2) {
        var vx = v[k], vy = v[k + 1];
        if (!isFinite(vx) || !isFinite(vy)) continue;
        if (vx < minX) minX = vx;
        if (vx > maxX) maxX = vx;
        if (vy < minY) minY = vy;
        if (vy > maxY) maxY = vy;
      }
    }
    if (!used || !(maxX > minX) || !(maxY > minY)) return null;

    // 外扩：顶点盒偏紧（头发丝之类的细长件容易被判在边缘），
    // 而且呼吸/转头会让轮廓在帧间轻微移动
    var pad = CFG.pat.padRatio || 0;
    var w = maxX - minX, h = maxY - minY;
    return {
      minX: minX - w * pad, maxX: maxX + w * pad,
      minY: minY - h * pad, maxY: maxY + h * pad
    };
  }

  /**
   * 头部包围盒（**视口归一化**，0~1，左上为原点）。
   *
   * 每次点击现算，不缓存：模型在呼吸、会转头、布局也可能变，静态盒子必然偏 ——
   * 这正是旧实现"看着在头上却判定不到"的原因。代价是几万次 min/max（亚毫秒级），
   * 而点击是低频事件，不值得为它做缓存失效。
   *
   * @returns null 表示算不出来（老运行时 / headParts 为空 / 顶点全无效），
   *          调用方应回落到 [patFallbackBox]
   */
  function patHeadBox() {
    var mb = patHeadModelBox();
    var L = _layoutCache;
    if (!mb || !L || !L.sw || !L.sh) return null;

    // 模型坐标 → 视口归一化：与 layout() 同一套变换。
    // anchor 在画布中心，所以 model.x/y 就是"画布中心"的屏幕位置，
    // 于是任意模型坐标 (mx,my) 落在 model.x + (mx - canvasW/2) * scale。
    var x0 = (L.x + (mb.minX - L.canvasW / 2) * L.scale) / L.sw;
    var x1 = (L.x + (mb.maxX - L.canvasW / 2) * L.scale) / L.sw;
    var y0 = (L.y + (mb.minY - L.canvasH / 2) * L.scale) / L.sh;
    var y1 = (L.y + (mb.maxY - L.canvasH / 2) * L.scale) / L.sh;
    return {
      x: Math.min(x0, x1), y: Math.min(y0, y1),
      w: Math.abs(x1 - x0), h: Math.abs(y1 - y0)
    };
  }

  /**
   * 兜底矩形：把 CFG.pat.hit（内容包围盒归一化）换算成视口归一化。
   *
   * 只在 [patHeadBox] 算不出来时用 —— 宁可退回一个粗糙但能用的区域，
   * 也不要让"点了没反应"变成常态。
   */
  function patFallbackBox() {
    var L = _layoutCache, hit = CFG.pat.hit;
    if (!L || !L.sw || !L.sh || !hit) return null;
    var cb = L.cb;
    if (!cb || !cb.width || !cb.height) return null;
    var left = L.x + (cb.x - L.canvasW / 2) * L.scale;
    var top = L.y + (cb.y - L.canvasH / 2) * L.scale;
    return {
      x: (left + cb.width * L.scale * hit.x0) / L.sw,
      y: (top + cb.height * L.scale * hit.y0) / L.sh,
      w: (cb.width * L.scale * (hit.x1 - hit.x0)) / L.sw,
      h: (cb.height * L.scale * (hit.y1 - hit.y0)) / L.sh
    };
  }

  /**
   * 命中判定：视口归一化坐标 (nx, ny) 是否落在头部盒里。
   *
   * @returns {ok, box, src}；src = 'parts'（部件盒）/ 'fallback'（兜底矩形）/ 'none'
   */
  function patHitTest(nx, ny) {
    var box = patHeadBox(), src = 'parts';
    if (!box) { box = patFallbackBox(); src = box ? 'fallback' : 'none'; }
    _patLastHit = { nx: nx, ny: ny, ok: false, src: src, box: box };
    if (!box || box.w <= 0 || box.h <= 0) return { ok: false, box: null, src: 'none' };
    var ok = nx >= box.x && nx <= box.x + box.w && ny >= box.y && ny <= box.y + box.h;
    _patLastHit.ok = ok;
    return { ok: ok, box: box, src: src };
  }

  // ======================= 摸头手势（在页面内捕获）=======================
  //
  // 为什么在页面里而不是宿主（Compose）：WebView 是真实 View，绘制与触摸派发
  // 都在 Compose 画布之上 —— 宿主在 Compose 里叠一层触摸层**收不到点击**
  // （旧实现就是这么做的，现象就是"点了没反应"）。放在这里还顺带干掉了
  // 两套坐标系：手势与命中判定用的是同一个视口、同一套归一化。
  //
  // 只认"轻点"：按下与抬起间隔够短、位移够小。滑动与长按不触发，
  // 避免与将来的交互（拖动、缩放）抢手势。
  var PAT_TAP_MAX_MS = 400;
  var PAT_TAP_MAX_PX = 24;

  /** 视口归一化坐标（与 patHeadBox 同一套单位） */
  function patPointerNorm(e) {
    var sw = (_layoutCache && _layoutCache.sw) || window.innerWidth || 1;
    var sh = (_layoutCache && _layoutCache.sh) || window.innerHeight || 1;
    return { x: (e ? e.clientX : 0) / sw, y: (e ? e.clientY : 0) / sh };
  }

  function onPatPointerDown(e) {
    if (!CFG.pat.enabled || !modelReady) return;
    if (e && e.isPrimary === false) return;   // 多指：只认第一根
    _patDownAt = (typeof performance !== 'undefined' && performance.now)
      ? performance.now() : Date.now();
    _patDownX = e ? e.clientX : 0;
    _patDownY = e ? e.clientY : 0;
  }

  function onPatPointerUp(e) {
    if (!CFG.pat.enabled || !modelReady) return;
    if (_patDownAt < 0) return;
    var now = (typeof performance !== 'undefined' && performance.now)
      ? performance.now() : Date.now();
    var held = now - _patDownAt;
    var dx = (e ? e.clientX : 0) - _patDownX;
    var dy = (e ? e.clientY : 0) - _patDownY;
    _patDownAt = -1;
    if (held > PAT_TAP_MAX_MS) return;                              // 长按不算点
    if (Math.sqrt(dx * dx + dy * dy) > PAT_TAP_MAX_PX) return;      // 划走了不算点
    triggerPat(patPointerNorm(e), 'tap');
  }

  function onPatPointerCancel() { _patDownAt = -1; }

  /**
   * 触发一次摸头（手势与调试入口共用同一条路径）。
   *
   * @param norm {x,y} 视口归一化坐标；传 null = 不看位置、强制触发（调试用）
   * @param source 'tap' / 'api' —— 进日志，便于区分"用户点的"和"调试叫的"
   * @returns 是否真的播了演出
   */
  function triggerPat(norm, source) {
    if (!modelReady || !model || !CFG.pat.enabled) return false;

    if (norm) {
      var res = patHitTest(norm.x, norm.y);
      if (!res.ok) {
        // 没命中**也要回报**：旧实现把命中信息丢在 info 事件里，
        // 结果"点了没反应"在日志里完全看不到原因
        notify('pat', {
          hit: false, src: res.src, source: source,
          x: norm.x, y: norm.y, box: res.box
        });
        if (_patDebug) drawPatBox(true);
        return false;
      }
    }

    if (_idleTime < _patCooldownUntil) {
      notify('pat', { hit: true, playing: false, reason: 'cooldown', source: source });
      return false;
    }

    _patStart = _idleTime;
    _patUntil = _idleTime + CFG.pat.duration;
    _patCooldownUntil = _idleTime + CFG.pat.cooldown + CFG.pat.duration;

    // 表情只在「当前没有 LLM 情绪表情」时才套 —— _cue 优先级更高，
    // 否则一次摸头会把 LLM 刚触发的情绪脸冲掉，而且复位后回不到那张脸。
    var expr = (CFG.pat.expression && !_cue) ? CFG.pat.expression : null;
    notify('pat', {
      hit: true, playing: true, source: source,
      expression: expr, holdMs: CFG.pat.holdMs || 1200
    });
    if (_patDebug) drawPatBox(true);
    return true;
  }

  /**
   * 摸头层：算通道叠加值 → 平滑收敛
   *
   * 包络 env = sin(π·p) · |sin(π·p·waves)|：
   *   前一个因子让整段演出自然淡入淡出（不会"啪"地开始、"啪"地结束），
   *   后一个因子在 duration 内叠出 waves 个快周期，做出"被揉了几下"的节奏。
   *
   * 该层只做**叠加**：目标值直接取 envelope × amp，不清零待机层的基线。
   * 不在演出期时按同一套插值把叠加值衰减回 0（不是硬切），否则演出结束会"咔"一下。
   */
  function updatePat(dt) {
    if (!modelReady || !model || !CFG.pat.enabled) return;

    var cfg = CFG.pat;
    var active = _patUntil > 0 && _idleTime <= _patUntil;
    // 上一帧没参与过演出的通道要先补 0，否则 += 会碰到 undefined → NaN
    if (active) {
      for (var nm in cfg.amp) {
        if (Object.prototype.hasOwnProperty.call(cfg.amp, nm) &&
            typeof _patValue[nm] !== 'number') _patValue[nm] = 0;
      }
    }

    var scale = _cue ? cfg.cueScale : 1;
    var env = 0;
    if (active) {
      var p = clamp((_idleTime - _patStart) / cfg.duration, 0, 1);
      env = Math.sin(p * Math.PI) * Math.abs(Math.sin(p * Math.PI * cfg.waves));
    }

    var amp = cfg.amp, name;
    var rate = cfg.poseRate || 0.16;
    var k = 1 - Math.pow(1 - rate, dt * 60);
    var idle = 0;

    for (name in amp) {
      if (!Object.prototype.hasOwnProperty.call(amp, name)) continue;
      // 这个通道在本模型的待机通道表里不存在 → 跳过多余的数学
      if (!CFG.idle.channels || !CFG.idle.channels[name]) continue;
      // 与表情抢戏的通道在情绪表情期间压一点，纯姿势通道不压
      var sc = (name === 'tilt' || name === 'sway') ? 1 : scale;
      var want = env * amp[name] * sc;
      _patValue[name] += ((want || 0) - (_patValue[name] || 0)) * k;
      if (Math.abs(_patValue[name]) > 1e-4) idle = 1;
    }

    // 收敛到 0 且已不在演出期：彻底清零，避免每帧写一堆 1e-7 级别的噪声值
    if (!active && !idle) {
      for (name in _patValue) {
        if (Object.prototype.hasOwnProperty.call(_patValue, name)) _patValue[name] = 0;
      }
    }
  }

  /**
   * 把摸头层的值写进模型参数（与 applyIdle 完全同构）
   *
   * 同样挂在 afterMotionUpdate、同样写在待机层之后：同帧更晚写入即为覆盖式叠加。
   */
  function applyPat() {
    if (!modelReady || !model || !CFG.pat.enabled) return;
    var core = model.internalModel && model.internalModel.coreModel;
    if (!core || typeof core.setParameterValueById !== 'function') return;

    var ch = CFG.idle.channels, name, ids, i, id;
    for (name in ch) {
      if (!Object.prototype.hasOwnProperty.call(ch, name)) continue;
      var v = _patValue[name] || 0;
      if (v === 0) continue;   // 没在演出：一个参数都不写，避免干扰待机层
      ids = ch[name];
      for (i = 0; i < ids.length; i++) {
        id = ids[i];
        try {
          if (!paramOK(core, id)) continue;
          core.setParameterValueById(id, v);
        } catch (e) { /* 单个参数失败不影响其他 */ }
      }
    }
  }

  /**
   * 调试：画出**实际参与判定**的头部框
   *
   * 用 fixed 定位的 div 画框，坐标与 [patHitTest] 用同一个盒子，
   * 所以框住的就是"点这里会触发"的真实区域 —— 真机上瞄一眼就知道对不对，
   * 不需要再去调任何数值。框是虚线红框；若是兜底矩形会额外标一行字。
   */
  function drawPatBox(show) {
    try {
      if (!show) {
        if (_patBoxEl && _patBoxEl.parentNode) _patBoxEl.parentNode.removeChild(_patBoxEl);
        _patBoxEl = null;
        return;
      }
      var box = patHeadBox(), fallback = false;
      if (!box) { box = patFallbackBox(); fallback = true; }
      if (!box || !document.body) return;
      if (!_patBoxEl) {
        _patBoxEl = document.createElement('div');
        _patBoxEl.style.position = 'fixed';
        _patBoxEl.style.border = '2px dashed rgba(255,64,64,0.9)';
        _patBoxEl.style.background = 'rgba(255,64,64,0.12)';
        _patBoxEl.style.pointerEvents = 'none';
        _patBoxEl.style.zIndex = '9999';
        _patBoxEl.style.color = 'rgba(255,64,64,0.95)';
        _patBoxEl.style.font = '11px/1.2 monospace';
        document.body.appendChild(_patBoxEl);
      }
      // 用视口百分比表达，窗口尺寸变化时不需要重算
      _patBoxEl.style.left = (box.x * 100) + '%';
      _patBoxEl.style.top = (box.y * 100) + '%';
      _patBoxEl.style.width = (box.w * 100) + '%';
      _patBoxEl.style.height = (box.h * 100) + '%';
      _patBoxEl.textContent = fallback ? 'fallback' : '';
    } catch (e) { /* 调试功能，失败不影响主流程 */ }
  }

  /**
   * 接管运行库内置呼吸的幅度
   *
   * CubismBreath 的参数是 (id, offset, peak, cycle, weight) 四元组，运行时可用
   * setParameters() 整体换掉。换不到（拿不到 BreathParameterData）就沿用官方值。
   */
  function applyBreathConfig() {
    if (!CFG.breath.enabled) return;
    try {
      var im = model.internalModel;
      var B = PIXI.live2d && PIXI.live2d.BreathParameterData;
      if (!im.breath || typeof im.breath.setParameters !== 'function' || typeof B !== 'function') {
        console.warn('[L2D] 拿不到 BreathParameterData，沿用库内置呼吸幅度');
        return;
      }
      var b = CFG.breath, c = b.cycles;
      // 幅度为 0 的条目直接不注册：某些模型的某个角度参数是**物理输出**
      // （例：DeepSeek 酱的 ParamBodyAngleX），注册进去每帧都会被物理覆盖，
      // 写了等于没写；而且会把库内置的呼吸数据挤掉。不注册则完全交给物理。
      var list = [];
      if (b.angleX) list.push(new B('ParamAngleX', 0, b.angleX, c.angleX, 0.5));
      if (b.angleY) list.push(new B('ParamAngleY', 0, b.angleY, c.angleY, 0.5));
      if (b.angleZ) list.push(new B('ParamAngleZ', 0, b.angleZ, c.angleZ, 0.5));
      if (b.bodyAngleX) list.push(new B('ParamBodyAngleX', 0, b.bodyAngleX, c.bodyAngleX, 0.5));
      if (b.breath) list.push(new B('ParamBreath', 0, b.breath, c.breath, 0.5));
      if (!list.length) return;
      im.breath.setParameters(list);
      console.log('[L2D] 呼吸幅度已接管：头 yaw ±' + (b.angleX * 0.5) + '°');
    } catch (e) { /* 拿不到就沿用库内置值 */ }
  }

  // ======================= 一次性动作序列 =======================
  /**
   * 播放变身过场
   *
   * 作者原动作是 Loop 的，只有副本（transform_in/out）才是一次性：
   *   'in'  只播"进入"（摘眼镜、变身开）
   *   'out' 只播"还原"（戴回眼镜、变身关）
   *   其它  播完整序列（进入 → 还原）
   *
   * @returns 是否真的开始播（动作组缺失时返回 false）
   */
  function startTransform(phase) {
    if (!modelReady || !model || !CFG.transform.enabled) return false;

    var mm = model.internalModel && model.internalModel.motionManager;
    var list = mm && mm.definitions && mm.definitions[CFG.transform.group];
    if (!list || !list.length) {
      // 静默失败会变成"调了没反应"，必须留下痕迹：多半是没跑生成脚本
      console.warn('[L2D] 没有动作组 ' + CFG.transform.group +
                   '（跑一下 tools/live2d_make_idle.py）');
      return false;
    }

    var queue = phase === 'in' ? [CFG.transform.inIndex]
              : phase === 'out' ? [CFG.transform.outIndex]
              : [CFG.transform.inIndex, CFG.transform.outIndex];
    // deadline 是给"序列被别的动作打断、motionFinish 不按我们的组名到达"兜底的
    _sequence = { queue: queue, pos: 0, deadline: _idleTime + queue.length * 3.2 };
    _transformResetPending = false;
    stepTransform();
    return true;
  }

  /** 播序列里的下一条（队列空了就收尾） */
  function stepTransform() {
    if (!_sequence) return;
    if (_sequence.pos >= _sequence.queue.length) { finishTransform(); return; }
    var index = _sequence.queue[_sequence.pos++];
    try {
      // NORMAL 优先级高于待机(IDLE)，所以序列播放期间待机动作抢不走它
      model.motion(CFG.transform.group, index, PIXI.live2d.MotionPriority.NORMAL);
    } catch (e) {
      finishTransform();
    }
  }

  /** 序列收尾：解除待机层让位，并标记"下一帧把变身参数写回中性值" */
  function finishTransform() {
    _sequence = null;
    _transformResetPending = true;
  }

  /**
   * 把变身相关参数写回中性值（写一帧就够，见 CFG.transform.reset）
   *
   * 写在 afterMotionUpdate 会被随后的 saveParameters() 记进基准值，
   * 所以写一次就持续生效；之后有表情/动作写同名参数时自然覆盖它。
   */
  function applyTransformReset() {
    if (!_transformResetPending) return;
    _transformResetPending = false;
    if (!modelReady || !model) return;
    var core = model.internalModel && model.internalModel.coreModel;
    if (!core || typeof core.setParameterValueById !== 'function') return;

    var reset = CFG.transform.reset, id;
    for (id in reset) {
      if (!Object.prototype.hasOwnProperty.call(reset, id)) continue;
      try {
        if (!paramOK(core, id)) continue;
        core.setParameterValueById(id, reset[id]);
      } catch (e) { /* 单个参数失败不影响其他 */ }
    }
  }

  /**
   * 动作播完的回调
   *
   * 待机动作也会触发 motionFinish（Idle 组现在有 3 条），所以必须确认
   * 播完的正是我们排的那一组，否则序列会被待机动作提前推进。
   */
  function onMotionFinish() {
    if (!_sequence || !model) return;
    var mm = model.internalModel && model.internalModel.motionManager;
    var state = mm && mm.state;
    // 事件在 state.complete() 之前触发，此刻 currentGroup 仍是刚播完的那一组
    if (state && state.currentGroup !== CFG.transform.group) return;
    stepTransform();
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

    // 待机微动作。与口型互不干扰：只写眉毛/眼形/嘴形/角度这些物理输入参数，
    // 不碰 ParamMouthOpenY（那是口型的通道）
    updateIdle(dt);
    applyIdle();

    // 摸头层写在待机层**之后**（同帧更晚 → 覆盖式叠加），
    // 但仍在 applyTransformReset 之前（变身收尾的复位值优先级最高）。
    updatePat(dt);
    applyPat();

    applyTransformReset();
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
  /**
   * 决定当前该显示哪张脸。
   *
   * 优先级：情绪覆盖层 _cue（LLM 触发的表情） > 状态默认表情 > 模型默认表情。
   *
   * _cue 必须在这里重新应用一次：thinking→speaking 的状态切换会重走 applyState，
   * 如果只在 setExpression 里调一次 model.expression()，刚触发的情绪会被
   * resetExpression() 冲掉，玩家看到的现象就是「LLM 调了表情但脸没变」。
   */
  function applyStateExpression() {
    if (!model) return;
    var s = CFG.states[currentState] || CFG.states.idle;
    try {
      if (_cue) {
        model.expression(_cue);
      } else if (s.expression) {
        model.expression(s.expression);
      } else {
        // 无表情时恢复模型默认表情
        var em = exprManager();
        if (em) em.resetExpression();
      }
    } catch (e) { /* 模型无此表情时忽略 */ }
  }

  function applyState(state) {
    if (!modelReady || !model) return;

    var s = CFG.states[state] || CFG.states.idle;

    // 表情
    applyStateExpression();

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
    /**
     * 桥接版本，便于 Android 侧探测。
     *
     * 1.2.0：摸头命中区改为按部件顶点现算、手势改为页面内捕获，
     *        `patHead({x,y})` 降级为调试入口（宿主不再调用）。
     */
    version: '1.2.0',

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

    /**
     * 是否启用程序化待机
     *
     * 真机调参用：关掉就能直接对比"完全静止"的样子，
     * 判断幅度是不是给大了。
     */
    setIdleEnabled: function (enabled) {
      _idleEnabled = !!enabled;
      return _idleEnabled;
    },

    /** 当前待机姿态值（调试/自测用，返回副本） */
    getIdle: function () {
      return JSON.parse(JSON.stringify(_idleValue));
    },

    /**
     * 指定情绪表情（传模型真实表情名；null 复位）
     *
     * 记为「情绪覆盖层」：后续状态切换会重新应用它（见 applyStateExpression），
     * 直到宿主显式传 null 复位 —— 复位后回落到当前状态的默认表情。
     */
    setExpression: function (name) {
      if (!modelReady || !model) return;
      if (!name) {
        _cue = null;
        console.log('[L2D] 情绪表情 → 复位');
        applyStateExpression();
        return;
      }
      var resolved = resolveExpressionName(name);
      if (resolved === null) {
        // 名字对不上时 pixi 会静默无效，这里必须留下痕迹否则无从排查
        console.warn('[L2D] 模型没有这个表情: ' + name);
        return;
      }
      _cue = resolved;
      console.log('[L2D] 情绪表情 → ' + resolved);
      applyStateExpression();
    },

    /** 当前情绪表情名，null 表示未设置（调试/自测用） */
    get expression() { return _cue; },

    /** 播放指定动作组（group 为空则忽略） */
    playMotion: function (group, index) {
      if (!modelReady || !model || !group) return;
      try {
        model.motion(String(group), index === undefined || index === null ? undefined : Number(index));
      } catch (e) { /* ignore */ }
    },

    /**
     * 播放变身过场：'in'（进入）| 'out'（还原）| 'full'（进入→还原）
     *
     * 接通时用 'full'（约 4.7s，正好盖住等待首句的时间），
     * 挂断时用 'out'（约 2.3s）—— 挂断后界面会立刻跳走，给太长看不到。
     */
    playTransform: function (phase) {
      return startTransform(phase === undefined || phase === null ? 'full' : String(phase));
    },

    /**
     * 调整布局：{fillRatio, offsetX, offsetY, fitBy}
     *
     * fitBy 由宿主传入：通话页把模型装进「舞台板块」后，视口不再是全屏，
     * 继续按宽度适配会让角色上下被裁掉，需要宿主按板块宽高比改成 height/contain。
     */
    setLayout: function (opts) {
      try {
        var o = typeof opts === 'string' ? JSON.parse(opts) : opts;
        if (o && typeof o === 'object') {
          if (typeof o.fillRatio === 'number') CFG.fillRatio = o.fillRatio;
          if (typeof o.offsetX === 'number') CFG.offsetX = o.offsetX;
          if (typeof o.offsetY === 'number') CFG.offsetY = o.offsetY;
          if (o.fitBy === 'width' || o.fitBy === 'height' || o.fitBy === 'contain') {
            CFG.fitBy = o.fitBy;
          }
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
     * 摸头：{x, y} 是**视口归一化坐标**（0~1，相对整个 WebView 视口，左上为原点）。
     *
     * ⚠️ 手势已经改在**页面内**捕获（见 onPatPointerDown/Up），宿主不再需要传坐标 ——
     * 这个方法现在只剩两个用途：CDP 调试手测，以及自测脚本驱动。
     * 不传坐标 = 不看位置、强制触发一次。
     *
     * 命中判定放在这里而不是 Android 侧：只有 JS 侧拿得到模型部件与顶点，
     * Kotlin 侧硬算必然对不上。
     *
     * @returns 是否真的触发了演出。冷却中 / 没命中 / 未就绪都返回 false，
     *          并通过 pat 事件把原因回给宿主，便于排查"点了没反应"。
     */
    patHead: function (opts) {
      try {
        if (!modelReady || !model || !CFG.pat.enabled) return false;
        var o = typeof opts === 'string' ? JSON.parse(opts) : (opts || {});
        var nx = Number(o.x), ny = Number(o.y);
        var norm = (isFinite(nx) && isFinite(ny)) ? { x: nx, y: ny } : null;
        return triggerPat(norm, 'api');
      } catch (e) {
        console.warn('[L2D] patHead 失败: ' + (e && e.message ? e.message : e));
        return false;
      }
    },

    /**
     * 调试：画出摸头命中区并回报最近一次判定
     *
     * 打开后：红色虚线框 = **实际参与判定**的头部区域（部件顶点算出来的，
     * 与命中判定共用同一个盒子）。若框里写着 fallback，说明部件数据没取到、
     * 正在用兜底矩形 —— 那种情况该去查 Core 是否可用。
     * 想看数值时用 `L2D.debug().patLastHit`。
     */
    debugPatHit: function (on) {
      _patDebug = !!on;
      drawPatBox(_patDebug);
      return JSON.stringify({
        on: _patDebug,
        headParts: (CFG.pat.headParts || []).length,
        modelBox: patHeadModelBox(),
        box: patHeadBox(),
        fallback: patFallbackBox(),
        last: _patLastHit
      });
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
        out.expression = _cue;
        out.idle = {
          enabled: _idleEnabled && CFG.idle.enabled,
          time: _idleTime,
          values: _idleValue,
          flickEnd: _idleFlickEnd
        };
        out.sequence = _sequence ? { pos: _sequence.pos, queue: _sequence.queue } : null;
        out.transformResetPending = _transformResetPending;
        out.pat = {
          enabled: CFG.pat.enabled,
          active: _patUntil > 0 && _idleTime <= _patUntil,
          values: _patValue,
          debug: _patDebug,
          headParts: (CFG.pat.headParts || []).length,
          modelBox: patHeadModelBox(),
          box: patHeadBox(),
          fallbackBox: patFallbackBox(),
          lastHit: _patLastHit
        };
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
    // 待机层由 bridge.js 自己提供，不依赖模型文件；宿主侧可据此决定是否还需别的兜底
    info.idle = true;
    // 摸头同理：程序化演出，不需要模型自带对应动作组。
    // 命中区改由部件顶点现算之后，宿主不再参与判定（手势也在页面内捕获）——
    // 这里只回报"部件表在不在、盒子算不算得出来"，供排查"点了没反应"。
    info.pat = {
      enabled: CFG.pat.enabled,
      duration: CFG.pat.duration,
      headParts: (CFG.pat.headParts || []).length,
      boxReady: !!patHeadBox()
    };
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

    // ---- 摸头手势（必须在页面内捕获）----
    //
    // 宿主（Compose）那边叠触摸层是收不到事件的：WebView 是真实 View，
    // 绘制与触摸派发都在 Compose 画布之上（旧实现就是栽在这里）。
    // 页面里没有可滚动/可拖拽内容，所以这些监听不会抢走别的手势。
    window.addEventListener('pointerdown', onPatPointerDown, { passive: true });
    window.addEventListener('pointerup', onPatPointerUp, { passive: true });
    window.addEventListener('pointercancel', onPatPointerCancel, { passive: true });
    // 长按选中 / 系统上下文菜单在舞台上没有意义，挡掉（否则按住会弹菜单）
    window.addEventListener('contextmenu', function (e) {
      if (e && typeof e.preventDefault === 'function') e.preventDefault();
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
        // 换模型后布局与部件表都变了：清掉上一份缓存再重算，
        // 否则命中判定会拿旧模型的部件索引去比对（换角色时尤其明显）
        _layoutCache = null;
        _headPartIdx = null;
        layout();

        // 关键：口型注入点
        model.internalModel.on('afterMotionUpdate', onAfterMotionUpdate);
        model.internalModel.on('beforeModelUpdate', onBeforeModelUpdate);

        // 呼吸幅度：必须在模型就绪后覆盖（构造函数里已经塞了官方示例值）
        applyBreathConfig();

        // 动作播完的回调（一次性序列靠它推进；待机动作也会触发，处理函数里认组名）
        var mmanager = model.internalModel.motionManager;
        if (mmanager && typeof mmanager.on === 'function') {
          mmanager.on('motionFinish', onMotionFinish);
        }

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
