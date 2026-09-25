/**
 * 内置角色 → 表情集 → 模型文件 三方一致性校验
 *
 * 为什么需要它
 * ------------
 * 1. 模型表情名是中文、带编号且空格不统一（`01黑脸` / `02 脸红爱心` / `月卡`），
 *    少写一个空格 pixi 就会**静默忽略** —— 现象是「表情没变」，不报任何错。
 *    这类错误靠肉眼审查几乎不可能发现，只能让机器来对。
 * 2. 内置预设已并列化：每个角色各自引用一套表情集与一个模型文件。
 *    把 DeepSeek 酱错接到银狼的表情集（或反之）会得到同一类静默失效，
 *    而且更隐蔽 —— 所以这里按 BuiltInCharacters 的真实接线去校验，
 *    而不是分别校验两个文件。
 *
 * 运行：node tools/check_expression_names.cjs
 * 模型文件未随仓库分发（见 .gitignore），缺失时自动跳过对应的模型比对。
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const KT = path.join(
  ROOT, 'app', 'src', 'main', 'java', 'com', 'lv999call', 'app', 'domain', 'model', 'Live2DExpression.kt'
);
const CHARACTERS_KT = path.join(
  ROOT, 'app', 'src', 'main', 'java', 'com', 'lv999call', 'app', 'preset', 'BuiltInCharacters.kt'
);
const MODELS_ROOT = path.join(ROOT, 'app', 'src', 'main', 'assets', 'live2d');

let pass = 0, fail = 0;
const check = (name, cond, extra = '') => {
  if (cond) { pass++; console.log('  OK   ' + name); }
  else { fail++; console.log('  FAIL ' + name + (extra ? '  -> ' + extra : '')); }
};

const kt = fs.readFileSync(KT, 'utf8');
const charsKt = fs.readFileSync(CHARACTERS_KT, 'utf8');

// ---------------------------------------------------------------- 解析表情集
//
// 结构：
//   val SILVERWOLF = ExpressionSet(
//     displayName = "银狼",
//     entries = listOf(
//       Live2DExpression("黑脸", "01黑脸", "无语、嫌弃、瞪人"),
//       Live2DExpression("抱胸", "12 抱胸手", "…", Live2DExpression.Kind.POSE),
//     ),
//     examples = listOf(...)
//   )
/**
 * 从 `name(` 的 `(` 位置起，取括号内内容（正确处理嵌套括号与字符串里的括号）。
 *
 * 不用正则的懒匹配：表达式里嵌着 `listOf(...)`、`mapOf(...)`、字符串里还可能有
 * 中文括号，`[\s\S]*?` 会在错误的 `)` 上收尾，把两个集合并成一个 —— 这正是
 * 本脚本上一版"解析到 0 条却全部通过"的成因（空集合让 every() 恒真）。
 */
function extractBalanced(src, openIdx) {
  let depth = 0;
  let inStr = false;
  for (let i = openIdx; i < src.length; i++) {
    const ch = src[i];
    if (inStr) {
      if (ch === '\\') { i++; continue; }
      if (ch === '"') inStr = false;
      continue;
    }
    if (ch === '"') { inStr = true; continue; }
    if (ch === '(') depth++;
    else if (ch === ')') {
      depth--;
      if (depth === 0) return src.slice(openIdx + 1, i);
    }
  }
  return null;
}

/** 扫描所有 `val NAME = <ctor>(` 形式，返回 [{name, body}] */
function collectCtorBodies(src, ctor) {
  const out = [];
  const re = new RegExp(`val\\s+([A-Z][A-Z0-9_]*)\\s*=\\s*${ctor}\\s*\\(`, 'g');
  for (const m of src.matchAll(re)) {
    const openIdx = m.index + m[0].length - 1;
    const body = extractBalanced(src, openIdx);
    if (body !== null) out.push({ name: m[1], body });
  }
  return out;
}

const ENTRY = /Live2DExpression\(\s*"([^"]*)"\s*,\s*"([^"]*)"\s*,\s*"([^"]*)"\s*(?:,\s*(?:Live2DExpression\.)?Kind\.([A-Z_]+))?\s*\)/g;

/** @type {Record<string, {displayName:string, entries:Array}>} */
const sets = {};
for (const { name, body } of collectCtorBodies(kt, 'ExpressionSet')) {
  const displayName = (body.match(/displayName\s*=\s*"([^"]*)"/) || [])[1] || '';
  const entries = [];
  for (const e of body.matchAll(ENTRY)) {
    entries.push({ key: e[1], modelName: e[2], hint: e[3], kind: e[4] || 'EMOTION' });
  }
  sets[name] = { displayName, entries };
}

// ---------------------------------------------------------- 解析角色真实接线
//
// val SILVERWOLF = BuiltInCharacter(
//   id = "silverwolf",
//   displayName = "银狼",
//   modelPath = "models/silverwolf/silverwolf.model3.json",
//   expressions = Live2DExpressions.SILVERWOLF,
//   ttsPolicy = TtsPolicy.CloneVoice(...) / TtsPolicy.PresetVoice(voice = "冰糖"),
//   ...
// )
const characters = [];
for (const { name: constName, body } of collectCtorBodies(charsKt, 'BuiltInCharacter')) {
  const pick = (re) => (body.match(re) || [])[1];
  characters.push({
    constName,
    body,
    id: pick(/\bid\s*=\s*"([^"]*)"/),
    displayName: pick(/displayName\s*=\s*"([^"]*)"/),
    modelPath: pick(/modelPath\s*=\s*"([^"]*)"/),
    expressionsRef: pick(/expressions\s*=\s*Live2DExpressions\.([A-Z][A-Z0-9_]*)/),
    ttsPresetVoice: pick(/TtsPolicy\.PresetVoice\(\s*voice\s*=\s*"([^"]*)"/),
    ttsPresetModel: pick(/TtsPolicy\.PresetVoice\(\s*modelId\s*=\s*"([^"]*)"/),
    ttsClone: /TtsPolicy\.CloneVoice\(/.test(body),
  });
}

// 注册表里 ALL 列出的角色，必须都在上面解析到
const allList = (charsKt.match(/val\s+ALL\s*:\s*List<BuiltInCharacter>\s*=\s*listOf\(([\s\S]*?)\)/) || [])[1] || '';
const registered = [...allList.matchAll(/([A-Z][A-Z0-9_]*)/g)].map((m) => m[1]);

console.log('\n[0] 结构解析');
check('解析到表情集', Object.keys(sets).length >= 2, Object.keys(sets).join(','));
check('解析到内置角色', characters.length >= 2, characters.map((c) => c.id).join(','));
check('每个注册角色都解析到了定义', registered.every((r) => characters.some((c) => c.constName === r)),
      `registered=${registered.join(',')} parsed=${characters.map((c) => c.constName).join(',')}`);
check('每个角色都引用了已定义的表情集',
      characters.every((c) => c.expressionsRef && sets[c.expressionsRef]),
      characters.filter((c) => !c.expressionsRef || !sets[c.expressionsRef]).map((c) => `${c.id}->${c.expressionsRef}`).join(','));
check('角色 id 唯一', new Set(characters.map((c) => c.id)).size === characters.length);
check('角色模型路径唯一（并列预设不应共用一个模型文件）',
      new Set(characters.map((c) => c.modelPath)).size === characters.length,
      characters.map((c) => c.modelPath).join(' / '));

// ------------------------------------------------------- 逐角色校验（含模型）
for (const c of characters) {
  const set = sets[c.expressionsRef];
  if (!set) continue;

  console.log(`\n[${c.displayName}] 表情集 ${c.expressionsRef}（${set.entries.length} 条）`);

  check(`${c.id}: 表情集非空`, set.entries.length > 0);
  check(`${c.id}: 短标签唯一`,
        new Set(set.entries.map((e) => e.key)).size === set.entries.length,
        set.entries.map((e) => e.key).join(','));
  check(`${c.id}: 模型真实名唯一`,
        new Set(set.entries.map((e) => e.modelName)).size === set.entries.length,
        set.entries.map((e) => e.modelName).join(','));
  check(`${c.id}: 每项都有情绪说明`, set.entries.every((e) => e.hint.length > 0));
  check(`${c.id}: 条目只有 EMOTION / POSE`,
        set.entries.every((e) => e.kind === 'EMOTION' || e.kind === 'POSE'),
        set.entries.filter((e) => e.kind !== 'EMOTION' && e.kind !== 'POSE').map((e) => e.key).join(','));
  check(`${c.id}: 短标签不含方括号（否则会把相邻标签并成一个）`,
        set.entries.every((e) => !/[\[\]]/.test(e.key)),
        set.entries.filter((e) => /[\[\]]/.test(e.key)).map((e) => e.key).join(','));

  // 标签必须能被共用正则整串解析
  const regexSrc = (kt.match(/TAG_REGEX\s*=\s*Regex\("""([\s\S]*?)"""\)/) || [])[1];
  check(`${c.id}: 取到 TAG_REGEX`, !!regexSrc, String(regexSrc));
  if (regexSrc) {
    const TAG_ONE = new RegExp('^' + regexSrc + '$');
    const bad = [];
    for (const e of set.entries) {
      const letter = e.kind === 'POSE' ? 'm' : 'e';
      const tag = `[[${letter}:${e.key}]]`;
      const mm = tag.match(TAG_ONE);
      if (!mm || mm[2] !== e.key || mm[1].toLowerCase() !== letter) bad.push(tag);
    }
    check(`${c.id}: 所有标签都能被正则解析`, bad.length === 0, bad.join(' '));
  }

  // 与模型文件比对
  const modelFile = path.join(MODELS_ROOT, c.modelPath.split('/').join(path.sep));
  if (!fs.existsSync(modelFile)) {
    console.log('  SKIP 模型文件不存在（已被 .gitignore 排除）：' + path.relative(ROOT, modelFile));
    console.log('       -> 用 python tools/setup_deepseek_model.py 或自备模型后重跑本检查');
    continue;
  }

  const model = JSON.parse(fs.readFileSync(modelFile, 'utf8'));
  const modelNames = ((model.FileReferences || {}).Expressions || []).map((x) => x.Name);
  check(`${c.id}: 模型里读到了表情`, modelNames.length > 0, `count=${modelNames.length}`);

  const missing = set.entries.filter((e) => !modelNames.includes(e.modelName));
  check(`${c.id}: 每个条目的模型名都能在模型里找到`, missing.length === 0,
        missing.map((e) => `"${e.modelName}"`).join(' / '));

  const norm = (s) => String(s).replace(/\s+/g, '');
  const normModel = modelNames.map(norm);
  const hardMiss = set.entries.filter((e) => !normModel.includes(norm(e.modelName)));
  check(`${c.id}: 去掉空格后也没有拼写错误`, hardMiss.length === 0,
        hardMiss.map((e) => `"${e.modelName}"`).join(' / '));

  const poses = set.entries.filter((e) => e.kind === 'POSE');
  const used = new Set(set.entries.map((e) => norm(e.modelName)));
  const unused = modelNames.filter((n) => !used.has(norm(n)));
  console.log('  提示 未开放给 LLM 的表情（' + unused.join('、') + '）');
  console.log(`  已开放：情绪 ${set.entries.length - poses.length} 条 / 姿势 ${poses.length} 条`);
}

// --------------------------------------------------------------- TTS 策略校验
//
// 预置音色只能配 mimo-v2.5-tts（见 MiMo 文档）；写成 voiceclone 会直接报错。
console.log('\n[TTS 策略]');
const PRESET_MODEL = 'mimo-v2.5-tts';
const KNOWN_VOICES = ['冰糖', '茉莉', '苏打', '白桦', 'Mia', 'Chloe', 'Milo', 'Dean'];
for (const c of characters) {
  if (c.ttsPresetVoice) {
    check(`${c.id}: 预置音色模型是 ${PRESET_MODEL}`,
          (c.ttsPresetModel || PRESET_MODEL) === PRESET_MODEL,
          c.ttsPresetModel);
    check(`${c.id}: 预置音色「${c.ttsPresetVoice}」在 MiMo 官方列表内`,
          KNOWN_VOICES.includes(c.ttsPresetVoice),
          KNOWN_VOICES.join('/'));
  }
  if (c.ttsClone) {
    const rel = (c.body.match(/refAudioAsset\s*=\s*"([^"]*)"/) || [])[1];
    check(`${c.id}: 克隆音色的参考音频已随仓库分发`,
          !!rel && fs.existsSync(path.join(ROOT, 'app', 'src', 'main', 'assets', rel)),
          String(rel));
  }
}

console.log('\n' + '='.repeat(46));
console.log(`通过 ${pass} / 失败 ${fail}`);
process.exit(fail === 0 ? 0 : 1);
