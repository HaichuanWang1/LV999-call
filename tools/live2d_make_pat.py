#!/usr/bin/env python3
"""生成摸头反应动作（PatOnce 组）

为什么要动作文件
----------------
摸头原来只靠 bridge.js 的程序化叠层（CFG.pat.amp）：那几个通道的幅度是
"微表情"级别（tilt 3° / smile 0.2），读起来更像"轻轻歪了下头"，看不出是被摸头。
大姿态必须走动作文件 —— 关键帧 + 缓入缓出 + 尾部过冲，这是参数叠层做不到的。

通道预算（本脚本最要紧的约束）
------------------------------
bridge.js 每帧的写入顺序是 `applyIdle()` → `applyPat()`，两者写的是**同一批通道**
（CFG.idle.channels = brow / browForm / smile / squint / mouthForm / breath /
tilt(ParamAngleZ) / sway(ParamBodyAngleZ)）。所以动作文件写这些通道**必然被覆盖**。

于是摸头动作只能用两层都不碰的通道 —— 与现有 idle_nod / idle_shift 完全同一个预算：

    ParamAngleY（头部俯仰）、ParamBodyAngleX / ParamBodyAngleY（身体前后、左右）

这比"演出期间让待机层整体让位"更好：让位会把程序化层负责的**脸**（脸红/眯眼/眉毛）
一起掐掉，而摸头最需要的恰恰是那张脸。分工保持：**动作管大姿态，程序化层管脸**。

刻意不碰的通道（都会在 validate 里拦下）：
    ParamMouthOpenY —— 口型
    ParamAngleX / ParamEyeBall* —— 视线（角色必须一直看着用户）
    ParamAngleZ / ParamBodyAngleZ —— 程序化待机层每帧写
    物理输出参数 —— 写了会被物理每帧覆盖，看不见

用法
----
    python tools/live2d_make_pat.py                # 两个模型都生成
    python tools/live2d_make_pat.py --model silverwolf
    python tools/live2d_make_pat.py --dry-run      # 只打印
    python tools/live2d_make_pat.py --remove       # 撤掉 PatOnce 组与生成的文件

模型目录被 .gitignore 排除，所以**这个脚本才是改动的事实来源**：
重跑 tools/setup_live2d_assets.sh 或换机器之后，再跑一次本脚本即可复原。
"""

import argparse
import json
import os
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODELS_ROOT = os.path.join(ROOT, "app", "src", "main", "assets", "live2d", "models")
DEFAULT_BACKUP_DIR = os.path.join(ROOT, "app", "build", "live2d-pat-backup")

GROUP_NAME = "PatOnce"

# 口型与视线：动作文件绝不能碰
FORBIDDEN = {"ParamMouthOpenY", "ParamMouthOpen"}
GAZE_OWNED_BY_FOCUS = {"ParamAngleX", "ParamEyeBallX", "ParamEyeBallY"}
# 程序化待机层每帧写入（见文件头"通道预算"）
RESERVED_BY_IDLE_LAYER = {
    "ParamBrowLY", "ParamBrowRY", "ParamBrowLForm", "ParamBrowRForm",
    "ParamEyeLSmile", "ParamEyeRSmile", "ParamEyeLSquint", "ParamEyeRSquint",
    "ParamMouthForm", "ParamBreath", "ParamAngleZ", "ParamBodyAngleZ",
}

# ---------------------------------------------------------------------------
# 档位：连摸第 N 下播哪一套
#
# 符号约定来自模型自带的 idle_nod（"极小的点头"先 -1.5 再 +1.2）与
# idle_stretch（"先沉下去再挺起来"用 -1.8 → +2.4）：
#     ParamAngleY 负 = 低头 / 被按下去，正 = 抬头
#     ParamBodyAngleX 负 = 身体下沉
# 幅度刻意是 idle 动作的 5~9 倍 —— 那几条是"偶发小动作"，摸头要看得出来。
# ---------------------------------------------------------------------------
TIERS = [
    {
        "name": "lv1",
        "duration": 0.90,
        "comment": "轻按一下：头下沉 6.5° 后慢慢回弹（第 1 下）",
        "pitch": [(0.0, 0.0), (0.16, -6.5), (0.50, -4.0), (0.90, 0.0)],
    },
    {
        "name": "lv2",
        "duration": 1.00,
        "comment": "按得更深：下沉 10.5°，回弹带一次小过冲（第 2 下）",
        "pitch": [(0.0, 0.0), (0.14, -10.5), (0.42, -6.0), (0.72, -1.0), (0.88, 0.8), (1.00, 0.0)],
    },
    {
        "name": "lv3",
        "duration": 1.05,
        "comment": "缩脖子：下沉 13.5° 并压住不回弹，身体跟着沉（第 3 下）",
        "pitch": [(0.0, 0.0), (0.18, -13.5), (0.55, -11.0), (0.85, -4.0), (1.05, 0.0)],
        "body_x": [(0.0, 0.0), (0.20, -3.0), (0.60, -2.4), (1.05, 0.0)],
    },
    {
        "name": "lv4",
        "duration": 0.75,
        "comment": "甩开：被按下去后猛地抬头过冲 +2.5°（第 4 下起，不耐烦）",
        "pitch": [(0.0, 0.0), (0.10, -12.0), (0.30, -9.0), (0.48, 2.5), (0.62, -1.0), (0.75, 0.0)],
    },
]

# ---------------------------------------------------------------------------
# 各模型的路径与通道预算
# ---------------------------------------------------------------------------
MODELS = {
    "silverwolf": {
        "dir": os.path.join(MODELS_ROOT, "silverwolf"),
        "model3": "silverwolf.model3.json",
        "physics": "silverwolf.physics3.json",
        "out_dir": "motions",          # 与 idle_*.motion3.json 同目录
        # 该模型的 ParamBodyAngleX/Y 不是物理输出（ParamBodyAngleX2.. 才是）
        "pitch": "ParamAngleY",
        "body_x": "ParamBodyAngleX",
        "body_y": "ParamBodyAngleY",
    },
    # 旧条目「deepseek」已删：它指向 models/deepseek/c_0120.*，
    # 而那个模型（DS鲸鱼娘）已经被 dafeiyu 取代并从 assets 里移除了。
    "dafeiyu": {
        "dir": os.path.join(MODELS_ROOT, "dafeiyu"),
        "model3": "dafeiyu.model3.json",
        "physics": "dafeiyu.physics3.json",
        "out_dir": "motions",
        # ⚠️ ParamBodyAngleX/Y/Z 全是物理输出（权重 100），写进去会被物理每帧覆盖。
        # 该模型另有 ParamAngleX4/Y4/X5/Y5/Z4/Z5 是物理**输入**（cdi3 里叫"物理输入身体 X/XX…"），
        # 理论上能做身体下沉，但没法离线验证观感 —— 先只动头。
        "pitch": "ParamAngleY",
        "body_x": None,
        "body_y": None,
    },
}


def write_json(path, obj):
    text = json.dumps(obj, ensure_ascii=False, indent=2) + "\n"
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


def load_physics(model_dir, physics_name):
    path = os.path.join(model_dir, physics_name)
    if not os.path.exists(path):
        return None, None
    with open(path, encoding="utf-8") as fh:
        data = json.load(fh)
    ins, outs = set(), set()
    for st in data.get("PhysicsSettings", []):
        for i in st.get("Input", []):
            ins.add(i["Source"]["Id"])
        for o in st.get("Output", []):
            outs.add(o["Destination"]["Id"])
    return ins, outs


def build_curve(pid, keys):
    """[(t, v), ...] -> Segments（与 live2d_make_idle.py 同一套格式）

    先裸写起点，之后每段 = 类型 + 该段终点；1 = 贝塞尔（多两个控制点），
    控制点取 1/3、2/3 处，比直线自然。
    """
    assert len(keys) >= 2, pid
    for i in range(1, len(keys)):
        assert keys[i][0] > keys[i - 1][0], f"{pid} 时间必须递增: {keys}"
    segs = [keys[0][0], keys[0][1]]
    nseg, npt = 0, 1
    for i in range(1, len(keys)):
        t0, v0 = keys[i - 1]
        t1, v1 = keys[i]
        d = (t1 - t0) / 3.0
        segs += [1, t0 + d, v0, t0 + 2 * d, v1, t1, v1]
        nseg += 1
        npt += 3
    return segs, nseg, npt


def curves_for(tier, budget):
    """把一档的曲线表展开成 [(pid, keys), ...]（模型缺哪条通道就跳过）"""
    out = []
    if budget.get("pitch") and tier.get("pitch"):
        out.append((budget["pitch"], tier["pitch"]))
    if budget.get("body_x") and tier.get("body_x"):
        out.append((budget["body_x"], tier["body_x"]))
    if budget.get("body_y") and tier.get("body_y"):
        out.append((budget["body_y"], tier["body_y"]))
    return out


def build_motion(tier, budget):
    curves, nseg, npt = [], 0, 0
    for pid, keys in curves_for(tier, budget):
        segs, s, p = build_curve(pid, keys)
        curves.append({"Target": "Parameter", "Id": pid, "Segments": segs})
        nseg += s
        npt += p
    return {
        "Version": 3,
        "Meta": {
            "Duration": tier["duration"],
            "Fps": 60.0,
            "Loop": False,
            "AreBeziersRestricted": True,
            "CurveCount": len(curves),
            "TotalSegmentCount": nseg,
            "TotalPointCount": npt,
            "UserDataCount": 0,
            "TotalUserDataSize": 0,
            # 摸头是"被碰了一下"的即时反应：淡入要短，否则前几帧软绵绵的。
            # 淡出稍长一点，避免回弹结束时"啪"地断掉。
            "FadeInTime": 0.12,
            "FadeOutTime": 0.30,
        },
        "Curves": curves,
    }


def validate(tier, budget, ins, outs):
    problems = []
    for pid, keys in curves_for(tier, budget):
        if pid in FORBIDDEN:
            problems.append(f"{pid} 是口型通道")
        if pid in RESERVED_BY_IDLE_LAYER:
            problems.append(f"{pid} 由程序化待机层每帧写入，写在这里会被覆盖")
        if pid in GAZE_OWNED_BY_FOCUS:
            problems.append(f"{pid} 是视线通道（角色会不看用户）")
        if outs is not None and pid in outs:
            problems.append(f"{pid} 是物理输出，会被物理每帧覆盖")
        for t, v in keys:
            if t < 0 or t > tier["duration"] + 1e-9:
                problems.append(f"{pid} 关键点时间 {t} 超出时长 {tier['duration']}")
            if abs(v) > 30:
                problems.append(f"{pid} 的值 {v} 过大（角度参数范围 ±30）")
    if not curves_for(tier, budget):
        problems.append("这一档没有任何可用通道（检查通道预算）")
    return problems


def file_name(tag, tier):
    return f"pat_{tier['name']}.motion3.json"


def main():
    ap = argparse.ArgumentParser(description="生成摸头反应动作（PatOnce 组）")
    ap.add_argument("--model", choices=sorted(MODELS), help="只处理某个模型")
    ap.add_argument("--backup-dir", default=DEFAULT_BACKUP_DIR)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--remove", action="store_true")
    args = ap.parse_args()

    targets = [args.model] if args.model else sorted(MODELS)
    for tag in targets:
        cfg = MODELS[tag]
        model_dir = cfg["dir"]
        model_json = os.path.join(model_dir, cfg["model3"])
        if not os.path.exists(model_json):
            print(f"[跳过] {tag}：没找到模型 {model_json}\n"
                  f"        模型未随仓库分发，请先跑 tools/setup_live2d_assets.sh")
            continue
        motion_dir = os.path.join(model_dir, cfg["out_dir"]) if cfg["out_dir"] else model_dir
        prefix = "motions/" if cfg["out_dir"] else ""

        print(f"===== {tag}（{len(TIERS)} 档）")
        if args.remove:
            with open(model_json, encoding="utf-8") as fh:
                data = json.load(fh)
            motions = data.get("FileReferences", {}).get("Motions", {})
            print(f"  移除动作组 {GROUP_NAME}: {'有' if motions.pop(GROUP_NAME, None) else '本来就没有'}")
            if not args.dry_run:
                write_json(model_json, data)
                for tier in TIERS:
                    p = os.path.join(motion_dir, file_name(tag, tier))
                    if os.path.exists(p):
                        os.remove(p)
                        print(f"  删除 {p}")
            continue

        ins, outs = load_physics(model_dir, cfg["physics"])
        if ins is None:
            print("[提示] 没有 physics3.json，跳过物理覆盖校验")

        bad = False
        for tier in TIERS:
            problems = validate(tier, cfg, ins, outs)
            print(f"  {file_name(tag, tier)}  ({tier['duration']}s) — {tier['comment']}")
            for pid, _ in curves_for(tier, cfg):
                role = "物理输出 [禁止]" if (outs and pid in outs) else (
                    "物理输入 [可驱动]" if (ins and pid in ins) else "空闲参数 [可驱动]")
                print(f"      {pid:<18} {role}")
            for p in problems:
                print(f"      [问题] {p}")
                bad = True
        if bad:
            sys.exit("有校验问题，未写入任何文件")

        with open(model_json, encoding="utf-8") as fh:
            data = json.load(fh)
        refs = data.setdefault("FileReferences", {})
        motions = refs.setdefault("Motions", {})
        motions[GROUP_NAME] = [{"File": f"{prefix}{file_name(tag, t)}"} for t in TIERS]
        # 生成的组放最前，便于人工核对（库按名字查找，顺序无功能影响）
        refs["Motions"] = {GROUP_NAME: motions.pop(GROUP_NAME), **motions}

        if args.dry_run:
            for tier in TIERS:
                print(f"  [dry-run] 将写入 {os.path.join(motion_dir, file_name(tag, tier))}")
            print(f"  [dry-run] 将注册 {GROUP_NAME} 组 -> {model_json}")
            continue

        os.makedirs(motion_dir, exist_ok=True)
        for tier in TIERS:
            path = os.path.join(motion_dir, file_name(tag, tier))
            write_json(path, build_motion(tier, cfg))
            print(f"  [写入] {path}")
        write_json(model_json, data)
        print(f"  [写入] {model_json}")

    if not args.dry_run:
        print("\n完成。校验：node tools/live2d_motion_check.cjs")
    return 0


if __name__ == "__main__":
    sys.exit(main())
