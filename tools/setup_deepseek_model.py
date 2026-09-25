#!/usr/bin/env python3
"""安装 DS鲸鱼娘（DeepSeek 酱）Live2D 模型

为什么需要这个脚本
------------------
1. `app/src/main/assets/live2d/models/` 被 .gitignore 排除（第三方美术资产不入库），
   所以**本脚本才是这次改动的唯一事实来源**：换机器 / 重新 clone 之后重跑一次即可。
2. 作者提供的 `c_0120.model3.json` **没有 Motions 段，也没有 Expressions 段** ——
   44 个 `.exp3.json` 和 7 条动作文件全是"裸文件"。pixi-live2d-display 只认
   model3.json 里注册过的条目，不注册的话 LLM 调表情 / 播动作会**全部静默失效**
   （pixi 找不到就忽略，不报错）。所以这里必须补注册。

选型说明：用「鼠控版」
----------------------
压缩包里有「DS面捕版」和「DS鼠控版」两个目录，我做过全量 MD5 比对，
两者**只有 4 个文件不同**：吐舌.exp3.json / icon.png / c_0120.vtube.json /
items_pinned_to_model.json。后两个是 VTube Studio 的工程配置，与播放无关。

关键差异是「吐舌」：鼠控版额外写了 ParamMouthForm / ParamMouthOpenY，**自己把嘴张开**；
面捕版只挂了个标记参数，靠面捕实时驱动嘴部。本项目没有面捕，
用面捕版会得到一个"嘴不张的吐舌"怪表情 —— 所以选鼠控版。

文件名为什么要 ASCII 化
-----------------------
作者的 44 个表情文件是中文名（`脸红.exp3.json` 等）。Android 打包链路（AAPT2）
在 Windows 上对 assets 里的非 ASCII 文件名支持不一致，而 `model3.json` 里的
`Expressions[].Name` 是 JSON 字符串、UTF-8 完全没问题 —— 所以：
  * 文件重命名成 `e01.exp3.json` …（纯 ASCII，打包安全）
  * `Name` 保留原始中文名（LLM 侧按中文短标签调用，与银狼模型同一套约定）

用法
----
    python tools/setup_deepseek_model.py --zip "C:/Users/xxx/Downloads/DS鲸鱼娘.zip"
    python tools/setup_deepseek_model.py --src <已解包目录>     # 用已解包的 DS鼠控版
    python tools/setup_deepseek_model.py --dry-run              # 只打印将要做的事
    python tools/setup_deepseek_model.py --remove               # 卸载

模型目录被 .gitignore 排除，所以本脚本不会、也不该被提交模型文件。
"""

import argparse
import json
import os
import shutil
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEST_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "live2d", "models", "deepseek")

# 优先使用鼠控版（见模块 docstring 的选型说明）
PREFERRED_VARIANT = "DS鼠控版"

# ---------------------------------------------------------------------------
# 表情清单
#
# 顺序 = 写入 model3.json 的注册顺序，也是文件名编号顺序。
# 左：原始中文名（同时作为 Expressions[].Name，LLM 侧按它调用）
# 右：ASCII 文件名（打包安全）
#
# 刻意注册**全部 44 个**而不是只注册白名单里的那些：注册只是"让模型认得这个名字"，
# 不产生任何行为；真正决定 LLM 能调哪些的是 Kotlin 侧的 ExpressionSet
# （见 Live2DExpressions.DEEPSEEK）。分开的好处是以后想放开某个道具表情，
# 只改 Kotlin 一行，不用重跑本脚本。
# ---------------------------------------------------------------------------
EXPRESSIONS = [
    "爱心眼", "巴菲", "悲伤", "闭眼口水", "撤回", "呆呆眼", "单边马尾", "蛋包饭",
    "点菜按下", "调皮", "方眼镜", "感叹号", "蝴蝶结贴纸", "画笔", "挤", "鲸鱼",
    "鲸鱼放桌上", "开心兴奋", "哭", "脸红", "流汗", "猫猫贴纸", "喵喵手~喵~动画",
    "魔爪", "魔爪换色", "墨镜", "情绪花花", "深色桌布", "生气", "手机换色",
    "双手比耶", "头箍", "吐魂", "吐舌", "兔兔贴纸", "椭圆眼镜", "问号", "橡皮",
    "心跳", "星星眼", "阴暗", "圆眼镜", "晕晕", "love",
]

# ---------------------------------------------------------------------------
# 动作清单
#
# 全部是作者原文件（Loop: true），**不改数据、只改文件名**。
#
# Idle 组：作者的 idle.motion3.json 就是"安静待机"（4s / 89 曲线，把各种道具
#   参数都压在 0），直接作为 Idle 组循环播放即可 —— 与银狼那种"没有 Idle 组、
#   需要脚本另造动作"的情况不同，这里不需要造动作。
# Action 组：吹泡泡 / 自拍 / 开盖 / 番茄酱 / 喷水等道具演出。注册但不主动播放
#   （pixi 只会自动播 Idle 组），留给以后做 [[m:]] 动作扩展。
# ---------------------------------------------------------------------------
IDLE_MOTIONS = [
    ("motions/idle.motion3.json", "m01_idle.motion3.json", "作者原始待机（4s，循环）"),
]

ACTION_MOTIONS = [
    ("motions/chuipaopao.motion3.json", "m02_chuipaopao.motion3.json", "吹泡泡糖（5s）"),
    ("motions/喷水.motion3.json", "m03_pengshui.motion3.json", "鲸鱼喷水（0.47s）"),
    ("motions/自拍.motion3.json", "m04_zipai.motion3.json", "自拍（3.3s）"),
    ("motions/自拍简单.motion3.json", "m05_zipai_simple.motion3.json", "快速自拍（1.27s）"),
    ("motions/开盖.motion3.json", "m06_kaigai.motion3.json", "蛋包饭开盖（1s）"),
    ("motions/番茄酱.motion3.json", "m07_fanqiejiang.motion3.json", "挤番茄酱 moe moe Q（5s）"),
]

MODEL_FILE = "c_0120.model3.json"


def find_variant_dir(root):
    """在解包目录里定位「DS鼠控版」（兼容多套解包方式）"""
    # 直接命中
    direct = os.path.join(root, PREFERRED_VARIANT)
    if os.path.isdir(direct):
        return direct
    # 递归找
    for cur, dirs, _ in os.walk(root):
        for d in dirs:
            if d == PREFERRED_VARIANT:
                return os.path.join(cur, d)
    # 退而求其次：只要找到一个含 model3.json 的目录
    for cur, _, files in os.walk(root):
        if MODEL_FILE in files:
            return cur
    return None


def extract_zip(zip_path, work_dir):
    """解出外层包与内层包（GBK 文件名修正），返回可用作 src 的根目录"""
    os.makedirs(work_dir, exist_ok=True)

    def _extract(zpath, dest):
        with zipfile.ZipFile(zpath) as z:
            for info in z.infolist():
                raw = info.filename
                # 作者在 Windows 上打包且未置 UTF-8 标志位 → 需按 GBK 还原
                name = raw if (info.flag_bits & 0x800) else _decode_gbk(raw)
                target = os.path.join(dest, name.replace("\\", "/"))
                if info.is_dir() or name.endswith("/"):
                    os.makedirs(target, exist_ok=True)
                    continue
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with z.open(info) as src, open(target, "wb") as out:
                    shutil.copyfileobj(src, out)

    _extract(zip_path, work_dir)
    # 内层还有一层 zip
    for name in os.listdir(work_dir):
        p = os.path.join(work_dir, name)
        if name.lower().endswith(".zip") and os.path.isfile(p):
            _extract(p, os.path.join(work_dir, os.path.splitext(name)[0]))
    return work_dir


def _decode_gbk(raw):
    try:
        return raw.encode("cp437").decode("gbk")
    except Exception:
        return raw


def build_model3(src_dir):
    """基于作者原文件生成注册好 Motions / Expressions 的 model3.json"""
    with open(os.path.join(src_dir, MODEL_FILE), encoding="utf-8") as f:
        data = json.load(f)

    refs = data.setdefault("FileReferences", {})

    motions = {}
    if IDLE_MOTIONS:
        motions["Idle"] = [{"File": dst} for _, dst, _ in IDLE_MOTIONS]
    if ACTION_MOTIONS:
        motions["Action"] = [{"File": dst} for _, dst, _ in ACTION_MOTIONS]
    refs["Motions"] = motions

    refs["Expressions"] = [
        {"Name": name, "File": f"expressions/{expr_file_name(i)}"}
        for i, name in enumerate(EXPRESSIONS, start=1)
    ]

    # 作者原文件没有 Groups 段里的 LipSync 内容（Ids 为空），但 ParamMouthOpenY
    # 真实存在（已直接扫 moc3 确认）。补进 LipSync 组，让运行库与自检工具都
    # 能识别到口型通道；bridge.js 另外还会直接写参数兜底。
    groups = data.setdefault("Groups", [])
    for g in groups:
        if g.get("Name") == "LipSync":
            g["Ids"] = ["ParamMouthOpenY"]
    if not any(g.get("Name") == "LipSync" for g in groups):
        groups.append({
            "Target": "Parameter",
            "Name": "LipSync",
            "Ids": ["ParamMouthOpenY"],
        })
    return data


def expr_file_name(index):
    return f"e{index:02d}.exp3.json"


def install(src_dir, dest_dir, dry_run=False):
    plan = []

    # 1. 模型本体（moc3 / 贴图 / 物理 / cdi3）
    for name in ("c_0120.moc3", "c_0120.physics3.json", "c_0120.cdi3.json"):
        if os.path.exists(os.path.join(src_dir, name)):
            plan.append((os.path.join(src_dir, name), name))

    tex_dir = "c_0120.2048"
    src_tex = os.path.join(src_dir, tex_dir)
    if os.path.isdir(src_tex):
        for t in sorted(os.listdir(src_tex)):
            plan.append((os.path.join(src_tex, t), f"{tex_dir}/{t}"))

    # 2. 表情：中文名 → ASCII 文件名
    for i, name in enumerate(EXPRESSIONS, start=1):
        s = os.path.join(src_dir, f"{name}.exp3.json")
        if not os.path.exists(s):
            print(f"  [!] 表情缺失，跳过: {name}.exp3.json", file=sys.stderr)
            continue
        plan.append((s, f"expressions/{expr_file_name(i)}"))

    # 3. 动作
    for s_rel, d_rel, _ in IDLE_MOTIONS + ACTION_MOTIONS:
        s = os.path.join(src_dir, s_rel.replace("/", os.sep))
        if not os.path.exists(s):
            print(f"  [!] 动作缺失，跳过: {s_rel}", file=sys.stderr)
            continue
        plan.append((s, d_rel))

    if dry_run:
        print(f"[dry-run] 源目录: {src_dir}")
        print(f"[dry-run] 目标目录: {dest_dir}")
        for s, d in plan:
            print(f"  {os.path.relpath(s, src_dir)}  ->  {d}")
        print(f"[dry-run] 另写入 {MODEL_FILE}（补注册 Motions/Expressions）")
        return 0

    if os.path.isdir(dest_dir):
        shutil.rmtree(dest_dir)
    os.makedirs(dest_dir, exist_ok=True)

    for s, d in plan:
        target = os.path.join(dest_dir, d.replace("/", os.sep))
        os.makedirs(os.path.dirname(target), exist_ok=True)
        shutil.copyfile(s, target)

    model3 = build_model3(src_dir)
    with open(os.path.join(dest_dir, MODEL_FILE), "w", encoding="utf-8", newline="\n") as f:
        json.dump(model3, f, ensure_ascii=False, indent=2)

    print(f"  [ok] 已安装 {len(plan)} 个文件 -> {dest_dir}")
    print(f"  [ok] {MODEL_FILE}: 注册 {len(EXPRESSIONS)} 个表情、"
          f"{len(IDLE_MOTIONS)} 条 Idle 动作、{len(ACTION_MOTIONS)} 条 Action 动作")
    return 0


def remove(dest_dir):
    if os.path.isdir(dest_dir):
        shutil.rmtree(dest_dir)
        print(f"  [ok] 已卸载 {dest_dir}")
    else:
        print(f"  [skip] 不存在: {dest_dir}")
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--zip", help="DS鲸鱼娘.zip 路径")
    ap.add_argument("--src", help="已解包的 DS鼠控版 目录")
    ap.add_argument("--work", default=os.path.join(ROOT, "app", "build", "deepseek-model-src"),
                    help="解包临时目录（默认放 build/ 下，不会被打进 APK）")
    ap.add_argument("--dest", default=DEST_DIR, help="安装目标目录")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--remove", action="store_true")
    args = ap.parse_args()

    if args.remove:
        return remove(args.dest)

    if args.src:
        src_root = args.src
    elif args.zip:
        if not os.path.exists(args.zip):
            print(f"找不到 zip: {args.zip}", file=sys.stderr)
            return 2
        print(f"==> 解包 {args.zip}")
        src_root = extract_zip(args.zip, args.work)
    else:
        ap.error("需要 --zip 或 --src（或用 --remove 卸载）")

    src_dir = find_variant_dir(src_root)
    if not src_dir:
        print(f"在 {src_root} 下找不到含 {MODEL_FILE} 的目录", file=sys.stderr)
        return 2

    print(f"==> 使用模型: {src_dir}")
    return install(src_dir, args.dest, dry_run=args.dry_run)


if __name__ == "__main__":
    raise SystemExit(main())
