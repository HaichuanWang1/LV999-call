#!/usr/bin/env python3
"""生成银狼模型的待机动作（Idle 组）

为什么需要它
------------
模型自带的 4 条动作全是「变身 / 生气 / 睡觉」特效循环，且**没有 Idle 组**，
而 pixi-live2d-display 的自动待机写死找 `Idle` 这个名字，找不到就一条都不播。
结果角色除了呼吸、眨眼、视线之外全程静止。

bridge.js 里的程序化待机层（CFG.idle）负责"一直在线"的微表情，
本脚本负责"偶发一下"的大姿态（转头张望、换重心），两者分工靠**通道不重叠**：

    程序化层（bridge.js）  : 眉毛 / 眼形 / 嘴形 / 呼吸 / 头侧倾 ParamAngleZ / 身体 ParamBodyAngleZ
    本脚本生成的动作       : 头 yaw·pitch / 腰转 / 身体前后 / 眼球 ParamAngle{X,Y}、ParamBodyAngle{X,Y}、ParamEyeBall{X,Y}

为什么不循环（`Loop: false`）
---------------------------
运行库的 Idle 机制是「一条播完立刻随机抽下一条」，
循环动作会让它永远停在同一条上；非循环 + 末尾留一段静止（hold），
才能得到"偶发做个小动作、其余时间安静待机"的节奏。

用法
----
    python tools/live2d_make_idle.py              # 生成并注册进 model3.json
    python tools/live2d_make_idle.py --dry-run    # 只打印将要做的事
    python tools/live2d_make_idle.py --remove     # 撤掉 Idle 组与生成的文件
    python tools/live2d_make_idle.py --model DIR  # 指定模型目录

模型目录被 .gitignore 排除，所以**这个脚本才是改动的事实来源**：
重新跑 `tools/setup_live2d_assets.sh` 或换机器之后，重跑一次即可复原。
"""

import argparse
import json
import os
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_MODEL_DIR = os.path.join(
    ROOT, "app", "src", "main", "assets", "live2d", "models", "silverwolf"
)
# 备份必须放在 assets 之外：assets 下任何文件都会被 Gradle 打进 APK
# （水印那次就是把 *.orig 放进了 assets，结果带水印的原图被一起分发出去）
DEFAULT_BACKUP_DIR = os.path.join(ROOT, "app", "build", "live2d-idle-backup")

GROUP_NAME = "Idle"

# ---------------------------------------------------------------------------
# 变身过场
#
# 作者原文件 m_transform_1/2 是同一段演出的前后两半（_1 = 摘下眼镜+变身开+
# 划卡特效 0→10，_2 = 戴回眼镜+变身关+特效 10→20），但**都是 Loop: true**，
# 直接播会一直循环 —— 所以生成两份"只改 Loop"的一次性副本，注册成 TransformOnce 组。
#
# 刻意不动其它任何数据（包括作者写越界的 Param172 10→20）：原文件是作者的资产，
# 抄一份改行为可以，改数据不行。生成时会断言"除 Loop 外逐字节相同"。
# ---------------------------------------------------------------------------
TRANSFORM_GROUP = "TransformOnce"
TRANSFORM_COPIES = [
    {
        "src": "m_transform_1.motion3.json",
        "dst": "transform_in.motion3.json",
        "comment": "变身·进入（摘眼镜、变身开、划卡特效 0→10）",
    },
    {
        "src": "m_transform_2.motion3.json",
        "dst": "transform_out.motion3.json",
        "comment": "变身·还原（戴回眼镜、变身关、划卡特效 10→20，作者原值越界会被 clamp）",
    },
]

# 这些参数由 bridge.js 的程序化待机层每帧写入（见 CFG.idle.channels）。
# 动作文件再写一遍会被覆盖（那层写在 afterMotionUpdate，晚于动作更新），
# 所以这里明确禁止重叠 —— 排查"动作播了但没反应"时这是第一嫌疑人。
RESERVED_BY_IDLE_LAYER = {
    "ParamBrowLY", "ParamBrowRY", "ParamBrowLForm", "ParamBrowRForm",
    "ParamEyeLSmile", "ParamEyeRSmile", "ParamEyeLSquint", "ParamEyeRSquint",
    "ParamMouthForm", "ParamBreath", "ParamAngleZ", "ParamBodyAngleZ",
}

# ---------------------------------------------------------------------------
# 待机动作定义
#
# 每个动作 = 一串曲线；每条曲线 = [(时间, 值), ...]（秒）。
# 关键点之间用三次贝塞尔做缓入缓出（控制点取 1/3、2/3 处），比直线自然。
#
# 幅度刻意压得很小：这些参数会和运行库的呼吸（对头角做 ±4~7.5° 的加性写入）
# 以及 bridge.js 的视线跟随叠在一起。
# ---------------------------------------------------------------------------
MOTIONS = [
    {
        "file": "idle_glance.motion3.json",
        "duration": 4.5,
        "comment": "快速瞥一眼旁边（短促，像听到什么动静）",
        "curves": [
            ("ParamAngleX",   [(0.0, 0.0), (0.45, -9.0), (1.7, -9.0), (2.3, 0.0), (4.5, 0.0)]),
            ("ParamAngleY",   [(0.0, 0.0), (0.45, -1.5), (1.7, -1.5), (2.3, 0.0), (4.5, 0.0)]),
            ("ParamEyeBallX", [(0.0, 0.0), (0.22, -0.55), (1.7, -0.5), (2.3, 0.0), (4.5, 0.0)]),
            ("ParamEyeBallY", [(0.0, 0.0), (0.22, 0.10), (1.7, 0.06), (2.3, 0.0), (4.5, 0.0)]),
            ("ParamBodyAngleY", [(0.0, 0.0), (0.5, -2.2), (1.7, -2.0), (2.4, 0.0), (4.5, 0.0)]),
        ],
    },
    {
        "file": "idle_lookaround.motion3.json",
        "duration": 7.5,
        "comment": "慢慢左右张望一圈",
        "curves": [
            ("ParamAngleX",   [(0.0, 0.0), (1.2, -6.0), (2.4, -6.0), (4.2, 7.0), (5.4, 7.0), (6.6, 0.0), (7.5, 0.0)]),
            ("ParamAngleY",   [(0.0, 0.0), (1.2, -2.0), (2.4, -2.0), (4.2, 2.0), (5.4, 2.0), (6.6, 0.0), (7.5, 0.0)]),
            ("ParamEyeBallX", [(0.0, 0.0), (1.0, -0.45), (2.4, -0.45), (4.0, 0.5), (5.4, 0.5), (6.6, 0.0), (7.5, 0.0)]),
            ("ParamEyeBallY", [(0.0, 0.0), (1.2, 0.12), (4.2, -0.10), (7.5, 0.0)]),
            ("ParamBodyAngleY", [(0.0, 0.0), (1.3, -3.0), (2.4, -3.0), (4.3, 3.4), (5.4, 3.4), (6.7, 0.0), (7.5, 0.0)]),
        ],
    },
    {
        "file": "idle_stretch.motion3.json",
        "duration": 8.0,
        "comment": "换重心松一下身子（先沉下去再挺起来）",
        "curves": [
            ("ParamBodyAngleX", [(0.0, 0.0), (1.4, -1.6), (2.6, -1.6), (4.0, 2.2), (5.0, 2.2), (6.6, 0.0), (8.0, 0.0)]),
            ("ParamAngleY",     [(0.0, 0.0), (1.4, -3.5), (2.6, -3.5), (4.0, 3.0), (5.0, 3.0), (6.6, 0.0), (8.0, 0.0)]),
            ("ParamAngleX",     [(0.0, 0.0), (2.0, -2.0), (4.5, 2.5), (6.6, 0.0), (8.0, 0.0)]),
            ("ParamEyeBallY",   [(0.0, 0.0), (1.4, -0.28), (4.0, 0.30), (6.6, 0.0), (8.0, 0.0)]),
            ("ParamBodyAngleY", [(0.0, 0.0), (2.2, -1.6), (4.6, 1.8), (6.8, 0.0), (8.0, 0.0)]),
        ],
    },
]

# 这些参数由 bridge.js 的程序化待机层每帧写入（见 CFG.idle.channels）。
# 动作文件再写一遍会被覆盖（那层写在 afterMotionUpdate，晚于动作更新），
# 所以这里明确禁止重叠 —— 排查"动作播了但没反应"时这是第一嫌疑人。
RESERVED_BY_IDLE_LAYER = {
    "ParamBrowLY", "ParamBrowRY", "ParamBrowLForm", "ParamBrowRForm",
    "ParamEyeLSmile", "ParamEyeRSmile", "ParamEyeLSquint", "ParamEyeRSquint",
    "ParamMouthForm", "ParamBreath", "ParamAngleZ", "ParamBodyAngleZ",
}

# 口型与情绪表情的通道，动作文件绝不能碰
FORBIDDEN = {"ParamMouthOpenY", "ParamMouthOpen"}


# ---------------------------------------------------------------------------
# 曲线构造
# ---------------------------------------------------------------------------


def build_curve(pid, keys, ease=True):
    """把 [(t, v), ...] 变成 moc3 的 Segments 数组

    格式（已在自带动作文件上验证）：
        先裸写起点 (x0, y0)，之后每段 = 类型 + 该段终点（贝塞尔多两个控制点）
        0 = 直线（1 点）  1 = 贝塞尔（3 点：c1、c2、终点）  2 = 阶梯（1 点）

    返回 (Segments, 段数, 点数)
    """
    assert len(keys) >= 2, pid
    for i in range(1, len(keys)):
        assert keys[i][0] > keys[i - 1][0], f"{pid} 时间必须递增: {keys}"
    segs = [keys[0][0], keys[0][1]]
    nseg = 0
    npt = 1
    for i in range(1, len(keys)):
        t0, v0 = keys[i - 1]
        t1, v1 = keys[i]
        if ease:
            d = (t1 - t0) / 3.0
            segs += [1, t0 + d, v0, t0 + 2 * d, v1, t1, v1]
            nseg += 1
            npt += 3
        else:
            segs += [0, t1, v1]
            nseg += 1
            npt += 1
    return segs, nseg, npt


def build_motion(spec):
    curves, nseg, npt = [], 0, 0
    for pid, keys in spec["curves"]:
        segs, s, p = build_curve(pid, keys)
        curves.append({"Target": "Parameter", "Id": pid, "Segments": segs})
        nseg += s
        npt += p
    return {
        "Version": 3,
        "Meta": {
            "Duration": spec["duration"],
            "Fps": 60.0,
            # 非循环：播完由运行库随机抽下一条（见文件头说明）
            "Loop": False,
            "AreBeziersRestricted": True,
            "CurveCount": len(curves),
            "TotalSegmentCount": nseg,
            "TotalPointCount": npt,
            "UserDataCount": 0,
            "TotalUserDataSize": 0,
            # 文件里的淡入淡出单位是秒；Idle 组默认按 idleMotionFadingDuration 处理，
            # 这里显式给短一点，免得动作之间串味
            "FadeInTime": 0.7,
            "FadeOutTime": 0.7,
        },
        "Curves": curves,
    }


# ---------------------------------------------------------------------------
# 校验
# ---------------------------------------------------------------------------


def load_physics(model_dir):
    """返回 (输入集合, 输出集合)，模型缺失时返回 (None, None)"""
    path = os.path.join(model_dir, "silverwolf.physics3.json")
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


def validate(spec, ins, outs):
    """返回问题列表（空 = 通过）"""
    problems = []
    for pid, keys in spec["curves"]:
        if pid in FORBIDDEN:
            problems.append(f"{pid} 是口型通道，动作文件不能写")
        if pid in RESERVED_BY_IDLE_LAYER:
            problems.append(f"{pid} 由程序化待机层每帧写入，写在这里会被覆盖")
        if outs is not None and pid in outs:
            problems.append(f"{pid} 是物理输出，会被物理每帧覆盖（写了看不见）")
        for t, v in keys:
            if t < 0 or t > spec["duration"] + 1e-9:
                problems.append(f"{pid} 关键点时间 {t} 超出时长 {spec['duration']}")
            if abs(v) > 30:
                problems.append(f"{pid} 的值 {v} 过大（多数参数范围是 ±1，角度是 ±30）")
    return problems


def report_curve_roles(spec, ins, outs):
    rows = []
    for pid, keys in spec["curves"]:
        if outs is not None and pid in outs:
            role = "物理输出 [禁止]"
        elif ins is not None and pid in ins:
            role = "物理输入 [可驱动]"
        elif ins is None:
            role = "未校验（本地无模型）"
        else:
            role = "空闲参数 [可驱动]"
        rows.append(f"    {pid:<18} {role}")
    return rows


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------


def guard_backup_dir(path):
    """备份目录绝不允许落在 assets 下（会被打进 APK 一起分发）"""
    norm = os.path.normpath(path).replace("\\", "/").lower()
    if "/assets/" in norm + "/" or norm.endswith("/assets"):
        sys.exit(f"[拒绝] 备份目录不能位于 assets 内：{path}")
    return path


def backup(path, backup_dir):
    os.makedirs(backup_dir, exist_ok=True)
    dst = os.path.join(backup_dir, os.path.basename(path))
    if os.path.exists(path) and not os.path.exists(dst):
        shutil.copy2(path, dst)
        print(f"  [备份] {path} -> {dst}")


def build_transform_copies(motion_dir, dry_run, quiet=False):
    """生成"只改 Loop"的一次性副本

    返回 (动作组条目, 问题列表)。刻意不动作者的任何数据 ——
    包括写得越界的 Param172（那是原文件的问题，抄一份改行为可以，改数据不行），
    所以这里断言"除 Meta.Loop 外逐字段相同"。
    """
    entries, problems = [], []
    for spec in TRANSFORM_COPIES:
        src_path = os.path.join(motion_dir, spec["src"])
        if not os.path.exists(src_path):
            problems.append(f"找不到源文件 {src_path}")
            continue
        with open(src_path, encoding="utf-8") as fh:
            src = json.load(fh)
        if src.get("Meta", {}).get("Loop") is not True:
            problems.append(f"{spec['src']} 的 Meta.Loop 不是 true（可能已经是副本）")
        dst = json.loads(json.dumps(src))
        dst["Meta"]["Loop"] = False
        # 反向还原一次，确认只动了 Loop 这一个字段
        check = json.loads(json.dumps(dst))
        check["Meta"]["Loop"] = True
        if check != src:
            problems.append(f"{spec['dst']}: 除 Loop 外还有其它改动，拒绝写入")
            continue
        if not dry_run:
            write_json(os.path.join(motion_dir, spec["dst"]), dst)
        entries.append({"File": f"motions/{spec['dst']}"})
        if not quiet:
            print(f"  {spec['dst']}  <-  {spec['src']}  (Loop: true -> false)  {spec['comment']}")
    if problems:
        return None, problems
    return entries, []


def main():
    ap = argparse.ArgumentParser(description="生成待机动作（Idle 组）与变身过场副本")
    ap.add_argument("--model", default=DEFAULT_MODEL_DIR, help="模型目录")
    ap.add_argument("--backup-dir", default=DEFAULT_BACKUP_DIR)
    ap.add_argument("--dry-run", action="store_true", help="只打印，不写文件")
    ap.add_argument("--remove", action="store_true", help="撤掉生成的组与动作文件")
    args = ap.parse_args()

    model_dir = os.path.abspath(args.model)
    model_json = os.path.join(model_dir, "silverwolf.model3.json")
    motion_dir = os.path.join(model_dir, "motions")
    if not os.path.exists(model_json):
        sys.exit(f"[跳过] 没找到模型：{model_json}\n"
                 f"        模型未随仓库分发，请先跑 tools/setup_live2d_assets.sh 并放入自己的模型")

    ins, outs = load_physics(model_dir)
    if ins is None:
        print("[提示] 没有 physics3.json，跳过物理覆盖校验")

    if args.remove:
        with open(model_json, encoding="utf-8") as fh:
            data = json.load(fh)
        motions = data.get("FileReferences", {}).get("Motions", {})
        for name in (GROUP_NAME, TRANSFORM_GROUP):
            print(f"  移除动作组 {name}: {'有' if motions.pop(name, None) else '本来就没有'}")
        if not args.dry_run:
            backup(model_json, guard_backup_dir(args.backup_dir))
            write_json(model_json, data)
            for spec in MOTIONS + TRANSFORM_COPIES:
                p = os.path.join(motion_dir, spec["file"] if "file" in spec else spec["dst"])
                if os.path.exists(p):
                    os.remove(p)
                    print(f"  删除 {p}")
        return 0

    print("== 生成待机动作 ==")
    for spec in MOTIONS:
        problems = validate(spec, ins, outs)
        print(f"  {spec['file']}  ({spec['duration']}s) — {spec['comment']}")
        for row in report_curve_roles(spec, ins, outs):
            print(row)
        if problems:
            for p in problems:
                print(f"    [问题] {p}")
            sys.exit("有校验问题，未写入任何文件")

    print(f"\n== 生成变身过场（{TRANSFORM_GROUP} 组）==")
    # 先静默跑一遍做校验，确认没问题再动任何文件
    entries, problems = build_transform_copies(motion_dir, True, quiet=True)
    if problems:
        for p in problems:
            print(f"    [问题] {p}")
        sys.exit("有校验问题，未写入任何文件")

    with open(model_json, encoding="utf-8") as fh:
        data = json.load(fh)
    refs = data.setdefault("FileReferences", {})
    motions = refs.setdefault("Motions", {})
    motions[GROUP_NAME] = [{"File": f"motions/{s['file']}"} for s in MOTIONS]
    motions[TRANSFORM_GROUP] = entries
    # 组顺序：我们生成的放最前，便于人工核对（库按名字查找，顺序无功能影响）
    refs["Motions"] = {GROUP_NAME: motions.pop(GROUP_NAME),
                       TRANSFORM_GROUP: motions.pop(TRANSFORM_GROUP), **motions}

    if args.dry_run:
        print("\n[dry-run] 将写入：")
        for spec in MOTIONS:
            print(f"  {os.path.join(motion_dir, spec['file'])}")
        for spec in TRANSFORM_COPIES:
            print(f"  {os.path.join(motion_dir, spec['dst'])}")
        print(f"  注册 {GROUP_NAME} / {TRANSFORM_GROUP} 组 -> {model_json}")
        print(f"  备份目录 {guard_backup_dir(args.backup_dir)}")
        return 0

    backup(model_json, guard_backup_dir(args.backup_dir))
    os.makedirs(motion_dir, exist_ok=True)
    for spec in MOTIONS:
        path = os.path.join(motion_dir, spec["file"])
        write_json(path, build_motion(spec))
        print(f"  [写入] {path}")
    build_transform_copies(motion_dir, False)

    write_json(model_json, data)
    print(f"  [写入] {model_json}")
    print(f"\n完成：{len(MOTIONS)} 条待机动作 -> {GROUP_NAME} 组，"
          f"{len(TRANSFORM_COPIES)} 条变身副本 -> {TRANSFORM_GROUP} 组。")
    print("校验：node tools/live2d_motion_check.cjs")
    return 0


def write_json(path, obj):
    """统一写成 LF + 2 空格缩进 + 结尾换行（与仓库内其他 JSON 一致）"""
    text = json.dumps(obj, ensure_ascii=False, indent=2) + "\n"
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


if __name__ == "__main__":
    sys.exit(main())
