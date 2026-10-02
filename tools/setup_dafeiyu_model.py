#!/usr/bin/env python3
"""安装「大肥鱼」Live2D 模型（替换 DeepSeek 酱原来用的 DS鲸鱼娘 c_0120）

为什么需要这个脚本
------------------
1. `app/src/main/assets/live2d/models/` 被 .gitignore 排除（第三方美术资产不入库），
   所以**本脚本才是这次改动的唯一事实来源**：换机器 / 重新 clone 之后重跑一次即可。

2. 作者给的是 **VTube Studio 模型**，直接塞进 assets 播不了：

   - `大肥鱼.model3.json` 只有 Moc / Textures / Physics / DisplayInfo，
     **没有 Motions 段、没有 Expressions 段**，而且 EyeBlink / LipSync 两个组是
     **空数组**。pixi-live2d-display 只认 model3.json 里注册过的条目 ——
     不注册的话 16 个表情 / 6 条动作会**全部静默失效**（pixi 找不到就忽略，不报错）。
   - 文件名全是中文（`大肥鱼.moc3` / `exp/星星眼.exp3.json`）。Android 打包链路（AAPT2）
     在 Windows 上对 assets 里的非 ASCII 文件名支持不一致，而 model3.json 里的
     `Expressions[].Name` 是 JSON 字符串、UTF-8 完全没问题 —— 所以文件重命名成 ASCII，
     `Name` 保留原始中文名（LLM 侧按中文短标签调用，与银狼模型同一套约定）。
   - 贴图是 **4096×2048 + 两张 4096×8192**（共 34.7MB）。4096×8192 超过很多手机的
     GL_MAX_TEXTURE_SIZE（常见上限 4096），上传失败的表现是**整块贴图变黑 / 模型不显示**；
     就算支持，三张加起来也是 ~290MB 显存。所以默认按「长边 ≤ 4096」逐页降采样。

3. 作者自带的 `motion/idle.motion3.json` 写了 `ParamAngleX`（头 yaw）与 `ParamAngleZ`：
   前者与「视线只来自 focus / 呼吸」的硬性约定冲突（见 live2d_motion_check.cjs 的
   GAZE_PARAMS），后者与 bridge.js 程序化待机层的 tilt 通道抢道。所以**不注册 Idle 组**，
   待机交给程序化待机层 + 运行库呼吸 + 眨眼（与银狼同一路线）。
   作者其余 5 条动作（吃饭 / 吃token / token转 / sleep / Scene1）注册成 `Action` 组：
   注册只让模型认得这些名字、不产生任何行为（与表情全量注册同一个理由），
   目前没有代码播它们。

4. `ParamBodyAngleX/Y/Z`、`ParamAngleX2/Y2/Z2` 以及所有头发/耳朵/蝴蝶结/裙部件在这个
   模型里**都是物理输出**（physics3.json），程序化写进去会被每帧覆盖 ——
   所以 bridge.js 的 `deepseek` profile 里没有 sway 通道，只有 tilt。

用法
----
    python tools/setup_dafeiyu_model.py --zip "C:/Users/xxx/Downloads/大肥鱼.zip"
    python tools/setup_dafeiyu_model.py --src <已解包目录>       # 目录里含 大肥鱼/
    python tools/setup_dafeiyu_model.py --dry-run                # 只打印将要做的事
    python tools/setup_dafeiyu_model.py --remove                 # 卸载
    python tools/setup_dafeiyu_model.py --zip ... --max-long-side 2048   # 更省显存

装完之后还要跑（顺序不能反 —— 本脚本会重写 model3.json）：
    python tools/live2d_make_pat.py --model dafeiyu     # 摸头动作 PatOnce 四档
    python tools/live2d_dump_parts.py --model dafeiyu   # 摸头命中盒的 headParts
    node tools/live2d_motion_check.cjs                  # 校验
"""

import argparse
import json
import os
import shutil
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEST_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "live2d", "models", "dafeiyu")

# 模型在压缩包 / 解包目录里的顶层文件夹名
SRC_ROOT_NAME = "大肥鱼"

# 贴图目录名：按降采样后的标称尺寸命名（与作者的 `大肥鱼.4096` 同一套习惯）
TEX_DIR = "dafeiyu.2048"

# ---------------------------------------------------------------------------
# 表情清单
#
# 顺序 = 写入 model3.json 的注册顺序，也是 ASCII 文件名编号顺序。
# 左：原始中文名（同时作为 Expressions[].Name，LLM 侧按它调用）
# 右：ASCII 文件名（打包安全）
#
# 刻意注册**全部 16 个**而不是只注册白名单里的那些：注册只是"让模型认得这个名字"，
# 不产生任何行为；真正决定 LLM 能调哪些的是 Kotlin 侧的 ExpressionSet
# （见 Live2DExpressions.DEEPSEEK）。分开的好处是以后想放开某个道具表情，
# 只改 Kotlin 一行，不用重跑本脚本。
#
# ⚠️ 这个模型的 16 个"表情"**全是 VTS 道具开关**（写的是 Param89~101 这些开关参数），
#    没有任何面部情绪表情 —— 所以 Kotlin 侧的表情集只能由这几条拼出来。
#    面部能用的只有原始参数：笑眼 / 眉毛变形 / 嘴形 / 鼓腮 / 吐舌 / 眼珠。
# ---------------------------------------------------------------------------
EXPRESSIONS = [
    ("星星眼", "e01.exp3.json"),
    ("爱心眼", "e02.exp3.json"),
    ("用户彻底怒了", "e03.exp3.json"),
    ("钢盆", "e04.exp3.json"),
    ("token", "e05.exp3.json"),
    ("气泡", "e06.exp3.json"),
    ("滑动变阻器", "e07.exp3.json"),
    ("滑动变阻器2", "e08.exp3.json"),
    ("滑动变阻器3", "e09.exp3.json"),
    ("滑动变阻器4", "e10.exp3.json"),
    ("滑动变阻器5", "e11.exp3.json"),
    ("滑动变阻器6", "e12.exp3.json"),
    ("滑动变阻器开关", "e13.exp3.json"),
    ("滑动变阻器气泡", "e14.exp3.json"),
    ("气泡滑动", "e15.exp3.json"),
    ("默认", "e16.exp3.json"),
]

# 动作清单（原始名 -> ASCII 文件名）。
# 作者自带的 idle 刻意不在其中，理由见模块 docstring 第 3 条。
MOTIONS = [
    ("吃饭", "eat_rice.motion3.json"),
    ("吃token", "eat_token.motion3.json"),
    ("token转", "token_spin.motion3.json"),
    ("sleep", "sleep.motion3.json"),
    ("Scene1", "scene1.motion3.json"),
]

# 不搬进 assets 的纯 VTS 工程文件（快捷键配置 / 场景 / 缩略图 / 说明书）
SKIP_SUFFIXES = (
    ".vtube.json", ".xyplugin.json",
    "items_pinned_to_model.json",
    "使用说明.md", "使用说明.txt", "使用说明.png",
    "VTS导入与使用教程.txt",
    "大肥鱼.png",
)

MOC_SRC = "大肥鱼.moc3"
PHYSICS_SRC = "大肥鱼.physics3.json"
CDI3_SRC = "大肥鱼.cdi3.json"
TEX_SRC_DIR = "大肥鱼.4096"

# 眼睛开闭 / 口型组：作者那份是空数组，必须按真实参数补上，否则
# 眨眼与口型同步整条失效（pixi 靠这两个组找参数）。
EYE_BLINK_PARAMS = ["ParamEyeLOpen", "ParamEyeROpen"]
LIP_SYNC_PARAMS = ["ParamMouthOpenY"]


def write_json(path, obj):
    text = json.dumps(obj, ensure_ascii=False, indent=2) + "\n"
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


def locate_root(src):
    """在 zip / 目录里找到 `大肥鱼/` 那一层，返回一个「相对路径 -> 绝对路径」的映射工厂。

    返回 (list_names, open_binary, is_zip)。为了同时支持 zip 与目录，
    这里不返回真实路径，而是给出「列出文件名」与「按名读取二进制」两个操作。
    """
    if os.path.isdir(src):
        # 允许直接给 `大肥鱼/` 本身，也允许给它的父目录
        base = src
        if os.path.basename(os.path.normpath(src)) != SRC_ROOT_NAME:
            inner = os.path.join(src, SRC_ROOT_NAME)
            if os.path.isdir(inner):
                base = inner

        def names():
            out = []
            for dirpath, _dirnames, filenames in os.walk(base):
                for fn in filenames:
                    full = os.path.join(dirpath, fn)
                    out.append(os.path.relpath(full, base).replace(os.sep, "/"))
            return out

        def read_binary(rel):
            with open(os.path.join(base, rel.replace("/", os.sep)), "rb") as fh:
                return fh.read()

        return names(), read_binary, False

    zf = zipfile.ZipFile(src)

    def names():
        out = []
        for name in zf.namelist():
            if name.endswith("/"):
                continue
            parts = name.split("/")
            if parts[0] != SRC_ROOT_NAME:
                continue
            out.append("/".join(parts[1:]))
        return out

    def read_binary(rel):
        return zf.read(SRC_ROOT_NAME + "/" + rel)

    return names(), read_binary, True


def should_skip(rel):
    return any(rel.endswith(sfx) for sfx in SKIP_SUFFIXES)


def plan_textures(read_binary, max_long_side):
    """算出每张贴图的降采样计划：[(源相对路径, 目标文件名, 原始尺寸, 目标尺寸)]"""
    from PIL import Image  # 延迟导入：--dry-run / --remove 不需要 Pillow
    import io

    plan = []
    for i in range(8):  # 作者目前 3 张，多留几个位置不写死
        rel = "%s/texture_%02d.png" % (TEX_SRC_DIR, i)
        try:
            data = read_binary(rel)
        except (KeyError, FileNotFoundError):
            break
        with Image.open(io.BytesIO(data)) as im:
            w, h = im.size
        long_side = max(w, h)
        if long_side <= max_long_side:
            tw, th = w, h
        else:
            k = max_long_side / float(long_side)
            tw, th = max(1, int(round(w * k))), max(1, int(round(h * k)))
        plan.append((rel, "texture_%02d.png" % i, (w, h), (tw, th)))
    return plan


def pat_files():
    return ["motions/pat_lv%d.motion3.json" % n for n in (1, 2, 3, 4)]


def build_model3(has_pat):
    """组装 model3.json

    `PatOnce`（摸头动作）由 live2d_make_pat.py 自己注册。这里只在动作文件**已经存在**时
    补上这个组 —— 否则首次安装会注册一个指向不存在文件的组（校验会直接报"文件存在"失败），
    而重跑本脚本又会把 make_pat 注册好的组抹掉。
    """
    motions = {}
    if has_pat:
        motions["PatOnce"] = [{"File": f} for f in pat_files()]
    motions["Action"] = [{"File": "motions/" + dst} for _name, dst in MOTIONS]
    return {
        "Version": 3,
        "FileReferences": {
            "Moc": "dafeiyu.moc3",
            "Textures": ["%s/texture_%02d.png" % (TEX_DIR, i) for i in range(3)],
            "Physics": "dafeiyu.physics3.json",
            "DisplayInfo": "dafeiyu.cdi3.json",
            "Motions": motions,
            "Expressions": [
                {"Name": name, "File": "expressions/" + dst} for name, dst in EXPRESSIONS
            ],
        },
        "Groups": [
            {"Target": "Parameter", "Name": "EyeBlink", "Ids": list(EYE_BLINK_PARAMS)},
            {"Target": "Parameter", "Name": "LipSync", "Ids": list(LIP_SYNC_PARAMS)},
        ],
    }


def main():
    ap = argparse.ArgumentParser(description="安装「大肥鱼」Live2D 模型")
    src_group = ap.add_mutually_exclusive_group(required=True)
    src_group.add_argument("--zip", help="作者给的压缩包")
    src_group.add_argument("--src", help="已解包的目录（含 大肥鱼/）")
    src_group.add_argument("--remove", action="store_true", help="卸载已安装的模型")
    ap.add_argument("--dry-run", action="store_true", help="只打印，不写文件")
    ap.add_argument("--max-long-side", type=int, default=4096,
                    help="贴图长边上限（默认 4096，超过就等比缩小）")
    args = ap.parse_args()

    if args.remove:
        if not os.path.isdir(DEST_DIR):
            print("[跳过] 没有装过：%s" % DEST_DIR)
            return 0
        print("卸载 %s" % DEST_DIR)
        if not args.dry_run:
            shutil.rmtree(DEST_DIR)
        print("  完成。记得把 bridge.js 的 deepseek profile 指回原来的模型，"
              "或重跑 tools/setup_deepseek_model.py。")
        return 0

    src = args.zip or args.src
    if not os.path.exists(src):
        sys.exit("[错误] 源不存在：%s" % src)

    names, read_binary, _is_zip = locate_root(src)
    name_set = set(names)
    for required in (MOC_SRC, PHYSICS_SRC, CDI3_SRC):
        if required not in name_set:
            sys.exit("[错误] 源里没有 %s —— 确认给的是 %s/ 这一层" % (required, SRC_ROOT_NAME))

    try:
        tex_plan = plan_textures(read_binary, args.max_long_side)
    except ImportError:
        sys.exit("[错误] 需要 Pillow 做贴图降采样：pip install Pillow")
    if not tex_plan:
        sys.exit("[错误] 源里没有找到 %s/texture_*.png" % TEX_SRC_DIR)

    print("源：%s" % src)
    print("目标：%s" % DEST_DIR)
    print("\n== 贴图降采样（长边上限 %d）==" % args.max_long_side)
    for rel, dst, (w, h), (tw, th) in tex_plan:
        note = "保持原尺寸" if (w, h) == (tw, th) else "→ 缩小"
        print("  %-40s %dx%d → %dx%d  %s" % (rel, w, h, tw, th, note))

    print("\n== 搬运（ASCII 化）==")
    print("  %-34s → dafeiyu.moc3" % MOC_SRC)
    print("  %-34s → dafeiyu.physics3.json" % PHYSICS_SRC)
    print("  %-34s → dafeiyu.cdi3.json" % CDI3_SRC)
    for name, dst in EXPRESSIONS:
        print("  %-34s → expressions/%s" % ("exp/%s.exp3.json" % name, dst))
    for name, dst in MOTIONS:
        print("  %-34s → motions/%s" % ("motion/%s.motion3.json" % name, dst))
    skipped = sorted(n for n in names if should_skip(n))
    print("\n== 跳过（VTS 工程文件 / 说明书）==")
    for n in skipped:
        print("  %s" % n)

    if args.dry_run:
        print("\n[dry-run] 没有写任何文件")
        return 0

    # ---------------------------------------------------------------- 落盘
    # 先记下"摸头动作在不在"—— 下面会清空整个目录
    has_pat = all(os.path.exists(os.path.join(DEST_DIR, f)) for f in pat_files())
    if os.path.isdir(DEST_DIR):
        shutil.rmtree(DEST_DIR)
    os.makedirs(os.path.join(DEST_DIR, TEX_DIR))
    os.makedirs(os.path.join(DEST_DIR, "expressions"))
    os.makedirs(os.path.join(DEST_DIR, "motions"))

    with open(os.path.join(DEST_DIR, "dafeiyu.moc3"), "wb") as fh:
        fh.write(read_binary(MOC_SRC))
    for src_name, dst_name in ((PHYSICS_SRC, "dafeiyu.physics3.json"),
                               (CDI3_SRC, "dafeiyu.cdi3.json")):
        with open(os.path.join(DEST_DIR, dst_name), "wb") as fh:
            fh.write(read_binary(src_name))
    for name, dst in EXPRESSIONS:
        with open(os.path.join(DEST_DIR, "expressions", dst), "wb") as fh:
            fh.write(read_binary("exp/%s.exp3.json" % name))
    for name, dst in MOTIONS:
        with open(os.path.join(DEST_DIR, "motions", dst), "wb") as fh:
            fh.write(read_binary("motion/%s.motion3.json" % name))

    from PIL import Image
    import io

    for rel, dst, (_w, _h), (tw, th) in tex_plan:
        with Image.open(io.BytesIO(read_binary(rel))) as im:
            im = im.convert("RGBA")
            if im.size != (tw, th):
                im = im.resize((tw, th), Image.LANCZOS)
            im.save(os.path.join(DEST_DIR, TEX_DIR, dst), "PNG", optimize=True)

    write_json(os.path.join(DEST_DIR, "dafeiyu.model3.json"), build_model3(has_pat))

    print("\n安装完成：%s" % DEST_DIR)
    print("接着跑（顺序不能反 —— 本脚本会重写 model3.json）：")
    print("  python tools/live2d_make_pat.py --model dafeiyu")
    print("  python tools/live2d_dump_parts.py --model dafeiyu")
    print("  node tools/live2d_motion_check.cjs")
    return 0


if __name__ == "__main__":
    sys.exit(main())
