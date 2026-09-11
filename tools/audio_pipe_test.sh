#!/usr/bin/env bash
# ============================================================
# AudioPipe 自测
#
# AudioPipe 是纯 JDK 实现（不碰任何 Android API），所以可以直接拿 Gradle
# 编译出来的 .class 在桌面 JVM 上跑 —— 测的就是真正会被打进 APK 的那份代码。
#
# 覆盖：写入即唤醒 / 数据完整性 / 背压 / close 唤醒读写两端 / 部分读 / 环形回绕
#
# 用法：
#   bash tools/audio_pipe_test.sh
# ============================================================
set -u

cd "$(dirname "$0")/.."

CLASSES="app/build/tmp/kotlin-classes/debug"
if [ ! -f "$CLASSES/com/lv999call/app/audio/AudioPipe.class" ]; then
    echo "未找到编译产物，先执行: ./gradlew.bat compileDebugKotlin --offline"
    ./gradlew.bat -q compileDebugKotlin --offline || exit 1
fi

STDLIB="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib" \
    -name 'kotlin-stdlib-*.jar' 2>/dev/null | head -1)"
if [ -z "$STDLIB" ]; then
    echo "找不到 kotlin-stdlib.jar（Gradle 缓存里没有？）"
    exit 1
fi

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

# javac/java 是 Windows 程序，必须喂 Windows 形式路径
CP="$(cygpath -w "$PWD/$CLASSES");$(cygpath -w "$STDLIB")"
OUT_W="$(cygpath -w "$OUT")"

javac -encoding UTF-8 -cp "$CP" -d "$OUT_W" tools/AudioPipeTest.java || exit 1
java -Dstdout.encoding=UTF-8 -cp "$OUT_W;$CP" AudioPipeTest
