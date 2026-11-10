# 签名教程（APK 与 RPK 两端统一）

小米互联会校验「APK 证书」与「手环端 rpk 证书」是否一致，不一致会抛
`SignatureVerifyFailedException: fingerprint verify failed`。
所以：**用同一个 keystore 给 APK 和 rpk 签名**，并且 rpk 里内嵌的证书必须和 APK 一致。

本教程所有密码都请自己设定，**不要写进仓库**；`.jks`、`*.p12`、`private.pem` 一律加入 `.gitignore`。

---

## 0. 一条命令（推荐）

```bash
export KS_PASS='你的密码'
bash tools/gen-signing.sh
```

它会把下面第 1~3 节全部做完：

- 生成 `keys/release.jks`（alias `gaodenav`，用于 apksigner 与 aiot）
- 导出 `keys/private.pem` + `keys/certificate.pem`
- 复制 PEM 到 `band-app/sign/{debug,release}/` 与 `band-app-pro/sign/{debug,release}/`
- 打印 SHA1 / SHA256 / MD5（写入 `keys/fingerprints.txt`），并把证书 SHA-256 写进
  `keys/cert-sha256.txt` 供 `build.sh` 做「APK == rpk」一致性校验

已有 keystore 只想导出 PEM / 指纹：

```bash
bash tools/gen-signing.sh --from ~/keys/release.jks
```

Windows：

```powershell
powershell -ExecutionPolicy Bypass -File tools\gen-signing.ps1 -Pass '你的密码'
```

下面是手工步骤，了解原理或需要自定义时用。

---

## 1. 生成 keystore

```bash
mkdir -p keys
keytool -genkeypair -v \
  -keystore keys/release.jks -storetype JKS \
  -alias gaodenav \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=BandNav, OU=Dev, O=YourOrg, L=Beijing, ST=Beijing, C=CN" \
  -storepass "$KS_PASS" -keypass "$KS_PASS"
```

说明：
- `-alias gaodenav`：别名，后面 apksigner / aiot 都用这个（`tools/gen-signing.sh` 与
  `android-app/build.sh` 的默认别名也是 `gaodenav`；自定义时两边要用同一个 `KS_ALIAS`）
- `-validity 10000`：约 27 年
- 密码用环境变量传入（`export KS_PASS='...'`），避免留在 shell 历史里

## 2. 查看证书指纹（SHA1 / SHA256 / MD5）

```bash
keytool -list -v -keystore keys/release.jks -alias gaodenav -storepass "$KS_PASS"
```

输出里：
- `SHA1: 28:83:...`（**高德 Key 要填这个，带冒号大写**）
- `SHA256: D6:C6:...`
- `MD5: BC:17:...`

也可以直接从 APK 看（最准确，是用户手机实际装的那份）：

```bash
apksigner verify --print-certs dist/cn.dsh.gaodenav.apk
```

## 3. keystore → PKCS12 → PEM（给手环端用）

`aiot-toolkit` 签 rpk 需要 `private.pem` + `certificate.pem`：

```bash
# jks -> p12
keytool -importkeystore -noprompt \
  -srckeystore keys/release.jks -srcstoretype JKS -srcalias gaodenav \
  -srcstorepass "$KS_PASS" \
  -destkeystore keys/release.p12 -deststoretype PKCS12 \
  -deststorepass "$KS_PASS" -destkeypass "$KS_PASS"

# p12 -> private.pem（PKCS8 / 无加密私钥）
openssl pkcs12 -in keys/release.p12 -nocerts -nodes \
  -passin "pass:$KS_PASS" -out keys/private.pem

# p12 -> certificate.pem
openssl pkcs12 -in keys/release.p12 -nokeys -clcerts \
  -passin "pass:$KS_PASS" -out keys/certificate.pem

# 校验
openssl x509 -in keys/certificate.pem -noout -subject -fingerprint -sha256
```

把两个 PEM 复制到手环工程：

```bash
cp keys/private.pem keys/certificate.pem band-app/sign/debug/
cp keys/private.pem keys/certificate.pem band-app/sign/release/
```

> `sign/debug` 与 `sign/release` 放**同一套**证书，保证调试包/正式包证书一致。

## 4. 打 RPK 并检查签名

```bash
cd band-app
aiot build      # 调试包，使用 sign/debug
aiot release    # 正式包，使用 sign/release
```

**必须检查日志**（`aiot` 找不到自己的 sign 目录会静默回退到工具自带证书）：

```
privatekeyPath is  <你的项目>/band-app/sign/release/private.pem
certificatePath is <你的项目>/band-app/sign/release/certificate.pem
```

出现 `.../aiotpack/lib/compiler/javascript/vela/utils/signature/pem/private.pem` 这类路径 = **用错了默认证书**，rpk 与 APK 证书会不一致。此时要检查：
- `sign/debug/private.pem`、`sign/debug/certificate.pem` 是否存在且非空
- release 模式只认 `sign/release/`（或 `sign/` 根目录），`sign/debug` 不参与

### 硬校验：证书是否真的内嵌进 rpk

仓库自带校验脚本（就是下面这段逻辑的可执行版本）：

```bash
python3 tools/verify-rpk-cert.py band-app/dist/<产物>.rpk keys/certificate.pem
```

输出「rpk 用的是你自己的证书 ✅」才算通过。原理：rpk 的签名块里内嵌证书 DER，直接在字节里查找：

```bash
python3 - <<'PY'
import base64, re, sys
rpk = open('band-app/dist/xxx.rpk','rb').read()
pem = open('keys/certificate.pem','rb').read()
der = base64.b64decode(re.sub(rb'\s', b'', re.search(
    rb'-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----', pem, re.S).group(1)))
print('cert_der_in_rpk =', der in rpk)
sys.exit(0 if der in rpk else 1)
PY
```

输出 `True` 才算 rpk 用的是你的证书。

## 5. APK 用同一 keystore 签名

```bash
# 先 zipalign（-p 让 .so 页对齐）
zipalign -p -f 4 out/unaligned.apk out/aligned.apk

apksigner sign \
  --ks keys/release.jks --ks-key-alias gaodenav \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --min-sdk-version 26 \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out dist/cn.dsh.gaodenav.apk out/aligned.apk

apksigner verify --print-certs dist/cn.dsh.gaodenav.apk
```

说明：
- `minSdk 26` 时默认 v1 不是必需项，`apksigner verify -v` 对 v1 可能显示 false；加 `--min-sdk-version 21` 复查可看到 `v1 scheme (JAR signing): true`
- 部分 SDK（含高德早期签名校验）会读 `META-INF/*.RSA`（v1 签名），所以建议 **v1+v2+v3 都开**

## 6. 两端一致性检查

| 检查项 | 命令 / 方法 |
| --- | --- |
| APK 证书 SHA256 | `apksigner verify --print-certs dist/cn.dsh.gaodenav.apk` |
| rpk 证书 SHA256 | `openssl x509 -in keys/certificate.pem -noout -fingerprint -sha256` |
| 两者是否一致 | 两个 SHA256 必须完全相同（忽略大小写与冒号） |
| rpk 内嵌证书 | 第 4 节的 Python 校验返回 `True` |
| 包名一致 | APK `AndroidManifest.xml` 的 `package` == rpk `src/manifest.json` 的 `package` |
| App 内自检 | App →「诊断」弹窗：签名 SHA1/256/MD5、运行时 Key、包名、版本 |

## 7. 高德 Key 配置

1. 高德开放平台控制台 → 创建应用 → 添加 Key
2. 服务类型选 **Android 平台 SDK**
3. **Package**：与 APK 包名一致
4. **发布版安全码 SHA1 / 调试版安全码 SHA1**：都填 `keytool` 输出的 **带冒号大写** SHA1（类似
   `AA:BB:CC:DD:...:FF` 的 40 位十六进制，以你自己 keystore 的实际值为准），不要有空格
5. 保存后 **等 5~10 分钟** 生效
6. 改包名或换签名后，Key 必须重新绑定（否则报 `INVALID_USER_SCODE` / code=22）

排查顺序：App「诊断」里的实际 SHA1 → 与控制台逐位对比 → 确认保存成功 → 等生效 → 重开 App 试算路。
