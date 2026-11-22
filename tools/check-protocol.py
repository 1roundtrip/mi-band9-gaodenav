#!/usr/bin/env python3
"""校验手机端 NavFrame.TURNS 与全部手环端 ICONS 的键集合一致。

协议漂移（一端加了新 turn、另一端忘了加图标）在运行时表现为手环显示默认
直行图标或动作错乱，且很难从日志里看出来。这个检查放进 CI，改动协议时
第一时间就能暴露。

用法：python3 tools/check-protocol.py
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

NAVFRAME = os.path.join(ROOT, "android-app", "src", "cn", "dsh", "gaodenav", "NavFrame.java")
BAND_APPS = [
    "band-app", "band-app-pro", "band-app-8", "band-app-8-pro",
    "band-app-10", "band-app-10-pro", "band-app-11",
]
BAND_UX = [
    os.path.join(ROOT, d, "src", "pages", "index", "index.ux") for d in BAND_APPS
]


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def java_turns():
    src = read(NAVFRAME)
    m = re.search(r"TURNS\s*=\s*\{(.*?)\}", src, re.S)
    if not m:
        die("NavFrame.java 里找不到 TURNS 数组")
    turns = re.findall(r'"([a-z-]+)"', m.group(1))
    if not turns:
        die("NavFrame.TURNS 解析结果为空")
    return set(turns)


def band_keys(path):
    src = read(path)
    m = re.search(r"const\s+ICONS\s*=\s*\{(.*?)\n\}", src, re.S)
    if not m:
        die("找不到 ICONS 定义: " + path)
    body = m.group(1)
    keys = re.findall(r"^\s*(?:'([^']+)'|([a-z][a-z0-9-]*))\s*:", body, re.M)
    result = set(a or b for a, b in keys)
    if not result:
        die("ICONS 键解析结果为空: " + path)
    return result


def die(msg):
    print("ERROR: " + msg, file=sys.stderr)
    sys.exit(1)


def main():
    turns = java_turns()
    print("Java  TURNS (%d): %s" % (len(turns), ", ".join(sorted(turns))))

    failed = False
    for path in BAND_UX:
        keys = band_keys(path)
        label = os.path.basename(os.path.dirname(os.path.dirname(path)))
        print("%s ICONS (%d): %s" % (label, len(keys), ", ".join(sorted(keys))))
        only_java = turns - keys
        only_band = keys - turns
        if only_java or only_band:
            failed = True
            if only_java:
                print("  ERROR: 手机端有、手环缺图标: %s" % ", ".join(sorted(only_java)),
                      file=sys.stderr)
            if only_band:
                print("  ERROR: 手环有图标、手机端未定义: %s" % ", ".join(sorted(only_band)),
                      file=sys.stderr)

    if failed:
        print("\n协议不一致：请同步 NavFrame.TURNS 与两端 index.ux 的 ICONS。", file=sys.stderr)
        sys.exit(1)
    print("\nOK: 手机端与 %d 个手环端的 turn 协议一致" % len(BAND_UX))


if __name__ == "__main__":
    main()
