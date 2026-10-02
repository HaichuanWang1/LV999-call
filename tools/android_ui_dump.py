"""把 `uiautomator dump` 的 XML 解析成「文字 + 中心坐标」，用于真机/模拟器导航。

用法：
    adb shell uiautomator dump /sdcard/ui.xml
    adb pull /sdcard/ui.xml .dsh/ui.xml
    python -X utf8 tools/android_ui_dump.py .dsh/ui.xml
    # 然后按列出的中心坐标点击：
    adb shell input tap <x> <y>

为什么要有这个脚本
------------------
`uiautomator` 的 XML 是一行超长属性串，直接看没法用；而把正则塞进
PowerShell 双引号里会被 `[` 之类字符搞成 ParserError（`Missing type name
after '['`），整条命令连**前面的**步骤都不会执行 —— 排查时很容易误判成
"点了没反应"。落成文件再交给 Python 最稳。

点击坐标必须取**中心**：`uiautomator` 给的是 bounds 矩形，直接点左上角
会落在元素边缘甚至外面。实测踩过一次：通话按钮的 bounds 是 360x360，
按 y 坐标估错 300px 就完全点不到，而截图看起来"什么都没发生"。

只读工具，不写任何东西。
"""
import re
import sys

if len(sys.argv) < 2:
    print(__doc__)
    sys.exit(2)

path = sys.argv[1]
with open(path, encoding="utf-8", errors="replace") as fh:
    xml = fh.read()

# 节点属性顺序不固定，宽松地逐个抓 key="value"
NODE_RE = re.compile(r"<node\b[^>]*>")
ATTR_RE = re.compile(r'([\w-]+)="([^"]*)"')
BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")

rows = []
for node in NODE_RE.finditer(xml):
    attrs = dict(ATTR_RE.findall(node.group(0)))
    m = BOUNDS_RE.match(attrs.get("bounds", ""))
    if not m:
        continue
    x0, y0, x1, y1 = (int(v) for v in m.groups())
    label = (attrs.get("text") or attrs.get("content-desc") or "").strip()
    clickable = attrs.get("clickable") == "true"
    # 既没文字又不可点的节点对导航没用，跳过（否则一堆容器节点刷屏）
    if not label and not clickable:
        continue
    rows.append((label, clickable, (x0 + x1) // 2, (y0 + y1) // 2, x1 - x0, y1 - y0))

print(f"{'文字/描述':<36}{'可点':<6}{'中心坐标':<16}尺寸")
for label, clickable, cx, cy, w, h in rows:
    left = f"{label[:36]:<36}{'是' if clickable else '否':<6}({cx},{cy})"
    print(f"{left:<58}{w}x{h}")
