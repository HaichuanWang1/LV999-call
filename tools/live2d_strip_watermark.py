#!/usr/bin/env python3
"""Live2D 模型水印/署名剔除

把图集里「署名水印」图层所占的矩形区域涂成完全透明，模型其它部分不动。

## 区域是怎么定出来的

不是肉眼估的，是用 Cubism Core 解析 `silverwolf.moc3` 得到的：
部位名(cdi3) → 该部位的 drawable → 顶点 UV bbox → 像素矩形。
当前模型对应的两块：

| 部位名(PSD 图层名)      | drawable   | 图层                    | 画序      |
|------------------------|------------|------------------------|-----------|
| `槿絮水印.png`          | ArtMesh35  | texture_00 x17-1016, y17-1463   | 373/375 |
| `夜墨ww黑色.png`        | ArtMesh425 | texture_01 x2192-3170, y1936-2852 | 374/375 |

两块都是 4 顶点/网格贴片贴在画布正中（胸口），画序压在全部角色图层之上，
`parts.opacities` 恒为 1，4 个 motion3.json 里没有任何曲线碰过 Part19/Part20
——也就是说永远可见。

## 为什么直接擦图集是安全的

已逐个 drawable 检查过：这两个矩形区域内**只被水印自己的 UV 引用**，
没有任何角色部件采样到该区域，所以涂透明不会误伤。

（若将来换了模型，需要重新解析 moc3 核对，不要照抄坐标。）

用法:
    python tools/live2d_strip_watermark.py --model-dir app/src/main/assets/live2d/models/silverwolf
    python tools/live2d_strip_watermark.py --model-dir ... --dry-run    # 只报告不写回
    python tools/live2d_strip_watermark.py --model-dir ... --no-backup
"""
import argparse
import os
import shutil
import sys

# (相对 model-dir 的纹理路径, x0, y0, x1, y1, 说明)
WATERMARKS = [
    (
        "silverwolf.4096/texture_00.png",
        17, 17, 1016, 1463,
        "Part19 槿絮水印.png / ArtMesh35 (画序 373)",
    ),
    (
        "silverwolf.4096/texture_01.png",
        2192, 1936, 3170, 2852,
        "Part20 夜墨ww黑色.png / ArtMesh425 (画序 374)",
    ),
]


def strip_one(path: str, box, backup: bool, dry_run: bool) -> int:
    from PIL import Image

    im = Image.open(path).convert("RGBA")
    x0, y0, x1, y1 = box
    if x0 < 0 or y0 < 0 or x1 > im.width or y1 > im.height:
        raise ValueError(f"区域越界: {box} 不在 {im.width}x{im.height} 内")

    clear = Image.new("RGBA", (x1 - x0, y1 - y0), (0, 0, 0, 0))
    before = im.crop(box).getchannel("A").getbbox()
    if before is None:
        return 0                      # 已经是空的，幂等
    if dry_run:
        return (x1 - x0) * (y1 - y0)

    if backup:
        bak = path + ".orig"
        if not os.path.exists(bak):
            shutil.copy2(path, bak)

    im.paste(clear, (x0, y0))
    im.save(path)
    return (x1 - x0) * (y1 - y0)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model-dir", required=True, help="模型目录（含 .moc3 与纹理目录）")
    ap.add_argument("--dry-run", action="store_true", help="只检查不写回")
    ap.add_argument("--no-backup", action="store_true", help="不生成 .orig 备份")
    args = ap.parse_args()

    if not os.path.isdir(args.model_dir):
        print(f"模型目录不存在: {args.model_dir}")
        return 1

    try:
        import PIL  # noqa: F401
    except ImportError:
        print("需要 Pillow: pip install pillow")
        return 1

    print(f"模型目录: {args.model_dir}")
    total = 0
    for rel, x0, y0, x1, y1, note in WATERMARKS:
        path = os.path.join(args.model_dir, rel)
        if not os.path.exists(path):
            print(f"  [skip] 找不到 {rel}")
            continue
        box = (x0, y0, x1, y1)
        cleared = strip_one(path, box, backup=not args.no_backup, dry_run=args.dry_run)
        total += cleared
        flag = "待清理" if args.dry_run else ("已清理" if cleared else "已经是空的")
        print(f"  [ok] {rel} {box} {flag}  {note}")

    if args.dry_run:
        print(f"dry-run：将清理 {total} 像素")
    else:
        print(f"完成，共清理 {total} 像素（原图备份为 *.png.orig）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
