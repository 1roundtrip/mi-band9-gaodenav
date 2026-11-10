#!/usr/bin/env bash
# =============================================================================
# 第三方依赖获取：高德导航 SDK / 小米互联 SDK / R8（可选）
#
# 为什么仓库里不放这些文件：
#   * 高德 Android 导航 SDK、小米互联 SDK（来自 BandQQ 的 xms-wearable-lib AAR）
#     都不是本项目的代码，许可证不允许随仓库转分发；
#   * 体积也很大（高德包 236MB，解出 so+资源约 135MB）。
#   所以这里提供「自动下载 + 结构校验 + 固定落地位置」，构建脚本按固定路径读取。
#
# 用法（仓库根目录执行，Linux / macOS / WSL / Git Bash 均可）：
#   bash tools/fetch-deps.sh                     # 高德 SDK + 小米互联 AAR
#   bash tools/fetch-deps.sh --check             # 只检查是否就绪，不下载
#   bash tools/fetch-deps.sh --with-r8           # 额外下载 R8（本机没有 d8 时用它生成 dex）
#   bash tools/fetch-deps.sh --offline /path/AMap_Android_Navi_SDK_All.zip
#   bash tools/fetch-deps.sh --force             # 忽略已有缓存，全部重下
#
# 产物（默认在 third_party/，已写进 .gitignore，不会进仓库）：
#   third_party/AMapNavi/libs/<合并 jar>       高德 3DMap + Navi + Search + Location
#   third_party/AMapNavi/assets/               从 jar 里抽出的地图/导航资源（约 48MB）
#   third_party/AMapNavi/jni/arm64-v8a/*.so    7 个 arm64 so（约 87MB）
#   third_party/r8/r8.jar                      （仅 --with-r8）
#   android-app/libs/xms-wearable-classes.jar  从小米互联 AAR 解出的 classes.jar
# =============================================================================
set -euo pipefail

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$SELF_DIR/.." && pwd)"

DEPS_DIR="${DEPS_DIR:-$REPO_DIR/third_party}"
CACHE_DIR="${DEPS_CACHE_DIR:-$REPO_DIR/.cache/deps}"
XMS_JAR_DIR="$REPO_DIR/android-app/libs"
AMAP_DEST="$DEPS_DIR/AMapNavi"

AMAP_URL="${AMAP_URL:-https://amappc.cn-hangzhou.oss-pub.aliyun-inc.com/lbs/static/zip/AMap_Android_Navi_SDK_All.zip}"
XMS_URL="${XMS_URL:-https://raw.githubusercontent.com/Gsjsjzhznsz/BandQQ/main/android-sync/app/libs/xms-wearable-lib_1.4_release.aar}"
# GitHub 直连不通时用镜像前缀（换别的镜像也可以：https://gh-proxy.com/ 等）
XMS_PROXY="${XMS_PROXY:-https://ghfast.top/}"
R8_METADATA_URL="${R8_METADATA_URL:-https://maven.aliyun.com/repository/google/com/android/tools/r8/maven-metadata.xml}"
R8_BASE_URL="${R8_BASE_URL:-https://maven.aliyun.com/repository/google/com/android/tools/r8}"

DO_AMAP=1
DO_XMS=1
DO_R8=0
FORCE=0
CHECK_ONLY=0
OFFLINE_ZIP=""

say()  { printf '%s\n' "$*"; }
step() { printf '\n===== %s =====\n' "$*"; }
die()  { printf '\n[错误] %s\n' "$*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

# 判断 zip/jar 里有没有某个条目。
# 不要写成 unzip -l ... | grep -q ...：grep -q 命中后立刻退出，unzip 还在写管道会吃
# SIGPIPE（141），脚本是 set -euo pipefail，整条管道返回 141 就会被当成「校验失败」，
# 于是即使下载的 jar 完全正确也会 die。先把清单落到临时文件再 grep。
jar_has_entry() { # $1=zip/jar 文件  $2=条目路径子串
  local jar="$1" pat="$2" list rc
  list="$(mktemp "${TMPDIR:-/tmp}/jar-list-XXXXXX" 2>/dev/null || echo "${TMPDIR:-/tmp}/jar-list-$$")"
  if ! unzip -l "$jar" >"$list" 2>/dev/null; then
    rm -f "$list"
    return 2
  fi
  rc=0
  grep -q -- "$pat" "$list" || rc=1
  rm -f "$list"
  return $rc
}

usage() {
  sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'
  cat <<'EOF'

可用选项：
  --check                只检查依赖是否齐全（不下载），缺失时返回非 0
  --dir <路径>           依赖落地目录，默认 <repo>/third_party
  --cache <路径>         下载缓存目录，默认 <repo>/.cache/deps
  --offline <zip>        用本地的高德 SDK zip（不联网下载高德包）
  --with-r8              额外下载 R8（没有 d8 时用来生成 classes.dex）
  --skip-amap            跳过高德 SDK
  --skip-xms             跳过小米互联 AAR
  --force                忽略已有文件，重新下载/解压
  -h, --help             显示本帮助
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --check)      CHECK_ONLY=1 ;;
    --with-r8)    DO_R8=1 ;;
    --skip-amap)  DO_AMAP=0 ;;
    --skip-xms)   DO_XMS=0 ;;
    --force)      FORCE=1 ;;
    --dir)        [ $# -ge 2 ] || die "--dir 需要一个路径参数"; DEPS_DIR="$2"; AMAP_DEST="$DEPS_DIR/AMapNavi"; shift ;;
    --cache)      [ $# -ge 2 ] || die "--cache 需要一个路径参数"; CACHE_DIR="$2"; shift ;;
    --offline)    [ $# -ge 2 ] || die "--offline 需要一个 zip 路径"; OFFLINE_ZIP="$2"; shift ;;
    -h|--help)    usage; exit 0 ;;
    *)            die "未知参数：$1（用 --help 看用法）" ;;
  esac
  shift
done

mkdir -p "$DEPS_DIR" "$CACHE_DIR" "$XMS_JAR_DIR"

# ------------------------------------------------------------------ 工具函数
fetch_url() { # $1=url $2=输出文件
  local url="$1" out="$2"
  mkdir -p "$(dirname "$out")"
  say "  GET $url"
  if have curl; then
    curl -fL --retry 3 --retry-delay 2 --connect-timeout 20 --progress-bar -o "$out.part" "$url" \
      || { rm -f "$out.part"; return 1; }
  elif have wget; then
    wget -O "$out.part" "$url" || { rm -f "$out.part"; return 1; }
  else
    die "需要 curl 或 wget 才能下载（也可以手动下载后用 --offline 指定本地文件）"
  fi
  [ -s "$out.part" ] || { rm -f "$out.part"; return 1; }
  mv -f "$out.part" "$out"
}

sha256_of() {
  if have sha256sum; then sha256sum "$1" | awk '{print $1}'
  elif have shasum; then shasum -a 256 "$1" | awk '{print $1}'
  elif have openssl; then openssl dgst -sha256 "$1" | sed 's/^.*= *//'
  else echo "unknown"
  fi
}

human_size() {
  if have du; then du -sh "$1" 2>/dev/null | awk '{print $1}'; else echo "?"; fi
}

amap_jar_path() {
  ls -1 "$AMAP_DEST"/libs/*.jar 2>/dev/null | head -1
}

amap_so_count() {
  ls -1 "$AMAP_DEST"/jni/arm64-v8a/*.so 2>/dev/null | wc -l | tr -d ' '
}

amap_asset_count() {
  find "$AMAP_DEST/assets" -type f 2>/dev/null | wc -l | tr -d ' '
}

xms_jar_ok() {
  local j="$XMS_JAR_DIR/xms-wearable-classes.jar"
  [ -s "$j" ] || return 1
  have unzip || return 0
  jar_has_entry "$j" 'com/xiaomi/xms/wearable/Wearable.class'
}

amap_ready() {
  local jar; jar="$(amap_jar_path)"
  [ -n "$jar" ] && [ -s "$jar" ] || return 1
  [ -d "$AMAP_DEST/assets" ] && [ "$(amap_asset_count)" -ge 500 ] || return 1
  [ "$(amap_so_count)" -ge 5 ] || return 1
}

# ------------------------------------------------------------------ 依赖检查
print_status() {
  step "依赖状态（$DEPS_DIR）"
  local jar; jar="$(amap_jar_path)"
  if amap_ready; then
    say "  [OK]   高德导航 SDK"
    say "         jar   : $jar ($(human_size "$jar"))"
    say "         assets: $(amap_asset_count) 个文件 ($(human_size "$AMAP_DEST/assets"))"
    say "         so    : $(amap_so_count) 个 arm64 ($(human_size "$AMAP_DEST/jni/arm64-v8a"))"
  else
    say "  [缺失] 高德导航 SDK —— 运行 bash tools/fetch-deps.sh"
  fi
  if xms_jar_ok; then
    say "  [OK]   小米互联 SDK：$XMS_JAR_DIR/xms-wearable-classes.jar ($(human_size "$XMS_JAR_DIR/xms-wearable-classes.jar"))"
  else
    say "  [缺失] 小米互联 SDK —— 运行 bash tools/fetch-deps.sh"
  fi
  if [ -s "$DEPS_DIR/r8/r8.jar" ]; then
    say "  [OK]   R8：$DEPS_DIR/r8/r8.jar ($(human_size "$DEPS_DIR/r8/r8.jar"))"
  else
    say "  [--]   R8 未下载（只有本机没有 d8 时才需要：--with-r8）"
  fi
}

require_unzip() {
  have unzip || die "需要 unzip 解压 SDK 包（apt install unzip / brew install unzip）"
}

# ------------------------------------------------------------------ 高德 SDK
fetch_amap() {
  step "1/3 高德 Android 导航 SDK"
  if amap_ready && [ "$FORCE" -eq 0 ]; then
    say "  已就绪，跳过（--force 可强制重下）"
    return 0
  fi
  require_unzip
  local zip="$CACHE_DIR/AMap_Android_Navi_SDK_All.zip"
  if [ -n "$OFFLINE_ZIP" ]; then
    [ -f "$OFFLINE_ZIP" ] || die "--offline 指定的文件不存在：$OFFLINE_ZIP"
    say "  使用本地包：$OFFLINE_ZIP"
    cp -f "$OFFLINE_ZIP" "$zip"
  elif [ ! -s "$zip" ] || [ "$FORCE" -eq 1 ]; then
    fetch_url "$AMAP_URL" "$zip" || die "高德 SDK 下载失败，可手动下载后用 --offline 指定"
  else
    say "  使用缓存：$zip ($(human_size "$zip"))"
  fi

  say "  校验 zip 完整性…"
  unzip -tqq "$zip" >/dev/null 2>&1 || die "下载的 zip 已损坏：$zip（删掉后重试）"

  local work="$CACHE_DIR/amap-work"
  rm -rf "$work"
  mkdir -p "$work/outer" "$work/inner"
  say "  解压外层包…"
  unzip -q "$zip" -d "$work/outer"

  local inner_zip
  inner_zip="$(find "$work/outer" -maxdepth 1 -name 'AMap3DMap*.zip' -type f | head -1)"
  [ -n "$inner_zip" ] || die "高德包结构异常：外层 zip 里没找到 AMap3DMap*.zip"
  say "  解压内层包：$(basename "$inner_zip")"
  unzip -q "$inner_zip" -d "$work/inner"

  local jar so_dir
  jar="$(find "$work/inner" -maxdepth 2 -name '*.jar' -type f | head -1)"
  [ -n "$jar" ] || die "高德包结构异常：内层 zip 里没找到合并 jar"
  so_dir="$(find "$work/inner" -maxdepth 2 -type d -name 'arm64-v8a' | head -1)"
  [ -n "$so_dir" ] || die "高德包结构异常：内层 zip 里没找到 arm64-v8a/*.so"

  say "  组装到 $AMAP_DEST …"
  rm -rf "$AMAP_DEST"
  mkdir -p "$AMAP_DEST/libs" "$AMAP_DEST/assets" "$AMAP_DEST/jni/arm64-v8a"
  cp -f "$jar" "$AMAP_DEST/libs/"
  cp -f "$so_dir"/*.so "$AMAP_DEST/jni/arm64-v8a/"

  say "  从 jar 里抽出 assets（地图/导航资源）…"
  # 注意：unzip 的通配符 * 不跨目录（'assets/*' 只会解出顶层 45 个），
  # 所以这里整包解压后只取 assets/ 目录。
  unzip -q "$jar" -d "$work/jarx"
  if [ -d "$work/jarx/assets" ]; then
    cp -rf "$work/jarx/assets/." "$AMAP_DEST/assets/"
  fi
  rm -rf "$work/jarx"

  amap_ready || die "组装后自检失败（jar/assets/so 数量不对），请检查 SDK 版本是否变化"
  say "  完成：$(basename "$jar") / assets $(amap_asset_count) 个 / so $(amap_so_count) 个"
  {
    echo "source: $AMAP_URL"
    echo "downloaded: $(date '+%Y-%m-%d %H:%M:%S %z')"
    echo "jar: $(basename "$jar")"
    echo "jar_sha256: $(sha256_of "$AMAP_DEST/libs/$(basename "$jar")")"
    echo "assets_files: $(amap_asset_count)"
    echo "jni_arm64_so: $(amap_so_count)"
  } >"$AMAP_DEST/SDK-VERSION.txt"
  rm -rf "$work"
}

# ------------------------------------------------------------------ 小米互联
fetch_xms() {
  step "2/3 小米互联 SDK（BandQQ 的 xms-wearable-lib）"
  if xms_jar_ok && [ "$FORCE" -eq 0 ]; then
    say "  已就绪，跳过（--force 可强制重下）"
    return 0
  fi
  require_unzip
  local aar="$CACHE_DIR/xms-wearable-lib_1.4_release.aar"
  if [ ! -s "$aar" ] || [ "$FORCE" -eq 1 ]; then
    if ! fetch_url "$XMS_URL" "$aar"; then
      say "  直连失败，改用镜像：$XMS_PROXY"
      fetch_url "$XMS_PROXY$XMS_URL" "$aar" || die "小米互联 AAR 下载失败（GitHub 被墙时可用 XMS_PROXY 换镜像，或手动下载后放到 $aar）"
    fi
  else
    say "  使用缓存：$aar ($(human_size "$aar"))"
  fi
  local tmp="$CACHE_DIR/xms-classes.jar"
  unzip -p "$aar" classes.jar >"$tmp" || die "从 AAR 里取 classes.jar 失败：$aar"
  [ -s "$tmp" ] || die "AAR 里的 classes.jar 是空的"
  if have unzip; then
    jar_has_entry "$tmp" 'com/xiaomi/xms/wearable/Wearable.class' \
      || die "classes.jar 里没有 com.xiaomi.xms.wearable.Wearable —— AAR 版本可能变了"
  fi
  mv -f "$tmp" "$XMS_JAR_DIR/xms-wearable-classes.jar"
  say "  完成：android-app/libs/xms-wearable-classes.jar ($(human_size "$XMS_JAR_DIR/xms-wearable-classes.jar"))"
  {
    echo "source: $XMS_URL"
    echo "downloaded: $(date '+%Y-%m-%d %H:%M:%S %z')"
    echo "aar_sha256: $(sha256_of "$aar")"
  } >"$XMS_JAR_DIR/xms-wearable-classes.txt"
}

# ------------------------------------------------------------------ R8
fetch_r8() {
  step "3/3 R8（可选的 dexer，本机没有 d8 时使用）"
  local out="$DEPS_DIR/r8/r8.jar"
  if [ -s "$out" ] && [ "$FORCE" -eq 0 ]; then
    say "  已就绪，跳过：$out ($(human_size "$out"))"
    return 0
  fi
  require_unzip
  have curl || die "下载 R8 需要 curl"
  mkdir -p "$DEPS_DIR/r8"
  say "  查询 R8 最新版本…"
  local meta="$CACHE_DIR/r8-metadata.xml"
  fetch_url "$R8_METADATA_URL" "$meta" || die "取不到 R8 版本列表：$R8_METADATA_URL"
  # 优先用最新「稳定版」：maven 的 <latest> 常常是 alpha（例如 9.6.0-alpha12），
  # 拿来做 dexer 不稳定，也可能和 R8 的 CLI/API 变化对不上。
  local ver
  ver="$(sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' "$meta" \
    | grep -Ev 'alpha|beta|rc|dev|SNAPSHOT' | tail -1)"
  [ -n "$ver" ] || ver="$(awk -F'[<>]' '/<latest>/{print $3; exit}' "$meta" 2>/dev/null)"
  [ -n "$ver" ] || ver="$(awk -F'[<>]' '/<version>/{v=$3} END{if(v!="") print v}' "$meta" 2>/dev/null)"
  [ -n "$ver" ] || die "解析 R8 版本号失败"
  say "  版本：$ver（优先稳定版）"
  fetch_url "$R8_BASE_URL/$ver/r8-$ver.jar" "$out.part" || die "R8 下载失败"
  mv -f "$out.part" "$out"
  jar_has_entry "$out" 'com/android/tools/r8/D8.class' || die "下载的 R8 jar 结构不对"
  say "  完成：$out ($(human_size "$out"))"
}

# ------------------------------------------------------------------ 主流程
say "仓库目录：$REPO_DIR"
say "依赖目录：$DEPS_DIR"
say "缓存目录：$CACHE_DIR"

if [ "$CHECK_ONLY" -eq 1 ]; then
  print_status
  if amap_ready && { [ "$DO_XMS" -eq 0 ] || xms_jar_ok; }; then
    say ""
    say "依赖齐全 ✅"
    exit 0
  fi
  say ""
  say "依赖不齐 ❌ 运行：bash tools/fetch-deps.sh"
  exit 1
fi

[ "$DO_AMAP" -eq 1 ] && fetch_amap
[ "$DO_XMS" -eq 1 ] && fetch_xms
[ "$DO_R8" -eq 1 ] && fetch_r8

print_status
say ""
say "依赖就绪 ✅  下一步："
say "  1) bash tools/gen-signing.sh      # 生成 APK/rpk 统一签名（需要 KS_PASS）"
say "  2) bash android-app/build.sh      # 只打 APK"
say "  3) bash tools/build-all.sh        # 或一键：检查 + 签名 + APK + 两个机型 rpk"
