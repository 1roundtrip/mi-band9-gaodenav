#!/usr/bin/env bash
# =============================================================================
# 公共探测函数（被 android-app/build.sh 与 tools/check-env.sh source 引入）
#   * detect_android_jar        找到 android.jar（javac / aapt 需要的 Android 平台）
#   * detect_build_tools_dir    找到 Android SDK build-tools 目录（aapt/zipalign/apksigner/d8）
#   * detect_dexer              找到 dexer：d8 命令，或 R8 jar（纯 Java 调用）
#   * require_tool              必需命令缺失时给出安装提示
# =============================================================================

# 常见 Android SDK 根目录候选（按优先级）
_detect_sdk_bases() {
  {
    [ -n "${ANDROID_HOME:-}" ] && printf '%s\n' "$ANDROID_HOME"
    [ -n "${ANDROID_SDK_ROOT:-}" ] && printf '%s\n' "$ANDROID_SDK_ROOT"
    [ -n "${LOCALAPPDATA:-}" ] && printf '%s\n' "$LOCALAPPDATA/Android/Sdk"
    printf '%s\n' "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" \
      "/usr/lib/android-sdk" "/opt/android-sdk" "/usr/local/share/android-sdk" \
      "/opt/homebrew/share/android-commandlinetools" "/snap/android-sdk/current"
  } | awk 'NF && !seen[$0]++'
}

# 打印 android.jar 路径；找不到返回 1
detect_android_jar() {
  if [ -n "${ANDROID_JAR:-}" ]; then
    if [ -f "$ANDROID_JAR" ]; then printf '%s\n' "$ANDROID_JAR"; return 0; fi
    return 1
  fi
  local base api jar
  for api in 35 34 33 32 31 30 29 28 27 26 25 24 23 22 21; do
    while IFS= read -r base; do
      [ -n "$base" ] || continue
      jar="$base/platforms/android-$api/android.jar"
      if [ -f "$jar" ]; then printf '%s\n' "$jar"; return 0; fi
    done <<EOF
$(_detect_sdk_bases)
EOF
  done
  # 兜底：任意已安装 platform
  while IFS= read -r base; do
    [ -n "$base" ] || continue
    if [ -d "$base/platforms" ]; then
      jar="$(ls -1d "$base"/platforms/*/android.jar 2>/dev/null | head -1)"
      if [ -n "$jar" ]; then printf '%s\n' "$jar"; return 0; fi
    fi
  done <<EOF
$(_detect_sdk_bases)
EOF
  return 1
}

# 打印 build-tools 目录（含 aapt / zipalign / apksigner / d8）；找不到返回 1
detect_build_tools_dir() {
  if [ -n "${ANDROID_BUILD_TOOLS:-}" ] && [ -d "$ANDROID_BUILD_TOOLS" ]; then
    printf '%s\n' "$ANDROID_BUILD_TOOLS"; return 0
  fi
  local base dir
  while IFS= read -r base; do
    [ -n "$base" ] || continue
    if [ -d "$base/build-tools" ]; then
      dir="$(ls -1d "$base"/build-tools/*/ 2>/dev/null | sed 's:/$::' | sort -V 2>/dev/null | tail -1)"
      [ -n "$dir" ] || dir="$(ls -1d "$base"/build-tools/*/ 2>/dev/null | sed 's:/$::' | tail -1)"
      if [ -n "$dir" ] && { [ -x "$dir/aapt" ] || [ -x "$dir/aapt.exe" ]; }; then
        printf '%s\n' "$dir"; return 0
      fi
    fi
  done <<EOF
$(_detect_sdk_bases)
EOF
  return 1
}

# 打印 dexer 描述： "d8" 或 "jar:/path/to/r8.jar"；找不到返回 1
detect_dexer() {
  if command -v d8 >/dev/null 2>&1 && d8 --version >/dev/null 2>&1; then
    printf '%s\n' "d8"; return 0
  fi
  if command -v d8.bat >/dev/null 2>&1; then
    printf '%s\n' "d8.bat"; return 0
  fi
  local cand
  for cand in "${R8_JAR:-}" "${DEPS_DIR:-}/r8/r8.jar" "/opt/r8/r8.jar" "${BUILD_DIR:-}/r8.jar"; do
    if [ -n "$cand" ] && [ -s "$cand" ]; then printf '%s\n' "jar:$cand"; return 0; fi
  done
  return 1
}

# 必需命令检查：require_tool <命令> <安装提示>
require_tool() {
  if command -v "$1" >/dev/null 2>&1; then return 0; fi
  printf '缺少命令：%s\n  %s\n' "$1" "$2" >&2
  return 1
}
