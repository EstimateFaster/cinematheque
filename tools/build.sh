#!/bin/bash
# ============================================================
# 影厅 Cinematheque — APK 构建脚本（可移植：本地 / GitHub Actions 通用）
#
# 环境变量（均可选）：
#   ANDROID_SDK   已有 SDK 目录；不存在则自动下载 cmdline-tools
#   OUTPUT_DIR    产物输出目录，默认 <repo>/dist
#   KS_PATH       签名 keystore 路径，默认 <repo>/dsh.keystore（不存在则生成）
#   KS_PASS       keystore 密码，默认 dsh12345
#
# 说明：源码禁止匿名内部类与（非静态）内部类 —— 部分 d8 版本会崩溃。
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/tools"
OUT="${OUTPUT_DIR:-$ROOT/dist}"
BUILD="$OUT/build"

SDK="${ANDROID_SDK:-$ROOT/.android-sdk}"
CMDLINE_VER="11076708"
BT_VER="34.0.0"
PLATFORM_VER="android-34"

KS="${KS_PATH:-$ROOT/dsh.keystore}"
KSPASS="${KS_PASS:-dsh12345}"

echo "==> 项目根目录: $ROOT"
echo "==> 输出目录  : $OUT"

# ---------- 1. 准备 Android SDK ----------
if [ ! -x "$SDK/build-tools/$BT_VER/aapt2" ] || [ ! -f "$SDK/platforms/$PLATFORM_VER/android.jar" ]; then
  echo "==> 安装 Android SDK 组件到 $SDK"
  if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    mkdir -p "$SDK/cmdline-tools"
    TMPZ="$(mktemp -d)/cmdline.zip"
    curl -fsSL -o "$TMPZ" \
      "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_VER}_latest.zip"
    unzip -q "$TMPZ" -d "$SDK/cmdline-tools"
    rm -rf "$SDK/cmdline-tools/latest"
    mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
  fi
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
  "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" \
    "platforms;$PLATFORM_VER" "build-tools;$BT_VER"
fi

BT="$SDK/build-tools/$BT_VER"
PLATFORM="$SDK/platforms/$PLATFORM_VER/android.jar"

# ---------- 2. 清理 ----------
rm -rf "$BUILD"
mkdir -p "$BUILD/res" "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$OUT"

# ---------- 3. 资源编译 ----------
echo "==> aapt2 compile"
"$BT/aapt2" compile --dir "$SRC/res" -o "$BUILD/res/res.zip"

echo "==> aapt2 link（含 assets）"
"$BT/aapt2" link \
  -o "$BUILD/base.apk" \
  -I "$PLATFORM" \
  --manifest "$SRC/AndroidManifest.xml" \
  --java "$BUILD/gen" \
  -A "$SRC/assets" \
  --min-sdk-version 21 --target-sdk-version 29 \
  --version-code 1 --version-name 1.0 \
  "$BUILD/res/res.zip"

# ---------- 4. Java 编译 ----------
echo "==> javac"
javac --release 8 -nowarn -encoding UTF-8 \
  -classpath "$PLATFORM" \
  -d "$BUILD/classes" \
  $(find "$SRC/java" "$BUILD/gen" -name '*.java')

# ---------- 5. dex ----------
echo "==> d8"
"$BT/d8" --lib "$PLATFORM" --min-api 21 --output "$BUILD/dex" \
  $(find "$BUILD/classes" -name '*.class')

echo "==> 打包 classes.dex"
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
(cd "$BUILD/dex" && zip -q -X "$BUILD/unsigned.apk" classes.dex)

# ---------- 6. 签名 ----------
echo "==> zipalign + apksigner"
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -alias dsh -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$KSPASS" -keypass "$KSPASS" \
    -dname "CN=DSH, OU=dev, O=dsh, L=NA, S=NA, C=CN" >/dev/null 2>&1
fi
"$BT/zipalign" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KSPASS" --key-pass "pass:$KSPASS" \
  --out "$OUT/cinematheque.apk" "$BUILD/aligned.apk"

echo "==> 完成"
ls -lh "$OUT/cinematheque.apk"
"$BT/apksigner" verify "$OUT/cinematheque.apk" && echo "签名校验通过"
echo "--- APK 内容 ---"
unzip -l "$OUT/cinematheque.apk" | grep -E "assets/|classes.dex|resources.arsc" | head
