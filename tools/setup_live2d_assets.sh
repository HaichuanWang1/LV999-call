#!/usr/bin/env bash
# ============================================================
# Live2D 资源本地获取脚本
# ------------------------------------------------------------
# 这些资源因版权原因不入库（见 .gitignore），需在本地拉取。
# 缺失时 App 仍可运行，只是会自动回退到静态头像。
#
# 用法:
#   bash tools/setup_live2d_assets.sh               # 运行时 + 示例模型
#   bash tools/setup_live2d_assets.sh --lib-only    # 只拉运行时
#   bash tools/setup_live2d_assets.sh --sample-only # 只拉示例模型
# ============================================================
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/live2d"
LIB_DIR="$DEST/lib"
MODEL_DIR="$DEST/models/haru"
POST="$ROOT/tools/live2d_postprocess.py"

PIXI_URL="https://cdn.jsdelivr.net/npm/pixi.js@6.5.10/dist/browser/pixi.min.js"
CORE_URL="https://cdn.jsdelivr.net/npm/live2dcubismcore@1.0.2/live2dcubismcore.min.js"
PLD_URL="https://cdn.jsdelivr.net/npm/pixi-live2d-display@0.4.0/dist/cubism4.min.js"
MODEL_BASE="https://cdn.jsdelivr.net/gh/guansss/pixi-live2d-display/test/assets/haru"

DO_LIB=1
DO_SAMPLE=1
for a in "$@"; do
  case "$a" in
    --lib-only)    DO_SAMPLE=0 ;;
    --sample-only) DO_LIB=0 ;;
    -h|--help)     sed -n '2,13p' "$0"; exit 0 ;;
    *) echo "unknown option: $a" >&2; exit 2 ;;
  esac
done

OK=0
BAD=0

fetch() { # $1=url  $2=dest file  $3=min bytes
  mkdir -p "$(dirname "$2")"
  local code size
  code=$(curl -sSL -w '%{http_code}' -o "$2" "$1" 2>/dev/null) || code=000
  size=$(wc -c < "$2" 2>/dev/null || echo 0)
  if [ "$code" = "200" ] && [ "$size" -ge "${3:-1}" ]; then
    printf '  [ok]   %-46s %8s B\n' "$(basename "$2")" "$size"
    OK=$((OK + 1))
  else
    printf '  [FAIL] %s (code=%s)\n' "$1" "$code" >&2
    BAD=$((BAD + 1))
    rm -f "$2"
  fi
}

if [ "$DO_LIB" = 1 ]; then
  echo "==> runtime -> lib/"
  fetch "$PIXI_URL" "$LIB_DIR/pixi.min.js" 300000
  fetch "$CORE_URL" "$LIB_DIR/live2dcubismcore.min.js" 100000
  fetch "$PLD_URL"  "$LIB_DIR/cubism4.min.js" 50000
fi

if [ "$DO_SAMPLE" = 1 ]; then
  echo "==> sample model (Haru) -> models/haru/"
  for f in \
    haru_greeter_t03.model3.json \
    haru_greeter_t03.moc3 \
    haru_greeter_t03.physics3.json \
    haru_greeter_t03.pose3.json \
    haru_greeter_t03.2048/texture_00.png \
    haru_greeter_t03.2048/texture_01.png \
    motion/haru_g_idle.motion3.json \
    motion/haru_g_m07.motion3.json \
    motion/haru_g_m15.motion3.json \
    motion/haru_g_m14.motion3.json \
    motion/haru_g_m05.motion3.json ; do
    fetch "$MODEL_BASE/$f" "$MODEL_DIR/$f" 100
  done
  for i in 1 2 3 4 5 6 7 8; do
    fetch "$MODEL_BASE/expressions/F0$i.exp3.json" "$MODEL_DIR/expressions/F0$i.exp3.json" 50
  done
fi

# ---------- 后处理 ----------
PY=""
command -v python3 >/dev/null 2>&1 && PY=python3
[ -z "$PY" ] && command -v python >/dev/null 2>&1 && PY=python

if [ -n "$PY" ] && [ -f "$POST" ]; then
  echo "==> post-process"
  ARGS=()
  [ "$DO_LIB" = 1 ] && ARGS+=(--lib "$LIB_DIR")
  [ "$DO_SAMPLE" = 1 ] && ARGS+=(--model "$MODEL_DIR/haru_greeter_t03.model3.json")
  [ ${#ARGS[@]} -gt 0 ] && "$PY" "$POST" "${ARGS[@]}"
else
  echo "  [!] python or $POST not found; skipping post-process" >&2
  echo "      (pixi.min.js may keep sourceMappingURL; model3.json may keep missing refs)" >&2
fi

echo
echo "Done: ok=$OK failed=$BAD"
if [ "$BAD" -gt 0 ]; then
  echo "Some downloads failed. Check network and retry." >&2
  exit 1
fi
echo "Ready to build. Licensing: $DEST/LICENSES.md"
