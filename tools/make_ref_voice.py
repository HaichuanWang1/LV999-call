#!/usr/bin/env python3
"""从游戏语音里剪一段「克隆参考音频」，产出 22.05kHz / 单声道 / 16bit 的 wav

为什么需要它
------------
`TtsPolicy.CloneVoice` 要求每个角色自带一段参考音频（银狼是
`assets/silverwolf/ref_voice.wav`）。流萤只有整轨剧情语音（`sourse/流萤/`，
1149 条），没有现成的「一句自我介绍」可以直接用，所以这段要剪。

为什么要自己剪而不是随便挑一条
------------------------------
参考音频每轮都要随 TTS 请求上传（见 `docs/tts.md`），所以：
- **不能太长**：银狼那份是 15 秒 / 661KB / base64 约 861KB。按这个量级走，
  再长就是每句话都在多传几百 KB。
- **不能用剧情台词**：参考音频决定的是**音色**，但语气也会被带过去。挑「兵器」
  「残骸」「熄灭」这种悲情台词，克隆出来的音色会偏沉。挑日常闲聊的语气，
  出来的才像朋友。
- **开头结尾不能是半句**：`.lab` 只有整条转写、没有时间对齐，所以按**能量**
  （RMS）切句，而不是按字数估算 —— 见 `speech_runs()`。

怎么切
------
1. `--list` 看有哪些候选（转写 + 时长 + 自动切出的句子）。
2. 按 RMS 门限找出「有声段」（合并短停顿、丢掉过短碎片、两端留 50ms 余量）。
   门限只用来**找切点**，不用来重排音频。
3. 从选中的文件里往 target 时长上凑，凑到哪句就在**那句的结尾**切一刀。
   同一个文件里连着的句子**整段取走**，原始停顿原样保留 —— 按句切开再垫
   统一静音会拼出「一顿一顿」的节奏，克隆出来的韵律也跟着走样（试过，
   18 段拼出来 17.4 秒，比原句还散）。
4. 每个区间 30ms 淡入淡出 → 不同文件之间垫 120ms 静音 → 峰值归一化到 −3dBFS。

用法
----
    python tools/make_ref_voice.py --list              # 只列候选，不写文件
    python tools/make_ref_voice.py                     # 按默认选段生成
    python tools/make_ref_voice.py --pick 1,7,8 --target 15
    python tools/make_ref_voice.py --out /tmp/x.wav    # 换输出位置试听

输出
----
    app/src/main/assets/firefly/ref_voice.wav
"""

import argparse
import base64
import os
import sys
import wave

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_DIR = os.path.join(ROOT, "sourse", "流萤")
OUT_DEFAULT = os.path.join(
    ROOT, "app", "src", "main", "assets", "firefly", "ref_voice.wav"
)

# MiMo 的参考音频走 base64 塞进请求体，硬上限 10MB（base64 之后）。
# 这里留足余量：真正卡住我们的从来不是上限，而是每轮多传的那几百 KB。
MAX_B64 = 10 * 1024 * 1024

# 默认选段。挑的都是**日常闲聊**语气，不是剧情独白：
#   1  「嗨，又见面啦…很高兴见到你。和往常一样，叫我「流萤」吧。」—— 打招呼，
#       最贴近「她接起电话」的状态，也天然带出「流萤」这个自称。
#   8  「野草，浆果，清风，蝴蝶…偶尔放放风的时候，我喜欢去植物繁茂的郊外…」
#       —— 语速慢、句子长、情绪平稳，补一段和打招呼不同的韵律。
DEFAULT_PICK = "1,8"


# ---------------------------------------------------------------- 读写


def read_wav_mono(path):
    """读成 float32 单声道（-1.0 ~ 1.0）。只支持 16bit PCM —— 源文件全是。"""
    with wave.open(path, "rb") as w:
        ch, sw, sr, n = w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()
        raw = w.readframes(n)
    if sw != 2:
        raise SystemExit(f"{os.path.basename(path)}: 只支持 16bit PCM，实际 {sw * 8}bit")
    x = np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768.0
    if ch > 1:
        x = x.reshape(-1, ch).mean(axis=1)
    return x, sr


def write_wav(path, x, sr):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    pcm = np.round(np.clip(x, -1.0, 1.0) * 32767.0).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm.tobytes())


def read_lab(path):
    """`.lab` 是整条转写。实测有 UTF-8 与 GBK 两种，先试 UTF-8。"""
    if not os.path.exists(path):
        return ""
    raw = open(path, "rb").read()
    try:
        return raw.decode("utf-8").strip()
    except UnicodeDecodeError:
        return raw.decode("gbk", "replace").strip()


# ---------------------------------------------------------------- 重采样


def _lowpass(x, sr, cutoff, taps=101):
    """窗函数 sinc 低通。降采样前必须先滤，否则高频会折回成噪声。"""
    n = np.arange(taps) - (taps - 1) / 2.0
    fc = cutoff / sr
    h = 2.0 * fc * np.sinc(2.0 * fc * n) * np.hamming(taps)
    h /= h.sum()
    return np.convolve(x, h, mode="same")


def resample(x, sr_in, sr_out):
    if sr_in == sr_out:
        return x
    if sr_out < sr_in:
        x = _lowpass(x, sr_in, 0.45 * sr_out)
    n_out = int(round(len(x) * sr_out / sr_in))
    t_in = np.arange(len(x)) / float(sr_in)
    t_out = np.arange(n_out) / float(sr_out)
    return np.interp(t_out, t_in, x).astype(np.float32)


# ---------------------------------------------------------------- 切句


def speech_runs(x, sr, thresh_db=-40.0, min_speech=0.15, min_silence=0.20, pad=0.05):
    """按 RMS 找出有声段，返回 [(起, 止)] 的采样下标。

    门限是**相对峰值**的（峰值帧 RMS 往下 thresh_db），不是绝对 dBFS ——
    这批语音的录制增益不一致，绝对门限会在小声的那几条上整条判成静音。
    """
    frame = int(0.02 * sr)
    n = len(x) // frame
    if n == 0:
        return []
    rms = np.sqrt(np.mean(x[: n * frame].reshape(n, frame) ** 2, axis=1) + 1e-12)
    voiced = rms > rms.max() * (10.0 ** (thresh_db / 20.0))

    runs = []
    start = None
    for i, v in enumerate(voiced):
        if v and start is None:
            start = i
        elif not v and start is not None:
            runs.append([start, i])
            start = None
    if start is not None:
        runs.append([start, n])

    # 先并掉短停顿（句内的换气），再丢碎片 —— 顺序反了会把一句话拆成两半
    merged = []
    for r in runs:
        if merged and (r[0] - merged[-1][1]) * frame < min_silence * sr:
            merged[-1][1] = r[1]
        else:
            merged.append(r)

    out = []
    for a, b in merged:
        if (b - a) * frame < min_speech * sr:
            continue
        a = max(0, int(a * frame - pad * sr))
        b = min(len(x), int(b * frame + pad * sr))
        out.append((a, b))
    return out


def fade(x, sr, ms=30.0):
    """两端淡入淡出。不淡的话，截断点会「啪」一声，克隆出来的音色也带毛刺。"""
    n = min(int(sr * ms / 1000.0), len(x) // 2)
    if n <= 0:
        return x
    ramp = np.linspace(0.0, 1.0, n, dtype=np.float32)
    y = x.copy()
    y[:n] *= ramp
    y[-n:] *= ramp[::-1]
    return y


# ---------------------------------------------------------------- 选段


def candidates():
    """`sourse/流萤/archive_firefly_*.wav` —— 档案语音，语速平稳、最像日常说话。

    刻意**不含** `带变量语音 - Placeholder/`（那些是带占位符的模板句）与
    章节剧情语音（`chapter*`，几乎全是独白，语气偏沉）。
    """
    if not os.path.isdir(SRC_DIR):
        raise SystemExit(f"找不到语音目录：{SRC_DIR}")
    files = []
    for name in os.listdir(SRC_DIR):
        if not name.startswith("archive_firefly") or not name.endswith(".wav"):
            continue
        stem = os.path.splitext(name)[0]
        try:
            num = int(stem.split("_")[-1])
        except ValueError:
            continue
        files.append((num, os.path.join(SRC_DIR, name)))
    return sorted(files)


def cmd_list(sr_out):
    print(f"候选目录：{SRC_DIR}\n")
    print(f"{'#':>3}  {'时长':>7}  {'规格':<22}  句数  转写")
    print("-" * 100)
    for num, path in candidates():
        x, sr = read_wav_mono(path)
        y = resample(x, sr, sr_out)
        runs = speech_runs(y, sr_out)
        spec = f"{sr}Hz {1}ch 16bit"
        print(
            f"{num:>3}  {len(x) / sr:6.2f}s  {spec:<22}  {len(runs):>3}  "
            f"{read_lab(os.path.splitext(path)[0] + '.lab')}"
        )


def build(pick, target, allow_overshoot, gap_ms, fade_ms, peak_db, sr_out):
    gap_n = int(sr_out * gap_ms / 1000.0)
    target_n = int(target * sr_out)

    by_num = {n: p for n, p in candidates()}
    missing = [n for n in pick if n not in by_num]
    if missing:
        raise SystemExit(f"没有这些编号：{missing}（可选：{sorted(by_num)}）")

    # spans 里存的是「原文件上的连续区间」，不是切碎的单句。
    spans = []   # (编号, 路径, y, 起, 止)
    used = 0     # 已占用采样数，**含段间静音**（漏算它就会超时长，别问怎么知道的）
    for num in pick:
        path = by_num[num]
        x, sr = read_wav_mono(path)
        y = resample(x, sr, sr_out)
        runs = speech_runs(y, sr_out)
        if not runs:
            print(f"  ! #{num} 没切出有声段，跳过")
            continue

        room = target_n - used - (gap_n if spans else 0)
        if room <= 0:
            break

        # 尽可能多地纳入整句：同文件内相邻两句之间的原始停顿也要算进预算
        k, acc = 0, 0
        while k < len(runs):
            seg = runs[k][1] - runs[k][0]
            if k > 0:
                seg += runs[k][0] - runs[k - 1][1]
            if acc + seg > room:
                break
            acc += seg
            k += 1

        if k == 0:
            # 第一句就放不下：差得不多就整句留（宁可长一点也别切在半句上），
            # 差太多才截断
            a, b = runs[0]
            if room >= (b - a) - int(allow_overshoot * sr_out):
                spans.append((num, path, y, a, b))
                used += b - a + (gap_n if len(spans) > 1 else 0)
            else:
                spans.append((num, path, y, a, a + room))
                used += room + (gap_n if len(spans) > 1 else 0)
            break

        a, b = runs[0][0], runs[k - 1][1]
        spans.append((num, path, y, a, b))
        used += (b - a) + (gap_n if len(spans) > 1 else 0)

    if not spans:
        raise SystemExit("没拼出任何内容")

    pieces = []
    for num, path, y, a, b in spans:
        pieces.append(fade(y[a:b], sr_out, fade_ms))
        print(
            f"  #{num:<2} {os.path.basename(path)}  "
            f"[{a / sr_out:6.2f}s → {b / sr_out:6.2f}s]  {(b - a) / sr_out:5.2f}s"
        )

    out = pieces[0]
    for p in pieces[1:]:
        out = np.concatenate([out, np.zeros(gap_n, dtype=np.float32), p])

    # 峰值归一化：克隆音色对输入电平敏感，统一到 -3dBFS 避免有的角色偏小
    peak = float(np.max(np.abs(out)))
    if peak > 0:
        out = out * (10.0 ** (peak_db / 20.0) / peak)
    return out, sr_out


# ---------------------------------------------------------------- 主流程


def main():
    ap = argparse.ArgumentParser(description="剪一段 TTS 克隆参考音频")
    ap.add_argument("--list", action="store_true", help="只列候选，不写文件")
    ap.add_argument("--pick", default=DEFAULT_PICK, help=f"用哪些编号，默认 {DEFAULT_PICK}")
    ap.add_argument("--target", type=float, default=15.0, help="目标时长（秒），默认 15")
    ap.add_argument("--allow-overshoot", type=float, default=0.8,
                    help="最后一句超出目标多少秒以内就整句保留，默认 0.8")
    ap.add_argument("--gap-ms", type=float, default=120.0, help="句间静音，默认 120ms")
    ap.add_argument("--fade-ms", type=float, default=30.0, help="淡入淡出，默认 30ms")
    ap.add_argument("--peak-db", type=float, default=-3.0, help="峰值归一化目标，默认 -3dBFS")
    ap.add_argument("--sr", type=int, default=22050, help="输出采样率，默认 22050")
    ap.add_argument("--out", default=OUT_DEFAULT, help="输出路径")
    args = ap.parse_args()

    if args.list:
        cmd_list(args.sr)
        return

    pick = [int(p) for p in args.pick.split(",") if p.strip()]
    print(f"目标 {args.target}s / {args.sr}Hz 单声道 16bit，选段 {pick}\n")
    out, sr = build(pick, args.target, args.allow_overshoot, args.gap_ms,
                    args.fade_ms, args.peak_db, args.sr)

    write_wav(args.out, out, sr)
    size = os.path.getsize(args.out)
    b64 = len(base64.b64encode(out.astype("<i2").tobytes()))

    print(f"\n写出：{args.out}")
    print(f"  时长   {len(out) / sr:.2f}s")
    print(f"  规格   {sr}Hz 1ch 16bit")
    print(f"  文件   {size} 字节（{size / 1024:.0f} KB）")
    print(f"  base64 {b64} 字节（{b64 / 1024:.0f} KB）")
    print(f"  峰值   {20 * np.log10(max(float(np.max(np.abs(out))), 1e-9)):.1f} dBFS")

    if b64 >= MAX_B64:
        raise SystemExit(f"❌ base64 {b64} 超过 MiMo 上限 {MAX_B64}")
    print(f"✅ base64 未超 {MAX_B64 // (1024 * 1024)}MB 上限")


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except AttributeError:
        pass
    main()
