#!/usr/bin/env bash
# =============================================================================
# 生成 / 导出 APK 与 RPK 的统一签名
#
# 为什么必须统一：
#   小米互联会拿「APK 证书指纹」和「手环端 rpk 证书指纹」比对，不一致就抛
#   SignatureVerifyFailedException: fingerprint verify failed。所以两端必须是同一把 keystore。
#
# 用法：
#   KS_PASS='你的密码' bash tools/gen-signing.sh          # 生成新 keystore 并导出 PEM
#   KS_PASS='你的密码' bash tools/gen-signing.sh --force  # 覆盖已有 keystore（慎用：已发布的包无法覆盖升级）
#   bash tools/gen-signing.sh --from ~/my/release.jks      # 用自己的现成 keystore 只导出 PEM
#
# 产物（默认 keys/，已写进 .gitignore）：
#   keys/release.jks         keystore（apksigner 用）
#   keys/release.p12         中间格式
#   keys/private.pem         aiot 打包 rpk 用（PKCS#8 无加密私钥）
#   keys/certificate.pem     证书（APK 与 rpk 共用）
#   keys/cert-sha256.txt     证书 SHA-256（无冒号小写，build.sh 读它做一致性校验）
#   keys/fingerprints.txt    SHA1/SHA256/MD5 人类可读指纹（高德 Key 要填带冒号大写 SHA1）
#   同时复制 private.pem/certificate.pem 到 band-app 与 band-app-pro 的 sign/{debug,release}/
# =============================================================================
set -euo pipefail

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SELF_DIR/.." && pwd)"

OUT_DIR="${OUT_DIR:-$ROOT/keys}"
ALIAS="${KS_ALIAS:-gaodenav}"
KS_PASS="${KS_PASS:-}"
FROM_KS=""
FORCE=0
DNAME="${KS_DNAME:-CN=BandNav, OU=Dev, O=YourOrg, L=Beijing, ST=Beijing, C=CN}"

say()  { printf '%s\n' "$*"; }
step() { printf '\n===== %s =====\n' "$*"; }
die()  { printf '\n[错误] %s\n' "$*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

usage() {
  cat <<'EOF'
用法：
  KS_PASS='密码' bash tools/gen-signing.sh [选项]

选项：
  --out <目录>      输出目录（默认 <repo>/keys）
  --alias <别名>    keystore 别名（默认 gaodenav，apksigner 与 aiot 都用它）
  --dname <DN>      keytool 的 -dname（默认 CN=BandNav, OU=Dev, O=YourOrg, L=Beijing, ST=Beijing, C=CN）
  --from <jks>      使用已有 keystore，只在本地导出 PEM / 指纹
  --force           已存在 keystore 时重新生成（会让已发布的包无法覆盖升级，慎用）
  -h, --help        显示本帮助
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --out)    [ $# -ge 2 ] || die "--out 需要路径"; OUT_DIR="$2"; shift ;;
    --alias)  [ $# -ge 2 ] || die "--alias 需要别名"; ALIAS="$2"; shift ;;
    --dname)  [ $# -ge 2 ] || die "--dname 需要 DN"; DNAME="$2"; shift ;;
    --from)   [ $# -ge 2 ] || die "--from 需要 jks 路径"; FROM_KS="$2"; shift ;;
    --force)  FORCE=1 ;;
    -h|--help) usage; exit 0 ;;
    *) die "未知参数：$1（--help 看用法）" ;;
  esac
  shift
done

step "0/5 检查工具"
have keytool || die "缺少 keytool（JDK 自带：apt install openjdk-17-jdk-headless / brew install openjdk@17）"
have openssl || die "缺少 openssl（导出 PEM 用：apt install openssl / brew install openssl；Windows 用 Git Bash 自带的 openssl）"
[ -n "$KS_PASS" ] || die "请先设置密码：export KS_PASS='你的密码'（不要提交到仓库）"
say "  keytool : $(command -v keytool)"
say "  openssl : $(command -v openssl)"

mkdir -p "$OUT_DIR"
KS="$OUT_DIR/release.jks"
P12="$OUT_DIR/release.p12"
PRIV="$OUT_DIR/private.pem"
CERT="$OUT_DIR/certificate.pem"

if [ -n "$FROM_KS" ]; then
  [ -f "$FROM_KS" ] || die "找不到 --from 指定的 keystore：$FROM_KS"
  cp -f "$FROM_KS" "$KS"
  say "  使用已有 keystore：$FROM_KS -> $KS"
fi

step "1/5 keystore"
if [ -f "$KS" ] && [ "$FORCE" -eq 0 ]; then
  say "  已存在，复用：$KS（--force 可重新生成）"
else
  rm -f "$KS"
  keytool -genkeypair -v -keystore "$KS" -storetype JKS -alias "$ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "$DNAME" \
    -storepass "$KS_PASS" -keypass "$KS_PASS" >/dev/null 2>&1 \
    || die "keytool 生成 keystore 失败（密码至少 6 位）"
  say "  已生成：$KS（alias=$ALIAS，有效期 10000 天）"
fi

step "2/5 jks -> p12"
rm -f "$P12"
keytool -importkeystore -noprompt \
  -srckeystore "$KS" -srcstoretype JKS -srcalias "$ALIAS" -srcstorepass "$KS_PASS" \
  -destkeystore "$P12" -deststoretype PKCS12 -deststorepass "$KS_PASS" -destkeypass "$KS_PASS" \
  >/dev/null 2>&1 || die "jks -> p12 转换失败（别名或密码不对？）"
say "  已生成：$P12"

step "3/5 p12 -> PEM"
rm -f "$PRIV" "$CERT"
openssl pkcs12 -in "$P12" -nocerts -nodes -passin "pass:$KS_PASS" -out "$PRIV" 2>/dev/null \
  || die "导出 private.pem 失败"
openssl pkcs12 -in "$P12" -nokeys -clcerts -passin "pass:$KS_PASS" -out "$CERT" 2>/dev/null \
  || die "导出 certificate.pem 失败"
grep -q 'BEGIN PRIVATE KEY\|BEGIN RSA PRIVATE KEY' "$PRIV" || die "private.pem 内容异常"
grep -q 'BEGIN CERTIFICATE' "$CERT" || die "certificate.pem 内容异常"
say "  已生成：$PRIV"
say "  已生成：$CERT"

step "4/5 指纹"
FP_LOG="$OUT_DIR/fingerprints.txt"
keytool -list -v -keystore "$KS" -alias "$ALIAS" -storepass "$KS_PASS" 2>/dev/null \
  | grep -E 'SHA1:|SHA256:|MD5:|Owner:|Valid from' | sed 's/^[[:space:]]*//' >"$FP_LOG" || true
# 用 sed -n '1p' 而不是 head -1：head 提前退出会让上游 sed 吃 SIGPIPE，
# 在 set -euo pipefail 下这条赋值会直接失败。
SHA1="$(sed -n 's/^SHA1: *//p' "$FP_LOG" | sed -n '1p')"
SHA256="$(sed -n 's/^SHA256: *//p' "$FP_LOG" | sed -n '1p')"
MD5="$(sed -n 's/^MD5: *//p' "$FP_LOG" | sed -n '1p')"
if [ -z "$MD5" ] && have openssl; then
  MD5="$(openssl x509 -in "$CERT" -noout -fingerprint -md5 2>/dev/null | sed 's/^.*=//')"
fi
SHA256_PLAIN="$(printf '%s' "$SHA256" | tr -d ': ' | tr 'A-F' 'a-f')"
[ -n "$SHA256_PLAIN" ] || SHA256_PLAIN="$(openssl x509 -in "$CERT" -noout -fingerprint -sha256 | sed 's/^.*=//' | tr -d ':' | tr 'A-F' 'a-f')"
printf '%s\n' "$SHA256_PLAIN" >"$OUT_DIR/cert-sha256.txt"
say "  SHA1   : $SHA1"
say "  SHA256 : $SHA256"
[ -n "$MD5" ] && say "  MD5    : $MD5"
say "  （高德开放平台 Key 要填上面这串带冒号大写的 SHA1）"

step "5/5 分发到两个手环工程"
for d in band-app band-app-pro; do
  for mode in debug release; do
    dst="$ROOT/$d/sign/$mode"
    mkdir -p "$dst"
    cp -f "$PRIV" "$dst/private.pem"
    cp -f "$CERT" "$dst/certificate.pem"
  done
  say "  $d/sign/{debug,release}/private.pem + certificate.pem"
done

echo
say "完成 ✅"
say "  keystore : $KS"
say "  PEM      : $PRIV / $CERT"
say "  指纹文件 : $FP_LOG / $OUT_DIR/cert-sha256.txt"
say ""
say "下一步："
say "  export KS_FILE=\"$KS\""
say "  export KS_PASS='你的密码'"
say "  bash android-app/build.sh        # 打 APK"
say "  (cd band-app && npm install && npx aiot release)"
say ""
say "⚠️  keys/ 与 *.pem 已写进 .gitignore：绝不提交。换签名会让已安装的包无法覆盖升级。"
