/**
 * 表情白名单一致性校验
 *
 * 校验 Live2DExpression.kt 里「短标签 → 模型真实表情名」的映射，
 * 与模型文件、以及解析用的正则是否自洽。
 *
 * 为什么需要它：模型表情名是中文、带编号且空格不统一
 * （`01黑脸` / `02 脸红爱心` / `03 生气` / `月卡`），
 * 少写一个空格 pixi 就会静默忽略 —— 现象是「表情没变」，而且不报任何错。
 * 这类错误靠肉眼审查几乎不可能发现，只能让机器来对。
 *
 * 运行：node tools/check_expression_names.cjs
 * 模型文件未随仓库分发（见 .gitignore），缺失时自动跳过模型比对。
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const KT = path.join(
  ROOT, 'app', 'src', 'main', 'java', 'com', 'lv999call', 'app', 'domain', 'model', 'Live2DExpression.kt'
);
const MODEL = path.join(
  ROOT, 'app', 'src', 'main', 'assets', 'live2d', 'models', 'silverwolf', 'silverwolf.model3.json'
);

let pass = 0, fail = 0;
const check = (name, cond, extra = '') => {
  if (cond) { pass++; console.log('  OK   ' + name); }
  else { fail++; console.log('  FAIL ' + name + (extra ? '  -> ' + extra : '')); }
};

const kt = fs.readFileSync(KT, 'utf8');

// 枚举项：NAME("短标签", "模型真实名", "说明")，
// 姿势类多一个参数：NAME("…", "…", "…", Kind.POSE)
const ENTRY = /^\s*([A-Z][A-Z0-9_]*)\(\s*"([^"]*)"\s*,\s*"([^"]*)"\s*,\s*"([^"]*)"\s*(?:,\s*(?:Live2DExpression\.)?Kind\.([A-Z_]+))?\s*\)/gm;
const entries = [];
for (const m of kt.matchAll(ENTRY)) {
  entries.push({ name: m[1], key: m[2], modelName: m[3], hint: m[4], kind: m[5] || 'EMOTION' });
}

const regexSrc = (kt.match(/TAG_REGEX\s*=\s*Regex\("""([\s\S]*?)"""\)/) || [])[1];

console.log('\n[1] 枚举自身');
check('解析到表情枚举项', entries.length >= 8, `count=${entries.length}`);
check('短标签唯一', new Set(entries.map((e) => e.key)).size === entries.length,
      entries.map((e) => e.key).join(','));
check('模型名唯一', new Set(entries.map((e) => e.modelName)).size === entries.length,
      entries.map((e) => e.modelName).join(','));
check('每项都有情绪说明', entries.every((e) => e.hint.length > 0));
check('条目只有 EMOTION / POSE 两种', entries.every((e) => e.kind === 'EMOTION' || e.kind === 'POSE'),
      entries.filter((e) => e.kind !== 'EMOTION' && e.kind !== 'POSE').map((e) => e.name).join(','));
const poses = entries.filter((e) => e.kind === 'POSE');
check('至少有一条姿势类动作', poses.length >= 1, poses.map((e) => e.key).join(','));

console.log('\n[2] 标签能被正则解析（Kotlin 与 JS 共用同一份正则源）');
check('取到 TAG_REGEX', !!regexSrc, String(regexSrc));
const TAG_ONE = new RegExp('^' + regexSrc + '$');
for (const e of entries) {
  // 姿势走 [[m:…]]，情绪走 [[e:…]]；正则两个字母都收
  const letter = e.kind === 'POSE' ? 'm' : 'e';
  const tag = `[[${letter}:${e.key}]]`;
  const m = tag.match(TAG_ONE);   // 必须整串匹配：标签前后不能有残留字符
  check(tag + (m ? ' 解析为通道 ' + m[1] + ' / ' + m[2] : ' 解析失败'),
        !!m && m[2] === e.key && m[1].toLowerCase() === letter,
        `匹配到 ${JSON.stringify(m && [m[1], m[2]])}`);
}
check('短标签不含方括号（否则会把相邻标签并成一个）',
      entries.every((e) => !/[\[\]]/.test(e.key)),
      entries.filter((e) => /[\[\]]/.test(e.key)).map((e) => e.key).join(','));

console.log('\n[3] 与模型文件比对');
if (!fs.existsSync(MODEL)) {
  console.log('  SKIP 模型文件不存在（已被 .gitignore 排除）：' + path.relative(ROOT, MODEL));
} else {
  const model = JSON.parse(fs.readFileSync(MODEL, 'utf8'));
  const modelNames = ((model.FileReferences || {}).Expressions || []).map((x) => x.Name);
  check('模型里读到了表情', modelNames.length > 0, `count=${modelNames.length}`);

  const missing = entries.filter((e) => !modelNames.includes(e.modelName));
  check('每个枚举的模型名都能在模型里找到', missing.length === 0,
        missing.map((e) => `${e.name}="${e.modelName}"`).join(' / '));

  // 宽松匹配（忽略空白）后仍对不上，说明是名字本身写错了而不仅是空格问题
  const norm = (s) => String(s).replace(/\s+/g, '');
  const normModel = modelNames.map(norm);
  const hardMiss = entries.filter((e) => !normModel.includes(norm(e.modelName)));
  check('去掉空格后也没有拼写错误', hardMiss.length === 0,
        hardMiss.map((e) => `"${e.modelName}"`).join(' / '));

  const used = new Set(entries.map((e) => norm(e.modelName)));
  const unused = modelNames.filter((n) => !used.has(norm(n)));
  console.log('  提示 未开放给 LLM 的表情（' + unused.join('、') + '）');
  console.log(`  已开放：情绪 ${entries.length - poses.length} 条 / 姿势 ${poses.length} 条`);
}

console.log('\n' + '='.repeat(46));
console.log(`通过 ${pass} / 失败 ${fail}`);
process.exit(fail === 0 ? 0 : 1);
