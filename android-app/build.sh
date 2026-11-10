#!/usr/bin/env bash
# =============================================================================
# 高德导航手环桥 —— 纯命令行 APK 构建（不依赖 Gradle / aapt2 / Kotlin）
#
# 流水线：
#   0) 环境与依赖检查（JDK / Android build-tools / 高德 SDK / 小米互联 AAR / 签名库）
#   1) 图标映射离线自测（纯 Java，先保证解析逻辑正确）
#   2) javac -source 8 -target 8 编译
#   3) D8/R8 生成 classes.dex（含高德 SDK 与 xms-wearable 的类）
#   4) aapt 打资源与清单（含高德 SDK 的 ~48MB assets）
#   5) 塞入 classes.dex 与 arm64-v8a/*.so
#   6) zipalign -p（.so 页对齐）
#   7) apksigner 签名（v1+v2+v3）
#   8) 校验：APK 证书 SHA-256 == rpk 证书 SHA-256，且 dex/so/assets 齐全
#   9) 产出到 dist/（可选复制到手机存储，见 SDCARD_DEST）
#
# 用法：
#   export KS_PASS='你的密码'
#   bash android-app/build.sh              # 完整构建
#   bash android-app/build.sh --check      # 只检查环境与依赖，不构建
#   bash android-app/build.sh --help
#
# 首次构建前先准备依赖与签名（详见 README.md）：
#   bash tools/fetch-deps.sh               # 高德 SDK + 小米互联 AAR（约 236MB 下载）
#   bash tools/gen-signing.sh              # keystore + PEM（APK 与 rpk 必须同一套）
#
# 可用环境变量：
#   KS_PASS         keystore 密码（必填，不要写进仓库）
#   KS_FILE         签名库路径（默认 keys/release.jks）
#   CERT_DIR        certificate.pem 所在目录（默认 keys/）
#   ANDROID_JAR     android.jar 路径（默认自动探测 ANDROID_HOME / ANDROID_SDK_ROOT / 常见安装位置）
#   ANDROID_BUILD_TOOLS  build-tools 目录（默认自动探测，找到后自动加进 PATH）
#   SDK_ROOT        高德 SDK 目录（默认 third_party/AMapNavi）
#   XMS_JAR         小米互联 classes.jar（默认 android-app/libs/xms-wearable-classes.jar）
#   DEPS_DIR        依赖根目录（默认 <repo>/third_party）
#   BUILD_DIR       中间产物目录（默认 $TMPDIR/gaodenav-build）
#   DIST_DIR        APK 输出目录（默认 android-app/dist）
#   SDCARD_DEST     可选：构建后额外复制到的目录（如手机共享目录），不设置则不复制
#   ICON_SRC        可选：图标源目录（含 mipmap-* 五档 PNG），不设置则用仓库里已有的 res 图标
# =============================================================================
set -uo pipefail

APP="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
DEPS_DIR="${DEPS_DIR:-$ROOT/third_party}"
BUILD_DIR="${BUILD_DIR:-${TMPDIR:-/tmp}/gaodenav-build}"
PKG=cn.dsh.gaodenav
KS="${KS_FILE:-$ROOT/keys/release.jks}"
KSPASS="${KS_PASS:-}"
CERT_DIR="${CERT_DIR:-$ROOT/keys}"
DIST="${DIST_DIR:-$APP/dist}"
SDK_ROOT="${SDK_ROOT:-$DEPS_DIR/AMapNavi}"
XMS_JAR="${XMS_JAR:-$APP/libs/xms-wearable-classes.jar}"
SDCARD_DEST="${SDCARD_DEST:-}"

CHECK_ONLY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --check) CHECK_ONLY=1 ;;
    -h|--help) sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数：$1（用 --help 看用法）" >&2; exit 2 ;;
  esac
  shift
done

mkdir -p "$BUILD_DIR"
LOG="$BUILD_DIR/apk.log"
: >"$LOG"
# 同时输出到控制台与日志（bash 进程替换；没有 tee 时退化为只写日志）
if command -v tee >/dev/null 2>&1; then exec > >(tee -a "$LOG") 2>&1; else exec >>"$LOG" 2>&1; fi

step() { echo; echo "===== [$(date +%H:%M:%S)] $* ====="; }
info() { echo "  $*"; }
die()  { echo; echo "!!!!! 构建失败: $* !!!!!"; echo "日志：$LOG"; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

# 路径分隔符（Windows 上的 java/javac 用 ; ）
case "$(uname -s 2>/dev/null || echo unknown)" in
  MINGW*|MSYS*|CYGWIN*) CPSEP=';' ;;
  *) CPSEP=':' ;;
esac

# 单独传一个路径时 MSYS 会自动转换，但「; 分隔的 classpath 列表」不会，
# 所以在 Windows 上显式用 cygpath 转成原生路径。
winpath() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

# 共用探测函数（android.jar / build-tools / dexer）
if [ -f "$ROOT/tools/lib/detect.sh" ]; then
  # shellcheck source=/dev/null
  . "$ROOT/tools/lib/detect.sh"
fi

sha256sum_of() {
  if have sha256sum; then sha256sum "$1" | awk '{print $1}'
  elif have shasum; then shasum -a 256 "$1" | awk '{print $1}'
  elif have openssl; then openssl dgst -sha256 "$1" | sed 's/^.*= *//'
  else echo unknown
  fi
}
size_of() { if have du; then du -sh "$1" 2>/dev/null | awk '{print $1}'; else echo '?'; fi; }
human_list() { find "$1" -type f 2>/dev/null | wc -l | tr -d ' '; }

# ---------------------------------------------------------------- 依赖与工具探测
findAmapJar() {
  # 用 glob 循环而不是 ls | head：管道在 pipefail 下可能因 SIGPIPE 变成 141
  local cand
  for cand in "$SDK_ROOT"/libs/*.jar \
              "$SDK_ROOT"/unzip/nested_AMap3DMap*/*.jar \
              "$SDK_ROOT"/unzip/*/*.jar; do
    if [ -f "$cand" ]; then printf '%s\n' "$cand"; return 0; fi
  done
  return 1
}

step "0/9 环境与依赖检查"
info "仓库根目录   : $ROOT"
info "中间产物目录 : $BUILD_DIR"
if have java; then info "java          : $(java -version 2>&1 | head -1)"; fi
if have javac; then info "javac         : $(javac -version 2>&1 | head -1)"; fi

# --- Android build-tools（aapt / zipalign / apksigner / d8）---
if ! have aapt || ! have zipalign || ! have apksigner; then
  BT_DIR="${ANDROID_BUILD_TOOLS:-}"
  if [ -z "$BT_DIR" ] && declare -f detect_build_tools_dir >/dev/null 2>&1; then
    BT_DIR="$(detect_build_tools_dir || true)"
  fi
  if [ -n "$BT_DIR" ] && [ -d "$BT_DIR" ]; then
    PATH="$BT_DIR:$PATH"
    export PATH
    info "build-tools   : $BT_DIR（已加入 PATH）"
  fi
fi
# Windows 下 apksigner / d8 是 .bat，bash 里补一层包装
if ! have apksigner && have apksigner.bat; then apksigner() { apksigner.bat "$@"; }; fi
if ! have d8 && have d8.bat; then d8() { d8.bat "$@"; }; fi

# --- android.jar ---
# 注意：这里不能先把 ANDROID_JAR 清空再探测，否则用户 export 的 ANDROID_JAR 会被吃掉
ANDROID_JAR="${ANDROID_JAR:-}"
if declare -f detect_android_jar >/dev/null 2>&1; then
  ANDROID_JAR_FOUND="$(detect_android_jar 2>/dev/null || true)"
  if [ -n "$ANDROID_JAR_FOUND" ]; then ANDROID_JAR="$ANDROID_JAR_FOUND"; fi
fi
[ -n "$ANDROID_JAR" ] && [ -f "$ANDROID_JAR" ] || die "找不到 android.jar。
  装一个 Android SDK platform（任选其一）：
    * Android Studio → SDK Manager → 勾选任一 Android API（推荐 30+）
    * 命令行：sdkmanager 'platforms;android-30' 'build-tools;34.0.0'
    * Debian/Ubuntu 可 apt install android-sdk-platform-23
  或显式指定：export ANDROID_JAR=/path/to/platforms/android-30/android.jar"
info "android.jar   : $ANDROID_JAR"

# --- 必需命令 ---
for t in javac java jar aapt zipalign apksigner unzip; do
  have "$t" || die "缺少命令 $t。
    Linux : sudo apt install openjdk-17-jdk-headless aapt apksigner zipalign unzip android-sdk-platform-23
    macOS : brew install openjdk@17 android-commandlinetools && sdkmanager 'platforms;android-30' 'build-tools;34.0.0'
    Windows: 装 JDK 17 + Android SDK build-tools，然后在 Git Bash / WSL 里构建"
done
info "aapt          : $(aapt v 2>&1 | head -1)"
info "apksigner     : $(apksigner version 2>&1 | head -1)"

# --- 签名库 ---
if [ "$CHECK_ONLY" -eq 1 ] && [ -z "$KSPASS" ]; then
  info "KS_PASS       : 未设置（--check 模式下允许，真正构建时必填）"
else
  [ -n "$KSPASS" ] || die "请先设置 keystore 密码：export KS_PASS='你的密码'（见 SIGNING.md）"
  [ -f "$KS" ] || die "找不到签名库：$KS
  先运行 bash tools/gen-signing.sh 生成（或 export KS_FILE=/path/to/your.jks）"
  info "keystore      : $KS"
fi

# --- 小米互联 SDK ---
[ -f "$XMS_JAR" ] || die "找不到小米互联 SDK：$XMS_JAR
  先运行 bash tools/fetch-deps.sh（会从 BandQQ 的 AAR 里抽出 classes.jar）"
info "xms jar       : $XMS_JAR ($(size_of "$XMS_JAR"))"

# --- 高德导航 SDK ---
AMAP_JAR="$(findAmapJar || true)"
[ -n "${AMAP_JAR:-}" ] && [ -f "$AMAP_JAR" ] || die "找不到高德导航 SDK jar（期望 $SDK_ROOT/libs/*.jar）
  先运行 bash tools/fetch-deps.sh（自动下载并整理高德 SDK）"
SDK_ASSETS="$SDK_ROOT/assets"
SDK_SO_DIR="$SDK_ROOT/jni/arm64-v8a"
[ -d "$SDK_ASSETS" ] || die "找不到高德 SDK assets：$SDK_ASSETS（先运行 bash tools/fetch-deps.sh）"
[ -d "$SDK_SO_DIR" ] || die "找不到高德 SDK arm64 so：$SDK_SO_DIR（先运行 bash tools/fetch-deps.sh）"
info "高德 jar      : $AMAP_JAR ($(size_of "$AMAP_JAR"))"
info "高德 assets   : $(human_list "$SDK_ASSETS") 个文件 ($(size_of "$SDK_ASSETS"))"
info "高德 so       : $(ls -1 "$SDK_SO_DIR"/*.so 2>/dev/null | wc -l | tr -d ' ') 个 arm64 ($(size_of "$SDK_SO_DIR"))"

# --- dexer ---
DEXER=""
if declare -f detect_dexer >/dev/null 2>&1; then
  DEXER="$(DEPS_DIR="$DEPS_DIR" BUILD_DIR="$BUILD_DIR" detect_dexer 2>/dev/null || true)"
fi
[ -n "$DEXER" ] || die "找不到 dexer（d8 / R8）。
  * 装 Android build-tools（自带 d8）：sdkmanager 'build-tools;34.0.0'
  * 或者下载 R8 纯 Java 版：bash tools/fetch-deps.sh --with-r8
  * 注意：Debian 的 /usr/bin/dx 是 OpenVisualization Data Explorer，不是 Android dexer"
case "$DEXER" in
  jar:*) info "dexer         : R8 ($(echo "$DEXER" | sed 's/^jar://'))" ;;
  *)     info "dexer         : $DEXER" ;;
esac

# --- 清单回归防护 ---
[ -f "$APP/AndroidManifest.xml" ] || die "缺 AndroidManifest.xml"
grep -q 'android.permission.QUERY_ALL_PACKAGES' "$APP/AndroidManifest.xml" \
  || die "清单缺少 QUERY_ALL_PACKAGES：小米互联 SDK 会找不到小米运动健康，手环将连不上（不要删）"
grep -q 'com.amap.api.v2.apikey' "$APP/AndroidManifest.xml" \
  || die "清单缺少高德 apikey meta-data（com.amap.api.v2.apikey）"
grep -q 'com.amap.api.location.APSService' "$APP/AndroidManifest.xml" \
  || die "清单缺少高德定位 APSService"
info "清单检查      : QUERY_ALL_PACKAGES / 高德 apikey / APSService 都在"

# --- 图标 ---
if [ -n "${ICON_SRC:-}" ] && [ -d "$ICON_SRC" ]; then
  for d in mipmap-mdpi mipmap-hdpi mipmap-xhdpi mipmap-xxhdpi mipmap-xxxhdpi; do
    if [ -f "$ICON_SRC/$d/ic_launcher.png" ]; then
      mkdir -p "$APP/res/$d"
      cp -f "$ICON_SRC/$d/ic_launcher.png" "$APP/res/$d/ic_launcher.png"
    fi
  done
  info "图标          : 已从 $ICON_SRC 复制"
fi
[ -f "$APP/res/mipmap-xxxhdpi/ic_launcher.png" ] || die "缺少 App 图标：$APP/res/mipmap-xxxhdpi/ic_launcher.png"
info "图标          : $(ls "$APP"/res/mipmap-*/ic_launcher.png 2>/dev/null | wc -l | tr -d ' ') 个尺寸"

VERSION_NAME="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$APP/AndroidManifest.xml")"
VERSION_CODE="$(sed -n 's/.*android:versionCode="\([^"]*\)".*/\1/p' "$APP/AndroidManifest.xml")"
info "包名/版本     : $PKG  $VERSION_NAME ($VERSION_CODE)"

if [ "$CHECK_ONLY" -eq 1 ]; then
  echo
  if [ -f "$KS" ] && [ -f "$CERT_DIR/certificate.pem" ]; then
    echo "  签名：$KS / $CERT_DIR/certificate.pem 已就绪"
  else
    echo "  ⚠️ 签名还没准备（真正构建前需要：KS_PASS='密码' bash tools/gen-signing.sh）"
  fi
  echo "===== 环境检查通过（--check 模式，未构建）====="
  echo "  APK 构建所需工具与依赖已就绪；rpk 构建还需要 node/npm/aiot（见 tools/check-env.sh）"
  exit 0
fi

# ---------------------------------------------------------------- 1. 自测
step "1/9 图标映射自测（纯 Java，不依赖 Android / 高德 SDK）"
rm -rf "$BUILD_DIR/test"
mkdir -p "$BUILD_DIR/test"
javac -encoding UTF-8 -d "$BUILD_DIR/test" \
  "$APP/src/cn/dsh/gaodenav/NaviIconMapper.java" \
  "$APP/tools/IconMapperTest.java" 2>&1 | tail -20
[ -f "$BUILD_DIR/test/IconMapperTest.class" ] || die "自测编译失败（IconMapperTest.java）"
java -cp "$BUILD_DIR/test" IconMapperTest || die "NaviIconMapper 自测未通过"

# ---------------------------------------------------------------- 2. javac
step "2/9 编译 App 源码（javac -source 8 -target 8）"
rm -rf "$APP/out/classes"
mkdir -p "$APP/out/classes" "$BUILD_DIR/gen"
CP="$(winpath "$ANDROID_JAR")${CPSEP}$(winpath "$XMS_JAR")${CPSEP}$(winpath "$AMAP_JAR")"
find "$APP/src" -name '*.java' >"$BUILD_DIR/sources.txt"
info "源文件数：$(wc -l <"$BUILD_DIR/sources.txt" | tr -d ' ')"
# 直接把源文件列表作为参数传给 javac：@argfile 在 Windows 上按系统代码页读取，
# 仓库路径含中文（如「小米手环9」）时会抛 MalformedInputException。
JAVA_SOURCES=()
while IFS= read -r f; do
  [ -n "$f" ] && JAVA_SOURCES+=("$f")
done <"$BUILD_DIR/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -nowarn -Xlint:-options \
  -classpath "$CP" -d "$APP/out/classes" "${JAVA_SOURCES[@]}" 2>&1 | tail -40
[ -n "$(find "$APP/out/classes" -name '*.class' 2>/dev/null | head -1)" ] || die "javac 编译失败（往上翻看错误）"
info "class 文件：$(find "$APP/out/classes" -name '*.class' | wc -l | tr -d ' ') 个"

# ---------------------------------------------------------------- 3. dex
step "3/9 生成 classes.dex（D8/R8）"
rm -rf "$BUILD_DIR/dex"
mkdir -p "$BUILD_DIR/dex"
rm -f "$BUILD_DIR/app-classes.jar"
( cd "$APP/out/classes" && jar cf "$BUILD_DIR/app-classes.jar" . ) || die "jar 打包 classes 目录失败（需要 JDK 的 jar 命令）"
case "$DEXER" in
  jar:*)
    R8JAR="$(echo "$DEXER" | sed 's/^jar://')"
    java -cp "$R8JAR" com.android.tools.r8.D8 --min-api 26 --lib "$ANDROID_JAR" \
      --output "$BUILD_DIR/dex" "$BUILD_DIR/app-classes.jar" "$XMS_JAR" "$AMAP_JAR" || die "D8(R8) 失败"
    ;;
  *)
    "$DEXER" --min-api 26 --lib "$ANDROID_JAR" \
      --output "$BUILD_DIR/dex" "$BUILD_DIR/app-classes.jar" "$XMS_JAR" "$AMAP_JAR" || die "D8 失败"
    ;;
esac
[ -s "$BUILD_DIR/dex/classes.dex" ] || die "dexer 没有产出 classes.dex"
cp -f "$BUILD_DIR/dex/classes.dex" "$APP/out/classes.dex"
info "classes.dex   : $(size_of "$APP/out/classes.dex")"

# ---------------------------------------------------------------- 4. aapt
step "4/9 aapt 打包资源与清单"
rm -f "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/aligned.apk"
rm -rf "$BUILD_DIR/assets"
mkdir -p "$BUILD_DIR/assets"
if [ -d "$APP/assets" ]; then cp -rf "$APP/assets/." "$BUILD_DIR/assets/"; fi
cp -rf "$SDK_ASSETS/." "$BUILD_DIR/assets/"
info "合并后 assets：$(human_list "$BUILD_DIR/assets") 个文件 ($(size_of "$BUILD_DIR/assets"))"
# 清单里已有 uses-sdk（min 26 / target 30），带不带 --min/--target-sdk-version 结果一致；
# 老版本 aapt（如 Debian 的 8.1）可能不认这两个参数，失败时退回只用清单。
if ! aapt package -f -m -J "$BUILD_DIR/gen" -M "$APP/AndroidManifest.xml" -S "$APP/res" \
      -A "$BUILD_DIR/assets" -I "$ANDROID_JAR" -F "$BUILD_DIR/unaligned.apk" \
      --min-sdk-version 26 --target-sdk-version 30; then
  info "aapt 不接受 --min/--target-sdk-version，改用清单里的 uses-sdk 重试"
  rm -f "$BUILD_DIR/unaligned.apk"
  aapt package -f -m -J "$BUILD_DIR/gen" -M "$APP/AndroidManifest.xml" -S "$APP/res" \
    -A "$BUILD_DIR/assets" -I "$ANDROID_JAR" -F "$BUILD_DIR/unaligned.apk" \
    || die "aapt package 失败"
fi
info "未签名 APK   : $(size_of "$BUILD_DIR/unaligned.apk")"

# ---------------------------------------------------------------- 5. dex + so
step "5/9 把 classes.dex 与高德 so 放进 APK"
cp -f "$APP/out/classes.dex" "$BUILD_DIR/classes.dex"
( cd "$BUILD_DIR" && aapt add -f unaligned.apk classes.dex ) || die "aapt add classes.dex 失败"
rm -rf "$BUILD_DIR/lib"
mkdir -p "$BUILD_DIR/lib/arm64-v8a"
cp -f "$SDK_SO_DIR"/*.so "$BUILD_DIR/lib/arm64-v8a/"
( cd "$BUILD_DIR" && aapt add -f unaligned.apk lib/arm64-v8a/*.so ) || die "aapt add so 失败"
# 注意：不要写成 unzip -l ... | grep -q ...。grep -q 命中后立刻退出，
# unzip 还在往管道里写就会吃 SIGPIPE（141），在 set -o pipefail 下整条管道返回 141，
# 于是这一步会「必报」APK 里没有 classes.dex。先把清单落到文件再 grep。
APK_LIST_MID="$BUILD_DIR/unaligned-list.txt"
unzip -l "$BUILD_DIR/unaligned.apk" >"$APK_LIST_MID" 2>/dev/null || die "无法读取未签名 APK 内容"
info "APK 内 so 数 : $(grep -c 'lib/arm64-v8a/' "$APK_LIST_MID" || true)"
grep -q 'classes.dex' "$APK_LIST_MID" || die "APK 里没有 classes.dex"
grep -q 'lib/arm64-v8a/libAMapSDK_NAVI' "$APK_LIST_MID" || die "APK 里没有高德导航 .so"

# ---------------------------------------------------------------- 6. zipalign
step "6/9 zipalign（-p 保证 .so 页对齐）"
zipalign -p -f 4 "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/aligned.apk" || die "zipalign 失败"
info "对齐后 APK   : $(size_of "$BUILD_DIR/aligned.apk")"

# ---------------------------------------------------------------- 7. sign
step "7/9 apksigner 签名"
rm -f "$APP/out/$PKG.apk"
KS_ALIAS="${KS_ALIAS:-gaodenav}"
if ! apksigner sign --ks "$KS" --ks-key-alias "$KS_ALIAS" \
      --ks-pass "pass:$KSPASS" --key-pass "pass:$KSPASS" --min-sdk-version 26 \
      --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
      --out "$APP/out/$PKG.apk" "$BUILD_DIR/aligned.apk"; then
  info "v1/v2/v3 显式参数不被当前 apksigner 支持，退回默认签名方案"
  rm -f "$APP/out/$PKG.apk"
  apksigner sign --ks "$KS" --ks-key-alias "$KS_ALIAS" \
    --ks-pass "pass:$KSPASS" --key-pass "pass:$KSPASS" --min-sdk-version 26 \
    --out "$APP/out/$PKG.apk" "$BUILD_DIR/aligned.apk" || die "apksigner 签名失败"
fi
info "签名 APK     : $(size_of "$APP/out/$PKG.apk")"

# ---------------------------------------------------------------- 8. verify
step "8/9 校验签名与证书指纹"
apksigner verify --print-certs "$APP/out/$PKG.apk" >"$BUILD_DIR/verify.log" 2>&1 \
  || { cat "$BUILD_DIR/verify.log"; die "apksigner verify 失败"; }
grep -m1 -i 'SHA-256 digest' "$BUILD_DIR/verify.log"
APK_FPR="$(grep -m1 -i 'SHA-256 digest' "$BUILD_DIR/verify.log" | sed 's/^.*: *//' | tr -d ': ' | tr 'A-F' 'a-f')"
[ -n "$APK_FPR" ] || die "取不到 APK 证书 SHA-256（apksigner 输出格式变了？）"

# rpk 侧证书指纹：优先 tools/gen-signing.sh 记录的，否则现算 certificate.pem
CERT_PEM="$CERT_DIR/certificate.pem"
if [ -s "$CERT_DIR/cert-sha256.txt" ]; then
  RPK_FPR="$(tr -d ' \n\r' <"$CERT_DIR/cert-sha256.txt")"
elif [ -f "$CERT_PEM" ] && have openssl; then
  RPK_FPR="$(openssl x509 -in "$CERT_PEM" -noout -fingerprint -sha256 | sed 's/^.*=//' | tr -d ':' | tr 'A-F' 'a-f')"
elif [ -f "$CERT_PEM" ] && have python3; then
  RPK_FPR="$(python3 - "$CERT_PEM" <<'PY'
import base64, hashlib, re, sys, ssl
pem = open(sys.argv[1]).read()
der = ssl.PEM_cert_to_DER_cert(pem)
print(hashlib.sha256(der).hexdigest())
PY
)"
else
  die "既没有 $CERT_DIR/cert-sha256.txt 也没有 $CERT_PEM（先运行 bash tools/gen-signing.sh）"
fi
info "APK 证书 SHA-256: $APK_FPR"
info "rpk 证书 SHA-256: $RPK_FPR"
[ "$APK_FPR" = "$RPK_FPR" ] || die "证书不一致！APK=$APK_FPR rpk=$RPK_FPR
  小米互联会报 fingerprint verify failed —— 两端必须用同一把 keystore（见 SIGNING.md）"

# APK 内容兜底校验（不依赖 python）
APK_LIST="$BUILD_DIR/apk-list.txt"
unzip -l "$APP/out/$PKG.apk" >"$APK_LIST" 2>/dev/null || die "无法读取 APK 内容"
grep -q 'classes.dex' "$APK_LIST" || die "APK 里没有 classes.dex"
grep -q 'lib/arm64-v8a/libAMapSDK_NAVI' "$APK_LIST" || die "APK 缺少高德导航 .so"
grep -q 'assets/ae/GNaviConfig.xml' "$APK_LIST" || die "APK 缺少高德 assets（地图资源没打进去？）"
info "APK 内 so 数 : $(grep -c 'lib/arm64-v8a/' "$APK_LIST" || true)"
info "APK 大小     : $(size_of "$APP/out/$PKG.apk")（含高德 SDK，60~100MB 属正常）"
info "证书一致 ✅  classes.dex / so / assets 齐全 ✅"

# ---------------------------------------------------------------- 9. 产出
step "9/9 产出与汇总"
mkdir -p "$DIST"
cp -f "$APP/out/$PKG.apk" "$DIST/$PKG.apk"
if [ -n "$SDCARD_DEST" ]; then
  mkdir -p "$SDCARD_DEST" 2>/dev/null && cp -f "$DIST/$PKG.apk" "$SDCARD_DEST/" \
    && info "已复制到 $SDCARD_DEST" || info "复制到 $SDCARD_DEST 失败（跳过）"
fi

APK_SHA="$(sha256sum_of "$DIST/$PKG.apk")"
{
  echo "构建时间 : $(date '+%Y-%m-%d %H:%M:%S %z')"
  echo "包名     : $PKG"
  echo "版本     : $VERSION_NAME ($VERSION_CODE)"
  echo
  echo "== APK =="
  ls -l "$DIST/$PKG.apk"
  echo "sha256: $APK_SHA"
  echo
  echo "== 证书（APK 与 rpk 必须一致） =="
  echo "SHA-256: $APK_FPR"
  if [ -f "$CERT_PEM" ] && have openssl; then openssl x509 -in "$CERT_PEM" -noout -subject -dates 2>/dev/null; fi
  echo
  echo "== 工具链 =="
  java -version 2>&1 | sed -n '1,2p'
  aapt v 2>&1 | head -1
  apksigner version 2>&1 | head -1
  echo
  echo "== apksigner verify =="
  cat "$BUILD_DIR/verify.log"
} >"$BUILD_DIR/apk-summary.txt" 2>&1
cat "$BUILD_DIR/apk-summary.txt"

echo
echo "===== 完成 ====="
echo "APK : $DIST/$PKG.apk"
echo "日志: $LOG"
