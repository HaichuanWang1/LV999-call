#!/usr/bin/env python3
"""从模型的 cdi3.json 里挑出「头部部件」，生成 bridge.js 的 pat.headParts 列表

为什么需要它
------------
摸头命中判定不再用手调矩形，而是取**头部部件**下所有 drawable 的顶点包围盒
（见 bridge.js 的 patHeadBox）。部件 id 与显示名的对应关系只在 cdi3.json
（Cubism Display Info）里，运行时拿不到（Core 只给 "Part127" 这种 id），
所以这一步放在离线：脚本选出 id，人眼核对名字，再写进 profile。

为什么按名字挑而不是写死 id
--------------------------
换模型、换版本、重导出都会让 id 变；名字（脸 / 五官 / 前发 / 头发…）是人给的、
稳定的。所以"事实来源"是这份脚本 + 它的关键词表，而不是 bridge.js 里那串 id。

用法
----
    python tools/live2d_dump_parts.py                 # 两个模型都打
    python tools/live2d_dump_parts.py --model silverwolf
    python tools/live2d_dump_parts.py --json          # 只输出可粘贴的片段
"""

import argparse
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODELS = {
    "silverwolf": os.path.join(
        ROOT, "app", "src", "main", "assets", "live2d", "models", "silverwolf",
        "silverwolf.cdi3.json",
    ),
    "deepseek": os.path.join(
        ROOT, "app", "src", "main", "assets", "live2d", "models", "deepseek",
        "c_0120.cdi3.json",
    ),
}

# 「贴着头的部件」：脸 / 五官 / 头饰 / 眉 / 眼 / 嘴 / 耳 / 角 …
#
# ⚠️ 刻意**不含头发**，见 HAIR 那组的说明。
INCLUDE = (
    "头", "脸", "五官", "眼", "眉", "嘴", "耳", "角", "帽",
    "Head", "Face", "Eye", "Ear", "Brow", "Mouth",
)

# 「头发」单独一组，默认不进 headParts。
#
# 为什么：实测这两个模型的头发都会**一路垂到身体**，混进命中盒就不是"摸头"了 ——
#   银狼    后发  1673×1686 px（一直垂到脚）
#   DeepSeek 酱 头发  2378×2790 px（几乎整个模型）
# 于是"点她大腿也算摸头"，连摸四下还会让她生气。
# 头（脸 + 头饰）本身已经足够大，外扩 8% 就够容错，不需要靠头发凑面积。
# 真要带上（比如某个模型的头发只贴着头），用 --with-hair，并真机看一眼红框。
HAIR = ("发", "呆毛", "刘海", "Hair")

# 排除关键词：看着像头、其实不是（脖子在头下面；眼镜/贴纸/水印是贴片；
# 切换/预设是编辑器用的隐藏辅助部件；手/身/腿/尾/桌/包 属于身体或道具）
EXCLUDE = (
    "脖", "眼镜", "镜", "贴纸", "水印", "切换", "预设", "手", "身", "腿", "脚",
    "尾", "桌", "包", "卡", "特效", "背景", "Neck", "Glass", "Sticker",
)


def pick(path, with_hair=False):
    """返回 (全部部件, 选中的, 被排除的, 头发组)"""
    with open(path, encoding="utf-8") as fh:
        data = json.load(fh)
    parts = data.get("Parts") or []
    chosen, skipped, hair = [], [], []
    for part in parts:
        name = str(part.get("Name", ""))
        pid = str(part.get("Id", ""))
        if not name or not pid:
            continue
        if any(k in name for k in EXCLUDE):
            if any(k in name for k in INCLUDE + HAIR):
                skipped.append((pid, name))
            continue
        is_hair = any(k in name for k in HAIR)
        if is_hair:
            hair.append((pid, name))
            if with_hair:
                chosen.append((pid, name))
            continue
        if any(k in name for k in INCLUDE):
            chosen.append((pid, name))
    return parts, chosen, skipped, hair


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", choices=sorted(MODELS), help="只处理某个模型")
    ap.add_argument("--json", action="store_true", help="只输出可粘贴的片段")
    ap.add_argument("--with-hair", action="store_true",
                    help="把头发也并进 headParts（默认不要，见 HAIR 的说明）")
    args = ap.parse_args()

    targets = [args.model] if args.model else sorted(MODELS)
    for tag in targets:
        path = MODELS[tag]
        if not os.path.exists(path):
            print(f"[skip] {tag}: 找不到 {path}", file=sys.stderr)
            continue
        parts, chosen, skipped, hair = pick(path, args.with_hair)
        if args.json:
            ids = ", ".join("'%s'" % p for p, _ in chosen)
            print(f"        headParts: [{ids}],")
            continue
        print(f"===== {tag}（部件总数 {len(parts)}，选中 {len(chosen)}）")
        for pid, name in chosen:
            print(f"    + {pid:<10} {name}")
        if hair:
            print(f"    ~ 头发组（**默认不选**：可能一直垂到身体，混进来就不是摸头了）：")
            for pid, name in hair:
                print(f"      {pid:<10} {name}")
        if skipped:
            print(f"    - 被排除（名字像头但属于贴片/辅助件）：")
            for pid, name in skipped:
                print(f"      {pid:<10} {name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
