#!/usr/bin/env node
/**
 * MiMo TTS 流式下发节奏探针（不需要手机）
 *
 * ## 它回答什么问题
 *
 * app 里偶尔出现「她说着说着卡了一下」。成因有两类，听感一样：
 *   A. 服务端/网络没及时下发下一块音频  → 本探针能直接量出来
 *   B. 本地播放/解码跟不上              → 本探针量不到（那是 app 里的事）
 *
 * 本探针把**服务端那一半**单独拎出来测：直接打 MiMo 的接口，逐块记录到达时刻，
 * 于是「服务端是不是一段段憋出来的」这件事变成一串可复现的数字。
 *
 * 之所以要它：真机复现是碰运气的，而这个测试跑 10 次只要一分钟，还能换模型对比。
 *
 * ## 用法
 *
 * ```bash
 * # 克隆音色（银狼 / 流萤走的就是这条）
 * node tools/tts_stream_probe.cjs --key sk-xxx --runs 3
 *
 * # 换个角色的参考音频
 * node tools/tts_stream_probe.cjs --key sk-xxx --ref app/src/main/assets/silverwolf/ref_voice.wav
 *
 * # 预置音色（DeepSeek 酱走的是这条）—— 用来验证"是不是只有克隆音色才卡"
 * node tools/tts_stream_probe.cjs --key sk-xxx --model mimo-v2.5-tts --voice 冰糖
 * ```
 *
 * key 也可以放环境变量：`MIMO_API_KEY=sk-xxx node tools/tts_stream_probe.cjs`
 *
 * ## 怎么判读
 *
 * - **最大间隔 < 250ms** → 服务端下发是均匀的，app 里的卡顿**不在这条链路上**，
 *   该去查本地播放侧（app 里 `硬件欠载` 那条日志）。
 * - **最大间隔 ≥ 250ms，且落在句子边界** → 服务端按内部切分一段段算、算好一段发一段。
 *   这就是「第二句卡住」的服务端解释。
 * - **首块很晚（比如 > 2s）** → 是「开口前等待」长，不是中途卡顿，两回事，别混。
 *
 * 注意：MiMo 文档明确写了 `mimo-v2.5-tts-voiceclone` 的**低延迟流式尚未可用**，
 * 流式接口处于兼容模式。所以如果克隆音色测出"整段算完才吐"，那是**已知行为**，
 * 不是 bug；真正要确认的是它吐的时候是均匀的、还是一顿一顿的。
 */

const fs = require('node:fs');
const path = require('node:path');

const DEFAULT_REF = 'app/src/main/assets/firefly/ref_voice.wav';
const URL = 'https://api.xiaomimimo.com/v1/chat/completions';

/** 与 app 里 AudioPipe 的告警阈值保持一致，便于两边日志对照 */
const STALL_WARN_MS = 250;

/** 默认测试文本：四句话，长度与一轮正常回复相当 */
const DEFAULT_TEXT =
  '今天过得怎么样呀？我这边窗外的天一直阴沉沉的，好像随时要下雨。' +
  '你要是还没吃饭，就先别管我了，去吃点热的吧。' +
  '我等你回来，慢慢说给我听就好。';

/** 24kHz / mono / 16bit：1ms 音频 = 48 字节 */
const BYTES_PER_MS = 24000 * 2 / 1000;

function parseArgs(argv) {
  const out = {
    key: process.env.MIMO_API_KEY || '',
    model: 'mimo-v2.5-tts-voiceclone',
    voice: '',
    ref: DEFAULT_REF,
    mime: 'audio/wav',
    text: DEFAULT_TEXT,
    prompt: '',
    runs: 1,
  };
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i];
    const next = () => argv[++i];
    if (a === '--key') out.key = next();
    else if (a === '--model') out.model = next();
    else if (a === '--voice') out.voice = next();
    else if (a === '--ref') out.ref = next();
    else if (a === '--mime') out.mime = next();
    else if (a === '--text') out.text = next();
    else if (a === '--prompt') out.prompt = next();
    else if (a === '--runs') out.runs = Math.max(1, parseInt(next(), 10) || 1);
    else if (a === '--help' || a === '-h') {
      console.log(fs.readFileSync(__filename, 'utf8').split('## 用法')[1].split('## 怎么判读')[0]);
      process.exit(0);
    } else {
      console.error(`未知参数: ${a}（--help 看用法）`);
      process.exit(2);
    }
  }
  return out;
}

function buildVoice(opts) {
  if (opts.voice) return { voice: opts.voice, kind: `预置音色「${opts.voice}」` };

  const abs = path.resolve(opts.ref);
  if (!fs.existsSync(abs)) {
    console.error(`参考音频不存在: ${abs}`);
    process.exit(2);
  }
  const bytes = fs.readFileSync(abs);
  const b64 = bytes.toString('base64');
  const mb = b64.length / 1024 / 1024;
  if (mb > 10) {
    console.error(`参考音频 base64 ${mb.toFixed(2)}MB 超过 MiMo 的 10MB 上限`);
    process.exit(2);
  }
  console.log(
    `参考音频: ${opts.ref}（${(bytes.length / 1024).toFixed(0)}KB → base64 ` +
      `${(b64.length / 1024).toFixed(0)}KB，${mb.toFixed(2)}MB / 10MB）`
  );
  return { voice: `data:${opts.mime};base64,${b64}`, kind: `克隆音色（${path.basename(opts.ref)}）` };
}

async function runOnce(opts, voiceUri, runIndex, total) {
  const messages = [];
  if (opts.prompt) messages.push({ role: 'user', content: opts.prompt });
  messages.push({ role: 'assistant', content: opts.text });

  const body = {
    model: opts.model,
    messages,
    audio: { format: 'pcm16', voice: voiceUri },
    stream: true,
  };

  console.log(`\n── run ${runIndex}/${total} ──`);

  const t0 = Date.now();
  let res;
  try {
    res = await fetch(URL, {
      method: 'POST',
      headers: { 'api-key': opts.key, 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
  } catch (e) {
    console.error(`请求失败: ${e.message}`);
    return null;
  }

  if (!res.ok) {
    const text = await res.text().catch(() => '');
    console.error(`HTTP ${res.status} ${res.statusText}\n${text.slice(0, 600)}`);
    return null;
  }

  const chunks = []; // { atMs, gapMs, bytes }
  let lastArrival = null;
  let buf = '';
  let firstChunkAt = null;

  const decoder = new TextDecoder();
  for await (const part of res.body) {
    buf += decoder.decode(part, { stream: true });

    let nl;
    while ((nl = buf.indexOf('\n')) !== -1) {
      const line = buf.slice(0, nl).trim();
      buf = buf.slice(nl + 1);
      if (!line.startsWith('data:')) continue;

      const payload = line.slice(5).trim();
      if (payload === '[DONE]') continue;

      let json;
      try {
        json = JSON.parse(payload);
      } catch {
        continue;
      }
      const delta = json?.choices?.[0]?.delta;
      if (!delta) continue;

      const audio = delta.audio;
      const b64 = typeof audio === 'string' ? audio : audio?.data;
      if (!b64) continue;

      const now = Date.now() - t0;
      if (firstChunkAt === null) firstChunkAt = now;
      const gap = lastArrival === null ? null : now - lastArrival;
      lastArrival = now;

      chunks.push({ atMs: now, gapMs: gap, bytes: Buffer.from(b64, 'base64').length });
    }
  }

  if (chunks.length === 0) {
    console.error('一个音频块都没收到 —— 接口报错或响应结构变了');
    return null;
  }

  // ── 逐块明细 ──────────────────────────────────────────────────────
  let audioMs = 0;
  let maxGap = 0;
  let maxGapAtAudio = 0;
  const rows = [];
  chunks.forEach((c, i) => {
    if (c.gapMs !== null && c.gapMs > maxGap) {
      maxGap = c.gapMs;
      maxGapAtAudio = audioMs;
    }
    audioMs += c.bytes / BYTES_PER_MS;
    rows.push({ i: i + 1, ...c, audioMs });
  });

  const pad = (s, n) => String(s).padStart(n);
  console.log(`  ${pad('#', 3)} ${pad('到达ms', 7)} ${pad('间隔ms', 7)} ${pad('累计音频ms', 11)} ${pad('字节', 7)}`);
  for (const r of rows) {
    const flag = r.gapMs !== null && r.gapMs >= STALL_WARN_MS ? '  ⚠️' : '';
    console.log(
      `  ${pad(r.i, 3)} ${pad(r.atMs, 7)} ${pad(r.gapMs === null ? '-' : r.gapMs, 7)} ` +
        `${pad(r.audioMs.toFixed(0), 11)} ${pad(r.bytes, 7)}${flag}`
    );
  }

  const wallMs = lastArrival;
  console.log(
    `  小结: 首块=${firstChunkAt}ms 末块=${wallMs}ms 音频≈${audioMs.toFixed(0)}ms ` +
      `块数=${chunks.length} 最大间隔=${maxGap}ms@音频${maxGapAtAudio.toFixed(0)}ms`
  );

  return { firstChunkAt, wallMs, audioMs, chunks: chunks.length, maxGap, maxGapAtAudio };
}

async function main() {
  const opts = parseArgs(process.argv);
  if (!opts.key) {
    console.error('缺少 API Key：用 --key sk-xxx，或设环境变量 MIMO_API_KEY');
    process.exit(2);
  }

  console.log(`模型: ${opts.model}`);
  const { voice, kind } = buildVoice(opts);
  console.log(`音色: ${kind}`);
  console.log(`文本: ${opts.text.length} 字 —— ${opts.text.slice(0, 40)}…`);

  const results = [];
  for (let i = 1; i <= opts.runs; i++) {
    const r = await runOnce(opts, voice, i, opts.runs);
    if (r) results.push(r);
  }
  if (results.length === 0) process.exit(1);

  // ── 总结 ──────────────────────────────────────────────────────────
  const worst = results.reduce((a, b) => (b.maxGap > a.maxGap ? b : a));
  const avgFirst = results.reduce((s, r) => s + r.firstChunkAt, 0) / results.length;

  console.log('\n══ 总结 ══');
  console.log(
    `跑 ${results.length} 次 | 首块均值 ${avgFirst.toFixed(0)}ms | ` +
      `最大间隔的全局最差 ${worst.maxGap}ms @音频${worst.maxGapAtAudio.toFixed(0)}ms`
  );

  if (worst.maxGap >= STALL_WARN_MS) {
    console.log(
      `\n⚠️ 服务端下发**不均匀**：出现过 ${worst.maxGap}ms 的空档（阈值 ${STALL_WARN_MS}ms）。\n` +
        `   这就是 app 里「说着说着卡一下」的服务端解释。\n` +
        `   若该空档稳定落在句子边界，说明服务端是按内部切分一段段憋出来的。`
    );
  } else {
    console.log(
      `\n✅ 服务端下发是均匀的（最大间隔 ${worst.maxGap}ms < ${STALL_WARN_MS}ms）。\n` +
        `   那么 app 里的卡顿**不在这条链路上** —— 去查本地播放侧：\n` +
        `   adb logcat -s AudioPlayer:D ChatRepo:D 看「硬件欠载」和「音频断供」。`
    );
  }
}

main();
