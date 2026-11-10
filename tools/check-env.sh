#!/usr/bin/env bash
# =============================================================================
# 环境自检：APK（Android App）与 RPK（手环快应用）两条构建链路分别检查
#
# 用法：
#   bash tools/check-env.sh
#   bash tools/check-env.sh --quiet     # 只打印缺失项
#
# 退出码：0 = 两条链路都就绪；1 = 有必需项缺失
# =============================================================================
set -uo pipefail

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SELF_DIR/.." && pwd)"
# shellcheck source=/dev/null
. "$SELF_DIR/lib/detect.sh"

QUIET=0
[ "${1:-}" = "--quiet" ] && QUIET=1

MISSING=0
say() { [ "$QUIET" -eq 1 ] && return 0; printf '%s\n' "$*"; }
row() { # $1=状态 $2=名称 $3=详情
  if [ "$1" = "OK" ]; then
    [ "$QUIET" -eq 1 ] && return 0
    printf '  [OK]   %-14s %s\n' "$2" "$3"
  else
    printf '  [缺失] %-14s %s\n' "$2" "$3"
    MISSING=$((MISSING + 1))
  fi
}
opt() { # 可选依赖
  if [ "$1" = "OK" ]; then
    [ "$QUIET" -eq 1 ] && return 0
    printf '  [--]   %-14s %s\n' "$2" "$3"
  else
    [ "$QUIET" -eq 1 ] && return 0
    printf '  [可选] %-14s %s\n' "$2" "$3"
  fi
}

say "仓库目录：$ROOT"
say ""

say "== 一、APK 构建链（android-app/build.sh）=="
for t in java javac jar; do
  if command -v "$t" >/dev/null 2>&1; then row OK "$t" "$(command -v "$t")"; else row MISSING "$t" "装 JDK 17+（apt install openjdk-17-jdk-headless / brew install openjdk@17）"; fi
done
BT_DIR="${ANDROID_BUILD_TOOLS:-}"
# 环境变量给的目录不存在时也回退到自动探测
if [ -z "$BT_DIR" ] || [ ! -d "$BT_DIR" ]; then BT_DIR="$(detect_build_tools_dir 2>/dev/null || true)"; fi
if [ -n "$BT_DIR" ] && [ -d "$BT_DIR" ]; then
  PATH="$BT_DIR:$PATH"
  row OK build-tools "$BT_DIR"
else
  row MISSING build-tools "sdkmanager 'build-tools;34.0.0'（提供 aapt / zipalign / apksigner / d8）"
fi
for t in aapt zipalign apksigner unzip; do
  if command -v "$t" >/dev/null 2>&1 || command -v "$t.bat" >/dev/null 2>&1; then
    row OK "$t" "$(command -v "$t" 2>/dev/null || command -v "$t.bat")"
  else
    row MISSING "$t" "装 Android build-tools（apksigner 也在里面）/ unzip"
  fi
done
AJ="$(detect_android_jar 2>/dev/null || true)"
if [ -n "$AJ" ]; then row OK android.jar "$AJ"; else row MISSING android.jar "sdkmanager 'platforms;android-30'（或 apt install android-sdk-platform-23）"; fi
DEXER="$(DEPS_DIR="$ROOT/third_party" BUILD_DIR="${TMPDIR:-/tmp}/gaodenav-build" detect_dexer 2>/dev/null || true)"
if [ -n "$DEXER" ]; then row OK dexer "$DEXER"; else row MISSING dexer "build-tools 自带 d8；没有的话 bash tools/fetch-deps.sh --with-r8"; fi
if [ -s "$ROOT/android-app/libs/xms-wearable-classes.jar" ]; then row OK xms-sdk "android-app/libs/xms-wearable-classes.jar"; else row MISSING xms-sdk "bash tools/fetch-deps.sh"; fi
if [ -n "$(ls -1 "$ROOT"/third_party/AMapNavi/libs/*.jar 2>/dev/null | head -1)" ]; then
  row OK amap-sdk "third_party/AMapNavi（jar+assets+so）"
else
  row MISSING amap-sdk "bash tools/fetch-deps.sh"
fi
if command -v keytool >/dev/null 2>&1; then opt OK keytool "$(command -v keytool)"; else opt MISSING keytool "JDK 自带（生成 keystore 用）"; fi
if command -v openssl >/dev/null 2>&1; then opt OK openssl "$(command -v openssl)"; else opt MISSING openssl "签名 PEM 导出 / 证书指纹用（brew install openssl / apt install openssl）"; fi
if [ -f "$ROOT/keys/release.jks" ] && [ -f "$ROOT/keys/certificate.pem" ]; then
  row OK 签名 "$ROOT/keys（keystore + PEM 已就绪）"
else
  row MISSING 签名 "KS_PASS='密码' bash tools/gen-signing.sh"
fi

say ""
say "== 二、RPK 构建链（band-app / band-app-pro）=="
if command -v node >/dev/null 2>&1; then row OK node "$(node -v)"; else row MISSING node "装 Node.js 18+（https://nodejs.org）"; fi
if command -v npm >/dev/null 2>&1; then row OK npm "$(npm -v 2>/dev/null)"; else row MISSING npm "随 Node.js 一起安装"; fi
if command -v aiot >/dev/null 2>&1; then
  row OK aiot "$(aiot -v 2>/dev/null || echo 已安装（全局）)"
elif [ -x "$ROOT/band-app/node_modules/.bin/aiot" ] || [ -x "$ROOT/band-app-pro/node_modules/.bin/aiot" ]; then
  row OK aiot "工程内 node_modules/.bin/aiot（npm install 已装好，build-all 会用它）"
else
  row MISSING aiot "npm i -g aiot-toolkit（或在 band-app 里 npm install，工程内自带）"
fi
for d in band-app band-app-pro; do
  if [ -s "$ROOT/$d/sign/release/private.pem" ] && [ -s "$ROOT/$d/sign/release/certificate.pem" ]; then
    row OK "$d 证书" "sign/release 已就绪"
  else
    row MISSING "$d 证书" "bash tools/gen-signing.sh（会同时分发到两个机型）"
  fi
done

say ""
if [ "$MISSING" -eq 0 ]; then
  say "全部就绪 ✅  可以构建：KS_PASS='密码' bash tools/build-all.sh"
  exit 0
fi
say "有 $MISSING 项缺失 ❌  按上面提示补齐后重跑；依赖可用 bash tools/fetch-deps.sh 自动获取"
exit 1
