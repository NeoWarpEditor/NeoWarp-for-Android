#!/usr/bin/env bash
# NeoWarp Android 统一打包脚本
# 命名规则：NeoWarp-<versionName>-release.apk（不加任何自定义后缀）
# 每次打包直接覆盖同名文件，输出到 /workspace
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")" && pwd)"
ANDROID_DIR="$REPO_DIR/android"
OUT_DIR="/workspace"
BT="/opt/android-sdk/build-tools/34.0.0"
KEYSTORE="/root/.android/debug.keystore"

# 从 build.gradle 读取 versionName，避免硬编码
VERSION="$(grep -Eo 'versionName\s+"[^"]+"' "$ANDROID_DIR/app/build.gradle" | head -1 | grep -Eo '"[^"]+"' | tr -d '"')"
if [ -z "$VERSION" ]; then echo "无法读取 versionName" >&2; exit 1; fi

APK_NAME="NeoWarp-${VERSION}-release.apk"
ALIGNED="$(mktemp /tmp/nw-aligned-XXXXXX.apk)"

echo ">>> 构建版本 $VERSION -> $OUT_DIR/$APK_NAME"

# 全量同步 web 资源到 Capacitor assets（gradle 用此副本打包）
cp -a "$REPO_DIR/www/." "$ANDROID_DIR/app/src/main/assets/www/"

cd "$ANDROID_DIR"
ANDROID_HOME=/opt/android-sdk /opt/gradle/gradle-8.7/bin/gradle assembleDebug -q

SRC="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
"$BT/zipalign" -f 4 "$SRC" "$ALIGNED"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android \
  --ks-key-alias androiddebugkey --out "$OUT_DIR/$APK_NAME" "$ALIGNED"
"$BT/apksigner" verify "$OUT_DIR/$APK_NAME"
rm -f "$ALIGNED"
echo ">>> 完成：$OUT_DIR/$APK_NAME"
