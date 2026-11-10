#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验 rpk 里内嵌的签名证书是否就是你的 certificate.pem。

小米互联比对的是「APK 证书」与「rpk 内嵌证书」，两者必须同一个 keystore。
aiot 找不到工程的 sign/ 目录时会**静默**使用工具自带证书，所以打完包要跑这个校验。

用法：
    python3 tools/verify-rpk-cert.py band-app/dist/xxx.rpk keys/certificate.pem
    python3 tools/verify-rpk-cert.py band-app/dist/xxx.rpk          # 默认用 keys/certificate.pem

退出码：0 = 证书内嵌且一致；1 = 没有自定义目录/证书不一致/参数错误
"""
import base64
import os
import re
import sys

CERT_RE = re.compile(
    rb"-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", re.S
)


def _tolerant_stdio():
    """Windows 控制台默认 GBK：把无法编码的字符替换掉，而不是抛 UnicodeEncodeError。"""
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(errors="replace")  # Python 3.7+
        except Exception:
            pass


def cert_ders(pem_path):
    with open(pem_path, "rb") as f:
        data = f.read()
    ders = []
    for m in CERT_RE.finditer(data):
        try:
            ders.append(base64.b64decode(re.sub(rb"\s", b"", m.group(1))))
        except Exception:
            continue
    return ders


def main(argv):
    _tolerant_stdio()
    if len(argv) < 2 or argv[1] in ("-h", "--help"):
        print(__doc__)
        return 0 if len(argv) >= 2 else 1
    rpk_path = argv[1]
    cert_path = argv[2] if len(argv) > 2 else os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(argv[0]))), "keys", "certificate.pem"
    )
    for p in (rpk_path, cert_path):
        if not os.path.isfile(p):
            print("找不到文件: %s" % p)
            return 1
    with open(rpk_path, "rb") as f:
        rpk = f.read()
    ders = cert_ders(cert_path)
    if not ders:
        print("certificate.pem 里没有解析出证书: %s" % cert_path)
        return 1

    print("rpk : %s (%d 字节)" % (rpk_path, len(rpk)))
    print("cert: %s (%d 张证书)" % (cert_path, len(ders)))
    ok = False
    for i, der in enumerate(ders):
        pos = rpk.find(der)
        if pos >= 0:
            print("  [OK]   第 %d 张证书内嵌在 rpk 的偏移 %d" % (i + 1, pos))
            ok = True
        else:
            print("  [缺失] 第 %d 张证书没有出现在 rpk 里" % (i + 1))
    if ok:
        print("\n结论：rpk 用的是你自己的证书 [OK]")
        print("下一步：确认 APK 证书 SHA-256 与此证书一致（bash android-app/build.sh 会自动比对）")
        return 0
    print("\n结论：rpk 没有内嵌你的证书 [FAIL]")
    print("常见原因：aiot 没找到 <工程>/sign/release/private.pem，回退用了工具自带证书。")
    print("处理：bash tools/gen-signing.sh 分发 PEM，然后 (cd band-app && npx aiot release) 重打，")
    print("      并在日志里确认 privatekeyPath 指向你自己的 sign/release 目录。")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
