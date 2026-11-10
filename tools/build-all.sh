#!/usr/bin/env bash
# =============================================================================
# 一键构建：环境检查 -> 依赖获取 -> 统一签名 -> APK -> 两个机型的 rpk -> dist/ 汇总
#
# 用法：
#   KS_PASS='你的密码' bash tools/build-all.sh
#   KS_PASS='你的密码' bash tools/build-all.sh --skip-rpk     # 只打 APK
#   KS_PASS='你的密码' bash tools/build-all.sh --skip-apk     # 只打 rpk
#   bash tools/build-all.sh --no-fetch                        # 不自动下载依赖（离线）
#
# 产物（默认 dist/）：
#   cn.dsh.gaodenav.apk         手机端（含高德 SDK，约 60~80MB）
#   手环导航-手环9.rpk           手环 9 / 9NFC
#   手环导航-手环9Pro.rpk        手环 9 Pro
#   BUILD-REPORT.txt            哈希、证书指纹、版本、工具链
# =============================================================================
set -uo pipefail

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SELF_DIR/.." && pwd)"
DIST="${DIST_DIR:-$ROOT/dist}"
KS_PASS="${KS_PASS:-}"
DO_APK=1
DO_RPK=1
DO_FETCH=1
CLEAN=0

say()  { printf '%s\n' "$*"; }
step() { printf '\n===== %s =====\n' "$*"; }
die()  { printf '\n[错误] %s\n' "$*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --skip-apk) DO_APK=0 ;;
    --skip-rpk) DO_RPK=0 ;;
    --no-fetch) DO_FETCH=0 ;;
    --clean)    CLEAN=1 ;;
    -h|--help)  sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "未知参数：$1（--help 看用法）" ;;
  esac
  shift
done


step "0/5 环境检查"
if [ "$CLEAN" -eq 1 ]; then
  say "  清理旧产物（android-app/out、android-app/dist、各 band-app/dist、dist/）"
  rm -rf "$ROOT/android-app/out" "$ROOT/android-app/dist" "$ROOT/dist"
  rm -rf "$ROOT/band-app/dist" "$ROOT/band-app-pro/dist"
fi
mkdir -p "$DIST"

DEPS_OK=0
if [ -n "$(ls -1 "$ROOT"/third_party/AMapNavi/libs/*.jar 2>/dev/null | head -1)" ] \
   && [ -s "$ROOT/android-app/libs/xms-wearable-classes.jar" ]; then
  DEPS_OK=1
fi
if [ "$DEPS_OK" -eq 0 ]; then
  if [ "$DO_FETCH" -eq 1 ]; then
    step "获取第三方依赖（高德 SDK / 小米互联 AAR）"
    bash "$SELF_DIR/fetch-deps.sh" || die "依赖获取失败"
    DEPS_OK=1
  else
    die "依赖缺失，且指定了 --no-fetch。先运行 bash tools/fetch-deps.sh"
  fi
fi

if [ ! -f "$ROOT/keys/release.jks" ] || [ ! -f "$ROOT/keys/certificate.pem" ]; then
  [ -n "$KS_PASS" ] || die "还没有签名（keys/ 为空）。请设置密码后重跑：export KS_PASS='你的密码' bash tools/build-all.sh"
  step "生成统一签名（APK 与 rpk 共用）"
  bash "$SELF_DIR/gen-signing.sh" || die "签名生成失败"
fi

if ! bash "$SELF_DIR/check-env.sh"; then
  say ""
  say "⚠️ 环境检查有缺失项（见上），继续尝试构建；真正缺依赖的那一步会给出具体报错。"
  say "   只想打其中一端时可以用 --skip-rpk / --skip-apk。"
fi

# ------------------------------------------------------------------ APK
APK_PATH=""
if [ "$DO_APK" -eq 1 ]; then
  step "构建手机端 APK"
  [ -n "$KS_PASS" ] || die "构建 APK 需要 keystore 密码：export KS_PASS='你的密码'"
  KS_PASS="$KS_PASS" bash "$ROOT/android-app/build.sh" || die "APK 构建失败（见上日志）"
  APK_PATH="$ROOT/android-app/dist/cn.dsh.gaodenav.apk"
  [ -f "$APK_PATH" ] || die "APK 没有产出：$APK_PATH"
  cp -f "$APK_PATH" "$DIST/cn.dsh.gaodenav.apk"
  say "  APK -> $DIST/cn.dsh.gaodenav.apk"
fi

# ------------------------------------------------------------------ RPK
RPK_NAMES=("band-app:手环导航-手环9" "band-app-pro:手环导航-手环9Pro")
RPK_DONE=()
if [ "$DO_RPK" -eq 1 ]; then
  step "构建手环端 RPK（手环 9 / 9 Pro）"
  have node || die "构建 rpk 需要 Node.js（https://nodejs.org）"
  have npm || die "构建 rpk 需要 npm"
  for pair in "${RPK_NAMES[@]}"; do
    dir="${pair%%:*}"
    outname="${pair##*:}"
    appdir="$ROOT/$dir"
    [ -d "$appdir" ] || die "找不到手环工程目录：$appdir"
    say ""
    say "-- $dir -> $outname.rpk"
    if [ -x "$appdir/node_modules/.bin/aiot" ]; then
      AIOT="$appdir/node_modules/.bin/aiot"
    elif have aiot; then
      AIOT="aiot"
    else
      say "   首次构建：npm install（安装 aiot-toolkit，约 1-2 分钟）"
      ( cd "$appdir" && npm install --no-audit --no-fund ) || die "$dir npm install 失败"
      AIOT="$appdir/node_modules/.bin/aiot"
      [ -x "$AIOT" ] || die "$dir 里没有 node_modules/.bin/aiot（aiot-toolkit 版本异常？）"
    fi
    # 证书必须是我们自己分发的，避免 aiot 静默用自带证书
    [ -s "$appdir/sign/release/private.pem" ] || die "$appdir/sign/release 缺 private.pem（先 bash tools/gen-signing.sh）"
    ( cd "$appdir" && "$AIOT" release ) || die "$dir aiot release 失败"
    rpk="$(ls -1t "$appdir"/dist/*.rpk 2>/dev/null | head -1)"
    [ -n "$rpk" ] || die "$dir 没有产出 rpk（看上面的 aiot 日志）"
    cp -f "$rpk" "$DIST/$outname.rpk"
    say "   rpk -> $DIST/$outname.rpk ($(du -h "$rpk" 2>/dev/null | awk '{print $1}'))"
    # 校验 rpk 内嵌证书（Windows 上往往只有 python.exe）
    PY=""
    for cand in python3 python; do
      if have "$cand"; then PY="$cand"; break; fi
    done
    if [ -f "$ROOT/keys/certificate.pem" ] && [ -n "$PY" ]; then
      "$PY" "$SELF_DIR/verify-rpk-cert.py" "$DIST/$outname.rpk" "$ROOT/keys/certificate.pem" >/dev/null 2>&1 \
        && say "   证书内嵌校验通过 ✅" \
        || say "   ⚠️ 证书内嵌校验失败：检查 aiot 日志里的 privatekeyPath 是否指向 $appdir/sign/release"
    fi
    RPK_DONE+=("$DIST/$outname.rpk")
  done
fi

# ------------------------------------------------------------------ 报告
step "汇总"
CERT_SHA="$(cat "$ROOT/keys/cert-sha256.txt" 2>/dev/null | tr -d ' \n\r')"
REPORT="$DIST/BUILD-REPORT.txt"
{
  echo "构建时间 : $(date '+%Y-%m-%d %H:%M:%S %z')"
  echo "仓库     : $ROOT"
  echo "证书     : SHA-256 $CERT_SHA（APK 与 rpk 必须一致）"
  echo
  echo "== 产物 =="
  for f in "$DIST"/*.apk "$DIST"/*.rpk; do
    [ -f "$f" ] || continue
    if have sha256sum; then sha="$(sha256sum "$f" | awk '{print $1}')"
    elif have shasum; then sha="$(shasum -a 256 "$f" | awk '{print $1}')"
    else sha="?"; fi
    printf '%-32s %10s  sha256=%s\n' "$(basename "$f")" "$(du -h "$f" | awk '{print $1}')" "$sha"
  done
  echo
  echo "== 工具链 =="
  ( command -v java >/dev/null 2>&1 && java -version 2>&1 | head -1 )
  ( command -v node >/dev/null 2>&1 && echo "node $(node -v)" )
  ( command -v npm >/dev/null 2>&1 && echo "npm $(npm -v 2>/dev/null)" )
  ( command -v aapt >/dev/null 2>&1 && aapt v 2>&1 | head -1 )
  echo
  echo "== 版本 =="
  echo "APK : $(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$ROOT/android-app/AndroidManifest.xml")"
  for pair in "${RPK_NAMES[@]}"; do
    d="${pair%%:*}"
    echo "rpk : $d -> $(sed -n 's/.*"versionName": *"\([^"]*\)".*/\1/p' "$ROOT/$d/src/manifest.json")"
  done
} >"$REPORT" 2>&1
cat "$REPORT"

echo
echo "===== 全部完成 ====="
echo "产物目录：$DIST"
