#!/usr/bin/env bash
# 打包脚本：与姊妹仓库 jev-chat-jarvis（完整安卓版）同一套约定。
#
#   ./tools/package.sh            # 有签名配置就打 release，没有就打 debug
#   ./tools/package.sh debug      # 强制 debug
#   ./tools/package.sh release    # 强制 release（缺签名配置会直接报错退出）
#
# 签名配置放在**仓库外**，用 JEV_KEYSTORE_PROPS 指定路径；默认读 ./keystore.properties。
# 文件里四个键：storeFile / storePassword / keyAlias / keyPassword。
# （与自己机器无关的绝对路径不要提交进仓库，.gitignore 已经挡住 keystore.properties / *.jks）
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"

MODE="${1:-auto}"

# ---- JDK 17：AGP 8.7 要 JDK 17，优先用它 ----
if [[ -z "${JAVA_HOME:-}" ]]; then
  if /usr/libexec/java_home -v 17 >/dev/null 2>&1; then
    JAVA_HOME="$(/usr/libexec/java_home -v 17)"
  else
    echo "!! 找不到 JDK 17，请先 export JAVA_HOME=<jdk17 路径>" >&2
    exit 1
  fi
fi
export JAVA_HOME
echo "== JDK: $JAVA_HOME"

# ---- Android SDK ----
if [[ -z "${ANDROID_HOME:-}" ]]; then
  if [[ -f local.properties ]]; then
    ANDROID_HOME="$(grep -E '^sdk\.dir=' local.properties | head -1 | cut -d= -f2- || true)"
  fi
  if [[ -z "${ANDROID_HOME:-}" && -d "$HOME/Library/Android/sdk" ]]; then
    ANDROID_HOME="$HOME/Library/Android/sdk"
  fi
  if [[ -z "${ANDROID_HOME:-}" ]]; then
    echo "!! 找不到 Android SDK：export ANDROID_HOME=<路径> 或在 local.properties 写 sdk.dir=" >&2
    exit 1
  fi
fi
export ANDROID_HOME
echo "== SDK: $ANDROID_HOME"

# ---- 签名配置 ----
PROPS_PATH="${JEV_KEYSTORE_PROPS:-$ROOT/keystore.properties}"
HAS_SIGNING=0
if [[ -f "$PROPS_PATH" ]]; then
  HAS_SIGNING=1
  echo "== 签名: $PROPS_PATH"
else
  echo "== 签名: 没有（$PROPS_PATH 不存在）"
fi

if [[ "$MODE" == "release" && $HAS_SIGNING -eq 0 ]]; then
  echo "!! 要打 release 但没有签名配置。" >&2
  echo "   创建 ${PROPS_PATH}，写入 storeFile / storePassword / keyAlias / keyPassword 后重试。" >&2
  exit 1
fi

if [[ "$MODE" == "debug" || ( "$MODE" == "auto" && $HAS_SIGNING -eq 0 ) ]]; then
  TASK=assembleDebug
  OUT_APK="app/build/outputs/apk/debug/app-debug.apk"
else
  TASK=assembleRelease
  OUT_APK="app/build/outputs/apk/release/app-release.apk"
fi

echo "== gradlew $TASK"
./gradlew "$TASK"

if [[ ! -f "$OUT_APK" ]]; then
  echo "!! 没找到产物 $OUT_APK" >&2
  exit 1
fi

# ---- 签名校验（有 apksigner 就验一把，没有就跳过） ----
APKSIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"
if [[ -x "$APKSIGNER" ]]; then
  echo "== apksigner verify"
  if ! "$APKSIGNER" verify --print-certs "$OUT_APK" 2>/dev/null | head -5; then
    echo "   （未签名或校验不通过——debug 包用 debug key 签名属正常）"
  fi
fi

# ---- 落一份到 apk/，文件名带版本号 ----
VERSION="$(grep -E 'versionName *= *"' app/build.gradle.kts | head -1 | sed -E 's/.*"([^"]*)".*/\1/')"
if [[ "$TASK" == "assembleDebug" ]]; then
  CHANNEL=debug
else
  CHANNEL=release
fi
mkdir -p apk
DEST="apk/jev-simple-v${VERSION}-${CHANNEL}.apk"
cp "$OUT_APK" "$DEST"

SIZE="$(du -h "$DEST" | cut -f1)"
echo
echo "✅ 打包完成：${DEST}（${SIZE}）"
echo "   安装：adb install -r \"$DEST\""
echo "   调试包 / release 包可直接 sideload；release 需要签名配置才可分发。"
