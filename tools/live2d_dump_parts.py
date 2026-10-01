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

# 命中关键词：名字里带这些字的部件都属于"头"这一片
INCLUDE = (
    "头", "脸", "五官", "发", "眼", "眉", "嘴", "耳", "角", "帽", "呆毛", "刘海",
    "Head", "Face", "Hair", "Eye", "Ear", "Brow", "Mouth",
)

# 排除关键词：看着像头、其实不是（脖子在头下面；眼镜/贴纸/水印是贴片；
# 切换/预设是编辑器用的隐藏辅助部件；手/身/腿/尾/桌/包 属于身体或道具）
EXCLUDE = (
    "脖", "眼镜", "镜", "贴纸", "水印", "切换", "预设", "手", "身", "腿", "脚",
    "尾", "桌", "包", "卡", "特效", "背景", "Neck", "Glass", "Sticker",
)


def pick(path):
    with open(path, encoding="utf-8") as fh:
        data = json.load(fh)
    parts = data.get("Parts") or []
    chosen, skipped = [], []
    for part in parts:
        name = str(part.get("Name", ""))
        pid = str(part.get("Id", ""))
        if not name or not pid:
            continue
        if any(k in name for k in EXCLUDE):
            if any(k in name for k in INCLUDE):
                skipped.append((pid, name))
            continue
        if any(k in name for k in INCLUDE):
            chosen.append((pid, name))
    return parts, chosen, skipped


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", choices=sorted(MODELS), help="只处理某个模型")
    ap.add_argument("--json", action="store_true", help="只输出可粘贴的片段")
    args = ap.parse_args()

    targets = [args.model] if args.model else sorted(MODELS)
    for tag in targets:
        path = MODELS[tag]
        if not os.path.exists(path):
            print(f"[skip] {tag}: 找不到 {path}", file=sys.stderr)
            continue
        parts, chosen, skipped = pick(path)
        if args.json:
            ids = ", ".join("'%s'" % p for p, _ in chosen)
            print(f"        headParts: [{ids}],")
            continue
        print(f"===== {tag}（部件总数 {len(parts)}，选中 {len(chosen)}）")
        for pid, name in chosen:
            print(f"    + {pid:<10} {name}")
        if skipped:
            print(f"    - 被排除（名字像头但属于贴片/辅助件）：")
            for pid, name in skipped:
                print(f"      {pid:<10} {name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
