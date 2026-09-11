#!/usr/bin/env python3
"""Live2D 资源下载后处理

1. 剥离 lib/*.js 末尾的 sourceMappingURL —— 对应 .map 未随包提供，
   WebView 会因 404 在控制台报错。
2. 清理示例模型 model3.json 中引用了但未随包提供的字段
   （DisplayInfo / Motion.Sound），否则 pixi-live2d-display 加载时报错。

用法:
    python tools/live2d_postprocess.py --lib <lib目录> [--model <model3.json>]
"""
import argparse
import json
import os
import re

SOURCE_MAP_RE = re.compile(r"\n?//# sourceMappingURL=.*$")


def strip_source_map(lib_dir: str) -> None:
    for name in ("pixi.min.js", "cubism4.min.js"):
        path = os.path.join(lib_dir, name)
        if not os.path.exists(path):
            print(f"  [skip] not found: {name}")
            continue
        with open(path, encoding="utf-8", errors="ignore") as fh:
            src = fh.read()
        out = SOURCE_MAP_RE.sub("", src.rstrip()) + "\n"
        with open(path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(out)
        print(f"  [ok] stripped sourceMappingURL: {name}")


def clean_model(path: str) -> None:
    if not os.path.exists(path):
        print(f"  [skip] not found: {path}")
        return
    with open(path, encoding="utf-8") as fh:
        data = json.load(fh)

    refs = data.get("FileReferences", {})
    removed = []
    if refs.pop("DisplayInfo", None) is not None:
        removed.append("DisplayInfo")
    for motions in refs.get("Motions", {}).values():
        for motion in motions:
            if motion.pop("Sound", None) is not None and "Sound" not in removed:
                removed.append("Sound")

    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(data, fh, ensure_ascii=False, indent=2)
    print(f"  [ok] cleaned model refs: {', '.join(removed) if removed else 'nothing to remove'}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--lib", help="lib 目录")
    ap.add_argument("--model", help="model3.json 路径")
    args = ap.parse_args()

    if args.lib:
        strip_source_map(args.lib)
    if args.model:
        clean_model(args.model)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
